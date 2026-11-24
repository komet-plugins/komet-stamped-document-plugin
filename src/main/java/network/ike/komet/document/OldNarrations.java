/*
 * Copyright © 2026 Knowledge Graphlet / IKE Network
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package network.ike.komet.document;

import design.knowledge.stamp.adoc.git.AnnotationCommit;
import design.knowledge.stamp.adoc.identity.GitIdentityAdapter;
import design.knowledge.stamp.adoc.model.Annotation;
import design.knowledge.stamp.adoc.model.AnnotationKind;
import design.knowledge.stamp.adoc.model.AnnotationTarget;
import design.knowledge.stamp.adoc.model.KonceptRef;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.service.ServiceKeys;
import dev.ikm.tinkar.common.service.ServiceProperties;
import dev.ikm.tinkar.common.util.uuid.UuidT5Generator;
import dev.ikm.tinkar.entity.Entity;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.StampEntity;
import dev.ikm.tinkar.entity.boundary.DataKind;
import dev.ikm.tinkar.entity.boundary.EntityBoundaries;
import dev.ikm.tinkar.entity.scope.ScopeOperations;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.terms.KernelPattern;
import network.ike.foundation.ike.bindings.IkePattern;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Moves the narrations written before 2026-11-08 as knowledge about documents saved in files into
 * the documents' repositories (IKE-Network/ike-issues#1313; the fourth question before step 2 of
 * {@code design-2026-11-22-knowledge-shared-local}, settled by KEC on 2026-11-22).
 *
 * <p>Before 2026-11-08 the narrator wrote the narration of a document's commit as knowledge: a
 * comment in the module <em>Narration</em> on the commit's STAMP. Knowledge that refers to a
 * document keeps the document from being removed, so such a document could not be dropped when a
 * knowledge base opens. Since then a narration is an annotation commit in the document's own
 * repository (IKE-Network/ike-issues#1355), which opening the document reads back. Each old
 * narration is written there in that form, with its narrator, time and text, unsigned, and then
 * erased from the store and from the knowledge change sets ({@link ScopeOperations#eraseKnowledge}),
 * the one exception to knowledge never being erased. A narration whose document, repository or
 * commit cannot be found is written with its text to a report beside the knowledge base, and erased.
 * One whose repository refuses the commit, as one with staged changes does, is left as it is, and
 * moved the next time.
 */
final class OldNarrations {

    /** The narrator's namespace, as the assistant plugin's {@code NarratorIdentity} has it. */
    private static final UUID NARRATOR_NAMESPACE = UUID.fromString("a7d3e8f0-2b14-4c96-9e5a-1f0c8b2d6e34");

    /** The module the narrator wrote its narrations under, as the assistant plugin's {@code NarratorIdentity} has it. */
    static final PublicId NARRATION_MODULE = PublicIds.of(UuidT5Generator.get(NARRATOR_NAMESPACE, "komet-narration-module"));

    /** The report of narrations whose document could not be found, in the knowledge base's folder. */
    static final String REPORT = "narrations-not-moved.txt";

    private OldNarrations() { }

    /**
     * What moving the old narrations did.
     *
     * @param moved    narrations written into their documents' repositories and erased
     * @param reported narrations written to the report and erased
     * @param report   the report, or {@code null} when none was written
     * @param left     narrations left as they are, each with why
     */
    record Moved(int moved, int reported, Path report, List<String> left) {

        static final Moved NONE = new Moved(0, 0, null, List.of());
    }

    /** One old narration: the comment, the STAMP it narrates, and what it says. */
    private record Old(long nid, PublicId id, long stampNid, String text, long authorNid, long time) { }

    /** Where a narrated commit is: its SHA and the document whose git source names that STAMP. */
    private record Source(String sha, PublicId anchor) { }

    /**
     * Moves every old narration of a document saved in a file.
     *
     * @return what was moved
     */
    static Moved move() {
        if (!PrimitiveData.get().hasPublicId(NARRATION_MODULE) || GitStampVocabulary.entityAbsent(IkePattern.GIT_SOURCE_PATTERN)) {
            return Moved.NONE;
        }
        long moduleNid = PrimitiveData.nid(NARRATION_MODULE);
        List<Old> found = new ArrayList<>();
        EntityService.get().forEachSemanticOfPattern(KernelPattern.COMMENT_PATTERN.nid(), comment -> old(comment, moduleNid).ifPresent(found::add));
        if (found.isEmpty()) {
            return Moved.NONE;
        }
        Map<Long, Source> sources = sources();
        List<Long> erase = new ArrayList<>();
        List<Long> reportedNids = new ArrayList<>();
        List<String> left = new ArrayList<>();
        StringBuilder report = new StringBuilder();
        int moved = 0;
        int reported = 0;
        for (Old old : found) {
            Source source = sources.get(old.stampNid());
            Optional<Path> root = source == null ? Optional.empty() : DocumentFiles.rootOf(source.anchor());
            if (source == null || root.isEmpty() || !hasCommit(root.get(), source.sha())) {
                report.append(entry(old, source));
                reportedNids.add(old.nid());
                reported++;
                continue;
            }
            try {
                if (!alreadyMoved(root.get(), old.id())) {
                    AnnotationCommit.Result written = DocumentCommit.annotate(root.get(), List.of(annotation(old, root.get(), source.sha())),
                            old.text().strip(), false, Map.of());
                    if (!written.ok()) {
                        left.add(DocumentScopes.name(source.anchor()) + ": " + written.output());
                        continue;
                    }
                }
                erase.add(old.nid());
                moved++;
            } catch (IOException | RuntimeException failed) {
                left.add(DocumentScopes.name(source.anchor()) + ": " + failed.getMessage());
            }
        }
        Path reportFile = null;
        if (!report.isEmpty()) {
            reportFile = writeReport(report.toString());
            if (reportFile == null) {
                // With nowhere to keep their text, the reported narrations stay as they are.
                left.add(reported + " narration(s) whose document cannot be found: the knowledge base has no folder for the report");
                reported = 0;
            } else {
                erase.addAll(reportedNids);
            }
        }
        if (!erase.isEmpty()) {
            try {
                ScopeOperations.eraseKnowledge(erase.stream().mapToLong(Long::longValue).toArray());
            } catch (IllegalArgumentException | UnsupportedOperationException refused) {
                left.add("the moved narrations were not erased: " + refused.getMessage());
            }
        }
        return new Moved(moved, reported, reportFile, List.copyOf(left));
    }

    /** A comment written under the Narration module on the STAMP of local data saved in a file. */
    private static Optional<Old> old(SemanticEntity<SemanticEntityVersion> comment, long moduleNid) {
        SemanticEntityVersion first = null;
        for (SemanticEntityVersion version : comment.versions()) {
            if (version.time() != Long.MAX_VALUE && (first == null || version.time() < first.time())) {
                first = version;
            }
        }
        if (first == null || first.stamp().moduleNid() != moduleNid) {
            return Optional.empty();
        }
        long target = comment.referencedComponentNid();
        if (!(EntityHandle.get(target).orNull() instanceof StampEntity<?>)) {
            return Optional.empty();
        }
        if (EntityBoundaries.stampKind(target).kind() != DataKind.FILE) {
            return Optional.empty();
        }
        String text = first.fieldValues().isEmpty() ? "" : String.valueOf(first.fieldValues().get(0));
        return Optional.of(new Old(comment.nid(), comment.publicId(), target, text, first.stamp().authorNid(), first.time()));
    }

    /** Every commit a document's git source names, by the STAMP it was written under. */
    private static Map<Long, Source> sources() {
        Map<Long, Source> found = new HashMap<>();
        EntityService.get().forEachSemanticOfPattern(IkePattern.GIT_SOURCE_PATTERN.nid(), source -> {
            Entity<?> document = EntityHandle.get(source.referencedComponentNid()).orNull();
            if (document == null) {
                return;
            }
            for (SemanticEntityVersion version : source.versions()) {
                if (version.time() != Long.MAX_VALUE && version.fieldValues().size() > 1) {
                    found.putIfAbsent(version.stampNid(), new Source(String.valueOf(version.fieldValues().get(1)), document.publicId()));
                }
            }
        });
        return found;
    }

    /** The narration as an annotation commit's comment on the narrated commit, by its narrator, at its time. */
    private static Annotation annotation(Old old, Path root, String sha) throws IOException {
        Entity<?> author = EntityHandle.get(old.authorNid()).expectEntity();
        String name = StampNames.name(Calculators.View.Default(), old.authorNid());
        return new Annotation(AnnotationKind.COMMENT.ref(),
                new AnnotationTarget.ChangeSet(new KonceptRef(GitIdentityAdapter.fromMailmap(root).stamp(sha).uuid(), sha.substring(0, 7))),
                new KonceptRef(author.publicId().asUuidArray()[0], name), Instant.ofEpochMilli(old.time()), old.id().asUuidArray()[0]);
    }

    /** Whether the repository holds the commit. */
    private static boolean hasCommit(Path root, String sha) {
        return git(root, "cat-file", "-e", sha + "^{commit}").isPresent();
    }

    /** Whether an annotation commit already carries the narration, as one a move that stopped before erasing left. */
    private static boolean alreadyMoved(Path root, PublicId id) {
        return git(root, "log", "--all", "--format=%H", "--fixed-strings", "--grep=" + id.asUuidArray()[0])
                .filter(out -> !out.isBlank()).isPresent();
    }

    private static Optional<String> git(Path root, String... args) {
        List<String> command = new ArrayList<>(List.of("git", "-C", root.toString()));
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            return process.waitFor() == 0 ? Optional.of(out) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** One narration in the report: when, by whom, about what, and its text. */
    private static String entry(Old old, Source source) {
        return Instant.ofEpochMilli(old.time()) + " · " + StampNames.name(Calculators.View.Default(), old.authorNid())
                + (source == null ? "" : " · " + DocumentScopes.name(source.anchor()) + " @ " + source.sha())
                + " · " + old.id().idString() + "\n\n" + old.text().strip() + "\n\n";
    }

    /** Appends to the report beside the knowledge base; {@code null} when the knowledge base has no folder. */
    private static Path writeReport(String text) {
        Optional<java.io.File> folder = ServiceProperties.get(ServiceKeys.DATA_STORE_ROOT);
        if (folder.isEmpty() || !folder.get().isDirectory()) {
            return null;
        }
        Path report = folder.get().toPath().resolve(REPORT);
        try {
            if (!Files.exists(report)) {
                Files.writeString(report, "Narrations of documents saved in files, written before 2026-11-08, whose document,"
                        + " repository or commit Komet could not find when it moved them into the documents' repositories"
                        + " (IKE-Network/ike-issues#1313).\n\n", StandardCharsets.UTF_8);
            }
            Files.writeString(report, text, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            return report;
        } catch (IOException e) {
            return null;
        }
    }
}

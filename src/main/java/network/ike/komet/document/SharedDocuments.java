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

import design.knowledge.stamp.adoc.git.GitRepo;
import design.knowledge.stamp.adoc.model.AnnotationKind;
import dev.ikm.tinkar.common.id.LongIds;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.service.ScopeErasure;
import dev.ikm.tinkar.common.service.ServiceKeys;
import dev.ikm.tinkar.common.service.ServiceProperties;
import dev.ikm.tinkar.coordinate.edit.EditCoordinate;
import dev.ikm.tinkar.entity.Entity;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.EntityVersion;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.boundary.DataKind;
import dev.ikm.tinkar.entity.boundary.EntityBoundaries;
import dev.ikm.tinkar.entity.boundary.References;
import dev.ikm.tinkar.entity.scope.KindMove;
import dev.ikm.tinkar.entity.scope.Scope;
import dev.ikm.tinkar.entity.scope.ScopeOperations;
import dev.ikm.tinkar.entity.scope.SharedFolder;
import dev.ikm.tinkar.entity.transaction.StampedWriter;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.EntityProxy;
import dev.ikm.tinkar.terms.KernelConcept;
import dev.ikm.tinkar.terms.State;
import network.ike.foundation.ike.bindings.IkePattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Shared documents: sharing one, making it local again, and deleting it for everyone who syncs the
 * knowledge base (IKE-Network/ike-issues#1313; step 4 of {@code design-2026-11-22-knowledge-shared-local},
 * its questions settled by KEC on 2026-11-23).
 *
 * <p>A shared document's file stays its record. Whoever has the file edits and commits it, and the
 * shared copy follows each commit. Everyone else reads the document and can comment on it, but not edit
 * its text, so nothing has to be merged.
 */
public final class SharedDocuments {

    private static final Logger LOG = LoggerFactory.getLogger(SharedDocuments.class);

    private SharedDocuments() {
    }

    /** Why a document was not shared, made local, deleted or commented on, in words for the person. Nothing was changed. */
    public static final class Refused extends IllegalStateException {
        Refused(String reason) {
            super(reason);
        }
    }

    /**
     * What an act did.
     *
     * @param anchors the documents it was done to
     * @param report  what was done, a sentence or two for the person
     */
    public record Done(List<PublicId> anchors, String report) { }

    // ── what a document is ───────────────────────────────────────────────────

    /**
     * @param anchor a document's anchor
     * @return whether it is shared
     */
    public static boolean shared(PublicId anchor) {
        return kind(anchor) == DataKind.SHARED;
    }

    /**
     * @param anchor a document's anchor
     * @return whether it is kept in this knowledge base alone, as a shared document made local is where its file is not
     */
    public static boolean keptHereOnly(PublicId anchor) {
        return kind(anchor) == DataKind.LOCAL && VertexReader.isVertexDocument(anchor)
                && GitDocumentMaterializer.recordedPath(anchor).isPresent();
    }

    /** The kind of data a document is; knowledge when the knowledge base does not hold it. */
    static DataKind kind(PublicId anchor) {
        return PrimitiveData.get().hasPublicId(anchor) && EntityHandle.get(anchor).entity().isPresent()
                ? EntityBoundaries.kindOf(EntityService.get().nidForPublicId(anchor)) : DataKind.KNOWLEDGE;
    }

    /**
     * @param anchor a document's anchor
     * @return whether its record is not a file on this computer, so its text is not edited here: a shared document
     *         whose file is elsewhere, or one kept in this knowledge base alone
     */
    public static boolean readOnly(PublicId anchor) {
        return shared(anchor) && DocumentFiles.rootOf(anchor).isEmpty() || keptHereOnly(anchor);
    }

    /**
     * Refuses an edit of the text of a shared document whose file is not on this computer.
     *
     * @param anchor the document's anchor
     * @throws IllegalArgumentException when its text is not edited here
     */
    static void requireEditable(PublicId anchor) {
        if (readOnly(anchor)) {
            throw new IllegalArgumentException(DocumentScopes.name(anchor) + (shared(anchor) ? " is shared, and its file is not on this computer"
                    : " is kept only in this knowledge base, with no file") + ", so its text is not edited here; you can comment on it");
        }
    }

    /**
     * The documents that sharing some takes along: those, the documents they include, and the documents
     * anything of theirs refers to, at any depth, since shared data never refers to local data and a
     * document whose includes are missing reads as a document with holes.
     *
     * @param anchors the documents' anchors
     * @return their anchors and those of the documents they take along
     */
    public static List<PublicId> withThoseItRefersTo(Collection<PublicId> anchors) {
        Map<Long, PublicId> byModule = new HashMap<>();
        GitDocumentMaterializer.storedDocumentsByPath().values().forEach(list -> list.forEach(anchor ->
                byModule.put(moduleOf(anchor), anchor)));
        Set<PublicId> all = new LinkedHashSet<>();
        List<PublicId> queue = new ArrayList<>(anchors);
        while (!queue.isEmpty()) {
            PublicId anchor = queue.removeFirst();
            if (!all.add(anchor)) {
                continue;
            }
            queue.addAll(includedBy(anchor));
            Scope scope = DocumentScopes.of(anchor);
            for (long member : scope.members().toArray()) {
                EntityHandle.get(member).entity().ifPresent(entity -> entity.versions().forEach(version ->
                        References.of((EntityVersion) version, (where, target) -> {
                            if (target != 0 && !scope.contains(target) && EntityBoundaries.kindOf(target) == DataKind.FILE) {
                                PublicId other = byModule.get(moduleOf(target));
                                if (other != null && !all.contains(other)) {
                                    queue.add(other);
                                }
                            }
                        })));
            }
        }
        return List.copyOf(all);
    }

    /**
     * The stored documents a document includes, at any depth, found as the card finds them: by the
     * include lines of its text, in the working tree its file is in and those beside it.
     *
     * @param anchor the document's anchor
     * @return the included documents' anchors, the document itself not among them
     */
    static List<PublicId> includedBy(PublicId anchor) {
        Optional<GitDocumentMaterializer.StoredDocument> host = GitDocumentMaterializer.load(anchor);
        Optional<String> path = GitDocumentMaterializer.recordedPath(anchor);
        if (host.isEmpty() || path.isEmpty()) {
            return List.of();
        }
        List<Path> trees = new ArrayList<>();
        DocumentFiles.rootOf(anchor).ifPresent(root -> {
            trees.add(root);
            trees.addAll(WorkingTrees.beside(root).trees());
        });
        Map<String, List<PublicId>> byPath = GitDocumentMaterializer.storedDocumentsByPath();
        java.util.function.Function<String, Optional<GitDocumentMaterializer.StoredDocument>> store = wanted -> {
            for (Path tree : trees) {
                PublicId under = GitDocumentMaterializer.documentAnchor(tree, wanted);
                if (!PublicId.equals(under, anchor) && kind(under) != DataKind.KNOWLEDGE) {
                    return GitDocumentMaterializer.load(under);
                }
            }
            return trees.isEmpty() ? byPath.getOrDefault(wanted, List.of()).stream().filter(other -> !PublicId.equals(other, anchor))
                    .findFirst().flatMap(GitDocumentMaterializer::load) : Optional.empty();
        };
        return DocumentAssembly.of(host.get(), path.get(), store, rest -> IncludeResolver.endingWith(byPath.keySet(), rest)).members().stream()
                .map(member -> member.document().anchor()).filter(member -> !PublicId.equals(member, anchor)).distinct().toList();
    }

    /** Refuses when a shared document outside the given ones includes one of them: it would be left with a hole. */
    private static void requireNotIncluded(List<PublicId> anchors) {
        for (List<PublicId> others : GitDocumentMaterializer.storedDocumentsByPath().values()) {
            for (PublicId other : others) {
                if (anchors.stream().anyMatch(anchor -> PublicId.equals(anchor, other)) || !shared(other)) {
                    continue;
                }
                for (PublicId included : includedBy(other)) {
                    if (anchors.stream().anyMatch(anchor -> PublicId.equals(anchor, included))) {
                        throw new Refused(DocumentScopes.name(other) + ", which is shared, includes " + DocumentScopes.name(included)
                                + ". Make that one local or delete it first, or both together.");
                    }
                }
            }
        }
    }

    // ── share, make local, delete ────────────────────────────────────────────

    /**
     * Shares documents with everyone who syncs the knowledge base, with the documents they refer to.
     * They go to the others when the knowledge base is next synced.
     *
     * @param anchors the documents' anchors
     * @param edit    the edit coordinate of the person who shares them
     * @return what was done
     * @throws Refused why nothing was changed
     */
    public static Done share(Collection<PublicId> anchors, EditCoordinate edit) {
        return share(anchors, edit, folder());
    }

    /** Shares documents through the given folder of shared data. */
    static Done share(Collection<PublicId> anchors, EditCoordinate edit, Path folder) {
        List<PublicId> all = withThoseItRefersTo(requireSome(anchors)).stream().filter(anchor -> !shared(anchor)).toList();
        if (all.isEmpty()) {
            throw new Refused(named(anchors) + (anchors.size() == 1 ? " is" : " are") + " shared already.");
        }
        for (PublicId anchor : all) {
            requireSettled(anchor);
            if (EntityBoundaries.kindOf(EntityService.get().nidForPublicId(anchor)) != DataKind.FILE) {
                throw new Refused(DocumentScopes.name(anchor) + " is not saved in a file.");
            }
            if (VertexMaterializer.textRevision(GitDocumentExporter.export(anchor, false).map(GitDocumentExporter.Exported::commit).orElse(""))) {
                throw new Refused(DocumentScopes.name(anchor) + " is in a folder without git, so the shared copy could not follow it."
                        + " More › Start Git History makes the folder a repository.");
            }
            if (DocumentFiles.rootOf(anchor).isEmpty()) {
                throw new Refused("Komet cannot tell where the file of " + DocumentScopes.name(anchor) + " is, and the file is its record."
                        + " Open the file, then share the document.");
            }
        }
        Scope scope = Scope.of(roots(all));
        move(scope, KernelConcept.SHARED_MODULE, edit);
        for (PublicId anchor : all) {
            SharedFolder.unmark(folder, moduleId(anchor));
        }
        LOG.info("Shared {} document(s): {}", all.size(), all.stream().map(DocumentScopes::name).toList());
        return new Done(all, named(all) + (all.size() == 1 ? " is" : " are") + " shared with everyone who syncs this knowledge base, when it is next synced.");
    }

    /**
     * Makes shared documents local: everyone else who syncs the knowledge base loses them, and they are
     * kept here, as documents saved in files where their files are here, and as local data where they are not.
     *
     * @param anchors the documents' anchors
     * @param edit    the edit coordinate of the person
     * @param by      the person's name, for the marker
     * @return what was done
     * @throws Refused why nothing was changed
     */
    public static Done makeLocal(Collection<PublicId> anchors, EditCoordinate edit, String by) {
        return makeLocal(anchors, edit, by, folder());
    }

    /** Makes shared documents local through the given folder of shared data. */
    static Done makeLocal(Collection<PublicId> anchors, EditCoordinate edit, String by, Path folder) {
        List<PublicId> all = requireShared(anchors);
        List<PublicId> withFile = all.stream().filter(anchor -> DocumentFiles.rootOf(anchor).isPresent()).toList();
        List<PublicId> without = all.stream().filter(anchor -> DocumentFiles.rootOf(anchor).isEmpty()).toList();
        if (!withFile.isEmpty() && !without.isEmpty()) {
            throw new Refused("The files of some of them are on this computer and those of others are not. Make them local one at a time.");
        }
        requireNotIncluded(all);
        Map<PublicId, Marker> markers = markers(all);
        move(Scope.of(roots(all)), withFile.isEmpty() ? KernelConcept.LOCAL_MODULE : KernelConcept.FILE_MODULE, edit);
        mark(folder, markers, SharedFolder.Act.MADE_LOCAL, by);
        return new Done(all, named(all) + (all.size() == 1 ? " is" : " are") + " local again, and everyone else who syncs this knowledge base loses "
                + (all.size() == 1 ? "it" : "them") + " at their next sync.");
    }

    /**
     * Deletes shared documents for everyone who syncs the knowledge base, or documents kept in this
     * knowledge base alone. Their files, where they are on someone's computer, stay where they are; the
     * folder's git history still holds what was shared.
     *
     * @param anchors the documents' anchors
     * @param by      the person's name, for the marker
     * @return what was done
     * @throws Refused why nothing was changed
     */
    public static Done delete(Collection<PublicId> anchors, String by) {
        return delete(anchors, by, anchors.stream().allMatch(SharedDocuments::keptHereOnly) ? null : folder());
    }

    /** Deletes shared documents through the given folder of shared data. */
    static Done delete(Collection<PublicId> anchors, String by, Path folder) {
        boolean here = requireSome(anchors).stream().allMatch(SharedDocuments::keptHereOnly);
        List<PublicId> all = here ? List.copyOf(new LinkedHashSet<>(anchors)) : requireShared(anchors);
        if (!here) {
            requireNotIncluded(all);
        }
        Map<PublicId, Marker> markers = markers(all);
        Scope scope = Scope.of(roots(all));
        if (!scope.deletable()) {
            throw new Refused("Something else refers to " + named(all) + ", so nothing was deleted. The first is "
                    + DocumentScopes.referrer(scope.incoming().getFirst()) + ".");
        }
        try {
            ScopeOperations.delete(scope);
        } catch (IllegalStateException cited) {
            throw new Refused("Something else refers to " + named(all) + ", so nothing was deleted: " + cited.getMessage());
        }
        if (here) {
            return new Done(all, String.join(", ", markers.values().stream().map(Marker::name).toList()) + (all.size() == 1 ? " is" : " are") + " deleted.");
        }
        mark(folder, markers, SharedFolder.Act.DELETED, by);
        return new Done(all, String.join(", ", markers.values().stream().map(Marker::name).toList())
                + (all.size() == 1 ? " is" : " are") + " deleted, and everyone else who syncs this knowledge base loses "
                + (all.size() == 1 ? "it" : "them") + " at their next sync.");
    }

    /** What a marker needs of a document, read before the document goes. */
    private record Marker(UUID module, String name) { }

    private static Map<PublicId, Marker> markers(List<PublicId> anchors) {
        Map<PublicId, Marker> markers = new java.util.LinkedHashMap<>();
        for (PublicId anchor : anchors) {
            markers.put(anchor, new Marker(moduleId(anchor), PrimitiveData.text(moduleOf(anchor))));
        }
        return markers;
    }

    private static void mark(Path folder, Map<PublicId, Marker> markers, SharedFolder.Act act, String by) {
        Instant now = Instant.now();
        for (Marker marker : markers.values()) {
            SharedFolder.mark(folder, marker.module(), marker.name(), act, by, now);
            LOG.info("{} was {} by {}", marker.name(), act == SharedFolder.Act.DELETED ? "deleted" : "made local", by);
        }
    }

    private static void move(Scope scope, ConceptFacade parent, EditCoordinate edit) {
        try {
            KindMove.move(scope, parent, EntityProxy.Concept.make(edit.getAuthorNidForChanges()));
        } catch (KindMove.Refused refused) {
            throw new Refused(refused.getMessage());
        }
    }

    // ── a comment without the file ───────────────────────────────────────────

    /**
     * Comments on lines of a shared document whose file is not on this computer. The comment is the
     * person's, written under the document's module, so it goes to everyone the document goes to. Where the
     * file is here a comment is a commit in its repository, as it is for any document.
     *
     * @param anchor the document's anchor
     * @param lines  the nids of the lines
     * @param text   the comment
     * @param edit   the edit coordinate of the person
     * @return the comment's identity
     * @throws Refused why nothing was written
     */
    public static PublicId comment(PublicId anchor, List<Long> lines, String text, EditCoordinate edit) {
        if (!readOnly(anchor)) {
            throw new Refused("A comment on this document is a commit in its repository.");
        }
        if (text == null || text.isBlank()) {
            throw new Refused("A comment needs text.");
        }
        if (lines.isEmpty()) {
            throw new Refused("Select the lines to comment on.");
        }
        long root = EntityService.get().nidForPublicId(anchor);
        List<SemanticEntity<SemanticEntityVersion>> sources = EntityService.get()
                .semanticsForComponentOfPattern(root, IkePattern.GIT_SOURCE_PATTERN.nid()).toList();
        if (sources.isEmpty()) {
            throw new Refused("The document has no source to anchor a comment to.");
        }
        long reviewed = GitDocumentMaterializer.reviewedStampNid(EntityProxy.Semantic.make(sources.getFirst().publicId()), "");
        long module = moduleOf(anchor);
        PublicId comment = PublicIds.newRandom();
        try (StampedWriter writer = StampedWriter.open("comment on " + DocumentScopes.name(anchor), State.ACTIVE,
                EntityProxy.Concept.make(edit.getAuthorNidForChanges()),
                EntityProxy.Concept.make(PrimitiveData.text(module), PrimitiveData.publicId(module)), KernelConcept.DEVELOPMENT_PATH)) {
            writer.semantic(comment, IkePattern.ANNOTATION_PATTERN.publicId(), anchor,
                    StampedDocumentTerms.kind(AnnotationKind.COMMENT), EntityProxy.make(reviewed),
                    LongIds.list.of(lines.stream().mapToLong(Long::longValue).toArray()), text.strip(),
                    StampedDocumentTerms.textRange(lines.size(), List.of()));
            writer.commit();
        }
        return comment;
    }

    /**
     * How many comments on documents are in the knowledge base alone: those made where the file was not.
     * Such a comment has a random identity; one read from a repository has an identity derived from its commit.
     *
     * @param anchors the documents' anchors
     * @return the number of comments no file holds
     */
    static int commentsInNoFile(Collection<PublicId> anchors) {
        int comments = 0;
        for (PublicId anchor : anchors) {
            comments += (int) inNoFile(anchor).count();
        }
        return comments;
    }

    /**
     * @param anchor     the document's anchor
     * @param annotation an annotation on one of its lines
     * @return whether the annotation is in the knowledge base alone, so that no commit recorded it
     */
    static boolean inNoFile(PublicId anchor, GitDocumentMaterializer.StoredAnnotation annotation) {
        return annotation.stamp() != null && inNoFile(anchor).anyMatch(comment -> comment.versions().anySatisfy(
                version -> version.stampNid() == annotation.stamp().nid()));
    }

    private static Stream<SemanticEntity<SemanticEntityVersion>> inNoFile(PublicId anchor) {
        return EntityService.get().semanticsForComponentOfPattern(EntityService.get().nidForPublicId(anchor), IkePattern.ANNOTATION_PATTERN.nid())
                .filter(annotation -> annotation.publicId().asUuidArray()[0].version() == 4);
    }

    // ── following the file ───────────────────────────────────────────────────

    /**
     * Brings the shared documents whose files are on this computer up to date with them: the commits the
     * knowledge base lacks are written, as opening the file writes them. Called before a sync sends.
     *
     * @return how many documents had something new
     */
    public static int bringUpToDate() {
        int brought = 0;
        for (List<PublicId> anchors : GitDocumentMaterializer.storedDocumentsByPath().values()) {
            for (PublicId anchor : anchors) {
                if (!shared(anchor) || DocumentEditor.pending(anchor).isPresent()) {
                    continue;
                }
                Optional<Path> root = DocumentFiles.rootOf(anchor);
                Optional<String> path = GitDocumentMaterializer.recordedPath(anchor);
                if (root.isEmpty() || path.isEmpty() || GitRepo.findRoot(root.get()).isEmpty()) {
                    continue;
                }
                try {
                    if (GitDocumentMaterializer.materialize(root.get(), root.get().resolve(path.get()), true).commits() > 0) {
                        brought++;
                    }
                } catch (java.io.IOException | RuntimeException failed) {
                    LOG.warn("Could not bring the shared copy of {} up to date with its file", path.get(), failed);
                }
            }
        }
        if (brought > 0) {
            LOG.info("Brought {} shared document(s) up to date with their files", brought);
        }
        return brought;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Collection<PublicId> requireSome(Collection<PublicId> anchors) {
        if (anchors.isEmpty()) {
            throw new Refused("No document was named.");
        }
        for (PublicId anchor : anchors) {
            if (!PrimitiveData.get().hasPublicId(anchor)) {
                throw new Refused("The document is not in the knowledge base.");
            }
        }
        return anchors;
    }

    private static List<PublicId> requireShared(Collection<PublicId> anchors) {
        List<PublicId> all = List.copyOf(new LinkedHashSet<>(requireSome(anchors)));
        for (PublicId anchor : all) {
            if (!shared(anchor)) {
                throw new Refused(DocumentScopes.name(anchor) + " is not shared.");
            }
            requireSettled(anchor);
        }
        return all;
    }

    private static void requireSettled(PublicId anchor) {
        if (DocumentEditor.pending(anchor).isPresent()) {
            throw new Refused("An edit of " + DocumentScopes.name(anchor) + " is not committed. Commit it or discard it first.");
        }
    }

    /** The knowledge base's folder of shared data. */
    private static Path folder() {
        if (!SharedFolder.declared()) {
            throw new Refused("This knowledge base's starter set does not have the Shared module; load the current starter set first.");
        }
        Optional<File> root = ServiceProperties.get(ServiceKeys.DATA_STORE_ROOT);
        if (root.isEmpty() || !(PrimitiveData.get() instanceof ScopeErasure erasure)
                || erasure.newChangeSet(KernelConcept.SHARED_MODULE.nid()).isEmpty()) {
            throw new Refused("This knowledge base is in memory only, so it has no folder to sync.");
        }
        return SharedFolder.of(root.get().toPath());
    }

    private static long[] roots(List<PublicId> anchors) {
        return anchors.stream().mapToLong(DocumentScopes::rootNid).toArray();
    }

    /** The module a component was first written under. */
    private static long moduleOf(long nid) {
        Entity<? extends EntityVersion> entity = EntityHandle.get(nid).orNull();
        return entity == null ? 0 : entity.versions().stream().map(version -> (EntityVersion) version)
                .min(java.util.Comparator.comparingLong(EntityVersion::time))
                .map(version -> EntityBoundaries.moduleOf(version.stampNid())).orElse(0L);
    }

    private static long moduleOf(PublicId anchor) {
        return moduleOf(EntityService.get().nidForPublicId(anchor));
    }

    private static UUID moduleId(PublicId anchor) {
        return PrimitiveData.publicId(moduleOf(anchor)).asUuidArray()[0];
    }

    private static String named(Collection<PublicId> anchors) {
        List<PublicId> all = List.copyOf(anchors);
        return all.size() == 1 ? DocumentScopes.name(all.getFirst())
                : DocumentScopes.name(all.getFirst()) + " and " + (all.size() - 1) + " more";
    }
}

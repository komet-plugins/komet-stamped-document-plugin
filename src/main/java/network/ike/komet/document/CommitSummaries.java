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

import design.knowledge.stamp.adoc.model.AnnotationKind;
import design.knowledge.stamp.adoc.model.StampRecord;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.StampEntity;
import network.ike.foundation.ike.bindings.IkePattern;
import network.ike.komet.document.GitDocumentMaterializer.StoredAnnotation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the repository says about the commits a stamped document's STAMPs stand for: the
 * short SHA, the subject and the committer of each, read from the repository when the card has one
 * open (ike-lab-documents topic {@code dev-stamped-document-surface}). The store records only the
 * SHA, in the git-source semantic's version under each commit's STAMP; the subject and the
 * committer are git's.
 *
 * @param shaByStamp the commit SHA of each STAMP nid the document's git source was versioned under
 * @param records    the file's STAMP records by commit SHA, empty when no repository was read
 * @param committers who committed the repository's commits, {@link Committers#NONE} when none was read
 */
public record CommitSummaries(Map<Long, String> shaByStamp, Map<String, StampRecord> records, Committers committers) {

    private static final Logger LOG = LoggerFactory.getLogger(CommitSummaries.class);

    /** Nothing known. */
    public static final CommitSummaries NONE = new CommitSummaries(Map.of(), Map.of(), Committers.NONE);

    /**
     * Reads the SHAs from the store and, when a working tree holding the file is given, the
     * commit records and the committers from the repository. Never throws: a repository that cannot
     * be read leaves them empty.
     *
     * @param anchor   the document anchor
     * @param repoRoot the working tree, or {@code null}
     * @param relative the file's path within it, or {@code null}
     * @return the summaries
     */
    public static CommitSummaries read(PublicId anchor, Path repoRoot, Path relative) {
        Map<Long, String> shas = new HashMap<>();
        long anchorNid = EntityService.get().nidForPublicId(anchor);
        for (SemanticEntity<SemanticEntityVersion> s : EntityService.get()
                .semanticsForComponentOfPattern(anchorNid, IkePattern.GIT_SOURCE_PATTERN.nid()).toList()) {
            for (SemanticEntityVersion v : s.versions()) {
                if (!v.canceled() && v.fieldValues().size() > 1) {
                    shas.put(v.stamp().nid(), String.valueOf(v.fieldValues().get(1)));
                }
            }
        }
        Map<String, StampRecord> records = new HashMap<>();
        Committers committers = Committers.NONE;
        if (repoRoot != null && relative != null && Files.isRegularFile(repoRoot.resolve(relative))) {
            try {
                GitStampBridge bridge = GitStampBridge.read(repoRoot, relative);
                for (StampRecord r : bridge.stamps().stamps().values()) {
                    records.put(r.commitSha(), r);
                }
            } catch (IOException | RuntimeException e) {
                LOG.info("Commit summaries of {} not read from {}: {}", relative, repoRoot, e.getMessage());
            }
            committers = Committers.read(repoRoot);
        }
        return new CommitSummaries(Map.copyOf(shas), Map.copyOf(records), committers);
    }

    /**
     * The summaries of several documents as one, for a surface that shows an assembly: every
     * member's STAMPs and records together.
     *
     * @param all the summaries to merge
     * @return one summaries over all of them
     */
    public static CommitSummaries merge(java.util.Collection<CommitSummaries> all) {
        Map<Long, String> shas = new HashMap<>();
        Map<String, StampRecord> records = new HashMap<>();
        for (CommitSummaries s : all) {
            shas.putAll(s.shaByStamp());
            records.putAll(s.records());
        }
        return new CommitSummaries(Map.copyOf(shas), Map.copyOf(records),
                Committers.merge(all.stream().map(CommitSummaries::committers).toList()));
    }

    /** @return the commit SHA the STAMP stands for, if the store recorded one */
    public Optional<String> sha(long stampNid) {
        return Optional.ofNullable(shaByStamp.get(stampNid));
    }

    /** @return the commit's record, if the repository was read and holds it */
    public Optional<StampRecord> record(long stampNid) {
        return sha(stampNid).map(records::get);
    }

    /** @return "abc1234  subject" for the STAMP's commit, as much of it as is known */
    public Optional<String> summary(long stampNid) {
        Optional<String> sha = sha(stampNid);
        if (sha.isEmpty()) {
            return Optional.empty();
        }
        String shortSha = sha.get().substring(0, Math.min(7, sha.get().length()));
        StampRecord record = records.get(sha.get());
        return Optional.of(record == null ? shortSha : shortSha + "  " + record.summary());
    }

    /**
     * The commits that recorded an annotation, as a retraction names one. The store has a SHA only for a commit
     * that changed the file, and an annotation's commit changes none, so it is found in the repository by the
     * author and time its STAMP has ({@link Committers#commits}). Where the author has several commits in that
     * second, the ones that the file's own history says made an annotation of the kind are meant.
     *
     * @param annotation the annotation
     * @return the SHAs, one unless several commits in that second made such an annotation; empty when the
     *         repository was not read or does not contain the commit
     */
    public List<String> annotationCommits(StoredAnnotation annotation) {
        List<String> commits = committers.commits(annotation.stamp());
        if (commits.size() < 2) {
            return commits;
        }
        List<String> annotating = commits.stream().filter(sha -> records.containsKey(sha) && annotates(records.get(sha), annotation.kind())).toList();
        return annotating.isEmpty() ? commits : annotating;
    }

    /** Whether a commit annotates a line of the file with a kind; a kind the engine does not know leaves no trace in the record. */
    private static boolean annotates(StampRecord commit, AnnotationKind kind) {
        return kind != null && switch (kind) {
            case AFFIRMATION -> !commit.affirmed().isEmpty();
            case REFUTATION -> !commit.refuted().isEmpty();
            case COMMENT -> !commit.commented().isEmpty();
            case RETRACTION -> false;
        };
    }

    /**
     * The commits that retracted an annotation, found as {@link #annotationCommits} finds the one that recorded
     * it. A retraction leaves no mark on a line, so a commit that does is not meant.
     *
     * @param annotation the annotation
     * @return the SHAs; empty when it is in force, and when the repository was not read or does not contain the commit
     */
    List<String> retractionCommits(StoredAnnotation annotation) {
        return committers.commits(annotation.retractedBy()).stream().filter(sha -> !records.containsKey(sha)).toList();
    }

    /**
     * Who committed the STAMP's commit, as review says it.
     *
     * @param view  the view that names the responsible person
     * @param stamp the STAMP
     * @return the committer; empty when it is the author, or is not known
     */
    public Optional<Committers.Committed> committed(ViewCalculator view, StampEntity<?> stamp) {
        return stamp == null ? Optional.empty() : committers.of(view, sha(stamp.nid()), stamp);
    }

    /**
     * Whether the STAMP's commit is marked, as {@link #committed} would say.
     *
     * @param stamp the STAMP
     * @return whether it is an assistant's work that its person did not commit
     */
    boolean committerDiffers(StampEntity<?> stamp) {
        return stamp != null && committers.differs(sha(stamp.nid()), stamp);
    }

    /**
     * What a tooltip says of a STAMP's commit under its author: the committer, then the short SHA and subject,
     * each on a line of its own.
     *
     * @param view  the view that names the responsible person
     * @param stamp the STAMP
     * @return the lines, each with a line break before it; empty when nothing is known
     */
    String lines(ViewCalculator view, StampEntity<?> stamp) {
        return committed(view, stamp).map(committed -> "\n" + committed.text()).orElse("")
                + summary(stamp.nid()).map(summary -> "\n" + summary).orElse("");
    }
}

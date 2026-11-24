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
import design.knowledge.stamp.adoc.identity.GitIdentityAdapter;
import design.knowledge.stamp.adoc.identity.Identity;
import design.knowledge.stamp.adoc.model.Person;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.StampEntity;
import dev.ikm.tinkar.terms.ConceptFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Who committed the commits a document's STAMPs stand for (IKE-Network/ike-issues#1399, decision 7). Git
 * records an author and a committer on every commit. A STAMP has the author alone, so the committer is read
 * from the repository, as a commit's subject is ({@link CommitSummaries}); nothing is written to the store,
 * and with no working tree no committer is known.
 *
 * <p>A commit of an assistant's work is to have the person responsible for it as committer. The person is
 * the one the store records ({@link ResponsiblePersons}), so the two say the same thing from two records,
 * and review shows both. A commit of an assistant's work that anyone else committed, the assistant itself
 * included, is marked ({@link Committed#differs()}). A committer that is an earlier identity of the person
 * counts as the person, since the person is responsible for that identity too; its address is shown. A commit
 * by a person, or by an author with no record, is never marked: its committer is shown when it is not the
 * author, as after a rebase by someone else.
 */
public final class Committers {

    private static final Logger LOG = LoggerFactory.getLogger(Committers.class);

    /** No committer known. */
    public static final Committers NONE = new Committers(Map.of(), Map.of());

    /** Each working tree's log, read again when HEAD moves: an assembly reads one tree once for each of its files. */
    private static final Map<Path, Log> LOGS = new ConcurrentHashMap<>();

    /** The commits reachable from HEAD, as git records their people. */
    private record Log(String head, List<GitOperations.CommitPeople> commits) { }

    /** How a commit's committer stands to its author. */
    private enum Relation {
        /** The committer is the author, who is no one's assistant. */
        AUTHOR,
        /** The author is an assistant, and the committer is the person responsible for its work. */
        PERSON,
        /** The author is an assistant, and the committer is an earlier identity of the person responsible. */
        EARLIER_IDENTITY,
        /** The author is no one's assistant, and someone else committed. */
        ANOTHER,
        /** The author is an assistant, and the committer is not the person responsible for its work. */
        DIFFERS
    }

    /** One commit's committer, the responsible person being the nid of a concept where the author is an assistant. */
    private record Verdict(Relation relation, Identity committer, long personNid) { }

    /** What a commit's STAMP has of it: the author and the moment. A rebase keeps both, where the SHA changes. */
    private record Authored(long authorNid, long time) { }

    /**
     * What review says of a commit's committer.
     *
     * @param name    the committer's name alone, for a column of a table
     * @param who     the committer, with the address where the name is not enough, and for a marked commit the
     *                person it is not
     * @param differs whether the commit is marked: an assistant's work that its person did not commit
     */
    public record Committed(String name, String who, boolean differs) {

        /** @return {@code Committed by Keith E Campbell}, as a line of a popover or a tooltip */
        public String text() {
            return "Committed by " + who;
        }

        /** The mark's word in a margin, where {@link #text()} is its tooltip. */
        public static final String MARK = "committer";
        /** The mark's colour, the one an uncommitted edit has. */
        public static final String MARK_COLOUR = "#b26a00";
    }

    private final Map<String, Verdict> bySha;
    /** The commits' SHAs, newest first, by what their STAMPs have of them: several where an author has several in one second. */
    private final Map<Authored, List<String>> byAuthored;

    private Committers(Map<String, Verdict> bySha, Map<Authored, List<String>> byAuthored) {
        this.bySha = bySha;
        this.byAuthored = byAuthored;
    }

    /**
     * Reads the committers of every commit reachable from a working tree's HEAD, and compares each with what
     * the store records of its author. Never throws: a repository that cannot be read has none. Runs git, so
     * off the FX thread.
     *
     * @param root the working tree
     * @return the committers
     */
    public static Committers read(Path root) {
        try {
            GitIdentityAdapter adapter = GitIdentityAdapter.fromMailmap(root);
            Map<Person, Identity> identities = new HashMap<>();
            Map<UUID, Map<UUID, Verdict>> verdicts = new HashMap<>();
            Map<String, Verdict> bySha = new HashMap<>();
            Map<Authored, List<String>> byAuthored = new HashMap<>();
            for (GitOperations.CommitPeople commit : log(root)) {
                Identity author = identities.computeIfAbsent(commit.author(), adapter::author);
                Identity committer = identities.computeIfAbsent(commit.committer(), adapter::author);
                Verdict verdict = verdicts.computeIfAbsent(author.uuid(), a -> new HashMap<>())
                        .computeIfAbsent(committer.uuid(), c -> verdict(author, committer));
                bySha.put(commit.sha(), verdict);
                nidOf(author).ifPresent(nid -> add(byAuthored, new Authored(nid, commit.time()), commit.sha()));
            }
            return new Committers(bySha, byAuthored);
        } catch (IOException | RuntimeException e) {
            LOG.info("Committers not read from {}: {}", root, e.getMessage());
            return NONE;
        }
    }

    /**
     * The committers of several documents as one, for a surface that shows an assembly.
     *
     * @param all the committers to merge
     * @return one over all of them
     */
    static Committers merge(Collection<Committers> all) {
        Map<String, Verdict> bySha = new HashMap<>();
        Map<Authored, List<String>> byAuthored = new HashMap<>();
        for (Committers one : all.stream().distinct().toList()) {
            bySha.putAll(one.bySha);
            one.byAuthored.forEach((authored, commits) -> commits.forEach(sha -> add(byAuthored, authored, sha)));
        }
        return new Committers(bySha, byAuthored);
    }

    /**
     * What review says of the committer of the commit a STAMP stands for.
     *
     * @param view  the view that names the person
     * @param sha   the commit the store records for the STAMP, when it records one; a commit that only
     *              annotates, or one a rebase replaced, is found by its author and time
     * @param stamp the STAMP
     * @return the committer; empty when it is the author, when the repository was not read or does not contain
     *         the commit, and for an uncommitted STAMP
     */
    Optional<Committed> of(ViewCalculator view, Optional<String> sha, StampEntity<?> stamp) {
        return said(view, verdicts(sha, stamp));
    }

    /**
     * Whether the commit a STAMP stands for is marked: {@link Committed#differs()}, without the words.
     *
     * @param sha   the commit the store records for the STAMP, when it records one
     * @param stamp the STAMP
     * @return whether it is an assistant's work that its person did not commit
     */
    boolean differs(Optional<String> sha, StampEntity<?> stamp) {
        return verdicts(sha, stamp).stream().anyMatch(verdict -> verdict.relation() == Relation.DIFFERS);
    }

    /**
     * The commits whose author and time a STAMP has. A commit that only annotates is found this way, since the
     * store records no SHA for it ({@link CommitSummaries#annotationCommits}).
     *
     * @param stamp the STAMP
     * @return the SHAs, newest first; several where the author has several in that second; empty when the
     *         repository was not read or does not contain the commit, and for an uncommitted STAMP
     */
    List<String> commits(StampEntity<?> stamp) {
        return stamp == null || stamp.time() == Long.MAX_VALUE ? List.of()
                : byAuthored.getOrDefault(new Authored(stamp.authorNid(), stamp.time()), List.of());
    }

    private List<Verdict> verdicts(Optional<String> sha, StampEntity<?> stamp) {
        if (stamp == null || stamp.time() == Long.MAX_VALUE) {
            return List.of();
        }
        Optional<Verdict> named = sha.map(bySha::get);
        return named.map(List::of).orElseGet(() -> commits(stamp).stream().map(bySha::get).distinct().toList());
    }

    /**
     * What review says of the committer of a commit.
     *
     * @param view the view that names the person
     * @param sha  the commit's full SHA
     * @return the committer; empty when it is the author, or when the repository does not contain the commit
     */
    Optional<Committed> of(ViewCalculator view, String sha) {
        return said(view, Optional.ofNullable(bySha.get(sha)).map(List::of).orElse(List.of()));
    }

    private static Optional<Committed> said(ViewCalculator view, List<Verdict> verdicts) {
        List<Verdict> others = verdicts.stream().filter(verdict -> verdict.relation() != Relation.AUTHOR).toList();
        if (others.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Committed(
                others.stream().map(verdict -> verdict.committer().name()).distinct().collect(Collectors.joining(" and ")),
                others.stream().map(verdict -> who(view, verdict)).distinct().collect(Collectors.joining(" and ")),
                others.stream().anyMatch(verdict -> verdict.relation() == Relation.DIFFERS)));
    }

    /** The committer's name; with its address where the name alone could be the person's, or is not the person at all. */
    private static String who(ViewCalculator view, Verdict verdict) {
        return switch (verdict.relation()) {
            case AUTHOR, ANOTHER -> verdict.committer().name();
            case PERSON -> person(view, verdict);
            case EARLIER_IDENTITY -> GitStampTerms.fullyQualifiedName(verdict.committer());
            case DIFFERS -> GitStampTerms.fullyQualifiedName(verdict.committer()) + ", not by " + person(view, verdict);
        };
    }

    private static String person(ViewCalculator view, Verdict verdict) {
        return view == null ? StampNames.recordedName(verdict.personNid()).orElse(verdict.committer().name())
                : StampNames.name(view, verdict.personNid());
    }

    private static Verdict verdict(Identity author, Identity committer) {
        OptionalLong directing = directedBy(author);
        if (directing.isEmpty()) {
            return new Verdict(author.uuid().equals(committer.uuid()) ? Relation.AUTHOR : Relation.ANOTHER, committer, 0);
        }
        long person = directing.getAsLong();
        if (PrimitiveData.publicId(person).contains(committer.uuid())) {
            return new Verdict(Relation.PERSON, committer, person);
        }
        boolean earlier = responsibility(committer).filter(of -> of.reason() == ResponsiblePersons.Reason.EARLIER_IDENTITY
                && of.personNid() == person).isPresent();
        return new Verdict(earlier ? Relation.EARLIER_IDENTITY : Relation.DIFFERS, committer, person);
    }

    /** The person directing an author that the store records as an assistant. */
    private static OptionalLong directedBy(Identity author) {
        return responsibility(author).filter(of -> of.reason() == ResponsiblePersons.Reason.DIRECTED_ASSISTANT)
                .map(of -> OptionalLong.of(of.personNid())).orElse(OptionalLong.empty());
    }

    private static Optional<ResponsiblePersons.Responsibility> responsibility(Identity identity) {
        OptionalLong nid = nidOf(identity);
        return nid.isEmpty() ? Optional.empty() : ResponsiblePersons.of(nid.getAsLong());
    }

    /** The nid of an author's concept, when the store has one: no nid is made for a committer that no document has as an author. */
    private static OptionalLong nidOf(Identity identity) {
        ConceptFacade concept = GitStampTerms.concept(identity);
        return GitStampVocabulary.entityAbsent(concept) ? OptionalLong.empty() : OptionalLong.of(concept.nid());
    }

    private static void add(Map<Authored, List<String>> byAuthored, Authored authored, String sha) {
        List<String> commits = byAuthored.computeIfAbsent(authored, a -> new ArrayList<>(1));
        if (!commits.contains(sha)) {
            commits.add(sha);
        }
    }

    private static List<GitOperations.CommitPeople> log(Path root) throws IOException {
        Path tree = root.toAbsolutePath().normalize();
        String head;
        try (GitRepo repo = GitRepo.open(tree)) {
            head = repo.headRevision();
        }
        try {
            return LOGS.compute(tree, (path, read) -> {
                if (read != null && read.head().equals(head)) {
                    return read;
                }
                try {
                    return new Log(head, GitOperations.people(path));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }).commits();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
}

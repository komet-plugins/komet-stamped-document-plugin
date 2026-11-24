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

import design.knowledge.stamp.adoc.identity.GitIdentityAdapter;
import design.knowledge.stamp.adoc.identity.Identity;
import design.knowledge.stamp.adoc.model.Person;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.util.uuid.UuidT5Generator;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.transaction.StampedWriter;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.EntityFacade;
import dev.ikm.tinkar.terms.State;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.foundation.ike.bindings.IkePattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Who is responsible for the work of a commit's author (IKE-Network/ike-issues#1399). An author that is
 * not a person's own identity, such as the assistant a person directs or an address the person committed
 * under earlier, has one semantic of the Responsible person pattern on its concept: the person, and the
 * reason. A person reviewing a document reads it beside the author's name ({@link #authorName}).
 *
 * <p>Komet writes the record when it reads a document whose history has a commit by such an author
 * ({@link #recordIfAbsent(Collection)}), from the IKE Dashboard's file of authors ({@link CommitAuthors}),
 * until the registry of people writes it (KEC, 2026-11-23). It adds a record and never changes one. The
 * record has the module, path and author that a git author's concept has ({@link GitStampVocabulary}), so
 * the two are kept, and synchronized, together. With no file, or a starter set from before the pattern,
 * nothing is written, and an author is shown by its name alone.
 */
public final class ResponsiblePersons {

    private static final Logger LOG = LoggerFactory.getLogger(ResponsiblePersons.class);

    /** The authors whose stored record was found to differ from the file, so each is logged once. */
    private static final Set<UUID> REPORTED = ConcurrentHashMap.newKeySet();

    private ResponsiblePersons() {
    }

    /** Why a person is responsible for an author's work. */
    public enum Reason {
        /** The author is an assistant, and the person directs it. */
        DIRECTED_ASSISTANT,
        /** The person, and any assistants of the person, used the author before each had an identity. */
        EARLIER_IDENTITY;

        private ConceptFacade concept() {
            return switch (this) {
                case DIRECTED_ASSISTANT -> IkeConcept.DIRECTED_ASSISTANT;
                case EARLIER_IDENTITY -> IkeConcept.EARLIER_IDENTITY;
            };
        }
    }

    /**
     * What the store records of an author.
     *
     * @param personNid the nid of the responsible person's own author concept
     * @param reason    why the person is responsible
     */
    public record Responsibility(long personNid, Reason reason) {
    }

    /**
     * How review names an author: the author's name and, for an author with a responsible person, who that
     * is.
     *
     * @param view      the view that selects descriptions
     * @param authorNid the nid of an author's concept
     * @return {@code KEC Claude, directed by Keith E Campbell}, or {@code Keith E Campbell (earlier commits)},
     *         or the author's name when the store records no responsible person
     */
    public static String authorName(ViewCalculator view, long authorNid) {
        return of(authorNid).map(responsibility -> {
            String person = StampNames.name(view, responsibility.personNid());
            return switch (responsibility.reason()) {
                case DIRECTED_ASSISTANT -> StampNames.name(view, authorNid) + ", directed by " + person;
                case EARLIER_IDENTITY -> person + " (earlier commits)";
            };
        }).orElseGet(() -> StampNames.name(view, authorNid));
    }

    /**
     * The responsible person the store records for an author, whatever the view selects.
     *
     * @param authorNid the nid of an author's concept
     * @return the person and the reason; empty when the latest committed version is not active, there is no
     *         record, or its reason is one this Komet does not know
     */
    public static Optional<Responsibility> of(long authorNid) {
        if (GitStampVocabulary.entityAbsent(IkePattern.RESPONSIBLE_PERSON_PATTERN)) {
            return Optional.empty();
        }
        return latest(authorNid).filter(version -> version.active()).flatMap(version -> {
            List<Object> fields = List.copyOf(version.fieldValues().castToList());
            if (fields.size() < 2 || !(fields.get(0) instanceof EntityFacade person)
                    || !(fields.get(1) instanceof EntityFacade why)) {
                return Optional.empty();
            }
            return Stream.of(Reason.values()).filter(reason -> reason.concept().nid() == why.nid()).findFirst()
                    .map(reason -> new Responsibility(person.nid(), reason));
        });
    }

    /** The latest committed version of the record on an author, whatever its status. */
    private static Optional<SemanticEntityVersion> latest(long authorNid) {
        SemanticEntityVersion latest = null;
        for (SemanticEntity<SemanticEntityVersion> record : EntityService.get()
                .semanticsForComponentOfPattern(authorNid, IkePattern.RESPONSIBLE_PERSON_PATTERN.nid()).toList()) {
            for (SemanticEntityVersion version : record.versions()) {
                if (version.time() != Long.MAX_VALUE && version.time() != Long.MIN_VALUE
                        && (latest == null || version.time() > latest.time())) {
                    latest = version;
                }
            }
        }
        return Optional.ofNullable(latest);
    }

    /**
     * Records the responsible person of each author the file of authors names, when there is no record yet.
     *
     * @param authors the authors of a document's history
     * @return how many records were written
     */
    static int recordIfAbsent(Collection<Identity> authors) {
        return CommitAuthors.inFile().map(named -> recordIfAbsent(authors, named)).orElse(0);
    }

    /**
     * Records the responsible person of each author in a document's history that is the assistant or an
     * earlier address of the person a file names, when there is no record yet. The person's concept is added
     * first if it is absent.
     *
     * @param authors the authors of a document's history
     * @param named   the authors the file names
     * @return how many records were written
     */
    static synchronized int recordIfAbsent(Collection<Identity> authors, CommitAuthors named) {
        Map<UUID, Reason> reasons = new LinkedHashMap<>();
        named.assistant().ifPresent(assistant -> reasons.put(identity(assistant).uuid(), Reason.DIRECTED_ASSISTANT));
        named.earlier().forEach(address -> reasons.put(
                identity(new CommitAuthors.Author(named.person().name(), address)).uuid(), Reason.EARLIER_IDENTITY));
        List<Identity> recorded = authors.stream().distinct().filter(author -> reasons.containsKey(author.uuid())).toList();
        if (recorded.isEmpty() || !available()) {
            return 0;
        }
        int written = 0;
        for (Identity author : recorded) {
            if (recordIfAbsent(author, reasons.get(author.uuid()), identity(named.person()))) {
                written++;
            }
        }
        return written;
    }

    private static boolean recordIfAbsent(Identity author, Reason reason, Identity person) {
        ConceptFacade concept = GitStampTerms.concept(author);
        if (GitStampVocabulary.entityAbsent(concept)) {
            return false;                                        // none of its commits is in the store
        }
        Optional<SemanticEntityVersion> stored = latest(concept.nid());
        if (stored.isPresent()) {
            reportDifference(author, reason, person, concept.nid());
            return false;
        }
        ConceptFacade responsible = GitStampVocabulary.seedAuthorIfAbsent(person);
        PublicId pattern = IkePattern.RESPONSIBLE_PERSON_PATTERN.publicId();
        try (StampedWriter writer = GitStampVocabulary.seedWriter()) {
            writer.semantic(PublicIds.of(UuidT5Generator.singleSemanticUuid(pattern, concept.publicId())), pattern,
                    concept.publicId(), responsible, reason.concept());
            writer.commit();
        }
        LOG.info("Recorded that {} is responsible for the work of {}: {}", GitStampTerms.fullyQualifiedName(person),
                GitStampTerms.fullyQualifiedName(author), reason.concept().description());
        return true;
    }

    /** Logs, once for an author, that the stored record is not what the file says. The record is left as it is. */
    private static void reportDifference(Identity author, Reason reason, Identity person, long authorNid) {
        boolean same = of(authorNid).filter(stored -> stored.reason() == reason
                && PrimitiveData.publicId(stored.personNid()).contains(person.uuid())).isPresent();
        if (!same && REPORTED.add(author.uuid())) {
            LOG.info("The responsible person recorded for {} is not {} ({}), as the file of authors has it; the record is left as it is",
                    GitStampTerms.fullyQualifiedName(author), GitStampTerms.fullyQualifiedName(person), reason.concept().description());
        }
    }

    /** Whether the open store holds the pattern and both reasons. */
    private static boolean available() {
        return !GitStampVocabulary.entityAbsent(IkePattern.RESPONSIBLE_PERSON_PATTERN)
                && Stream.of(Reason.values()).noneMatch(reason -> GitStampVocabulary.entityAbsent(reason.concept()));
    }

    /** The identity of an author in the file: its address as it stands, since no repository's mailmap covers the file. */
    private static Identity identity(CommitAuthors.Author author) {
        return GitIdentityAdapter.raw().author(new Person(author.name(), author.email()));
    }
}

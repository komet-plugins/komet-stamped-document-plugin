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
import dev.ikm.komet.terms.KometConcept;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.util.uuid.UuidT5Generator;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.boundary.DataKind;
import dev.ikm.tinkar.entity.boundary.EntityBoundaries;
import dev.ikm.tinkar.entity.boundary.ModuleKinds;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.entity.transaction.StampedWriter;
import dev.ikm.tinkar.terms.EntityFacade;
import dev.ikm.tinkar.terms.KernelConcept;
import dev.ikm.tinkar.terms.KernelPattern;
import dev.ikm.tinkar.terms.State;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.foundation.ike.bindings.IkePattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The person responsible for the work of a commit's author (IKE-Network/ike-issues#1399): the record Komet
 * writes when it reads a document with commits by the assistant or by an earlier address of the person, and
 * how review then names those authors. Each test has people of its own, with invented names, since the
 * author concepts of one store are shared by every document in it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResponsiblePersonsIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");

    @TempDir
    Path root;

    @BeforeAll
    void setupDatabase() {
        assertTrue(PB_STARTER_DATA.exists(), "Starter data must be present at " + PB_STARTER_DATA.getAbsolutePath());
        PrimitiveData.selectControllerByName("Load Memory-Only 64-bit Store");
        PrimitiveData.start();
        assertTrue(new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute().getTotalCount() > 0);
        System.setProperty(VertexMaterializer.MODEL_PROPERTY, "vertex");
    }

    @AfterAll
    void teardownDatabase() {
        System.clearProperty(VertexMaterializer.MODEL_PROPERTY);
        ModuleKinds.clear();
        EntityBoundaries.clear();
        PrimitiveData.stop();
    }

    @Test
    void readingADocumentRecordsThePersonOfTheAssistantAndOfTheEarlierAddress() throws Exception {
        Path dir = repository("ada");
        commit(dir, "The first claim.", "Ada Lovelace", "ada@example.test");
        commit(dir, "The second claim.", "Ada's Claude", "ada-claude@komet.ike.network");
        commit(dir, "The third claim.", "Charles Babbage", "charles@example.test");
        Identity person = author("Ada Lovelace", "ada@komet.ike.network");
        assertTrue(GitStampVocabulary.entityAbsent(GitStampTerms.concept(person)), "no commit is hers yet");

        withFile("""
                person.name=Ada Lovelace
                person.email=ada@komet.ike.network
                person.earlier=ada@example.test
                assistant.name=Ada's Claude
                assistant.email=ada-claude@komet.ike.network
                """, () -> GitDocumentMaterializer.materialize(dir, dir.resolve("ada.adoc")));

        assertRecord(author("Ada's Claude", "ada-claude@komet.ike.network"), person, IkeConcept.DIRECTED_ASSISTANT);
        assertRecord(author("Ada Lovelace", "ada@example.test"), person, IkeConcept.EARLIER_IDENTITY);
        assertEquals(List.of(), records(author("Charles Babbage", "charles@example.test")), "no one is responsible for his work");
        assertEquals(List.of(), records(person), "her identity is her own");

        assertEquals("Ada's Claude, directed by Ada Lovelace", shown("Ada's Claude", "ada-claude@komet.ike.network"));
        assertEquals("Ada Lovelace (earlier commits)", shown("Ada Lovelace", "ada@example.test"));
        assertEquals("Charles Babbage", shown("Charles Babbage", "charles@example.test"));
        assertEquals("Ada Lovelace", shown("Ada Lovelace", "ada@komet.ike.network"));
    }

    /** A document materialized before the file listed anyone gets its records when it is next read. */
    @Test
    void aDocumentReadAgainGetsTheRecordsItHasNone() throws Exception {
        Path dir = repository("grace");
        commit(dir, "The first claim.", "Grace's Claude", "grace-claude@komet.ike.network");
        GitDocumentMaterializer.materialize(dir, dir.resolve("grace.adoc"));
        Identity assistant = author("Grace's Claude", "grace-claude@komet.ike.network");
        assertEquals(List.of(), records(assistant), "the file does not name it");
        assertEquals("Grace's Claude", shown("Grace's Claude", "grace-claude@komet.ike.network"));

        String file = """
                person.name=Grace Hopper
                person.email=grace@komet.ike.network
                assistant.name=Grace's Claude
                assistant.email=grace-claude@komet.ike.network
                """;
        withFile(file, () -> GitDocumentMaterializer.materialize(dir, dir.resolve("grace.adoc")));
        assertRecord(assistant, author("Grace Hopper", "grace@komet.ike.network"), IkeConcept.DIRECTED_ASSISTANT);

        withFile(file, () -> GitDocumentMaterializer.materialize(dir, dir.resolve("grace.adoc")));
        assertEquals(1, records(assistant).getFirst().versions().size(), "reading it again adds nothing");
    }

    @Test
    void aRecordThatIsThereIsNotChanged() {
        Identity assistant = author("Shared Claude", "shared-claude@komet.ike.network");
        GitStampVocabulary.seedAuthorIfAbsent(assistant);
        CommitAuthors first = new CommitAuthors(new CommitAuthors.Author("Mary Somerville", "mary@komet.ike.network"),
                Optional.of(new CommitAuthors.Author("Shared Claude", "shared-claude@komet.ike.network")));
        CommitAuthors second = new CommitAuthors(new CommitAuthors.Author("Emmy Noether", "emmy@komet.ike.network"),
                Optional.of(new CommitAuthors.Author("Shared Claude", "shared-claude@komet.ike.network")));

        assertEquals(1, ResponsiblePersons.recordIfAbsent(List.of(assistant), first));
        assertEquals(0, ResponsiblePersons.recordIfAbsent(List.of(assistant), second));

        assertRecord(assistant, author("Mary Somerville", "mary@komet.ike.network"), IkeConcept.DIRECTED_ASSISTANT);
        assertTrue(GitStampVocabulary.entityAbsent(GitStampTerms.concept(author("Emmy Noether", "emmy@komet.ike.network"))),
                "nothing is added for the person of the second file");
        assertEquals("Shared Claude, directed by Mary Somerville", shown("Shared Claude", "shared-claude@komet.ike.network"));
    }

    @Test
    void aRetiredRecordNamesNoOneAndIsNotWrittenAgain() {
        Identity assistant = author("Old Claude", "old-claude@komet.ike.network");
        GitStampVocabulary.seedAuthorIfAbsent(assistant);
        CommitAuthors authors = new CommitAuthors(new CommitAuthors.Author("Sofia Kovalevskaya", "sofia@komet.ike.network"),
                Optional.of(new CommitAuthors.Author("Old Claude", "old-claude@komet.ike.network")));
        assertEquals(1, ResponsiblePersons.recordIfAbsent(List.of(assistant), authors));
        SemanticEntity<SemanticEntityVersion> record = records(assistant).getFirst();
        try (StampedWriter writer = StampedWriter.open("retire", State.INACTIVE, System.currentTimeMillis() + 1,
                KernelConcept.USER, KometConcept.DEVELOPMENT_MODULE, KernelConcept.DEVELOPMENT_PATH)) {
            writer.semantic(record.publicId(), IkePattern.RESPONSIBLE_PERSON_PATTERN.publicId(),
                    GitStampTerms.concept(assistant).publicId(), record.versions().getFirst().fieldValues().toArray());
            writer.commit();
        }

        assertEquals("Old Claude", shown("Old Claude", "old-claude@komet.ike.network"));
        assertEquals(0, ResponsiblePersons.recordIfAbsent(List.of(assistant), authors));
        assertEquals("Old Claude", shown("Old Claude", "old-claude@komet.ike.network"));
    }

    @Test
    void anAuthorWithNoConceptInTheStoreGetsNoRecord() {
        Identity assistant = author("Unseen Claude", "unseen-claude@komet.ike.network");
        CommitAuthors authors = new CommitAuthors(new CommitAuthors.Author("Hypatia", "hypatia@komet.ike.network"),
                Optional.of(new CommitAuthors.Author("Unseen Claude", "unseen-claude@komet.ike.network")));

        assertEquals(0, ResponsiblePersons.recordIfAbsent(List.of(assistant), authors));
        assertTrue(GitStampVocabulary.entityAbsent(GitStampTerms.concept(assistant)));
        assertTrue(GitStampVocabulary.entityAbsent(GitStampTerms.concept(author("Hypatia", "hypatia@komet.ike.network"))));
    }


    @Test
    void anAuthorConceptHasTheAddressInItsFullyQualifiedNameAndTheNameAlone() {
        Identity author = author("Alan Turing", "alan@example.test");
        long nid = GitStampVocabulary.seedAuthorIfAbsent(author).nid();

        assertEquals(Map.of(KernelConcept.FULLY_QUALIFIED_NAME_DESCRIPTION_TYPE.nid(), "Alan Turing (alan@example.test)",
                KernelConcept.REGULAR_NAME_DESCRIPTION_TYPE.nid(), "Alan Turing"), descriptions(nid));
        assertEquals("Alan Turing", StampNames.name(Calculators.View.Default(), nid));
        assertEquals(Optional.of("Alan Turing"), StampNames.recordedName(nid), "whatever the view selects");
        assertEquals(DataKind.KNOWLEDGE, EntityBoundaries.kindOf(nid));
    }

    @Test
    void anAuthorConceptOfBeforeKeepsItsName() {
        Identity author = author("Joan Clarke", "joan@example.test");
        GitStampVocabulary.seedAuthorIfAbsent(GitStampTerms.concept(author));

        long nid = GitStampVocabulary.seedAuthorIfAbsent(author).nid();

        assertEquals(Map.of(KernelConcept.FULLY_QUALIFIED_NAME_DESCRIPTION_TYPE.nid(), "Joan Clarke"), descriptions(nid));
        assertEquals("Joan Clarke", StampNames.name(Calculators.View.Default(), nid));
    }

    private static void assertRecord(Identity author, Identity person, EntityFacade reason) {
        List<SemanticEntity<SemanticEntityVersion>> records = records(author);
        assertEquals(1, records.size(), "one record on " + author.name());
        SemanticEntity<SemanticEntityVersion> record = records.getFirst();
        PublicId concept = PublicIds.of(author.uuid());
        assertEquals(PublicIds.of(UuidT5Generator.singleSemanticUuid(IkePattern.RESPONSIBLE_PERSON_PATTERN.publicId(), concept)),
                record.publicId(), "the identity every machine makes");
        assertEquals(1, record.versions().size());
        SemanticEntityVersion version = record.versions().getFirst();
        assertEquals(PublicIds.of(person.uuid()), ((EntityFacade) version.fieldValues().get(0)).publicId(), "the responsible person");
        assertEquals(reason.nid(), ((EntityFacade) version.fieldValues().get(1)).nid(), "the reason");
        assertEquals(KernelConcept.USER.nid(), version.stamp().authorNid());
        assertEquals(KometConcept.DEVELOPMENT_MODULE.nid(), version.stamp().moduleNid());
        assertEquals(KernelConcept.DEVELOPMENT_PATH.nid(), version.stamp().pathNid());
        assertEquals(DataKind.KNOWLEDGE, EntityBoundaries.kindOf(record.nid()), "kept and synchronized with the author's concept");
        assertFalse(GitStampVocabulary.entityAbsent(GitStampTerms.concept(person)), "the person's concept is in the store");
    }

    private static List<SemanticEntity<SemanticEntityVersion>> records(Identity author) {
        return EntityService.get().semanticsForComponentOfPattern(GitStampTerms.concept(author).nid(),
                IkePattern.RESPONSIBLE_PERSON_PATTERN.nid()).toList();
    }

    private static Map<Long, String> descriptions(long nid) {
        Map<Long, String> found = new java.util.HashMap<>();
        EntityService.get().semanticsForComponentOfPattern(nid, KernelPattern.DESCRIPTION_PATTERN.nid()).toList().forEach(d -> {
            SemanticEntityVersion version = (SemanticEntityVersion) EntityHandle.get(d.nid()).expectSemantic().versions().getFirst();
            found.put(((EntityFacade) version.fieldValues().get(3)).nid(), (String) version.fieldValues().get(1));
        });
        return found;
    }

    private static String shown(String name, String email) {
        return ResponsiblePersons.authorName(Calculators.View.Default(), GitStampTerms.concept(author(name, email)).nid());
    }

    private static Identity author(String name, String email) {
        return GitIdentityAdapter.raw().author(new Person(name, email));
    }

    /** Runs with a home folder whose {@code .ike/authors.properties} has a text, as the materializer reads the file. */
    private void withFile(String text, Step step) throws Exception {
        Path home = Files.createTempDirectory(root, "home");
        Files.createDirectories(home.resolve(".ike"));
        Files.writeString(CommitAuthors.file(home), text);
        String before = System.setProperty("user.home", home.toString());
        try {
            step.run();
        } finally {
            System.setProperty("user.home", before);
        }
    }

    @FunctionalInterface
    private interface Step {
        void run() throws Exception;
    }

    private Path repository(String name) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name));
        git(dir, "init", "-q", "-b", "main");
        git(dir, "config", "user.name", "Repository Owner");
        git(dir, "config", "user.email", "owner@example.test");
        git(dir, "config", "commit.gpgsign", "false");
        git(dir, "config", "core.hooksPath", "/dev/null");
        Files.writeString(dir.resolve(name + ".adoc"), ":topic-id: dev-responsible-" + name + "\n\n= Responsible " + name + "\n\n");
        return dir;
    }

    /** Adds a line to the repository's document and commits it under an author. */
    private static void commit(Path dir, String line, String name, String email) throws Exception {
        Path file = dir.resolve(dir.getFileName() + ".adoc");
        Files.writeString(file, Files.readString(file) + line + "\n");
        git(dir, "add", file.getFileName().toString());
        git(dir, "commit", "-q", "-m", line, "--author", name + " <" + email + ">");
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes()).strip();
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
        }
    }
}

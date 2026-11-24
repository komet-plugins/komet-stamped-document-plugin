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
import design.knowledge.stamp.adoc.model.Person;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.entity.boundary.EntityBoundaries;
import dev.ikm.tinkar.entity.boundary.ModuleKinds;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A conversation saved to a git folder reads back as an IKE Document whose lines' STAMPs name who said
 * each turn, and when (IKE-Network/ike-issues#1313; step 3 of
 * {@code design-2026-11-22-knowledge-shared-local}, settled by KEC on 2026-11-23).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConversationFileIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");

    private static final CommitAuthors AUTHORS = new CommitAuthors(
            new CommitAuthors.Author("Ada Lovelace", "ada@komet.ike.network"),
            Optional.of(new CommitAuthors.Author("Ada's Claude", "ada-claude@komet.ike.network")));

    private static final long T1 = 1_795_000_000_000L;

    @TempDir
    Path root;

    @BeforeAll
    void setupDatabase() {
        assertTrue(PB_STARTER_DATA.exists(), "Starter data must be present at " + PB_STARTER_DATA.getAbsolutePath());
        PrimitiveData.selectControllerByName("Load Memory-Only 64-bit Store");
        PrimitiveData.start();
        assertTrue(new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute().getTotalCount() > 0);
    }

    @AfterAll
    void teardownDatabase() {
        ModuleKinds.clear();
        EntityBoundaries.clear();
        PrimitiveData.stop();
    }

    @Test
    void turnsSaidInTheSameSecondStillReadAsTheLatestDocument() throws Exception {
        // The journal writes a person's turn and the reply to it a millisecond apart, and git keeps
        // seconds: two commits in one second left the reader with two latest versions of the document.
        Path dir = Files.createDirectories(root.resolve("same-second"));
        Path file = dir.resolve("quick.adoc");
        List<ConversationFile.Turn> turns = List.of(
                new ConversationFile.Turn("Thank you, that is all.", false, T1),
                new ConversationFile.Turn("You are welcome.", true, T1 + 1));
        ConversationFile.save(file, "c-same", "Quick", turns, true, Optional.of(AUTHORS));
        PublicId anchor = GitDocumentMaterializer.materialize(dir, file).anchor();
        List<String> lines = GitDocumentMaterializer.load(anchor).orElseThrow().lines().stream()
                .map(GitDocumentMaterializer.StoredLine::text).toList();
        assertEquals(Files.readAllLines(file), lines, "the document as its last commit has it");
        assertEquals(T1 + 1000, line(GitDocumentMaterializer.load(anchor).orElseThrow(), "You are welcome.").introduced().time(),
                "the reply a second after the turn it answers");
    }

    @Test
    void eachLineOfTheSavedConversationIsStampedByWhoSaidItAndWhen() throws Exception {
        Path dir = Files.createDirectories(root.resolve("conversations"));
        Path file = dir.resolve("modules.adoc");
        List<ConversationFile.Turn> turns = List.of(
                new ConversationFile.Turn("What is a module?", false, T1),
                new ConversationFile.Turn("A module groups the versions written under it.", true, T1 + 60_000));
        ConversationFile.Saved saved = ConversationFile.save(file, "c-it", "About modules", turns, true, Optional.of(AUTHORS));
        assertEquals(2, saved.commits(), saved.report());

        PublicId anchor = GitDocumentMaterializer.materialize(dir, file).anchor();
        GitDocumentMaterializer.StoredDocument document = GitDocumentMaterializer.load(anchor).orElseThrow();
        GitIdentityAdapter adapter = GitIdentityAdapter.raw();
        GitDocumentMaterializer.StoredLine question = line(document, "What is a module?");
        GitDocumentMaterializer.StoredLine answer = line(document, "A module groups the versions written under it.");
        assertEquals(PublicIds.of(adapter.author(new Person("Ada Lovelace", "ada@komet.ike.network")).uuid()),
                question.introduced().author().publicId(), "the person's turn is hers");
        assertEquals(PublicIds.of(adapter.author(new Person("Ada's Claude", "ada-claude@komet.ike.network")).uuid()),
                answer.introduced().author().publicId(), "the assistant's turn is the assistant's");
        assertEquals(T1, question.introduced().time(), "said at the turn's time");
        assertEquals(T1 + 60_000, answer.introduced().time());
    }

    private static GitDocumentMaterializer.StoredLine line(GitDocumentMaterializer.StoredDocument document, String text) {
        return document.lines().stream().filter(l -> l.text().equals(text)).findFirst().orElseThrow();
    }
}

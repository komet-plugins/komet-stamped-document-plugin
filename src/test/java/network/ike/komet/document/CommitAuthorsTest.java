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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The IKE Dashboard's file of authors as Komet reads it (IKE-Network/ike-issues#1399): the person and
 * the assistant they direct, and the git variables that make a commit of the assistant's work name the
 * assistant as author and the person as committer. The names are invented.
 */
class CommitAuthorsTest {

    @TempDir
    Path home;

    @Test
    void theFileIsTheDashboardsInDotIke() {
        assertEquals(home.resolve(".ike").resolve("authors.properties"), CommitAuthors.file(home));
    }

    @Test
    void noFileNamesNoOne() {
        assertEquals(Optional.empty(), CommitAuthors.read(CommitAuthors.file(home)));
    }

    @Test
    void theAssistantsWorkHasTheAssistantAsAuthorAndThePersonAsCommitter() throws Exception {
        CommitAuthors authors = CommitAuthors.read(write("""
                # the two authors
                person.name=Ada Lovelace
                person.email=ada@komet.ike.network
                assistant.name=Ada's Claude
                assistant.email=ada-claude@komet.ike.network
                """)).orElseThrow();

        assertEquals(new CommitAuthors.Author("Ada Lovelace", "ada@komet.ike.network"), authors.person());
        assertEquals("Ada's Claude <ada-claude@komet.ike.network>", authors.assistant().orElseThrow().ident());
        Map<String, String> variables = authors.assistantsWork();
        assertEquals(List.of(CommitAuthors.AUTHOR_NAME, CommitAuthors.AUTHOR_EMAIL, CommitAuthors.COMMITTER_NAME,
                CommitAuthors.COMMITTER_EMAIL), List.copyOf(variables.keySet()), "git's own four variables, in a fixed order");
        assertEquals("Ada's Claude", variables.get("GIT_AUTHOR_NAME"));
        assertEquals("ada-claude@komet.ike.network", variables.get("GIT_AUTHOR_EMAIL"));
        assertEquals("Ada Lovelace", variables.get("GIT_COMMITTER_NAME"));
        assertEquals("ada@komet.ike.network", variables.get("GIT_COMMITTER_EMAIL"));
        assertEquals(" as Ada's Claude, committed by Ada Lovelace", CommitAuthors.described(variables));
    }

    @Test
    void aFileWithNoAssistantLeavesEveryCommitAsBefore() throws Exception {
        CommitAuthors authors = CommitAuthors.read(write("""
                person.name=Ada Lovelace
                person.email=ada@komet.ike.network
                """)).orElseThrow();

        assertTrue(authors.assistant().isEmpty());
        assertEquals(Map.of(), authors.assistantsWork(), "no variables: git's own author and committer");
        assertEquals("", CommitAuthors.described(authors.assistantsWork()));
    }

    @Test
    void aKometOpenedWithNoAssistantPassesTheFilesAssistantOver() throws Exception {
        write("""
                person.name=Ada Lovelace
                person.email=ada@komet.ike.network
                assistant.name=Ada's Claude
                assistant.email=ada-claude@komet.ike.network
                """);

        CommitAuthors asStarted = CommitAuthors.current(home, Map.of()).orElseThrow();
        assertEquals("Ada's Claude", asStarted.assistant().orElseThrow().name(), "no variable: as the file says");

        CommitAuthors alone = CommitAuthors.current(home, Map.of(CommitAuthors.ASSISTANT_VARIABLE, "none")).orElseThrow();
        assertEquals(new CommitAuthors.Author("Ada Lovelace", "ada@komet.ike.network"), alone.person());
        assertTrue(alone.assistant().isEmpty());
        assertEquals(Map.of(), alone.assistantsWork(), "the assistant's work is committed as the person: no variables");
        assertEquals("", CommitAuthors.described(alone.assistantsWork()));
    }

    @Test
    void onlyNoneLeavesTheAssistantOut() {
        assertEquals("IKE_ASSISTANT", CommitAuthors.ASSISTANT_VARIABLE, "the name the IKE Dashboard sets");
        assertTrue(CommitAuthors.leavesAssistantOut(Map.of("IKE_ASSISTANT", " None ")));
        assertFalse(CommitAuthors.leavesAssistantOut(Map.of("IKE_ASSISTANT", "")));
        assertFalse(CommitAuthors.leavesAssistantOut(Map.of("IKE_ASSISTANT", "Ada's Claude")));
        assertFalse(CommitAuthors.leavesAssistantOut(Map.of()));
    }

    @Test
    void withNoFileTheVariableChangesNothing() {
        assertEquals(Optional.empty(), CommitAuthors.current(home, Map.of(CommitAuthors.ASSISTANT_VARIABLE, "none")));
    }

    @Test
    void keysKometDoesNotKnowArePassedOver() throws Exception {
        CommitAuthors authors = CommitAuthors.read(write("""
                person.name=Ada Lovelace
                person.email=ada@komet.ike.network
                person.key=ssh-ed25519 AAAA
                assistant.name=Ada's Claude
                assistant.email=ada-claude@komet.ike.network
                """)).orElseThrow();

        assertEquals("Ada's Claude", authors.assistant().orElseThrow().name());
    }

    @Test
    void anAssistantNamedInPartIsRefused() throws Exception {
        Path file = write("""
                person.name=Ada Lovelace
                person.email=ada@komet.ike.network
                assistant.name=Ada's Claude
                """);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> CommitAuthors.read(file));
        assertTrue(refused.getMessage().contains("assistant.email"), refused.getMessage());
    }

    @Test
    void aFileNamingNoPersonIsRefused() throws Exception {
        Path file = write("""
                assistant.name=Ada's Claude
                assistant.email=ada-claude@komet.ike.network
                """);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> CommitAuthors.read(file));
        assertTrue(refused.getMessage().contains("names no person"), refused.getMessage());
    }

    @Test
    void anAddressGitWouldNotTakeIsRefused() throws Exception {
        Path file = write("""
                person.name=Ada Lovelace
                person.email=ada@komet.ike.network
                assistant.name=Ada's Claude
                assistant.email=<ada-claude@komet.ike.network>
                """);

        assertThrows(IllegalArgumentException.class, () -> CommitAuthors.read(file));
    }

    private Path write(String text) throws Exception {
        Path file = CommitAuthors.file(home);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }
}

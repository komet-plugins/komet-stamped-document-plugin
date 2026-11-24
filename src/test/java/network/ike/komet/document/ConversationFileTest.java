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

import design.knowledge.stamp.adoc.dialect.Block;
import design.knowledge.stamp.adoc.dialect.Document;
import design.knowledge.stamp.adoc.dialect.IkeDialectParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A conversation saved to a file: a commit per turn at its time by whoever said it, more turns added
 * by saving again, and nothing written over another document or over changes not committed
 * (IKE-Network/ike-issues#1313; step 3 of {@code design-2026-11-22-knowledge-shared-local}, settled by
 * KEC on 2026-11-23).
 */
class ConversationFileTest {

    private static final CommitAuthors AUTHORS = new CommitAuthors(
            new CommitAuthors.Author("Ada Lovelace", "ada@komet.ike.network"),
            Optional.of(new CommitAuthors.Author("Ada's Claude", "ada-claude@komet.ike.network")));

    private static final long T1 = 1_795_000_000_000L;
    private static final List<ConversationFile.Turn> TURNS = List.of(
            new ConversationFile.Turn("What is a *module*?", false, T1),
            new ConversationFile.Turn("A module groups versions.\n\n- one\n- two", true, T1 + 60_000),
            new ConversationFile.Turn("Thanks.", false, T1 + 120_000),
            new ConversationFile.Turn("You are welcome.", true, T1 + 180_000));

    @TempDir
    Path root;

    @Test
    void eachTurnIsACommitAtItsOwnTimeByWhoeverSaidIt() throws Exception {
        Path dir = repository("each");
        Path file = dir.resolve("chat.adoc");
        ConversationFile.Saved saved = ConversationFile.save(file, "c-1", "About modules", TURNS, false, Optional.of(AUTHORS));
        assertEquals(ConversationFile.Outcome.SAVED, saved.outcome(), saved.report());
        assertEquals(4, saved.commits());
        assertTrue(saved.report().startsWith("Saved 4 turns of About modules to chat.adoc as 4 commits"), saved.report());
        assertEquals(List.of(
                "Ada Lovelace <ada@komet.ike.network> | Ada Lovelace <ada@komet.ike.network> | " + T1 / 1000,
                "Ada's Claude <ada-claude@komet.ike.network> | Ada Lovelace <ada@komet.ike.network> | " + (T1 + 60_000) / 1000,
                "Ada Lovelace <ada@komet.ike.network> | Ada Lovelace <ada@komet.ike.network> | " + (T1 + 120_000) / 1000,
                "Ada's Claude <ada-claude@komet.ike.network> | Ada Lovelace <ada@komet.ike.network> | " + (T1 + 180_000) / 1000),
                git(dir, "log", "--reverse", "--format=%an <%ae> | %cn <%ce> | %at").lines().toList(),
                "the person's turns are their own; the assistant's have the assistant as author and the person as committer");
        String text = Files.readString(file);
        assertTrue(text.startsWith("= About modules\n:conversation-id: c-1\n\n[[turn-1]]\n== Ada Lovelace\n\nWhat is a _module_?\n"), text);
        Document document = IkeDialectParser.parse(text);
        assertEquals("About modules", document.title());
        List<Block> turns = document.root().children();
        assertEquals(List.of("Ada Lovelace", "Ada's Claude", "Ada Lovelace", "Ada's Claude"), turns.stream().map(Block::title).toList());
        assertEquals(List.of(Block.Kind.PARAGRAPH, Block.Kind.LIST), turns.get(1).children().stream().map(Block::kind).toList());
        assertEquals(OptionalInt.of(4), ConversationFile.held(file, "c-1"));
    }

    @Test
    void savingAgainAddsOnlyTheTurnsSaidSince() throws Exception {
        Path dir = repository("again");
        Path file = dir.resolve("chat.adoc");
        ConversationFile.save(file, "c-2", "Again", TURNS.subList(0, 2), false, Optional.of(AUTHORS));
        ConversationFile.Saved added = ConversationFile.save(file, "c-2", "Again", TURNS, false, Optional.of(AUTHORS));
        assertEquals(2, added.commits());
        assertTrue(added.report().startsWith("Added 2 turns of Again"), added.report());
        assertEquals("4", git(dir, "rev-list", "--count", "HEAD"));
        assertEquals(ConversationFile.Outcome.NOTHING_NEW,
                ConversationFile.save(file, "c-2", "Again", TURNS, false, Optional.of(AUTHORS)).outcome());
        assertEquals("4", git(dir, "rev-list", "--count", "HEAD"), "nothing new, no commit");
    }

    @Test
    void anotherDocumentIsNeverWrittenOver() throws Exception {
        Path dir = repository("other");
        Path file = dir.resolve("chat.adoc");
        Files.writeString(file, "= Someone else's document\n\nTheir text.\n");
        ConversationFile.Saved saved = ConversationFile.save(file, "c-3", "Mine", TURNS, false, Optional.of(AUTHORS));
        assertEquals(ConversationFile.Outcome.NOT_THIS_CONVERSATION, saved.outcome());
        assertEquals("= Someone else's document\n\nTheir text.\n", Files.readString(file));
        ConversationFile.save(dir.resolve("mine.adoc"), "c-3", "Mine", TURNS, false, Optional.of(AUTHORS));
        assertEquals(ConversationFile.Outcome.NOT_THIS_CONVERSATION,
                ConversationFile.save(dir.resolve("mine.adoc"), "c-4", "Another", TURNS, false, Optional.of(AUTHORS)).outcome(),
                "nor another conversation's file");
    }

    @Test
    void aFileWithChangesNotCommittedIsNotAddedTo() throws Exception {
        Path dir = repository("changed");
        Path file = dir.resolve("chat.adoc");
        ConversationFile.save(file, "c-5", "Changed", TURNS.subList(0, 2), false, Optional.of(AUTHORS));
        Files.writeString(file, Files.readString(file) + "\nA note of mine.\n");
        String before = Files.readString(file);
        ConversationFile.Saved saved = ConversationFile.save(file, "c-5", "Changed", TURNS, false, Optional.of(AUTHORS));
        assertEquals(ConversationFile.Outcome.REFUSED, saved.outcome());
        assertTrue(saved.report().contains("changes that are not committed"), saved.report());
        assertEquals(before, Files.readString(file));
        assertEquals("2", git(dir, "rev-list", "--count", "HEAD"));
    }

    @Test
    void aFolderWithoutGitGetsOneVersionUnlessKometMakesItARepository() throws Exception {
        Path plain = Files.createDirectories(root.resolve("plain"));
        ConversationFile.Saved once = ConversationFile.save(plain.resolve("chat.adoc"), "c-6", "Plain", TURNS, false, Optional.of(AUTHORS));
        assertEquals(ConversationFile.Outcome.SAVED, once.outcome());
        assertFalse(once.git());
        assertEquals(0, once.commits());
        assertFalse(Files.exists(plain.resolve(".git")), "no repository was made");
        assertEquals(OptionalInt.of(4), ConversationFile.held(plain.resolve("chat.adoc"), "c-6"));

        Path made = Files.createDirectories(root.resolve("made"));
        ConversationFile.Saved history = ConversationFile.save(made.resolve("chat.adoc"), "c-7", "Made", TURNS, true, Optional.of(AUTHORS));
        assertTrue(history.git());
        assertEquals(4, history.commits());
        assertTrue(Files.isDirectory(made.resolve(".git")));
    }

    @Test
    void turnsSavedBeforeTheFolderHadGitBecomeOneCommitOfThePersons() throws Exception {
        Path dir = Files.createDirectories(root.resolve("later"));
        Path file = dir.resolve("chat.adoc");
        ConversationFile.save(file, "c-8", "Later", TURNS.subList(0, 2), false, Optional.of(AUTHORS));
        git(dir, "init", "-q");
        ConversationFile.Saved added = ConversationFile.save(file, "c-8", "Later", TURNS, false, Optional.of(AUTHORS));
        assertEquals(2, added.commits());
        assertEquals(List.of("Later, turns 1 to 2, saved before in a folder without git", "Later, turn 3, by Ada Lovelace",
                "Later, turn 4, by Ada's Claude"), git(dir, "log", "--reverse", "--format=%s").lines().toList());
    }

    @Test
    void withNoAssistantNamedTheAssistantsTurnsAreThePersonsAndTheReportSaysSo() throws Exception {
        Path dir = repository("unnamed");
        CommitAuthors personOnly = new CommitAuthors(new CommitAuthors.Author("Ada Lovelace", "ada@komet.ike.network"), Optional.empty());
        ConversationFile.Saved saved = ConversationFile.save(dir.resolve("chat.adoc"), "c-9", "Unnamed", TURNS, false, Optional.of(personOnly));
        assertTrue(saved.report().contains("names no assistant, so the assistant's turns are committed as yours"), saved.report());
        assertEquals(List.of("Ada Lovelace"), git(dir, "log", "--format=%an").lines().distinct().toList());
        assertTrue(Files.readString(dir.resolve("chat.adoc")).contains("== " + ConversationFile.ASSISTANT + "\n"));
    }

    @Test
    void eachCommitIsAtLeastASecondAfterTheOneBefore() throws Exception {
        Path dir = repository("seconds");
        Path file = dir.resolve("chat.adoc");
        List<ConversationFile.Turn> quick = List.of(new ConversationFile.Turn("Thanks.", false, T1),
                new ConversationFile.Turn("Welcome.", true, T1 + 1));
        ConversationFile.save(file, "c-10", "Quick", quick, false, Optional.of(AUTHORS));
        List<ConversationFile.Turn> more = new java.util.ArrayList<>(quick);
        more.add(new ConversationFile.Turn("One more.", false, T1 + 2));
        ConversationFile.save(file, "c-10", "Quick", more, false, Optional.of(AUTHORS));
        assertEquals(List.of(String.valueOf(T1 / 1000), String.valueOf(T1 / 1000 + 1), String.valueOf(T1 / 1000 + 2)),
                git(dir, "log", "--reverse", "--format=%at").lines().toList(), "a turn added later follows the file's last commit too");
    }

    @Test
    void theFileNameOfferedIsMadeFromTheTitle() {
        assertEquals("about-modules-a-talk.adoc", ConversationFile.fileName("About modules: a talk!"));
        assertEquals("conversation.adoc", ConversationFile.fileName("  "));
    }

    @Test
    void aGitDateIsEpochSecondsAndAnOffset() {
        assertTrue(ConversationFile.gitDate(T1).matches("@1795000000 [+-]\\d{4}"), ConversationFile.gitDate(T1));
    }

    private Path repository(String name) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name));
        git(dir, "init", "-q", "-b", "main");
        return dir;
    }

    private static String git(Path dir, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
        }
        return out;
    }
}

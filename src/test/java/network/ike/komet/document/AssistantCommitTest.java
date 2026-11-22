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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A commit of the assistant's work in a document repository has the assistant as git author and the
 * person as committer (IKE-Network/ike-issues#1399), through the git on PATH; with no variables a commit
 * names the repository's own git identity, as before. The scratch repositories run no hooks: a hook from
 * the runner's global core.hooksPath would otherwise change what the test asserts. The names are invented.
 */
class AssistantCommitTest {

    private static final CommitAuthors AUTHORS = new CommitAuthors(
            new CommitAuthors.Author("Ada Lovelace", "ada@komet.ike.network"),
            Optional.of(new CommitAuthors.Author("Ada's Claude", "ada-claude@komet.ike.network")));

    /** A hooks directory that holds no hooks. */
    private static final String NO_HOOKS = "/dev/null";

    @TempDir
    Path root;

    @Test
    void aDocumentEditOfTheAssistantsNamesTheAssistantAndThePerson() throws Exception {
        Path dir = repository("edit");
        Files.writeString(dir.resolve("doc.adoc"), "= Doc\n\nThe assistant's paragraph.\n");

        DocumentCommit.Result result = DocumentCommit.write(dir, "doc.adoc", "Edit doc.adoc\n", false, AUTHORS.assistantsWork());

        assertTrue(result.ok(), result.output());
        assertEquals("Ada's Claude <ada-claude@komet.ike.network>", head(dir, "%an <%ae>"), "the assistant wrote it");
        assertEquals("Ada Lovelace <ada@komet.ike.network>", head(dir, "%cn <%ce>"), "the person committed it");
    }

    @Test
    void withNoVariablesTheCommitIsTheRepositorysOwnIdentity() throws Exception {
        Path dir = repository("own");
        Files.writeString(dir.resolve("doc.adoc"), "= Doc\n\nThe person's paragraph.\n");

        DocumentCommit.Result result = DocumentCommit.write(dir, "doc.adoc", "Edit doc.adoc\n", false, Map.of());

        assertTrue(result.ok(), result.output());
        assertEquals("rita <rita@example.test>", head(dir, "%an <%ae>"));
        assertEquals("rita <rita@example.test>", head(dir, "%cn <%ce>"));
    }

    @Test
    void aFirstCommitOfADocumentTheAssistantMadeIsTheAssistants() throws Exception {
        Path dir = repository("first");
        Files.writeString(dir.resolve("new.adoc"), "= New\n\nWhat it is for.\n");

        DocumentCommit.Result result = DocumentCommit.first(dir, "new.adoc", "Add New\n", AUTHORS.assistantsWork());

        assertTrue(result.ok(), result.output());
        assertEquals("Ada's Claude <ada-claude@komet.ike.network>", head(dir, "%an <%ae>"));
        assertEquals("Ada Lovelace <ada@komet.ike.network>", head(dir, "%cn <%ce>"));
        assertEquals("new.adoc", git(dir, "show", "--name-only", "--format=", "HEAD"));
    }

    @Test
    void anAnnotationCommitOfTheAssistantsNamesTheAssistantAndThePerson() throws Exception {
        Path dir = repository("annotation");
        String sha = git(dir, "rev-parse", "HEAD");

        AnnotationCommit.Result result = DocumentCommit.annotate(dir, List.of(comment(dir, sha)), "A narration.", false,
                AUTHORS.assistantsWork());

        assertTrue(result.ok(), result.output());
        assertEquals(git(dir, "rev-parse", "HEAD"), result.sha());
        assertEquals("Ada's Claude <ada-claude@komet.ike.network>", head(dir, "%an <%ae>"));
        assertEquals("Ada Lovelace <ada@komet.ike.network>", head(dir, "%cn <%ce>"));
        assertEquals("", git(dir, "show", "--name-only", "--format=", "HEAD"), "an empty commit");
        assertEquals("N", head(dir, "%G?"), "unsigned, as asked");
        assertTrue(head(dir, "%B").contains("Annotation: "), head(dir, "%B"));
    }

    @Test
    void anAnnotationCommitWithNoVariablesIsWrittenAsBefore() throws Exception {
        Path dir = repository("annotation-as-before");
        String sha = git(dir, "rev-parse", "HEAD");

        AnnotationCommit.Result result = DocumentCommit.annotate(dir, List.of(comment(dir, sha)), "A narration.", false, Map.of());

        assertTrue(result.ok(), result.output());
        assertEquals("rita <rita@example.test>", head(dir, "%an <%ae>"));
        assertEquals("rita <rita@example.test>", head(dir, "%cn <%ce>"));
    }

    @Test
    void anAnnotationCommitOfTheAssistantsIsRefusedOverStagedChanges() throws Exception {
        Path dir = repository("staged");
        String sha = git(dir, "rev-parse", "HEAD");
        Files.writeString(dir.resolve("other.txt"), "staged\n");
        git(dir, "add", "other.txt");

        AnnotationCommit.Result result = DocumentCommit.annotate(dir, List.of(comment(dir, sha)), "A narration.", false,
                AUTHORS.assistantsWork());

        assertFalse(result.ok());
        assertTrue(result.output().contains("staged changes"), result.output());
        assertEquals(sha, git(dir, "rev-parse", "HEAD"), "nothing was committed");
    }

    private static Annotation comment(Path dir, String sha) throws Exception {
        return new Annotation(AnnotationKind.COMMENT.ref(),
                new AnnotationTarget.ChangeSet(new KonceptRef(GitIdentityAdapter.fromMailmap(dir).stamp(sha).uuid(), sha.substring(0, 7))),
                null, Instant.parse("2026-11-21T18:00:00Z"), UUID.fromString("5b0e1f2a-3c4d-5e6f-8a9b-0c1d2e3f4a5b"));
    }

    private Path repository(String name) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name));
        git(dir, "init", "-q", "-b", "main");
        git(dir, "config", "user.name", "rita");
        git(dir, "config", "user.email", "rita@example.test");
        git(dir, "config", "commit.gpgsign", "false");
        git(dir, "config", "core.hooksPath", NO_HOOKS);
        Files.writeString(dir.resolve("doc.adoc"), "= Doc\n\nThe first paragraph.\n");
        git(dir, "add", "doc.adoc");
        git(dir, "commit", "-q", "--no-gpg-sign", "-m", "Add the document");
        return dir;
    }

    private static String head(Path dir, String format) throws Exception {
        return git(dir, "log", "-1", "--format=" + format);
    }

    private static String git(Path dir, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes()).strip();
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
        }
        return out;
    }
}

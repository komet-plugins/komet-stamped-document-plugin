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

import dev.ikm.tinkar.common.service.PrimitiveData;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An assembly materializes as itself plus every included file, each its own document in
 * include order, and a second run writes nothing (IKE-Network/ike-issues#1307). The fixture
 * is shaped like the ANF repository: a compendium whose {@code :topics:} attribute names a
 * build-time copy, resolved through the topic-library convention onto the committed sources,
 * and one include that nothing resolves, reported by name while the rest proceed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GitDocumentAssemblyIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");

    @TempDir
    Path root;

    @BeforeAll
    void setupDatabase() {
        assertTrue(PB_STARTER_DATA.exists(), "Starter data must be present at " + PB_STARTER_DATA.getAbsolutePath());
        PrimitiveData.selectControllerByName("Load Memory-Only 64-bit Store");
        PrimitiveData.start();
        long count = new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute().getTotalCount();
        assertTrue(count > 0, "Should load entities from the starter-data protobuf file");
    }

    @AfterAll
    void teardownDatabase() {
        PrimitiveData.stop();
    }

    @Test
    void anAssemblyMaterializesEveryTopicOnceAndARerunWritesNothing() throws Exception {
        Path repo = Files.createDirectories(root.resolve("assembly"));
        gitIn(repo, "init", "-q", "-b", "main");
        Path topics = Files.createDirectories(repo.resolve("topics/src/docs/asciidoc/topics/anf"));
        Files.writeString(topics.resolve("first.adoc"), """
                :topic-id: anf-first
                [[anf-first]]
                = First Topic

                One claim.
                Another claim.
                """);
        Files.writeString(topics.resolve("second.adoc"), """
                :topic-id: anf-second
                [[anf-second]]
                = Second Topic

                A claim that cites xref:anf-first[].
                """);
        Path compendium = Files.createDirectories(repo.resolve("compendium/src/docs/asciidoc")).resolve("compendium.adoc");
        Files.writeString(compendium, """
                = Fixture Compendium
                :doctype: book

                // resolved by the build, so the topic-library convention stands in
                :topics: {generated}/topics-asciidoc

                == Part One

                include::{topics}/topics/anf/first.adoc[leveloffset=+2]

                include::{topics}/topics/anf/second.adoc[leveloffset=+2]

                include::{topics}/topics/anf/first.adoc[leveloffset=+2]

                include::{topics}/topics/anf/absent.adoc[leveloffset=+2]
                """);
        gitIn(repo, "add", "topics/src/docs/asciidoc/topics/anf/first.adoc", "topics/src/docs/asciidoc/topics/anf/second.adoc",
                "compendium/src/docs/asciidoc/compendium.adoc");
        gitIn(repo, "-c", "user.name=alice", "-c", "user.email=alice@example.test", "commit", "-q", "--no-gpg-sign",
                "--date=2026-04-01T00:00:00Z", "-m", "assemble two topics");

        GitDocumentMaterializer.AssemblyResult first = GitDocumentMaterializer.materializeAssembly(repo, compendium);

        assertEquals(List.of("compendium/src/docs/asciidoc/compendium.adoc", "anf-first", "anf-second"),
                first.documents().stream().map(GitDocumentMaterializer.Result::name).toList(),
                "the assembly itself, then each included topic once, in include order");
        assertEquals(3, first.commits(), "one commit per document");
        assertTrue(first.lines() > 0 && first.blocks() > 0);
        assertEquals(1, first.unresolved().size(), "the absent topic is reported, not fatal");
        GitDocumentMaterializer.Unresolved absent = first.unresolved().getFirst();
        assertEquals(Path.of("compendium/src/docs/asciidoc/compendium.adoc"), absent.including());
        assertEquals(15, absent.line());
        assertEquals("{topics}/topics/anf/absent.adoc", absent.target());
        for (GitDocumentMaterializer.Result document : first.documents()) {
            assertTrue(GitDocumentMaterializer.load(document.anchor()).isPresent(), document.name() + " is in the store");
        }
        Map<String, ?> stored = GitDocumentMaterializer.storedDocuments();
        assertTrue(stored.containsKey("anf-first") && stored.containsKey("anf-second"), "both topics are in the chooser: " + stored.keySet());

        GitDocumentMaterializer.AssemblyResult again = GitDocumentMaterializer.materializeAssembly(repo, compendium);

        assertEquals(first.documents().stream().map(GitDocumentMaterializer.Result::anchor).toList(),
                again.documents().stream().map(GitDocumentMaterializer.Result::anchor).toList(), "the same documents");
        assertEquals(0, again.commits(), "a re-run writes nothing");
        assertEquals(0, again.lines());
        assertEquals(0, again.blocks());
        assertEquals(first.unresolved(), again.unresolved());
    }

    /** An included file that is itself an aggregator is followed; a cycle ends where it began. */
    @Test
    void aNestedAggregatorIsFollowedAndACycleEnds() throws Exception {
        Path repo = Files.createDirectories(root.resolve("nested"));
        gitIn(repo, "init", "-q", "-b", "main");
        Files.createDirectories(repo.resolve("docs/parts"));
        Files.writeString(repo.resolve("docs/book.adoc"), "= Book\n\ninclude::parts/part.adoc[]\n");
        Files.writeString(repo.resolve("docs/parts/part.adoc"), ":topic-id: nested-part\n= Part\n\ninclude::leaf.adoc[]\n\ninclude::../book.adoc[]\n");
        Files.writeString(repo.resolve("docs/parts/leaf.adoc"), ":topic-id: nested-leaf\n= Leaf\n\nA claim.\n");
        gitIn(repo, "add", "docs/book.adoc", "docs/parts/part.adoc", "docs/parts/leaf.adoc");
        gitIn(repo, "-c", "user.name=alice", "-c", "user.email=alice@example.test", "commit", "-q", "--no-gpg-sign",
                "--date=2026-04-02T00:00:00Z", "-m", "nest");

        GitDocumentMaterializer.AssemblyResult result = GitDocumentMaterializer.materializeAssembly(repo, Path.of("docs/book.adoc"));

        assertEquals(List.of("docs/book.adoc", "nested-part", "nested-leaf"),
                result.documents().stream().map(GitDocumentMaterializer.Result::name).toList());
        assertTrue(result.unresolved().isEmpty(), "the cycle back to the book is neither followed nor an error");
    }

    /**
     * A book of a host and three chapters, the host named for the test and each chapter with its
     * own topic id, on a commit day of its own, so the runs of one test leave the documents of
     * another alone.
     *
     * @return the host file, root-relative
     */
    private Path book(Path repo, String id, int day) throws Exception {
        gitIn(repo, "init", "-q", "-b", "main");
        Files.createDirectories(repo.resolve("book/chapters"));
        Files.writeString(repo.resolve("book/" + id + ".adoc"), "= " + id + " book\n\n"
                + "include::chapters/one.adoc[]\n\ninclude::chapters/two.adoc[]\n\ninclude::chapters/absent.adoc[]\n\ninclude::chapters/three.adoc[]\n");
        for (String chapter : List.of("one", "two", "three")) {
            Files.writeString(repo.resolve("book/chapters/" + chapter + ".adoc"),
                    ":topic-id: " + id + "-" + chapter + "\n= Chapter " + chapter + "\n\nA claim of chapter " + chapter + ".\n");
        }
        gitIn(repo, "add", "book/" + id + ".adoc", "book/chapters/one.adoc", "book/chapters/two.adoc", "book/chapters/three.adoc");
        gitIn(repo, "-c", "user.name=alice", "-c", "user.email=alice@example.test", "commit", "-q", "--no-gpg-sign",
                "--date=2026-05-" + String.format("%02d", day) + "T00:00:00Z", "-m", "a book of three chapters");
        return Path.of("book/" + id + ".adoc");
    }

    /**
     * A watched run knows every file before it writes one, so a card can say "2 of 4", and it
     * reports each file as it starts and ends it, in include order; the include that is not
     * there is known from the start and does not stop the run.
     */
    @Test
    void aWatchedRunKnowsEveryFileFirstAndReportsEachInIncludeOrder() throws Exception {
        Path repo = Files.createDirectories(root.resolve("watched"));
        Path host = book(repo, "watched", 1);
        List<String> events = new java.util.ArrayList<>();
        AssemblyRun run = new AssemblyRun(new AssemblyRun.Listener() {
            @Override
            public void planned(List<Path> files, List<GitDocumentMaterializer.Unresolved> unresolved) {
                events.add("planned " + files.stream().map(f -> f.getFileName().toString()).toList() + " missing " + unresolved.size());
            }

            @Override
            public void writing(int number, int total, Path file) {
                events.add("writing " + number + "/" + total + " " + file.getFileName());
            }

            @Override
            public void written(int number, int total, GitDocumentMaterializer.Result result) {
                events.add("written " + number + "/" + total + " " + result.name());
            }
        });

        GitDocumentMaterializer.AssemblyResult result = GitDocumentMaterializer.materializeAssembly(repo, host, run);

        assertEquals(List.of(
                "planned [watched.adoc, one.adoc, two.adoc, three.adoc] missing 1",
                "writing 1/4 watched.adoc", "written 1/4 book/watched.adoc",
                "writing 2/4 one.adoc", "written 2/4 watched-one",
                "writing 3/4 two.adoc", "written 3/4 watched-two",
                "writing 4/4 three.adoc", "written 4/4 watched-three"), events);
        assertEquals(4, result.documents().size());
        assertFalse(result.stopped(), "a run nobody stopped writes every file");
        assertTrue(result.notWritten().isEmpty());
        assertEquals(1, result.unresolved().size(), "the absent chapter is reported, not fatal");
        assertEquals("chapters/absent.adoc", result.unresolved().getFirst().target());
        assertEquals(AssemblyRun.State.RUNNING, run.state());
    }

    /**
     * Stop keeps the files already written and leaves the rest out of the store, named in the
     * result; opening the file again writes only what is missing.
     */
    @Test
    void aStoppedRunKeepsWhatItWroteAndALaterRunWritesTheRest() throws Exception {
        Path repo = Files.createDirectories(root.resolve("stopped"));
        Path host = book(repo, "stopped", 2);
        AssemblyRun[] holder = new AssemblyRun[1];
        holder[0] = new AssemblyRun(new AssemblyRun.Listener() {
            @Override
            public void written(int number, int total, GitDocumentMaterializer.Result result) {
                if (number == 2) {
                    holder[0].stop();                              // as the strip's Stop does, once the second file is written
                }
            }
        });

        GitDocumentMaterializer.AssemblyResult stopped = GitDocumentMaterializer.materializeAssembly(repo, host, holder[0]);

        assertEquals(AssemblyRun.State.STOPPED, holder[0].state());
        assertTrue(stopped.stopped());
        assertEquals(List.of("book/stopped.adoc", "stopped-one"), stopped.documents().stream().map(GitDocumentMaterializer.Result::name).toList(),
                "the files written before the stop are kept");
        Path tree = repo.toAbsolutePath().normalize();
        assertEquals(List.of(tree.resolve("book/chapters/two.adoc"), tree.resolve("book/chapters/three.adoc")), stopped.notWritten(),
                "the files the run did not reach, in include order");
        assertTrue(stopped.summary().contains("stopped with 2 file(s) not materialized"), stopped.summary());
        Map<String, ?> stored = GitDocumentMaterializer.storedDocuments();
        assertTrue(stored.containsKey("stopped-one"), "a file written before the stop is in the store");
        assertFalse(stored.containsKey("stopped-two") || stored.containsKey("stopped-three"),
                "a file the run did not reach is not in the store: " + stored.keySet());

        GitDocumentMaterializer.AssemblyResult rest = GitDocumentMaterializer.materializeAssembly(repo, host);

        assertFalse(rest.stopped());
        assertEquals(List.of(0, 0, 1, 1), rest.documents().stream().map(GitDocumentMaterializer.Result::commits).toList(),
                "the second run writes only the files the first did not reach");
        assertTrue(GitDocumentMaterializer.storedDocuments().keySet().containsAll(List.of("stopped-two", "stopped-three")));
    }

    /** A paused run waits between files until it is resumed, and then writes the rest. */
    @Test
    void aPausedRunWaitsBetweenFilesUntilResumed() throws Exception {
        Path repo = Files.createDirectories(root.resolve("paused"));
        Path host = book(repo, "paused", 3);
        java.util.concurrent.CountDownLatch pausedAfterFirst = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger written = new java.util.concurrent.atomic.AtomicInteger();
        AssemblyRun[] holder = new AssemblyRun[1];
        holder[0] = new AssemblyRun(new AssemblyRun.Listener() {
            @Override
            public void written(int number, int total, GitDocumentMaterializer.Result result) {
                written.set(number);
                if (number == 1) {
                    holder[0].pause();
                    pausedAfterFirst.countDown();
                }
            }
        });
        java.util.concurrent.CompletableFuture<GitDocumentMaterializer.AssemblyResult> running = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return GitDocumentMaterializer.materializeAssembly(repo, host, holder[0]);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });

        assertTrue(pausedAfterFirst.await(60, java.util.concurrent.TimeUnit.SECONDS), "the first file is written");
        assertEquals(AssemblyRun.State.PAUSED, holder[0].state());
        assertThrows(java.util.concurrent.TimeoutException.class, () -> running.get(500, java.util.concurrent.TimeUnit.MILLISECONDS),
                "a paused run does not finish");
        assertEquals(1, written.get(), "a paused run writes no further file");

        holder[0].resume();
        GitDocumentMaterializer.AssemblyResult result = running.get(60, java.util.concurrent.TimeUnit.SECONDS);

        assertEquals(AssemblyRun.State.RUNNING, holder[0].state());
        assertEquals(4, result.documents().size(), "after Resume the run writes the rest");
        assertFalse(result.stopped());
    }

    /** Stop also ends a paused run, which then writes nothing further. */
    @Test
    void stopEndsAPausedRun() throws Exception {
        Path repo = Files.createDirectories(root.resolve("paused-stopped"));
        Path host = book(repo, "pausedstop", 4);
        AssemblyRun run = AssemblyRun.unattended();
        run.pause();
        java.util.concurrent.CompletableFuture<GitDocumentMaterializer.AssemblyResult> running = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return GitDocumentMaterializer.materializeAssembly(repo, host, run);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        assertThrows(java.util.concurrent.TimeoutException.class, () -> running.get(500, java.util.concurrent.TimeUnit.MILLISECONDS));

        run.stop();
        GitDocumentMaterializer.AssemblyResult result = running.get(60, java.util.concurrent.TimeUnit.SECONDS);

        assertTrue(result.documents().isEmpty(), "a run stopped before its first file writes none");
        assertEquals(4, result.notWritten().size());
    }

    private static void gitIn(Path dir, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
        }
    }

    /**
     * An include that names a library another repository holds resolves under the AsciiDoc roots
     * of the working trees beside this one, as the origin manifest lists them, and the child
     * materializes under the tree that holds it (topic {@code dev-adoc-packages},
     * IKE-Network/ike-issues#1345): the UNLV proposal including ike-lab-documents topics.
     */
    @Test
    void anIncludeOfALibraryInAnotherWorkingTreeMaterializesUnderThatTree() throws Exception {
        Path folder = Files.createDirectories(root.resolve("fleet"));
        Files.createDirectories(folder.resolve("scripts"));
        Files.writeString(folder.resolve(WorkingTrees.ORIGINS), """
                proposals https://example.test/proposals.git
                documents https://example.test/documents.git
                """);
        Path documents = Files.createDirectories(folder.resolve("documents"));
        gitIn(documents, "init", "-q", "-b", "main");
        Path library = Files.createDirectories(documents.resolve("topics/src/docs/asciidoc/network.ike.lab/ikm"));
        Files.writeString(library.resolve("health-it-challenges.adoc"), """
                :topic-id: ikm-health-it-challenges
                [[ikm-health-it-challenges]]
                = Health IT Challenges

                A claim from the lab documents.
                """);
        gitIn(documents, "add", "topics/src/docs/asciidoc/network.ike.lab/ikm/health-it-challenges.adoc");
        gitIn(documents, "-c", "user.name=alice", "-c", "user.email=alice@example.test", "commit", "-q", "--no-gpg-sign",
                "--date=2026-04-01T00:00:00Z", "-m", "a lab topic");

        Path proposals = Files.createDirectories(folder.resolve("proposals"));
        gitIn(proposals, "init", "-q", "-b", "main");
        Path own = Files.createDirectories(proposals.resolve("unlv-topics/src/docs/asciidoc/network.ike.lab/lab"));
        Files.writeString(own.resolve("infrastructure-imperative.adoc"), """
                :topic-id: lab-infrastructure-imperative
                [[lab-infrastructure-imperative]]
                = The Infrastructure Imperative

                A claim from the proposals.
                """);
        Path proposal = Files.createDirectories(proposals.resolve("ike-lab-unlv-proposal/src/docs/asciidoc")).resolve("proposal.adoc");
        Files.writeString(proposal, """
                = Proposal
                :doctype: book

                include::{adoc}/network.ike.lab/lab/infrastructure-imperative.adoc[leveloffset=+1]

                include::{adoc}/network.ike.lab/ikm/health-it-challenges.adoc[leveloffset=+1]

                include::{adoc}/network.ike.lab/ikm/absent.adoc[leveloffset=+1]
                """);
        gitIn(proposals, "add", "unlv-topics/src/docs/asciidoc/network.ike.lab/lab/infrastructure-imperative.adoc",
                "ike-lab-unlv-proposal/src/docs/asciidoc/proposal.adoc");
        gitIn(proposals, "-c", "user.name=alice", "-c", "user.email=alice@example.test", "commit", "-q", "--no-gpg-sign",
                "--date=2026-04-02T00:00:00Z", "-m", "a proposal over two libraries");

        GitDocumentMaterializer.AssemblyResult result = GitDocumentMaterializer.materializeAssembly(proposals, proposal);

        assertEquals(List.of("ike-lab-unlv-proposal/src/docs/asciidoc/proposal.adoc", "lab-infrastructure-imperative", "ikm-health-it-challenges"),
                result.documents().stream().map(GitDocumentMaterializer.Result::name).toList(),
                "the proposal, its own library's topic, then the topic the other working tree holds");
        assertEquals(GitDocumentMaterializer.documentAnchor(documents, "topics/src/docs/asciidoc/network.ike.lab/ikm/health-it-challenges.adoc"),
                result.documents().get(2).anchor(), "the child is the document its file materializes under in its own tree");
        assertEquals(GitDocumentMaterializer.documentAnchor(proposals, "unlv-topics/src/docs/asciidoc/network.ike.lab/lab/infrastructure-imperative.adoc"),
                result.documents().get(1).anchor());
        assertEquals(1, result.unresolved().size(), "the absent topic is reported, not fatal: " + result.unresolved());
        GitDocumentMaterializer.Unresolved absent = result.unresolved().getFirst();
        assertEquals("{adoc}/network.ike.lab/ikm/absent.adoc", absent.target());
        assertTrue(absent.reason().contains("beside it"), "the neighbours were looked in: " + absent.reason());

        GitDocumentMaterializer.AssemblyResult again = GitDocumentMaterializer.materializeAssembly(proposals, proposal);
        assertEquals(0, again.commits(), "a re-run writes nothing");
    }
}

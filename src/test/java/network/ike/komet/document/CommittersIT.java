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
import design.knowledge.stamp.adoc.identity.Identity;
import design.knowledge.stamp.adoc.model.Annotation;
import design.knowledge.stamp.adoc.model.AnnotationKind;
import design.knowledge.stamp.adoc.model.LineRange;
import design.knowledge.stamp.adoc.model.Person;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.StampEntity;
import dev.ikm.tinkar.entity.boundary.EntityBoundaries;
import dev.ikm.tinkar.entity.boundary.ModuleKinds;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import jfx.incubator.scene.control.richtext.RichTextArea;
import network.ike.komet.document.GitDocumentMaterializer.StoredDocument;
import network.ike.komet.document.GitDocumentMaterializer.StoredLine;
import network.ike.komet.document.surface.BlockFactory;
import network.ike.komet.document.surface.DocumentSurface;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who committed a commit, as review says it, and the mark of an assistant's work that its person did not
 * commit (IKE-Network/ike-issues#1399, decision 7). Each test has people of its own, with invented names,
 * since the author concepts of one store are shared by every document in it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommittersIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");
    private static final ViewCalculator VIEW_OF_NOTHING = null;

    @TempDir
    Path root;

    @BeforeAll
    void setup() {
        assertTrue(PB_STARTER_DATA.exists(), "Starter data must be present at " + PB_STARTER_DATA.getAbsolutePath());
        try {
            HeadlessFx.select();
            Platform.startup(() -> { });
        } catch (IllegalStateException alreadyStarted) {
            // the toolkit outlives a test class
        } catch (RuntimeException | Error noToolkit) {
            Assumptions.abort("no JavaFX toolkit here: " + noToolkit.getMessage());
        }
        PrimitiveData.selectControllerByName("Load Memory-Only 64-bit Store");
        PrimitiveData.start();
        assertTrue(new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute().getTotalCount() > 0);
        System.setProperty(VertexMaterializer.MODEL_PROPERTY, "vertex");
    }

    @AfterAll
    void teardown() {
        System.clearProperty(VertexMaterializer.MODEL_PROPERTY);
        ModuleKinds.clear();
        EntityBoundaries.clear();
        PrimitiveData.stop();
    }

    @Test
    void theCommitterIsShownWhereItIsNotTheAuthorAndMarkedWhereItIsNotTheAssistantsPerson() throws Exception {
        People ada = new People("Ada Lovelace", "ada");
        Person charles = new Person("Charles Babbage", "charles@example.test");
        Path dir = repository("ada");
        ada.record();
        commit(dir, "Her own claim.", ada.person, ada.person);
        commit(dir, "Her own claim, committed for her.", ada.person, charles);
        commit(dir, "A claim of the assistant.", ada.assistant, ada.person);
        commit(dir, "A claim of the assistant under an earlier address.", ada.assistant, ada.earlier);
        commit(dir, "A claim of the assistant that another person committed.", ada.assistant, charles);
        commit(dir, "A claim that the assistant committed itself.", ada.assistant, ada.assistant);
        commit(dir, "An earlier claim.", ada.earlier, ada.earlier);
        commit(dir, "A claim of his own.", charles, charles);
        Read read = read(dir);

        assertEquals(Optional.empty(), read.of("Her own claim."), "the committer is the author");
        assertEquals(shown("Charles Babbage"), read.of("Her own claim, committed for her."), "she is no one's assistant, so it is not marked");
        assertEquals(shown("Ada Lovelace"), read.of("A claim of the assistant."), "the committer and the record say the same of it, and both are shown");
        assertEquals(shown("Ada Lovelace (ada@example.test)"), read.of("A claim of the assistant under an earlier address."),
                "she is responsible for the earlier identity too, and the address says which it is");
        assertEquals(marked("Charles Babbage (charles@example.test), not by Ada Lovelace"),
                read.of("A claim of the assistant that another person committed."));
        assertEquals(marked("Ada's Claude (ada-claude@komet.ike.network), not by Ada Lovelace"),
                read.of("A claim that the assistant committed itself."), "no person committed it");
        assertEquals(Optional.empty(), read.of("An earlier claim."));
        assertEquals(Optional.empty(), read.of("A claim of his own."));

        assertEquals("Committed by Charles Babbage (charles@example.test), not by Ada Lovelace",
                read.of("A claim of the assistant that another person committed.").orElseThrow().text());
        StampEntity<?> elsewhere = read.line("A claim of the assistant that another person committed.").introduced();
        assertTrue(read.summaries.committerDiffers(elsewhere));
        assertFalse(read.summaries.committerDiffers(read.line("A claim of the assistant.").introduced()));
        String sha = read.summaries.sha(elsewhere.nid()).orElseThrow();
        assertEquals("\nCommitted by Charles Babbage (charles@example.test), not by Ada Lovelace\n" + sha.substring(0, 7)
                + "  A claim of the assistant that another person committed.", read.summaries.lines(Calculators.View.Default(), elsewhere));
        assertEquals(marked("Charles Babbage (charles@example.test), not by Ada Lovelace"),
                read.summaries.committers().of(Calculators.View.Default(), sha), "as the History view asks, by the commit");
        assertEquals(read.of("A claim of the assistant."), read.summaries.committed(VIEW_OF_NOTHING, read.line("A claim of the assistant.").introduced()),
                "with no view the person's recorded name is the one");
    }

    @Test
    void withNoRecordACommitterIsShownAndNothingIsMarked() throws Exception {
        People grace = new People("Grace Hopper", "grace");
        Path dir = repository("grace");
        commit(dir, "A claim of the assistant.", grace.assistant, new Person("Someone Else", "someone@example.test"));
        assertEquals(shown("Someone Else"), read(dir).of("A claim of the assistant."), "nothing says whose assistant it is");

        // the record is read when the document is: once the knowledge base has it, the same commit is marked
        grace.record();
        assertEquals(marked("Someone Else (someone@example.test), not by Grace Hopper"), read(dir).of("A claim of the assistant."));
    }

    @Test
    void withNoWorkingTreeNoCommitterIsKnown() throws Exception {
        People mary = new People("Mary Somerville", "mary");
        Path dir = repository("mary");
        mary.record();
        commit(dir, "A claim of the assistant.", mary.assistant, mary.assistant);
        Read read = read(dir);
        StampEntity<?> stamp = read.line("A claim of the assistant.").introduced();
        assertTrue(read.summaries.committerDiffers(stamp));

        CommitSummaries unread = CommitSummaries.read(read.document.anchor(), null, null);
        assertEquals(Optional.empty(), unread.committed(Calculators.View.Default(), stamp));
        assertFalse(unread.committerDiffers(stamp));
        assertTrue(unread.summary(stamp.nid()).isPresent(), "the store has the SHA alone");
        assertEquals(Optional.empty(), Committers.read(root.resolve("no-such-folder")).of(Calculators.View.Default(), Optional.empty(), stamp));
        assertFalse(GitStampVocabulary.entityAbsent(GitStampTerms.concept(identity(mary.assistant))));
    }

    @Test
    void anAnnotationSaysItsCommitterToo() throws Exception {
        People emmy = new People("Emmy Noether", "emmy");
        Person other = new Person("David Hilbert", "david@example.test");
        Path dir = repository("emmy");
        emmy.record();
        commit(dir, "The first claim.", emmy.person, emmy.person);
        // the annotation's commit changes no file, so the store records no SHA for its STAMP
        annotate(dir, 5, "Read twice.", emmy.assistant, emmy.person);
        commit(dir, "The second claim.", emmy.person, emmy.person);
        annotate(dir, 6, "Read once.", emmy.assistant, other);
        Read read = read(dir);

        GitDocumentMaterializer.StoredAnnotation first = read.line("The first claim.").annotations().getFirst();
        GitDocumentMaterializer.StoredAnnotation second = read.line("The second claim.").annotations().getFirst();
        assertEquals(Optional.empty(), read.summaries.sha(first.stamp().nid()));
        String firstText = ProvenancePopover.annotationText(first, Calculators.View.Default(), read.summaries);
        assertTrue(firstText.startsWith("… Commented on by Emmy's Claude, directed by Emmy Noether, committed by Emmy Noether on "), firstText);
        String secondText = ProvenancePopover.annotationText(second, Calculators.View.Default(), read.summaries);
        assertTrue(secondText.startsWith("… Commented on by Emmy's Claude, directed by Emmy Noether, "
                + "committed by David Hilbert (david@example.test), not by Emmy Noether on "), secondText);
        assertTrue(ProvenancePopover.annotationText(second, Calculators.View.Default())
                .startsWith("… Commented on by Emmy's Claude, directed by Emmy Noether on "), "with no repository read, the author alone");
        assertFalse(read.summaries.committerDiffers(first.stamp()));
        assertTrue(read.summaries.committerDiffers(second.stamp()));
    }

    /** A rebase, or an amendment, keeps the author and the author's time and replaces the SHA and the committer. */
    @Test
    void aCommitARebaseReplacedIsFoundByItsAuthorAndTime() throws Exception {
        People sofia = new People("Sofia Kovalevskaya", "sofia");
        Path dir = repository("sofia");
        sofia.record();
        commit(dir, "A claim of the assistant.", sofia.assistant, sofia.person);
        Read read = read(dir);
        assertEquals(shown("Sofia Kovalevskaya"), read.of("A claim of the assistant."));

        ProcessBuilder amend = new ProcessBuilder("git", "commit", "-q", "--amend", "--no-edit").directory(dir.toFile());
        amend.environment().put("GIT_COMMITTER_NAME", "Someone Else");
        amend.environment().put("GIT_COMMITTER_EMAIL", "someone@example.test");
        run(amend);
        StampEntity<?> stamp = read.line("A claim of the assistant.").introduced();
        CommitSummaries again = CommitSummaries.read(read.document.anchor(), dir, Path.of("sofia.adoc"));
        assertEquals(marked("Someone Else (someone@example.test), not by Sofia Kovalevskaya"),
                again.committed(Calculators.View.Default(), stamp), "HEAD moved, so the log was read again");
    }

    @Test
    void theMailmapJoinsACommittersAddresses() throws Exception {
        People hypatia = new People("Hypatia", "hypatia");
        Path dir = repository("hypatia");
        hypatia.record();
        Files.writeString(dir.resolve(".mailmap"), "Hypatia <hypatia@komet.ike.network> <hypatia@old.test>\n");
        commit(dir, "A claim of the assistant.", hypatia.assistant, new Person("H.", "hypatia@old.test"));

        assertEquals(shown("Hypatia"), read(dir).of("A claim of the assistant."), "the committer is the person's identity address, by the mailmap");
    }

    @Test
    void theMarkIsDrawnInTheMarginsWhereItChanges() throws Exception {
        People rosa = new People("Rosa Parks", "rosa");
        Person other = new Person("Someone Else", "someone@example.test");
        Path dir = repository("rosa");
        rosa.record();
        commit(dir, "First paragraph, hers.\n", rosa.person, rosa.person);
        commit(dir, "Second paragraph, the assistant's.\n", rosa.assistant, rosa.person);
        commit(dir, "Third paragraph, committed by another person.\n\nFourth paragraph, of the same commit.\n", rosa.assistant, other);
        commit(dir, "Fifth paragraph, hers.\n", rosa.person, rosa.person);
        annotate(dir, 7, "A comment on the second.", rosa.assistant, other);
        Read read = read(dir);

        DocumentSurface surface = onFx(() -> new DocumentSurface(new BlockFactory(Calculators.View.Default(), 13)));
        DocumentSurfaceView view = onFx(() -> {
            Stage stage = new Stage();
            stage.setScene(new Scene(surface, 560, 700));            // narrow, so the margin is too
            stage.show();
            DocumentSurfaceView v = new DocumentSurfaceView(host(read.summaries), surface);
            v.show(DocumentAssembly.single(read.document, "rosa.adoc"));
            return v;
        });
        pulses();

        assertEquals(List.of(), onFx(() -> marks(surface, "First paragraph")));
        assertEquals(List.of("committer, true: Committed by Someone Else (someone@example.test), not by Rosa Parks"),
                onFx(() -> marks(surface, "Second paragraph")), "its person committed it, and not the comment on it: the mark is the note's");
        assertTrue(onFx(() -> {
            Label day = (Label) surface.lookup(".stamped-document-note").lookupAll(".label").stream()
                    .filter(node -> ((Label) node).getText().matches("\\d{4}-\\d{2}-\\d{2}")).findFirst().orElseThrow();
            return day.getWidth() >= day.prefWidth(-1) - 0.5;
        }), "the note's day is drawn whole beside the mark");
        assertEquals(List.of("committer, true: Committed by Someone Else (someone@example.test), not by Rosa Parks: 1 of these 1 line(s)"),
                onFx(() -> marks(surface, "Third paragraph")));
        assertEquals(List.of("committer, false: Committed by Someone Else (someone@example.test), not by Rosa Parks: 1 of these 1 line(s)"),
                onFx(() -> marks(surface, "Fourth paragraph")), "the same commit as the block above: drawn under the pointer");
        assertEquals(List.of(), onFx(() -> marks(surface, "Fifth paragraph")));

        onFx(() -> {
            view.setView(DocumentSurfaceView.View.SOURCE);
            return null;
        });
        pulses();
        assertEquals(List.of("!: Committed by Someone Else (someone@example.test), not by Rosa Parks"), onFx(() -> {
            RichTextArea ledger = surface.block(0).richText();
            List<String> found = new ArrayList<>();
            for (Node node : ledger.lookup(".left-side").lookupAll(".stamped-document-committer")) {
                found.add(((Label) node).getText() + ": " + ((Label) node).getTooltip().getText());
            }
            return found;
        }), "one mark, on the first line of the run");

        // the popover over a line: the committer under the author, in the mark's colour where it is marked
        assertEquals("Committed by Rosa Parks", onFx(() -> popover(read, "Second paragraph, the assistant's.").getText()));
        Label committer = onFx(() -> popover(read, "Third paragraph, committed by another person."));
        assertEquals("Committed by Someone Else (someone@example.test), not by Rosa Parks", committer.getText());
        assertTrue(committer.getStyle().contains(Committers.Committed.MARK_COLOUR), committer.getStyle());
        assertEquals(null, onFx(() -> popover(read, "First paragraph, hers.")), "nothing where the committer is the author");
    }

    private static Label popover(Read read, String text) {
        Node content = ProvenancePopover.content(read.line(text), Calculators.View.Default(), read.summaries,
                stamp -> new javafx.scene.image.WritableImage(1, 1), null);
        new Scene((javafx.scene.Parent) content);
        return (Label) content.lookup("#stampedDocumentProvenanceCommitter");
    }

    /** The committer's marks in the margin of the block that starts with a text: the word, whether it is drawn, and its tooltip. */
    private static List<String> marks(DocumentSurface surface, String startingWith) {
        for (int i = 0; i < surface.blockCount(); i++) {
            if (surface.block(i).plainText().strip().startsWith(startingWith)) {
                List<String> found = new ArrayList<>();
                for (Node node : surface.block(i).stackNode().lookupAll(".stamped-document-committer")) {
                    boolean drawn = node.isVisible() && node.getParent().isVisible();
                    assertTrue(((Label) node).getWidth() >= ((Label) node).prefWidth(-1) - 0.5, "the word is drawn whole");
                    found.add(((Label) node).getText() + ", " + drawn + ": " + ((Label) node).getTooltip().getText());
                }
                return found;
            }
        }
        throw new AssertionError("no block starts with " + startingWith);
    }

    // ── a person, their assistant and an earlier address ─────────────────────

    /** A person, the assistant they direct and an address they committed under earlier, each with a name of the test's own. */
    private static final class People {
        final Person person;
        final Person assistant;
        final Person earlier;

        People(String name, String key) {
            person = new Person(name, key + "@komet.ike.network");
            assistant = new Person(name.split(" ")[0] + "'s Claude", key + "-claude@komet.ike.network");
            earlier = new Person(name, key + "@example.test");
        }

        /** Records that the person is responsible for the other two, as Komet does when it reads a document with commits by them. */
        void record() {
            GitStampVocabulary.seedAuthorIfAbsent(identity(assistant));
            GitStampVocabulary.seedAuthorIfAbsent(identity(earlier));
            assertEquals(2, ResponsiblePersons.recordIfAbsent(List.of(identity(assistant), identity(earlier)),
                    new CommitAuthors(new CommitAuthors.Author(person.name(), person.email()),
                            Optional.of(new CommitAuthors.Author(assistant.name(), assistant.email())), List.of(earlier.email()))));
        }
    }

    private static Identity identity(Person person) {
        return GitIdentityAdapter.raw().author(person);
    }

    private static Optional<Committers.Committed> shown(String who) {
        return Optional.of(new Committers.Committed(name(who), who, false));
    }

    private static Optional<Committers.Committed> marked(String who) {
        return Optional.of(new Committers.Committed(name(who), who, true));
    }

    /** The committer's name alone, out of what is said of the committer. */
    private static String name(String who) {
        int address = who.indexOf(" (");
        return address < 0 ? who : who.substring(0, address);
    }

    /** A document as the card reads it. */
    private record Read(StoredDocument document, CommitSummaries summaries) {

        StoredLine line(String text) {
            return document.lines().stream().filter(line -> line.text().equals(text)).findFirst()
                    .orElseThrow(() -> new AssertionError("no line reads " + text));
        }

        Optional<Committers.Committed> of(String text) {
            return summaries.committed(Calculators.View.Default(), line(text).introduced());
        }
    }

    private static Read read(Path dir) throws Exception {
        String file = dir.getFileName() + ".adoc";
        PublicId anchor = GitDocumentMaterializer.materialize(dir, dir.resolve(file)).anchor();
        return new Read(GitDocumentMaterializer.load(anchor, true).orElseThrow(), CommitSummaries.read(anchor, dir, Path.of(file)));
    }

    // ── git ──────────────────────────────────────────────────────────────────

    /** Each commit an hour after the one before: two commits of one author in the same second are one STAMP. */
    private int hour;

    private Path repository(String name) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name));
        git(dir, "init", "-q", "-b", "main");
        git(dir, "config", "user.name", "Repository Owner");
        git(dir, "config", "user.email", "owner@example.test");
        git(dir, "config", "commit.gpgsign", "false");
        git(dir, "config", "core.hooksPath", "/dev/null");
        Files.writeString(dir.resolve(name + ".adoc"), ":topic-id: dev-committers-" + name + "\n\n= Committers " + name + "\n\n");
        return dir;
    }

    /** Adds lines to the repository's document and commits them under an author and a committer. */
    private void commit(Path dir, String lines, Person author, Person committer) throws Exception {
        Path file = dir.resolve(dir.getFileName() + ".adoc");
        Files.writeString(file, Files.readString(file) + lines + "\n");
        git(dir, "add", ".");
        ProcessBuilder commit = new ProcessBuilder("git", "commit", "-q", "-m", lines.lines().findFirst().orElseThrow()).directory(dir.toFile());
        people(commit, author, committer);
        run(commit);
    }

    /** Comments on a line of the document at HEAD, as an empty commit under an author and a committer. */
    private void annotate(Path dir, int line, String text, Person author, Person committer) throws Exception {
        Path file = Path.of(dir.getFileName() + ".adoc");
        String head = GitOperations.head(dir).orElseThrow();
        Annotation comment = Annotation.of(AnnotationKind.COMMENT, LineRange.of(file, line, line, head, Files.readAllLines(dir.resolve(file))));
        ProcessBuilder commit = new ProcessBuilder("git", "commit", "-q", "--allow-empty", "-m",
                AnnotationCommit.subject(List.of(comment)) + "\n\n" + text + "\n\n" + comment.trailer()).directory(dir.toFile());
        people(commit, author, committer);
        run(commit);
    }

    private void people(ProcessBuilder commit, Person author, Person committer) {
        String when = String.format("2026-11-%02dT%02d:00:00Z", 1 + hour / 24, hour % 24);
        hour++;
        commit.environment().put("GIT_AUTHOR_NAME", author.name());
        commit.environment().put("GIT_AUTHOR_EMAIL", author.email());
        commit.environment().put("GIT_AUTHOR_DATE", when);
        commit.environment().put("GIT_COMMITTER_NAME", committer.name());
        commit.environment().put("GIT_COMMITTER_EMAIL", committer.email());
        commit.environment().put("GIT_COMMITTER_DATE", when);
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        run(new ProcessBuilder(command).directory(dir.toFile()));
    }

    private static void run(ProcessBuilder command) throws Exception {
        Process p = command.redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes()).strip();
        if (p.waitFor() != 0) {
            throw new IllegalStateException(String.join(" ", command.command()) + " failed: " + out);
        }
    }

    // ── JavaFX ───────────────────────────────────────────────────────────────

    private static DocumentSurfaceView.Host host(CommitSummaries summaries) {
        return new DocumentSurfaceView.Host() {
            @Override
            public ViewCalculator view() {
                return Calculators.View.Default();
            }

            @Override
            public CommitSummaries summaries() {
                return summaries;
            }

            @Override
            public void status(String text) { }

            @Override
            public void annotate(AnnotationKind kind) { }

            @Override
            public void saveEdit(GitDocumentMaterializer.StoredBlock block, String text) { }

            @Override
            public void showHistory() { }

            @Override
            public void selectionChanged() { }

            @Override
            public void showingMember(DocumentAssembly.Member member) { }
        };
    }

    /** Lets the window's pulses lay the view out. */
    private static void pulses() throws Exception {
        for (int i = 0; i < 12; i++) {
            Thread.sleep(60);
            onFx(() -> null);
        }
    }

    private static <T> T onFx(Callable<T> body) throws Exception {
        CompletableFuture<T> done = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                done.complete(body.call());
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        return done.get(30, TimeUnit.SECONDS);
    }
}

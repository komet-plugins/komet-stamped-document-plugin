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
import design.knowledge.stamp.adoc.model.Annotation;
import design.knowledge.stamp.adoc.model.AnnotationKind;
import design.knowledge.stamp.adoc.model.LineRange;
import dev.ikm.komet.framework.view.ObservableViewNoOverride;
import dev.ikm.komet.framework.view.ViewProperties;
import dev.ikm.komet.layout.context.KlContext;
import dev.ikm.komet.preferences.KometPreferences;
import dev.ikm.komet.preferences.KometPreferencesImpl;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Coordinates;
import dev.ikm.tinkar.entity.boundary.EntityBoundaries;
import dev.ikm.tinkar.entity.boundary.ModuleKinds;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import network.ike.komet.document.GitDocumentMaterializer.StoredAnnotation;
import network.ike.komet.document.GitDocumentMaterializer.StoredLine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Annotating and retracting in a real STAMPed Document card, on the starter set and a real git repository:
 * a comment is written in the margin and recorded as a commit, and Retract… withdraws it. Each test has a
 * repository, a file and a topic of its own.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnnotationCardIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");
    private static final String READ_AT = "network.ike.komet.document.readAt.";

    @TempDir
    static Path root;

    private String home;
    private ViewProperties viewProperties;
    private KometPreferences windows;
    private final List<StampedDocumentCard> cards = new ArrayList<>();

    @BeforeAll
    void setup() throws Exception {
        assertTrue(PB_STARTER_DATA.exists(), "Starter data must be present at " + PB_STARTER_DATA.getAbsolutePath());
        try {
            HeadlessFx.select();
            Platform.startup(() -> { });
        } catch (IllegalStateException alreadyStarted) {
            // the toolkit outlives a test class
        } catch (RuntimeException | Error noToolkit) {
            Assumptions.abort("no JavaFX toolkit here: " + noToolkit.getMessage());
        }
        Platform.setImplicitExit(false);                            // a test's window closes before the next one's opens
        home = System.getProperty("user.home");
        System.setProperty("user.home", Files.createDirectories(root.resolve("home")).toString());   // no document library, no file of authors
        PrimitiveData.selectControllerByName("Load Memory-Only 64-bit Store");
        PrimitiveData.start();
        assertTrue(new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute().getTotalCount() > 0);
        System.setProperty(VertexMaterializer.MODEL_PROPERTY, "vertex");
        viewProperties = onFx(() -> new ObservableViewNoOverride(Coordinates.View.DefaultView()).makeOverridableViewProperties());
        windows = KometPreferencesImpl.getConfigurationRootPreferences().node("annotation-card-" + UUID.randomUUID());
    }

    @AfterEach
    void closeTheCards() throws Exception {
        settle();
        onFx(() -> {
            for (StampedDocumentCard card : cards) {
                card.knowledgeLayoutUnbind();
                card.fxObject().getScene().getWindow().hide();
            }
            return null;
        });
        cards.clear();
    }

    @AfterAll
    void teardown() {
        try {
            windows.removeNode();
        } catch (Exception neverWritten) {
            // a node never flushed has no folder to delete
        }
        System.clearProperty(VertexMaterializer.MODEL_PROPERTY);
        ModuleKinds.clear();
        EntityBoundaries.clear();
        PrimitiveData.stop();
        System.setProperty("user.home", home);
    }

    /**
     * A comment's commit changes no file, so the store has no SHA for its STAMP. Retract… finds the commit
     * in the repository, names it in the retraction, and the comment loses force.
     */
    @Test
    void aCommentWrittenInTheMarginIsRetracted() throws Exception {
        Path dir = repository("margin");
        StampedDocumentCard card = open(dir);

        comment(card, 4, "Say where this was measured.");
        String commented = head(dir);
        assertTrue(message(dir).contains("\n\nSay where this was measured.\n\nAnnotation: "), message(dir));
        assertEquals(1, onFx(() -> line(card, 4).inForce().size()));

        DialogPane confirm = retract(card, 4);
        assertTrue(onFx(confirm::getHeaderText).contains(commented.substring(0, 7)), onFx(confirm::getHeaderText));
        confirm(confirm, "It is in the next section.");
        await("the retraction is recorded", () -> status(card).startsWith("Retraction recorded as commit "));

        assertTrue(message(dir).contains("@" + commented), "the retraction names the comment's commit in full: " + message(dir));
        assertEquals(List.of(), onFx(() -> line(card, 4).inForce()), "the comment lost force");
        StoredAnnotation withdrawn = onFx(() -> line(card, 4).annotations().getFirst());
        assertTrue(withdrawn.retracted());
        assertEquals("Say where this was measured.", withdrawn.text());
    }

    /** Several annotations in force on a line are chosen from, each named by its own commit. */
    @Test
    void eachAnnotationOfALineIsOfferedByItsOwnCommit() throws Exception {
        Path dir = repository("several");
        StampedDocumentCard card = open(dir);
        comment(card, 4, "The first comment.");
        String first = head(dir);
        comment(card, 4, "The second comment.");
        String second = head(dir);

        DialogPane choose = retract(card, 4);
        assertEquals("stampedDocumentRetractChoice", onFx(choose::getId));
        List<String> offered = onFx(() -> choices(choose));
        assertEquals(2, offered.size(), offered.toString());
        assertTrue(offered.get(0).startsWith(first.substring(0, 7)) && offered.get(0).endsWith("The first comment."), offered.toString());
        assertTrue(offered.get(1).startsWith(second.substring(0, 7)) && offered.get(1).endsWith("The second comment."), offered.toString());
        press(choose, "Cancel");
    }

    /** A line of an included file: the annotation is the file's own, and so is its retraction. */
    @Test
    void anAnnotationOfAnIncludedFileIsRetracted() throws Exception {
        Path dir = Files.createDirectories(root.resolve("included"));
        init(dir);
        Files.writeString(dir.resolve("included.adoc"), ":topic-id: annotation-card-book\n= Book\n\nA line of the book.\n\ninclude::chapter.adoc[]\n");
        Files.writeString(dir.resolve("chapter.adoc"), ":topic-id: annotation-card-chapter\n= Chapter\n\nA claim of the chapter.\n");
        git(dir, "add", "included.adoc", "chapter.adoc");
        git(dir, "commit", "-q", "--no-gpg-sign", "--date=" + nextDay(), "-m", "Add the book");
        StampedDocumentCard card = open(dir);
        assertTrue(onFx(() -> card.selectLines(Path.of("chapter.adoc"), 4, 4)), "the chapter is shown in place");
        compose(card, "Which claim?");
        String commented = head(dir);

        assertTrue(onFx(() -> card.selectLines(Path.of("chapter.adoc"), 4, 4)));
        DialogPane confirm = retractSelection(card);
        confirm(confirm, "The only one.");
        await("the retraction is recorded", () -> status(card).startsWith("Retraction recorded as commit "));
        assertTrue(message(dir).contains("@" + commented), message(dir));
    }

    @Test
    void aLineWithNoAnnotationHasNothingToRetract() throws Exception {
        Path dir = repository("nothing");
        StampedDocumentCard card = open(dir);
        onFx(() -> {
            card.selectLines(4, 4);
            card.annotate(AnnotationKind.RETRACTION);
            return null;
        });
        assertEquals("No annotation is in force on the selected lines; there is nothing to retract.", onFx(() -> status(card)));
    }

    /**
     * The card reads its own annotation and its own retraction into the store as it records them, so it does
     * not read the repository again when it sees that HEAD moved. A commit made elsewhere is still read.
     */
    @Test
    void whatTheCardRecordedIsNotReadAgain() throws Exception {
        Path dir = repository("again");
        StampedDocumentCard card = open(dir);

        comment(card, 4, "A comment.");
        assertTrue(statusOnceHeadIsSeen(card, dir).startsWith("Comment recorded as commit "), "the comment is in the store already");

        DialogPane confirm = retract(card, 4);
        confirm(confirm, "Not needed.");
        await("the retraction is recorded", () -> status(card).startsWith("Retraction recorded as commit "));
        assertTrue(statusOnceHeadIsSeen(card, dir).startsWith("Retraction recorded as commit "), "and so is the retraction");

        Files.writeString(dir.resolve("again.adoc"), Files.readString(dir.resolve("again.adoc")) + "\nA claim made elsewhere.\n");
        git(dir, "add", "again.adoc");
        git(dir, "commit", "-q", "--no-gpg-sign", "--date=" + nextDay(), "-m", "Add a claim");
        onFx(() -> {
            card.open(card.shown().orElseThrow().anchor());
            return null;
        });
        await("the commit made elsewhere is read", () -> card.shown().orElseThrow().lines().stream()
                .anyMatch(line -> line.text().equals("A claim made elsewhere.")));
    }

    /** A document opened from the store, with no working tree known: the repository is found when it is needed. */
    @Test
    void theRepositoryIsFoundWhenTheCardKnewNone() throws Exception {
        Path dir = repository("unknown");
        Path file = dir.resolve("unknown.adoc");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, file).anchor();
        String commented = annotate(dir, "unknown.adoc", AnnotationKind.COMMENT, 4, "Made elsewhere.", null);
        GitDocumentMaterializer.materialize(dir, file);
        StampedDocumentCard card = card();
        onFx(() -> {
            card.open(anchor);
            return null;
        });
        await("the document is shown", () -> card.shown().isPresent());
        assertEquals(null, onFx(card::repoRoot));
        assertEquals(1, onFx(() -> line(card, 4).inForce().size()));

        DocumentNarratives.workingTreeOpened(dir);                  // as another card does when it opens a file there
        DialogPane confirm = retract(card, 4);
        assertTrue(onFx(confirm::getHeaderText).contains(commented.substring(0, 7)), onFx(confirm::getHeaderText));
        confirm(confirm, "Found.");
        await("the retraction is recorded", () -> status(card).startsWith("Retraction recorded as commit "));
        assertTrue(message(dir).contains("@" + commented), message(dir));
    }

    /** A line of a file that another repository has, shown in place: the retraction is a commit of that repository. */
    @Test
    void anAnnotationOfAFileInAnotherRepositoryIsRetractedThere() throws Exception {
        Path fleet = Files.createDirectories(root.resolve("fleet"));
        Files.createDirectories(fleet.resolve("scripts"));
        Files.writeString(fleet.resolve(WorkingTrees.ORIGINS), "proposals https://example.test/proposals.git\nlibrary https://example.test/library.git\n");
        Path library = Files.createDirectories(fleet.resolve("library"));
        init(library);
        String topic = "topics/src/docs/asciidoc/annotation-card/lab/topic.adoc";
        Files.createDirectories(library.resolve(topic).getParent());
        Files.writeString(library.resolve(topic), ":topic-id: annotation-card-topic\n= Topic\n\nA claim of the library.\n");
        git(library, "add", topic);
        git(library, "commit", "-q", "--no-gpg-sign", "--date=" + nextDay(), "-m", "Add the topic");
        String commented = annotate(library, topic, AnnotationKind.COMMENT, 4, "Which claim?", null);
        Path proposals = Files.createDirectories(fleet.resolve("proposals"));
        init(proposals);
        Files.writeString(proposals.resolve("proposals.adoc"), "= Proposal\n\ninclude::{adoc}/annotation-card/lab/topic.adoc[leveloffset=+1]\n");
        git(proposals, "add", "proposals.adoc");
        git(proposals, "commit", "-q", "--no-gpg-sign", "--date=" + nextDay(), "-m", "Add the proposal");
        String proposed = head(proposals);

        StampedDocumentCard card = open(proposals);
        assertTrue(onFx(() -> card.selectLines(Path.of(topic), 4, 4)), "the topic is shown in place");
        confirm(retractSelection(card), "It is clear.");
        await("the retraction is recorded", () -> status(card).startsWith("Retraction recorded as commit "));
        assertTrue(message(library).contains("@" + commented), message(library));
        assertEquals(proposed, head(proposals), "nothing is committed in the repository of the file that includes it");
        assertEquals(proposals, onFx(card::repoRoot), "which stays the card's own");
    }

    /** A clone that has not been brought up to date does not have the commit: the card says so, and not that there is nothing. */
    @Test
    void aCommitTheRepositoryDoesNotContainIsSaidToBeMissing() throws Exception {
        Path dir = repository("missing");
        StampedDocumentCard card = open(dir);
        comment(card, 4, "A comment.");
        statusOnceHeadIsSeen(card, dir);
        git(dir, "reset", "-q", "--hard", "HEAD~1");
        onFx(() -> {
            card.open(card.shown().orElseThrow().anchor());
            return null;
        });
        statusOnceHeadIsSeen(card, dir);
        onFx(() -> {
            card.selectLines(4, 4);
            card.annotate(AnnotationKind.RETRACTION);
            return null;
        });
        assertEquals("The commit that recorded the annotation is not in the history of missing; pull it, then retract.", onFx(() -> status(card)));
    }

    /** Git dates a commit to the second: an affirmation, a comment and a commit to the file in one second have one STAMP, and each has its commit. */
    @Test
    void annotationsOfOneSecondHaveTheirOwnCommits() throws Exception {
        Path dir = repository("second");
        Path file = dir.resolve("second.adoc");
        String when = "2026-04-28T12:00:00Z";
        Files.writeString(file, Files.readString(file) + "\nA third claim.\n");
        git(dir, "add", "second.adoc");
        git(dir, "commit", "-q", "--no-gpg-sign", "--date=" + when, "-m", "Add the third claim");
        String changed = head(dir);
        String affirmed = annotate(dir, "second.adoc", AnnotationKind.AFFIRMATION, 4, "Read.", when);
        String commented = annotate(dir, "second.adoc", AnnotationKind.COMMENT, 4, "Where?", when);
        PublicId anchor = GitDocumentMaterializer.materialize(dir, file).anchor();
        CommitSummaries summaries = CommitSummaries.read(anchor, dir, Path.of("second.adoc"));

        StoredLine line = GitDocumentMaterializer.load(anchor, true).orElseThrow().lines().get(3);
        assertEquals(1, line.inForce().stream().map(a -> a.stamp().nid()).distinct().count(), "one STAMP");
        assertEquals(changed, summaries.sha(line.inForce().getFirst().stamp().nid()).orElseThrow(), "for which the store has the commit that changed the file");
        assertEquals(List.of(affirmed), summaries.annotationCommits(annotation(line, AnnotationKind.AFFIRMATION)));
        assertEquals(List.of(commented), summaries.annotationCommits(annotation(line, AnnotationKind.COMMENT)));
        assertEquals(List.of(), CommitSummaries.read(anchor, null, null).annotationCommits(annotation(line, AnnotationKind.COMMENT)),
                "with no repository read, none is known");
    }

    private static StoredAnnotation annotation(StoredLine line, AnnotationKind kind) {
        return line.inForce().stream().filter(a -> a.kind() == kind).findFirst().orElseThrow();
    }

    /** Annotates a line of a file at HEAD from outside the card, as an empty commit. */
    private static String annotate(Path dir, String file, AnnotationKind kind, int line, String text, String when) throws Exception {
        Annotation annotation = Annotation.of(kind, LineRange.of(Path.of(file), line, line, head(dir), Files.readAllLines(dir.resolve(file))));
        List<String> commit = new ArrayList<>(List.of("commit", "-q", "--allow-empty", "--no-gpg-sign", "-m",
                AnnotationCommit.subject(List.of(annotation)) + "\n\n" + text + "\n\n" + annotation.trailer()));
        if (when != null) {
            commit.add("--date=" + when);
        }
        git(dir, commit.toArray(String[]::new));
        return head(dir);
    }

    // ── the card ──────────────────────────────────────────────────────────────

    /** A card in a shown window, on no document, in the Reading view. */
    private StampedDocumentCard card() throws Exception {
        StampedDocumentCard card = onFx(() -> {
            StampedDocumentCard made = (StampedDocumentCard) new StampedDocumentCard.Factory()
                    .createCard(windows.node("card-" + UUID.randomUUID()), UUID.randomUUID());
            made.preferences().put("network.ike.komet.document.view", "READING");
            Stage stage = new Stage();
            stage.setScene(new Scene((Parent) made.fxObject(), 1000, 800));
            stage.show();
            made.setSeedParentContext(KlContext.overView(made, viewProperties.nodeView(), "Test journal"));
            made.realize();
            return made;
        });
        cards.add(card);
        return card;
    }

    /** A card on the repository's document, which has the repository's name. */
    private StampedDocumentCard open(Path dir) throws Exception {
        StampedDocumentCard card = card();
        onFx(() -> {
            card.open(dir.resolve(dir.getFileName() + ".adoc"));
            return null;
        });
        await("the document is shown", () -> card.shown().isPresent() && status(card).startsWith("Materialized "));
        statusOnceHeadIsSeen(card, dir);
        return card;
    }

    /** Writes a comment on a line in the margin and records it with Enter. */
    private void comment(StampedDocumentCard card, int line, String text) throws Exception {
        assertTrue(onFx(() -> card.selectLines(line, line)));
        compose(card, text);
    }

    /** Comments on the selected lines, and lets the second pass: git dates a commit to the second, and what follows is a later one. */
    private void compose(StampedDocumentCard card, String text) throws Exception {
        onFx(() -> {
            card.annotate(AnnotationKind.COMMENT);
            return null;
        });
        await("the note is open", () -> note(card).isPresent() && note(card).get().getSkin() != null);
        onFx(() -> {
            TextArea note = note(card).orElseThrow();
            note.setText(text);
            note.end();
            note.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
            return null;
        });
        await("the comment is recorded", () -> status(card).startsWith("Comment recorded as commit "));
        Thread.sleep(1100);
    }

    private static Optional<TextArea> note(StampedDocumentCard card) {
        return Optional.ofNullable((TextArea) card.fxObject().lookup("#stampedDocumentNoteText"));
    }

    /** Retract… on a line: the dialog it opens, or the failure the card's status line says. */
    private DialogPane retract(StampedDocumentCard card, int line) throws Exception {
        assertTrue(onFx(() -> card.selectLines(line, line)));
        return retractSelection(card);
    }

    private DialogPane retractSelection(StampedDocumentCard card) throws Exception {
        Platform.runLater(() -> card.annotate(AnnotationKind.RETRACTION));   // the dialog waits in a loop of its own
        String recorded = onFx(() -> status(card));
        await("a retraction dialog, or the card's reason for none", () -> dialog().isPresent() || !status(card).equals(recorded)
                || status(card).startsWith("No annotation is in force"));
        return onFx(AnnotationCardIT::dialog).orElseThrow(() -> new AssertionError("no dialog; the card says: " + statusOf(card)));
    }

    private static Optional<DialogPane> dialog() {
        return Window.getWindows().stream().filter(Window::isShowing).map(Window::getScene).filter(scene -> scene != null)
                .map(Scene::getRoot).filter(DialogPane.class::isInstance).map(DialogPane.class::cast)
                .filter(pane -> pane.getId() != null && pane.getId().startsWith("stampedDocumentRetract")).findFirst();
    }

    private static List<String> choices(DialogPane pane) {
        for (Node node : pane.lookupAll("*")) {
            if (node instanceof ComboBox<?> box) {
                return box.getItems().stream().map(String::valueOf).toList();
            }
            if (node instanceof ChoiceBox<?> box) {
                return box.getItems().stream().map(String::valueOf).toList();
            }
        }
        throw new AssertionError("the dialog has no choices");
    }

    /** Gives the reason and records the retraction, unsigned. */
    private static void confirm(DialogPane pane, String reason) throws Exception {
        assertEquals("stampedDocumentRetractionDialog", onFx(pane::getId));
        onFx(() -> {
            ((TextArea) pane.lookup("#stampedDocumentRetractionText")).setText(reason);
            return null;
        });
        press(pane, "Unsigned Commit");
    }

    private static void press(DialogPane pane, String button) throws Exception {
        onFx(() -> {
            ButtonType type = pane.getButtonTypes().stream().filter(t -> t.getText().equals(button)).findFirst().orElseThrow();
            ((Button) pane.lookupButton(type)).fire();
            return null;
        });
        await("the dialog closes", () -> dialog().isEmpty());
    }

    private static StoredLine line(StampedDocumentCard card, int number) {
        return card.shown().orElseThrow().lines().get(number - 1);
    }

    private static String status(StampedDocumentCard card) {
        return ((Label) card.fxObject().lookup("#stampedDocumentStatus")).getText();
    }

    private static String statusOf(StampedDocumentCard card) {
        try {
            return onFx(() -> status(card));
        } catch (Exception e) {
            return e.toString();
        }
    }

    /** What the status line says at the moment the card has seen where the repository stands: a reading of it begins in that same turn. */
    private static String statusOnceHeadIsSeen(StampedDocumentCard card, Path dir) throws Exception {
        String key = READ_AT + onFx(() -> card.shown().orElseThrow().anchor().asUuidArray()[0]);
        String head = head(dir);
        String[] said = new String[1];
        await("the card has seen where the repository stands", () -> {
            String read = card.preferences().get(key, "");
            said[0] = status(card);
            return !read.isEmpty() && head.startsWith(read);
        });
        return said[0];
    }

    // ── git ───────────────────────────────────────────────────────────────────

    private int day;

    private String nextDay() {
        return "2026-04-" + String.format("%02d", ++day) + "T12:00:00";
    }

    private Path repository(String name) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name));
        init(dir);
        Files.writeString(dir.resolve(name + ".adoc"), ":topic-id: annotation-card-" + name + "\n= " + name + "\n\nA first claim.\n\nA second claim.\n");
        git(dir, "add", name + ".adoc");
        git(dir, "commit", "-q", "--no-gpg-sign", "--date=" + nextDay(), "-m", "Add the " + name + " document");
        return dir;
    }

    private static void init(Path dir) throws Exception {
        git(dir, "init", "-q", "-b", "main");
        git(dir, "config", "user.name", "reviewer");
        git(dir, "config", "user.email", "reviewer@example.test");
        git(dir, "config", "commit.gpgsign", "false");
        git(dir, "config", "core.hooksPath", "/dev/null");
    }

    private static String head(Path dir) throws Exception {
        return git(dir, "rev-parse", "HEAD");
    }

    private static String message(Path dir) throws Exception {
        return git(dir, "log", "-1", "--format=%B");
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

    // ── JavaFX ────────────────────────────────────────────────────────────────

    private static void await(String what, Callable<Boolean> condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!onFx(condition)) {
            if (System.nanoTime() > until) {
                throw new AssertionError("not so after 30 seconds: " + what + openSaying());
            }
            Thread.sleep(20);
        }
    }

    /** Lets what a card reads off the FX thread, and what it then shows and reads in turn, come to an end. */
    private static void settle() throws Exception {
        for (int i = 0; i < 6; i++) {
            assertTrue(ForkJoinPool.commonPool().awaitQuiescence(30, TimeUnit.SECONDS), "the card is still reading");
            onFx(() -> null);
        }
    }

    private static String openSaying() throws Exception {
        return onFx(() -> {
            StringBuilder said = new StringBuilder();
            for (Window window : Window.getWindows()) {
                if (window.getScene() != null && window.getScene().getRoot().lookup("#stampedDocumentStatus") instanceof Label status) {
                    said.append("; the card says: ").append(status.getText());
                }
            }
            return said.toString();
        });
    }

    private static <T> T onFx(Callable<T> work) throws Exception {
        if (Platform.isFxApplicationThread()) {
            return work.call();
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(work.call());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result.get(30, TimeUnit.SECONDS);
    }
}

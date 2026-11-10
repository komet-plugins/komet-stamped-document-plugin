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
import design.knowledge.stamp.adoc.model.AnnotationKind;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import javafx.application.Platform;
import javafx.scene.Node;
import network.ike.komet.document.DocumentSurfaceView.Layer;
import network.ike.komet.document.GitDocumentMaterializer.StoredBlock;
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
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The right margin's notes in the Reading view: a comment is written beside its lines and read
 * there whether or not the stamps are drawn, since the comments are a layer of their own. With
 * the stamps off the margin once went, and a note opened for writing had nowhere to stand; and a
 * note drawn once was not drawn again when its block was filled a second time.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MarginNoteIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");

    /** Each test's document is its own: its own file, topic and day, so two tests never write one document's identity from two histories. */
    private static final String TEXT = """
            :topic-id: margin-note-NAME
            = Margin Note

            A first claim.

            A second claim.
            """;

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
        new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute();
    }

    @AfterAll
    void teardown() {
        PrimitiveData.stop();
    }

    @Test
    void aNoteIsWrittenInTheMarginWithTheStampsOff() throws Exception {
        Path dir = repository("compose");
        StoredDocument document = stored(dir);
        DocumentSurface body = onFx(() -> new DocumentSurface(new BlockFactory(Calculators.View.Default(), 13)));
        Set<Layer> shown = EnumSet.noneOf(Layer.class);
        List<String> recorded = new ArrayList<>();

        boolean composing = onFx(() -> {
            DocumentSurfaceView view = new DocumentSurfaceView(host(shown), body);
            // as the card that first showed the failure had them: no dates, no stamps, and no comment yet in force
            view.setLayer(Layer.DATES, false);
            view.setLayer(Layer.STAMPS, false);
            view.setLayer(Layer.COMMENTS, false);
            view.show(DocumentAssembly.single(document, "compose.adoc"));
            assertEquals(List.of(), inStack(body, "#stampedDocumentNoteText"), "nothing is being written yet");
            view.select(selectionOf(document, "A second claim."));
            view.composeAnnotation(AnnotationKind.COMMENT, "Your comment", false, false, recorded::add);
            return view.composing();
        });
        assertTrue(composing);
        List<Node> notes = onFx(() -> inStack(body, "#stampedDocumentNoteText"));
        assertEquals(1, notes.size(), "the note stands in a row of the stack, where it can be seen and written in");
        assertEquals(Set.of(Layer.COMMENTS), shown, "writing a comment switches the Comments layer on, and the card's menu is told");
        int block = onFx(() -> blockHolding(body, notes.getFirst()));
        assertTrue(onFx(() -> body.block(block).plainText()).contains("A second claim."), "beside the block of the selected line");
        assertTrue(onFx(() -> body.block(block).isPinned()), "its block stays built while the note is written");

        // Escape discards the note, and the margin it came for goes with it
        onFx(() -> {
            notes.getFirst().fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED, "", "",
                    javafx.scene.input.KeyCode.ESCAPE, false, false, false, false));
            return null;
        });
        assertEquals(List.of(), onFx(() -> inStack(body, "#stampedDocumentNoteText")));
        assertEquals(List.of(), recorded, "nothing was recorded");
        assertFalse(onFx(() -> body.block(block).isPinned()));
    }

    @Test
    void aCommentInForceIsReadInTheMarginWhateverTheStampsDo() throws Exception {
        Path dir = repository("read");
        StoredDocument before = stored(dir);
        int line = 1 + before.lines().stream().map(StoredLine::text).toList().indexOf("A first claim.");
        StampedDocumentCard.Proposal proposal = StampedDocumentCard.prepare(AnnotationKind.COMMENT, List.of(new int[] {line, line}), dir,
                Path.of("read.adoc"), GitDocumentExporter.export(before.anchor(), false).orElseThrow().lines());
        AnnotationCommit.Result result = AnnotationCommit.write(dir, proposal.annotations(), "Say where this was measured.", false);
        assertTrue(result.ok(), result.output());
        GitDocumentMaterializer.materialize(dir, dir.resolve("read.adoc"), true);
        StoredDocument document = GitDocumentMaterializer.load(before.anchor(), true).orElseThrow();

        DocumentSurface body = onFx(() -> new DocumentSurface(new BlockFactory(Calculators.View.Default(), 13)));
        DocumentSurfaceView view = onFx(() -> {
            DocumentSurfaceView made = new DocumentSurfaceView(host(EnumSet.noneOf(Layer.class)), body);
            made.setLayer(Layer.STAMPS, false);
            made.show(DocumentAssembly.single(document, "read.adoc"));
            return made;
        });
        assertEquals(List.of("Say where this was measured."), onFx(() -> noteTexts(body)), "the comment stands in the margin with the stamps off");

        // a block is filled again when a layer is switched, or when a long document builds it a second time: the note is drawn again
        onFx(() -> {
            view.setLayer(Layer.TINTS, true);
            return null;
        });
        assertEquals(List.of("Say where this was measured."), onFx(() -> noteTexts(body)), "the note survives its block being filled again");

        onFx(() -> {
            view.setLayer(Layer.COMMENTS, false);
            return null;
        });
        assertEquals(List.of(), onFx(() -> noteTexts(body)), "the Comments layer switches the notes off on its own");
        onFx(() -> {
            view.setLayer(Layer.STAMPS, true);
            return null;
        });
        assertEquals(List.of(), onFx(() -> noteTexts(body)), "and the stamps do not bring them back");
        onFx(() -> {
            view.setLayer(Layer.COMMENTS, true);
            return null;
        });
        assertEquals(List.of("Say where this was measured."), onFx(() -> noteTexts(body)));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** The nodes a selector finds in the rows the surface stacked: what is in a row is on the surface, what is not is nowhere. */
    private static List<Node> inStack(DocumentSurface body, String selector) {
        List<Node> found = new ArrayList<>();
        for (int b = 0; b < body.blockCount(); b++) {
            found.addAll(body.block(b).stackNode().lookupAll(selector));
        }
        return found;
    }

    private static int blockHolding(DocumentSurface body, Node node) {
        for (int b = 0; b < body.blockCount(); b++) {
            for (Node at = node; at != null; at = at.getParent()) {
                if (at == body.block(b).stackNode()) {
                    return b;
                }
            }
        }
        throw new AssertionError("the node is in no block's row");
    }

    private static List<String> noteTexts(DocumentSurface body) {
        List<String> texts = new ArrayList<>();
        for (Node note : inStack(body, ".stamped-document-note")) {
            note.lookupAll(".label").stream().map(javafx.scene.control.Label.class::cast).map(javafx.scene.control.Label::getText)
                    .filter(text -> !text.matches("\\d{4}-\\d{2}-\\d{2}")).forEach(texts::add);
        }
        return texts;
    }

    private static LineSelection selectionOf(StoredDocument document, String text) {
        Map<Long, Integer> ordinals = LineSelection.ordinals(document.lines());
        StoredLine line = document.lines().stream().filter(l -> l.text().equals(text)).findFirst().orElseThrow();
        return new LineSelection(ordinals.get(line.nid()), ordinals.get(line.nid()));
    }

    private Path repository(String name) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name));
        git(dir, "init", "-q", "-b", "main");
        git(dir, "config", "user.name", "reviewer");
        git(dir, "config", "user.email", "reviewer@example.test");
        git(dir, "config", "commit.gpgsign", "false");
        Files.writeString(dir.resolve(name + ".adoc"), TEXT.replace("NAME", name));
        git(dir, "add", name + ".adoc");
        git(dir, "commit", "-q", "--no-gpg-sign", "--date=2026-03-" + String.format("%02d", ++day) + "T12:00:00", "-m", "Add the " + name + " document");
        return dir;
    }

    private int day;

    private static StoredDocument stored(Path dir) throws Exception {
        PublicId anchor = GitDocumentMaterializer.materialize(dir, dir.resolve(dir.getFileName() + ".adoc")).anchor();
        return GitDocumentMaterializer.load(anchor, true).orElseThrow();
    }

    private static DocumentSurfaceView.Host host(Set<Layer> shown) {
        return new DocumentSurfaceView.Host() {
            @Override
            public ViewCalculator view() {
                return Calculators.View.Default();
            }

            @Override
            public CommitSummaries summaries() {
                return CommitSummaries.NONE;
            }

            @Override
            public void status(String text) {
            }

            @Override
            public void annotate(AnnotationKind kind) {
            }

            @Override
            public void saveEdit(StoredBlock edited, String text) {
            }

            @Override
            public void showHistory() {
            }

            @Override
            public void selectionChanged() {
            }

            @Override
            public void showingMember(DocumentAssembly.Member member) {
            }

            @Override
            public void layerShown(Layer layer) {
                shown.add(layer);
            }
        };
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
        }
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

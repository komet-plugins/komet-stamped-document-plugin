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

import design.knowledge.stamp.adoc.model.AnnotationKind;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import javafx.application.Platform;
import network.ike.komet.document.GitDocumentMaterializer.StoredBlock;
import network.ike.komet.document.GitDocumentMaterializer.StoredDocument;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Find in a document: a text is found wherever it stands in the view shown, whatever its case, in
 * reading order; in the Reading view that is the text of the blocks and the captions of figures
 * and tables, which are labels and no block's text; in the Source view it is the lines as
 * written. The graph is another drawing of the lines and is not read.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FindInDocumentIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");

    private static final String TEXT = """
            :topic-id: find-in-document
            = Finding Words

            The Sensitivity of the choice is drawn first.

            == A heading about the wear test

            A second paragraph names the sensitivity again, and the WEAR TEST once.

            .The quantities in question
            |===
            | Name | Value

            | Hazard | 2%
            |===
            """;

    /** The text of each block the view asked to have saved. */
    private static final List<String> SAVED = new ArrayList<>();

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
    void aTextIsFoundInTheBlocksAndTheCaptionsOfTheViewShown() throws Exception {
        Path dir = Files.createDirectories(root.resolve("find-in-document"));
        git(dir, "init", "-q", "-b", "main");
        git(dir, "config", "user.name", "author");
        git(dir, "config", "user.email", "author@example.test");
        Path file = dir.resolve("find-in-document.adoc");
        Files.writeString(file, TEXT);
        git(dir, "add", "find-in-document.adoc");
        git(dir, "commit", "-q", "--no-gpg-sign", "-m", "The document");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, file).anchor();
        StoredDocument document = GitDocumentMaterializer.load(anchor, true).orElseThrow();

        DocumentSurface surface = onFx(() -> new DocumentSurface(new BlockFactory(Calculators.View.Default(), 13)));
        DocumentSurfaceView view = onFx(() -> {
            DocumentSurfaceView made = new DocumentSurfaceView(host(), surface);
            made.show(DocumentAssembly.single(document, "find-in-document.adoc"));
            return made;
        });

        List<DocumentSurfaceView.Match> sensitivity = onFx(() -> view.find("sensitivity"));
        assertEquals(2, sensitivity.size(), "whatever its case, once in each paragraph: " + sensitivity);
        assertTrue(sensitivity.stream().allMatch(DocumentSurfaceView.Match::selectable), "prose matches are selected where they stand");
        assertTrue(sensitivity.get(0).blockIndex() < sensitivity.get(1).blockIndex(), "in reading order");

        List<DocumentSurfaceView.Match> wear = onFx(() -> view.find("wear test"));
        assertEquals(2, wear.size(), "in the heading and in the paragraph under it: " + wear);

        List<DocumentSurfaceView.Match> caption = onFx(() -> view.find("QUANTITIES IN QUESTION"));
        assertEquals(1, caption.size(), "a caption's words are found once, in its block: " + caption);
        String label = onFx(() -> ((javafx.scene.control.Label) surface.block(caption.get(0).blockIndex()).node()
                .lookup(".stamped-document-caption")).getText());
        assertEquals("Table 1. The quantities in question", label, "the block the match names is the one the caption stands with");
        List<DocumentSurfaceView.Match> numbered = onFx(() -> view.find("Table 1."));
        assertEquals(1, numbered.size(), "a caption is a label, and is found by its number as it reads: " + numbered);
        assertFalse(numbered.get(0).selectable(), "it is shown by its block");
        assertEquals(caption.get(0).blockIndex(), numbered.get(0).blockIndex());

        // a table row is drawn as its cells: the words are found in the cell that draws them, not in the row's source
        List<DocumentSurfaceView.Match> cell = onFx(() -> view.find("hazard"));
        assertEquals(1, cell.size(), "once, where it is drawn: " + cell);
        assertEquals(0, cell.get(0).cell(), "in the row's first cell");
        assertTrue(cell.get(0).selectable(), "where it can be selected");
        assertEquals(1, onFx(() -> view.find("2%")).get(0).cell(), "and a word of the second cell in the second");
        assertTrue(cell.get(0).blockIndex() > caption.get(0).blockIndex() || cell.get(0).blockIndex() == caption.get(0).blockIndex());

        assertTrue(onFx(() -> view.find("  ")).isEmpty(), "blank finds nothing");
        assertTrue(onFx(() -> view.find("no such words")).isEmpty());

        // a match is shown without a window or a layout: the fold over it opens and its block is built
        onFx(() -> {
            view.showMatch(sensitivity.get(1));
            view.showMatch(caption.get(0));
            view.showMatch(cell.get(0));
            view.clearMatch();
            return null;
        });

        // replace: the words of a match are replaced in the source of their line, and the block is saved as an edit
        SAVED.clear();
        assertFalse(onFx(() -> view.replaceMatch(wear.get(0), "abrasion test")), "a heading's words are changed in its source, with Edit block");
        assertFalse(onFx(() -> view.replaceMatch(numbered.get(0), "Table 9.")), "as a caption's are");
        assertFalse(onFx(() -> view.replaceMatch(cell.get(0), "Risk")), "and a table cell's");
        assertTrue(SAVED.isEmpty(), "nothing is saved for a match that is left as it stands");
        assertTrue(onFx(() -> view.replaceMatch(wear.get(1), "abrasion test")), "a paragraph's words are replaced where they stand");
        assertEquals(List.of("A second paragraph names the sensitivity again, and the abrasion test once."), SAVED,
                "the match alone is replaced, whatever its case, and the rest of the line is as it was written");
        SAVED.clear();
        assertTrue(onFx(() -> view.replaceMatch(sensitivity.get(0), "")), "an empty replacement takes the words away");
        assertEquals(List.of("The  of the choice is drawn first."), SAVED);

        onFx(() -> {
            view.setView(DocumentSurfaceView.View.SOURCE);
            return null;
        });
        assertEquals(1, onFx(() -> view.find(".The quantities in question")).size(), "the Source view reads the lines as written");
        assertTrue(onFx(() -> view.find("Table 1.")).isEmpty(), "where no caption is numbered");

        onFx(() -> {
            view.setView(DocumentSurfaceView.View.GRAPH);
            return null;
        });
        assertTrue(onFx(() -> view.find("sensitivity")).isEmpty(), "the graph is not read");
    }

    private static DocumentSurfaceView.Host host() {
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
                SAVED.add(text);
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

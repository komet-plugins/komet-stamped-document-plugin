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
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import network.ike.komet.document.GitDocumentMaterializer.StoredBlock;
import network.ike.komet.document.GitDocumentMaterializer.StoredDocument;
import network.ike.komet.document.print.DocumentFonts;
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
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A table on a card too narrow for its columns. Five columns of sentences in the width of one
 * paragraph are columns a few letters wide and a page tall, with words cut inside themselves. The
 * Reading view draws such a table's rows as stacks: a row's cells one under another, each with the
 * name its column's header gives it, a sentence under its name at the row's width and a word or a
 * number beside it, with the header row itself not drawn.
 * The same table on a wide card is a grid, and a table of short cells is a grid on either.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TableStackIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");

    private static final List<String> NAMES = List.of("Decision point", "Controller", "Loop", "How it goes wrong", "What is priced");

    private static final String TEXT = """
            :topic-id: table-stack
            = Tables Wide and Narrow

            A paragraph before the tables.

            .The decisions
            [cols="3,1,1,3,3", options="header"]
            |===
            | Decision point | Controller | Loop | How it goes wrong | What is priced
            | Which modality the label and the study may use, MRA or catheter angiography | Regulator | Premarket, then postmarket | A modality whose specificity is poor for this device fills the count with false alarms | The value of an imperfect test from its two error rates
            | Whether a confirmed reopening is retreated | Clinician | | Retreating a reopening that would never have ruptured; leaving one that would | The retreatment threshold in the same tree
            |===

            .The counts
            [cols="2,1", options="header"]
            |===
            | Course | Ruptures
            | None scanned | 36
            | All scanned | 7
            |===
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
        DocumentFonts.loadForJavaFx();
    }

    @AfterAll
    void teardown() {
        PrimitiveData.stop();
    }

    @Test
    void aTableTooNarrowForItsColumnsIsDrawnAsRowsOfNamedCells() throws Exception {
        Path dir = Files.createDirectories(root.resolve("table-stack"));
        git(dir, "init", "-q", "-b", "main");
        git(dir, "config", "user.name", "author");
        git(dir, "config", "user.email", "author@example.test");
        Path file = dir.resolve("table-stack.adoc");
        Files.writeString(file, TEXT);
        git(dir, "add", "table-stack.adoc");
        git(dir, "commit", "-q", "--no-gpg-sign", "-m", "The document");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, file).anchor();
        StoredDocument document = GitDocumentMaterializer.load(anchor, true).orElseThrow();

        DocumentSurface body = onFx(() -> new DocumentSurface(new BlockFactory(Calculators.View.Default(), 13)));
        Stage stage = onFx(() -> {
            Stage made = new Stage();
            // The window keeps one size and the card's width changes inside it, as a card's does in
            // a journal. A stage made narrow and then wide again has Monocle's render thread throw
            // "Too small int buffer size" for the first wide frame: on stderr only, so nothing
            // fails, and the build report counts each line of the stack trace as a warning.
            made.setScene(new Scene(new StackPane(body), 1300, 900));
            made.show();
            new DocumentSurfaceView(host(), body).show(DocumentAssembly.single(document, "table-stack.adoc"));
            return made;
        });
        pulses();

        // wide: both tables are grids, a row's cells side by side
        List<GridPane> rows = onFx(() -> rows(body));
        assertEquals(6, rows.size(), "three rows to a table");
        List<GridPane> decisions = rows.subList(0, 3);
        List<GridPane> counts = rows.subList(3, 6);
        assertTrue(onFx(() -> rows.stream().noneMatch(TableStackIT::stacked)), "on a wide card every row is a grid");
        assertTrue(onFx(() -> rows.stream().allMatch(Node::isVisible)), "the header rows among them");
        assertEquals(List.of(), onFx(() -> names(decisions.get(1))), "where the header names the columns, no cell is named again");
        List<Node> wide = onFx(() -> cells(decisions.get(1)));
        assertEquals(5, wide.size());
        for (int c = 1; c < wide.size(); c++) {
            Node before = wide.get(c - 1);
            Node cell = wide.get(c);
            assertTrue(onFx(() -> cell.getLayoutX() > before.getLayoutX() && Math.abs(cell.getLayoutY() - before.getLayoutY()) < 1),
                    "cell " + c + " stands beside the one before it");
        }

        // narrow: the table of sentences is stacked, and the table of short cells is still a grid
        onFx(() -> {
            body.setMaxWidth(520);
            return null;
        });
        pulses();
        assertTrue(onFx(() -> decisions.stream().allMatch(TableStackIT::stacked)), "five columns of sentences do not stand side by side in one paragraph's width");
        assertFalse(onFx(() -> decisions.get(0).isVisible() || decisions.get(0).isManaged()), "the header row's words are the cells' names, and it is not drawn");
        assertEquals(NAMES, onFx(() -> names(decisions.get(1))), "each cell is named by its column's header, in the columns' order");
        List<Node> narrow = onFx(() -> cells(decisions.get(1)));
        double rowWidth = onFx(() -> decisions.get(1).getWidth());
        for (int c = 0; c < narrow.size(); c++) {
            Node cell = narrow.get(c);
            assertTrue(onFx(() -> cell.getBoundsInParent().getWidth()) > rowWidth * 0.5, "cell " + c + " has the row's width, or what its name leaves of it");
            if (c > 0) {
                Node before = narrow.get(c - 1);
                assertTrue(onFx(() -> cell.getLayoutY() >= before.getLayoutY() + before.getBoundsInParent().getHeight() - 1), "cell " + c + " stands under the one before it");
            }
        }
        // a sentence stands under its name at the row's width; a cell of a word stands on one line beside its name
        List<Label> labels = onFx(() -> decisions.get(1).getChildren().stream().filter(node -> node.getStyleClass().contains("doc-table-cell-name"))
                .map(Label.class::cast).sorted(Comparator.comparingDouble(Node::getLayoutY)).toList());
        assertTrue(onFx(() -> narrow.get(0).getLayoutY() > labels.get(0).getLayoutY() + 1 && narrow.get(0).getBoundsInParent().getWidth() > rowWidth * 0.95),
                "the decision's sentence is under its name, as wide as the row");
        assertTrue(onFx(() -> Math.abs(narrow.get(1).getLayoutY() - labels.get(1).getLayoutY()) < 1 && narrow.get(1).getLayoutX() > labels.get(1).getLayoutX()),
                "the controller's one word is beside its name");
        assertEquals(onFx(() -> narrow.get(1).getLayoutX()), onFx(() -> narrow.get(2).getLayoutX()), 0.5, "and the cells beside their names line up");
        // the row whose third cell is empty: that cell is not drawn, and its column is not named
        assertEquals(List.of("Decision point", "Controller", "How it goes wrong", "What is priced"), onFx(() -> names(decisions.get(2))));
        assertEquals(4, onFx(() -> cells(decisions.get(2))).size());
        assertTrue(onFx(() -> counts.stream().noneMatch(TableStackIT::stacked)), "a table of short cells is a grid at any width that holds its words");
        assertTrue(onFx(() -> counts.stream().allMatch(Node::isVisible)));

        // wide again: the grid is back, with its header
        onFx(() -> {
            body.setMaxWidth(Double.MAX_VALUE);
            return null;
        });
        pulses();
        assertTrue(onFx(() -> rows.stream().noneMatch(TableStackIT::stacked)));
        assertTrue(onFx(() -> decisions.get(0).isVisible() && decisions.get(0).isManaged()), "the header row is drawn again");
        assertEquals(List.of(), onFx(() -> names(decisions.get(2))));
        assertEquals(5, onFx(() -> cells(decisions.get(2))).size(), "the empty cell keeps its column");
        onFx(() -> {
            stage.hide();
            return null;
        });
    }

    private static List<GridPane> rows(DocumentSurface body) {
        List<GridPane> rows = new ArrayList<>();
        for (int b = 0; b < body.blockCount(); b++) {
            body.block(b).node().lookupAll(".doc-table-row").forEach(row -> rows.add((GridPane) row));
        }
        return rows;
    }

    private static boolean stacked(GridPane row) {
        return row.getStyleClass().contains("doc-table-stacked");
    }

    /** The cells a row draws, in the order it lays them out: down, then across. */
    private static List<Node> cells(GridPane row) {
        return row.getChildren().stream().filter(node -> node.getStyleClass().contains("doc-table-cell") && node.isManaged())
                .sorted(Comparator.comparingDouble(Node::getLayoutY).thenComparingDouble(Node::getLayoutX)).toList();
    }

    /** The names a row draws for its cells, from the top. */
    private static List<String> names(GridPane row) {
        return row.getChildren().stream().filter(node -> node.getStyleClass().contains("doc-table-cell-name"))
                .sorted(Comparator.comparingDouble(Node::getLayoutY)).map(node -> ((Label) node).getText()).toList();
    }

    private static void pulses() throws Exception {
        for (int i = 0; i < 12; i++) {
            Thread.sleep(60);
            onFx(() -> null);
        }
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

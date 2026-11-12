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
import network.ike.komet.document.surface.DocumentBlock;
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
 * Folds by what a block is and what it stands in: a paragraph by itself, a table whole from any
 * of its rows, a list whole from any of its items, the section around a block, every section at
 * once, and every fold opened at once.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FoldByKindIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");

    private static final String TEXT = """
            :topic-id: fold-by-kind
            = Folding

            == A section

            A paragraph before the table.

            |===
            | Name | Value

            | Hazard | 2%
            | Rupture | 1%
            |===

            * The first item
            * The second item

            == Another section

            The last paragraph.
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
    void aBlockIsFoldedByWhatItIsAndWhatItStandsIn() throws Exception {
        Path dir = Files.createDirectories(root.resolve("fold-by-kind"));
        git(dir, "init", "-q", "-b", "main");
        git(dir, "config", "user.name", "author");
        git(dir, "config", "user.email", "author@example.test");
        Path file = dir.resolve("fold-by-kind.adoc");
        Files.writeString(file, TEXT);
        git(dir, "add", "fold-by-kind.adoc");
        git(dir, "commit", "-q", "--no-gpg-sign", "--date", "2026-11-04T10:00:00", "-m", "The document");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, file).anchor();
        StoredDocument document = GitDocumentMaterializer.load(anchor, true).orElseThrow();

        DocumentSurface surface = onFx(() -> new DocumentSurface(new BlockFactory(Calculators.View.Default(), 13)));
        DocumentSurfaceView view = onFx(() -> {
            DocumentSurfaceView made = new DocumentSurfaceView(host(), surface);
            made.show(DocumentAssembly.single(document, "fold-by-kind.adoc"));
            return made;
        });
        List<DocumentBlock> blocks = onFx(() -> blocks(surface));
        int paragraph = blocks.indexOf(saying(blocks, "A paragraph before the table."));
        int hazard = blocks.indexOf(saying(blocks, "Hazard"));
        int rupture = blocks.indexOf(saying(blocks, "Rupture"));
        int first = blocks.indexOf(saying(blocks, "The first item"));
        int second = blocks.indexOf(saying(blocks, "The second item"));
        int last = blocks.indexOf(saying(blocks, "The last paragraph."));

        // a paragraph: itself, its section, and all at once
        List<String> atParagraph = onFx(() -> view.foldsOffered(paragraph));
        assertEquals(List.of("Fold paragraph", "Fold section “A section”", "Fold every section"), atParagraph);
        onFx(() -> {
            view.fold(paragraph, "Fold paragraph");
            return null;
        });
        assertTrue(onFx(() -> view.folded(paragraph)), "the paragraph is folded");
        assertFalse(onFx(() -> view.folded(hazard)), "and nothing else");
        assertTrue(onFx(() -> view.foldsOffered(paragraph)).contains("Unfold"), "the fold is opened where it was made");

        // a row: the whole table, never the row alone
        List<String> atRow = onFx(() -> view.foldsOffered(rupture));
        assertTrue(atRow.contains("Fold table"), "a row offers its table: " + atRow);
        assertFalse(atRow.stream().anyMatch(offer -> offer.contains("row")), "and no fold of one row: " + atRow);
        onFx(() -> {
            view.fold(rupture, "Fold table");
            return null;
        });
        assertTrue(onFx(() -> view.folded(hazard)) && onFx(() -> view.folded(rupture)), "every row of the table is folded");
        assertFalse(onFx(() -> view.folded(first)), "the list after it is not");

        // an item: itself, or its whole list
        List<String> atItem = onFx(() -> view.foldsOffered(second));
        assertTrue(atItem.containsAll(List.of("Fold list item", "Fold list")), "an item offers itself and its list: " + atItem);
        onFx(() -> {
            view.fold(second, "Fold list");
            return null;
        });
        assertTrue(onFx(() -> view.folded(first)) && onFx(() -> view.folded(second)), "both items are folded");

        // everything opened, then every section folded
        onFx(() -> {
            view.fold(last, "Unfold everything");
            return null;
        });
        for (int index : List.of(paragraph, hazard, rupture, first, second, last)) {
            assertFalse(onFx(() -> view.folded(index)), "block " + index + " is open again");
        }
        onFx(() -> {
            view.fold(last, "Fold every section");
            return null;
        });
        for (int index : List.of(paragraph, hazard, rupture, first, second, last)) {
            assertTrue(onFx(() -> view.folded(index)), "block " + index + " is folded under its section's heading");
        }
        onFx(() -> {
            view.fold(last, "Unfold everything");
            view.fold(last, "Fold section “Another section”");
            return null;
        });
        assertTrue(onFx(() -> view.folded(last)));
        assertFalse(onFx(() -> view.folded(paragraph)), "the other section is open");
    }

    private static List<DocumentBlock> blocks(DocumentSurface surface) {
        List<DocumentBlock> blocks = new ArrayList<>();
        for (int i = 0; i < surface.blockCount(); i++) {
            blocks.add(surface.block(i));
        }
        return blocks;
    }

    private static DocumentBlock saying(List<DocumentBlock> blocks, String words) {
        return blocks.stream().filter(block -> block.plainText().contains(words)).findFirst()
                .orElseThrow(() -> new AssertionError("no block says “" + words + "”"));
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

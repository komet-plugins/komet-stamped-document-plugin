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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A document read again after an edit is drawn only where it changed: the blocks whose lines are
 * as they were keep the controls they were drawn with, in the same place on the surface, and the
 * block that changed, with the headings above it, is drawn again. A change that moves what other
 * blocks read, as a heading's words do, draws the whole document again.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChangeOnlyRedrawIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");

    private static final String TEXT = """
            :topic-id: change-only-redraw
            = Redrawing

            The first paragraph stays.

            == A section

            The second paragraph changes here.

            The third paragraph stays.

            == Another section

            The fourth paragraph stays.
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
    void onlyTheBlockThatChangedIsDrawnAgain() throws Exception {
        Path dir = Files.createDirectories(root.resolve("change-only-redraw"));
        git(dir, "init", "-q", "-b", "main");
        git(dir, "config", "user.name", "author");
        git(dir, "config", "user.email", "author@example.test");
        Path file = dir.resolve("change-only-redraw.adoc");
        Files.writeString(file, TEXT);
        git(dir, "add", "change-only-redraw.adoc");
        git(dir, "commit", "-q", "--no-gpg-sign", "--date", "2026-11-01T10:00:00", "-m", "The document");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, file).anchor();
        StoredDocument first = GitDocumentMaterializer.load(anchor, true).orElseThrow();

        DocumentSurface surface = onFx(() -> new DocumentSurface(new BlockFactory(Calculators.View.Default(), 13)));
        DocumentSurfaceView view = onFx(() -> {
            DocumentSurfaceView made = new DocumentSurfaceView(host(), surface);
            made.show(DocumentAssembly.single(first, "change-only-redraw.adoc"));
            return made;
        });
        List<DocumentBlock> before = onFx(() -> blocks(surface));
        assertEquals(0, onFx(view::keptAreas), "a document shown for the first time is drawn whole");

        // the same document read again with nothing changed: every block is kept
        onFx(() -> {
            view.showChanged(DocumentAssembly.single(GitDocumentMaterializer.load(anchor, true).orElseThrow(), "change-only-redraw.adoc"));
            return null;
        });
        List<DocumentBlock> unchanged = onFx(() -> blocks(surface));
        assertEquals(before.size(), unchanged.size());
        for (int i = 0; i < before.size(); i++) {
            assertSame(before.get(i), unchanged.get(i), "block " + i + " is the one that was drawn before");
        }

        // one paragraph is edited and committed
        Files.writeString(file, TEXT.replace("The second paragraph changes here.", "The second paragraph has changed."));
        git(dir, "commit", "-q", "--no-gpg-sign", "--date", "2026-11-02T10:00:00", "-am", "One paragraph");
        GitDocumentMaterializer.materialize(dir, file);
        StoredDocument second = GitDocumentMaterializer.load(anchor, true).orElseThrow();
        onFx(() -> {
            view.showChanged(DocumentAssembly.single(second, "change-only-redraw.adoc"));
            return null;
        });
        List<DocumentBlock> after = onFx(() -> blocks(surface));
        assertEquals(before.size(), after.size(), "the same blocks, one of them changed");
        for (String stays : List.of("The first paragraph stays.", "The third paragraph stays.", "Another section", "The fourth paragraph stays.")) {
            assertSame(saying(before, stays), saying(after, stays), "the block that says “" + stays + "” is kept as it was drawn");
        }
        assertTrue(after.stream().noneMatch(block -> block.plainText().contains("changes here")), "the old words are gone");
        DocumentBlock changed = saying(after, "The second paragraph has changed.");
        assertTrue(before.stream().noneMatch(block -> block == changed), "the changed paragraph is drawn again");
        assertEquals(4, (int) onFx(view::keptAreas), "the three paragraphs that stay, and the other section's heading");
        assertTrue(onFx(view::drawnAreas) < before.size() - 3, "what is drawn again is the paragraph and the headings above it: " + onFx(view::drawnAreas));

        // a heading's words are read by the blocks that refer to it and by the contents: the document is drawn whole
        Files.writeString(file, TEXT.replace("The second paragraph changes here.", "The second paragraph has changed.").replace("== Another section", "== The last section"));
        git(dir, "commit", "-q", "--no-gpg-sign", "--date", "2026-11-03T10:00:00", "-am", "A heading");
        GitDocumentMaterializer.materialize(dir, file);
        StoredDocument third = GitDocumentMaterializer.load(anchor, true).orElseThrow();
        onFx(() -> {
            view.showChanged(DocumentAssembly.single(third, "change-only-redraw.adoc"));
            return null;
        });
        assertEquals(0, (int) onFx(view::keptAreas), "nothing is kept when a heading changes");
        assertTrue(onFx(() -> blocks(surface)).stream().anyMatch(block -> block.plainText().contains("The last section")));

        // and show, as distinct from showChanged, always draws whole
        onFx(() -> {
            view.show(DocumentAssembly.single(third, "change-only-redraw.adoc"));
            return null;
        });
        assertEquals(0, (int) onFx(view::keptAreas));
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

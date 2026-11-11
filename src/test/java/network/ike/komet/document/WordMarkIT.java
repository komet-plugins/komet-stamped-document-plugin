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
import network.ike.komet.document.print.DocumentFonts;
import network.ike.komet.document.print.GreedyFxOracle;
import network.ike.komet.document.print.LayoutOracle;
import network.ike.komet.document.surface.BlockFactory;
import network.ike.komet.document.surface.DocumentSurface;
import network.ike.komet.document.surface.print.settings.PrintSettings;
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
 * Marks written over words selected where they are read, with no editor opened: in the Reading
 * view and on a page the words are found in their lines' source, the mark is toggled there, and
 * the block is saved as an edit of its source. The selection is the reader's own words; the
 * document's selection is the lines they touch.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WordMarkIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");

    private static final String FIRST = "**The release loaded.** The knowledge base registers one release,";
    private static final String SECOND = "`gudid-origin.zip`, with _emphasis_ on its *date*, as";
    private static final String THIRD = "xref:other-topic[the other topic] says.footnote:[A note at the foot.]";

    private static final String TEXT = """
            :topic-id: word-marks
            = Word Marks

            %s
            %s
            %s

            == A *strong* heading

            * An item with `code` in it.

            ----
            a listing keeps *its stars* and `ticks`
            ----
            """.formatted(FIRST, SECOND, THIRD);

    @TempDir
    Path root;

    /** What the surface asked its card to save, and what it said. */
    private final List<String> saved = new ArrayList<>();
    private final List<StoredBlock> savedBlocks = new ArrayList<>();
    private final List<String> said = new ArrayList<>();

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
    void aMarkIsWrittenOverWordsSelectedInTheReadingViewAndOnAPage() throws Exception {
        Path dir = Files.createDirectories(root.resolve("word-marks"));
        git(dir, "init", "-q", "-b", "main");
        git(dir, "config", "user.name", "author");
        git(dir, "config", "user.email", "author@example.test");
        Path file = dir.resolve("word-marks.adoc");
        Files.writeString(file, TEXT);
        git(dir, "add", "word-marks.adoc");
        git(dir, "commit", "-q", "--no-gpg-sign", "-m", "The document");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, file).anchor();
        StoredDocument document = GitDocumentMaterializer.load(anchor, true).orElseThrow();
        DocumentAssembly assembly = DocumentAssembly.single(document, "word-marks.adoc");

        DocumentSurfaceView view = onFx(() -> {
            DocumentSurfaceView surface = new DocumentSurfaceView(host(), new DocumentSurface(new BlockFactory(Calculators.View.Default(), 13)));
            surface.show(assembly);
            return surface;
        });
        int firstLine = assembly.lines().stream().map(GitDocumentMaterializer.StoredLine::text).toList().indexOf(FIRST);

        // ── the Reading view ──
        assertFalse(onFx(view::wordsSelected), "nothing selected at first");
        assertFalse(onFx(view::canMark));
        assertTrue(onFx(() -> view.selectWords("registers one release", 0)), "words are selected as they are drawn");
        assertEquals("registers one release", onFx(view::selectedWords));
        assertEquals(Optional.of(new LineSelection(firstLine, firstLine)), onFx(view::selection), "the document's selection is the line the words touch");
        assertTrue(onFx(view::canMark));
        onFx(() -> {
            view.markSelection(InlineMarks.BOLD);
            return null;
        });
        assertEquals(List.of(FIRST.replace("registers one release", "*registers one release*") + "\n" + SECOND + "\n" + THIRD), saved,
                "the mark is written in the line's source, and the block saved as its source: " + said);
        assertEquals(document.blocks().get(savedBlocks.getFirst().nid()).kind(), design.knowledge.stamp.adoc.dialect.Block.Kind.PARAGRAPH);

        assertEquals(FIRST + "\n" + SECOND.replace("*date*", "date") + "\n" + THIRD, mark(view, "date", InlineMarks.BOLD),
                "a marked phrase selected whole loses its mark");
        assertEquals(FIRST + "\n" + SECOND.replace("with _emphasis_", "*with _emphasis_*") + "\n" + THIRD, mark(view, "with emphasis", InlineMarks.BOLD),
                "a selection that takes in a marked phrase takes its marks, so the new mark nests");
        assertEquals(FIRST.replace("one release,", "_one release,") + "\n" + SECOND.replace("`gudid-origin.zip`", "`gudid-origin.zip`_") + "\n" + THIRD,
                mark(view, "one release, gudid-origin.zip", InlineMarks.ITALIC), "a selection over two lines of the paragraph is one marked phrase");
        assertEquals(Optional.of(new LineSelection(firstLine, firstLine + 1)), onFx(view::selection), "and the lines it touches are the selection");
        assertEquals("* An item with `code` in it.".replace("item with", "#item with#"), mark(view, "item with", InlineMarks.HIGHLIGHT), "a list item, past its marker");

        // what is not prose, or not words, is said and left alone
        int before = saved.size();
        said.clear();
        assertTrue(onFx(() -> view.selectWords("strong heading", 0)));
        assertFalse(onFx(view::canMark), "a heading is not edited in place");
        onFx(() -> {
            view.markSelection(InlineMarks.BOLD);
            return null;
        });
        assertTrue(onFx(() -> view.selectWords("its stars", 0)));
        onFx(() -> {
            view.markSelection(InlineMarks.BOLD);
            return null;
        });
        assertTrue(onFx(() -> view.selectWords("says.¹", 0)));
        onFx(() -> {
            view.markSelection(InlineMarks.BOLD);
            return null;
        });
        assertTrue(onFx(() -> view.selectWords("release loaded", 0)));
        onFx(() -> {
            view.markSelection(InlineMarks.BOLD);
            return null;
        });
        assertEquals(before, saved.size(), "nothing was saved: " + saved);
        assertEquals(4, said.size(), said.toString());
        assertTrue(said.get(0).startsWith("A mark is written in a paragraph") && said.get(1).startsWith("A mark is written in a paragraph"), said.toString());
        assertTrue(said.get(2).startsWith("That selection cannot be marked"), "a footnote's mark is not words: " + said);
        assertTrue(said.get(3).contains("already bold"), "words inside a longer bold phrase: " + said);
        onFx(() -> {
            view.select(new LineSelection(firstLine, firstLine + 1));
            return null;
        });
        assertFalse(onFx(view::wordsSelected), "a selection of lines is not one of words");

        // ── the Page layout: a page line holds part of a source line, and the words are found all the same ──
        PrintSettings settings = PrintSettings.DEFAULT;
        LayoutOracle.Geometry geometry = new LayoutOracle.Geometry(612, 792, 72, 72, 468, 648);
        onFx(() -> {
            view.setView(DocumentSurfaceView.View.PAGE_LAYOUT);
            List<LayoutOracle.Unit> units = view.pageUnits(settings.theme());
            view.layoutLines(new GreedyFxOracle(DocumentFonts.SERIF, DocumentFonts.MONO).layout(units, geometry), units);
            return view.linesOn(0, geometry.contentLeft(), geometry.contentWidth(), geometry.bandHeight());
        });
        assertEquals(FIRST.replace("knowledge base", "*knowledge base*") + "\n" + SECOND + "\n" + THIRD, mark(view, "knowledge base", InlineMarks.BOLD),
                "on the paragraph's first page line");
        assertEquals(FIRST + "\n" + SECOND + "\n" + THIRD.replace("the other topic] says", "the other topic] _says_"), mark(view, "says", InlineMarks.ITALIC),
                "and on a later one, where the source line began on the line above or stands part way along");
        assertEquals(FIRST + "\n" + SECOND.replace("_emphasis_", "emphasis") + "\n" + THIRD, mark(view, "emphasis", InlineMarks.ITALIC));
        assertEquals("* An item with `code` in it.".replace("`code`", "*`code`*"), mark(view, "code", InlineMarks.BOLD));
    }

    /** Selects words in the view shown, marks them, and gives the block text the surface asked its card to save. */
    private String mark(DocumentSurfaceView view, String words, InlineMarks mark) throws Exception {
        int before = saved.size();
        said.clear();
        assertTrue(onFx(() -> view.selectWords(words, 0)), "the view draws '" + words + "'");
        onFx(() -> {
            view.markSelection(mark);
            return null;
        });
        assertEquals(before + 1, saved.size(), "one block saved for '" + words + "': " + said);
        return saved.getLast();
    }

    private DocumentSurfaceView.Host host() {
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
                said.add(text);
            }

            @Override
            public void annotate(AnnotationKind kind) {
            }

            @Override
            public void saveEdit(StoredBlock edited, String text) {
                savedBlocks.add(edited);
                saved.add(text);
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

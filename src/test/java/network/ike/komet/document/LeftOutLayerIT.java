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

import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import design.knowledge.stamp.adoc.model.AnnotationKind;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import jfx.incubator.scene.control.richtext.RichTextArea;
import network.ike.komet.document.DocumentSurfaceView.Layer;
import network.ike.komet.document.GitDocumentMaterializer.StoredBlock;
import network.ike.komet.document.GitDocumentMaterializer.StoredDocument;
import network.ike.komet.document.GitDocumentMaterializer.StoredLine;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Reading view's Passages left out layer, from a document in git through the store: the
 * passages a conditional directive leaves out of the document as it reads are drawn faint, each
 * under a label that says when it is read, and a block a conditional parts is edited there as
 * its whole source, its directives among its lines, so nothing the view does not draw is lost.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeftOutLayerIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");
    private static final String LABEL = "Left out here: read only where compendium is set";
    private static final String OPENING = "A paragraph that ends as its reader needs,";
    private static final String FOR_COMPENDIUM = "with the ending the compendium keeps.";
    private static final String ELSEWHERE = "with the ending every other book keeps.";
    private static final String ONE_LINE = "A sentence the compendium reads from one line.";
    private static final String TABLE_TITLE = "A table the compendium keeps";

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

    /** A topic with a whole passage kept for the compendium, and a paragraph whose ending differs there. */
    private static String topic(String name) {
        return """
                :topic-id: %s
                = A Topic With Conditions

                Before the conditionals.

                ifdef::compendium[]
                Only the compendium reads this paragraph.

                And this one after it.
                endif::[]

                %s
                ifdef::compendium[]
                %s
                endif::[]
                ifndef::compendium[]
                %s
                endif::[]

                ifdef::compendium[%s]

                ifdef::compendium[]
                .%s
                [cols="1,3"]
                |===
                | 1 | A row only the compendium reads
                |===
                endif::[]

                After the conditionals.
                """.formatted(name, OPENING, FOR_COMPENDIUM, ELSEWHERE, ONE_LINE, TABLE_TITLE);
    }

    /** A repository of its own for each test: its own file, topic-id and commit day, so no two tests write one document's identity. */
    private DocumentAssembly document(String name, int day) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name));
        git(dir, "init", "-q", "-b", "main");
        git(dir, "config", "user.name", "reviewer");
        git(dir, "config", "user.email", "reviewer@example.test");
        git(dir, "config", "commit.gpgsign", "false");
        String file = name + ".adoc";
        Files.writeString(dir.resolve(file), topic(name));
        git(dir, "add", file);
        git(dir, "commit", "-q", "--no-gpg-sign", "--date=2026-06-" + String.format("%02d", day) + "T12:00:00", "-m", "The " + name + " topic");
        GitDocumentMaterializer.AssemblyResult made = GitDocumentMaterializer.materializeAssembly(dir, dir.resolve(file));
        PublicId anchor = made.assembly().anchor();
        StoredDocument stored = GitDocumentMaterializer.load(anchor, true).orElseThrow();
        return DocumentAssembly.of(stored, file, path -> Optional.empty());
    }

    /** What each block draws: its text area's words, which for a conditional that holds its text are that text and not its markup. */
    private static List<String> blockTexts(DocumentSurface body) {
        return IntStream.range(0, body.blockCount()).mapToObj(b -> drawn(body, b)).toList();
    }

    private static String drawn(DocumentSurface body, int block) {
        RichTextArea rta = body.block(block).richText();
        if (rta == null) {
            return body.block(block).plainText().replace('\n', ' ');
        }
        return IntStream.range(0, rta.getParagraphCount()).mapToObj(rta::getPlainText).reduce((a, b) -> a + " " + b).orElse("");
    }

    private static List<Node> inStack(DocumentSurface body, String selector) {
        List<Node> found = new ArrayList<>();
        for (int b = 0; b < body.blockCount(); b++) {
            found.addAll(body.block(b).stackNode().lookupAll(selector));
        }
        return found;
    }

    private static int blockReading(DocumentSurface body, String words) {
        return IntStream.range(0, body.blockCount()).filter(b -> drawn(body, b).contains(words)).findFirst()
                .orElseThrow(() -> new AssertionError("no block reads " + words + ": " + blockTexts(body)));
    }

    private static StoredLine line(DocumentAssembly assembly, String text) {
        return assembly.lines().stream().filter(l -> l.text().equals(text)).findFirst().orElseThrow(() -> new AssertionError("no line " + text));
    }

    @Test
    void thePassagesAConditionalLeavesOutAreDrawnFaintUnderALabelThatSaysWhenTheyAreRead() throws Exception {
        DocumentAssembly assembly = document("leftout-draw", 1);
        DocumentSurface body = onFx(() -> new DocumentSurface(new BlockFactory(Calculators.View.Default(), 13)));
        DocumentSurfaceView view = onFx(() -> {
            Stage stage = new Stage();
            stage.setScene(new Scene(body, 900, 1400));
            stage.show();
            DocumentSurfaceView made = new DocumentSurfaceView(new Host(), body);
            made.show(assembly);
            return made;
        });
        pulses();

        // as the document reads: neither passage kept for the compendium, and no directive
        String asRead = String.join(" | ", onFx(() -> blockTexts(body)));
        assertTrue(asRead.contains("Before the conditionals.") && asRead.contains(ELSEWHERE) && asRead.contains("After the conditionals."), asRead);
        assertFalse(asRead.contains("Only the compendium reads") || asRead.contains(FOR_COMPENDIUM) || asRead.contains(ONE_LINE),
                "what is left out is not drawn: " + asRead);
        assertFalse(asRead.contains("ifdef::") || asRead.contains("endif::"), "nor is a directive: " + asRead);
        assertEquals(List.of(), onFx(() -> inStack(body, ".stamped-document-left-out-label")));

        // with the layer on: both passages, each under its condition, faint; still no directive drawn
        onFx(() -> {
            view.setLayer(Layer.LEFT_OUT, true);
            view.show(assembly);
            return null;
        });
        pulses();
        String withLeftOut = String.join(" | ", onFx(() -> blockTexts(body)));
        assertTrue(withLeftOut.contains("Only the compendium reads this paragraph.") && withLeftOut.contains("And this one after it.")
                && withLeftOut.contains(FOR_COMPENDIUM), "the passages left out are drawn: " + withLeftOut);
        assertTrue(withLeftOut.contains(OPENING) && withLeftOut.contains(ELSEWHERE), "beside what is read: " + withLeftOut);
        assertFalse(withLeftOut.contains("ifdef::") || withLeftOut.contains("ifndef::") || withLeftOut.contains("endif::"),
                "a directive is drawn in neither: the label says its condition: " + withLeftOut);
        List<String> labels = onFx(() -> inStack(body, ".stamped-document-left-out-label").stream().map(n -> ((Label) n).getText()).toList());
        assertEquals(List.of(LABEL, LABEL, LABEL, LABEL), labels,
                "one label over the two paragraphs of the first passage, which is one passage; one over the ending kept for the compendium; "
                        + "one over the sentence a conditional holds on its own line; and one over the table");

        // a table left out is faint in all it is drawn with, its rows' grid as its lines, and its label stands above its title
        List<Node> rows = onFx(() -> inStack(body, ".doc-table-row"));
        assertFalse(rows.isEmpty(), "the table left out has its rows");
        for (Node row : rows) {
            assertEquals(0.5, onFx(row::getOpacity), 0.001, "a row's grid is as faint as the words about it");
        }
        Label tableTitle = (Label) onFx(() -> inStack(body, ".label").stream()
                .filter(n -> n instanceof Label l && l.getText() != null && l.getText().contains(TABLE_TITLE)).findFirst().orElseThrow());
        javafx.scene.Parent holder = onFx(tableTitle::getParent);
        Node topOfHolder = onFx(() -> holder.getChildrenUnmodifiable().getFirst());
        assertTrue(topOfHolder instanceof Label l && l.getText().equals(LABEL), "the label that says the passage is left out stands first, above the table's title");
        assertEquals(1.0, onFx(topOfHolder::getOpacity), 0.001, "and is read at full strength");
        assertEquals(0.5, onFx(tableTitle::getOpacity), 0.001, "the title beneath it is faint");
        assertTrue(withLeftOut.contains(ONE_LINE), "a conditional that holds its text is drawn as that text: " + withLeftOut);
        int oneLine = onFx(() -> blockReading(body, ONE_LINE));
        assertEquals(ONE_LINE, onFx(() -> drawn(body, oneLine)).strip(), "its words alone, not its markup");
        assertEquals(0.5, onFx(() -> body.block(oneLine).richText().getOpacity()), 0.001);

        int first = onFx(() -> blockReading(body, "Only the compendium reads this paragraph."));
        int second = onFx(() -> blockReading(body, "And this one after it."));
        int ending = onFx(() -> blockReading(body, FOR_COMPENDIUM));
        int read = onFx(() -> blockReading(body, ELSEWHERE));
        int opening = onFx(() -> blockReading(body, OPENING));
        for (int leftOut : List.of(first, second, ending)) {
            assertEquals(0.5, onFx(() -> body.block(leftOut).richText().getOpacity()), 0.001, "a passage left out is drawn faint");
        }
        for (int stands : List.of(read, opening)) {
            assertEquals(1.0, onFx(() -> body.block(stands).richText().getOpacity()), 0.001, "what is read is drawn as ever");
        }
        assertTrue(opening < ending && ending < read, "the paragraph's parts stand in the order of its lines: its opening, the ending left out, the ending read");
        assertFalse(onFx(() -> drawn(body, ending)).contains(OPENING), "and the part left out is drawn apart from the part that is read");

        // the lines left out are lines of the document: one can be selected, and so judged
        int at = assembly.ordinal(assembly.host(), line(assembly, FOR_COMPENDIUM).nid());
        onFx(() -> {
            view.select(new LineSelection(at, at));
            return null;
        });
        assertEquals(List.of(FOR_COMPENDIUM), onFx(() -> view.selectedLines().stream().map(StoredLine::text).toList()));

        // and off again
        onFx(() -> {
            view.setLayer(Layer.LEFT_OUT, false);
            view.show(assembly);
            return null;
        });
        pulses();
        assertEquals(asRead, String.join(" | ", onFx(() -> blockTexts(body))), "with the layer off the view is the document as it reads");
    }

    @Test
    void aBlockAConditionalPartsIsEditedAsItsWholeSource() throws Exception {
        DocumentAssembly assembly = document("leftout-edit", 2);
        List<String> saved = new ArrayList<>();
        List<StoredBlock> blocks = new ArrayList<>();
        List<String> said = new ArrayList<>();
        DocumentSurface body = onFx(() -> new DocumentSurface(new BlockFactory(Calculators.View.Default(), 13)));
        DocumentSurfaceView view = onFx(() -> {
            Stage stage = new Stage();
            stage.setScene(new Scene(body, 900, 1400));
            stage.show();
            DocumentSurfaceView made = new DocumentSurfaceView(new Host() {
                @Override
                public void saveEdit(StoredBlock edited, String text) {
                    blocks.add(edited);
                    saved.add(text);
                }

                @Override
                public void status(String text) {
                    said.add(text);
                }
            }, body);
            made.show(assembly);
            return made;
        });
        pulses();
        StoredLine leftOut = line(assembly, FOR_COMPENDIUM);
        int at = assembly.ordinal(assembly.host(), leftOut.nid());
        StoredBlock paragraph = assembly.host().document().blocks().values().stream().filter(b -> b.members().contains(leftOut.nid())).findFirst().orElseThrow();
        String source = paragraph.members().stream().map(nid -> assembly.host().document().line(nid).orElseThrow().text())
                .reduce((a, b) -> a + "\n" + b).orElseThrow();
        assertTrue(source.startsWith(OPENING) && source.contains("ifdef::compendium[]") && source.contains("ifndef::compendium[]") && source.endsWith("endif::[]"),
                "the fixture: the paragraph holds both endings and their directives as its own lines:\n" + source);

        // as the document reads, the line left out has no block to edit, and the view says where to turn
        onFx(() -> {
            view.select(new LineSelection(at, at));
            view.editSelectedBlock();
            return null;
        });
        assertEquals(List.of(), saved);
        assertTrue(said.getLast().contains("Passages left out"), "the status line says how to reach it: " + said);

        // with the layer on, the paragraph is edited from the ending left out, as its whole source
        onFx(() -> {
            view.setLayer(Layer.LEFT_OUT, true);
            view.show(assembly);
            view.select(new LineSelection(at, at));
            return null;
        });
        pulses();
        assertEquals(paragraph.nid(), onFx(view::selectedEditableBlock).orElseThrow(() -> new AssertionError("not editable")).nid());
        onFx(() -> {
            view.editSelectedBlock();
            return null;
        });
        pulses();
        RichTextArea editor = (RichTextArea) onFx(() -> body.lookup("#stampedDocumentBlockEditor"));
        assertEquals(source, onFx(() -> IntStream.range(0, editor.getParagraphCount()).mapToObj(editor::getPlainText).reduce((a, b) -> a + "\n" + b).orElseThrow()),
                "the editor holds every line of the block, the directives among them, so a save loses nothing");
        assertEquals(1.0, onFx(editor::getOpacity), 0.001, "the editor is drawn at full strength though its passage is left out");
        onFx(() -> {
            ((Button) body.lookup("#stampedDocumentSaveEdit")).fire();
            return null;
        });
        assertEquals(List.of(source), saved, "saved unchanged, the block is its source");
        assertEquals(paragraph.nid(), blocks.getFirst().nid());

        // a mark over words of the ending left out is saved in the whole block too
        onFx(() -> {
            view.show(assembly);
            return null;
        });
        pulses();
        assertTrue(onFx(() -> view.selectWords("the compendium keeps", 0)), "the words of the passage left out are found");
        assertTrue(onFx(view::canMark));
        onFx(() -> {
            view.markSelection(InlineMarks.BOLD);
            return null;
        });
        assertEquals(2, saved.size());
        assertEquals(source.replace("the compendium keeps", "*the compendium keeps*"), saved.get(1),
                "the mark is written in the line left out, and every other line of the block is kept");
    }

    private static void pulses() throws Exception {
        for (int i = 0; i < 12; i++) {
            Thread.sleep(60);
            onFx(() -> null);
        }
    }

    /** A host that says nothing and records nothing. */
    private static class Host implements DocumentSurfaceView.Host {
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
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
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
        return result.get(60, TimeUnit.SECONDS);
    }
}

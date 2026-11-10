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
import jfx.incubator.scene.control.richtext.model.RichParagraph;
import jfx.incubator.scene.control.richtext.model.StyleAttributeMap;
import jfx.incubator.scene.control.richtext.model.StyledSegment;
import jfx.incubator.scene.control.richtext.model.StyledTextModel;
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
 * Prose in the Reading view, read as the pages read it: strong text is bold with its marks gone,
 * monospace is in the mono font, a cross reference and a link read as their words, and a
 * footnote is a mark in the text with its words beneath the block. What is not prose, a listing
 * and the header's attribute lines, stays as written, and every character keeps its source line.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReadingInlineIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");

    private static final String TEXT = """
            :topic-id: reading-inline
            = Reading `Inline`

            **The release loaded.** The knowledge base registers one release,
            `gudid-origin.zip`, with _emphasis_ on its *date*, as
            xref:other-topic[the other topic] says.footnote:[A note at the foot.]

            == A *strong* heading

            * An item with `code` in it.

            NOTE: A note with *weight*.

            Its module is k:uuid=MODULE[Module], or [k:uuid=MODULE[Module]],
            not k:uuid=00000000-0000-4000-8000-000000000000[Nothing here].

            |===
            | Named | k:uuid=MODULE[Module] in a *cell*
            |===

            ----
            a listing keeps *its stars* and `ticks`
            ----
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
    void theReadingViewReadsProseAsThePagesDo() throws Exception {
        Path dir = Files.createDirectories(root.resolve("reading"));
        git(dir, "init", "-q", "-b", "main");
        git(dir, "config", "user.name", "author");
        git(dir, "config", "user.email", "author@example.test");
        Path file = dir.resolve("reading.adoc");
        // a concept the starter data holds, named as prose names one, and one no store holds
        String module = network.ike.foundation.ike.bindings.IkeConcept.ASCIIDOC_LITERAL_BLOCK.publicId().asUuidArray()[0].toString();
        Files.writeString(file, TEXT.replace("MODULE", module));
        git(dir, "add", "reading.adoc");
        git(dir, "commit", "-q", "--no-gpg-sign", "-m", "The document");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, file).anchor();
        StoredDocument document = GitDocumentMaterializer.load(anchor, true).orElseThrow();

        DocumentSurface body = onFx(() -> {
            DocumentSurface surface = new DocumentSurface(new BlockFactory(Calculators.View.Default(), 13));
            new DocumentSurfaceView(host(), surface).show(DocumentAssembly.single(document, "reading.adoc"));
            return surface;
        });

        List<String> texts = new ArrayList<>();
        List<String> bold = new ArrayList<>();
        List<String> italic = new ArrayList<>();
        List<String> mono = new ArrayList<>();
        List<String> footnotes = new ArrayList<>();
        List<String> chipped = new ArrayList<>();
        List<Node> chips = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        onFx(() -> {
            for (int b = 0; b < body.blockCount(); b++) {
                StyledTextModel model = body.block(b).model();
                if (model == null) {
                    continue;
                }
                StringBuilder text = new StringBuilder();
                for (int p = 0; p < model.size(); p++) {
                    RichParagraph paragraph = model.getParagraph(p);
                    text.append(p == 0 ? "" : "\n").append(model.getPlainText(p));
                    for (int k = 0; k < paragraph.getSegmentCount(); k++) {
                        StyledSegment segment = paragraph.getSegment(k);
                        StyleAttributeMap style = segment.getStyleAttributeMap(null);
                        if (segment.getType() == StyledSegment.Type.INLINE_NODE) {
                            chipped.add(model.getPlainText(p));
                            chips.add(segment.getInlineNodeGenerator().get());
                        }
                        if (segment.getType() != StyledSegment.Type.TEXT || style == null) {
                            continue;
                        }
                        if (!segment.getText().isBlank()) {
                            assertTrue(style.get(LineRunModel.LINE) != null, "every word keeps its source line: " + segment.getText());
                        }
                        // a heading is bold throughout; a span is told by a weight the text around it lacks
                        if (style.isBold()) {
                            bold.add(segment.getText());
                        }
                        if (style.isItalic()) {
                            italic.add(segment.getText());
                        }
                        if (LineRunModel.SCREEN_MONO.equals(style.getFontFamily())) {
                            mono.add(segment.getText());
                        }
                        if (style.isItalic()) {
                            // the app's body face has no italic, and JavaFX gives its bold for one
                            assertEquals(LineRunModel.SCREEN_ITALIC, style.getFontFamily(), "an italic is in a face that has one");
                        }
                    }
                }
                texts.add(text.toString());
                for (Node cell : body.block(b).node().lookupAll(".doc-table-cell")) {
                    for (Node child : ((javafx.scene.layout.Pane) cell).getChildren()) {
                        if (child instanceof jfx.incubator.scene.control.richtext.RichTextArea area) {
                            RichParagraph paragraph = area.getModel().getParagraph(0);
                            StringBuilder kinds = new StringBuilder(area.getModel().getPlainText(0)).append('|');
                            for (int k = 0; k < paragraph.getSegmentCount(); k++) {
                                StyledSegment segment = paragraph.getSegment(k);
                                kinds.append(segment.getType() == StyledSegment.Type.INLINE_NODE ? "chip"
                                        : segment.getStyleAttributeMap(null).isBold() ? "bold" : "text").append(' ');
                            }
                            cells.add(kinds.toString().strip());
                        }
                    }
                }
                for (Node note : body.block(b).node().lookupAll(".stamped-document-footnote")) {
                    footnotes.add(((javafx.scene.control.Label) note).getText());
                }
                for (Node label : body.block(b).node().lookupAll(".label")) {
                    assertFalse(((javafx.scene.control.Label) label).getText().contains("†"), "a footnote is said once, with its number");
                }
            }
            return null;
        });

        String paragraph = texts.stream().filter(t -> t.contains("The release loaded.")).findFirst().orElseThrow();
        assertEquals("The release loaded. The knowledge base registers one release, gudid-origin.zip, with emphasis on its date, as "
                + "the other topic says.¹", paragraph, "the paragraph reads as its words, the marks gone");
        assertTrue(bold.contains("The release loaded."), "a doubled strong mark is bold: " + bold);
        assertTrue(bold.contains("date"), "and a single one: " + bold);
        assertTrue(italic.contains("emphasis"), italic.toString());
        assertTrue(mono.contains("gudid-origin.zip"), mono.toString());
        assertEquals(List.of("¹A note at the foot."), footnotes, "the footnote's words stand beneath its block");

        assertTrue(texts.stream().anyMatch(t -> t.equals("Reading Inline")), "the title reads without its marks: " + texts);
        assertTrue(mono.contains("Inline"), "and keeps its monospace: " + mono);
        assertTrue(texts.stream().anyMatch(t -> t.equals("A strong heading")), texts.toString());
        assertTrue(texts.stream().anyMatch(t -> t.endsWith("An item with code in it.")), "a list item: " + texts);
        assertTrue(mono.contains("code"), mono.toString());
        assertTrue(texts.stream().anyMatch(t -> t.endsWith("A note with weight.")), "an admonition: " + texts);
        assertTrue(bold.contains("weight"), bold.toString());

        String named = "Its module is  , or  , not k:uuid=00000000-0000-4000-8000-000000000000[Nothing here].";
        assertEquals(List.of(named, named), chipped, "a concept the store holds is a chip in place of its reference, brackets written around "
                + "the reference going with it; one the store does not hold stays as written");
        assertEquals(2, chips.size());
        assertEquals(List.of("Named|text", "in a cell|chip text bold"), cells, "a table's cells read as prose does, a concept a chip and a strong word bold");
        assertTrue(chips.getFirst() instanceof dev.ikm.komet.framework.controls.KonceptBadge, "the chip the rest of Komet draws: " + chips.getFirst());

        assertTrue(texts.stream().anyMatch(t -> t.contains("a listing keeps *its stars* and `ticks`")), "a listing is its source: " + texts);
        assertTrue(texts.stream().anyMatch(t -> t.contains(":topic-id: reading-inline")), "the header's lines are as written: " + texts);
        assertFalse(bold.contains("its stars"), "and nothing in a listing is weighted");
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

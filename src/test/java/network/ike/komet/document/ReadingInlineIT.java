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
 * A table's own lines, its attributes, title and delimiters, are not drawn: its caption is.
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

            .Materials of the lens
            [cols="1,2"]
            |===
            | Material | Etafilcon A
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
        List<String> shown = new ArrayList<>();
        List<String> captions = new ArrayList<>();
        onFx(() -> {
            for (int b = 0; b < body.blockCount(); b++) {
                for (Node caption : body.block(b).node().lookupAll(".stamped-document-caption")) {
                    captions.add(((javafx.scene.control.Label) caption).getText());
                }
                StyledTextModel model = body.block(b).model();
                if (model == null) {
                    continue;
                }
                jfx.incubator.scene.control.richtext.RichTextArea drawn = body.block(b).richText();
                if (drawn != null && drawn.isVisible()) {
                    for (int p = 0; p < model.size(); p++) {
                        shown.add(model.getPlainText(p));
                    }
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
        assertEquals(List.of("Named|text", "in a cell|chip text bold", "Material|text", "Etafilcon A|text"), cells,
                "a table's cells read as prose does, a concept a chip and a strong word bold");
        for (String source : List.of("|===", "[cols", ".Materials")) {
            assertTrue(shown.stream().noneMatch(t -> t.contains(source)), "a table's own lines are its source, not drawn: " + source + " in " + shown);
        }
        assertTrue(captions.stream().anyMatch(c -> c.endsWith("Materials of the lens")), "a titled table has its caption: " + captions);
        assertTrue(chips.getFirst() instanceof dev.ikm.komet.framework.controls.KonceptBadge, "the chip the rest of Komet draws: " + chips.getFirst());

        assertTrue(texts.stream().anyMatch(t -> t.contains("a listing keeps *its stars* and `ticks`")), "a listing is its source: " + texts);
        assertTrue(texts.stream().anyMatch(t -> t.contains(":topic-id: reading-inline")), "the header's lines are as written: " + texts);
        assertFalse(bold.contains("its stars"), "and nothing in a listing is weighted");
    }

    @Test
    void aPageNamesAConceptWrittenWithNoLabelAsTheOpenStoreNamesIt() {
        PublicId literal = network.ike.foundation.ike.bindings.IkeConcept.ASCIIDOC_LITERAL_BLOCK.publicId();
        String uuid = literal.asUuidArray()[0].toString();
        String name = PrimitiveData.textOptional(PrimitiveData.nid(literal)).orElseThrow();
        assertEquals("A " + name + " here.", network.ike.komet.document.print.InlineText.of("A k:uuid=" + uuid + " here.").text(),
                "a token with no label prints as the name the open store gives its concept");
        assertEquals("A " + name + " here.", network.ike.komet.document.print.InlineText.of("A k:uuid=" + uuid + "[] here.").text(),
                "and so does one whose label is empty");
    }

    private static final String CITED = """
            :topic-id: reading-cited
            [[reading-cited]]
            = Reading Citations

            The value is that of <<hhsvalues2026>>, as xref:sec-method[the method] and <<sec-method>> say,
            and <<elsewhere>> does not.

            [[sec-method]]
            == Method

            How it is done.

            [bibliography]
            == References

            - [[[hhsvalues2026,HHS 2026, Standard Values]]] Kearsley, A. (2026). Standard values.
            """;

    @Test
    void theReadingViewDrawsACitationAsItsLabelAndFollowsItToTheEntry() throws Exception {
        Path dir = Files.createDirectories(root.resolve("cited"));
        git(dir, "init", "-q", "-b", "main");
        git(dir, "config", "user.name", "author");
        git(dir, "config", "user.email", "author@example.test");
        Path file = dir.resolve("cited.adoc");
        Files.writeString(file, CITED);
        git(dir, "add", "cited.adoc");
        git(dir, "commit", "-q", "--no-gpg-sign", "-m", "The document");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, file).anchor();
        StoredDocument document = GitDocumentMaterializer.load(anchor, true).orElseThrow();
        DocumentAssembly assembly = DocumentAssembly.single(document, "cited.adoc");

        DocumentSurface body = onFx(() -> new DocumentSurface(new BlockFactory(Calculators.View.Default(), 13)));
        DocumentSurfaceView view = onFx(() -> {
            DocumentSurfaceView made = new DocumentSurfaceView(host(), body);
            made.show(assembly);
            return made;
        });
        List<String> texts = new ArrayList<>();
        List<String> links = new ArrayList<>();
        onFx(() -> {
            for (int b = 0; b < body.blockCount(); b++) {
                StyledTextModel model = body.block(b).model();
                if (!(model instanceof LineRunModel lines)) {
                    continue;
                }
                for (int p = 0; p < model.size(); p++) {
                    String text = model.getPlainText(p);
                    texts.add(text);
                    RichParagraph paragraph = model.getParagraph(p);
                    int at = 0;
                    for (int k = 0; k < paragraph.getSegmentCount(); k++) {
                        StyledSegment segment = paragraph.getSegment(k);
                        if (segment.getType() == StyledSegment.Type.TEXT && LineRunModel.LINK.equals(segment.getStyleAttributeMap(null).getTextColor())) {
                            links.add(segment.getText() + " -> "
                                    + lines.internalLinkAt(jfx.incubator.scene.control.richtext.TextPos.ofLeading(p, at)).orElse("?"));
                        }
                        at += segment.getText() == null ? 1 : segment.getText().length();
                    }
                }
            }
            return null;
        });

        assertTrue(texts.contains("The value is that of [HHS 2026, Standard Values], as the method and Method say, and elsewhere does not."),
                "a citation reads as its entry's label in brackets, a bare reference as its section's title: " + texts);
        assertTrue(texts.stream().anyMatch(t -> t.endsWith("[HHS 2026, Standard Values] Kearsley, A. (2026). Standard values.")), "the entry begins with its label: " + texts);
        String entry = assembly.targets().of("hhsvalues2026").orElseThrow().key();
        String method = assembly.targets().of("sec-method").orElseThrow().key();
        assertEquals(List.of("[HHS 2026, Standard Values] -> " + entry, "the method -> " + method, "Method -> " + method), links,
                "what found its target is drawn as a link to the target's block, and nothing else is");

        // following a link reveals the block it names: here the last block of the document
        int target = onFx(() -> {
            view.follow(entry);
            for (int b = 0; b < body.blockCount(); b++) {
                StyledTextModel model = body.block(b).model();
                if (model != null && model.size() > 0 && model.getPlainText(0).contains("Kearsley")) {
                    return b;
                }
            }
            return -1;
        });
        assertTrue(target > 0, "the entry is a block of the view");
        assertTrue(assembly.targets().block(entry).isPresent() && assembly.targets().block("b0").isEmpty());
    }

    private static final String PICTURED = """
            :topic-id: reading-pictured
            = Reading Pictures
            :charts: .

            Before the chart.

            .A chart of two bars
            image::{charts}/images/chart.png["Two bars, the left one taller"]

            image::images/missing.png[]

            After it.
            """;

    @Test
    void anImageBlockWhoseFileIsInTheWorkingTreeIsShownAndPrintedAsItsPicture() throws Exception {
        Path dir = Files.createDirectories(root.resolve("pictured"));
        git(dir, "init", "-q", "-b", "main");
        git(dir, "config", "user.name", "author");
        git(dir, "config", "user.email", "author@example.test");
        Path file = dir.resolve("pictured.adoc");
        Files.writeString(file, PICTURED);
        Path chart = Files.createDirectories(dir.resolve("images")).resolve("chart.png");
        java.awt.image.BufferedImage bars = new java.awt.image.BufferedImage(200, 100, java.awt.image.BufferedImage.TYPE_INT_RGB);
        javax.imageio.ImageIO.write(bars, "png", chart.toFile());
        git(dir, "add", "pictured.adoc", "images/chart.png");
        git(dir, "commit", "-q", "--no-gpg-sign", "-m", "The document");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, file).anchor();
        StoredDocument document = GitDocumentMaterializer.load(anchor, true).orElseThrow();
        DocumentAssembly assembly = DocumentAssembly.single(document, "pictured.adoc");

        // the card's host, which knows the working tree the document was read from
        DocumentSurfaceView.Host plain = host();
        DocumentSurfaceView.Host inTree = (DocumentSurfaceView.Host) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {DocumentSurfaceView.Host.class},
                (proxy, method, args) -> method.getName().equals("workingTrees") ? List.of(dir) : method.invoke(plain, args));
        DocumentSurface body = onFx(() -> new DocumentSurface(new BlockFactory(Calculators.View.Default(), 13)));
        DocumentSurfaceView view = onFx(() -> {
            DocumentSurfaceView made = new DocumentSurfaceView(inTree, body);
            made.show(assembly);
            return made;
        });

        List<javafx.scene.image.ImageView> shown = onFx(() -> {
            List<javafx.scene.image.ImageView> found = new ArrayList<>();
            for (int b = 0; b < body.blockCount(); b++) {
                body.block(b).node().lookupAll(".stamped-document-picture").forEach(node -> found.add((javafx.scene.image.ImageView) node));
            }
            return found;
        });
        assertEquals(1, shown.size(), "the Reading view shows the picture the tree holds, and none for the file it does not");
        assertEquals(200, shown.getFirst().getImage().getWidth(), 0.5);

        network.ike.komet.document.surface.print.settings.PrintSettings settings = network.ike.komet.document.surface.print.settings.PrintSettings.DEFAULT;
        List<network.ike.komet.document.print.LayoutOracle.Unit> units = onFx(() -> view.pageUnits(settings.theme()));
        List<network.ike.komet.document.print.LayoutOracle.Unit> figures = units.stream()
                .filter(u -> u.kind() == network.ike.komet.document.print.LayoutOracle.Kind.FIGURE).toList();
        assertEquals(1, figures.size(), "on the pages the picture is a figure: " + units.stream().map(u -> u.kind() + " " + u.text()).toList());
        assertEquals(150, figures.getFirst().figureWidth(), 0.01, "at its own size, a pixel three quarters of a point");
        assertTrue(figures.getFirst().figureHeight() > 75, "with its caption beneath it: " + figures.getFirst().figureHeight());
        assertTrue(units.stream().anyMatch(u -> u.text().contains("images/missing.png")), "an image whose file is not found prints as its name");

        network.ike.komet.document.print.DocumentModel model = onFx(() -> network.ike.komet.document.print.StoredDocumentModel.of(assembly, view.printing()));
        network.ike.komet.document.print.DocumentModel.Drawn drawn = model.blocks().stream()
                .filter(network.ike.komet.document.print.DocumentModel.Drawn.class::isInstance)
                .map(network.ike.komet.document.print.DocumentModel.Drawn.class::cast).findFirst().orElseThrow();
        assertEquals(chart.toRealPath(), drawn.picture().file().toRealPath(), "the PDF embeds the same file");
        assertEquals("Figure 1. A chart of two bars", drawn.caption());
        assertEquals(1, model.blocks().stream().filter(network.ike.komet.document.print.DocumentModel.Image.class::isInstance).count(),
                "and names the one it could not find");
        String typst = network.ike.komet.document.print.TypstWriter.write(model, network.ike.komet.document.print.TypstWriter.PageSetup.DEFAULT);
        assertTrue(typst.contains("image(\"" + network.ike.komet.document.print.TypstWriter.pictureName(drawn.picture().file()) + "\", width: 150pt, height: 75pt)"), typst);
    }

    private static final String CONDITIONAL = """
            :topic-id: reading-conditional
            = Reading Conditionals
            :fn: pass:[<sup class="mark">†</sup><span class="note">]
            :fnend: pass:[</span>]
            :book:

            ++++
            <style>
            @media print {
              sup.mark { display: none; }
              span.note { float: footnote; }
            }
            </style>
            ++++

            Shown to every reader.{fn}The first note.{fnend}

            ifdef::compendium[]
            Only in the compendium.
            endif::[]
            ifndef::book[]
            NOTE: Only outside the book.
            endif::[]

            .The quantities{fn}Each is an estimate.{fnend}
            [cols="1,1", options="header"]
            |===
            | Quantity | Value{fn}In dollars.{fnend}
            | Price | 100
            |===

            After the table, reply {startsb}1{endsb}.

            ifdef::book[Said only in the book.]
            """;

    @Test
    void theReadingViewLeavesOutWhatAConditionalDoesAndSaysEachNoteOnce() throws Exception {
        Path dir = Files.createDirectories(root.resolve("conditional"));
        git(dir, "init", "-q", "-b", "main");
        git(dir, "config", "user.name", "author");
        git(dir, "config", "user.email", "author@example.test");
        Path file = dir.resolve("conditional.adoc");
        Files.writeString(file, CONDITIONAL);
        git(dir, "add", "conditional.adoc");
        git(dir, "commit", "-q", "--no-gpg-sign", "-m", "The document");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, file).anchor();
        StoredDocument document = GitDocumentMaterializer.load(anchor, true).orElseThrow();

        DocumentSurface body = onFx(() -> {
            DocumentSurface surface = new DocumentSurface(new BlockFactory(Calculators.View.Default(), 13));
            new DocumentSurfaceView(host(), surface).show(DocumentAssembly.single(document, "conditional.adoc"));
            return surface;
        });
        List<String> texts = new ArrayList<>();
        List<String> captions = new ArrayList<>();
        List<String> footnotes = new ArrayList<>();
        onFx(() -> {
            for (int b = 0; b < body.blockCount(); b++) {
                Node block = body.block(b).node();
                for (Node area : block.lookupAll(".rich-text-area")) {
                    if (area.isVisible() && area instanceof jfx.incubator.scene.control.richtext.RichTextArea rta && rta.getModel() != null) {
                        for (int p = 0; p < rta.getModel().size(); p++) {
                            texts.add(rta.getModel().getPlainText(p));
                        }
                    }
                }
                block.lookupAll(".stamped-document-caption").forEach(caption -> captions.add(((javafx.scene.control.Label) caption).getText()));
                block.lookupAll(".stamped-document-footnote").forEach(note -> footnotes.add(((javafx.scene.control.Label) note).getText()));
            }
            return null;
        });

        assertTrue(texts.contains("Shown to every reader.¹"), "a reference that opens a note and one that closes it read as a footnote: " + texts);
        assertFalse(texts.stream().anyMatch(t -> t.contains("ifdef::") || t.contains("ifndef::") || t.contains("endif::")), "no directive is read: " + texts);
        assertFalse(texts.stream().anyMatch(t -> t.contains("Only in the compendium") || t.contains("Only outside the book")),
                "nor a line a conditional leaves out: " + texts);
        assertEquals(List.of("Table 1. The quantities²"), captions, "a caption keeps the mark of its note");
        assertTrue(texts.contains("Value³"), "a cell's note is numbered on from the caption's: " + texts);
        assertEquals(List.of("¹The first note.", "²Each is an estimate.", "³In dollars."), footnotes, "each note's words once, beneath what it annotates");
        assertTrue(texts.contains("After the table, reply [1]."), "a character's reference is the character: " + texts);
        assertTrue(texts.contains("Said only in the book."), "a conditional that holds its text reads as that text, not as its source: " + texts);
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

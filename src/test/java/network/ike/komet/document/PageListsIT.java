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
import javafx.scene.layout.Pane;
import javafx.scene.text.Text;
import network.ike.komet.document.surface.BlockFactory;
import network.ike.komet.document.surface.DocumentSurface;
import network.ike.komet.document.surface.print.settings.PrintSettings;
import network.ike.komet.document.GitDocumentMaterializer.StoredBlock;
import network.ike.komet.document.GitDocumentMaterializer.StoredDocument;
import network.ike.komet.document.print.DocumentFonts;
import network.ike.komet.document.print.DocumentModel;
import network.ike.komet.document.print.Folios;
import network.ike.komet.document.print.FrontLists;
import network.ike.komet.document.print.GreedyFxOracle;
import network.ike.komet.document.print.LayoutOracle;
import network.ike.komet.document.print.ModelUnits;
import network.ike.komet.document.print.PageFidelity;
import network.ike.komet.document.print.RunningHeads;
import network.ike.komet.document.print.StoredDocumentModel;
import network.ike.komet.document.print.TypstCompiler;
import network.ike.komet.document.print.TypstOracle;
import network.ike.komet.document.print.TypstPdf;
import network.ike.komet.document.print.TypstWriter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The front lists on the Stamped Document card's pages, from a stored document that asks for
 * them through the units, the settling passes and the page lines: the pages open on a Contents
 * list and a Tables list whose entries are units of their own, each entry's page is where the
 * layout put its target, the second pass confirms the first, the page lines draw an entry's text
 * with its page after a leader, the word labels move nothing against the printed document, and
 * the printed document has as many pages as the pages do. The pages are numbered as
 * {@code dev-document-front-matter} settles it: no number on the title page, the lists in
 * lowercase roman, the body from 1, the same on the sheets of the Page layout as Typst prints
 * them, with each entry naming the number its target's page prints.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PageListsIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");
    private static final String FILLER = "The fence reads the record and the holder renews it again while the daemon keeps watch over every lease, "
            + "and the record travels by sync to every machine that shares the folder, where the next holder takes it up.";

    @TempDir
    Path root;

    @BeforeAll
    void setup() {
        assertTrue(PB_STARTER_DATA.exists(), "Starter data must be present at " + PB_STARTER_DATA.getAbsolutePath());
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException alreadyStarted) {
            // Started by another test class in this JVM.
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

    private static String document() {
        StringBuilder text = new StringBuilder("""
                :topic-id: page-lists
                = Page Lists
                :toc:
                :toclevels: 2
                :list-of-tables:
                :sectnums:

                == One

                """);
        for (int i = 0; i < 12; i++) {
            text.append(FILLER).append("\n\n");
        }
        text.append("""
                .First table
                [cols="1,3", options="header"]
                |===
                | Name | Value
                | a | b
                |===

                == Two

                === Deeper

                """);
        for (int i = 0; i < 16; i++) {
            text.append(FILLER).append("\n\n");
        }
        text.append("""
                .Second table
                |===
                | x | y
                |===

                == Three

                The end.
                """);
        return text.toString();
    }

    @Test
    void thePagesOpenOnTheListsWithTheirTargetsPagesAndPaginateAsThePdfDoes() throws Exception {
        StoredDocument document = stored("page-lists", document());
        DocumentSurfaceView surface = onFx(() -> {
            DocumentSurfaceView view = new DocumentSurfaceView(host(), new DocumentSurface(new BlockFactory(Calculators.View.Default(), 13)));
            view.show(DocumentAssembly.single(document, "page-lists.adoc"));
            return view;
        });
        PrintSettings settings = PrintSettings.DEFAULT;
        DocumentModel model = StoredDocumentModel.of(document, Map.of(), onFx(surface::printing));
        assertEquals(List.of("1 One", "2 Two", "2.1 Deeper", "3 Three"), ((DocumentModel.FrontList) model.blocks().get(0)).entries().stream()
                .map(DocumentModel.Entry::text).toList());
        assertEquals(List.of("Table 1. First table", "Table 2. Second table"), ((DocumentModel.FrontList) model.blocks().get(1)).entries().stream()
                .map(DocumentModel.Entry::text).toList());

        List<LayoutOracle.Unit> units = onFx(() -> surface.pageUnits(settings.theme()));
        assertEquals(List.of(LayoutOracle.Kind.TITLE, LayoutOracle.Kind.HEADING, LayoutOracle.Kind.ENTRY, LayoutOracle.Kind.ENTRY, LayoutOracle.Kind.ENTRY,
                LayoutOracle.Kind.ENTRY, LayoutOracle.Kind.HEADING, LayoutOracle.Kind.ENTRY, LayoutOracle.Kind.ENTRY, LayoutOracle.Kind.HEADING),
                units.subList(0, 10).stream().map(LayoutOracle.Unit::kind).toList(), "the title, the Contents list, the Tables list, then the body");
        assertEquals(List.of("Contents", "1 One", "2 Two", "2.1 Deeper", "3 Three", "Tables", "Table 1. First table", "Table 2. Second table", "1 One"),
                units.subList(1, 10).stream().map(LayoutOracle.Unit::text).toList());
        assertEquals(List.of(0, 1, 1, 1, 1, 1, 2, 2, 2, 3), units.subList(0, 10).stream().map(LayoutOracle.Unit::block).toList(),
                "each list is one block of the print model, as the writer labels them");
        assertEquals(settings.theme().baseFontSize(), units.get(4).indent(), 0.001, "a second-level entry is an em in");
        Map<String, Integer> targets = onFx(surface::entryTargets);
        assertEquals(9, targets.get(model.entries().getFirst().target()), "the first heading's entry names the unit the heading is");
        assertTrue(model.targets().stream().allMatch(targets::containsKey), "every entry's target is a unit on the pages");

        LayoutOracle.Geometry geometry = new LayoutOracle.Geometry(612, 792, 72, 72, 468, 648);
        GreedyFxOracle greedy = onFx(() -> new GreedyFxOracle(DocumentFonts.SERIF, DocumentFonts.MONO));
        LayoutOracle.Layout estimate = onFx(() -> greedy.layout(units, geometry));
        assertTrue(estimate.pages() >= 3, "the body runs over pages: " + estimate.pages());
        onFx(() -> {
            surface.beginLineCheck(geometry.contentLeft());
            surface.layoutLines(estimate, units);
            return null;
        });
        assertTrue(onFx(() -> surface.lineCheck()).contains(" 0 drawing other text"), "the estimate draws the entries as text");

        Assumptions.assumeTrue(TypstCompiler.locate().isPresent(), "typst is not installed");
        Path folder = Files.createDirectories(root.resolve("typst"));
        Path fonts = DocumentFonts.extractTo(folder.resolve("fonts"));
        Path source = folder.resolve("page-lists.typ");
        TypstWriter.PageSetup setup = TypstPdf.setup(settings);
        List<Integer> passes = new ArrayList<>();
        FrontLists.Settled settled = FrontLists.settle(model, FrontLists.pages(estimate, targets), targets, numbered -> {
            passes.add(numbered.entries().getFirst().page());
            Files.writeString(source, TypstWriter.write(numbered, setup, TypstWriter.Labels.WORDS), StandardCharsets.UTF_8);
            return new TypstOracle(folder, source, fonts, (text, unit, mono, bold, italic) -> supplyOnFx(() -> greedy.width(text, unit, mono, bold, italic)))
                    .layout(units, geometry);
        });
        LayoutOracle.Layout layout = settled.layout();
        assertEquals(List.of(), layout.notes());
        assertTrue(settled.passes() >= 1 && settled.passes() <= 2, "the pages settle in a pass or two: " + settled.passes() + " " + passes);
        Folios folios = layout.folios();
        assertEquals(new Folios(3, true), folios, "the title page, the contents and the tables are the front matter: the body begins on the fourth page");
        assertEquals(new Folios(3, true), estimate.folios(), "the estimate numbers the pages the same way");
        for (DocumentModel.Entry entry : settled.model().entries()) {
            int page = layout.pageOf(targets.get(entry.target()));
            assertEquals(page - 3 + 1, entry.page(), "the page printed for " + entry.text() + " is the number its target's page prints");
            assertEquals(folios.label(page), Integer.toString(entry.page()));
            assertEquals(entry.page(), settled.pages().get(entry.target()));
        }
        assertEquals(1, settled.pages().get(model.entries().getFirst().target()), "the title page, the contents, the tables, then the body on a page of its own, which is page 1");
        assertTrue(settled.pages().get(model.entries().get(1).target()) > 1, "Two starts on a later page");
        assertEquals(0, layout.pageOf(0), "the title stands alone on the first page");
        for (int u = 1; u < 6; u++) {
            assertEquals(1, layout.pageOf(u), "the contents list opens the second page: unit " + u);
        }
        for (int u = 6; u < 9; u++) {
            assertEquals(2, layout.pageOf(u), "the tables list opens the third: unit " + u);
        }
        assertEquals(3, layout.pageOf(9), "the body opens the fourth");
        for (int u = 2; u < 9; u++) {
            if (units.get(u).kind() == LayoutOracle.Kind.ENTRY) {
                List<LayoutOracle.Line> own = layout.ofUnit(u);
                assertEquals(2, own.size(), "a short entry is its mark and one line of text, its leader and page the renderer's: unit " + u + " " + own);
                assertEquals(own.getFirst().baseline(), own.getLast().baseline(), 0.01, "the mark on the text's baseline");
                assertEquals(units.get(u).text().length(), own.getLast().to());
            }
        }
        assertEquals(List.of(0, 1, 2, 3), List.of(estimate.pageOf(0), estimate.pageOf(1), estimate.pageOf(6), estimate.pageOf(9)), "the estimate opens the same pages");

        String check = onFx(() -> {
            surface.beginLineCheck(geometry.contentLeft());
            surface.layoutLines(layout, units);
            return surface.lineCheck();
        });
        assertTrue(check.contains(" 0 drawing other text"), check);
        assertTrue(onFx(() -> surface.linesOn(0, geometry.contentLeft(), geometry.contentWidth(), geometry.bandHeight())).stream()
                .noneMatch(p -> p.node().getStyleClass().contains("stamped-page-entry-tail")), "no entry on the title page");
        List<PageStackView.Placed> first = new ArrayList<>(onFx(() -> surface.linesOn(1, geometry.contentLeft(), geometry.contentWidth(), geometry.bandHeight())));
        first.addAll(onFx(() -> surface.linesOn(2, geometry.contentLeft(), geometry.contentWidth(), geometry.bandHeight())));
        List<Node> tails = first.stream().map(PageStackView.Placed::node).filter(n -> n.getStyleClass().contains("stamped-page-entry-tail")).toList();
        assertEquals(6, tails.size(), "every entry's last line ends in its leader and page");
        List<String> numbers = tails.stream().map(tail -> ((Pane) tail).getChildren().stream().filter(Text.class::isInstance).map(Text.class::cast)
                .map(Text::getText).findFirst().orElse("")).toList();
        assertEquals(settled.model().entries().stream().map(e -> Integer.toString(e.page())).toList(), numbers, "the page drawn is the page settled on");
        PageStackView.Placed tail = first.stream().filter(p -> p.node() == tails.getFirst()).findFirst().orElseThrow();
        assertEquals(geometry.contentLeft() + geometry.contentWidth(), tail.x() + tail.width(), 0.01, "the tail runs to the measure's right edge");
        assertEquals(units.get(2).index(), surface.identity(tail)[0], "the tail is its entry's");

        // The sheets of the Page layout draw the folios: none on the title page, roman on the lists, arabic from 1 in the body.
        List<String> expected = new ArrayList<>(List.of("", "ii", "iii", "1", "2"));
        for (int p = 5; p < layout.pages(); p++) {
            expected.add(Integer.toString(p - 2));
        }
        assertEquals(expected, java.util.stream.IntStream.range(0, layout.pages()).mapToObj(folios::label).toList());
        assertEquals(expected, onFx(() -> sheetFolios(surface, settings, layout, geometry)), "what each sheet draws in its footer");
        assertEquals("Page iii, 3 of " + layout.pages(), DocumentFooter.page(folios, 2, layout.pages()), "the card's footer on the list of tables");

        // A page that opens something carries no running head: the title's, each list's first, and each top-level section's first.
        assertEquals(List.of("Page Lists", "Contents", "Tables", "1 One", "2 Two", "3 Three"),
                units.stream().filter(LayoutOracle.Unit::opensChapter).map(LayoutOracle.Unit::text).toList(), "the units that open a chapter; 2.1 is no chapter");
        RunningHeads heads = RunningHeads.of(units, layout.lines());
        java.util.Set<Integer> opening = new java.util.TreeSet<>(List.of(0, 1, 2, 3));
        units.stream().filter(LayoutOracle.Unit::opensChapter).forEach(u -> opening.add(layout.pageOf(u.index())));
        assertEquals(opening, heads.openers());
        List<String> drawnHeads = onFx(() -> sheetFurniture(surface, settings, layout, heads, geometry, "stamped-page-running-head"));
        for (int p = 0; p < layout.pages(); p++) {
            assertEquals(!opening.contains(p), !drawnHeads.get(p).isEmpty(), "the running head on page " + (p + 1) + ": " + drawnHeads);
        }
        assertEquals(expected, onFx(() -> sheetFurniture(surface, settings, layout, heads, geometry, "stamped-page-folio")),
                "the folios stand in the foot whether or not the head is drawn");

        // The title is display type: centered in the measure, on every pass.
        for (LayoutOracle.Layout each : List.of(estimate, layout)) {
            LayoutOracle.Line title = each.ofUnit(0).getFirst();
            double width = onFx(() -> greedy.width("Page Lists", units.getFirst()));
            assertEquals(geometry.contentLeft() + (geometry.contentWidth() - width) / 2, title.x(), 0.5, "the title is centered");
        }
        assertEquals("Page 1, 4 of " + layout.pages(), DocumentFooter.page(folios, 3, layout.pages()), "and on the body's first page");

        // The word labels moved nothing against the printed document, and the printed document has the pages' count.
        String perturbation = PageFidelity.perturbation(settled.model(), settings, folder);
        assertTrue(perturbation.startsWith("label perturbation: 0 of "), perturbation);
        Path printed = folder.resolve("printed.typ");
        Files.writeString(printed, TypstWriter.write(model, setup, TypstWriter.Labels.BLOCKS), StandardCharsets.UTF_8);
        TypstCompiler.Result end = TypstCompiler.locate(folder, printed, TypstWriter.END_LABEL, fonts);
        assertTrue(end.ok(), end.output());
        assertEquals(layout.pages(), PageFidelity.parse(end.output()).getFirst().page(), "the printed document's page count is the pages'");
        TypstPdf.Printed pdf = TypstPdf.print(model, settings, folder, "page-lists");
        assertTrue(pdf.result().ok(), pdf.report());
        assertTrue(Files.size(pdf.pdf()) > 0);

        // The printed document prints the same folio on every page, and each entry the folio of its target's page.
        TypstCompiler.Result printedFolios = TypstCompiler.evaluate(folder, pdf.source(), "query(<" + TypstWriter.FOLIO_LABEL + ">).map(it => it.value)", fonts);
        assertTrue(printedFolios.ok(), printedFolios.output());
        assertEquals(expected, strings(printedFolios.output()), "the PDF's folios, page by page, are the sheets'");
        TypstCompiler.Result printedOpeners = TypstCompiler.evaluate(folder, pdf.source(),
                "query(selector(heading.where(level: 1)).or(<" + TypstWriter.OPENER_LABEL + ">)).map(it => str(it.location().page() - 1)).dedup()", fonts);
        assertTrue(printedOpeners.ok(), printedOpeners.output());
        assertEquals(opening.stream().map(String::valueOf).toList(), strings(printedOpeners.output()),
                "the PDF leaves its running head off the pages the sheets do");
        for (DocumentModel.Entry entry : settled.model().entries()) {
            TypstCompiler.Result counted = TypstCompiler.evaluate(folder, pdf.source(),
                    "query(<" + TypstWriter.TARGET_LABEL + entry.target() + ">).map(it => str(counter(page).at(it.location()).first()))", fonts);
            assertTrue(counted.ok(), counted.output());
            assertEquals(List.of(Integer.toString(entry.page())), strings(counted.output()), "the page the PDF prints for " + entry.text());
        }
        TypstCompiler.Result body = TypstCompiler.locate(folder, pdf.source(), TypstWriter.BODY_LABEL, fonts);
        assertTrue(body.ok(), body.output());
        assertEquals(folios.bodyPage() + 1, PageFidelity.parse(body.output()).getFirst().page(), "the PDF's body begins on the page the pages' body does");
    }

    @Test
    void aDocumentThatAsksForNoListKeepsItsTitleOnPageOneAndArabicNumbers() throws Exception {
        StoredDocument document = stored("page-plain", document().replace(":toc:\n", "").replace(":list-of-tables:\n", "").replace("page-lists", "page-plain"));
        DocumentSurfaceView surface = onFx(() -> {
            DocumentSurfaceView view = new DocumentSurfaceView(host(), new DocumentSurface(new BlockFactory(Calculators.View.Default(), 13)));
            view.show(DocumentAssembly.single(document, "page-plain.adoc"));
            return view;
        });
        PrintSettings settings = PrintSettings.DEFAULT;
        DocumentModel model = StoredDocumentModel.of(document, Map.of(), onFx(surface::printing));
        assertTrue(model.blocks().stream().noneMatch(DocumentModel.FrontList.class::isInstance), "no list asked for");
        assertTrue(!model.frontMatter() && !model.titlePage());

        List<LayoutOracle.Unit> units = onFx(() -> surface.pageUnits(settings.theme()));
        assertTrue(units.stream().noneMatch(LayoutOracle.Unit::opensBody), "no front matter, no body to open");
        assertTrue(units.stream().noneMatch(LayoutOracle.Unit::pageBefore));
        LayoutOracle.Geometry geometry = new LayoutOracle.Geometry(612, 792, 72, 72, 468, 648);
        GreedyFxOracle greedy = onFx(() -> new GreedyFxOracle(DocumentFonts.SERIF, DocumentFonts.MONO));
        LayoutOracle.Layout estimate = onFx(() -> greedy.layout(units, geometry));
        assertEquals(Folios.NONE, estimate.folios());
        assertEquals(0, estimate.pageOf(0), "the title");
        assertEquals(0, estimate.pageOf(1), "and the first heading under it, on page 1");
        onFx(() -> {
            surface.beginLineCheck(geometry.contentLeft());
            surface.layoutLines(estimate, units);
            return null;
        });
        List<String> arabic = java.util.stream.IntStream.rangeClosed(1, estimate.pages()).mapToObj(Integer::toString).toList();
        assertTrue(arabic.size() >= 2, "the body runs over pages: " + arabic);
        assertEquals(arabic, onFx(() -> sheetFolios(surface, settings, estimate, geometry)), "every sheet draws its place, from 1");
        assertEquals("Page 2 of " + estimate.pages(), DocumentFooter.page(estimate.folios(), 1, estimate.pages()));

        String source = TypstWriter.write(model, TypstPdf.setup(settings));
        assertTrue(source.contains("counter(page).display(\"1\")"), "the PDF numbers every page from 1");
        assertTrue(!source.contains(TypstWriter.FOLIO_LABEL) && !source.contains(TypstWriter.BODY_LABEL) && !source.contains("#pagebreak"), "and has no front matter");
    }

    @Test
    void theAuthorAndTheCommitsDateStandCenteredBeneathTheTitleOnThePagesAndInThePdf() throws Exception {
        StoredDocument document = stored("page-byline", """
                :topic-id: page-byline
                = Applying Decision Theory to Create Transparent and Reproducible Regulatory Decisions
                :author: Keith E. Campbell, MD, PhD

                == One

                The end.
                """);
        long committed = document.lines().stream().filter(l -> l.introduced() != null).mapToLong(l -> l.introduced().time()).max().orElseThrow();
        assertEquals(committed, StoredDocumentModel.committed(List.of(document)), "the commit that introduced the newest line");
        String date = StoredDocumentModel.date(committed);
        DocumentSurfaceView surface = onFx(() -> {
            DocumentSurfaceView view = new DocumentSurfaceView(host(), new DocumentSurface(new BlockFactory(Calculators.View.Default(), 13)));
            view.show(DocumentAssembly.single(document, "page-byline.adoc"));
            return view;
        });
        PrintSettings settings = PrintSettings.DEFAULT;
        DocumentModel model = StoredDocumentModel.of(document, Map.of(), onFx(surface::printing));
        assertEquals(new DocumentModel.Byline("Keith E. Campbell, MD, PhD", date), model.byline(), "no :revdate:, so the day of the newest commit");

        List<LayoutOracle.Unit> units = onFx(() -> surface.pageUnits(settings.theme()));
        assertEquals(List.of(LayoutOracle.Kind.TITLE, LayoutOracle.Kind.BYLINE, LayoutOracle.Kind.BYLINE, LayoutOracle.Kind.HEADING),
                units.subList(0, 4).stream().map(LayoutOracle.Unit::kind).toList());
        assertEquals(List.of("Keith E. Campbell, MD, PhD", date), units.subList(1, 3).stream().map(LayoutOracle.Unit::text).toList());
        assertEquals(List.of(0, 0, 0, 1), units.subList(0, 4).stream().map(LayoutOracle.Unit::block).toList(), "the byline is the title's block");
        assertEquals(units.subList(0, 4).stream().map(LayoutOracle.Unit::kind).toList(),
                ModelUnits.of(model, settings.theme().baseFontSize()).subList(0, 4).stream().map(LayoutOracle.Unit::kind).toList(), "as the model's units have it");

        LayoutOracle.Geometry geometry = new LayoutOracle.Geometry(612, 792, 72, 72, 468, 648);
        GreedyFxOracle greedy = onFx(() -> new GreedyFxOracle(DocumentFonts.SERIF, DocumentFonts.MONO));
        LayoutOracle.Layout estimate = onFx(() -> greedy.layout(units, geometry));
        Assumptions.assumeTrue(TypstCompiler.locate().isPresent(), "typst is not installed");
        Path folder = Files.createDirectories(root.resolve("typst-byline"));
        Path fonts = DocumentFonts.extractTo(folder.resolve("fonts"));
        Path source = folder.resolve("page-byline.typ");
        Files.writeString(source, TypstWriter.write(model, TypstPdf.setup(settings), TypstWriter.Labels.WORDS), StandardCharsets.UTF_8);
        LayoutOracle.Layout layout = new TypstOracle(folder, source, fonts, (text, unit, mono, bold, italic) -> supplyOnFx(() -> greedy.width(text, unit, mono, bold, italic)))
                .layout(units, geometry);
        assertEquals(List.of(), layout.notes());
        assertEquals(3, layout.ofUnit(0).size(), "the title in its three balanced lines");
        for (LayoutOracle.Layout each : List.of(estimate, layout)) {
            double under = each.ofUnit(0).getLast().baseline();
            for (int u = 1; u <= 2; u++) {
                List<LayoutOracle.Line> own = each.ofUnit(u);
                assertEquals(1, own.size(), "one line: unit " + u);
                LayoutOracle.Unit unit = units.get(u);
                double width = onFx(() -> greedy.width(unit.text(), unit));
                assertEquals(geometry.contentLeft() + (geometry.contentWidth() - width) / 2, own.getFirst().x(), 0.5, "centered: unit " + u);
                assertEquals(0, own.getFirst().page());
                assertTrue(own.getFirst().baseline() > under, "beneath what stands above it: unit " + u);
                under = own.getFirst().baseline();
            }
        }
        // the estimate's gaps are Typst's: author and date at the baselines Typst sets them
        for (int u = 1; u <= 2; u++) {
            assertEquals(layout.ofUnit(u).getFirst().baseline(), estimate.ofUnit(u).getFirst().baseline(), 1.0, "the estimate's baseline: unit " + u);
        }
        String check = onFx(() -> {
            surface.beginLineCheck(geometry.contentLeft());
            surface.layoutLines(layout, units);
            return surface.lineCheck();
        });
        assertTrue(check.contains(" 0 drawing other text"), check);

        TypstPdf.Printed pdf = TypstPdf.print(model, settings, folder, "page-byline");
        assertTrue(pdf.result().ok(), pdf.report());
        String perturbation = PageFidelity.perturbation(model, settings, folder);
        assertTrue(perturbation.startsWith("label perturbation: 0 of "), perturbation);
    }

    /** A document whose contents list holds entries too long for one line, at two depths, one that ends in a short word, and one of four words. */
    private static String longEntries() {
        return """
                :topic-id: page-long
                = Page Long
                :toc:
                :toclevels: 2
                :sectnums:

                == One

                %1$s

                == Clearing the jaw implant or asking for a wear test: what happens to the patients who received it in either case

                %1$s

                === How likely breakdown had to be for the wear test to be worth asking for, and what the answer would have cost

                %1$s

                == A First Example: A Jaw Implant and the Wear Test the FDA Did Not Ask For

                %1$s

                == The decision record

                The end.
                """.formatted(FILLER);
    }

    @Test
    void aLongEntryTurnsOverShortOfThePageNumbersColumnAndHangsUnderItsText() throws Exception {
        StoredDocument document = stored("page-long", longEntries());
        DocumentSurfaceView surface = onFx(() -> {
            DocumentSurfaceView view = new DocumentSurfaceView(host(), new DocumentSurface(new BlockFactory(Calculators.View.Default(), 13)));
            view.show(DocumentAssembly.single(document, "page-long.adoc"));
            return view;
        });
        PrintSettings settings = PrintSettings.DEFAULT;
        DocumentModel model = StoredDocumentModel.of(document, Map.of(), onFx(surface::printing));
        List<LayoutOracle.Unit> units = onFx(() -> surface.pageUnits(settings.theme()));
        List<LayoutOracle.Unit> entries = units.stream().filter(u -> u.kind() == LayoutOracle.Kind.ENTRY).toList();
        assertEquals(5, entries.size());
        assertEquals(List.of("1", "2", "2.1", "3", "4"), entries.stream().map(u -> FrontLists.mark(u.text())).toList());
        Map<String, Integer> targets = onFx(surface::entryTargets);
        LayoutOracle.Geometry geometry = new LayoutOracle.Geometry(612, 792, 72, 72, 468, 648);
        GreedyFxOracle greedy = onFx(() -> new GreedyFxOracle(DocumentFonts.SERIF, DocumentFonts.MONO));
        LayoutOracle.Measure measure = (text, unit, mono, bold, italic) -> supplyOnFx(() -> greedy.width(text, unit, mono, bold, italic));
        LayoutOracle.Layout estimate = onFx(() -> greedy.layout(units, geometry));

        Assumptions.assumeTrue(TypstCompiler.locate().isPresent(), "typst is not installed");
        Path folder = Files.createDirectories(root.resolve("typst"));
        Path fonts = DocumentFonts.extractTo(folder.resolve("fonts"));
        Path source = folder.resolve("page-long.typ");
        TypstWriter.PageSetup setup = TypstPdf.setup(settings);
        FrontLists.Settled settled = FrontLists.settle(model, FrontLists.pages(estimate, targets), targets, numbered -> {
            Files.writeString(source, TypstWriter.write(numbered, setup, TypstWriter.Labels.WORDS), StandardCharsets.UTF_8);
            return new TypstOracle(folder, source, fonts, measure).layout(units, geometry);
        });
        LayoutOracle.Layout layout = settled.layout();
        assertEquals(List.of(), layout.notes());

        double right = geometry.contentLeft() + geometry.contentWidth();
        Map<Integer, FrontLists.Place> places = FrontLists.places(units, measure);
        assertEquals(places.get(entries.get(0).index()), places.get(entries.get(4).index()), "the entries of a depth stand alike");
        assertEquals(places.get(entries.get(0).index()).text(), places.get(entries.get(2).index()).left(), 1e-9, "a depth begins under the text of the depth above");
        for (LayoutOracle.Unit entry : entries) {
            List<LayoutOracle.Line> own = layout.ofUnit(entry.index());
            FrontLists.Place place = places.get(entry.index());
            int mark = FrontLists.mark(entry.text()).length();
            boolean isLong = entry.text().length() > 60;
            assertEquals(isLong ? 3 : 2, own.size(), "the mark, and Typst turns a long entry's text over once: " + entry.text() + " " + own);
            assertEquals(entry.text().length(), own.getLast().to(), "the lines cover the entry's text: its leader and page are no line");
            assertEquals(estimate.ofUnit(entry.index()).stream().map(l -> List.of(l.from(), l.to())).toList(),
                    own.stream().map(l -> List.of(l.from(), l.to())).toList(), "the estimate breaks the entry where Typst does: " + entry.text());
            assertEquals(List.of(0, mark), List.of(own.getFirst().from(), own.getFirst().to()), "the mark is a line of its own");
            assertEquals(geometry.contentLeft() + place.left(), own.getFirst().x(), 0.01, "in the column of its depth's marks");
            assertEquals(mark + 1, own.get(1).from(), "the text begins after the mark and its space");
            assertEquals(own.getFirst().baseline(), own.get(1).baseline(), 0.01, "on the mark's baseline");
            double edge = right - FrontLists.reserve(entry, measure);
            for (int k = 1; k < own.size(); k++) {
                LayoutOracle.Line line = own.get(k);
                assertFalse(line.hyphenated(), "an entry is never hyphenated");
                assertEquals(geometry.contentLeft() + place.text(), line.x(), 0.01, "every line of the text stands after the column of marks");
                assertTrue(line.x() + measure.width(line.text(entry), entry, false, false, false) <= edge + 2,
                        "every line keeps the leader's and the page number's room clear: " + line.text(entry));
                if (k > 1) {
                    assertEquals(own.get(k - 1).baseline() + entry.fontSize() * LayoutOracle.PITCH, line.baseline(), 0.1, "a pitch under the line before");
                }
            }
        }

        LayoutOracle.Unit asked = entries.get(3);
        assertEquals("Ask For", layout.ofUnit(asked.index()).getLast().text(asked), "a short last word turns over with the word before it, never alone");

        // The pages draw each entry's leader and page on its last line, against the measure's right edge.
        onFx(() -> {
            surface.beginLineCheck(geometry.contentLeft());
            surface.layoutLines(layout, units);
            return null;
        });
        assertTrue(onFx(() -> surface.lineCheck()).contains(" 0 drawing other text"));
        int listPage = layout.pageOf(entries.getFirst().index());
        List<PageStackView.Placed> placed = onFx(() -> surface.linesOn(listPage, geometry.contentLeft(), geometry.contentWidth(), geometry.bandHeight()));
        List<PageStackView.Placed> tails = placed.stream().filter(p -> p.node().getStyleClass().contains("stamped-page-entry-tail")).toList();
        assertEquals(5, tails.size(), "one tail an entry, however many lines it runs to");
        for (int e = 0; e < entries.size(); e++) {
            LayoutOracle.Unit entry = entries.get(e);
            LayoutOracle.Line last = layout.ofUnit(entry.index()).getLast();
            PageStackView.Placed tail = tails.get(e);
            assertEquals(last.baseline(), tail.baseline(), 0.01, "the tail stands on the text's last line: " + entry.text());
            assertEquals(right, tail.x() + tail.width(), 0.01, "and runs to the measure's right edge");
            double least = entry.fontSize() * (TypstWriter.ENTRY_LEAD + TypstWriter.ENTRY_GUTTER) + measure.width(TypstWriter.ENTRY_NUMBER_ROOM, entry, false, false, false);
            assertTrue(tail.width() >= least - 0.01, "with room for the least leader and the number's column: " + tail.width() + " < " + least);
            Text number = ((Pane) tail.node()).getChildren().stream().filter(Text.class::isInstance).map(Text.class::cast).findFirst().orElseThrow();
            assertEquals(Integer.toString(settled.pages().get(model.entries().get(e).target())), number.getText());
        }

        // The word labels moved nothing against the printed document, whose entries turn over where the measured ones do.
        String perturbation = PageFidelity.perturbation(settled.model(), settings, folder);
        assertTrue(perturbation.startsWith("label perturbation: 0 of "), perturbation);
        TypstPdf.Printed pdf = TypstPdf.print(model, settings, folder, "page-long");
        assertTrue(pdf.result().ok(), pdf.report());
        assertTrue(Files.size(pdf.pdf()) > 0);
    }

    /**
     * The folio each sheet of the Page layout draws, in page order: a page stack shown in a scene
     * with the surface's own lines, as the card shows it, each page scrolled to so its sheet is
     * furnished. A sheet that draws no folio answers an empty text.
     */
    private static List<String> sheetFolios(DocumentSurfaceView surface, PrintSettings settings, LayoutOracle.Layout layout, LayoutOracle.Geometry geometry) {
        return sheetFurniture(surface, settings, layout, RunningHeads.EVERY_PAGE, geometry, "stamped-page-folio");
    }

    /**
     * What each sheet of the Page layout draws in the furniture of a style class, in page order, as
     * {@link #sheetFolios} does, the running heads standing where the rule says: an empty text for
     * a sheet that draws none.
     */
    private static List<String> sheetFurniture(DocumentSurfaceView surface, PrintSettings settings, LayoutOracle.Layout layout, RunningHeads heads,
                                               LayoutOracle.Geometry geometry, String styleClass) {
        PageStackView stack = new PageStackView(settings, geometry.pageWidth(), geometry.pageHeight(), "Page Lists");
        javafx.scene.Scene scene = new javafx.scene.Scene(stack, 700, 900);
        stack.show(new PageStackView.Content() {
            @Override
            public int pageCount() {
                return layout.pages();
            }

            @Override
            public List<PageStackView.Placed> lines(int page) {
                return surface.linesOn(page, geometry.contentLeft(), geometry.contentWidth(), geometry.bandHeight());
            }

            @Override
            public List<PageStackView.Place> places(int page) {
                return surface.placesOn(page, geometry.contentWidth(), geometry.bandHeight());
            }

            @Override
            public List<PageStackView.Mark> marginalia(int page) {
                return surface.marginaliaOn(page);
            }

            @Override
            public String section(int page) {
                return surface.sectionOn(page);
            }

            @Override
            public String folio(int page) {
                return layout.folios().label(page);
            }

            @Override
            public boolean runningHead(int page) {
                return heads.shown(page);
            }
        });
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        List<String> out = new ArrayList<>();
        for (int p = 0; p < layout.pages(); p++) {
            stack.scrollToPage(p);
            assertTrue(stack.isBuilt(p), "page " + (p + 1) + " is furnished once it is scrolled to");
            Node sheet = stack.lookup("#stampedDocumentPage" + (p + 1));
            List<String> drawn = ((Pane) sheet).getChildren().stream().filter(n -> n.getStyleClass().contains(styleClass))
                    .map(n -> ((javafx.scene.control.Label) n).getText()).toList();
            assertTrue(drawn.size() <= 1, "one " + styleClass + " a sheet at most: " + drawn);
            out.add(drawn.isEmpty() ? "" : drawn.getFirst());
        }
        return out;
    }

    /** The strings of a JSON list of strings, as {@code typst eval} answers one. */
    private static List<String> strings(String json) {
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"([^\"]*)\"").matcher(json);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** A document committed and stored, as the card reads it. */
    private StoredDocument stored(String name, String text) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name));
        git(dir, "init", "-q", "-b", "main");
        git(dir, "config", "user.name", "author");
        git(dir, "config", "user.email", "author@example.test");
        Path file = dir.resolve(name + ".adoc");
        Files.writeString(file, text);
        git(dir, "add", name + ".adoc");
        git(dir, "commit", "-q", "--no-gpg-sign", "-m", "The document");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, file).anchor();
        return GitDocumentMaterializer.load(anchor, true).orElseThrow();
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

    private static double supplyOnFx(Callable<Double> work) {
        try {
            return onFx(work);
        } catch (Exception e) {
            throw new IllegalStateException(e);
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
        Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
        }
    }
}

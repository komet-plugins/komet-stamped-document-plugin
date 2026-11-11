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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A document's abstract on the Stamped Document card ({@code dev-document-front-matter}, the
 * second step), from a stored document through the print model, the surface's units, both
 * layout oracles, the sheets of the Page layout and the printed PDF: the abstract is printed on
 * the title page beneath the title, the author and the date; the lists follow on the next
 * pages; the body begins on a page of its own, which is page 1; an abstract too long for the
 * title page runs on to pages numbered ii, iii before the contents; and a document with no
 * abstract and no list keeps its title on page 1 with its text beneath. The Reading view shows
 * the same order: the title, the abstract, the lists, the body.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PageAbstractIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");
    private static final String ABSTRACT = "We propose a way for a reviewer to decide what evidence to ask an applicant for, "
            + "and a record of that decision, a page or two long, that a second reviewer can check.";
    private static final String FILLER = "The fence reads the record and the holder renews it again while the daemon keeps watch over every lease, "
            + "and the record travels by sync to every machine that shares the folder, where the next holder takes it up.";
    private static final LayoutOracle.Geometry GEOMETRY = new LayoutOracle.Geometry(612, 792, 72, 72, 468, 648);

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

    /** Two sections of prose that run over pages, the first with a table. */
    private static String body() {
        StringBuilder text = new StringBuilder("\n== One\n\n");
        for (int i = 0; i < 9; i++) {
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

                """);
        for (int i = 0; i < 9; i++) {
            text.append(FILLER).append("\n\n");
        }
        return text.append("The end.\n").toString();
    }

    @Test
    void anAbstractWithNoListIsPrintedOnTheTitlePageAndTheBodyBeginsOnPageOne() throws Exception {
        Laid laid = lay("abstract-plain", """
                :topic-id: abstract-plain
                = Abstract Plain
                :author: Ada Author

                [abstract]
                %s
                """.formatted(ABSTRACT) + body(), 1);

        DocumentModel.Abstract first = (DocumentModel.Abstract) laid.model().blocks().getFirst();
        assertEquals(List.of(new DocumentModel.Paragraph(ABSTRACT)), first.blocks());
        assertTrue(laid.model().frontMatter() && laid.model().titlePage(), "an abstract gives a document front matter and a title page, though its header names no list");
        assertTrue(laid.model().entries().isEmpty(), "no list asked for, none drawn");

        int at = laid.unit(ABSTRACT);
        int heading = laid.unit("One");
        assertEquals(heading, at + 1, "the body's first block follows the abstract");
        assertTrue(laid.units().subList(0, at).stream().allMatch(u -> u.kind() == LayoutOracle.Kind.TITLE || u.kind() == LayoutOracle.Kind.BYLINE),
                "only the title, the author and the date stand above the abstract");
        assertEquals(LayoutOracle.Kind.PARAGRAPH, laid.units().get(at).kind());
        assertEquals(1, laid.units().get(at).block(), "the abstract is the model's first block after the title");
        assertFalse(laid.units().get(at).pageBefore(), "the abstract opens no page");
        assertTrue(laid.units().get(heading).pageBefore() && laid.units().get(heading).opensBody(), "the block after it opens a page, and the body");
        assertEquals(1, laid.units().stream().filter(LayoutOracle.Unit::opensBody).count());

        for (LayoutOracle.Layout each : List.of(laid.estimate(), laid.layout())) {
            assertEquals(0, each.pageOf(0), "the title");
            assertEquals(List.of(0), each.ofUnit(at).stream().map(LayoutOracle.Line::page).distinct().toList(), "the abstract is whole on the title page");
            assertTrue(each.ofUnit(at).getFirst().baseline() > each.ofUnit(at - 1).getLast().baseline(), "beneath the title, the author and the date");
            assertEquals(1, each.pageOf(heading), "the body begins on the second page");
            assertEquals(new Folios(1, true), each.folios(), "which is page 1: the title page is the only front matter");
        }
        assertEquals(laid.layout().ofUnit(at).getFirst().baseline(), laid.estimate().ofUnit(at).getFirst().baseline(), 1.0,
                "the estimate sets the abstract where Typst does");

        List<String> expected = new ArrayList<>(List.of(""));
        IntStream.range(1, laid.layout().pages()).forEach(p -> expected.add(Integer.toString(p)));
        assertTrue(expected.size() >= 3, "the body runs over pages: " + expected);
        assertEquals(expected, laid.sheets(), "no number on the title page, then the body from 1");
        assertEquals(expected, laid.printed(), "the PDF prints the same folio on every page");
        assertEquals(2, laid.printedBodyPage(), "the PDF's body begins on its second page");
        assertEquals("Title page, 1 of " + laid.layout().pages(), DocumentFooter.page(laid.layout().folios(), 0, laid.layout().pages()));
        assertEquals("Page 1, 2 of " + laid.layout().pages(), DocumentFooter.page(laid.layout().folios(), 1, laid.layout().pages()));
        assertEquals("", laid.heads().get(0), "the title page carries no running head");
        assertEquals("Page i, then 1 to " + (laid.layout().pages() - 1), DocumentFooter.pageRange(laid.layout().folios(), laid.layout().pages()),
                "the pages a reader may go to: the title page is the only front matter");

        // The Reading view: the title, the abstract, then the body, with the abstract shown once.
        List<String> reading = laid.reading();
        int shown = indexOf(reading, ABSTRACT);
        assertTrue(indexOf(reading, "Abstract Plain") < shown && shown < indexOf(reading, "== One"), "title, abstract, body: " + reading.subList(0, Math.min(6, reading.size())));
        assertEquals(1, reading.stream().filter(t -> t.contains(ABSTRACT)).count());
        assertEquals(-1, laid.listsBeneath(), "no list drawn");
    }

    @Test
    void anAbstractWithListsStandsBeforeThemAndIsNoEntryOfTheContents() throws Exception {
        Laid laid = lay("abstract-lists", """
                :topic-id: abstract-lists
                = Abstract Lists
                :toc:
                :toclevels: 2
                :list-of-tables:
                :sectnums:
                :author: Ada Author

                [abstract]
                --
                %s

                The record has two parts.

                * the request
                * the reason for it
                --
                """.formatted(ABSTRACT) + body(), 2);

        List<DocumentModel.Block> blocks = laid.model().blocks();
        assertTrue(blocks.get(0) instanceof DocumentModel.Abstract, "the abstract first");
        assertEquals(DocumentModel.FrontList.Kind.CONTENTS, ((DocumentModel.FrontList) blocks.get(1)).kind());
        assertEquals(DocumentModel.FrontList.Kind.TABLES, ((DocumentModel.FrontList) blocks.get(2)).kind());
        assertEquals(List.of("1 One", "2 Two"), ((DocumentModel.FrontList) blocks.get(1)).entries().stream().map(DocumentModel.Entry::text).toList(),
                "the contents list what comes after them: the abstract is no entry");

        int at = laid.unit(ABSTRACT);
        // found by its words: which bullet the pages draw for a list inside an open block is not this test's to say
        int item = laid.units().stream().filter(u -> u.kind() == LayoutOracle.Kind.LIST_ITEM && u.text().endsWith("the reason for it")).findFirst().orElseThrow().index();
        int contents = laid.unit("Contents");
        int tables = laid.unit("Tables");
        int heading = laid.units().stream().filter(u -> u.kind() == LayoutOracle.Kind.HEADING && u.text().equals("1 One")).findFirst().orElseThrow().index();
        assertEquals(contents, item + 1, "the contents follow the abstract's last unit");
        assertTrue(laid.units().subList(at, item + 1).stream().allMatch(u -> u.block() == 1 && !u.pageBefore() && !u.opensBody()),
                "every unit of the abstract is its one block, and none opens a page");
        assertTrue(laid.units().get(contents).pageBefore() && !laid.units().get(contents).opensBody(), "the contents open a page, in the front matter");
        assertTrue(laid.units().get(tables).pageBefore() && !laid.units().get(tables).opensBody());
        assertTrue(laid.units().get(heading).pageBefore() && laid.units().get(heading).opensBody(), "the body opens after the last list");

        for (LayoutOracle.Layout each : List.of(laid.estimate(), laid.layout())) {
            for (int u = 0; u <= item; u++) {
                assertEquals(List.of(0), each.ofUnit(u).stream().map(LayoutOracle.Line::page).distinct().toList(), "the title page holds the title and the abstract: unit " + u);
            }
            assertEquals(List.of(1, 2, 3), List.of(each.pageOf(contents), each.pageOf(tables), each.pageOf(heading)), "the contents, the tables, then the body, each on a page");
            assertEquals(new Folios(3, true), each.folios());
        }
        List<String> expected = new ArrayList<>(List.of("", "ii", "iii"));
        IntStream.range(3, laid.layout().pages()).forEach(p -> expected.add(Integer.toString(p - 2)));
        assertEquals(expected, laid.sheets(), "none on the title page, the lists in roman, the body from 1");
        assertEquals(expected, laid.printed(), "the PDF prints the same folio on every page");
        assertEquals(4, laid.printedBodyPage());
        assertEquals(1, laid.settled().pages().get(laid.model().entries().getFirst().target()), "the first section is on page 1");
        for (DocumentModel.Entry entry : laid.settled().model().entries()) {
            assertEquals(laid.layout().folios().label(laid.layout().pageOf(laid.targets().get(entry.target()))), Integer.toString(entry.page()),
                    "the page printed for " + entry.text() + " is the number its target's page prints");
        }

        // The Reading view: the title, the abstract, the lists beneath it, then the body.
        List<String> reading = laid.reading();
        int last = indexOf(reading, "the reason for it");
        assertTrue(indexOf(reading, "Abstract Lists") < indexOf(reading, ABSTRACT) && indexOf(reading, ABSTRACT) < last, reading.subList(0, Math.min(8, reading.size())).toString());
        assertEquals(last, laid.listsBeneath(), "the lists are drawn beneath the abstract's last block");
        assertTrue(last < indexOf(reading, "== One"), "and the body follows them");
        assertEquals(1, reading.stream().filter(t -> t.contains(ABSTRACT)).count());
    }

    @Test
    void aLongAbstractRunsOnToPagesNumberedInRomanBeforeTheContents() throws Exception {
        StringBuilder text = new StringBuilder("""
                :topic-id: abstract-long
                = Abstract Long
                :toc:
                :sectnums:
                :author: Ada Author

                [abstract]
                --
                """);
        for (int i = 0; i < 16; i++) {
            text.append(i).append(". ").append(FILLER).append("\n\n");
        }
        text.append("--\n").append(body());
        Laid laid = lay("abstract-long", text.toString(), 3);

        List<LayoutOracle.Unit> held = laid.units().stream().filter(u -> u.block() == 1 && u.kind() != LayoutOracle.Kind.FOOTNOTE).toList();
        assertEquals(16, held.size(), "the abstract's paragraphs");
        int contents = laid.unit("Contents");
        int heading = laid.units().stream().filter(u -> u.kind() == LayoutOracle.Kind.HEADING && u.text().equals("1 One")).findFirst().orElseThrow().index();
        assertEquals(contents, held.getLast().index() + 1);

        for (LayoutOracle.Layout each : List.of(laid.estimate(), laid.layout())) {
            assertEquals(0, each.pageOf(held.getFirst().index()), "the abstract begins on the title page");
            int runs = each.ofUnit(held.getLast().index()).getLast().page();
            assertEquals(1, runs, "and runs on to the next page");
            assertEquals(runs + 1, each.pageOf(contents), "the contents open the page after the abstract ends");
            assertEquals(runs + 2, each.pageOf(heading), "and the body the page after the contents");
            assertEquals(new Folios(runs + 2, true), each.folios());
        }
        assertEquals(laid.layout().ofUnit(held.getLast().index()).getLast().page(), laid.estimate().ofUnit(held.getLast().index()).getLast().page());

        List<String> expected = new ArrayList<>(List.of("", "ii", "iii"));
        IntStream.range(3, laid.layout().pages()).forEach(p -> expected.add(Integer.toString(p - 2)));
        assertEquals(expected, laid.sheets(), "the abstract's second page is ii, the contents iii, the body from 1");
        assertEquals(expected, laid.printed(), "the PDF prints the same folio on every page");
        assertEquals(4, laid.printedBodyPage());
        assertEquals("Page ii, 2 of " + laid.layout().pages(), DocumentFooter.page(laid.layout().folios(), 1, laid.layout().pages()));
        assertEquals("Page i to iii, then 1 to " + (laid.layout().pages() - 3), DocumentFooter.pageRange(laid.layout().folios(), laid.layout().pages()));
        assertEquals("", laid.heads().get(0), "the title page carries no running head");
        assertFalse(laid.heads().get(1).isEmpty(), "the page the abstract runs on to opens nothing, and carries one");
        assertEquals(1, laid.settled().pages().get(laid.model().entries().getFirst().target()), "the first section is still on page 1");
    }

    @Test
    void aDocumentWithNoAbstractAndNoListKeepsItsTitleOnPageOneWithItsTextBeneath() throws Exception {
        Laid laid = lay("abstract-none", """
                :topic-id: abstract-none
                = Abstract None
                :author: Ada Author

                %s
                """.formatted(ABSTRACT) + body(), 4);

        assertTrue(laid.model().abstractBlock().isEmpty());
        assertFalse(laid.model().frontMatter() || laid.model().titlePage());
        assertEquals(new DocumentModel.Paragraph(ABSTRACT), laid.model().blocks().getFirst(), "the same words with no style are the body's first paragraph");
        assertTrue(laid.units().stream().noneMatch(u -> u.pageBefore() || u.opensBody()), "no front matter, no page opened, no body to open");

        int at = laid.unit(ABSTRACT);
        int heading = laid.unit("One");
        for (LayoutOracle.Layout each : List.of(laid.estimate(), laid.layout())) {
            assertEquals(List.of(0, 0, 0), List.of(each.pageOf(0), each.pageOf(at), each.pageOf(heading)), "the title, the paragraph and the first heading, all on page 1");
            assertEquals(Folios.NONE, each.folios());
        }
        List<String> arabic = IntStream.rangeClosed(1, laid.layout().pages()).mapToObj(Integer::toString).toList();
        assertTrue(arabic.size() >= 2, "the body runs over pages: " + arabic);
        assertEquals(arabic, laid.sheets(), "every sheet draws its place, from 1");
        assertEquals(List.of(), laid.printed(), "the PDF has no folio of the front matter's kind");
        assertEquals(0, laid.printedBodyPage(), "and no body to open");
        assertTrue(laid.source().contains("counter(page).display(\"1\")"), "it numbers every page from 1");
        assertFalse(laid.source().contains("#pagebreak"));
        assertEquals("Page 2 of " + laid.layout().pages(), DocumentFooter.page(laid.layout().folios(), 1, laid.layout().pages()));

        List<String> reading = laid.reading();
        assertTrue(indexOf(reading, "Abstract None") < indexOf(reading, ABSTRACT) && indexOf(reading, ABSTRACT) < indexOf(reading, "== One"));
    }

    /**
     * A document laid out every way the card lays one out.
     *
     * @param model           the print model
     * @param units           the surface's units for the pages
     * @param targets         the unit each front list target begins with
     * @param estimate        the greedy layout
     * @param settled         what the Typst passes settled on
     * @param sheets          the folio each sheet of the Page layout draws
     * @param heads           the running head each sheet draws, empty for none
     * @param printed         the folio each page of the PDF prints; empty for a document with no front matter
     * @param printedBodyPage the 1-based page of the PDF the body begins on; 0 for a document with no front matter
     * @param source          the PDF's Typst source
     * @param reading         the text of each block of the Reading view, in order
     * @param listsBeneath    the Reading view block the generated lists are drawn beneath, or -1
     */
    private record Laid(DocumentModel model, List<LayoutOracle.Unit> units, Map<String, Integer> targets, LayoutOracle.Layout estimate,
                        FrontLists.Settled settled, List<String> sheets, List<String> heads, List<String> printed, int printedBodyPage, String source,
                        List<String> reading, int listsBeneath) {

        LayoutOracle.Layout layout() {
            return settled.layout();
        }

        /** The first unit whose text is the given text. */
        int unit(String text) {
            return units.stream().filter(u -> u.text().equals(text)).findFirst().orElseThrow(() -> new AssertionError("no unit reads: " + text)).index();
        }
    }

    private Laid lay(String name, String text, int day) throws Exception {
        StoredDocument document = stored(name, text, day);
        DocumentSurface body = onFx(() -> new DocumentSurface(new BlockFactory(Calculators.View.Default(), 13)));
        DocumentSurfaceView surface = onFx(() -> {
            DocumentSurfaceView view = new DocumentSurfaceView(host(), body);
            view.show(DocumentAssembly.single(document, name + ".adoc"));
            return view;
        });
        List<String> reading = new ArrayList<>();
        int listsBeneath = onFx(() -> {
            int beneath = -1;
            for (int b = 0; b < body.blockCount(); b++) {
                reading.add(body.block(b).plainText());
                if (body.block(b).node().lookup(".stamped-document-lists") != null) {
                    assertEquals(-1, beneath, "the lists are drawn once");
                    beneath = b;
                }
            }
            return beneath;
        });

        PrintSettings settings = PrintSettings.DEFAULT;
        DocumentModel model = StoredDocumentModel.of(document, Map.of(), onFx(surface::printing));
        List<LayoutOracle.Unit> units = onFx(() -> surface.pageUnits(settings.theme()));
        List<LayoutOracle.Unit> modelled = ModelUnits.of(model, settings.theme().baseFontSize());
        assertEquals(modelled.stream().map(u -> u.kind() + " " + u.block() + " " + u.pageBefore() + " " + u.opensBody()).toList(),
                units.stream().map(u -> u.kind() + " " + u.block() + " " + u.pageBefore() + " " + u.opensBody()).toList(),
                "the surface's units are the model's: the same kinds, blocks, opened pages and opened body");
        Map<String, Integer> targets = onFx(surface::entryTargets);

        GreedyFxOracle greedy = onFx(() -> new GreedyFxOracle(DocumentFonts.SERIF, DocumentFonts.MONO));
        LayoutOracle.Layout estimate = onFx(() -> greedy.layout(units, GEOMETRY));

        Assumptions.assumeTrue(TypstCompiler.locate().isPresent(), "typst is not installed");
        Path folder = Files.createDirectories(root.resolve(name + "-typst"));
        Path fonts = DocumentFonts.extractTo(folder.resolve("fonts"));
        Path measured = folder.resolve(name + ".typ");
        TypstWriter.PageSetup setup = TypstPdf.setup(settings);
        FrontLists.Settled settled = FrontLists.settle(model, FrontLists.pages(estimate, targets), targets, numbered -> {
            Files.writeString(measured, TypstWriter.write(numbered, setup, TypstWriter.Labels.WORDS), StandardCharsets.UTF_8);
            return new TypstOracle(folder, measured, fonts, (words, unit, mono, bold, italic) -> supplyOnFx(() -> greedy.width(words, unit, mono, bold, italic)))
                    .layout(units, GEOMETRY);
        });
        LayoutOracle.Layout layout = settled.layout();
        assertEquals(List.of(), layout.notes());
        assertTrue(settled.passes() <= 2, "the pages settle in a pass or two: " + settled.passes());

        String check = onFx(() -> {
            surface.beginLineCheck(GEOMETRY.contentLeft());
            surface.layoutLines(layout, units);
            return surface.lineCheck();
        });
        assertTrue(check.contains(" 0 drawing other text"), check);
        RunningHeads heads = RunningHeads.of(units, layout.lines());
        List<String> sheets = onFx(() -> sheetFurniture(surface, settings, layout, heads, model.title(), "stamped-page-folio"));
        List<String> drawnHeads = onFx(() -> sheetFurniture(surface, settings, layout, heads, model.title(), "stamped-page-running-head"));

        // The word labels moved nothing against the printed document, and the printed document has the pages' count.
        String perturbation = PageFidelity.perturbation(settled.model(), settings, folder);
        assertTrue(perturbation.startsWith("label perturbation: 0 of "), perturbation);
        TypstPdf.Printed pdf = TypstPdf.print(model, settings, folder, name);
        assertTrue(pdf.result().ok(), pdf.report());
        assertTrue(Files.size(pdf.pdf()) > 0);
        Path labelled = folder.resolve("labelled.typ");
        Files.writeString(labelled, TypstWriter.write(model, setup, TypstWriter.Labels.BLOCKS), StandardCharsets.UTF_8);
        TypstCompiler.Result end = TypstCompiler.locate(folder, labelled, TypstWriter.END_LABEL, fonts);
        assertTrue(end.ok(), end.output());
        assertEquals(layout.pages(), PageFidelity.parse(end.output()).getFirst().page(), "the printed document's page count is the pages'");

        String source = Files.readString(pdf.source());
        List<String> printed = List.of();
        int printedBodyPage = 0;
        if (model.frontMatter()) {
            TypstCompiler.Result folios = TypstCompiler.evaluate(folder, pdf.source(), "query(<" + TypstWriter.FOLIO_LABEL + ">).map(it => it.value)", fonts);
            assertTrue(folios.ok(), folios.output());
            printed = strings(folios.output());
            TypstCompiler.Result opened = TypstCompiler.locate(folder, pdf.source(), TypstWriter.BODY_LABEL, fonts);
            assertTrue(opened.ok(), opened.output());
            printedBodyPage = PageFidelity.parse(opened.output()).getFirst().page();
            assertEquals(layout.folios().bodyPage() + 1, printedBodyPage, "the PDF's body begins on the page the pages' body does");
        }
        return new Laid(model, units, targets, estimate, settled, sheets, drawnHeads, printed, printedBodyPage, source, reading, listsBeneath);
    }

    private static int indexOf(List<String> texts, String part) {
        for (int i = 0; i < texts.size(); i++) {
            if (texts.get(i).contains(part)) {
                return i;
            }
        }
        throw new AssertionError("no block reads: " + part + " in " + texts);
    }

    /**
     * What each sheet of the Page layout draws in the furniture of a style class, in page order: a
     * page stack shown in a scene with the surface's own lines, as the card shows it, each page
     * scrolled to so its sheet is furnished. A sheet that draws none answers an empty text.
     */
    private static List<String> sheetFurniture(DocumentSurfaceView surface, PrintSettings settings, LayoutOracle.Layout layout, RunningHeads heads,
                                               String title, String styleClass) {
        PageStackView stack = new PageStackView(settings, GEOMETRY.pageWidth(), GEOMETRY.pageHeight(), title);
        javafx.scene.Scene scene = new javafx.scene.Scene(stack, 700, 900);
        stack.show(new PageStackView.Content() {
            @Override
            public int pageCount() {
                return layout.pages();
            }

            @Override
            public List<PageStackView.Placed> lines(int page) {
                return surface.linesOn(page, GEOMETRY.contentLeft(), GEOMETRY.contentWidth(), GEOMETRY.bandHeight());
            }

            @Override
            public List<PageStackView.Place> places(int page) {
                return surface.placesOn(page, GEOMETRY.contentWidth(), GEOMETRY.bandHeight());
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

    /** A document committed on a day of its own and stored, as the card reads it: each test has its own file, topic and day. */
    private StoredDocument stored(String name, String text, int day) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name));
        git(dir, "init", "-q", "-b", "main");
        git(dir, "config", "user.name", "author");
        git(dir, "config", "user.email", "author@example.test");
        Path file = dir.resolve(name + ".adoc");
        Files.writeString(file, text);
        git(dir, "add", name + ".adoc");
        git(dir, "commit", "-q", "--no-gpg-sign", "--date=2026-04-" + String.format("%02d", day) + "T12:00:00", "-m", "The document");
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
        return result.get(60, TimeUnit.SECONDS);
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

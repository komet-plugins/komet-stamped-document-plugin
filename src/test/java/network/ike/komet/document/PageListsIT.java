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
import network.ike.komet.document.print.FrontLists;
import network.ike.komet.document.print.GreedyFxOracle;
import network.ike.komet.document.print.LayoutOracle;
import network.ike.komet.document.print.PageFidelity;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The front lists on the Stamped Document card's pages, from a stored document that asks for
 * them through the units, the settling passes and the page lines: the pages open on a Contents
 * list and a Tables list whose entries are units of their own, each entry's page is where the
 * layout put its target, the second pass confirms the first, the page lines draw an entry's text
 * with its page after a leader, the word labels move nothing against the printed document, and
 * the printed document has as many pages as the pages do.
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
        for (DocumentModel.Entry entry : settled.model().entries()) {
            assertEquals(layout.pageOf(targets.get(entry.target())) + 1, entry.page(), "the page printed for " + entry.text() + " is its target's");
            assertEquals(entry.page(), settled.pages().get(entry.target()));
        }
        assertEquals(4, settled.pages().get(model.entries().getFirst().target()), "the title page, the contents, the tables, then the body on a page of its own");
        assertTrue(settled.pages().get(model.entries().get(1).target()) > 4, "Two starts on a later page");
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
                assertTrue(own.size() >= 2 && own.getLast().tail(units.get(u)), "the oracle read the entry's tail: unit " + u + " " + own);
                assertEquals(own.get(own.size() - 2).baseline(), own.getLast().baseline(), 0.01, "a short entry's tail shares its line: unit " + u);
                assertEquals(own.getLast().page(), own.getFirst().page());
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

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
 * The markers and indents of list items on the Stamped Document card's pages, from a stored
 * document through the surface's units: a list's items carry the bullet Typst gives a list at
 * its depth among lists, whatever block holds the list. An example, a sidebar, a quote, an open
 * block or an admonition around a list is no list, and adds no depth; a list inside an item
 * does. The surface's items read as the print model's do ({@link ModelUnits}), which is what the
 * Typst writer writes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PageListMarkersIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");

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

    /** The text of each list item among some units, in order: its marker and its words. */
    private static List<String> items(List<LayoutOracle.Unit> units) {
        return units.stream().filter(u -> u.kind() == LayoutOracle.Kind.LIST_ITEM).map(LayoutOracle.Unit::text).toList();
    }

    @Test
    void aListInsideAContainerCarriesTheBulletOfItsDepthAmongLists() throws Exception {
        StoredDocument document = stored("list-markers", """
                :topic-id: list-markers
                = List Markers

                * top one
                ** nested one
                *** deeper one
                * top two

                ====
                An example.

                * in an example
                ** nested in an example
                ====

                --
                * in an open block
                --

                [NOTE]
                ====
                A note.

                * in an admonition
                ====

                ****
                * in a sidebar
                ****

                ____
                * in a quote
                ____

                . first
                .. inner first
                . second
                """, 1);
        DocumentSurfaceView surface = onFx(() -> {
            DocumentSurfaceView view = new DocumentSurfaceView(host(), new DocumentSurface(new BlockFactory(Calculators.View.Default(), 13)));
            view.show(DocumentAssembly.single(document, "list-markers.adoc"));
            return view;
        });
        PrintSettings settings = PrintSettings.DEFAULT;
        double base = settings.theme().baseFontSize();
        DocumentModel model = StoredDocumentModel.of(document, Map.of(), onFx(surface::printing));
        List<LayoutOracle.Unit> units = onFx(() -> surface.pageUnits(settings.theme()));
        List<LayoutOracle.Unit> modelled = ModelUnits.of(model, base);

        assertEquals(List.of("• top one", "‣ nested one", "– deeper one", "• top two",
                "• in an example", "‣ nested in an example", "• in an open block", "• in an admonition", "• in a sidebar", "• in a quote",
                "1. first", "1. inner first", "2. second"),
                items(units), "a bullet follows the list's depth among lists: a container around a list adds none");
        // where an item stands is Typst's to say once it answers; the estimate's indent is not this test's
        assertEquals(items(modelled), items(units), "the surface's items read as the print model's do, which is what the Typst writer writes");
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

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
import design.knowledge.stamp.adoc.git.AnnotationCommit;
import design.knowledge.stamp.adoc.model.AnnotationKind;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;
import jfx.incubator.scene.control.richtext.RichTextArea;
import jfx.incubator.scene.control.richtext.SelectionSegment;
import network.ike.komet.document.DocumentSurfaceView.Layer;
import network.ike.komet.document.GitDocumentMaterializer.StoredAnnotation;
import network.ike.komet.document.GitDocumentMaterializer.StoredBlock;
import network.ike.komet.document.GitDocumentMaterializer.StoredDocument;
import network.ike.komet.document.GitDocumentMaterializer.StoredLine;
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
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One passage shown in two places on the Stamped Document card ({@code dev-document-front-matter},
 * the third step), from documents in git through the store: a book whose abstract includes the
 * tagged region of a topic and whose body includes the whole topic. The passage is one set of
 * stored lines. A selection is of the place the reader pointed at; an annotation names the
 * topic's line once, and is read beside the line in both places; and the pages print the passage
 * on the title page and again as the opening of the topic, on page 1.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RegionShownTwiceIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");
    private static final String BRIEF_ONE = "We propose a way for a reviewer to decide what evidence to ask an applicant for,";
    private static final String BRIEF_TWO = "and a record of that decision that a second reviewer can check.";
    private static final String BRIEF = BRIEF_ONE + " " + BRIEF_TWO;
    private static final String FILLER = "The fence reads the record and the holder renews it again while the daemon keeps watch over every lease, "
            + "and the record travels by sync to every machine that shares the folder, where the next holder takes it up.";
    private static final LayoutOracle.Geometry GEOMETRY = new LayoutOracle.Geometry(612, 792, 72, 72, 468, 648);

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

    /** The topic: its opening paragraph marked as the passage, as the design topic writes it, the end tag under the paragraph's last line. */
    private static String topic(String name, String endTag) {
        return """
                :topic-id: %s
                = The Proposal in Brief

                // tag::brief[]
                %s
                %s
                %s

                == The problem

                A reviewer decides alone, and nobody can check how.
                """.formatted(name, BRIEF_ONE, BRIEF_TWO, endTag);
    }

    /** The book: the region in its abstract, the whole topic in its body, then a section of its own. */
    private static String book(String name, String topicFile, String more) {
        return """
                :topic-id: %s
                = Applying Decision Theory
                :toc:
                :sectnums:
                :author: Ada Author

                [abstract]
                --
                include::%s[tag=brief]
                --

                :sectnums!:

                include::%s[leveloffset=+1]

                :sectnums:

                == Method

                How it is done.
                %s""".formatted(name, topicFile, topicFile, more);
    }

    /** A book and its topic as the store holds them, and the working tree they came from. */
    private record Books(Path dir, String bookFile, String topicFile, PublicId bookAnchor, PublicId topicAnchor) {

        DocumentAssembly assembly() {
            StoredDocument book = GitDocumentMaterializer.load(bookAnchor, true).orElseThrow();
            StoredDocument topic = GitDocumentMaterializer.load(topicAnchor, true).orElseThrow();
            return DocumentAssembly.of(book, bookFile, path -> path.equals(topicFile) ? Optional.of(topic) : Optional.empty());
        }
    }

    /** A repository of its own for each test: its own files, topic-ids and commit day, so no two tests write one document's identity. */
    private Books books(String name, int day, String endTag, String more) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name));
        git(dir, "init", "-q", "-b", "main");
        git(dir, "config", "user.name", "reviewer");
        git(dir, "config", "user.email", "reviewer@example.test");
        git(dir, "config", "commit.gpgsign", "false");
        String bookFile = name + "-book.adoc";
        String topicFile = name + "-summary.adoc";
        Files.writeString(dir.resolve(bookFile), book(name + "-book", topicFile, more));
        Files.writeString(dir.resolve(topicFile), topic(name + "-summary", endTag));
        git(dir, "add", bookFile, topicFile);
        git(dir, "commit", "-q", "--no-gpg-sign", "--date=2026-05-" + String.format("%02d", day) + "T12:00:00", "-m", "The " + name + " book");
        GitDocumentMaterializer.AssemblyResult made = GitDocumentMaterializer.materializeAssembly(dir, dir.resolve(bookFile));
        assertEquals(2, made.documents().size(), "the topic is included twice and stored once: " + made.documents());
        assertEquals(List.of(), made.unresolved());
        return new Books(dir, bookFile, topicFile, made.assembly().anchor(), GitDocumentMaterializer.documentAnchor(dir, topicFile));
    }

    @Test
    void aLineShownTwiceIsSelectedInThePlaceTheReaderPointedAt() throws Exception {
        Books books = books("region-select", 1, "// end::brief[]", "");
        DocumentAssembly assembly = books.assembly();
        DocumentAssembly.Member region = assembly.children().get(0);
        DocumentAssembly.Member whole = assembly.children().get(1);
        assertEquals(List.of(BRIEF_ONE, BRIEF_TWO), region.shown().lines().stream().map(StoredLine::text).toList());
        StoredLine first = whole.document().lines().stream().filter(l -> l.text().equals(BRIEF_ONE)).findFirst().orElseThrow();
        int inAbstract = assembly.ordinal(region, first.nid());
        int inBody = assembly.ordinal(whole, first.nid());
        assertTrue(inAbstract >= 0 && inBody > inAbstract);
        assertSame(assembly.lines().get(inAbstract), assembly.lines().get(inBody), "one stored line, at two places");

        DocumentSurface body = onFx(() -> new DocumentSurface(new BlockFactory(Calculators.View.Default(), 13)));
        DocumentSurfaceView view = onFx(() -> {
            Stage stage = new Stage();
            stage.setScene(new Scene(body, 900, 1400));
            stage.show();
            DocumentSurfaceView made = new DocumentSurfaceView(host(), body);
            made.show(assembly);
            return made;
        });
        pulses();
        List<Integer> blocks = onFx(() -> blocksReading(body, BRIEF));
        assertEquals(2, blocks.size(), "the Reading view shows the passage twice: in the abstract, and as the topic's opening");
        int abstractBlock = blocks.get(0);
        int bodyBlock = blocks.get(1);
        assertFalse(onFx(() -> body.block(abstractBlock).plainText()).contains("end::"), "no tag line in the abstract");
        assertTrue(onFx(() -> body.block(abstractBlock).stackNode().lookupAll(".stamped-document-member-path").stream()
                        .anyMatch(n -> ((javafx.scene.control.Label) n).getText().equals("↳ " + books.topicFile() + " · tag=brief"))),
                "the abstract names the file and the region it shows");

        // the lines selected in the abstract are drawn selected there, and nowhere else
        onFx(() -> {
            view.select(new LineSelection(inAbstract, inAbstract + 1));
            return null;
        });
        pulses();
        assertTrue(onFx(() -> selected(body, abstractBlock)), "drawn selected in the abstract");
        assertFalse(onFx(() -> selected(body, bodyBlock)), "and not in the body, where the same lines stand again");
        assertEquals(List.of(BRIEF_ONE, BRIEF_TWO), onFx(() -> view.selectedLines().stream().map(StoredLine::text).toList()));

        // and the other way about
        onFx(() -> {
            view.select(new LineSelection(inBody, inBody + 1));
            return null;
        });
        pulses();
        assertFalse(onFx(() -> selected(body, abstractBlock)));
        assertTrue(onFx(() -> selected(body, bodyBlock)), "drawn selected in the body");

        // words selected in one place are that place's lines
        assertTrue(onFx(() -> view.selectWords("a second reviewer", 0)));
        assertEquals(new LineSelection(inAbstract + 1, inAbstract + 1), onFx(() -> view.selection().orElseThrow()), "the first occurrence is the abstract's");
        assertTrue(onFx(() -> view.selectWords("a second reviewer", 1)));
        assertEquals(new LineSelection(inBody + 1, inBody + 1), onFx(() -> view.selection().orElseThrow()), "the second is the body's");

        // either place locates to the topic's own lines 5-6: what an annotation would name
        for (int at : List.of(inAbstract, inBody)) {
            DocumentAssembly.Located located = assembly.locate(new LineSelection(at, at + 1)).orElseThrow();
            assertEquals(books.topicFile(), located.member().path());
            assertEquals(new LineSelection(4, 5), located.local());
        }

        // the paragraph holds its end tag line in the file, so the region cuts it: it is edited where the topic is shown whole
        onFx(() -> {
            view.select(new LineSelection(inAbstract, inAbstract));
            return null;
        });
        assertTrue(onFx(view::selectedEditableBlock).isEmpty(), "read, not edited, in the abstract");
        onFx(() -> {
            view.select(new LineSelection(inBody, inBody));
            return null;
        });
        StoredBlock editable = onFx(view::selectedEditableBlock).orElseThrow();
        assertEquals(3, editable.members().size(), "the body's block is the whole paragraph, its end tag line among its lines");
        assertFalse(onFx(() -> body.block(bodyBlock).plainText()).isEmpty());

        // the Source view shows the region's lines with the numbers they have in the topic's file
        onFx(() -> {
            view.setView(DocumentSurfaceView.View.SOURCE);
            return null;
        });
        pulses();
        List<String> ledger = onFx(() -> IntStream.range(0, body.blockCount()).mapToObj(b -> body.block(b).plainText()).toList());
        assertEquals(2, ledger.stream().filter(t -> t.contains(BRIEF_ONE)).count(), "the ledger shows the passage in both places");
        assertEquals(1, ledger.stream().filter(t -> t.contains("// tag::brief[]")).count(), "and its tag lines once, where the topic is shown whole");
    }

    @Test
    void aBlockTheRegionHoldsWholeIsEditedInEitherPlaceAsOneBlock() throws Exception {
        // the end tag on a line of its own, a blank line under the paragraph: the paragraph is whole in the region
        Books books = books("region-edit", 2, "\n// end::brief[]", "");
        DocumentAssembly assembly = books.assembly();
        DocumentAssembly.Member region = assembly.children().get(0);
        DocumentAssembly.Member whole = assembly.children().get(1);
        StoredLine first = whole.document().lines().stream().filter(l -> l.text().equals(BRIEF_ONE)).findFirst().orElseThrow();
        List<StoredBlock> saved = new ArrayList<>();
        DocumentSurface body = onFx(() -> new DocumentSurface(new BlockFactory(Calculators.View.Default(), 13)));
        DocumentSurfaceView view = onFx(() -> {
            DocumentSurfaceView made = new DocumentSurfaceView(new Host() {
                @Override
                public void saveEdit(StoredBlock edited, String text) {
                    saved.add(edited);
                }
            }, body);
            made.show(assembly);
            return made;
        });
        List<Long> edited = new ArrayList<>();
        for (DocumentAssembly.Member member : List.of(region, whole)) {
            int at = assembly.ordinal(member, first.nid());
            onFx(() -> {
                view.select(new LineSelection(at, at));
                return null;
            });
            StoredBlock block = onFx(view::selectedEditableBlock).orElseThrow(() -> new AssertionError("not editable in " + member.tags()));
            edited.add(block.nid());
            assertEquals(2, block.members().size(), "the paragraph's two lines");
        }
        assertEquals(edited.get(0), edited.get(1), "the block is the same block in either place: an edit of it is one edit");
        assertEquals(List.of(), saved);
    }

    @Test
    void aMarkOverWordsOfTheAbstractIsSavedAsTheWholeBlockWithItsTagLine() throws Exception {
        // the end tag straight under the paragraph: the region cuts the block, which is whole where the topic is shown whole
        Books books = books("region-mark", 5, "// end::brief[]", "");
        DocumentAssembly assembly = books.assembly();
        List<String> saved = new ArrayList<>();
        List<StoredBlock> blocks = new ArrayList<>();
        DocumentSurface body = onFx(() -> new DocumentSurface(new BlockFactory(Calculators.View.Default(), 13)));
        DocumentSurfaceView view = onFx(() -> {
            DocumentSurfaceView made = new DocumentSurfaceView(new Host() {
                @Override
                public void saveEdit(StoredBlock edited, String text) {
                    blocks.add(edited);
                    saved.add(text);
                }
            }, body);
            made.show(assembly);
            return made;
        });
        for (int showing = 0; showing < 2; showing++) {
            int nth = showing;
            assertTrue(onFx(() -> view.selectWords("a second reviewer", nth)), "the words are found in showing " + showing);
            assertTrue(onFx(view::canMark), "a mark can be written from either place");
            onFx(() -> {
                view.markSelection(InlineMarks.BOLD);
                return null;
            });
        }
        assertEquals(2, saved.size(), "each mark is one edit of the block");
        assertEquals(blocks.get(0).nid(), blocks.get(1).nid(), "the same block from the abstract and from the body");
        assertEquals(saved.get(0), saved.get(1), "and the same edit");
        assertEquals(BRIEF_ONE + "\nand a record of that decision that *a second reviewer* can check.\n// end::brief[]", saved.get(0),
                "the whole block is saved: the mark is in it, and the end tag line under the paragraph is kept");
    }

    @Test
    void aCommentOnALineShownTwiceIsRecordedOnceAndReadInBothPlaces() throws Exception {
        Books books = books("region-comment", 3, "// end::brief[]", "");
        DocumentAssembly before = books.assembly();
        DocumentAssembly.Member region = before.children().get(0);
        StoredLine line = region.shown().lines().getFirst();
        assertEquals(BRIEF_ONE, line.text());
        assertTrue(line.annotations().isEmpty());

        // the reader selects the abstract's first line: the annotation names the topic's file, its line 5, once
        int inAbstract = before.ordinal(region, line.nid());
        DocumentAssembly.Located located = before.locate(new LineSelection(inAbstract, inAbstract)).orElseThrow();
        assertEquals(books.topicFile(), located.member().path());
        List<String> committed = GitDocumentExporter.export(books.topicAnchor(), false).orElseThrow().lines();
        List<int[]> ranges = located.local().ranges(index -> index + 1);
        assertEquals(1, ranges.size());
        assertEquals(5, ranges.getFirst()[0]);
        assertEquals(5, ranges.getFirst()[1]);
        StampedDocumentCard.Proposal proposal = StampedDocumentCard.prepare(AnnotationKind.COMMENT, ranges, books.dir(), Path.of(books.topicFile()), committed);
        assertEquals(1, proposal.annotations().size(), "one annotation, of one range");
        assertTrue(proposal.trailers().contains(books.topicFile() + ":5-5"), proposal.trailers());
        assertFalse(proposal.trailers().contains(books.bookFile()), "the book's file is not named: the line is the topic's");
        AnnotationCommit.Result result = AnnotationCommit.write(books.dir(), proposal.annotations(), "Say who the second reviewer is.", false);
        assertTrue(result.ok(), result.output());
        GitDocumentMaterializer.materialize(books.dir(), books.dir().resolve(books.topicFile()), true);

        DocumentAssembly after = books.assembly();
        DocumentAssembly.Member regionAfter = after.children().get(0);
        DocumentAssembly.Member wholeAfter = after.children().get(1);
        StoredLine commented = regionAfter.shown().lines().getFirst();
        List<StoredAnnotation> inForce = commented.inForce();
        assertEquals(1, inForce.size(), "the line carries the comment once");
        assertEquals("Say who the second reviewer is.", inForce.getFirst().text());
        assertSame(commented, after.lines().get(after.ordinal(wholeAfter, commented.nid())), "the body's line is the same line, with the same comment");
        assertEquals(1, after.lines().stream().filter(l -> !l.inForce().isEmpty()).map(StoredLine::nid).distinct().count(), "one line of the assembly is commented");
        assertEquals(2, after.lines().stream().filter(l -> !l.inForce().isEmpty()).count(), "and it stands in two places");

        // the margin's note is read beside the line in the abstract and again beside it in the body
        DocumentSurface body = onFx(() -> new DocumentSurface(new BlockFactory(Calculators.View.Default(), 13)));
        DocumentSurfaceView view = onFx(() -> {
            DocumentSurfaceView made = new DocumentSurfaceView(host(), body);
            made.setLayer(Layer.STAMPS, false);
            made.show(after);
            return made;
        });
        List<Integer> blocks = onFx(() -> blocksReading(body, BRIEF));
        assertEquals(2, blocks.size());
        List<Integer> noted = onFx(() -> blocksWithNote(body, "Say who the second reviewer is."));
        assertEquals(blocks, noted, "one note beside the passage in each place it is shown");
        assertEquals(2, onFx(() -> noteTexts(body)).size(), "and no third");

        // a block is filled again when a layer is switched: each showing keeps its note
        onFx(() -> {
            view.setLayer(Layer.TINTS, true);
            return null;
        });
        assertEquals(blocks, onFx(() -> blocksWithNote(body, "Say who the second reviewer is.")));
        onFx(() -> {
            view.setLayer(Layer.COMMENTS, false);
            return null;
        });
        assertEquals(List.of(), onFx(() -> noteTexts(body)));

        // a note is written beside the place the reader selected: here the body's showing of the passage
        int inBody = after.ordinal(wholeAfter, commented.nid());
        onFx(() -> {
            view.select(new LineSelection(inBody, inBody));
            view.composeAnnotation(AnnotationKind.COMMENT, "Your comment", false, false, text -> { });
            return null;
        });
        List<Node> composers = onFx(() -> inStack(body, "#stampedDocumentNoteText"));
        assertEquals(1, composers.size());
        assertEquals(blocks.get(1), onFx(() -> blockHolding(body, composers.getFirst())), "beside the body's block, not the abstract's");
        assertNotEquals(blocks.get(0), blocks.get(1));
    }

    @Test
    void theTitlePagePrintsTheRegionAndTheBodyPrintsTheTopicFromPageOne() throws Exception {
        StringBuilder more = new StringBuilder("\n");
        for (int i = 0; i < 14; i++) {
            more.append(FILLER).append("\n\n");
        }
        Books books = books("region-pages", 4, "// end::brief[]", more.toString());
        DocumentAssembly assembly = books.assembly();
        StoredDocument book = assembly.host().document();
        DocumentSurface body = onFx(() -> new DocumentSurface(new BlockFactory(Calculators.View.Default(), 13)));
        DocumentSurfaceView surface = onFx(() -> {
            DocumentSurfaceView view = new DocumentSurfaceView(host(), body);
            view.show(assembly);
            return view;
        });

        // The Reading view: the title, the abstract, the lists beneath it, then the body with the passage again.
        List<String> reading = onFx(() -> IntStream.range(0, body.blockCount()).mapToObj(b -> body.block(b).plainText()).toList());
        List<Integer> shown = IntStream.range(0, reading.size()).filter(b -> reading.get(b).contains(BRIEF_ONE)).boxed().toList();
        assertEquals(2, shown.size(), "the passage is read twice: " + reading);
        int topic = indexOf(reading, "= The Proposal in Brief");
        assertTrue(indexOf(reading, "Applying Decision Theory") < shown.get(0) && shown.get(0) < topic && topic < shown.get(1),
                "title, abstract, then the topic's heading with the passage under it");
        int lists = onFx(() -> IntStream.range(0, body.blockCount()).filter(b -> body.block(b).node().lookup(".stamped-document-lists") != null).findFirst().orElse(-1));
        assertTrue(lists >= shown.get(0) && lists < topic, "the lists are drawn beneath the abstract and above the body: " + lists);

        // The print model and the surface's units.
        PrintSettings settings = PrintSettings.DEFAULT;
        DocumentModel model = StoredDocumentModel.of(book, StoredDocumentModel.children(assembly), onFx(surface::printing));
        DocumentModel.Abstract first = (DocumentModel.Abstract) model.blocks().getFirst();
        assertEquals(List.of(new DocumentModel.Paragraph(BRIEF)), ((DocumentModel.Container) first.blocks().getFirst()).blocks(),
                "the abstract holds the region's paragraph");
        List<LayoutOracle.Unit> units = onFx(() -> surface.pageUnits(settings.theme()));
        List<LayoutOracle.Unit> modelled = ModelUnits.of(model, settings.theme().baseFontSize());
        assertEquals(modelled.stream().map(u -> u.kind() + " " + u.block() + " " + u.pageBefore() + " " + u.opensBody() + " " + u.text()).toList(),
                units.stream().map(u -> u.kind() + " " + u.block() + " " + u.pageBefore() + " " + u.opensBody() + " " + u.text()).toList(),
                "the surface's units are the model's: the same kinds, blocks, opened pages, opened body and words");
        List<Integer> passage = units.stream().filter(u -> u.text().equals(BRIEF)).map(LayoutOracle.Unit::index).toList();
        assertEquals(2, passage.size(), "the passage is set twice");
        int contents = units.stream().filter(u -> u.text().equals("Contents")).findFirst().orElseThrow().index();
        int heading = units.stream().filter(u -> u.kind() == LayoutOracle.Kind.HEADING && u.text().equals("The Proposal in Brief")).findFirst().orElseThrow().index();
        assertEquals(contents, passage.get(0) + 1, "the contents follow the abstract");
        assertEquals(heading + 1, (int) passage.get(1), "the passage again, straight under the topic's heading");
        assertTrue(units.get(heading).pageBefore() && units.get(heading).opensBody(), "the topic's heading opens the body");
        assertFalse(units.get(passage.get(0)).pageBefore());
        Map<String, Integer> targets = onFx(surface::entryTargets);

        GreedyFxOracle greedy = onFx(() -> new GreedyFxOracle(DocumentFonts.SERIF, DocumentFonts.MONO));
        LayoutOracle.Layout estimate = onFx(() -> greedy.layout(units, GEOMETRY));

        Assumptions.assumeTrue(TypstCompiler.locate().isPresent(), "typst is not installed");
        Path folder = Files.createDirectories(root.resolve("region-pages-typst"));
        Path fonts = DocumentFonts.extractTo(folder.resolve("fonts"));
        Path measured = folder.resolve("region-pages.typ");
        TypstWriter.PageSetup setup = TypstPdf.setup(settings);
        FrontLists.Settled settled = FrontLists.settle(model, FrontLists.pages(estimate, targets), targets, numbered -> {
            Files.writeString(measured, TypstWriter.write(numbered, setup, TypstWriter.Labels.WORDS), StandardCharsets.UTF_8);
            return new TypstOracle(folder, measured, fonts, (words, unit, mono, bold, italic) -> supplyOnFx(() -> greedy.width(words, unit, mono, bold, italic)))
                    .layout(units, GEOMETRY);
        });
        LayoutOracle.Layout layout = settled.layout();
        assertEquals(List.of(), layout.notes());

        for (LayoutOracle.Layout each : List.of(estimate, layout)) {
            assertEquals(0, each.pageOf(0), "the title");
            assertEquals(List.of(0), each.ofUnit(passage.get(0)).stream().map(LayoutOracle.Line::page).distinct().toList(), "the abstract is whole on the title page");
            assertEquals(1, each.pageOf(contents), "the contents on the page after it");
            assertEquals(2, each.pageOf(heading), "the topic opens the third sheet");
            assertEquals(2, each.pageOf(passage.get(1)), "with the passage under its heading");
            assertEquals(new Folios(2, true), each.folios(), "which is page 1");
        }
        assertEquals("1", layout.folios().label(layout.pageOf(passage.get(1))), "the passage is read again on page 1");
        assertEquals("", layout.folios().label(layout.pageOf(passage.get(0))), "and first on the title page, which prints no number");

        String check = onFx(() -> {
            surface.beginLineCheck(GEOMETRY.contentLeft());
            surface.layoutLines(layout, units);
            return surface.lineCheck();
        });
        assertTrue(check.contains(" 0 drawing other text"), check);
        RunningHeads heads = RunningHeads.of(units, layout.lines());
        List<String> sheets = onFx(() -> sheetFolios(surface, settings, layout, heads, model.title()));
        List<String> expected = new ArrayList<>(List.of("", "ii"));
        IntStream.range(2, layout.pages()).forEach(p -> expected.add(Integer.toString(p - 1)));
        assertTrue(expected.size() >= 4, "the body runs over pages: " + expected);
        assertEquals(expected, sheets, "no number on the title page, the contents on ii, the body from 1");

        // the sheets draw the passage on the title page and on page 1
        List<Integer> drawnOn = onFx(() -> IntStream.range(0, layout.pages())
                .filter(p -> surface.linesOn(p, GEOMETRY.contentLeft(), GEOMETRY.contentWidth(), GEOMETRY.bandHeight()).stream()
                        .anyMatch(placed -> placed.node() instanceof RichTextArea rta && text(rta).startsWith("We propose a way"))).boxed().toList());
        assertEquals(List.of(0, 2), drawnOn, "the first line of the passage is drawn on the title page and on the body's first page");

        // The printed document: the same pages, the passage twice in its source, no tag line in it.
        String perturbation = PageFidelity.perturbation(settled.model(), settings, folder);
        assertTrue(perturbation.startsWith("label perturbation: 0 of "), perturbation);
        TypstPdf.Printed pdf = TypstPdf.print(model, settings, folder, "region-pages");
        assertTrue(pdf.result().ok(), pdf.report());
        assertTrue(Files.size(pdf.pdf()) > 0);
        String source = Files.readString(pdf.source());
        assertEquals(2, source.split(java.util.regex.Pattern.quote(BRIEF), -1).length - 1, "the passage is printed twice");
        assertFalse(source.contains("tag::") || source.contains("end::") || source.contains("include::"), "no tag line and no include line is printed");
        int title = source.indexOf("#ike-title(\"");
        int once = source.indexOf(BRIEF);
        assertTrue(title >= 0 && title < once && once < source.indexOf(TypstWriter.PAGE_BREAK, title), "first on the title page, before any page break");
        assertTrue(source.indexOf(BRIEF, once + 1) > source.indexOf(TypstWriter.BODY_START), "and again after the body opens");
        TypstCompiler.Result folios = TypstCompiler.evaluate(folder, pdf.source(), "query(<" + TypstWriter.FOLIO_LABEL + ">).map(it => it.value)", fonts);
        assertTrue(folios.ok(), folios.output());
        assertEquals(expected, strings(folios.output()), "the PDF prints the same folio on every page");
        TypstCompiler.Result opened = TypstCompiler.locate(folder, pdf.source(), TypstWriter.BODY_LABEL, fonts);
        assertTrue(opened.ok(), opened.output());
        assertEquals(3, PageFidelity.parse(opened.output()).getFirst().page(), "the PDF's body begins on its third page");
        Path labelled = folder.resolve("labelled.typ");
        Files.writeString(labelled, TypstWriter.write(model, setup, TypstWriter.Labels.BLOCKS), StandardCharsets.UTF_8);
        TypstCompiler.Result end = TypstCompiler.locate(folder, labelled, TypstWriter.END_LABEL, fonts);
        assertTrue(end.ok(), end.output());
        assertEquals(layout.pages(), PageFidelity.parse(end.output()).getFirst().page(), "the printed document's page count is the pages'");
        assertEquals(1, settled.pages().get(model.entries().getFirst().target()), "the contents send the reader to page 1 for The Proposal in Brief");
        assertEquals("The Proposal in Brief", model.entries().getFirst().text());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String text(RichTextArea rta) {
        StringBuilder text = new StringBuilder();
        for (int p = 0; p < rta.getParagraphCount(); p++) {
            text.append(rta.getPlainText(p));
        }
        return text.toString();
    }

    /** The Reading view's blocks whose text holds the given words, in order. */
    private static List<Integer> blocksReading(DocumentSurface body, String words) {
        return IntStream.range(0, body.blockCount()).filter(b -> body.block(b).plainText().replace('\n', ' ').contains(words)).boxed().toList();
    }

    /** Whether a block's text draws a selection. */
    private static boolean selected(DocumentSurface body, int block) {
        RichTextArea rta = body.block(block).richText();
        SelectionSegment selection = rta == null ? null : rta.getSelection();
        return selection != null && !selection.isCollapsed();
    }

    private static List<Node> inStack(DocumentSurface body, String selector) {
        List<Node> found = new ArrayList<>();
        for (int b = 0; b < body.blockCount(); b++) {
            found.addAll(body.block(b).stackNode().lookupAll(selector));
        }
        return found;
    }

    private static int blockHolding(DocumentSurface body, Node node) {
        for (int b = 0; b < body.blockCount(); b++) {
            for (Node at = node; at != null; at = at.getParent()) {
                if (at == body.block(b).stackNode()) {
                    return b;
                }
            }
        }
        throw new AssertionError("the node is in no block's row");
    }

    private static List<String> noteTexts(DocumentSurface body) {
        List<String> texts = new ArrayList<>();
        for (Node note : inStack(body, ".stamped-document-note")) {
            note.lookupAll(".label").stream().map(javafx.scene.control.Label.class::cast).map(javafx.scene.control.Label::getText)
                    .filter(text -> !text.matches("\\d{4}-\\d{2}-\\d{2}")).forEach(texts::add);
        }
        return texts;
    }

    /** The blocks whose margin holds a note of the given words, in order. */
    private static List<Integer> blocksWithNote(DocumentSurface body, String words) {
        List<Integer> blocks = new ArrayList<>();
        for (int b = 0; b < body.blockCount(); b++) {
            for (Node note : body.block(b).stackNode().lookupAll(".stamped-document-note")) {
                if (note.lookupAll(".label").stream().map(javafx.scene.control.Label.class::cast).anyMatch(label -> words.equals(label.getText()))) {
                    blocks.add(b);
                }
            }
        }
        return blocks;
    }

    private static int indexOf(List<String> texts, String part) {
        for (int i = 0; i < texts.size(); i++) {
            if (texts.get(i).contains(part)) {
                return i;
            }
        }
        throw new AssertionError("no block reads: " + part + " in " + texts);
    }

    /** The folio each sheet of the Page layout draws, in page order: a page stack shown in a scene with the surface's own lines, as the card shows it. */
    private static List<String> sheetFolios(DocumentSurfaceView surface, PrintSettings settings, LayoutOracle.Layout layout, RunningHeads heads, String title) {
        PageStackView stack = new PageStackView(settings, GEOMETRY.pageWidth(), GEOMETRY.pageHeight(), title);
        Scene scene = new Scene(stack, 700, 900);
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
            List<String> drawn = ((Pane) sheet).getChildren().stream().filter(n -> n.getStyleClass().contains("stamped-page-folio"))
                    .map(n -> ((javafx.scene.control.Label) n).getText()).toList();
            assertTrue(drawn.size() <= 1, "one folio a sheet at most: " + drawn);
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

    /** Lets the window's pulses lay the view out and draw the selection. */
    private static void pulses() throws Exception {
        for (int i = 0; i < 12; i++) {
            Thread.sleep(60);
            onFx(() -> null);
        }
    }

    private static DocumentSurfaceView.Host host() {
        return new Host();
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

    private static double supplyOnFx(Callable<Double> work) {
        try {
            return onFx(work);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

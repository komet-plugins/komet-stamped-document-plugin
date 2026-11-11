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

import design.knowledge.stamp.adoc.dialect.Block;
import design.knowledge.stamp.adoc.model.AnnotationKind;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.StampEntity;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.TreeItem;
import javafx.scene.image.Image;
import javafx.scene.layout.StackPane;
import network.ike.komet.document.DocumentGraph.Kind;
import network.ike.komet.document.DocumentGraph.Node;
import network.ike.komet.document.DocumentGraphView.NodeRow;
import network.ike.komet.document.DocumentGraphView.PropertyRow;
import network.ike.komet.document.DocumentGraphView.EdgeRow;
import network.ike.komet.document.DocumentGraphView.Row;
import network.ike.komet.document.DocumentSurfaceView.Layer;
import network.ike.komet.document.StoredNodeFacts.NodeFacts;
import network.ike.komet.document.StoredNodeFacts.Property;
import dev.ikm.tinkar.common.id.PublicIds;
import java.util.UUID;
import java.util.function.Function;
import network.ike.komet.document.GitDocumentMaterializer.StoredDocument;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The Graph view's tree and its selection bridge, headless: rows select the document's lines,
 * and the document's selection is shown as rows.
 */
class DocumentGraphViewTest {

    /** The card as the graph sees it: a selection it keeps, the layers all on, every act recorded. */
    private static final class Card implements DocumentGraphView.Host {
        LineSelection selection;
        DocumentHistory history = DocumentHistory.NONE;
        Function<Node, Optional<NodeFacts>> facts = node -> Optional.empty();
        final List<String> acts = new ArrayList<>();
        final List<DocumentAssembly.Member> shown = new ArrayList<>();
        final List<Long> highlighted = new ArrayList<>();
        final List<String> statuses = new ArrayList<>();

        @Override
        public ViewCalculator view() {
            return null;
        }

        @Override
        public CommitSummaries summaries() {
            return CommitSummaries.NONE;
        }

        @Override
        public Optional<NodeFacts> facts(Node node) {
            return facts.apply(node);
        }

        @Override
        public DocumentHistory history(DocumentAssembly.Member member) {
            return history;
        }

        @Override
        public void highlightCommit(long stampNid) {
            highlighted.add(stampNid);
        }

        @Override
        public void status(String text) {
            statuses.add(text);
        }

        @Override
        public Image identicon(StampEntity<?> stamp) {
            return null;
        }

        @Override
        public boolean layer(Layer layer) {
            return true;
        }

        @Override
        public Optional<LineSelection> selection() {
            return Optional.ofNullable(selection);
        }

        @Override
        public void select(LineSelection wanted) {
            selection = wanted;
            acts.add("select " + wanted.min() + ".." + wanted.max());
        }

        @Override
        public void clearSelection() {
            selection = null;
            acts.add("clear");
        }

        @Override
        public void annotate(AnnotationKind kind) {
            acts.add("annotate " + kind);
        }

        @Override
        public void showHistory() {
            acts.add("history");
        }

        @Override
        public void editSelectedBlock() {
            acts.add("edit");
        }

        @Override
        public void editSentence(long sentence, String text) {
            acts.add("edit sentence " + sentence);
        }

        @Override
        public void showingMember(DocumentAssembly.Member member) {
            shown.add(member);
        }
    }

    private static final String TEXT = """
            = The Title

            == One

            First claim.
            Second claim.

            == Two

            Third claim.
            """;

    @BeforeAll
    static void startJavaFx() {
        try {
            Class.forName("com.sun.glass.ui.monocle.MonoclePlatformFactory");
            System.setProperty("glass.platform", "Monocle");
            System.setProperty("monocle.platform", "Headless");
            System.setProperty("prism.order", "sw");
            System.setProperty("prism.text", "t2k");
            System.setProperty("java.awt.headless", "true");
        } catch (ClassNotFoundException ignored) {
            // the default toolkit
        }
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException alreadyRunning) {
            // started by another test
        }
    }

    /** Runs on the JavaFX thread and waits, rethrowing what failed there. */
    private static void onFx(Runnable body) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                failed.set(t);
            } finally {
                done.countDown();
            }
        });
        if (!done.await(30, TimeUnit.SECONDS)) {
            fail("the JavaFX thread did not answer");
        }
        if (failed.get() instanceof Exception e) {
            throw e;
        }
        if (failed.get() instanceof Error e) {
            throw e;
        }
    }

    private static TreeItem<Row> find(TreeItem<Row> item, Kind kind, String label) {
        if (!(item.getValue() instanceof NodeRow)) {
            return null;
        }
        if (item.getValue().node().kind() == kind && item.getValue().node().label().equals(label)) {
            return item;
        }
        for (TreeItem<Row> child : item.getChildren()) {
            TreeItem<Row> found = find(child, kind, label);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static TreeItem<Row> firstOf(TreeItem<Row> item, Block.Kind kind) {
        if (!(item.getValue() instanceof NodeRow)) {
            return null;
        }
        if (item.getValue().node().blockKind() == kind) {
            return item;
        }
        for (TreeItem<Row> child : item.getChildren()) {
            TreeItem<Row> found = firstOf(child, kind);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static String rowName(TreeItem<Row> item) {
        return switch (item.getValue()) {
            case NodeRow n -> n.edge();
            case PropertyRow p -> p.property().key();
            case EdgeRow e -> e.name();
        };
    }

    private static List<TreeItem<Row>> nodeChildren(TreeItem<Row> item) {
        return item.getChildren().stream().filter(i -> i.getValue() instanceof NodeRow).toList();
    }

    /** A verbatim body keeps its lines, blank ones included, and says how many it leaves out. */
    @Test
    void aVerbatimBodyKeepsItsLines() {
        assertEquals("Review of the attribution section\n\nThe second passage", DocumentGraphView.verbatim(
                "Review of the attribution section\n\nThe second passage"));
        StringBuilder long_ = new StringBuilder();
        for (int i = 1; i <= DocumentGraphView.VERBATIM_LINES + 3; i++) {
            long_.append("line ").append(i).append('\n');
        }
        String shown = DocumentGraphView.verbatim(long_.toString());
        assertEquals(DocumentGraphView.VERBATIM_LINES + 1, shown.lines().count());
        assertTrue(shown.endsWith("… 3 more lines"), shown);
    }

    @Test
    void theStoresFactsBecomeTheNodeAndItsPropertyAndEdgeRows() throws Exception {
        StoredDocument document = DocumentGraphTest.stored(TEXT);
        DocumentAssembly assembly = DocumentAssembly.single(document, "docs/one.adoc");
        long first = document.lines().get(4).nid();
        Card card = new Card();
        card.facts = node -> node.nid() == first
                ? Optional.of(new NodeFacts(first, PublicIds.of(UUID.randomUUID()), "S", "Prose element pattern", "Prose element",
                        List.of(new Property("Prose content", "First claim.", 0)), 42L, "The document"))
                : node.block() != null && node.blockKind() == Block.Kind.PARAGRAPH
                ? Optional.of(new NodeFacts(node.nid(), PublicIds.of(UUID.randomUUID()), "S", "Document block pattern", "Document element",
                        List.of(new Property("Document element kind", "Document paragraph", 7L), new Property("Document element level", "0", 0),
                                new Property("Document element title", "", 0), new Property("Document element members", "2 components", 0)),
                        42L, "The document"))
                : Optional.empty();
        onFx(() -> {
            DocumentGraphView graph = new DocumentGraphView(card);
            graph.show(assembly);
            TreeItem<Row> paragraph = firstOf(graph.tree().getRoot(), Block.Kind.PARAGRAPH);
            assertEquals(List.of("Document element kind", "Document element level", "Document element title", "Document element members",
                    "referenced component", "member of", "member", "member"), paragraph.getChildren().stream().map(DocumentGraphViewTest::rowName).toList(),
                    "properties as the store keys them, the upward edges, then the member nodes");
            TreeItem<Row> line = nodeChildren(paragraph).getFirst();
            assertEquals(List.of("Prose content", "referenced component", "member of"), line.getChildren().stream().map(DocumentGraphViewTest::rowName).toList());
            EdgeRow memberOf = (EdgeRow) line.getChildren().get(2).getValue();
            assertEquals("Document element · Document paragraph", memberOf.target(), "the parent's meaning, refined by its kind");
            assertEquals(paragraph.getValue().node().nid(), memberOf.targetNid());

            paragraph.setExpanded(true);
            line.setExpanded(true);
            TreeItem<Row> text = line.getChildren().getFirst();
            graph.tree().getSelectionModel().clearAndSelect(graph.tree().getRow(text));
            assertEquals(new LineSelection(line.getValue().node().first(), line.getValue().node().first()), card.selection,
                    "a property row stands for its node");
        });
    }

    @Test
    void sectionsOpenExpandedAndBlocksCollapsed() throws Exception {
        StoredDocument document = DocumentGraphTest.stored(TEXT);
        DocumentAssembly assembly = DocumentAssembly.single(document, "docs/one.adoc");
        Card card = new Card();
        onFx(() -> {
            DocumentGraphView graph = new DocumentGraphView(card);
            graph.show(assembly);
            TreeItem<Row> root = graph.tree().getRoot();
            assertNotNull(root);
            assertTrue(root.isExpanded(), "the document opens expanded");
            TreeItem<Row> one = find(root, Kind.BLOCK, "One");
            assertNotNull(one);
            assertTrue(one.isExpanded(), "a section opens expanded");
            TreeItem<Row> paragraph = firstOf(one, Block.Kind.PARAGRAPH);
            assertNotNull(paragraph);
            assertFalse(paragraph.isExpanded(), "a block opens collapsed");
            assertEquals(List.of("member of", "member", "member"), paragraph.getChildren().stream().map(DocumentGraphViewTest::rowName).toList(),
                    "its upward edge, then its lines as member rows");
            assertTrue(card.acts.isEmpty(), "showing selects nothing");
        });
    }

    @Test
    void selectingRowsSelectsTheLinesTheySpan() throws Exception {
        StoredDocument document = DocumentGraphTest.stored(TEXT);
        DocumentAssembly assembly = DocumentAssembly.single(document, "docs/one.adoc");
        Card card = new Card();
        onFx(() -> {
            DocumentGraphView graph = new DocumentGraphView(card);
            graph.show(assembly);
            TreeItem<Row> paragraph = firstOf(graph.tree().getRoot(), Block.Kind.PARAGRAPH);
            Node node = paragraph.getValue().node();

            graph.tree().getSelectionModel().select(paragraph);
            assertEquals(List.of("select " + node.first() + ".." + node.last()), card.acts, "the block's lines become the selection");
            assertEquals(new LineSelection(node.first(), node.last()), card.selection);
            assertSame(assembly.host(), card.shown.getLast(), "the card learns whose member it is");

            TreeItem<Row> two = find(graph.tree().getRoot(), Kind.BLOCK, "Two");
            graph.tree().getSelectionModel().clearAndSelect(graph.tree().getRow(two));
            assertEquals(two.getValue().node().first(), card.selection.min());
            assertEquals(two.getValue().node().last(), card.selection.max());

            graph.tree().getSelectionModel().clearSelection();
            assertEquals("clear", card.acts.getLast(), "no rows, no selection");
            assertNull(card.selection);
        });
    }

    @Test
    void theDocumentsSelectionIsShownAsRows() throws Exception {
        StoredDocument document = DocumentGraphTest.stored(TEXT);
        DocumentAssembly assembly = DocumentAssembly.single(document, "docs/one.adoc");
        Card card = new Card();
        onFx(() -> {
            DocumentGraphView graph = new DocumentGraphView(card);
            new Scene(new StackPane(graph.node()), 400, 600);
            graph.show(assembly);
            TreeItem<Row> paragraph = firstOf(graph.tree().getRoot(), Block.Kind.PARAGRAPH);
            TreeItem<Row> second = nodeChildren(paragraph).get(1);
            int ordinal = second.getValue().node().first();

            card.selection = new LineSelection(ordinal, ordinal);
            graph.showSelection();
            assertEquals(List.of(second), List.copyOf(graph.tree().getSelectionModel().getSelectedItems()), "the one line's row");
            assertTrue(paragraph.isExpanded(), "its block opened to show it");
            assertTrue(card.acts.isEmpty(), "showing the selection does not re-select it");

            card.selection = new LineSelection(paragraph.getValue().node().first(), paragraph.getValue().node().last());
            graph.showSelection();
            assertEquals(List.of(paragraph), List.copyOf(graph.tree().getSelectionModel().getSelectedItems()),
                    "a selection that is exactly a block's lines selects the block");

            TreeItem<Row> one = find(graph.tree().getRoot(), Kind.BLOCK, "One");
            card.selection = new LineSelection(one.getValue().node().first(), paragraph.getValue().node().first());
            graph.showSelection();
            List<TreeItem<Row>> selected = List.copyOf(graph.tree().getSelectionModel().getSelectedItems());
            assertTrue(selected.stream().allMatch(i -> i.getValue().node().kind() == Kind.LINE), "a selection that is no node's lines selects the lines");
            // The blank line between the heading and the paragraph belongs to no block, so it has no row.
            assertEquals(List.of(one.getValue().node().first(), paragraph.getValue().node().first()),
                    selected.stream().map(i -> i.getValue().node().first()).toList(), "the heading's row and the paragraph's first line's");

            card.selection = null;
            graph.showSelection();
            assertTrue(graph.tree().getSelectionModel().getSelectedItems().isEmpty());
            assertTrue(card.acts.isEmpty());

            graph.show(null);
            assertNull(graph.tree().getRoot());
            assertTrue(graph.root().isEmpty());
        });
    }

    @Test
    void historyModeShowsVersionsAndRetiredLinesAndSelectsThroughThem() throws Exception {
        StoredDocument document = DocumentGraphTest.stored(TEXT);
        DocumentAssembly assembly = DocumentAssembly.single(document, "docs/one.adoc");
        long first = document.lines().get(4).nid();
        long second = document.lines().get(5).nid();
        long gone = 777_777L;
        Card card = new Card();
        card.history = DocumentHistory.from(
                List.of(List.of(first, gone, second), List.of(first, second)),
                Map.of(first, List.of(new DocumentHistory.Raw(null, "First claim, as written.", true), new DocumentHistory.Raw(null, "First claim.", true)),
                        gone, List.of(new DocumentHistory.Raw(null, "Withdrawn.", true), new DocumentHistory.Raw(null, "Withdrawn.", false)),
                        second, List.of(new DocumentHistory.Raw(null, "Second claim.", true))));
        onFx(() -> {
            DocumentGraphView graph = new DocumentGraphView(card);
            new Scene(new StackPane(graph.node()), 400, 600);
            graph.show(assembly);
            TreeItem<Row> paragraph = firstOf(graph.tree().getRoot(), Block.Kind.PARAGRAPH);
            assertEquals(2, nodeChildren(paragraph).size(), "Latest: the two lines");

            graph.setMode(DocumentGraphView.Mode.HISTORY);
            assertEquals(DocumentGraphView.Mode.HISTORY, graph.mode());
            paragraph = firstOf(graph.tree().getRoot(), Block.Kind.PARAGRAPH);
            assertEquals(List.of(Kind.LINE, Kind.RETIRED, Kind.LINE), nodeChildren(paragraph).stream().map(i -> i.getValue().node().kind()).toList());
            TreeItem<Row> firstLine = nodeChildren(paragraph).get(0);
            assertEquals(2, nodeChildren(firstLine).size(), "its versions beneath it");
            assertEquals(List.of("version", "version"), nodeChildren(firstLine).stream().map(i -> ((NodeRow) i.getValue()).edge()).toList());
            assertFalse(firstLine.isExpanded(), "a line opens collapsed");

            paragraph.setExpanded(true);
            firstLine.setExpanded(true);
            TreeItem<Row> version = nodeChildren(firstLine).get(0);
            assertEquals("text", ((PropertyRow) version.getChildren().getFirst().getValue()).property().key(),
                    "no facts: a version's text sits under a plain key");
            graph.tree().getSelectionModel().clearAndSelect(graph.tree().getRow(version));
            assertEquals(new LineSelection(firstLine.getValue().node().first(), firstLine.getValue().node().first()), card.selection, "a version selects its line");

            TreeItem<Row> retired = nodeChildren(paragraph).get(1);
            graph.tree().getSelectionModel().clearAndSelect(graph.tree().getRow(retired));
            assertEquals(new LineSelection(firstLine.getValue().node().first(), firstLine.getValue().node().first()), card.selection,
                    "a retired line alone leaves the document's selection as it was");
            assertTrue(card.statuses.isEmpty(), "no STAMP in the fixture, so nothing to say about when");

            card.selection = new LineSelection(firstLine.getValue().node().first(), firstLine.getValue().node().first());
            graph.showSelection();
            assertEquals(List.of(firstLine), List.copyOf(graph.tree().getSelectionModel().getSelectedItems()),
                    "the document's selection is shown as the line, not one of its versions");

            graph.setMode(DocumentGraphView.Mode.LATEST);
            paragraph = firstOf(graph.tree().getRoot(), Block.Kind.PARAGRAPH);
            assertEquals(2, nodeChildren(paragraph).size(), "back to the lines as they stand");
            assertTrue(nodeChildren(nodeChildren(paragraph).getFirst()).isEmpty());
        });
    }

    @Test
    void docBookModeShowsElementsWithTheirAttributesAndSelectsTheirLines() throws Exception {
        StoredDocument document = DocumentGraphTest.stored("""
                = The Title

                [[intro]]
                == One

                A *strong* claim
                over two lines.

                .Config
                [source,yaml]
                ----
                key: value
                ----
                """);
        DocumentAssembly assembly = DocumentAssembly.single(document, "docs/one.adoc");
        Card card = new Card();
        onFx(() -> {
            DocumentGraphView graph = new DocumentGraphView(card);
            new Scene(new StackPane(graph.node()), 400, 600);
            graph.setMode(DocumentGraphView.Mode.DOCBOOK);
            graph.show(assembly);
            assertEquals(DocumentGraphView.Mode.DOCBOOK, graph.mode());

            TreeItem<Row> section = firstOf(graph.tree().getRoot(), Block.Kind.SECTION);
            assertEquals("id", rowName(section.getChildren().getFirst()), "an element's attributes are its first rows");
            assertEquals("intro", ((PropertyRow) section.getChildren().getFirst().getValue()).property().value());
            assertTrue(section.getChildren().stream().noneMatch(i -> i.getValue() instanceof EdgeRow), "no upward edges in a structure view");

            TreeItem<Row> listing = firstOf(graph.tree().getRoot(), Block.Kind.LISTING);
            assertEquals(List.of("style", "language"), listing.getChildren().stream().filter(i -> i.getValue() instanceof PropertyRow).map(DocumentGraphViewTest::rowName).toList());
            assertEquals(List.of(Kind.ELEMENT, Kind.TEXT), nodeChildren(listing).stream().map(i -> i.getValue().node().kind()).toList(),
                    "its title and its body; no delimiter, no attribute line");

            TreeItem<Row> paragraph = firstOf(graph.tree().getRoot(), Block.Kind.PARAGRAPH);
            assertTrue(paragraph.isExpanded(), "blocks open, so the text shows");
            assertEquals(List.of(Kind.TEXT, Kind.INLINE, Kind.TEXT), nodeChildren(paragraph).stream().map(i -> i.getValue().node().kind()).toList());
            TreeItem<Row> strong = nodeChildren(paragraph).get(1);
            int line = strong.getValue().node().first();
            graph.tree().getSelectionModel().clearAndSelect(graph.tree().getRow(strong));
            assertEquals(new LineSelection(line, line), card.selection, "an inline element selects the line it came from");
            TreeItem<Row> rest = nodeChildren(paragraph).get(2);
            graph.tree().getSelectionModel().clearAndSelect(graph.tree().getRow(rest));
            assertEquals(new LineSelection(line, line + 1), card.selection, "a run over a line break selects both lines");

            card.selection = new LineSelection(line + 1, line + 1);
            graph.showSelection();
            assertEquals(List.of(rest), List.copyOf(graph.tree().getSelectionModel().getSelectedItems()),
                    "a line selected elsewhere shows as the text made of it");
            card.selection = new LineSelection(line, line);
            graph.showSelection();
            assertEquals(List.of(nodeChildren(paragraph).get(0), strong, rest), List.copyOf(graph.tree().getSelectionModel().getSelectedItems()),
                    "every node on the line");
            int attributeLine = listing.getValue().node().first() + 1;
            card.selection = new LineSelection(attributeLine, attributeLine);
            graph.showSelection();
            assertEquals(List.of(listing), List.copyOf(graph.tree().getSelectionModel().getSelectedItems()), "a folded markup line shows as its block");

            graph.setMode(DocumentGraphView.Mode.LATEST);
            paragraph = firstOf(graph.tree().getRoot(), Block.Kind.PARAGRAPH);
            assertEquals(List.of(Kind.LINE, Kind.LINE), nodeChildren(paragraph).stream().map(i -> i.getValue().node().kind()).toList(), "back to the lines");
        });
    }

    @Test
    void relayerRedrawsWithoutLosingTheSelection() throws Exception {
        StoredDocument document = DocumentGraphTest.stored(TEXT);
        DocumentAssembly assembly = DocumentAssembly.single(document, "docs/one.adoc");
        Card card = new Card();
        onFx(() -> {
            DocumentGraphView graph = new DocumentGraphView(card);
            new Scene(new StackPane(graph.node()), 400, 600);
            graph.show(assembly);
            TreeItem<Row> one = find(graph.tree().getRoot(), Kind.BLOCK, "One");
            graph.tree().getSelectionModel().select(one);
            graph.relayer();
            assertEquals(List.of(one), List.copyOf(graph.tree().getSelectionModel().getSelectedItems()));
        });
    }

    // ── one passage shown in two places (dev-document-front-matter, the third step) ──

    private static final String SUMMARY = """
            = The Proposal in Brief

            // tag::brief[]
            We propose a way for a reviewer to decide,
            and a record of that decision.

            // end::brief[]

            == The problem

            A reviewer decides alone.
            """;

    private static final String BOOK = """
            = Applying Decision Theory

            [abstract]
            --
            include::topics/summary.adoc[tag=brief]
            --

            include::topics/summary.adoc[leveloffset=+1]
            """;

    private static DocumentAssembly shownTwice(StoredDocument summary) {
        return DocumentAssembly.of(DocumentGraphTest.stored(BOOK), "docs/book.adoc",
                path -> path.equals("docs/topics/summary.adoc") ? Optional.of(summary) : Optional.empty());
    }

    private static void collect(TreeItem<Row> item, List<TreeItem<Row>> into) {
        into.add(item);
        for (TreeItem<Row> child : item.getChildren()) {
            collect(child, into);
        }
    }

    /** The node rows of a line, in tree order: one for each place the line is shown. */
    private static List<TreeItem<Row>> rowsOf(DocumentGraphView graph, long line) {
        List<TreeItem<Row>> every = new ArrayList<>();
        collect(graph.tree().getRoot(), every);
        return every.stream().filter(i -> i.getValue() instanceof NodeRow row && row.node().kind() == Kind.LINE && row.node().nid() == line).toList();
    }

    @Test
    void aSelectionInOneShowingOfAPassageIsShownOnThatShowingsRow() throws Exception {
        StoredDocument summary = DocumentGraphTest.stored(SUMMARY);
        DocumentAssembly assembly = shownTwice(summary);
        DocumentAssembly.Member abstractShowing = assembly.children().get(0);
        DocumentAssembly.Member bodyShowing = assembly.children().get(1);
        long line = summary.lines().get(3).nid();                   // "We propose a way for a reviewer to decide,"
        int inAbstract = assembly.ordinal(abstractShowing, line);
        int inBody = assembly.ordinal(bodyShowing, line);
        Card card = new Card();
        onFx(() -> {
            DocumentGraphView graph = new DocumentGraphView(card);
            card.selection = new LineSelection(inBody, inBody);
            graph.show(assembly);
            List<TreeItem<Row>> rows = rowsOf(graph, line);
            assertEquals(2, rows.size(), "the line has a row in the abstract's tree and a row in the body's");
            assertSame(abstractShowing, rows.get(0).getValue().node().member());
            assertSame(bodyShowing, rows.get(1).getValue().node().member());
            assertEquals(List.of(rows.get(1)), List.copyOf(graph.tree().getSelectionModel().getSelectedItems()),
                    "a selection made in the body is shown on the body's row, not the abstract's");

            card.selection = new LineSelection(inAbstract, inAbstract);
            graph.showSelection();
            assertEquals(List.of(rows.get(0)), List.copyOf(graph.tree().getSelectionModel().getSelectedItems()),
                    "and one made in the abstract on the abstract's row");
            assertTrue(card.acts.isEmpty(), "showing a selection makes none");

            graph.setMode(DocumentGraphView.Mode.DOCBOOK);
            List<DocumentAssembly.Member> selected = graph.tree().getSelectionModel().getSelectedItems().stream()
                    .map(i -> i.getValue().node().member()).distinct().toList();
            assertEquals(List.of(abstractShowing), selected, "as elements too, the rows selected are the abstract's");
        });
    }

    @Test
    void selectingARowOfAPassageShownTwiceSelectsThePlaceThatRowStandsIn() throws Exception {
        StoredDocument summary = DocumentGraphTest.stored(SUMMARY);
        DocumentAssembly assembly = shownTwice(summary);
        DocumentAssembly.Member abstractShowing = assembly.children().get(0);
        DocumentAssembly.Member bodyShowing = assembly.children().get(1);
        long line = summary.lines().get(3).nid();
        int inAbstract = assembly.ordinal(abstractShowing, line);
        int inBody = assembly.ordinal(bodyShowing, line);
        Card card = new Card();
        onFx(() -> {
            DocumentGraphView graph = new DocumentGraphView(card);
            graph.show(assembly);
            List<TreeItem<Row>> rows = rowsOf(graph, line);
            for (TreeItem<Row> row : rows) {
                for (TreeItem<Row> parent = row.getParent(); parent != null; parent = parent.getParent()) {
                    parent.setExpanded(true);
                }
            }
            graph.tree().getSelectionModel().clearAndSelect(graph.tree().getRow(rows.get(0)));
            assertEquals(new LineSelection(inAbstract, inAbstract), card.selection, "the abstract's row selects the line in the abstract");
            assertSame(abstractShowing, card.shown.getLast());

            graph.tree().getSelectionModel().clearAndSelect(graph.tree().getRow(rows.get(1)));
            assertEquals(new LineSelection(inBody, inBody), card.selection, "the body's row selects it in the body");
            assertSame(bodyShowing, card.shown.getLast());

            // the document shown by region: its row selects the region's lines where the abstract shows them
            TreeItem<Row> region = rows.get(0);
            while (region.getValue().node().kind() != Kind.DOCUMENT) {
                region = region.getParent();
            }
            assertEquals("tag=brief", region.getValue().node().member().tags());
            graph.tree().getSelectionModel().clearAndSelect(graph.tree().getRow(region));
            assertEquals(new LineSelection(inAbstract, inAbstract + 1), card.selection, "the two lines of the paragraph the region reads, in the abstract");
        });
    }

    // ── conditional directives: Elements mode is the document as it reads ──

    @Test
    void aLineAConditionalLeavesOutHasItsRowInLatestModeAndNoneInElementsMode() throws Exception {
        StoredDocument document = DocumentGraphTest.stored("""
                = A Topic

                Before.

                ifdef::compendium[]
                Only in the compendium.
                endif::[]

                After.
                """);
        DocumentAssembly assembly = DocumentAssembly.of(document, "docs/topic.adoc", path -> Optional.empty());
        long left = document.lines().get(5).nid();                  // "Only in the compendium."
        long read = document.lines().get(8).nid();                  // "After."
        int leftAt = assembly.ordinal(assembly.host(), left);
        int readAt = assembly.ordinal(assembly.host(), read);
        Card card = new Card();
        onFx(() -> {
            DocumentGraphView graph = new DocumentGraphView(card);
            card.selection = new LineSelection(leftAt, leftAt);
            graph.show(assembly);
            List<TreeItem<Row>> selected = List.copyOf(graph.tree().getSelectionModel().getSelectedItems());
            assertEquals(1, selected.size(), "as the store holds it, the line a conditional leaves out has its row");
            assertEquals(left, selected.getFirst().getValue().node().nid());

            graph.setMode(DocumentGraphView.Mode.DOCBOOK);
            assertTrue(graph.tree().getSelectionModel().getSelectedItems().isEmpty(),
                    "as the document reads, the line is no part of it: there is no row to show the selection on");

            card.selection = new LineSelection(readAt, readAt);
            graph.showSelection();
            List<TreeItem<Row>> shown = List.copyOf(graph.tree().getSelectionModel().getSelectedItems());
            assertEquals(1, shown.size(), "a line that is read still has its row");
            assertEquals(new LineSelection(readAt, readAt), shown.getFirst().getValue().node().selection().orElseThrow());
            assertTrue(card.acts.isEmpty(), "showing a selection makes none");
        });
    }
}

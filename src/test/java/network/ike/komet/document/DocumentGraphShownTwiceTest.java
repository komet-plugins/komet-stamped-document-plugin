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
import network.ike.komet.document.DocumentAssembly.Member;
import network.ike.komet.document.DocumentGraph.Kind;
import network.ike.komet.document.DocumentGraph.Node;
import network.ike.komet.document.GitDocumentMaterializer.StoredBlock;
import network.ike.komet.document.GitDocumentMaterializer.StoredDocument;
import network.ike.komet.document.GitDocumentMaterializer.StoredLine;
import network.ike.komet.document.GitDocumentMaterializer.StoredSentence;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Graph view's tree of a document that shows one passage in two places
 * ({@code dev-document-front-matter}, the third step): the file has a tree under each include line
 * that shows it, the same blocks and lines in both, and every node spans the surface ordinals of
 * its own showing, so a row selects the place it stands in and not the other one.
 */
class DocumentGraphShownTwiceTest {

    /** The topic; a blank line before the end tag keeps the opening paragraph whole in the region. */
    private static final String SUMMARY = """
            = The Proposal in Brief

            // tag::brief[]
            We propose a way for a reviewer to decide what evidence to ask for,
            and a record of that decision that a second reviewer can check.

            // end::brief[]

            == The problem

            A reviewer decides alone.
            """;

    /** The document: the region in its abstract, then the whole topic in its body. */
    private static final String BOOK = """
            = Applying Decision Theory

            [abstract]
            --
            include::topics/summary.adoc[tag=brief]
            --

            include::topics/summary.adoc[leveloffset=+1]

            == Closing

            The end.
            """;

    private static final String FIRST = "We propose a way for a reviewer to decide what evidence to ask for,";
    private static final String SECOND = "and a record of that decision that a second reviewer can check.";

    private static DocumentAssembly book(String bookText, Map<String, StoredDocument> store) {
        return DocumentAssembly.of(DocumentGraphTest.stored(bookText), "docs/book.adoc", path -> Optional.ofNullable(store.get(path)));
    }

    private static DocumentAssembly book(StoredDocument summary) {
        return book(BOOK, Map.of("docs/topics/summary.adoc", summary));
    }

    /** Every node beneath and including one, in document order. */
    private static List<Node> all(Node node) {
        List<Node> out = new ArrayList<>();
        out.add(node);
        for (Node child : node.children()) {
            out.addAll(all(child));
        }
        return out;
    }

    /** The included documents' nodes, in document order: the host's is left out. */
    private static List<Node> included(Node root) {
        return all(root).stream().filter(n -> n.kind() == Kind.DOCUMENT && n != root).toList();
    }

    /** The nodes of one showing: those beneath its document node that stand in it, an included child's left out. */
    private static List<Node> of(Node document) {
        return all(document).stream().filter(n -> n.member() == document.member()).toList();
    }

    private static long nid(StoredDocument document, String text) {
        return document.lines().stream().filter(l -> l.text().equals(text)).findFirst().orElseThrow().nid();
    }

    private static List<String> lineTexts(Node document) {
        return of(document).stream().filter(n -> n.kind() == Kind.LINE).map(n -> n.line().text()).toList();
    }

    @Test
    void aFileShownTwiceHasATreeUnderEachIncludeLineThatShowsIt() {
        StoredDocument summary = DocumentGraphTest.stored(SUMMARY);
        DocumentAssembly assembly = book(summary);
        Member regionShowing = assembly.children().get(0);
        Member wholeShowing = assembly.children().get(1);

        Node root = DocumentGraph.of(assembly);
        List<Node> documents = included(root);
        assertEquals(2, documents.size(), "the topic's tree stands twice: under the abstract's include line and under the body's");
        Node region = documents.get(0);
        Node whole = documents.get(1);
        assertSame(regionShowing, region.member());
        assertSame(wholeShowing, whole.member());
        assertEquals(region.nid(), whole.nid(), "both are the one document the store holds");
        assertEquals("docs/topics/summary.adoc · tag=brief", region.detail(), "the showing by region says its tag beside its path");
        assertEquals("docs/topics/summary.adoc", whole.detail());

        assertEquals(List.of(FIRST, SECOND), lineTexts(region), "the region's tree holds the lines of the block the region reads, and neither tag line");
        assertTrue(lineTexts(whole).containsAll(List.of("// tag::brief[]", FIRST, SECOND, "// end::brief[]", "A reviewer decides alone.")),
                "the whole file's tree holds every line, the tag lines among them");

        // the same lines, each node at the ordinal of its own showing
        for (String text : List.of(FIRST, SECOND)) {
            long line = nid(summary, text);
            Node inRegion = of(region).stream().filter(n -> n.kind() == Kind.LINE && n.nid() == line).findFirst().orElseThrow();
            Node inWhole = of(whole).stream().filter(n -> n.kind() == Kind.LINE && n.nid() == line).findFirst().orElseThrow();
            assertSame(inRegion.line(), inWhole.line(), "one stored line, in both trees");
            assertEquals(assembly.ordinal(regionShowing, line), inRegion.first(), "in the abstract it stands at the abstract's ordinal");
            assertEquals(assembly.ordinal(wholeShowing, line), inWhole.first(), "in the body at the body's");
            assertNotEquals(inRegion.first(), inWhole.first());
            assertTrue(inRegion.detail().endsWith("of summary.adoc"), "its line number is the file's in either place: " + inRegion.detail());
            assertEquals(inRegion.detail(), inWhole.detail());
        }

        // the paragraph is one block, held whole in both showings, and spans its own showing's lines
        Node paragraphInRegion = of(region).stream().filter(n -> n.blockKind() == Block.Kind.PARAGRAPH).findFirst().orElseThrow();
        Node paragraphInWhole = of(whole).stream().filter(n -> n.kind() == Kind.BLOCK && n.nid() == paragraphInRegion.nid()).findFirst().orElseThrow();
        assertEquals(new LineSelection(assembly.ordinal(regionShowing, nid(summary, FIRST)), assembly.ordinal(regionShowing, nid(summary, SECOND))),
                paragraphInRegion.selection().orElseThrow());
        assertEquals(new LineSelection(assembly.ordinal(wholeShowing, nid(summary, FIRST)), assembly.ordinal(wholeShowing, nid(summary, SECOND))),
                paragraphInWhole.selection().orElseThrow());

        // every node of a showing spans only that showing's ordinals
        Set<Integer> regionOrdinals = regionShowing.shown().lines().stream().map(l -> assembly.ordinal(regionShowing, l.nid())).collect(Collectors.toSet());
        for (Node node : of(region)) {
            if (node.spansLines()) {
                assertTrue(regionOrdinals.contains(node.first()) && regionOrdinals.contains(node.last()),
                        node.label() + " spans " + node.first() + ".." + node.last() + ", outside the abstract's showing " + regionOrdinals);
            }
        }

        // the include line in the abstract carries the region's tree, and spans through it
        Node include = all(root).stream().filter(n -> n.kind() == Kind.LINE && n.line().text().equals("include::topics/summary.adoc[tag=brief]"))
                .findFirst().orElseThrow();
        assertTrue(include.children().contains(region));
        assertEquals(region.last(), include.last());
    }

    @Test
    void eachShowingOfAFileFollowsItsOwnIncludes() {
        StoredDocument leaf = DocumentGraphTest.stored("""
                A leaf line.
                """);
        StoredDocument topic = DocumentGraphTest.stored("""
                = A Topic

                include::leaf.adoc[]

                After the leaf.
                """);
        DocumentAssembly assembly = book("""
                = A Book

                include::topics/topic.adoc[]

                == Again

                include::topics/topic.adoc[]
                """, Map.of("docs/topics/topic.adoc", topic, "docs/topics/leaf.adoc", leaf));

        List<Node> documents = included(DocumentGraph.of(assembly));
        assertEquals(List.of("docs/topics/topic.adoc", "docs/topics/leaf.adoc", "docs/topics/topic.adoc", "docs/topics/leaf.adoc"),
                documents.stream().map(Node::detail).toList(), "the topic twice, and under each its own leaf");
        assertSame(documents.get(0).member(), documents.get(1).member().includedBy());
        assertSame(documents.get(2).member(), documents.get(3).member().includedBy());
        long line = leaf.lines().getFirst().nid();
        Node first = of(documents.get(1)).stream().filter(n -> n.kind() == Kind.LINE).findFirst().orElseThrow();
        Node second = of(documents.get(3)).stream().filter(n -> n.kind() == Kind.LINE).findFirst().orElseThrow();
        assertEquals(line, first.nid());
        assertEquals(line, second.nid());
        assertEquals(assembly.ordinal(documents.get(1).member(), line), first.first());
        assertEquals(assembly.ordinal(documents.get(3).member(), line), second.first());
        assertTrue(second.first() > first.first(), "the leaf's line stands once in each place the topic is shown");
        assertEquals(List.of(first.first(), second.first()), assembly.ordinals(line));
    }

    @Test
    void withHistoryALinesVersionsStandInBothShowings() {
        StoredDocument summary = DocumentGraphTest.stored(SUMMARY);
        DocumentAssembly assembly = book(summary);
        long title = summary.lines().getFirst().nid();
        long first = nid(summary, FIRST);
        long second = nid(summary, SECOND);
        long led = 900_001;                                      // a line once before the title
        long between = 900_002;                                  // a line once between the paragraph's two
        DocumentHistory history = DocumentHistory.from(
                List.of(List.of(led, title, first, between, second), List.of(title, first, second)),
                Map.of(led, List.of(new DocumentHistory.Raw(null, "// once first", true), new DocumentHistory.Raw(null, "// once first", false)),
                        title, List.of(new DocumentHistory.Raw(null, "= The Proposal in Brief", true)),
                        first, List.of(new DocumentHistory.Raw(null, "We propose a way,", true), new DocumentHistory.Raw(null, FIRST, true)),
                        between, List.of(new DocumentHistory.Raw(null, "a claim since withdrawn,", true),
                                new DocumentHistory.Raw(null, "a claim since withdrawn,", false)),
                        second, List.of(new DocumentHistory.Raw(null, SECOND, true))));

        Node root = DocumentGraph.of(assembly, member -> member.path().endsWith("summary.adoc") ? history : DocumentHistory.NONE);
        List<Node> documents = included(root);
        Node region = documents.get(0);
        Node whole = documents.get(1);

        for (Node showing : List.of(region, whole)) {
            Node line = of(showing).stream().filter(n -> n.kind() == Kind.LINE && n.nid() == first).findFirst().orElseThrow();
            assertEquals(List.of("We propose a way,", FIRST), line.children().stream().filter(n -> n.kind() == Kind.VERSION).map(Node::label).toList(),
                    "the line's versions beneath it, in the abstract and in the body alike: it has one history");
            assertEquals(line.first(), line.children().getFirst().first(), "a version spans its line where that line stands");
            assertEquals(List.of(between), of(showing).stream().filter(n -> n.kind() == Kind.RETIRED && n.nid() != led).map(Node::nid).toList(),
                    "a line retired from between the region's lines stands where it stood, in both");
        }
        assertEquals(List.of(led), whole.children().stream().filter(n -> n.kind() == Kind.RETIRED).map(Node::nid).toList(),
                "a line retired from before the file's first stands first in the whole file's tree");
        assertTrue(of(region).stream().noneMatch(n -> n.nid() == led), "and not in a region that does not begin where the file does");
    }

    @Test
    void theDocBookProjectionReadsARegionFromItsOwnLinesAndSaysItsTag() {
        StoredDocument summary = DocumentGraphTest.stored(SUMMARY);
        DocumentAssembly assembly = book(summary);
        Member regionShowing = assembly.children().get(0);
        Member wholeShowing = assembly.children().get(1);
        StoredLine includeLine = assembly.lines().stream().filter(l -> l.text().equals("include::topics/summary.adoc[tag=brief]")).findFirst().orElseThrow();

        Node root = DocumentGraph.docbook(assembly);
        List<Node> documents = included(root);
        assertEquals(2, documents.size());
        Node region = documents.get(0);
        Node whole = documents.get(1);
        assertSame(regionShowing, region.member());
        assertEquals(Map.of("href", "topics/summary.adoc", "tag", "brief"), region.markup().attributes(), "the include's target and its tag, as written");
        assertEquals(List.of("href", "tag"), List.copyOf(region.markup().attributes().keySet()));
        assertEquals("docs/topics/summary.adoc · tag=brief", region.detail());
        assertFalse(whole.markup().attributes().containsKey("tag"));
        assertEquals("The Proposal in Brief", whole.label());
        assertEquals("summary.adoc", region.label(), "a region without the title line has no title: it is named by its file");

        Node paragraph = of(region).stream().filter(n -> n.blockKind() == Block.Kind.PARAGRAPH).findFirst().orElseThrow();
        assertEquals(1, of(region).stream().filter(n -> n.kind() == Kind.BLOCK).count(), "the region is its one paragraph");
        assertTrue(paragraph.markup().text().startsWith("We propose a way for a reviewer") && paragraph.markup().text().endsWith("can check."),
                paragraph.markup().text());
        assertEquals(new LineSelection(assembly.ordinal(regionShowing, nid(summary, FIRST)), assembly.ordinal(regionShowing, nid(summary, SECOND))),
                paragraph.selection().orElseThrow(), "the paragraph spans the abstract's lines");
        Node inWhole = of(whole).stream().filter(n -> n.kind() == Kind.BLOCK && n.nid() == paragraph.nid()).findFirst().orElseThrow();
        assertEquals(new LineSelection(assembly.ordinal(wholeShowing, nid(summary, FIRST)), assembly.ordinal(wholeShowing, nid(summary, SECOND))),
                inWhole.selection().orElseThrow(), "and in the body the body's");

        int include = assembly.ordinal(assembly.host(), includeLine.nid());
        assertEquals(include, region.first(), "the included document spans its include line, which is the host's");
        assertEquals(assembly.ordinal(regionShowing, nid(summary, SECOND)), region.last());
        for (Node node : of(region)) {
            if (node.spansLines()) {
                assertTrue(node.first() >= include && node.last() <= region.last(), node.label() + " spans " + node.first() + ".." + node.last());
            }
        }
        assertTrue(of(whole).stream().anyMatch(n -> n.markup() != null && n.markup().element().equals("Comment") && n.markup().text().equals("tag::brief[]")),
                "the whole file shows its tag lines as the comments they are");
        assertTrue(of(region).stream().noneMatch(n -> n.markup() != null && n.markup().element().equals("Comment")));
    }

    @Test
    void aStoredSentenceIsBoundInEachShowingThatReadsItsBlockWhole() {
        StoredDocument plain = DocumentGraphTest.stored(SUMMARY);
        StoredBlock paragraph = plain.blocks().values().stream().filter(b -> b.kind() == Block.Kind.PARAGRAPH).findFirst().orElseThrow();
        String sentence = "We propose a way for a reviewer to decide what evidence to ask for, and a record of that decision that a second reviewer can check.";
        StoredDocument summary = new StoredDocument(plain.anchor(), plain.commit(), plain.lines(), 1, plain.attributes(), plain.root(), plain.blocks(),
                plain.references(), Map.of(paragraph.nid(), List.of(new StoredSentence(801, sentence, null, null, List.of()))));
        DocumentAssembly assembly = book(summary);

        List<Node> documents = included(DocumentGraph.docbook(assembly, true));
        for (Node showing : documents) {
            List<Node> sentences = of(showing).stream().filter(n -> n.markup() != null && n.markup().element().equals("Sentence")).toList();
            assertEquals(List.of(801L), sentences.stream().map(Node::nid).toList(),
                    "the sentence is a node bound to its entity under " + showing.detail() + ": bound in one showing, it is still bound in the other");
            assertEquals(new LineSelection(assembly.ordinal(showing.member(), nid(summary, FIRST)), assembly.ordinal(showing.member(), nid(summary, SECOND))),
                    sentences.getFirst().selection().orElseThrow(), "and spans its lines where that showing has them");
        }
    }

    @Test
    void aRegionKeepsTheBlankLinesItsBlocksTreeHolds() {
        // A document stored as vertices holds the blank lines between a block's members in its tree, and not among its members.
        StoredDocument plain = DocumentGraphTest.stored("""
                = Two Paragraphs

                // tag::both[]
                The first paragraph.

                The second paragraph.

                // end::both[]

                After the region.
                """);
        Map<Long, Integer> index = new HashMap<>();
        for (int i = 0; i < plain.lines().size(); i++) {
            index.put(plain.lines().get(i).nid(), i);
        }
        StoredBlock root = plain.root();
        List<Long> tree = new ArrayList<>();
        int next = 0;
        for (long member : root.members()) {
            int at = firstLine(plain, member, index);
            for (; next < at; next++) {
                if (plain.lines().get(next).text().isBlank()) {
                    tree.add(plain.lines().get(next).nid());      // the blank lines before this member
                }
            }
            tree.add(member);
            next = Math.max(next, lastLine(plain, member, index) + 1);
        }
        Map<Long, StoredBlock> blocks = new LinkedHashMap<>(plain.blocks());
        StoredBlock withTree = new StoredBlock(root.nid(), root.kind(), root.level(), root.title(), root.parameters(), root.members(), root.stamp(), tree);
        blocks.put(root.nid(), withTree);
        StoredDocument topic = new StoredDocument(plain.anchor(), plain.commit(), plain.lines(), 1, plain.attributes(), withTree, blocks, plain.references());
        long blank = plain.lines().get(4).nid();                    // between the two paragraphs
        assertTrue(tree.contains(blank) && !root.members().contains(blank), "the fixture: the blank line is in the tree alone");

        DocumentAssembly assembly = book("""
                = A Book

                include::topics/topic.adoc[tag=both]
                """, Map.of("docs/topics/topic.adoc", topic));
        Member region = assembly.children().getFirst();
        assertTrue(region.shown().root().tree().contains(blank), "the region's tree keeps the blank line between the paragraphs it reads");
        assertFalse(region.shown().root().members().contains(blank));

        Node document = included(DocumentGraph.of(assembly)).getFirst();
        assertEquals(List.of("The first paragraph.", "", "The second paragraph.", ""), lineTexts(document),
                "so the graph shows it between them, as it does in the whole file");
        Node blankNode = of(document).stream().filter(n -> n.kind() == Kind.LINE && n.nid() == blank).findFirst().orElseThrow();
        assertEquals(assembly.ordinal(region, blank), blankNode.first());
    }

    private static int firstLine(StoredDocument document, long member, Map<Long, Integer> index) {
        Integer line = index.get(member);
        return line != null ? line : firstLine(document, document.blocks().get(member).members().getFirst(), index);
    }

    private static int lastLine(StoredDocument document, long member, Map<Long, Integer> index) {
        Integer line = index.get(member);
        return line != null ? line : lastLine(document, document.blocks().get(member).members().getLast(), index);
    }
}

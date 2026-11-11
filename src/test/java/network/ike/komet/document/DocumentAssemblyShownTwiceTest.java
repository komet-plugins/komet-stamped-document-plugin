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
import network.ike.komet.document.DocumentAssembly.Include;
import network.ike.komet.document.DocumentAssembly.Located;
import network.ike.komet.document.DocumentAssembly.Member;
import network.ike.komet.document.GitDocumentMaterializer.StoredBlock;
import network.ike.komet.document.GitDocumentMaterializer.StoredDocument;
import network.ike.komet.document.GitDocumentMaterializer.StoredLine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One passage of a document shown in two places by AsciiDoc's tagged include
 * ({@code dev-document-front-matter}, the third step): an include that names a tag shows the
 * lines between the file's two tag lines; a file may be shown more than once; and every showing
 * holds the same stored lines, so a line has one nid and one history however many places it is
 * read in, and a place on the surface is a showing and a line.
 */
class DocumentAssemblyShownTwiceTest {

    /** The topic, with its opening paragraph marked as the passage that goes on the title page. */
    private static final String SUMMARY = """
            :topic-id: shown-twice-summary
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
            :toc:

            [abstract]
            --
            include::topics/summary.adoc[tag=brief]
            --

            include::topics/summary.adoc[leveloffset=+1]

            == Closing

            The end.
            """;

    private static DocumentAssembly book(String bookText, StoredDocument summary) {
        StoredDocument book = RegionFixture.parse(bookText, 0);
        Map<String, StoredDocument> store = Map.of("docs/topics/summary.adoc", summary);
        return DocumentAssembly.of(book, "docs/book.adoc", path -> Optional.ofNullable(store.get(path)));
    }

    private static List<String> texts(List<StoredLine> lines) {
        return lines.stream().map(StoredLine::text).toList();
    }

    @Test
    void anIncludeThatNamesATagShowsTheLinesBetweenTheFilesTwoTagLines() {
        StoredDocument summary = RegionFixture.parse(SUMMARY, 10_000);
        DocumentAssembly assembly = book(BOOK, summary);

        Member region = assembly.children().getFirst();
        assertEquals("docs/topics/summary.adoc", region.path());
        assertEquals("tag=brief", region.tags());
        assertFalse(region.whole());
        assertSame(summary, region.document(), "the member's document is the file as stored, whole: its line numbers are the file's");
        assertEquals(List.of("We propose a way for a reviewer to decide what evidence to ask for,",
                        "and a record of that decision that a second reviewer can check."), texts(region.shown().lines()),
                "the region is the run of the file's lines between its two tag lines; neither tag line is shown");
        assertTrue(assembly.includes().getFirst().resolved());
        assertEquals("", assembly.includes().getFirst().note());

        // the surface order: the include line in the abstract's open block, then the region's two lines, then the block's end
        int include = texts(assembly.lines()).indexOf("include::topics/summary.adoc[tag=brief]");
        assertEquals(List.of("include::topics/summary.adoc[tag=brief]",
                "We propose a way for a reviewer to decide what evidence to ask for,",
                "and a record of that decision that a second reviewer can check.",
                "--"), texts(assembly.lines().subList(include, include + 4)));

        // a line of the region is the file's fifth line: that is what an annotation names
        assertSame(region, assembly.memberAt(include + 1).orElseThrow());
        assertEquals(4, assembly.indexInMember(include + 1), "an index into the file's own lines, tag lines counted");
        Located located = assembly.locate(new LineSelection(include + 1, include + 2)).orElseThrow();
        assertSame(region, located.member());
        assertEquals(List.of(5, 6), located.local().ranges(index -> index + 1).stream().flatMap(r -> List.of(r[0], r[1]).stream()).toList(),
                "the trailer names summary.adoc's lines 5-6");
    }

    @Test
    void aRegionKeepsTheBlocksThatHoldItsLinesAndNoTitle() {
        StoredDocument summary = RegionFixture.parse(SUMMARY, 10_000);
        Member region = book(BOOK, summary).children().getFirst();
        StoredDocument shown = region.shown();

        assertEquals("", shown.root().title(), "the topic's title line is outside the region: the region has no title");
        assertTrue(shown.attributes().isEmpty());
        assertEquals(1, shown.root().members().size(), "one block: the paragraph");
        StoredBlock paragraph = shown.blocks().get(shown.root().members().getFirst());
        assertEquals(Block.Kind.PARAGRAPH, paragraph.kind());
        assertEquals(2, paragraph.members().size(), "the paragraph without the end tag line the parser gave it");
        StoredBlock stored = summary.blocks().get(paragraph.nid());
        assertEquals(3, stored.members().size(), "in the file the end tag line is the paragraph's third line");
        assertSame(summary.line(paragraph.members().getFirst()).orElseThrow(), shown.line(paragraph.members().getFirst()).orElseThrow(),
                "the same stored line, not a copy");
        assertFalse(region.holds(paragraph), "a block the region cuts is read there, not edited: an edit is of the whole block");
        assertTrue(shown.blocks().values().stream().noneMatch(b -> b.kind() == Block.Kind.SECTION), "the section after the region is not shown");

        // the same passage with a blank line before its end tag: the paragraph is whole in the region, and is edited in either place
        StoredDocument spaced = RegionFixture.parse(SUMMARY.replace("can check.\n// end::brief[]", "can check.\n\n// end::brief[]"), 20_000);
        Member whole = book(BOOK, spaced).children().getFirst();
        StoredBlock kept = whole.shown().blocks().get(whole.shown().root().members().getFirst());
        assertEquals(Block.Kind.PARAGRAPH, kept.kind());
        assertTrue(whole.holds(kept));
        assertEquals(3, whole.shown().lines().size(), "the two lines and the blank line under them");
    }

    @Test
    void aFileShownTwiceIsOneSetOfLinesWithAPlaceInEachShowing() {
        StoredDocument summary = RegionFixture.parse(SUMMARY, 10_000);
        DocumentAssembly assembly = book(BOOK, summary);

        assertEquals(List.of("docs/book.adoc", "docs/topics/summary.adoc", "docs/topics/summary.adoc"),
                assembly.members().stream().map(Member::path).toList(), "the host, the region, the whole topic");
        Member region = assembly.children().get(0);
        Member whole = assembly.children().get(1);
        assertNotSame(region, whole);
        assertFalse(region.equals(whole), "a showing equals itself alone");
        assertTrue(whole.whole());
        assertSame(summary, whole.shown());
        assertEquals(List.of(region, whole), assembly.showings("docs/topics/summary.adoc"));
        assertTrue(assembly.unresolved().isEmpty(), "the second include of the file is followed, not left as a line");

        StoredLine first = summary.lines().get(4);
        assertEquals("We propose a way for a reviewer to decide what evidence to ask for,", first.text());
        int inAbstract = assembly.ordinal(region, first.nid());
        int inBody = assembly.ordinal(whole, first.nid());
        assertTrue(inAbstract >= 0 && inBody > inAbstract, "the line stands at two ordinals, the abstract's first");
        assertEquals(List.of(inAbstract, inBody), assembly.ordinals(first.nid()));
        assertEquals(inAbstract, assembly.ordinal(first.nid()), "by nid alone, where it is first shown");
        assertSame(assembly.lines().get(inAbstract), assembly.lines().get(inBody), "one stored line in both places: one nid, one history");
        assertSame(first, assembly.lines().get(inBody));
        assertSame(region, assembly.memberAt(inAbstract).orElseThrow());
        assertSame(whole, assembly.memberAt(inBody).orElseThrow());
        assertEquals(4, assembly.indexInMember(inAbstract));
        assertEquals(4, assembly.indexInMember(inBody), "the file's fifth line in either place");
        assertEquals(inBody, assembly.ordinalIn(whole, 4));
        assertEquals(inAbstract, assembly.ordinalIn(region, 4));
        assertEquals(-1, assembly.ordinalIn(region, 0), "the region does not read the file's first line");

        // a selection is of one place, and names the file's lines once
        Located there = assembly.locate(new LineSelection(inBody, inBody + 1)).orElseThrow();
        Located here = assembly.locate(new LineSelection(inAbstract, inAbstract + 1)).orElseThrow();
        assertSame(whole, there.member());
        assertSame(region, here.member());
        assertEquals(here.local(), there.local(), "the same lines of the same file, from either place");
        assertTrue(assembly.locate(new LineSelection(inAbstract, inBody)).isEmpty(), "from one showing into the other is not one run of a file's lines");

        // the whole topic is shown with its tag lines, which are comments there
        assertTrue(texts(assembly.lines().subList(inBody - 1, inBody + 3)).equals(List.of("// tag::brief[]",
                "We propose a way for a reviewer to decide what evidence to ask for,",
                "and a record of that decision that a second reviewer can check.", "// end::brief[]")));
        long hostLines = assembly.host().document().lines().size();
        assertEquals(hostLines + summary.lines().size(), assembly.storedLines(), "the passage is counted once");
        assertEquals(hostLines + summary.lines().size() + 2, assembly.lines().size(), "and stands twice in the surface's order");
    }

    @Test
    void aFileShownTwiceFollowsItsOwnIncludesInEachShowing() {
        StoredDocument leaf = RegionFixture.parse("""
                = Leaf

                A leaf's claim.
                """, 30_000);
        StoredDocument part = RegionFixture.parse("""
                = Part

                include::leaf.adoc[leveloffset=+1]
                """, 20_000);
        StoredDocument book = RegionFixture.parse("""
                = Book

                include::part.adoc[]

                include::part.adoc[]
                """, 0);
        Map<String, StoredDocument> store = Map.of("part.adoc", part, "leaf.adoc", leaf);
        DocumentAssembly assembly = DocumentAssembly.of(book, "book.adoc", path -> Optional.ofNullable(store.get(path)));

        List<Member> parts = assembly.showings("part.adoc");
        List<Member> leaves = assembly.showings("leaf.adoc");
        assertEquals(2, parts.size());
        assertEquals(2, leaves.size());
        long includeLine = part.lines().get(2).nid();
        Include first = assembly.includeOf(parts.get(0), includeLine).orElseThrow();
        Include second = assembly.includeOf(parts.get(1), includeLine).orElseThrow();
        assertSame(leaves.get(0), first.child(), "each showing of the part has its own showing of the leaf");
        assertSame(leaves.get(1), second.child());
        assertSame(parts.get(1), second.child().includedBy());
        assertSame(first, assembly.includeOf(includeLine).orElseThrow(), "by the line alone, where it is first shown");
        assertSame(leaves.get(1), assembly.parts(parts.get(1), List.of(part.lines().get(2))).getFirst().include().child());
        assertSame(leaves.get(0), assembly.parts(List.of(part.lines().get(2))).getFirst().include().child());
    }

    @Test
    void aFileIsNeverShownInsideItself() {
        StoredDocument part = RegionFixture.parse("""
                = Part

                // tag::lead[]
                The lead.
                // end::lead[]

                include::part.adoc[tag=lead]

                include::../book.adoc[]
                """, 20_000);
        StoredDocument book = RegionFixture.parse("""
                = Book

                include::parts/part.adoc[]

                include::parts/part.adoc[tag=lead]
                """, 0);
        Map<String, StoredDocument> store = Map.of("docs/book.adoc", book, "docs/parts/part.adoc", part);
        DocumentAssembly assembly = DocumentAssembly.of(book, "docs/book.adoc", path -> Optional.ofNullable(store.get(path)));

        assertEquals(List.of("docs/book.adoc", "docs/parts/part.adoc", "docs/parts/part.adoc"), assembly.members().stream().map(Member::path).toList(),
                "the part whole, then its region: a second showing beside the first is no cycle");
        List<Include> left = assembly.unresolved();
        assertEquals(2, left.size(), "the part's include of itself, and its include of the book that holds it");
        assertTrue(left.get(0).note().startsWith("a cycle: docs/parts/part.adoc"), left.get(0).note());
        assertTrue(left.get(1).note().startsWith("a cycle: docs/book.adoc"), left.get(1).note());
        assertNull(left.get(0).child());
        assertNull(left.get(1).child());
        assertEquals(List.of("The lead."), texts(assembly.children().get(1).shown().lines()));
    }

    @Test
    void aTagTheFileDoesNotHaveLeavesTheIncludeALineWithTheReason() {
        StoredDocument summary = RegionFixture.parse(SUMMARY, 10_000);
        DocumentAssembly none = book(BOOK.replace("[tag=brief]", "[tag=summary]"), summary);
        Include unresolved = none.includes().getFirst();
        assertFalse(unresolved.resolved());
        assertEquals("no tag summary in docs/topics/summary.adoc", unresolved.note());
        assertEquals(1, none.children().size(), "the whole topic is still shown in the body");

        DocumentAssembly some = book(BOOK.replace("[tag=brief]", "[tags=brief;summary]"), summary);
        Include partly = some.includes().getFirst();
        assertTrue(partly.resolved(), "the region that is there is shown");
        assertEquals("no tag summary in docs/topics/summary.adoc", partly.note(), "and the tag that is not is named");
        assertEquals(2, partly.child().shown().lines().size());

        DocumentAssembly empty = book(BOOK.replace("[tag=brief]", "[tags=!*;!**]"), summary);
        assertFalse(empty.includes().getFirst().resolved());
        assertTrue(empty.includes().getFirst().note().startsWith("no line of docs/topics/summary.adoc is under tags="), empty.includes().getFirst().note());
    }

    @Test
    void aSectionWithoutItsHeadingGivesItsBlocksToTheRegion() {
        StoredDocument topic = RegionFixture.parse("""
                = Topic

                == Method

                // tag::steps[]
                First we ask.

                Then we record.
                // end::steps[]

                // tag::whole[]
                == Result

                It worked.
                // end::whole[]
                """, 10_000);
        StoredDocument book = RegionFixture.parse("""
                = Book

                include::topic.adoc[tag=steps]

                include::topic.adoc[tag=whole]
                """, 0);
        DocumentAssembly assembly = DocumentAssembly.of(book, "book.adoc", path -> Optional.of(topic).filter(t -> path.equals("topic.adoc")));

        StoredDocument steps = assembly.children().get(0).shown();
        assertEquals(List.of(Block.Kind.PARAGRAPH, Block.Kind.PARAGRAPH), steps.root().members().stream().map(steps.blocks()::get).map(StoredBlock::kind).toList(),
                "the section's heading is outside the region: its two paragraphs stand in the region's own flow");
        StoredDocument whole = assembly.children().get(1).shown();
        StoredBlock section = whole.blocks().get(whole.root().members().getFirst());
        assertEquals(Block.Kind.SECTION, section.kind(), "a region that holds a heading holds its section");
        assertEquals("Result", section.title());
    }
}

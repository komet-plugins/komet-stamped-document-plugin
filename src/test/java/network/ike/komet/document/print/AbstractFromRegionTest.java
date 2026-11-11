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
package network.ike.komet.document.print;

import network.ike.komet.document.Captions;
import network.ike.komet.document.DocumentAssembly;
import network.ike.komet.document.GitDocumentMaterializer.StoredDocument;
import network.ike.komet.document.RegionFixture;
import network.ike.komet.document.print.DocumentModel.Abstract;
import network.ike.komet.document.print.DocumentModel.Container;
import network.ike.komet.document.print.DocumentModel.FrontList;
import network.ike.komet.document.print.DocumentModel.Heading;
import network.ike.komet.document.print.DocumentModel.Paragraph;
import network.ike.komet.document.print.LayoutOracle.Kind;
import network.ike.komet.document.print.LayoutOracle.Unit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An abstract that takes its words from a tagged region of a topic, with the whole topic in the
 * body ({@code dev-document-front-matter}, the third step), in the print model, the Typst source
 * and the model's units: the title page prints the region as the abstract, and the body prints
 * the topic with the same paragraph as its opening. The documents are built the way the
 * materializer stores them, with no store.
 */
class AbstractFromRegionTest {

    private static final String BRIEF = "We propose a way for a reviewer to decide what evidence to ask for, "
            + "and a record of that decision that a second reviewer can check.";

    /** The topic as the design topic writes it: the end tag straight under the paragraph's last line. */
    private static final String SUMMARY = """
            :topic-id: region-summary
            = The Proposal in Brief

            // tag::brief[]
            We propose a way for a reviewer to decide what evidence to ask for,
            and a record of that decision that a second reviewer can check.
            // end::brief[]

            == The problem

            A reviewer decides alone.
            """;

    private static final String BOOK = """
            = Applying Decision Theory
            :toc:
            :sectnums:

            [abstract]
            --
            include::topics/summary.adoc[tag=brief]
            --

            :sectnums!:

            include::topics/summary.adoc[leveloffset=+1]

            :sectnums:

            == Method

            How it is done.
            """;

    /** The model as the card reads it: the assembly's children, headings numbered and listed as its captions have them. */
    private static DocumentModel model(String book, String summary) {
        StoredDocument host = RegionFixture.parse(book, 0);
        StoredDocument topic = RegionFixture.parse(summary, 10_000);
        DocumentAssembly assembly = DocumentAssembly.of(host, "docs/book.adoc",
                path -> path.equals("docs/topics/summary.adoc") ? Optional.of(topic) : Optional.empty());
        assertTrue(assembly.unresolved().isEmpty(), "both includes are followed");
        Captions captions = Captions.of(assembly, b -> false, 3);
        return StoredDocumentModel.of(host, StoredDocumentModel.children(assembly), StoredDocumentModel.Figures.captioned(captions));
    }

    private static int count(String text, String part) {
        return text.split(Pattern.quote(part), -1).length - 1;
    }

    @Test
    void theAbstractHoldsTheRegionAndTheBodyHoldsTheWholeTopic() {
        DocumentModel model = model(BOOK, SUMMARY);

        Abstract first = assertInstanceOf(Abstract.class, model.blocks().getFirst());
        Container open = assertInstanceOf(Container.class, first.blocks().getFirst());
        assertEquals(List.of(new Paragraph(BRIEF)), open.blocks(),
                "the region's paragraph alone: no title of the topic, no tag line, none of what follows the region");
        assertTrue(model.frontMatter() && model.titlePage());

        FrontList contents = assertInstanceOf(FrontList.class, model.blocks().get(1));
        assertEquals(List.of("The Proposal in Brief", "The problem", "1 Method"), contents.entries().stream().map(DocumentModel.Entry::text).toList(),
                "the topic is listed once, where the body shows it; the abstract is no entry");

        List<DocumentModel.Block> body = model.blocks().subList(2, model.blocks().size());
        assertEquals("The Proposal in Brief", assertInstanceOf(Heading.class, body.get(0)).text());
        assertEquals(new Paragraph(BRIEF), body.get(1), "the same paragraph opens the topic in the body, its end tag line printing nothing");
        assertEquals("The problem", assertInstanceOf(Heading.class, body.get(2)).text());
        assertEquals(new Paragraph("A reviewer decides alone."), body.get(3));
        assertEquals("1 Method", assertInstanceOf(Heading.class, body.get(4)).text());
        assertEquals(2, model.blocks().stream().flatMap(AbstractFromRegionTest::paragraphs).filter(p -> p.text().equals(BRIEF)).count(),
                "the passage is in the model twice: on the title page and in the body");
    }

    private static java.util.stream.Stream<Paragraph> paragraphs(DocumentModel.Block block) {
        return switch (block) {
            case Paragraph p -> java.util.stream.Stream.of(p);
            case Abstract a -> a.blocks().stream().flatMap(AbstractFromRegionTest::paragraphs);
            case Container c -> c.blocks().stream().flatMap(AbstractFromRegionTest::paragraphs);
            default -> java.util.stream.Stream.empty();
        };
    }

    @Test
    void theTypstSourcePrintsThePassageOnTheTitlePageAndAgainInTheBody() {
        DocumentModel model = model(BOOK, SUMMARY);
        String typst = TypstWriter.write(model, TypstWriter.PageSetup.DEFAULT);

        assertEquals(2, count(typst, BRIEF), "the passage is printed twice");
        int title = typst.indexOf("#ike-title(\"");
        int first = typst.indexOf(BRIEF);
        int breakAfter = typst.indexOf(TypstWriter.PAGE_BREAK, first);
        int bodyStart = typst.indexOf(TypstWriter.BODY_START);
        int second = typst.indexOf(BRIEF, first + 1);
        assertTrue(title >= 0 && title < first && first < breakAfter, "once after the title and before the first page break: on the title page\n" + typst);
        assertTrue(bodyStart > breakAfter && second > bodyStart, "and once after the body opens");
        assertEquals(1, count(typst, TypstWriter.BODY_START), "the body is opened once");
        int topic = typst.indexOf("= The Proposal in Brief");
        assertTrue(bodyStart < topic && topic < second, "in the body the passage stands under the topic's heading");
        assertFalse(typst.contains("tag::") || typst.contains("end::"), "no tag line is printed, in either place");
        assertFalse(typst.contains("include::"), "and no include line");
        assertEquals(1, count(typst, "= The Proposal in Brief"), "the topic's heading is printed once, in the body");
    }

    @Test
    void theModelsUnitsSetThePassageOnTheTitlePageAndInTheBody() {
        DocumentModel model = model(BOOK, SUMMARY);
        List<Unit> units = ModelUnits.of(model, 11);

        List<Unit> passage = units.stream().filter(u -> u.text().equals(BRIEF)).toList();
        assertEquals(2, passage.size());
        assertEquals(1, passage.get(0).block(), "the first is the abstract, the model's first block after the title");
        assertFalse(passage.get(0).pageBefore() || passage.get(0).opensBody(), "it opens no page: it stands on the title page");
        assertEquals(Kind.PARAGRAPH, passage.get(0).kind());
        Unit contents = units.get(passage.get(0).index() + 1);
        assertTrue(contents.pageBefore() && !contents.opensBody(), "the contents open the next page, in the front matter");
        Unit topic = units.stream().filter(u -> u.kind() == Kind.HEADING && u.text().equals("The Proposal in Brief")).findFirst().orElseThrow();
        assertTrue(topic.pageBefore() && topic.opensBody(), "the topic's heading opens the body, and page 1");
        assertEquals(topic.index() + 1, passage.get(1).index(), "the passage again, as the topic's opening");
        assertEquals(1, units.stream().filter(Unit::opensBody).count());
    }

    @Test
    void aRegionWithItsEndTagOnALineOfItsOwnReadsTheSame() {
        String spaced = SUMMARY.replace("can check.\n// end::brief[]", "can check.\n\n// end::brief[]");
        assertEquals(TypstWriter.write(model(BOOK, SUMMARY), TypstWriter.PageSetup.DEFAULT).replaceAll("<ike-b\\d+>", "<ike-b>"),
                TypstWriter.write(model(BOOK, spaced), TypstWriter.PageSetup.DEFAULT).replaceAll("<ike-b\\d+>", "<ike-b>"),
                "a blank line before the end tag changes nothing on the pages");
    }

    @Test
    void aCommentAmongAParagraphsLinesPrintsNothing() {
        DocumentModel model = StoredDocumentModel.of(StoredFixture.parse("""
                = A Topic

                First words
                // a note to the author
                last words.
                ////
                not a line comment: a block comment's delimiter is left to the block
                """), Map.of());
        assertEquals(new Paragraph("First words last words."), model.blocks().getFirst(), "as AsciiDoc reads a line comment inside a paragraph");
    }

    @Test
    void aSectionShownTwiceIsNumberedAndListedOnceWhereItIsFirstShown() {
        DocumentModel model = model("""
                = Book
                :toc:
                :sectnums:

                include::topics/summary.adoc[leveloffset=+1]

                include::topics/summary.adoc[leveloffset=+1]

                == Method

                How it is done.
                """, SUMMARY);

        List<Heading> topics = model.blocks().stream().filter(Heading.class::isInstance).map(Heading.class::cast)
                .filter(h -> h.text().equals("1 The Proposal in Brief")).toList();
        assertEquals(2, topics.size(), "the topic's heading is printed in each showing, with the one number the section has");
        assertFalse(topics.get(0).key().isEmpty(), "the first carries the block's key");
        assertEquals("", topics.get(1).key(), "the second carries none: a key names one place");
        String typst = TypstWriter.write(model, TypstWriter.PageSetup.DEFAULT);
        assertEquals(1, count(typst, " <" + TypstWriter.TARGET_LABEL + topics.get(0).key() + ">\n"), "so the Typst source labels the block once");
        FrontList contents = assertInstanceOf(FrontList.class, model.blocks().getFirst());
        assertEquals(List.of("1 The Proposal in Brief", "1.1 The problem", "2 Method"), contents.entries().stream().map(DocumentModel.Entry::text).toList(),
                "a section shown twice is one section: listed once, and the section after it is the second");
    }
}

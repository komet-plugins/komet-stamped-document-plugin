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
import network.ike.komet.document.print.DocumentModel.Abstract;
import network.ike.komet.document.print.DocumentModel.Container;
import network.ike.komet.document.print.DocumentModel.FrontList;
import network.ike.komet.document.print.DocumentModel.Heading;
import network.ike.komet.document.print.DocumentModel.ListBlock;
import network.ike.komet.document.print.DocumentModel.Paragraph;
import network.ike.komet.document.print.LayoutOracle.Kind;
import network.ike.komet.document.print.LayoutOracle.Unit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A document's abstract in the print model, the Typst source and the model's units
 * ({@code dev-document-front-matter}, the second step): a block with AsciiDoc's {@code abstract}
 * style before the first section is the model's first block; it is written straight after the
 * title, so the title page prints it; a page break follows it; the lists and the body come
 * after, the body opened where page 1 begins. Each document is a {@link StoredDocument} built
 * the way the materializer stores one.
 */
class AbstractOnTitlePageTest {

    private static final String ABSTRACT = "We propose a way for a reviewer to decide what evidence to ask for, and a record of that decision.";

    private static final String BODY = """

            == One

            First prose.

            == Two

            Second prose.
            """;

    /** A titled document whose abstract is a paragraph, with no list. */
    private static String paragraphForm() {
        return """
                :topic-id: t-abstract
                = The Fixture Title

                [abstract]
                %s
                """.formatted(ABSTRACT) + BODY;
    }

    private static DocumentModel model(String asciidoc) {
        return StoredDocumentModel.of(StoredFixture.parse(asciidoc));
    }

    /** The model as a surface reads it: headings numbered and listed as the captions have them. */
    private static DocumentModel captioned(String asciidoc) {
        StoredDocument stored = StoredFixture.parse(asciidoc);
        Captions captions = Captions.of(DocumentAssembly.single(stored, "t.adoc"), b -> false, 3);
        return StoredDocumentModel.of(stored, Map.of(), StoredDocumentModel.Figures.captioned(captions));
    }

    /** The source after the title's line. */
    private static String afterTitle(String typst) {
        int title = typst.indexOf("#ike-title(\"");
        assertTrue(title >= 0, "title block missing in\n" + typst);
        return typst.substring(typst.indexOf('\n', title) + 1).strip();
    }

    private static int count(String text, String part) {
        return text.split(Pattern.quote(part), -1).length - 1;
    }

    @Test
    void anAbstractParagraphBeforeTheFirstSectionIsTheModelsFirstBlock() {
        DocumentModel model = model(paragraphForm());

        Abstract first = assertInstanceOf(Abstract.class, model.blocks().getFirst());
        assertEquals(List.of(new Paragraph(ABSTRACT)), first.blocks(), "the style line is no part of the text");
        assertEquals(first, model.abstractBlock().orElseThrow());
        assertEquals(1, model.blocks().stream().filter(Abstract.class::isInstance).count());
        assertEquals("One", assertInstanceOf(Heading.class, model.blocks().get(1)).text(), "the body follows, and the abstract is not read again in it");
        assertFalse(model.blocks().subList(1, model.blocks().size()).contains(new Paragraph(ABSTRACT)));
        assertTrue(model.frontMatter(), "a document with an abstract has front matter");
        assertTrue(model.titlePage(), "and a title page of its own, though its header names no list");
        assertTrue(model.entries().isEmpty());
    }

    @Test
    void anAbstractInAnOpenBlockHoldsItsParagraphsAndLists() {
        DocumentModel model = model("""
                :topic-id: t-abstract-open
                = The Fixture Title

                [abstract]
                --
                %s

                The record has three parts.

                * the request
                * the reason
                --
                """.formatted(ABSTRACT) + BODY);

        Abstract first = assertInstanceOf(Abstract.class, model.blocks().getFirst());
        Container open = assertInstanceOf(Container.class, first.blocks().getFirst());
        assertEquals(Container.Kind.OPEN, open.kind());
        assertEquals(List.of(new Paragraph(ABSTRACT), new Paragraph("The record has three parts.")), open.blocks().subList(0, 2));
        ListBlock list = assertInstanceOf(ListBlock.class, open.blocks().get(2));
        assertEquals(List.of("the request", "the reason"), list.items().stream().map(DocumentModel.Item::text).toList());
        assertTrue(model.frontMatter() && model.titlePage());

        String typst = afterTitle(TypstWriter.write(model, TypstWriter.PageSetup.DEFAULT));
        assertTrue(typst.startsWith(ABSTRACT + "\n\nThe record has three parts.\n\n- the request\n- the reason\n\n"
                + TypstWriter.PAGE_BREAK + TypstWriter.BODY_START + "= One\n\n"), typst);
    }

    @Test
    void onlyTheFirstBlockWithTheStyleBeforeTheFirstSectionIsTheAbstract() {
        // a second block with the style is an ordinary block, where it was written
        DocumentModel twice = model("""
                :topic-id: t-abstract-twice
                = The Fixture Title

                [abstract]
                The first.

                [abstract]
                The second.
                """ + BODY);
        assertEquals(List.of(new Paragraph("The first.")), assertInstanceOf(Abstract.class, twice.blocks().getFirst()).blocks());
        assertEquals(new Paragraph("The second."), twice.blocks().get(1), "at most one abstract");
        assertEquals(1, twice.blocks().stream().filter(Abstract.class::isInstance).count());

        // prose before it is the body's: the abstract is still taken to the front
        DocumentModel after = model("""
                :topic-id: t-abstract-after
                = The Fixture Title

                A word first.

                [abstract]
                %s
                """.formatted(ABSTRACT) + BODY);
        assertInstanceOf(Abstract.class, after.blocks().getFirst());
        assertEquals(new Paragraph("A word first."), after.blocks().get(1), "page 1 begins at the first block that is not the abstract");

        // the style inside a section is no abstract
        DocumentModel inside = model("""
                :topic-id: t-abstract-inside
                = The Fixture Title

                == One

                [abstract]
                %s
                """.formatted(ABSTRACT));
        assertTrue(inside.abstractBlock().isEmpty());
        assertFalse(inside.frontMatter());
        assertEquals(List.of(new Heading(1, "One"), new Paragraph(ABSTRACT)), List.of(new Heading(1, ((Heading) inside.blocks().get(0)).text()), inside.blocks().get(1)));

        // a role or an id beside the style, and another attribute line above it, still name the style
        assertTrue(StoredDocumentModel.abstractStyle("[abstract]"));
        assertTrue(StoredDocumentModel.abstractStyle("[abstract.lead]"));
        assertTrue(StoredDocumentModel.abstractStyle("[abstract#brief]"));
        assertTrue(StoredDocumentModel.abstractStyle("[%hardbreaks]\n[abstract]"));
        assertFalse(StoredDocumentModel.abstractStyle("[quote]"));
        assertFalse(StoredDocumentModel.abstractStyle("[role=abstract]"));
        assertFalse(StoredDocumentModel.abstractStyle(""));
        assertFalse(StoredDocumentModel.abstractStyle(null));
    }

    @Test
    void anIncludeEndsTheSearchAndAnIncludedDocumentsAbstractStaysWhereItIs() {
        StoredDocument host = StoredFixture.parse("""
                :topic-id: t-abstract-host
                = The Compendium

                include::{topics}/topics/a.adoc[leveloffset=+1]

                [abstract]
                Written after an include, where a section may already have opened.
                """);
        StoredDocument child = StoredFixture.parse("""
                :topic-id: t-abstract-child
                = Child Title

                [abstract]
                The child's own abstract.

                == Child Section

                More.
                """);
        long include = host.lines().stream().filter(l -> l.text().startsWith("include::")).findFirst().orElseThrow().nid();
        DocumentModel model = StoredDocumentModel.of(host, Map.of(include, new StoredDocumentModel.Child(child, 1)));

        assertTrue(StoredDocumentModel.abstractOf(host).isEmpty(), "an include line ends the search, as a section does");
        assertTrue(model.abstractBlock().isEmpty());
        assertFalse(model.frontMatter());
        assertEquals("Child Title", assertInstanceOf(Heading.class, model.blocks().get(0)).text());
        assertEquals(new Paragraph("The child's own abstract."), model.blocks().get(1), "a child's abstract is a paragraph of the child, not the host's abstract");
        assertTrue(StoredDocumentModel.abstractOf(child).isPresent(), "though the child read alone has one");
    }

    @Test
    void theAbstractIsWrittenAfterTheTitleAndTheBodyOpensOnANewPage() {
        DocumentModel model = model(paragraphForm());
        String typst = TypstWriter.write(model, TypstWriter.PageSetup.DEFAULT);

        assertTrue(afterTitle(typst).startsWith(ABSTRACT + "\n\n" + TypstWriter.PAGE_BREAK + TypstWriter.BODY_START + "= One\n\n"),
                "on the title page, beneath the title; then a new page, where the body and page 1 begin\n" + afterTitle(typst));
        assertEquals(1, count(typst, TypstWriter.PAGE_BREAK), "one page break: after the abstract");
        assertEquals(1, count(typst, TypstWriter.BODY_START), "the body is opened once");
        assertTrue(typst.contains(TypstWriter.folioHelper(true)), "the front matter is numbered in roman, with no number on the title page");
        assertFalse(typst.contains("counter(page).display("), "no page prints the counter's own number");
        assertEquals(1, count(typst, ABSTRACT), "the abstract is printed once");

        // the measured form marks the title, the abstract as one block, and every block after it
        String words = TypstWriter.write(model, TypstWriter.PageSetup.DEFAULT, TypstWriter.Labels.BLOCKS);
        assertTrue(words.contains("#metadata(0) <ike-block>\n#ike-title("), words);
        assertTrue(words.contains("#metadata(1) <ike-block>\n" + ABSTRACT + "\n\n" + TypstWriter.PAGE_BREAK + TypstWriter.BODY_START
                + "#metadata(2) <ike-block>\n= One\n\n"), "the page breaks where the printed form does, before the block's label\n" + words);
    }

    @Test
    void theListsFollowTheAbstractEachOnAPageAndTheAbstractIsNoEntry() {
        DocumentModel model = captioned(paragraphForm().replace("= The Fixture Title\n", "= The Fixture Title\n:toc:\n:sectnums:\n"));

        assertInstanceOf(Abstract.class, model.blocks().get(0));
        FrontList contents = assertInstanceOf(FrontList.class, model.blocks().get(1));
        assertEquals(List.of("1 One", "2 Two"), contents.entries().stream().map(DocumentModel.Entry::text).toList(),
                "the contents list what comes after them: the abstract is no entry");
        assertEquals("1 One", assertInstanceOf(Heading.class, model.blocks().get(2)).text());

        String typst = TypstWriter.write(model, TypstWriter.PageSetup.DEFAULT);
        String after = afterTitle(typst);
        assertTrue(after.startsWith(ABSTRACT + "\n\n" + TypstWriter.PAGE_BREAK + "= Contents\n\n"), "the abstract, then the contents on the next page\n" + after);
        assertTrue(after.contains(TypstWriter.PAGE_BREAK + TypstWriter.BODY_START + "= 1 One <ike-"), "then the body, on a page of its own, where page 1 begins\n" + after);
        assertEquals(2, count(typst, TypstWriter.PAGE_BREAK));
        assertEquals(1, count(typst, TypstWriter.BODY_START));
        assertTrue(typst.indexOf(ABSTRACT) < typst.indexOf("= Contents") && typst.indexOf("= Contents") < typst.indexOf(TypstWriter.BODY_START));
    }

    @Test
    void theAbstractsUnitsStandOnTheTitlesPageAndTheUnitAfterThemOpensThePageAndTheBody() {
        List<Unit> units = ModelUnits.of(model(paragraphForm()), 12);

        assertEquals(List.of(Kind.TITLE, Kind.PARAGRAPH, Kind.HEADING, Kind.PARAGRAPH, Kind.HEADING, Kind.PARAGRAPH), units.stream().map(Unit::kind).toList());
        assertEquals(List.of(0, 1, 2, 3, 4, 5), units.stream().map(Unit::block).toList(), "the abstract is one block of the model, as the writer labels it");
        assertEquals(ABSTRACT, units.get(1).text());
        assertEquals(List.of(false, false, true, false, false, false), units.stream().map(Unit::pageBefore).toList(),
                "the abstract opens no page: it stands on the title's; the block after it does");
        assertEquals(List.of(false, false, true, false, false, false), units.stream().map(Unit::opensBody).toList(), "and that block opens the body");

        // with a list, the list opens the page after the abstract, and the body the page after the list
        List<Unit> listed = ModelUnits.of(captioned(paragraphForm().replace("= The Fixture Title\n", "= The Fixture Title\n:toc:\n:sectnums:\n")), 12);
        assertEquals(List.of(Kind.TITLE, Kind.PARAGRAPH, Kind.HEADING, Kind.ENTRY, Kind.ENTRY, Kind.HEADING), listed.subList(0, 6).stream().map(Unit::kind).toList());
        assertEquals(List.of("Contents", "1 One", "2 Two", "1 One"), listed.subList(2, 6).stream().map(Unit::text).toList());
        assertEquals(List.of(false, false, true, false, false, true), listed.subList(0, 6).stream().map(Unit::pageBefore).toList());
        assertEquals(List.of(false, false, false, false, false, true), listed.subList(0, 6).stream().map(Unit::opensBody).toList(), "the lists are front matter too");
        assertEquals(1, listed.stream().filter(Unit::opensBody).count());

        // an abstract in an open block: every unit of it is the one block, and none opens a page
        List<Unit> open = ModelUnits.of(model("""
                :topic-id: t-abstract-units
                = The Fixture Title

                [abstract]
                --
                One paragraph.

                * an item
                --
                """ + BODY), 12);
        assertEquals(List.of(Kind.TITLE, Kind.PARAGRAPH, Kind.LIST_ITEM, Kind.HEADING), open.subList(0, 4).stream().map(Unit::kind).toList());
        assertEquals(List.of(0, 1, 1, 2), open.subList(0, 4).stream().map(Unit::block).toList());
        assertEquals(List.of(false, false, false, true), open.subList(0, 4).stream().map(Unit::pageBefore).toList());
    }

    @Test
    void aDocumentWithNoAbstractAndNoListIsWrittenAsItAlwaysWas() {
        String plain = paragraphForm().replace("[abstract]\n", "");
        DocumentModel model = model(plain);
        String typst = TypstWriter.write(model, TypstWriter.PageSetup.DEFAULT);

        assertTrue(model.abstractBlock().isEmpty());
        assertFalse(model.frontMatter());
        assertFalse(model.titlePage());
        assertEquals(new Paragraph(ABSTRACT), model.blocks().getFirst(), "the same words with no style are the body's first paragraph");
        assertTrue(afterTitle(typst).startsWith(ABSTRACT + "\n\n= One\n\n"), "on page 1, beneath the title, with the text after it");
        assertFalse(typst.contains("#pagebreak"));
        assertFalse(typst.contains(TypstWriter.BODY_LABEL));
        assertFalse(typst.contains(TypstWriter.FOLIO_LABEL));
        assertTrue(typst.contains("counter(page).display(\"1\")"), "arabic from the first page");
        assertTrue(ModelUnits.of(model, 12).stream().noneMatch(unit -> unit.pageBefore() || unit.opensBody()));

        // the abstract's own writing adds nothing to the source but the page break and the body's opening
        String withAbstract = TypstWriter.write(model(paragraphForm()), TypstWriter.PageSetup.DEFAULT);
        assertEquals(afterTitle(typst), afterTitle(withAbstract).replace(TypstWriter.PAGE_BREAK + TypstWriter.BODY_START, ""));
    }

    @Test
    void anAbstractWithNothingAfterItIsNoFrontMatter() {
        DocumentModel alone = model("""
                :topic-id: t-abstract-alone
                = The Fixture Title

                [abstract]
                %s
                """.formatted(ABSTRACT));
        String typst = TypstWriter.write(alone, TypstWriter.PageSetup.DEFAULT);

        assertTrue(alone.abstractBlock().isPresent());
        assertFalse(alone.frontMatter(), "there is no body for it to stand in front of");
        assertFalse(alone.titlePage());
        assertEquals(ABSTRACT, afterTitle(typst));
        assertFalse(typst.contains("#pagebreak"));
        assertFalse(typst.contains(TypstWriter.FOLIO_LABEL));
        assertTrue(ModelUnits.of(alone, 12).stream().noneMatch(unit -> unit.pageBefore() || unit.opensBody()));

        // an open block with the style and nothing in it sets nothing, and is no abstract
        DocumentModel empty = model("""
                :topic-id: t-abstract-empty
                = The Fixture Title

                [abstract]
                --
                --
                """ + BODY);
        assertTrue(empty.abstractBlock().isEmpty());
        assertFalse(empty.frontMatter());
        assertFalse(TypstWriter.write(empty, TypstWriter.PageSetup.DEFAULT).contains("#pagebreak"));
    }
}

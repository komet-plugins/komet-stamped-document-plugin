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

import network.ike.komet.document.surface.print.settings.DocumentTheme;
import network.ike.komet.document.surface.print.settings.FurnitureVisibility;
import network.ike.komet.document.surface.print.settings.MarginPreset;
import network.ike.komet.document.surface.print.settings.PageNumberPlacement;
import network.ike.komet.document.surface.print.settings.PageOrientation;
import network.ike.komet.document.surface.print.settings.PageSize;
import network.ike.komet.document.surface.print.settings.PrintSettings;
import network.ike.komet.document.GitDocumentMaterializer.StoredDocument;
import network.ike.komet.document.print.DocumentModel.Heading;
import network.ike.komet.document.print.DocumentModel.ListBlock;
import network.ike.komet.document.print.DocumentModel.Table;
import org.junit.jupiter.api.Test;
import network.ike.komet.document.DecisionFigure;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Typst writer, one construct at a time, each from a {@link StoredDocument} built the way
 * the materializer stores one. The body assertions read the source after the preamble.
 */
class TypstWriterTest {

    private static final String HEADER = """
            :topic-id: t-fixture
            = The Fixture Title

            """;

    private static String body(String asciidoc) {
        StoredDocument stored = StoredFixture.parse(HEADER + asciidoc);
        String typst = TypstWriter.write(StoredDocumentModel.of(stored), TypstWriter.PageSetup.DEFAULT);
        int title = typst.indexOf("#ike-title[");
        assertTrue(title >= 0, "title block missing in\n" + typst);
        return typst.substring(typst.indexOf('\n', title) + 1).strip();
    }

    @Test
    void pageSetupCarriesPaperMarginsFontsAndFurniture() {
        PrintSettings settings = new PrintSettings(PageSize.A4, PageOrientation.LANDSCAPE, MarginPreset.NARROW,
                DocumentTheme.MANUSCRIPT, FurnitureVisibility.SHOWN, PageNumberPlacement.FOOTER_RIGHT);
        String typst = TypstWriter.write(StoredDocumentModel.of(StoredFixture.parse(HEADER + "Hello.\n")), TypstPdf.setup(settings));
        assertTrue(typst.contains("#set document(title: \"The Fixture Title\")"));
        assertTrue(typst.contains("paper: \"a4\""));
        assertTrue(typst.contains("flipped: true"));
        assertTrue(typst.contains("margin: (top: 36pt, bottom: 36pt, left: 36pt, right: 36pt)"));
        assertTrue(typst.contains("\"Libertinus Serif\""), "serif body for the manuscript theme");
        assertTrue(typst.contains("size: 12pt"));
        assertTrue(typst.contains("header: context {"), "a running head");
        assertTrue(typst.contains("text(\"The Fixture Title\"), current)"), "title left, section right");
        assertTrue(typst.contains("footer: context align(right, text(size: 0.8em, fill: luma(70), counter(page).display(\"1\")))"));
        assertTrue(typst.contains("#ike-title[The Fixture Title]"));
    }

    @Test
    void noRunningHeadAndNoFolioLeaveNoFurniture() {
        PrintSettings settings = new PrintSettings(PageSize.LETTER, PageOrientation.PORTRAIT, MarginPreset.NORMAL,
                DocumentTheme.DEFAULT, FurnitureVisibility.HIDDEN, PageNumberPlacement.NONE);
        String typst = TypstWriter.write(StoredDocumentModel.of(StoredFixture.parse(HEADER + "Hello.\n")), TypstPdf.setup(settings));
        assertFalse(typst.contains("header:"));
        assertFalse(typst.contains("footer:"));
        assertTrue(typst.contains("\"Helvetica Neue\""), "sans body for the default theme");
        assertTrue(typst.contains("size: 11pt"));
    }

    @Test
    void headerFolioSharesTheRunningHeadBand() {
        PrintSettings settings = PrintSettings.DEFAULT.withPageNumbers(PageNumberPlacement.HEADER_RIGHT);
        String typst = TypstWriter.write(StoredDocumentModel.of(StoredFixture.parse(HEADER + "Hello.\n")), TypstPdf.setup(settings));
        assertTrue(typst.contains("#h(1em) #current], counter(page).display(\"1\"))"));
        assertFalse(typst.contains("footer:"));
    }

    private static DocumentModel drawn() {
        return new DocumentModel("T", List.of(new DocumentModel.Drawn(new DecisionFigure(600, 120, "decision-tree",
                List.of(new DecisionFigure.Text(1, 2, "inside the figure", 10, false, false, "#000000", "name"))))));
    }

    @Test
    void aDrawnBlockPrintsItsMarksFittedToThePage() {
        String typst = TypstWriter.write(drawn(), TypstWriter.PageSetup.DEFAULT);
        assertTrue(typst.contains("#ike-figure(600pt, 120pt, ["), typst);
        assertTrue(typst.contains("inside the figure"), typst);
        assertTrue(typst.contains("reflow: true, body"), "the scaled marks take the room of their scaled size, as the measured figure does");
    }

    @Test
    void aDrawnBlockIsMeasuredAsOneLabelledWord() {
        String typst = TypstWriter.write(drawn(), TypstWriter.PageSetup.DEFAULT, TypstWriter.Labels.WORDS);
        assertFalse(typst.contains("inside the figure"), "its words are not the document's");
        assertTrue(typst.contains("#metadata(\"figure\") <" + TypstOracle.WORD_LABEL + ">"), typst);
        assertTrue(typst.contains("#ike-figure(600pt, 120pt, [])"), typst);
    }

    @Test
    void headingsByLevel() {
        String typst = body("""
                == Section One

                Text.

                === Deeper

                More.
                """);
        assertTrue(typst.startsWith("= Section One\n\nText.\n\n== Deeper\n\nMore."), typst);
    }

    @Test
    void paragraphLinesJoinWithSpacesAndHardBreaksBreak() {
        String typst = body("""
                First line of prose
                continues here, +
                and ends with a backslash \\
                then the last.
                """);
        assertEquals("First line of prose continues here, \\\nand ends with a backslash then the last.", typst);
    }

    @Test
    void indexTermsPrintNothingAndFlowTermsKeepTheirWords() {
        String typst = body("""
                indexterm:[ANF, medication history example]
                This reference (((concealed))) covers ((hypertension)) and more.
                """);
        assertEquals("This reference covers hypertension and more.", typst);
    }

    @Test
    void markupCharactersAreEscaped() {
        String typst = body("""
                A 2 * 3 sum, snake_case_name, a #hash, 100% of [brackets] <angles> and a -- dash.
                """);
        assertEquals("A 2 \\* 3 sum, snake\\_case\\_name, a \\#hash, 100% of \\[brackets\\] \\<angles\\> and a \\-\\- dash.", typst);
    }

    @Test
    void inlineSpansAreRealTypst() {
        assertEquals("Use #strong[bold]; and #emph[emphasis];, #raw(\"measurementBasis\");.", body("""
                Use *bold* and _emphasis_, `measurementBasis`.
                """));
        assertEquals("#strong[#emph[both];]; and #strong[#raw(\"code\");];", body("""
                *_both_* and *`code`*
                """), "a span nested in a span");
        assertEquals("#raw(\"a\\\"b\");.foo", body("""
                `a"b`.foo
                """), "the semicolon ends the expression, so what follows is text");
    }

    @Test
    void referencesPrintTheirText() {
        assertEquals("See the data types and anf data types, and #link(\"https://ike.network/a\")[the site];.", body("""
                See xref:anf-data-types[the data types] and <<anf-data-types>>, and https://ike.network/a[the site].
                """));
    }

    @Test
    void aFootnoteIsTypstsAndTakesTheSpaceBeforeItsMark() {
        assertEquals("Said so.#footnote[With #raw(\"code\"); in it.]; Then more.", body("""
                Said so. footnote:[With `code` in it.] Then more.
                """));
    }

    @Test
    void aNamedFootnoteIsLabelledAndAMarkPointsAtIt() {
        assertEquals("One.#footnote[The note.]<shared> Two.#footnote(<shared>);", body("""
                One. footnote:shared[The note.] Two. footnote:shared[]
                """));
    }

    @Test
    void theRunningHeadLeavesAHeadingsNoteToTheHeading() {
        assertTrue(TypstWriter.preamble("T", TypstWriter.PageSetup.DEFAULT).contains("show footnote: none"));
    }

    @Test
    void unorderedAndOrderedLists() {
        String typst = body("""
                * first item
                  wraps here
                * second item

                Between.

                . step one
                . step two
                """);
        assertEquals("- first item wraps here\n- second item\n\nBetween.\n\n+ step one\n+ step two", typst);
    }

    @Test
    void nestedListAndContinuationParagraph() {
        String typst = body("""
                * outer
                ** inner one
                ** inner two
                * outer two
                +
                Attached paragraph.
                """);
        assertEquals("- outer\n  - inner one\n  - inner two\n- outer two\n  Attached paragraph.", typst);
    }

    @Test
    void descriptionListTermsAndText() {
        String typst = body("""
                Term one:: Its meaning.
                Term two::
                  Wrapped meaning.
                """);
        assertEquals("/ Term one: Its meaning.\n/ Term two: Wrapped meaning.", typst);
    }

    @Test
    void listTitleBecomesABlockTitle() {
        String typst = body("""
                .Things
                * one
                """);
        assertEquals("#ike-block-title[Things]\n- one", typst);
    }

    @Test
    void paragraphAdmonition() {
        String typst = body("""
                NOTE: Mind the gap
                on the platform.
                """);
        assertEquals("#ike-admonition(\"NOTE\")[\n  Mind the gap on the platform.\n]", typst);
    }

    @Test
    void blockAdmonitionCarriesItsBody() {
        String typst = body("""
                [WARNING]
                ====
                First paragraph.

                * a list inside
                ====
                """);
        assertEquals("#ike-admonition(\"WARNING\")[\n  First paragraph.\n\n  - a list inside\n]", typst);
    }

    @Test
    void listingIsFencedWithoutDelimitersAndKeepsLines() {
        String typst = body("""
                .Example code
                [source,java]
                ----
                int x = `tick`;
                  indented *stars* #hash
                ----
                """);
        assertEquals("#ike-block-title[Example code]\n```\nint x = `tick`;\n  indented *stars* #hash\n```", typst);
    }

    @Test
    void literalBlockFenceOutgrowsBackticksInside() {
        String typst = body("""
                ....
                a ``` run
                ....
                """);
        assertEquals("````\na ``` run\n````", typst);
    }

    @Test
    void tableWithHeaderRowColsAndTitle() {
        String typst = body("""
                .Elements
                [cols="2,3", options="header"]
                |===
                | Element | Topic

                | Gender (female)
                | Female

                | 58 years
                | Age
                |===
                """);
        String expected = """
                #figure(
                  table(
                    columns: (2fr, 3fr),
                    table.header(repeat: true,
                      [Element], [Topic], \n\
                    ),
                    [Gender (female)], [Female], \n\
                    [58 years], [Age], \n\
                  ),
                  caption: [Elements],
                  kind: table,
                )""";
        assertEquals(expected, typst);
    }

    @Test
    void aTableBreaksBetweenWholeRowsItsCaptionKeptWithIt() {
        String preamble = TypstWriter.preamble("T", TypstWriter.PageSetup.DEFAULT);

        assertTrue(preamble.contains("#show figure.where(kind: table): set block(breakable: true)"), preamble);
        assertTrue(preamble.contains("#set table.cell(breakable: false)"), preamble);
        assertTrue(preamble.contains("#show figure.caption: it => block(sticky: true, it)"), preamble);
    }

    @Test
    void aRowThatMayNotFitAPageIsLetBreak() {
        String tall = "x".repeat(TypstWriter.WHOLE_ROW_CHARACTERS / 4 + 1);
        String typst = TypstWriter.write(new DocumentModel("", List.of(new Table("", List.of(1, 3), 1, List.of(
                List.of("Head", "Other"), List.of(tall, "b"), List.of("c", tall), List.of("d", "e"))))), TypstWriter.PageSetup.DEFAULT);

        assertTrue(typst.contains("table.cell(breakable: true)[" + tall + "], table.cell(breakable: true)[b], \n"), "a quarter of the measure: " + typst);
        assertTrue(typst.contains("[c], [" + tall + "], \n"), "three quarters of it");
        assertTrue(typst.contains("[d], [e], \n"), "and a short row stays whole");
    }

    @Test
    void tableWithoutColsOrHeaderPadsShortRows() {
        String typst = body("""
                |===
                | a | b | c

                | d
                |===
                """);
        assertTrue(typst.contains("columns: (1fr, 1fr, 1fr),"), typst);
        assertFalse(typst.contains("table.header"), typst);
        assertTrue(typst.contains("[a], [b], [c], \n    [d], [], [], \n"), typst);
    }

    @Test
    void tableModelReadsRepeatedColumnSpecs() {
        StoredDocument stored = StoredFixture.parse(HEADER + """
                [cols="3*1"]
                |===
                | a | b | c
                |===
                """);
        DocumentModel model = StoredDocumentModel.of(stored);
        Table table = assertInstanceOf(Table.class, model.blocks().getFirst());
        assertEquals(List.of(1, 1, 1), table.widths());
        assertEquals(0, table.headerRows());
    }

    @Test
    void exampleBlockWithTitle() {
        String typst = body("""
                .Narrative
                ====
                *Medication History* +
                Patient reports.
                ====
                """);
        assertEquals("#ike-example([Narrative])[\n  #strong[Medication History]; \\\n  Patient reports.\n]", typst);
    }

    @Test
    void quoteAndSidebar() {
        String typst = body("""
                ____
                A quotation.
                ____

                ****
                An aside.
                ****
                """);
        assertEquals("#quote(block: true)[\n  A quotation.\n]\n\n#ike-sidebar(none)[\n  An aside.\n]", typst);
    }

    @Test
    void imageIsNamedNotEmbedded() {
        String typst = body("""
                .A figure
                image::images/figure.svg[The figure]
                """);
        assertEquals("#ike-image(\"images/figure.svg\", [A figure])", typst);
    }

    @Test
    void commentsAttributeEntriesAndIncludesPrintNothing() {
        String typst = body("""
                // a comment
                :attr: value
                include::other.adoc[leveloffset=+1]

                Prose.
                """);
        assertEquals("Prose.", typst);
    }

    @Test
    void assemblyFlowsChildrenAtTheirOffsetLevels() {
        StoredDocument parent = StoredFixture.parse("""
                = The Compendium

                == Part One

                include::{topics}/topics/anf/a.adoc[leveloffset=+2]
                """);
        StoredDocument child = StoredFixture.parse("""
                :topic-id: anf-a
                = Child Title

                Child prose.

                == Child Section

                More.
                """);
        long includeLine = parent.lines().stream().filter(l -> l.text().startsWith("include::")).findFirst().orElseThrow().nid();
        DocumentModel model = StoredDocumentModel.of(parent, Map.of(includeLine, new StoredDocumentModel.Child(child, 2)));
        assertEquals("The Compendium", model.title());
        assertEquals(List.of(new Heading(1, "Part One"), new Heading(2, "Child Title"), new Heading(3, "Child Section")),
                List.of(model.blocks().get(0), model.blocks().get(1), model.blocks().get(3)).stream()
                        .map(Heading.class::cast).map(h -> new Heading(h.level(), h.text())).toList(), "every heading carries its block's key besides");
        assertTrue(model.blocks().stream().filter(Heading.class::isInstance).map(Heading.class::cast).noneMatch(h -> h.key().isEmpty()));
        String typst = TypstWriter.write(model, TypstWriter.PageSetup.DEFAULT);
        assertTrue(typst.contains("= Part One\n\n== Child Title\n\nChild prose.\n\n=== Child Section\n\nMore."), typst);
        assertEquals(2, StoredDocumentModel.Child.levelOffset("leveloffset=+2"));
        assertEquals(0, StoredDocumentModel.Child.levelOffset(""));
    }

    @Test
    void listKindFollowsTheMarker() {
        DocumentModel model = StoredDocumentModel.of(StoredFixture.parse(HEADER + "1. one\n2. two\n"));
        ListBlock list = assertInstanceOf(ListBlock.class, model.blocks().getFirst());
        assertEquals(ListBlock.Kind.ORDERED, list.kind());
        assertEquals("one", list.items().getFirst().text());
    }

    @Test
    void fontListsKeepOnlyInstalledFamilies() {
        assertEquals(List.of("B"), TypstPdf.available(List.of("A", "B", "C"), java.util.Optional.of(java.util.Set.of("b"))));
        assertEquals(List.of("A", "B"), TypstPdf.available(List.of("A", "B"), java.util.Optional.empty()));
        assertEquals(List.of("A", "B"), TypstPdf.available(List.of("A", "B"), java.util.Optional.of(java.util.Set.of("z"))));
    }

    @Test
    void fileStemFromNamesAndPaths() {
        assertEquals("compendium", TypstPdf.stem("compendium/src/docs/asciidoc/compendium.adoc"));
        assertEquals("anf-example-medication", TypstPdf.stem("anf-example-medication"));
        assertEquals("document", TypstPdf.stem(""));
        assertEquals("a-b", TypstPdf.stem("a b"));
    }

    @Test
    void theLabelledFormMarksTheTitleEveryTopLevelBlockAndTheEnd() {
        StoredDocument stored = StoredFixture.parse(HEADER + "A paragraph.\n\n== A section\n\nAnother paragraph.\n");
        DocumentModel model = StoredDocumentModel.of(stored);
        String typst = TypstWriter.write(model, TypstWriter.PageSetup.DEFAULT, true);

        String marker = "#metadata(0) <" + TypstWriter.BLOCK_LABEL + ">";
        java.util.regex.Matcher markers = java.util.regex.Pattern.compile("#metadata\\((\\d+)\\) <" + TypstWriter.BLOCK_LABEL + ">").matcher(typst);
        int count = 0;
        while (markers.find()) {
            assertEquals(count++, Integer.parseInt(markers.group(1)), "markers carry their index in order");
        }
        assertEquals(1 + model.blocks().size(), count, "one marker before the title and one before each top-level block");
        assertTrue(typst.contains("#metadata(none) <" + TypstWriter.END_LABEL + ">"), "the end label closes the document");
        assertTrue(typst.indexOf(marker) < typst.indexOf("#ike-title["), "the title's marker precedes it");
        assertFalse(TypstWriter.write(model, TypstWriter.PageSetup.DEFAULT).contains("<" + TypstWriter.BLOCK_LABEL + ">"), "the plain form carries no markers");
        assertFalse(typst.contains(TypstOracle.WORD_RULE), "block labels alone label no words");
        String words = TypstWriter.write(model, TypstWriter.PageSetup.DEFAULT, TypstWriter.Labels.WORDS);
        assertTrue(words.contains(TypstOracle.WORD_RULE), "the oracle's form labels every word");
        assertTrue(words.indexOf(TypstOracle.WORD_RULE) < words.indexOf(marker), "the show rule precedes the first block");
    }

    /** A model with a contents list and a list of tables naming a heading and a table by key, as the stored reading keys them. */
    private static DocumentModel listed() {
        return new DocumentModel("Listed", List.of(
                new DocumentModel.FrontList(DocumentModel.FrontList.Kind.CONTENTS, List.of(
                        new DocumentModel.Entry("1 Intro", 0, "b1"), new DocumentModel.Entry("1.1 Deeper", 1, "b2"))),
                new DocumentModel.FrontList(DocumentModel.FrontList.Kind.TABLES, List.of(new DocumentModel.Entry("Table 1. Costs", 0, "b3"))),
                new Heading(1, "1 Intro", "b1"),
                new DocumentModel.Paragraph("Prose."),
                new Heading(2, "1.1 Deeper", "b2"),
                new Heading(3, "Unlisted", "b9"),
                new Table("Table 1. Costs", List.of(1, 1), 1, List.of(List.of("a", "b")), "b3")),
                new DocumentModel.Lists(true, 2, false, true));
    }

    @Test
    void frontListsAreWrittenAsEntriesThatReadTheirTargetsPages() {
        String typst = TypstWriter.write(listed(), TypstWriter.PageSetup.DEFAULT);

        assertFalse(typst.contains("#outline"), "the lists are the model's entries, not Typst's outline");
        assertTrue(typst.contains(TypstWriter.ENTRY_HELPERS), "the entry and page helpers come with the lists");
        assertTrue(typst.contains("= Contents\n\n#ike-entry(0, repeat[.])[1 Intro][#ike-page(<ike-b1>)]\n#ike-entry(1, repeat[.])[1.1 Deeper][#ike-page(<ike-b2>)]\n\n"), typst);
        assertTrue(typst.contains("= Tables\n\n#ike-entry(0, repeat[.])[Table 1. Costs][#ike-page(<ike-b3>)]\n\n"), typst);
        assertTrue(typst.contains("= 1 Intro <ike-b1>\n\n"), "a heading an entry names carries its label");
        assertTrue(typst.contains(TypstWriter.PAGE_BREAK + "= Contents\n"), "each list opens a page: the title page stands alone");
        assertTrue(typst.contains(TypstWriter.PAGE_BREAK + "= Tables\n"), typst);
        assertTrue(typst.contains(TypstWriter.PAGE_BREAK + "= 1 Intro <ike-b1>\n"), "and so does the body after the last list");
        assertEquals(3, typst.split(java.util.regex.Pattern.quote(TypstWriter.PAGE_BREAK), -1).length - 1, "no other page break");
        assertTrue(TypstWriter.ENTRY_HELPERS.contains("#body#h(1em)#box(width: 1fr"), "a gap of an em before the leader, which Typst breaks the line at when the number has no room");
        assertTrue(TypstWriter.ENTRY_HELPERS.contains("#set par(justify: false)"), "an entry's lines are ragged");
        assertEquals(1, TypstWriter.ENTRY_GAP, "the pages keep the same gap");
        assertTrue(typst.contains("== 1.1 Deeper <ike-b2>\n\n"), typst);
        assertTrue(typst.contains("=== Unlisted\n\n"), "a heading no entry names carries none");
        assertTrue(typst.contains("  kind: table,\n) <ike-b3>\n\n"), "a table an entry names carries its label");
        assertTrue(typst.indexOf("= Contents") < typst.indexOf("= Tables") && typst.indexOf("= Tables") < typst.indexOf("= 1 Intro"), "the lists come first");
    }

    @Test
    void theMeasuredFormPrintsTheEntriesPagesAsFoundAndNoLeaderDots() {
        DocumentModel numbered = listed().withEntryPages(Map.of("b1", 2, "b2", 3, "b3", 4));
        String words = TypstWriter.write(numbered, TypstWriter.PageSetup.DEFAULT, TypstWriter.Labels.WORDS);

        String tail = "#metadata(" + TypstOracle.TAIL + ")<" + TypstWriter.TAIL_LABEL + ">";
        assertTrue(words.contains("#ike-entry(0, [])[1 Intro][#ike-page-room([2])" + tail + "]\n#ike-entry(1, [])[1.1 Deeper][#ike-page-room([3])" + tail + "]\n"), words);
        assertTrue(words.contains("#ike-entry(0, [])[Table 1. Costs][#ike-page-room([4])" + tail + "]\n"), words);
        assertTrue(TypstOracle.QUERY.contains("<" + TypstWriter.TAIL_LABEL + ">"), "the oracle asks where each tail ended");
        assertFalse(TypstWriter.write(numbered, TypstWriter.PageSetup.DEFAULT).contains(TypstWriter.TAIL_LABEL), "the printed form carries no tail label");
        assertTrue(words.contains(TypstWriter.PAGE_BREAK + "#metadata("), "the measured form breaks the pages where the printed one does, before the block's label");
        assertFalse(words.contains("#ike-page(<"), "a measured source holds the room of the number the last pass found, and no digits the oracle could read");
        assertTrue(words.contains("= 1 Intro <ike-b1>"), "the targets keep their labels, so the printed form reads the same document");
        String blocks = TypstWriter.write(numbered, TypstWriter.PageSetup.DEFAULT, TypstWriter.Labels.BLOCKS);
        assertTrue(blocks.contains("[1 Intro][#ike-page(<ike-b1>)]"), "the block-labelled form is the printed one, with its leader and Typst's own page");
        assertEquals(List.of(2, 3, 4), numbered.entries().stream().map(DocumentModel.Entry::page).toList());
        assertEquals(List.of(0, 0, 0), listed().entries().stream().map(DocumentModel.Entry::page).toList(), "unknown until a pass finds them");
    }

    @Test
    void aStoredDocumentThatAsksForListsPrintsThemFromItsCaptions() {
        StoredDocument stored = StoredFixture.parse("""
                :topic-id: t-lists
                = Listed
                :toc:
                :toclevels: 1
                :list-of-tables:
                :sectnums:

                == Intro

                Prose.

                === Deeper

                .Costs
                |===
                | a | b
                |===
                """);
        network.ike.komet.document.Captions captions = network.ike.komet.document.Captions.of(
                network.ike.komet.document.DocumentAssembly.single(stored, "t.adoc"), b -> false, 3);
        DocumentModel model = StoredDocumentModel.of(stored, Map.of(), StoredDocumentModel.Figures.captioned(captions));

        DocumentModel.FrontList contents = assertInstanceOf(DocumentModel.FrontList.class, model.blocks().get(0));
        assertEquals(DocumentModel.FrontList.Kind.CONTENTS, contents.kind());
        assertEquals(List.of("1 Intro"), contents.entries().stream().map(DocumentModel.Entry::text).toList(), "to the depth asked");
        DocumentModel.FrontList tables = assertInstanceOf(DocumentModel.FrontList.class, model.blocks().get(1));
        assertEquals(List.of("Table 1. Costs"), tables.entries().stream().map(DocumentModel.Entry::text).toList());
        Heading intro = assertInstanceOf(Heading.class, model.blocks().get(2));
        assertEquals(contents.entries().getFirst().target(), intro.key(), "the entry names the heading by the key the heading carries");
        Table costs = model.blocks().stream().filter(Table.class::isInstance).map(Table.class::cast).findFirst().orElseThrow();
        assertEquals(tables.entries().getFirst().target(), costs.key());
        assertEquals(java.util.Set.of(intro.key(), costs.key()), model.targets());
        String typst = TypstWriter.write(model, TypstWriter.PageSetup.DEFAULT);
        assertTrue(typst.contains("= 1 Intro <ike-" + intro.key() + ">"), typst);
        assertTrue(typst.contains("== 1.1 Deeper\n\n"), "the deeper heading is numbered but not listed, so not labelled");
        assertTrue(StoredDocumentModel.of(stored).entries().isEmpty(), "with no captions to read, a document lists nothing");
        assertTrue(StoredDocumentModel.key(-42).matches("[A-Za-z0-9_-]+"), "a key spells as a Typst label: " + StoredDocumentModel.key(-42));
    }

    @Test
    void theBundledNotoFamiliesComeFirst() {
        assertEquals(DocumentFonts.SERIF, TypstWriter.PageSetup.DEFAULT.bodyFonts().getFirst());
        assertEquals(DocumentFonts.MONO, TypstWriter.PageSetup.DEFAULT.monoFonts().getFirst());
        TypstWriter.PageSetup setup = TypstPdf.setup(PrintSettings.DEFAULT, java.util.Optional.of(java.util.Set.of("georgia")));
        assertEquals(List.of(DocumentFonts.SERIF, "Georgia"), setup.bodyFonts(), "the bundled family is always available; the rest only when installed");
    }
}

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
        int title = typst.indexOf("#ike-title(\"");
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
        assertTrue(typst.contains("header: context if not ike-opening() {"), "a running head, but on a page that opens something");
        assertTrue(typst.contains("ike-fit(\"The Fixture Title\", size.width - measure(current).width - 1.5em.to-absolute()), current))"),
                "title left, cut to the room the section leaves it; section right");
        assertTrue(typst.contains("footer: context align(right, text(size: 0.8em, fill: luma(70), counter(page).display(\"1\")))"));
        // "The" tied to the word it leads, the last two words tied, so the title never ends on a lone word
        assertTrue(typst.contains("#ike-title(\"The\u00A0Fixture\u00A0Title\")[The\u00A0Fixture\u00A0Title]"), typst);
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
    void theTitleIsDisplayTypeCenteredBalancedAndNeverHyphenated() {
        String typst = TypstWriter.write(new DocumentModel(
                "Applying Decision Theory to Create Transparent and Reproducible Regulatory Decisions", List.of()), TypstWriter.PageSetup.DEFAULT);
        // tied after its short words and between its last two, so no line ends on "to" or "and", and none is a lone word
        assertTrue(typst.contains("#ike-title(\"Applying Decision Theory to\u00A0Create Transparent and\u00A0Reproducible Regulatory\u00A0Decisions\")"), typst);
        assertTrue(typst.contains("set text(size: 1.8em, weight: \"bold\", hyphenate: false)"), "never hyphenated");
        assertTrue(typst.contains("set par(justify: false, linebreaks: \"simple\")"), "ragged, broken line by line");
        assertTrue(typst.contains("if measure(at(mid)).height <= lines + 0.01pt { hi = mid } else { lo = mid }"), "the narrowest measure that keeps the line count");
        assertTrue(typst.contains("align(center, at(hi))"), "centered");
        assertTrue(typst.contains("[#metadata(none) <" + TypstWriter.OPENER_LABEL + ">]"), "its page opens the document");
    }

    @Test
    void theAuthorAndTheDateStandCenteredBeneathTheTitle() {
        StoredDocument stored = StoredFixture.parse("""
                = A Book
                :doctype: book
                :author: Keith E. Campbell, MD, PhD
                :revdate: 2026-11-10

                Hello.
                """);
        DocumentModel model = StoredDocumentModel.of(stored);
        assertEquals(new DocumentModel.Byline("Keith E. Campbell, MD, PhD", "November 10, 2026"), model.byline(), "an ISO date is spelled out");
        String typst = TypstWriter.write(model, TypstWriter.PageSetup.DEFAULT);
        assertTrue(typst.contains("#ike-title(\"A\u00A0Book\", author: [Keith E. Campbell, MD, PhD], date: [November 10, 2026])[A\u00A0Book]"), typst);
        assertTrue(typst.contains("#let ike-title(words, body, author: none, date: none)"), typst);
        assertTrue(typst.contains("block(above: 1.4em, below: 0pt, width: 100%, align(center, text(size: 1.2em, author)))"), "the author, centered and smaller");
        assertTrue(typst.contains("align(center, text(size: 1em, date))"), "the date beneath it");
        assertTrue(typst.contains("if author != none { 0.5em } else { 1.4em }"), typst);
    }

    @Test
    void theHeadersImplicitAuthorAndRevisionLinesNameTheAuthorAndTheDate() {
        StoredDocument stored = StoredFixture.parse("""
                = A Report
                Ada Lovelace <ada@example.org>; Charles Babbage; Mary Somerville
                v2.1, 3 March 1843: the notes

                Hello.
                """);
        StoredDocumentModel.Header header = StoredDocumentModel.header(stored);
        assertEquals("Ada Lovelace, Charles Babbage and Mary Somerville", header.author(), "addresses dropped, the authors joined as one line");
        assertEquals("3 March 1843", header.date(), "a date that is not ISO prints as written");
        assertEquals(new DocumentModel.Byline("Ada Lovelace, Charles Babbage and Mary Somerville", "3 March 1843"), StoredDocumentModel.of(stored).byline());
        assertEquals("A and B", StoredDocumentModel.authors("A; B"));

        DocumentModel bare = StoredDocumentModel.of(StoredFixture.parse(HEADER + "Hello.\n"));
        assertEquals(DocumentModel.Byline.NONE, bare.byline(), "no author named and no commit: nothing beneath the title");
        assertTrue(TypstWriter.write(bare, TypstWriter.PageSetup.DEFAULT).contains("#ike-title(\"The\u00A0Fixture\u00A0Title\")["), "nor in the source");
    }

    @Test
    void headingsAreFlushLeftRaggedAndNeverHyphenated() {
        String typst = TypstWriter.write(new DocumentModel("T", List.of(new Heading(1, "Transparent Regulatory Decisions"))), TypstWriter.PageSetup.DEFAULT);
        assertTrue(typst.contains("#show heading: set text(weight: \"bold\", hyphenate: false)"), typst);
        assertTrue(typst.contains("#show heading: set par(justify: false)"), typst);
    }

    @Test
    void aPageThatOpensTheTitleOrAChapterCarriesNoRunningHead() {
        String typst = TypstWriter.write(new DocumentModel("T", List.of(new Heading(1, "One"))), TypstWriter.PageSetup.DEFAULT);
        assertTrue(typst.contains(TypstWriter.OPENING_HELPER), "the helper asks where the title and the level-1 headings stand");
        assertTrue(typst.contains("query(selector(heading.where(level: 1)).or(<ike-opener>))"), typst);
        assertTrue(typst.indexOf(TypstWriter.OPENING_HELPER) < typst.indexOf("#set page("), "defined before the page uses it");
        assertTrue(typst.contains("header: context if not ike-opening() {"), typst);
        PrintSettings bare = new PrintSettings(PageSize.LETTER, PageOrientation.PORTRAIT, MarginPreset.NORMAL,
                DocumentTheme.DEFAULT, FurnitureVisibility.HIDDEN, PageNumberPlacement.FOOTER_CENTER);
        assertFalse(TypstWriter.write(new DocumentModel("T", List.of()), TypstPdf.setup(bare)).contains("ike-opening"),
                "no head, nothing to stand down");
    }

    @Test
    void headerFolioSharesTheRunningHeadBand() {
        PrintSettings settings = PrintSettings.DEFAULT.withPageNumbers(PageNumberPlacement.HEADER_RIGHT);
        String typst = TypstWriter.write(StoredDocumentModel.of(StoredFixture.parse(HEADER + "Hello.\n")), TypstPdf.setup(settings));
        assertTrue(typst.contains("#h(1em) #current], counter(page).display(\"1\"))"));
        // on a page that opens something the head is not drawn, and its folio drops to the foot
        assertTrue(typst.contains("footer: context if ike-opening() { align(center, text(size: 0.8em, fill: luma(70), counter(page).display(\"1\"))) },"), typst);
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
    void aLineUnderOrThroughAPhraseAndAColourBehindOneAreWritten() {
        assertEquals("An #underline[underlined]; and a #strike[struck]; and a #highlight(fill: rgb(\"#fff3a3\"))[marked]; word.", body("""
                An [.underline]#underlined# and a [.line-through]#struck# and a #marked# word.
                """));
        assertEquals("#underline[#strong[bold];];#underline[ too];", body("""
                [.underline]#*bold* too#
                """), "a phrase's spans are runs of their own, each under the line");
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
                      table.cell(align: left + bottom)[Element], table.cell(align: left + bottom)[Topic], \n\
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
    void aCellOverSeveralColumnsIsWrittenAsOneCellWithItsSpan() {
        DocumentModel model = StoredDocumentModel.of(StoredFixture.parse("""
                [cols="4,2,2,2", options="header"]
                |===
                | Input | Low | Default | High
                4+| *The price of health*
                | A year of healthy life | 50 | 100 | 150
                2+| Two of them | x | y
                |===
                """));
        Table table = (Table) model.blocks().getFirst();
        assertEquals(List.of(List.of("Input", "Low", "Default", "High"), List.of("*The price of health*"),
                List.of("A year of healthy life", "50", "100", "150"), List.of("Two of them", "x", "y")), table.rows());
        assertEquals(List.of(4), table.spansOf(1));
        assertEquals(List.of(10), table.weightsOf(1), "a cell over every column is as wide as the table");
        assertEquals(List.of(6, 2, 2), table.weightsOf(3));
        assertEquals(List.of(4, 2, 2, 2), table.weightsOf(2));

        String typst = TypstWriter.write(model, TypstWriter.PageSetup.DEFAULT);
        assertTrue(typst.contains("    table.cell(colspan: 4)[#strong[The price of health];], \n"), typst);
        assertTrue(typst.contains("    table.cell(colspan: 2)[Two of them], [x], [y], \n"), "the cells after a span keep their columns: " + typst);
        assertTrue(typst.contains("    [A year of healthy life], [50], [100], [150], \n"));

        List<LayoutOracle.Unit> rows = ModelUnits.of(model, 11).stream().filter(u -> u.kind() == LayoutOracle.Kind.TABLE_ROW).toList();
        assertEquals("The price of health", rows.get(1).text(), "the row is its one cell: no bar, no empty place");
        assertEquals(List.of(10), rows.get(1).columns());
        assertEquals(List.of(6, 2, 2), rows.get(3).columns());
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
        assertTrue(typst.indexOf(marker) < typst.indexOf("#ike-title(\""), "the title's marker precedes it");
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
        assertTrue(typst.contains("= Contents\n\n#let ike-marks = (\"0\", \"0.0\", )\n#ike-entry(ike-marks, 0, \"1\", true, <ike-b1>)[Intro][#ike-page(<ike-b1>)]\n"
                + "#ike-entry(ike-marks, 1, \"1.1\", true, <ike-b2>)[Deeper][#ike-page(<ike-b2>)]\n\n"), typst);
        assertTrue(typst.contains("= Tables\n\n#let ike-marks = (\"Table 0.\", )\n#ike-entry(ike-marks, 0, \"Table 1.\", true, <ike-b3>)[Costs][#ike-page(<ike-b3>)]\n\n"), typst);
        assertTrue(typst.contains("= 1 Intro <ike-b1>\n\n"), "a heading an entry names carries its label");
        assertTrue(typst.contains(TypstWriter.PAGE_BREAK + "= Contents\n"), "each list opens a page: the title page stands alone");
        assertTrue(typst.contains(TypstWriter.PAGE_BREAK + "= Tables\n"), typst);
        assertTrue(typst.contains(TypstWriter.PAGE_BREAK + TypstWriter.BODY_START + "= 1 Intro <ike-b1>\n"), "and so does the body after the last list");
        assertEquals(3, typst.split(java.util.regex.Pattern.quote(TypstWriter.PAGE_BREAK), -1).length - 1, "no other page break");
        String helpers = TypstWriter.ENTRY_HELPERS;
        assertTrue(helpers.contains("grid(columns: (columns.at(depth), 1fr, calc.max(measure(text(weight: weight(0))[000]).width, measure(text(weight: weight(depth), page)).width)),"),
                "the mark and the page number have columns of their own: the number cannot wrap away from its entry, and has room for the list's heaviest digits");
        assertTrue(helpers.contains("let weight(d) = if marks.len() > 1 and d == 0 { \"bold\" } else { \"regular\" }"),
                "the outermost entries of a list that has levels are bold");
        assertTrue(helpers.contains("set text(weight: weight(depth))"), "mark, text and page number alike");
        assertTrue(helpers.contains("above: if chief { 1.1em } else { 0.65em }, below: 0.65em"), "a chief entry keeps its own space above");
        assertTrue(helpers.contains("let columns = marks.enumerate().map(((d, m)) => if m == \"\" { 0pt } else { measure(text(weight: weight(d), m)).width + 0.75em.to-absolute() })"),
                "a depth's column of marks is as wide as its widest mark and a gap; a depth with no mark has none");
        assertTrue(helpers.contains("let edge = columns.slice(0, depth).map(c => if c > 0pt { c } else { 1em.to-absolute() }).sum(default: 0pt)"),
                "a depth begins where the text of the depth above does, or a step in under a depth with no marks");
        assertTrue(helpers.contains("align: (left + top, left + top, bottom + right)"), "the number stands on the text's last line, against the right edge");
        assertTrue(helpers.contains("if mark == none { [] } else { box(width: columns.at(depth) - 0.75em.to-absolute(), align(right, mark)) },"),
                "a mark ends a gap before its text; an entry with no mark leaves its column empty, and its text begins where the others' do");
        assertTrue(helpers.contains("box(width: 100% - 0.4em - 2em, par(justify: false,"),
                "an entry's lines are ragged, in a measure that keeps the gap and the least leader clear, every line at the text's left edge");
        assertFalse(helpers.contains("hanging-indent"), "no line of an entry's text hangs");
        assertTrue(helpers.contains("if target != none and query(target).len() > 0 { link(target, row) } else { row })"),
                "the whole entry is a link to what it names, where the document holds it");
        assertTrue(helpers.contains("text(hyphenate: false)"), "an entry is never hyphenated");
        assertTrue(helpers.contains("move(dx: 0.4em, box(width: 100% + 2em, align(right, repeat(justify: false, gap: 0.15em)[.])))"),
                "the leader runs on over the room the text kept clear, its dots a fixed step apart from its right edge");
        assertTrue(helpers.contains("text(weight: \"regular\", ike-leader)"), "the dots are never bold");
        assertFalse(helpers.matches("(?s).*\\b(MARK_GAP|DOT_GAP|GAP|LEAD|GUTTER|STEP|GROUP|ROOM)\\b.*"), "every length is written out");
        assertEquals(List.of(0.4, 2.0, 0.4, 0.15, 0.75, 1.0, 1.1), List.of(TypstWriter.ENTRY_GAP, TypstWriter.ENTRY_LEAD, TypstWriter.ENTRY_GUTTER,
                TypstWriter.ENTRY_DOT_GAP, TypstWriter.ENTRY_MARK_GAP, TypstWriter.ENTRY_STEP, TypstWriter.ENTRY_GROUP), "the pages keep the same lengths");
        assertTrue(typst.contains("== 1.1 Deeper <ike-b2>\n\n"), typst);
        assertTrue(typst.contains("=== Unlisted\n\n"), "a heading no entry names carries none");
        assertTrue(typst.contains("  kind: table,\n) <ike-b3>\n\n"), "a table an entry names carries its label");
        assertTrue(typst.indexOf("= Contents") < typst.indexOf("= Tables") && typst.indexOf("= Tables") < typst.indexOf("= 1 Intro"), "the lists come first");
    }

    @Test
    void aDocumentWithFrontMatterNumbersItInRomanShowsNoNumberOnItsTitlePageAndStartsTheBodyAtOne() {
        String typst = TypstWriter.write(listed(), TypstWriter.PageSetup.DEFAULT);

        String helper = TypstWriter.folioHelper(true);
        assertTrue(typst.contains(helper), "the folio is reckoned by a helper of the document's own");
        assertTrue(typst.indexOf(helper) < typst.indexOf("#set page("), "defined before the page setup that draws it");
        assertTrue(helper.contains("if at > 1 { numbering(\"i\", at) } else { \"\" }"), "roman before the body, and none on the first page, the title page");
        assertTrue(helper.contains("numbering(\"1\", at - body.first().location().page() + 1)"), "arabic from 1 at the body's first page");
        assertTrue(helper.contains("query(<ike-body>)"), "counted from where the body begins");
        assertTrue(helper.contains("[#metadata(folio) <ike-folio>#folio]"), "each page's folio can be asked of Typst");
        assertTrue(typst.contains("footer: context align(center, text(size: 0.8em, fill: luma(70), ike-folio)),"), "the footer draws the folio");
        assertFalse(typst.contains("counter(page).display("), "no page prints the counter's own number");

        assertEquals("#metadata(none) <ike-body>\n#counter(page).update(1)\n", TypstWriter.BODY_START);
        assertEquals(1, typst.split(java.util.regex.Pattern.quote(TypstWriter.BODY_START), -1).length - 1, "the body is opened once");
        assertTrue(typst.indexOf("= Tables") < typst.indexOf(TypstWriter.BODY_START) && typst.indexOf(TypstWriter.BODY_START) < typst.indexOf("= 1 Intro"),
                "after the last list and before the body's first block, so an entry's page is its target's folio");
    }

    @Test
    void theFolioInTheRunningHeadsBandIsTheSameFolio() {
        TypstWriter.PageSetup header = new TypstWriter.PageSetup("us-letter", false, 72, 72, 72, 72, List.of("Noto Serif"), List.of("Noto Sans Mono"), 12, true, TypstWriter.Folio.HEADER_RIGHT);
        String typst = TypstWriter.write(listed(), header);
        assertTrue(typst.contains("#h(1em) #current], ike-folio)"), typst);
        assertTrue(typst.contains("footer: context if ike-opening() { align(center, text(size: 0.8em, fill: luma(70), ike-folio)) },"),
                "only a page that opens something has its folio at the foot");

        TypstWriter.PageSetup alone = new TypstWriter.PageSetup("us-letter", false, 72, 72, 72, 72, List.of("Noto Serif"), List.of("Noto Sans Mono"), 12, false, TypstWriter.Folio.HEADER_RIGHT);
        assertTrue(TypstWriter.write(listed(), alone).contains("    align(right, ike-folio)\n"));

        TypstWriter.PageSetup none = new TypstWriter.PageSetup("us-letter", false, 72, 72, 72, 72, List.of("Noto Serif"), List.of("Noto Sans Mono"), 12, true, TypstWriter.Folio.NONE);
        String unnumbered = TypstWriter.write(listed(), none);
        assertFalse(unnumbered.contains("ike-folio"), "no folio asked for, no helper");
        assertTrue(unnumbered.contains(TypstWriter.BODY_START), "the entries' pages are still the body's");
    }

    @Test
    void frontMatterWithNoTitleShowsARomanNumberOnItsFirstPage() {
        DocumentModel untitled = new DocumentModel("", listed().blocks(), listed().lists());
        String typst = TypstWriter.write(untitled, TypstWriter.PageSetup.DEFAULT);

        assertFalse(untitled.titlePage());
        assertTrue(typst.contains(TypstWriter.folioHelper(false)), "the first page is a list's, not a title page");
        assertTrue(TypstWriter.folioHelper(false).contains("    numbering(\"i\", at)\n"));
        assertFalse(TypstWriter.folioHelper(false).contains("at > 1"));
    }

    @Test
    void aDocumentWithNoFrontMatterIsWrittenAsItAlwaysWas() {
        DocumentModel plain = new DocumentModel("Plain", List.of(new Heading(1, "One"), new DocumentModel.Paragraph("Prose.")));
        String typst = TypstWriter.write(plain, TypstWriter.PageSetup.DEFAULT);

        assertFalse(plain.frontMatter());
        assertFalse(plain.titlePage());
        assertTrue(typst.contains("footer: context align(center, text(size: 0.8em, fill: luma(70), counter(page).display(\"1\"))),"), "arabic from the first page");
        assertFalse(typst.contains("ike-folio"), typst);
        assertFalse(typst.contains("ike-body"), "no body to open: the title stands at the top of page 1");
        assertFalse(typst.contains("counter(page).update"));
        assertFalse(typst.contains("#pagebreak"));
        assertEquals(TypstWriter.preamble("Plain", TypstWriter.PageSetup.DEFAULT), TypstWriter.preamble("Plain", TypstWriter.PageSetup.DEFAULT, false, false));

        DocumentModel asked = new DocumentModel("Plain", plain.blocks(), new DocumentModel.Lists(true, 2, true, true));
        assertFalse(asked.frontMatter(), "a header that asks for lists with nothing to list has no front matter");
        assertEquals(typst, TypstWriter.write(asked, TypstWriter.PageSetup.DEFAULT));
        assertTrue(listed().frontMatter());
        assertTrue(listed().titlePage());
    }

    @Test
    void anEntrysShortLastWordIsWrittenTiedToTheWordBeforeIt() {
        DocumentModel model = new DocumentModel("Tied", List.of(
                new DocumentModel.FrontList(DocumentModel.FrontList.Kind.CONTENTS, List.of(
                        new DocumentModel.Entry("1 The Test the FDA Did Not *Ask* For", 0, "b1"), new DocumentModel.Entry("2 What Happens to Patients", 0, "b2"))),
                new Heading(1, "1 The Test the FDA Did Not *Ask* For", "b1"),
                new Heading(1, "2 What Happens to Patients", "b2")),
                new DocumentModel.Lists(true, 2, false, false));

        String typst = TypstWriter.write(model, TypstWriter.PageSetup.DEFAULT);

        assertTrue(typst.contains("#ike-entry(ike-marks, 0, \"1\", true, <ike-b1>)[The Test the FDA Did Not #strong[Ask];\u00a0For][#ike-page(<ike-b1>)]\n"),
                "a no-break space before the short last word, after a span as after plain text: " + typst);
        assertTrue(typst.contains("#ike-entry(ike-marks, 0, \"2\", true, <ike-b2>)[What Happens to Patients][#ike-page(<ike-b2>)]\n"), "a longer last word is left alone");
        assertTrue(typst.contains("= 1 The Test the FDA Did Not #strong[Ask]; For <ike-b1>\n"), "the heading itself is not tied");
    }

    @Test
    void theMeasuredFormPrintsTheEntriesPagesAsFoundAndNoLeaderDots() {
        DocumentModel numbered = listed().withEntryPages(Map.of("b1", 2, "b2", 3, "b3", 4));
        String words = TypstWriter.write(numbered, TypstWriter.PageSetup.DEFAULT, TypstWriter.Labels.WORDS);

        assertTrue(words.contains("#ike-entry(ike-marks, 0, \"1\", false, none)[Intro][#ike-page-room([2])]\n#ike-entry(ike-marks, 1, \"1.1\", false, none)[Deeper][#ike-page-room([3])]\n"), words);
        assertTrue(words.contains("#ike-entry(ike-marks, 0, \"Table 1.\", false, none)[Costs][#ike-page-room([4])]\n"), words);
        assertFalse(TypstWriter.write(numbered, TypstWriter.PageSetup.DEFAULT).contains("ike-page-room(["), "the printed form asks the page counter");
        assertTrue(words.contains(TypstWriter.PAGE_BREAK + "#metadata(1) <ike-block>\n= Contents"), "the measured form breaks the pages where the printed one does, before the block's label");
        assertTrue(words.contains(TypstWriter.PAGE_BREAK + TypstWriter.BODY_START + "#metadata(3) <ike-block>\n"), "and opens the body where the printed one does");
        assertFalse(words.contains("#ike-page(<"), "a measured source holds the room of the number the last pass found, and no digits the oracle could read");
        assertTrue(words.contains("= 1 Intro <ike-b1>"), "the targets keep their labels, so the printed form reads the same document");
        String blocks = TypstWriter.write(numbered, TypstWriter.PageSetup.DEFAULT, TypstWriter.Labels.BLOCKS);
        assertTrue(blocks.contains("\"1\", true, <ike-b1>)[Intro][#ike-page(<ike-b1>)]"), "the block-labelled form is the printed one, with its leader and Typst's own page");
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

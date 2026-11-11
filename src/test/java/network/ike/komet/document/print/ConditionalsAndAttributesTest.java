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
import network.ike.komet.document.GitDocumentMaterializer.StoredLine;
import network.ike.komet.document.RegionFixture;
import network.ike.komet.document.print.DocumentModel.FrontList;
import network.ike.komet.document.print.DocumentModel.Heading;
import network.ike.komet.document.print.DocumentModel.Paragraph;
import network.ike.komet.document.print.DocumentModel.Table;
import network.ike.komet.document.print.LayoutOracle.Kind;
import network.ike.komet.document.print.LayoutOracle.Unit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A book's conditional directives and attribute references, through the assembly, the print
 * model, the Typst source and the model's units (IKE-Network/ike-issues#1351), written as the
 * regulatory book writes them: the book sets an attribute that its topics test, a topic keeps a
 * paragraph for another book, and an estimate carries a note that is a dagger on the web page
 * and a footnote on paper. The documents are built the way the materializer stores them, with
 * no store.
 */
class ConditionalsAndAttributesTest {

    private static final String BOOK = """
            = Regulatory Decisions
            :toc:
            :list-of-tables:
            :sectnums:
            :topics: .

            :regulatory-decision-analysis:

            // Estimate notes: a dagger with a tooltip on screen, a numbered page-foot footnote in print
            ++++
            <style>
            sup.ours-mark { font-size: 0.75em; }
            span.ours-note { display: none; }
            @media print {
              sup.ours-mark { display: none; }
              span.ours-note { display: inline; float: footnote; font-size: 9pt; }
            }
            </style>
            ++++

            include::{topics}/topics/tests.adoc[leveloffset=+1]

            ifdef::backend-pdf,backend-docbook5[]
            [index]
            == Index
            endif::[]
            """;

    private static final String TOPIC = """
            :topic-id: rda-tests
            :ours: pass:[<sup class="ours-mark" title="Our estimate.">†</sup><span class="ours-note">Our estimate.</span>]
            :fn: pass:[<sup class="ours-mark" title="our estimate">†</sup><span class="ours-note">]
            :fnend: pass:[</span>]

            = Which Tests to Require

            Two reviews of the same inputs reach the same requirement.

            ifdef::compendium[]
            The public record is set out in the compendium.
            endif::[]
            ifndef::compendium,regulatory-decision-analysis[]
            NOTE: The public record is set out in the companion document.
            endif::[]

            A wear test stops a sound device 1 time in 20.{ours} The accuracies
            are estimates.{fn}The accuracies and the cost
            of a redesign are our estimates.{fnend}

            == The quantities{fn}Each is an estimate.{fnend}

            .The quantities behind the tree{fn}The prior is our estimate.{fnend}
            [cols="1,1",options="header"]
            |===
            | Quantity | Value{fn}In dollars.{fnend}
            | Reply {startsb}1{endsb} | 100
            |===
            """;

    private static DocumentAssembly assembly() {
        StoredDocument host = RegionFixture.parse(BOOK, 0);
        StoredDocument topic = RegionFixture.parse(TOPIC, 10_000);
        DocumentAssembly assembly = DocumentAssembly.of(host, "docs/book.adoc",
                path -> path.equals("docs/topics/tests.adoc") ? Optional.of(topic) : Optional.empty());
        assertTrue(assembly.unresolved().isEmpty(), "the include is followed");
        return assembly;
    }

    private static DocumentModel model() {
        DocumentAssembly assembly = assembly();
        return StoredDocumentModel.of(assembly, StoredDocumentModel.Figures.captioned(Captions.of(assembly, b -> false, 3)));
    }

    private static List<String> texts(StoredDocument document) {
        return document.lines().stream().map(StoredLine::text).toList();
    }

    @Test
    void everyLineStaysALineOfTheAssemblyAndTheReadDocumentLeavesTheConditionalsOut() {
        DocumentAssembly assembly = assembly();
        List<String> all = assembly.lines().stream().map(StoredLine::text).toList();
        assertTrue(all.contains("ifdef::compendium[]") && all.contains("The public record is set out in the compendium.") && all.contains("== Index"),
                "the source keeps every line: each is one a person may come to change");

        List<String> topic = texts(assembly.read(assembly.children().getFirst()));
        assertFalse(topic.stream().anyMatch(line -> line.startsWith("ifdef::") || line.startsWith("ifndef::") || line.startsWith("endif::")),
                "a directive is not read");
        assertFalse(topic.contains("The public record is set out in the compendium."), "compendium is not set");
        assertFalse(topic.contains("NOTE: The public record is set out in the companion document."),
                "the book sets regulatory-decision-analysis, and the topic it includes is read with it set");
        assertTrue(topic.contains("Two reviews of the same inputs reach the same requirement."));

        List<String> host = texts(assembly.read(assembly.host()));
        assertFalse(host.contains("== Index") || host.contains("[index]"), "the index is kept for a backend that makes one");
        assertTrue(host.contains(":toc:"));
    }

    @Test
    void aTopicReadAloneReadsWhatTheBookLeftOut() {
        StoredDocument topic = RegionFixture.parse(TOPIC, 0);
        DocumentAssembly alone = DocumentAssembly.single(topic, "docs/topics/tests.adoc");
        assertTrue(texts(alone.read(alone.host())).contains("NOTE: The public record is set out in the companion document."),
                "nothing sets regulatory-decision-analysis here");
    }

    @Test
    void aConditionalThatHoldsItsTextIsAParagraphOfThatText() {
        StoredDocument host = RegionFixture.parse("""
                = Book
                :book:

                Before.

                ifdef::book[Shown in the *book*, in {backend}.]

                ifndef::book[Shown outside it.]

                A paragraph with a condition in it,
                ifdef::book[which the book reads,]
                and its last line.
                """, 0);
        DocumentAssembly assembly = DocumentAssembly.single(host, "docs/book.adoc");
        DocumentModel model = StoredDocumentModel.of(assembly, StoredDocumentModel.Figures.NONE);
        assertEquals(List.of(new Paragraph("Before."), new Paragraph("Shown in the *book*, in komet."),
                        new Paragraph("A paragraph with a condition in it, which the book reads, and its last line.")),
                model.blocks(), "a directive on a line of its own prints nothing, but one that holds its text prints the text");
        assertTrue(assembly.holds(assembly.host(), assembly.read(assembly.host()).blocks().values().stream()
                .filter(b -> b.members().size() == 1 && assembly.read(assembly.host()).line(b.members().getFirst())
                        .filter(l -> l.text().startsWith("ifdef::book[Shown")).isPresent()).findFirst().orElseThrow()),
                "it is a whole block, and is edited as its source line");
    }

    @Test
    void anIncludeThatAConditionalLeavesOutIsNotFollowed() {
        StoredDocument host = RegionFixture.parse("""
                = Book

                ifdef::appendix[]
                include::topics/tests.adoc[]
                endif::[]

                The end.
                """, 0);
        StoredDocument topic = RegionFixture.parse(TOPIC, 10_000);
        DocumentAssembly assembly = DocumentAssembly.of(host, "docs/book.adoc",
                path -> path.equals("docs/topics/tests.adoc") ? Optional.of(topic) : Optional.empty());
        assertTrue(assembly.children().isEmpty());
        assertTrue(assembly.includes().isEmpty(), "it is not an include that failed: it is not read");
    }

    @Test
    void thePrintModelHasNoDirectiveAndNoParagraphAConditionalLeftOut() {
        DocumentModel model = model();
        List<String> prose = model.blocks().stream().filter(Paragraph.class::isInstance).map(b -> ((Paragraph) b).text()).toList();
        assertEquals(List.of("Two reviews of the same inputs reach the same requirement.",
                        "A wear test stops a sound device 1 time in 20.footnote:[Our estimate.] The accuracies are estimates."
                                + "footnote:[The accuracies and the cost of a redesign are our estimates.]"),
                prose, "the paragraphs that are read, each note where it was written");
        assertFalse(model.blocks().stream().filter(Heading.class::isInstance).anyMatch(h -> ((Heading) h).text().contains("Index")));
    }

    @Test
    void aNotePrintsWhereItIsWrittenAndNotInTheFrontLists() {
        DocumentModel model = model();
        List<FrontList> lists = model.blocks().stream().filter(FrontList.class::isInstance).map(FrontList.class::cast).toList();
        assertEquals(2, lists.size());
        assertEquals(List.of("1 Which Tests to Require", "1.1 The quantities"), lists.get(0).entries().stream().map(DocumentModel.Entry::text).toList(),
                "the contents name the section, without its note, and no index");
        assertEquals(List.of("Table 1. The quantities behind the tree"), lists.get(1).entries().stream().map(DocumentModel.Entry::text).toList());

        Heading section = model.blocks().stream().filter(Heading.class::isInstance).map(Heading.class::cast)
                .filter(h -> h.text().startsWith("1.1")).findFirst().orElseThrow();
        assertEquals("1.1 The quantitiesfootnote:[Each is an estimate.]", section.text(), "the heading keeps its note on its own page");
        Table table = model.blocks().stream().filter(Table.class::isInstance).map(Table.class::cast).findFirst().orElseThrow();
        assertEquals("Table 1. The quantities behind the treefootnote:[The prior is our estimate.]", table.title());
        assertEquals(List.of(List.of("Quantity", "Valuefootnote:[In dollars.]"), List.of("Reply [1]", "100")), table.rows(),
                "a cell's references are read too");
    }

    @Test
    void theTypstSourceSetsEachNoteOnceAndNoneInAnEntry() {
        String typst = TypstWriter.write(model(), TypstWriter.PageSetup.DEFAULT);
        assertFalse(typst.contains("endif") || typst.contains("ifdef") || typst.contains("{fn}") || typst.contains("{ours}") || typst.contains("ours-note"),
                "no directive, no reference and no markup of the web page reaches the page");
        for (String note : List.of("Our estimate.", "The accuracies and the cost of a redesign are our estimates.", "Each is an estimate.",
                "The prior is our estimate.")) {
            assertEquals(1, typst.split(java.util.regex.Pattern.quote("#footnote[" + note + "]"), -1).length - 1, note);
        }
        // the header row is set again on every page the table runs on to: its note is given once, and the mark repeated
        assertTrue(java.util.regex.Pattern.compile("\\[Value#ike-head-note\\(\\d+\\)\\[In dollars\\.\\];\\]").matcher(typst).find(), "the note of a header cell");
        assertFalse(typst.contains("#footnote[In dollars.]"));
        assertEquals(1, typst.split(java.util.regex.Pattern.quote(TypstWriter.HEAD_NOTE_HELPER), -1).length - 1, "with the helper that gives it once");
        for (String line : typst.split("\n")) {
            if (line.startsWith("#ike-entry(")) {
                assertFalse(line.contains("footnote"), line);
            }
        }
    }

    @Test
    void theUnitsCarryTheNotesAsFootnotes() {
        List<Unit> units = ModelUnits.of(model(), 11);
        assertEquals(5, units.stream().filter(u -> u.kind() == Kind.FOOTNOTE).count());
        List<Unit> entries = units.stream().filter(u -> u.kind() == Kind.ENTRY).toList();
        assertEquals(3, entries.size());
        assertFalse(entries.stream().anyMatch(u -> u.text().contains("estimate")), "an entry draws no note and no mark of one");
        assertFalse(units.stream().anyMatch(u -> u.text().contains("endif") || u.text().contains("companion document")));
    }
}

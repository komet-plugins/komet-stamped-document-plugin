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
import network.ike.komet.document.GitDocumentMaterializer.StoredBlock;
import network.ike.komet.document.GitDocumentMaterializer.StoredDocument;
import network.ike.komet.document.print.DocumentModel;
import network.ike.komet.document.print.StoredDocumentModel;
import network.ike.komet.document.print.StoredFixture;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Titled figures and tables numbered in reading order, headings gathered with them, and the lists a header asks for. */
class CaptionsTest {

    private static final String TEXT = """
            = The Document
            :toc: left
            :toclevels: 3
            :list-of-figures:

            == First section

            .The control structure
            [graphviz]
            ----
            digraph { a -> b; }
            ----

            .Per 10,000 who take each alternative
            [cols="1,1", options="header"]
            |===
            | money | QALY
            | 1 | 2
            |===

            [cols="1,1"]
            |===
            | a | b
            |===

            === Deeper

            .A worked listing
            ----
            not a figure
            ----

            .The approval tree
            [graphviz]
            ----
            digraph { c -> d; }
            ----

            == Second section

            .Costs
            |===
            | x | y
            |===
            """;

    private static boolean figure(StoredBlock block) {
        return block.kind() == Block.Kind.DIAGRAM || block.kind() == Block.Kind.IMAGE;
    }

    @Test
    void titledFiguresAndTablesAreNumberedInReadingOrder() {
        StoredDocument document = StoredFixture.parse(TEXT);
        Captions captions = Captions.of(DocumentAssembly.single(document, "doc.adoc"), CaptionsTest::figure);
        assertEquals(List.of("Figure 1. The control structure", "Table 1. Per 10,000 who take each alternative", "Figure 2. The approval tree",
                "Table 2. Costs"), captions.all().stream().map(Captions.Caption::text).toList());
        assertEquals(2, captions.of(Captions.Kind.FIGURE).size());
        assertEquals(2, captions.of(Captions.Kind.TABLE).size());
        StoredBlock listing = document.blocks().values().stream().filter(b -> b.title().equals("A worked listing")).findFirst().orElseThrow();
        assertEquals("A worked listing", captions.text(listing), "a titled listing keeps its title, and is neither figure nor table");
        assertTrue(captions.of(listing.nid()).isEmpty());
        StoredBlock untitled = document.blocks().values().stream().filter(b -> b.kind() == Block.Kind.TABLE && b.title().isEmpty()).findFirst().orElseThrow();
        assertTrue(captions.of(untitled.nid()).isEmpty(), "an untitled table is not counted");
    }

    @Test
    void headingsAreGatheredWithTheirLevels() {
        StoredDocument document = StoredFixture.parse(TEXT);
        Captions captions = Captions.of(DocumentAssembly.single(document, "doc.adoc"), CaptionsTest::figure);
        assertEquals(List.of("First section", "Deeper", "Second section"), captions.headings().stream().map(Captions.Heading::text).toList());
        assertEquals(List.of(1, 2, 1), captions.headings().stream().map(Captions.Heading::level).toList());
    }

    @Test
    void headingsAreNumberedToTheDepthAsked() {
        StoredDocument document = StoredFixture.parse(TEXT);
        DocumentAssembly assembly = DocumentAssembly.single(document, "doc.adoc");
        Captions three = Captions.of(assembly, CaptionsTest::figure, 3);
        assertEquals(List.of("1", "1.1", "2"), three.headings().stream().map(Captions.Heading::number).toList());
        assertEquals(List.of("1 First section", "1.1 Deeper", "2 Second section"), three.headings().stream().map(Captions.Heading::read).toList());
        Captions one = Captions.of(assembly, CaptionsTest::figure, 1);
        assertEquals(List.of("1", "", "2"), one.headings().stream().map(Captions.Heading::number).toList(), "a deeper heading than the depth has no number");
        assertEquals("Deeper", one.heading(document.blocks().values().stream().filter(b -> b.title().equals("Deeper")).findFirst().orElseThrow(), "Deeper"));
        Captions none = Captions.of(assembly, CaptionsTest::figure);
        assertTrue(none.headings().stream().allMatch(h -> h.number().isEmpty()), "no numbers unless asked");
        assertEquals("", none.number(123456L), "a block that is no heading has no number");
    }

    @Test
    void theHeaderSaysWhetherSectionsAreNumbered() {
        DocumentModel.Lists lists = StoredDocumentModel.lists(StoredFixture.parse("= Doc\n:sectnums:\n:sectnumlevels: 2\n\nText.\n"));
        assertTrue(lists.sectionNumbers());
        assertEquals(2, lists.sectionLevels());
        DocumentModel.Lists off = StoredDocumentModel.lists(StoredFixture.parse("= Doc\n:sectnums:\n:sectnums!:\n\nText.\n"));
        assertFalse(off.sectionNumbers());
        assertEquals(3, StoredDocumentModel.lists(StoredFixture.parse(TEXT)).sectionLevels(), "three levels by default");
        assertFalse(StoredDocumentModel.lists(StoredFixture.parse(TEXT)).sectionNumbers());
    }

    @Test
    void theHeaderSaysWhichListsTheDocumentAsksFor() {
        DocumentModel.Lists lists = StoredDocumentModel.lists(StoredFixture.parse(TEXT));
        assertTrue(lists.contents());
        assertEquals(3, lists.depth());
        assertTrue(lists.figures());
        assertFalse(lists.tables());
        assertTrue(lists.any());
        assertFalse(StoredDocumentModel.lists(StoredFixture.parse("= Plain\n\nText.\n")).any());
    }

    @Test
    void nothingIsCaptionedInADocumentWithoutTitles() {
        Captions captions = Captions.of(DocumentAssembly.single(StoredFixture.parse("= Plain\n\n|===\n| a\n|===\n"), "p.adoc"), CaptionsTest::figure);
        assertTrue(captions.isEmpty());
        assertTrue(captions.headings().isEmpty());
    }
}

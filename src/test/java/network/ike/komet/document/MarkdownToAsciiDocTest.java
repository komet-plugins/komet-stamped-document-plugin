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
import design.knowledge.stamp.adoc.dialect.Document;
import design.knowledge.stamp.adoc.dialect.IkeDialectParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Markdown a conversation is written in, turned into the IKE dialect of AsciiDoc
 * (IKE-Network/ike-issues#1313, step 3 of {@code design-2026-11-22-knowledge-shared-local}).
 */
class MarkdownToAsciiDocTest {

    private static String convert(String markdown) {
        return MarkdownToAsciiDoc.convert(markdown, 1);
    }

    @Test
    void headingsBecomeSectionsBelowTheTurnWithNoLevelSkipped() {
        assertEquals("""
                === Findings

                Some text.

                ==== Detail

                More.""", convert("# Findings\n\nSome text.\n\n### Detail\n\nMore."));
    }

    @Test
    void emphasisStrongAndCodeKeepTheirMeaning() {
        assertEquals("Some _light_ and *strong* text, with `code`.", convert("Some *light* and **strong** text, with `code`."));
        assertEquals("un**believ**able and ``x``y", convert("un**believ**able and `x`y"));
    }

    @Test
    void paragraphsKeepTheirLines() {
        assertEquals("First line\nsecond line.\n\nNext paragraph.", convert("First line\nsecond line.\n\nNext paragraph."));
    }

    @Test
    void listsKeepTheirNestingAndTwoListsInARowStayApart() {
        assertEquals("""
                * a
                ** b
                * c

                //-

                . one
                . two""", convert("- a\n  - b\n- c\n\n1. one\n2. two"));
    }

    @Test
    void aListItemsFurtherBlocksAreAttached() {
        assertEquals("""
                * Run it:
                +
                [source,bash]
                ----
                ls -la
                ----
                * Done.""", convert("- Run it:\n\n  ```bash\n  ls -la\n  ```\n- Done."));
    }

    @Test
    void anOrderedListStartingElsewhereSaysWhere() {
        assertEquals("[start=3]\n. three\n. four", convert("3. three\n4. four"));
    }

    @Test
    void fencedCodeKeepsItsLanguageSoAKonceptTreeKeepsItsName() {
        assertEquals("""
                [source,koncept-tree]
                ----
                k:sctid=772222008[Medical devices]
                  k:sctid=118956008[Microbiology device]
                ----""", convert("```koncept-tree\nk:sctid=772222008[Medical devices]\n  k:sctid=118956008[Microbiology device]\n```"));
        assertEquals("[listing]\n-----\n----\n-----", convert("```\n----\n```"), "a longer delimiter than any line of the code");
    }

    @Test
    void tablesHaveAHeaderRow() {
        assertEquals("""
                [%header]
                |===
                |Name |Count
                |a\\|b |*2*
                |===""", convert("| Name | Count |\n|---|---|\n| a\\|b | **2** |"));
    }

    @Test
    void linksImagesKonceptTokensAndUrls() {
        assertEquals("See https://ike.example/x[the page], link:notes.adoc[notes] and https://a.example/b.",
                convert("See [the page](https://ike.example/x), [notes](notes.adoc) and https://a.example/b."));
        assertEquals("See k:sctid=118956008[Microbiology device] here.", convert("See k:sctid=118956008[Microbiology device] here."));
        assertEquals("image:chart.png[A chart]", convert("![A chart](chart.png)"));
    }

    @Test
    void aQuotationIsAQuoteBlock() {
        assertEquals("____\nQuoted text.\n____", convert("> Quoted text."));
    }

    @Test
    void textIsNeverReadAsMarkupItWasNotMeantToBe() {
        assertEquals("Items\n{empty}2. is not a list", convert("Items\n2. is not a list"), "a line that would start a list");
        assertEquals("{empty}.gitignore holds them", convert(".gitignore holds them"), "a line that would be a block title");
        assertEquals("Term&#58;: not a description list", convert("Term:: not a description list"));
        assertEquals("++Use {name} here.++", convert("Use {name} here."), "an attribute reference");
        assertEquals("++*not strong*++", convert("\\*not strong\\*"), "two marks that could pair");
        assertEquals("C# and F# and snake_case_names", convert("C# and F# and snake_case_names"), "marks that cannot pair stay as they are");
        assertEquals("++x^2^ and H~2~O++", convert("x^2^ and H~2~O"), "superscript and subscript");
        assertEquals("std::vector stays", convert("std::vector stays"), "colons inside a word are not a marker");
        assertEquals("`+*a* b+`", convert("`*a* b`"), "code whose marks could pair is literal");
        assertEquals("`a*b*c`", convert("`a*b*c`"), "code whose marks cannot pair is as it is");
        assertEquals("`pass:c[x++]`", convert("`x++`"));
    }

    @Test
    void theResultReadsBackAsTheBlocksTheMarkdownHad() {
        String adoc = "= Saved\n\n== Ada\n\n" + convert("# Plan\n\nFirst *step*.\n\n- one\n- two\n\n```java\nint x;\n```\n\n| A |\n|---|\n| 1 |");
        Document document = IkeDialectParser.parse(adoc);
        Block turn = document.root().children().getFirst();
        assertEquals(Block.Kind.SECTION, turn.kind());
        Block plan = turn.children().getFirst();
        assertEquals("Plan", plan.title());
        List<Block.Kind> kinds = plan.children().stream().map(Block::kind).toList();
        assertEquals(List.of(Block.Kind.PARAGRAPH, Block.Kind.LIST, Block.Kind.LISTING, Block.Kind.TABLE), kinds);
    }

    @Test
    void couldBeMarkupIsQuietForOrdinaryProse() {
        assertFalse(MarkdownToAsciiDoc.couldBeMarkup("An ordinary sentence, with a comma and 3 * 4."));
        assertFalse(MarkdownToAsciiDoc.couldBeMarkup("a_b_c"), "inside a word a single mark does not pair");
        assertTrue(MarkdownToAsciiDoc.couldBeMarkup("a __b__ c"));
        assertTrue(MarkdownToAsciiDoc.couldBeMarkup("see _this_ here"));
    }
}

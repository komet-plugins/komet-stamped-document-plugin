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

import network.ike.komet.document.DocumentAttributes.PrintStyles.Effect;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A document's attributes carried through its lines: what an entry sets, what a conditional
 * directive leaves out, and what a reference reads as, a passthrough value among them.
 */
class DocumentAttributesTest {

    /** The lines a walk reads, of those given. */
    private static List<String> read(DocumentAttributes.Walk walk, String text) {
        List<String> read = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            if (walk.line(line)) {
                read.add(line);
            }
        }
        return read;
    }

    private static List<String> read(String text) {
        return read(new DocumentAttributes.Walk(), text);
    }

    @Test
    void anEntrySetsAnAttributeFromItsLineOn() {
        DocumentAttributes.Walk walk = new DocumentAttributes.Walk();
        assertFalse(walk.state().defined("topics"));
        assertTrue(walk.line(":topics: ."), "an entry is a line of the document");
        assertEquals(".", walk.state().value("topics").orElseThrow());
        walk.line(":draft:");
        assertEquals("", walk.state().value("draft").orElseThrow(), "an entry with no value sets the attribute to nothing");
        walk.line(":draft!:");
        assertFalse(walk.state().defined("draft"));
        walk.line(":draft:");
        walk.line(":!draft:");
        assertFalse(walk.state().defined("draft"), "the mark may stand before the name");
    }

    @Test
    void aStateIsNotChangedByALaterEntry() {
        DocumentAttributes.Walk walk = new DocumentAttributes.Walk();
        walk.line(":edition: first");
        DocumentAttributes before = walk.state();
        walk.line(":edition: second");
        assertEquals("first", before.value("edition").orElseThrow(), "a line keeps the attributes it was read in");
        assertEquals("second", walk.state().value("edition").orElseThrow());
    }

    @Test
    void aValueIsFixedWhereItIsSetAndMayRunOverLines() {
        DocumentAttributes.Walk walk = new DocumentAttributes.Walk();
        walk.line(":root: docs");
        walk.line(":images: {root}/images");
        walk.line(":root: elsewhere");
        assertEquals("docs/images", walk.state().value("images").orElseThrow(), "the reference in a value is read at the entry");
        walk.line(":note: States the decision to inspect \\");
        assertFalse(walk.state().defined("note"), "an entry that runs on is not made until its last line");
        walk.line("a clinical site.");
        assertEquals("States the decision to inspect a clinical site.", walk.state().value("note").orElseThrow());
    }

    @Test
    void kometIsTheBackend() {
        DocumentAttributes start = DocumentAttributes.intrinsic();
        assertEquals("komet", start.value("backend").orElseThrow());
        assertTrue(start.defined("backend-komet"));
        assertFalse(start.defined("backend-pdf"), "what is kept for a backend that makes an index is not read where none is made");
        assertEquals("[", start.value("startsb").orElseThrow());
    }

    @Test
    void aConditionalLeavesOutItsLinesAndIsNotReadItself() {
        assertEquals(List.of("before", "after"), read("""
                before
                ifdef::compendium[]
                only in the compendium
                endif::[]
                after"""));
        assertEquals(List.of(":compendium:", "before", "only in the compendium", "after"), read("""
                :compendium:
                before
                ifdef::compendium[]
                only in the compendium
                endif::[]
                after"""));
    }

    @Test
    void ifndefWithSeveralNamesReadsOnlyWhenNoneIsSet() {
        String text = """
                ifndef::compendium,book[]
                in neither
                endif::[]
                ifndef::compendium+book[]
                not in both
                endif::[]""";
        assertEquals(List.of("in neither", "not in both"), read(text));
        DocumentAttributes.Walk book = new DocumentAttributes.Walk();
        book.line(":book:");
        assertEquals(List.of("not in both"), read(book, text), "a comma is any of them, a plus all of them");
        DocumentAttributes.Walk both = new DocumentAttributes.Walk();
        both.line(":book:");
        both.line(":compendium:");
        assertEquals(List.of(), read(both, text));
    }

    @Test
    void ifdefWithSeveralNames() {
        DocumentAttributes.Walk walk = new DocumentAttributes.Walk();
        walk.line(":book:");
        assertEquals(List.of("either"), read(walk, """
                ifdef::compendium,book[]
                either
                endif::[]
                ifdef::compendium+book[]
                both
                endif::[]"""));
    }

    @Test
    void conditionalsNestAndAnEntryLeftOutIsNotMade() {
        DocumentAttributes.Walk walk = new DocumentAttributes.Walk();
        assertEquals(List.of("shown"), read(walk, """
                ifdef::absent[]
                ifndef::absent[]
                inner
                endif::[]
                :made:
                still out
                endif::[]
                shown"""));
        assertFalse(walk.state().defined("made"));
    }

    @Test
    void aConditionalWithItsTextInItsBracketsIsALineOfItsOwn() {
        assertEquals(List.of("ifndef::absent[Shown.]", "after"), read("""
                ifdef::absent[Not shown.]
                ifndef::absent[Shown.]
                after"""), "it opens nothing: the line after is read");
        assertEquals("Shown.", DocumentAttributes.reading().line("ifndef::absent[Shown.]", DocumentAttributes.intrinsic()));
    }

    @Test
    void ifevalComparesTwoValues() {
        DocumentAttributes.Walk walk = new DocumentAttributes.Walk();
        walk.line(":level: 3");
        assertEquals(List.of("deep", "komet"), read(walk, """
                ifeval::[{level} >= 2]
                deep
                endif::[]
                ifeval::[{level} < 2]
                shallow
                endif::[]
                ifeval::["{backend}" == "komet"]
                komet
                endif::[]"""));
    }

    @Test
    void anEntryInAListingIsTheListingsText() {
        DocumentAttributes.Walk walk = new DocumentAttributes.Walk();
        assertEquals(6, read(walk, """
                ----
                :shown: as source
                ----
                ////
                ifdef::absent[]
                ////""").size(), "a comment block's lines are no directives");
        assertFalse(walk.state().defined("shown"));
        assertTrue(walk.line("after the comment block"));
    }

    @Test
    void aReferenceReadsAsItsValueAndOneToNothingSetStaysAsWritten() {
        DocumentAttributes.Walk walk = new DocumentAttributes.Walk();
        walk.line(":topics: .");
        DocumentAttributes at = walk.state();
        assertEquals("image::./images/a.svg[]", DocumentAttributes.reading().line("image::{topics}/images/a.svg[]", at));
        assertEquals("{unset} and [1]", DocumentAttributes.reading().line("{unset} and {startsb}1{endsb}", at));
        assertEquals("{topics} as written", DocumentAttributes.reading().line("\\{topics} as written", at), "an escaped reference is its own words");
        String plain = "no reference at all";
        assertEquals(plain, DocumentAttributes.reading().line(plain, at));
    }

    @Test
    void aValueThatIsAMarkIsNotReadAsMarkup() {
        DocumentAttributes at = DocumentAttributes.intrinsic();
        String read = DocumentAttributes.reading().line("EL{plus}{plus} stated axioms and an EL{plus}{plus} class in {cpp}", at);
        assertEquals("EL++ stated axioms and an EL++ class in C++", network.ike.komet.document.print.InlineText.of(read).text(),
                "the marks between the two are not a passthrough's");
        DocumentAttributes.Walk walk = new DocumentAttributes.Walk();
        walk.line(":repo: https://example.org/my_repo_name");
        assertEquals("link:https://example.org/my_repo_name/issues[issues]", DocumentAttributes.reading().line("link:{repo}/issues[issues]", walk.state()),
                "a mark inside a value pairs with nothing, and a link's target keeps no backslash");
    }

    /** The regulatory book's header: a dagger with a tooltip on the screen, a footnote in print. */
    private static DocumentAttributes estimateNotes() {
        DocumentAttributes.Walk walk = new DocumentAttributes.Walk();
        for (String line : """
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
                :ours: pass:[<sup class="ours-mark" title="Our estimate.">†</sup><span class="ours-note">Our estimate.</span>]
                :fn: pass:[<sup class="ours-mark" title="our estimate">†</sup><span class="ours-note">]
                :fnend: pass:[</span>]""".split("\n")) {
            assertTrue(walk.line(line));
        }
        return walk.state();
    }

    @Test
    void theStyleSheetSaysWhatAnElementIsInPrint() {
        DocumentAttributes.PrintStyles styles = estimateNotes().styles();
        assertEquals(Effect.HIDDEN, styles.of("sup", List.of("ours-mark")), "the mark is for the screen");
        assertEquals(Effect.FOOTNOTE, styles.of("span", List.of("ours-note")), "the rule under print wins over the one before it");
        assertEquals(Effect.INLINE, styles.of("span", List.of("other")));
        assertEquals(Effect.INLINE, DocumentAttributes.intrinsic().styles().of("span", List.of("ours-note")), "a document with no sheet says nothing");
    }

    @Test
    void aPassthroughNoteReadsAsAFootnote() {
        DocumentAttributes at = estimateNotes();
        assertEquals("cost five.footnote:[Our estimate.] With both harms",
                DocumentAttributes.reading().line("cost five.{ours} With both harms", at));
        assertEquals(".The quantities behind the treefootnote:[The prior is our estimate.]",
                DocumentAttributes.reading().line(".The quantities behind the tree{fn}The prior is our estimate.{fnend}", at));
    }

    @Test
    void aNoteMayOpenOnOneLineAndCloseOnTheNext() {
        DocumentAttributes at = estimateNotes();
        DocumentAttributes.Reading reading = DocumentAttributes.reading();
        assertEquals("we take.footnote:[The ranges for the three links", reading.line("we take.{fn}The ranges for the three links", at));
        assertEquals("are our estimates.] More follows.", reading.line("are our estimates.{fnend} More follows.", at));
    }

    @Test
    void withNoSheetAPassthroughElementReadsAsItsWords() {
        DocumentAttributes.Walk walk = new DocumentAttributes.Walk();
        walk.line(":mark: pass:[<span class=\"mark\">seen &amp; kept</span><br/>]");
        walk.line(":up: pass:[<sup>2</sup>]");
        assertEquals("a seen & kept b", DocumentAttributes.reading().line("a {mark} b", walk.state()));
        assertEquals("m^2^", DocumentAttributes.reading().line("m{up}", walk.state()));
    }
}

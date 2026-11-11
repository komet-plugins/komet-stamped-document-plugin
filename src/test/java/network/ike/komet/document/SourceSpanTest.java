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

import network.ike.komet.document.print.InlineText;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Words selected in a line's drawn text, found in the line's source: exactly, or not at all. */
class SourceSpanTest {

    /** The selection of a word's first occurrence in a share. */
    private static Optional<SourceSpan.Located> select(String source, boolean inline, String share, String words) {
        int at = share.indexOf(words);
        return SourceSpan.locate(source, inline, share, at, at + words.length());
    }

    private static String stretch(String source, SourceSpan.Located at) {
        return source.substring(at.from(), at.to());
    }

    @Test
    void plainWordsStandWhereTheyAreDrawn() {
        String source = "The legal standard by which the FDA then clears or approves a device would stay";
        SourceSpan.Located at = select(source, true, source + " ", "approves").orElseThrow();
        assertEquals("approves", stretch(source, at));
        assertEquals("approves", at.words());
        assertEquals("The legal standard by which the FDA then clears or *approves* a device would stay",
                InlineMarks.BOLD.toggle(source, at.from(), at.to()).text());
    }

    @Test
    void wordsAfterMarkupAreFoundPastTheMarksTheDrawnTextLacks() {
        String source = "The *decision record* would `not` change the law.";
        String share = InlineText.of(source).text();
        assertEquals("The decision record would not change the law.", share);
        SourceSpan.Located at = select(source, true, share, "change the law").orElseThrow();
        assertEquals("change the law", stretch(source, at));
        SourceSpan.Located phrase = select(source, true, share, "decision record").orElseThrow();
        assertEquals("decision record", stretch(source, phrase), "the marked phrase whole, its marks around it");
        assertEquals("The decision record would `not` change the law.", InlineMarks.BOLD.toggle(source, phrase.from(), phrase.to()).text(),
                "so the mark toggles off");
        assertFalse(phrase.bold(), "the whole phrase is not inside a longer one");
        assertEquals("*decision record*", source.substring(phrase.outerFrom(), phrase.outerTo()), "with its marks, for a mark of another kind");
        assertEquals("The _*decision record*_ would `not` change the law.", InlineMarks.ITALIC.toggle(source, phrase.outerFrom(), phrase.outerTo()).text());
        assertEquals(at.from(), at.outerFrom(), "plain words have no marks to take");
    }

    @Test
    void aSelectionThatTakesInAMarkedPhraseTakesItsMarksToo() {
        String source = "The *decision record* would `not` change the law.";
        String share = InlineText.of(source).text();
        SourceSpan.Located ends = select(source, true, share, "would not").orElseThrow();
        assertEquals("would `not`", stretch(source, ends), "the phrase that ends the selection, with its closing mark");
        assertEquals("The *decision record* _would `not`_ change the law.", InlineMarks.ITALIC.toggle(source, ends.from(), ends.to()).text(),
                "so the new mark nests around it and never crosses it");
        SourceSpan.Located begins = select(source, true, share, "decision record would").orElseThrow();
        assertEquals("*decision record* would", stretch(source, begins));
        SourceSpan.Located both = select(source, true, share, "decision record would not").orElseThrow();
        assertEquals("*decision record* would `not`", stretch(source, both));
        SourceSpan.Located first = select("*Bold* opens the line", true, "Bold opens the line", "Bold opens").orElseThrow();
        assertEquals(0, first.from(), "at the line's start too");
    }

    @Test
    void aSelectionOverLinesEndsOutsideAPhraseItTakesIn() {
        String first = "**The release loaded.** The knowledge base registers one release,";
        String last = "`gudid-origin.zip`, with _emphasis_ on its *date*, as";
        OptionalInt opens = SourceSpan.start(first, true, InlineText.of(first).text() + " ", InlineText.of(first).text().indexOf("one release"));
        OptionalInt closes = SourceSpan.end(last, true, InlineText.of(last).text() + " ", "gudid-origin.zip".length());
        assertEquals(first.indexOf("one release"), opens.getAsInt());
        assertEquals("`gudid-origin.zip`".length(), closes.getAsInt(), "after the monospace phrase's closing mark, not inside it");
        String[] both = InlineMarks.BOLD.across(first, opens.getAsInt(), last, closes.getAsInt()).orElseThrow();
        assertEquals("`gudid-origin.zip`*, with _emphasis_ on its *date*, as", both[1]);
        assertTrue(SourceSpan.end(last, true, InlineText.of(last).text(), "gudid-origin".length()).isEmpty(), "nor does it end inside one");
        OptionalInt atPhrase = SourceSpan.start(last, true, InlineText.of(last).text(), InlineText.of(last).text().indexOf("emphasis"));
        assertEquals(last.indexOf("_emphasis_"), atPhrase.getAsInt(), "and it begins before the marks of a phrase it begins with");
    }

    @Test
    void aSelectionInsideAWordIsTakenAsTheWord() {
        String source = "It bears on what evidence the FDA asks for.";
        int at = source.indexOf("vide");
        SourceSpan.Located found = SourceSpan.locate(source, true, source, at, at + 4).orElseThrow();
        assertEquals("evidence", stretch(source, found), "a constrained mark holds only at a word's edges");
        assertArrayEquals(new int[] {3, 8}, SourceSpan.words("It bears on", 4, 6));
        assertArrayEquals(new int[] {3, 8}, SourceSpan.words("It bears on", 2, 9), "white space at the ends is left out");
        assertNull(SourceSpan.words("It bears on", 2, 3), "no word");
    }

    @Test
    void theSameWordsTwiceAreToldApartByWhereTheyStand() {
        String source = "the cost of the evidence and the cost of the harm";
        int second = source.lastIndexOf("the cost");
        SourceSpan.Located at = SourceSpan.locate(source, true, source, second, second + 8).orElseThrow();
        assertEquals(second, at.from());
        assertEquals(1, at.nth(), "the second occurrence, so it can be selected again");
        assertEquals(0, select(source, true, source, "the cost").orElseThrow().from());
    }

    @Test
    void aListItemsMarkerAndAnAdmonitionsLabelAreTheViewsOwn() {
        String source = "* The FDA could show that a request is the least burdensome one";
        String share = "• The FDA could show that a request is the least burdensome one ";
        SourceSpan.Located at = select(source, true, share, "least burdensome").orElseThrow();
        assertEquals("least burdensome", stretch(source, at));
        String note = "NOTE: A cleared device is not an approved one.";
        SourceSpan.Located inNote = select(note, true, "NOTE — A cleared device is not an approved one.", "approved").orElseThrow();
        assertEquals("approved", stretch(note, inNote));
    }

    @Test
    void aPartOfAPageLineIsPlacedByTheTextAroundItWhenTheCountsDiffer() {
        // index terms print nothing and a doubled space is one: the drawn text holds fewer characters than the source
        String source = "the dollar  value((dollar value)) of a year and the dollar amounts in a decision";
        String share = "the dollar value of a year and the dollar amounts in a decision";
        int second = share.lastIndexOf("the dollar");
        SourceSpan.Located at = SourceSpan.locate(source, true, share, second, second + "the dollar".length()).orElseThrow();
        assertEquals(source.lastIndexOf("the dollar"), at.from());
    }

    @Test
    void whatIsNotWordsIsNotPlaced() {
        String source = "Health economists use it claxton1996 and <<pricing-health,the pricing>> footnote:[Our estimate.] too.";
        String share = InlineText.of(source).text();
        assertTrue(select(source, true, share, "Health economists").isPresent());
        assertTrue(select(source, true, share, "the pricing").isEmpty() || stretch(source, select(source, true, share, "the pricing").orElseThrow()).equals("the pricing"),
                "a reference's own words are placed inside it, or not at all");
        String marked = "It *bears on what* evidence";
        String drawn = InlineText.of(marked).text();
        int from = drawn.indexOf("on what");
        assertTrue(SourceSpan.locate(marked, true, drawn, from, drawn.indexOf("evidence") + 8).isEmpty(), "half of a marked phrase and words past it");
        SourceSpan.Located inside = select(marked, true, drawn, "on").orElseThrow();
        assertTrue(inside.bold(), "words inside a longer bold phrase say so, so the same mark is not written within it");
        assertFalse(inside.italic());
        assertTrue(SourceSpan.locate("a chip here", true, "a   here", 0, 8).isEmpty(), "a chip stands in the drawn text for a reference the source spells out");
        assertTrue(SourceSpan.locate("abc", true, "abc", 1, 1).isEmpty(), "nothing selected");
    }

    @Test
    void aLineDrawnAsWrittenIsItsOwnSource() {
        String source = "The *decision record* would not change the law.";
        SourceSpan.Located at = select(source, false, source, "record").orElseThrow();
        assertEquals(source.indexOf("record"), at.from());
        assertEquals("record", stretch(source, at));
    }

    @Test
    void aSelectionOverLinesIsPlacedAtItsTwoEnds() {
        String first = "The legal standard by which the FDA then clears or approves a device would stay";
        String last = "as it is. The dollar amounts in a decision record are the cost of producing the";
        OptionalInt opens = SourceSpan.start(first, true, first + " ", first.indexOf("would stay"));
        OptionalInt closes = SourceSpan.end(last, true, last + " ", "as it is".length());
        assertEquals(first.indexOf("would stay"), opens.getAsInt());
        assertEquals("as it is".length(), closes.getAsInt());
        String[] both = InlineMarks.ITALIC.across(first, opens.getAsInt(), last, closes.getAsInt()).orElseThrow();
        assertEquals("The legal standard by which the FDA then clears or approves a device _would stay", both[0]);
        assertEquals("as it is_. The dollar amounts in a decision record are the cost of producing the", both[1]);
        assertEquals("would stay as it is", InlineText.of(both[0] + "\n" + both[1]).runs().stream().filter(InlineText.Run::italic)
                .map(run -> InlineText.of(both[0] + "\n" + both[1]).text().substring(run.from(), run.to()).replace('\n', ' ')).findFirst().orElse(""),
                "AsciiDoc reads the two marks as one phrase, a paragraph's lines being one text");
        String[] off = InlineMarks.ITALIC.across(both[0], opens.getAsInt() + 1, both[1], closes.getAsInt()).orElseThrow();
        assertArrayEquals(new String[] {first, last}, off, "marked that way already, the marks go");
        assertTrue(InlineMarks.LINK.across(first, 0, last, 2).isEmpty(), "a link is one line's");
        assertTrue(SourceSpan.start(first, true, first, first.length()).isEmpty());
    }
}

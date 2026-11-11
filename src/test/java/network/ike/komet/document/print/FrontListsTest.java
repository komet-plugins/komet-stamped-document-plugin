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

import network.ike.komet.document.print.DocumentModel.Entry;
import network.ike.komet.document.print.DocumentModel.FrontList;
import network.ike.komet.document.print.DocumentModel.Heading;
import network.ike.komet.document.print.LayoutOracle.Layout;
import network.ike.komet.document.print.LayoutOracle.Line;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How the front lists' page numbers settle: a pass learns them, the next confirms them, a wider number can move one. */
class FrontListsTest {

    private static final DocumentModel MODEL = new DocumentModel("T", List.of(
            new FrontList(FrontList.Kind.CONTENTS, List.of(new Entry("1 One", 0, "a"), new Entry("2 Two", 0, "b"))),
            new Heading(1, "1 One", "a"),
            new Heading(1, "2 Two", "b")));
    /** The units the targets begin with: the contents' title and entries are units 1 to 3, the headings 4 and 5. */
    private static final Map<String, Integer> TARGETS = Map.of("a", 4, "b", 5);

    /** A layout putting target a on one page and b on another. */
    private static Layout layout(int pageA, int pageB) {
        List<Line> lines = new ArrayList<>();
        lines.add(new Line(4, pageA - 1, 72, 100, 0, 5, false));
        lines.add(new Line(5, pageB - 1, 72, 100, 0, 5, false));
        return new Layout(Math.max(pageA, pageB), lines, true, 0, List.of());
    }

    @Test
    void anEntrysMarkIsItsSectionNumberOrItsCaptionsWordAndNumber() {
        assertEquals("4", FrontLists.mark("4 Clearing the Jaw Implant"));
        assertEquals("4.1", FrontLists.mark("4.1 How likely breakdown had to be"));
        assertEquals("10.12.", FrontLists.mark("10.12. With a full stop"));
        assertEquals("Figure 3.", FrontLists.mark("Figure 3. The decision tree"));
        assertEquals("Table 12.", FrontLists.mark("Table 12. Costs"));
        assertEquals("", FrontLists.mark("The Proposal in Brief"), "a heading with no number");
        assertEquals("", FrontLists.mark("Step 1: how much the first signal changes"), "a number inside a title is no mark");
        assertEquals("", FrontLists.mark("Phase 2 results"), "nor a word and a number with no full stop");
        assertEquals("", FrontLists.mark("1983"), "a mark numbers the text after it");
    }

    @Test
    void aShortLastWordIsTiedToTheWordBeforeIt() {
        assertEquals("the FDA Did Not Ask\u00a0For", FrontLists.tied("the FDA Did Not Ask For"));
        assertEquals("What Happens\u00a0to", FrontLists.tied("What Happens to"));
        assertEquals("Apply to the\u00a0Jaw?", FrontLists.tied("Apply to the Jaw?"), "a mark of punctuation after it does not lengthen the word");
        assertEquals("What Happens to Patients", FrontLists.tied("What Happens to Patients"), "a longer last word turns over by itself");
        assertEquals("cleared in\u00a01983", FrontLists.tied("cleared in 1983"), "four letters or digits are short too");
        assertEquals("of a Wear\u00a0Test", FrontLists.tied("of a Wear Test"));
        assertEquals("for the device", FrontLists.tied("for the device"), "five are not");
        assertEquals("Contents", FrontLists.tied("Contents"), "one word has nothing to be tied to");
        assertEquals("a b\u00a0c", FrontLists.tied("a b c"), "only the last word is tied");
        assertEquals("Ask For".length(), FrontLists.tied("Ask For").length(), "the text keeps its length");
        assertEquals(4, FrontLists.TIE);
    }

    @Test
    void theWidestMarkOfEachDepthIsItsLongestWithEveryDigitAZero() {
        assertEquals(List.of("00", "00.00"), FrontLists.patterns(List.of("The Proposal in Brief", "The problem", "1 One", "1.1 Deeper", "10 Ten", "10.12 Twelve", "3.4 Four"),
                List.of(0, 1, 0, 1, 0, 1, 1)), "one text for each depth, whatever the digits: both ends measure the same one");
        assertEquals(List.of("Figure 00."), FrontLists.patterns(List.of("Figure 3. A tree", "Figure 12. A tornado"), List.of(0, 0)));
        assertEquals(List.of("", "0.0"), FrontLists.patterns(List.of("Preface", "1.1 Deeper"), List.of(0, 1)), "a depth with no mark has none");
        assertEquals(List.of("", ""), FrontLists.patterns(List.of("Preface", "Thanks"), List.of(0, 1)));
        assertEquals(List.of(), FrontLists.patterns(List.of(), List.of()));
    }

    @Test
    void theTextsOfADepthBeginAtOneEdgeAfterTheColumnOfItsMarks() {
        LayoutOracle.Measure six = (text, unit, mono, bold, italic) -> text.length() * 6.0;
        List<LayoutOracle.Unit> units = List.of(
                new LayoutOracle.Unit(0, 1, LayoutOracle.Kind.HEADING, "Contents", 18, true, 0, 0, 0, true),
                new LayoutOracle.Unit(1, 1, LayoutOracle.Kind.ENTRY, "The Proposal in Brief", 12, false, 0, 0, 0, false),
                new LayoutOracle.Unit(2, 1, LayoutOracle.Kind.ENTRY, "The problem", 12, false, 12, 0, 0, false),
                new LayoutOracle.Unit(3, 1, LayoutOracle.Kind.ENTRY, "4 Clearing", 12, false, 0, 0, 0, false),
                new LayoutOracle.Unit(4, 1, LayoutOracle.Kind.ENTRY, "4.1 How likely", 12, false, 12, 0, 0, false),
                new LayoutOracle.Unit(5, 1, LayoutOracle.Kind.ENTRY, "10 Ten", 12, false, 0, 0, 0, false),
                new LayoutOracle.Unit(6, 2, LayoutOracle.Kind.ENTRY, "Preface", 12, false, 0, 0, 0, false),
                new LayoutOracle.Unit(7, 2, LayoutOracle.Kind.ENTRY, "Thanks", 12, false, 12, 0, 0, false),
                new LayoutOracle.Unit(8, 3, LayoutOracle.Kind.PARAGRAPH, "4 is no mark here", 12, false, 0, 0, 0, false));

        Map<Integer, FrontLists.Place> places = FrontLists.places(units, six);

        double outer = 2 * 6 + 12 * TypstWriter.ENTRY_MARK_GAP;       // "00" and the gap
        double inner = 3 * 6 + 12 * TypstWriter.ENTRY_MARK_GAP;       // "0.0" and the gap
        assertEquals(java.util.Set.of(1, 2, 3, 4, 5, 6, 7), places.keySet(), "the entries, and no other unit");
        assertEquals(new FrontLists.Place(0, outer), places.get(3), "a mark stands at the left edge, in a column as wide as the depth's widest");
        assertEquals(places.get(3), places.get(5));
        assertEquals(places.get(3), places.get(1), "an entry with no mark begins where the others' texts do");
        assertEquals(outer, places.get(3).text(), 1e-9);
        assertEquals(new FrontLists.Place(outer, inner), places.get(4), "a depth begins where the text of the depth above does");
        assertEquals(places.get(4), places.get(2));
        assertEquals(new FrontLists.Place(0, 0), places.get(6), "a list with no marks has no column");
        assertEquals(new FrontLists.Place(12 * TypstWriter.ENTRY_STEP, 0), places.get(7), "and sets its second depth a step in");
        assertEquals(1, FrontLists.depth(units.get(4)));
        assertEquals(0, FrontLists.depth(units.get(3)));
    }

    @Test
    void everyLineOfAnEntryKeepsTheNumbersColumnTheLeastLeaderAndTheGapsClear() {
        LayoutOracle.Measure six = (text, unit, mono, bold, italic) -> text.length() * 6.0;
        LayoutOracle.Unit entry = new LayoutOracle.Unit(0, 0, LayoutOracle.Kind.ENTRY, "4.1 How likely", 10, false, 0, 0, 0, false);

        assertEquals(18, FrontLists.column(18, 12), "room for three digits, whatever the number");
        assertEquals(24, FrontLists.column(18, 24), "a wider number widens its own entry's column");
        assertEquals(18 + 10 * (0.4 + 2 + 0.4), FrontLists.reserve(10, 18), 1e-9, "the column, its gutter, the least leader and the gap");
        assertEquals(FrontLists.reserve(10, 18), FrontLists.reserve(entry, six), 1e-9, "an oracle keeps room for three digits");
    }

    @Test
    void thePagesOfTheTargetsAreReadOffTheirUnitsFirstLines() {
        assertEquals(Map.of("a", 2, "b", 9), FrontLists.pages(layout(2, 9), TARGETS));
        assertEquals(Map.of("a", 0), FrontLists.pages(new Layout(1, List.of(), false, 0, List.of()), Map.of("a", 4)), "no line, no page");
    }

    @Test
    void anEntrysPageIsTheFolioItsTargetsPagePrints() {
        // a title page, two pages of contents, then the body: the targets stand on the fourth and the twelfth pages
        Layout book = new Layout(12, layout(4, 12).lines(), true, 0, List.of(), new Folios(3, true));

        assertEquals(Map.of("a", 1, "b", 9), FrontLists.pages(book, TARGETS), "counted from the body's first page, as the pages print it");
        assertEquals(Map.of("a", 4, "b", 12), FrontLists.pages(layout(4, 12), TARGETS), "a document with no front matter counts from its first page");
    }

    @Test
    void aSecondPassConfirmsThePagesTheFirstFound() throws Exception {
        List<List<Integer>> printed = new ArrayList<>();
        FrontLists.Settled settled = FrontLists.settle(MODEL, Map.of("a", 1, "b", 1), TARGETS, numbered -> {
            printed.add(numbered.entries().stream().map(Entry::page).toList());
            return layout(2, 3);
        });

        assertEquals(2, settled.passes(), "one pass learns the pages, one confirms them");
        assertEquals(List.of(List.of(1, 1), List.of(2, 3)), printed, "the estimate first, then what was found");
        assertEquals(Map.of("a", 2, "b", 3), settled.pages());
        assertEquals(List.of(2, 3), settled.model().entries().stream().map(Entry::page).toList(), "the model as last measured carries the pages");
    }

    @Test
    void aNumberThatMovesALineTakesAnotherPass() throws Exception {
        // a wider number for a pushes b over a page: the layout depends on what the entries print
        FrontLists.Settled settled = FrontLists.settle(MODEL, Map.of("a", 1, "b", 1), TARGETS, numbered -> {
            int a = numbered.entries().getFirst().page();
            return layout(10, a >= 10 ? 12 : 11);
        });

        assertEquals(3, settled.passes());
        assertEquals(Map.of("a", 10, "b", 12), settled.pages());
    }

    @Test
    void anEstimateThatWasRightTakesOnePassAsDoesADocumentWithNoList() throws Exception {
        FrontLists.Settled right = FrontLists.settle(MODEL, Map.of("a", 2, "b", 3), TARGETS, numbered -> layout(2, 3));
        assertEquals(1, right.passes());

        DocumentModel plain = new DocumentModel("T", List.of(new Heading(1, "One")));
        List<DocumentModel> seen = new ArrayList<>();
        FrontLists.Settled none = FrontLists.settle(plain, Map.of(), Map.of(), numbered -> {
            seen.add(numbered);
            return layout(1, 1);
        });
        assertEquals(1, none.passes());
        assertSame(plain, seen.getFirst(), "a model with no list is measured as it is");
    }

    @Test
    void thePassesStopAtTheirLimitAndWhenTheOracleGivesNoAnswer() throws Exception {
        int[] calls = {0};
        FrontLists.Settled restless = FrontLists.settle(MODEL, Map.of("a", 1, "b", 1), TARGETS, numbered -> layout(2, 2 + ++calls[0]));
        assertEquals(FrontLists.PASSES, restless.passes());
        assertEquals(Map.of("a", 2, "b", 2 + FrontLists.PASSES), restless.pages(), "the last pass's pages, as found");

        FrontLists.Settled silent = FrontLists.settle(MODEL, Map.of("a", 1, "b", 1), TARGETS,
                numbered -> new Layout(1, List.of(), false, 0, List.of("typst did not answer")));
        assertEquals(1, silent.passes());
        assertTrue(silent.layout().lines().isEmpty());
    }
}

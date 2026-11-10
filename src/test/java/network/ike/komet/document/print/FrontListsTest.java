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
    void theTailSharesTheTextsLastLineOnlyWhenTheGapAndTheNumberFit() {
        assertTrue(FrontLists.tailFits(30, 11, 18), "room for the gap and the number: the leader takes what is left, if anything");
        assertTrue(FrontLists.tailFits(29, 11, 18), "exactly the gap and the number");
        assertFalse(FrontLists.tailFits(28, 11, 18), "a point short: the tail takes a line of its own, as Typst breaks at the gap");
        assertFalse(FrontLists.tailFits(0, 11, 18), "a line filled to the edge");
        assertFalse(FrontLists.tailFits(-2, 11, 18), "a line past the edge");
    }

    @Test
    void thePagesOfTheTargetsAreReadOffTheirUnitsFirstLines() {
        assertEquals(Map.of("a", 2, "b", 9), FrontLists.pages(layout(2, 9), TARGETS));
        assertEquals(Map.of("a", 0), FrontLists.pages(new Layout(1, List.of(), false, 0, List.of()), Map.of("a", 4)), "no line, no page");
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

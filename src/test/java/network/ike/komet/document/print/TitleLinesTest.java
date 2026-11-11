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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A document title's ties and its balanced lines, with every character a point wide. */
class TitleLinesTest {

    private static final String REGULATORY = "Applying Decision Theory to Create Transparent and Reproducible Regulatory Decisions";

    /** The lines of a title as text, their trailing spaces dropped, in a measure of characters. */
    private static List<String> lines(String title, double measure) {
        return TitleLines.breaks(title, (from, to) -> to - from, measure).stream()
                .map(r -> title.substring(r[0], r[1]).strip()).toList();
    }

    @Test
    void shortWordsTieToTheNextAndTheLastTwoWordsTie() {
        assertEquals("Applying Decision Theory to Create Transparent and Reproducible Regulatory Decisions",
                TitleLines.tie(REGULATORY));
        assertEquals("A Theory of the Mind", TitleLines.tie("A Theory of the Mind"));
        assertEquals("Two Words", TitleLines.tie("Two Words"), "a title of two words is left as it is");
        assertEquals("*Of* Mice and Men", TitleLines.tie("*Of* Mice and Men"), "markup around a short word is not the word");
        assertEquals(REGULATORY.length(), TitleLines.tie(REGULATORY).length(), "a tie stands where a space stood");
    }

    @Test
    void theRegulatoryTitleBreaksByPhraseIntoBalancedLines() {
        // in a measure of 39 characters it sets in three lines whatever the breaker; balanced, the first takes "to Create"
        assertEquals(List.of("Applying Decision Theory to Create", "Transparent and Reproducible", "Regulatory Decisions"), lines(REGULATORY, 39));
        assertEquals(List.of("Applying Decision Theory to Create", "Transparent and Reproducible", "Regulatory Decisions"), lines(REGULATORY, 45));
    }

    @Test
    void aTitleThatFitsStaysOneLine() {
        assertEquals(List.of(REGULATORY), lines(REGULATORY, 200));
    }

    @Test
    void balancingNarrowsTheMeasureRatherThanLeaveALongFirstLineAndAShortLast() {
        // greedy in 50: "Knowledge Representation for Clinical Decision" (46) / "Support Systems" (15); balanced, 37 and 24
        String title = "Knowledge Representation for Clinical Decision Support Systems";
        assertEquals(List.of("Knowledge Representation for Clinical", "Decision Support Systems"), lines(title, 50));
    }

    @Test
    void noLineEndsOnAShortWordAndNoLineIsALoneLastWord() {
        for (double measure = 12; measure <= 80; measure++) {
            for (String line : lines(REGULATORY, measure)) {
                String last = line.substring(line.lastIndexOf(' ') + 1).toLowerCase(java.util.Locale.ROOT);
                assertEquals(false, TitleLines.BINDERS.contains(last), line + " in a measure of " + measure);
                assertEquals(false, line.equals("Decisions"), "a lone last word in a measure of " + measure);
            }
        }
    }

    @Test
    void theLinesCoverTheTextOnceEachTakingItsTrailingSpace() {
        List<int[]> lines = TitleLines.breaks(REGULATORY, (from, to) -> to - from, 39);
        assertEquals(0, lines.getFirst()[0]);
        for (int i = 1; i < lines.size(); i++) {
            assertEquals(lines.get(i - 1)[1], lines.get(i)[0]);
        }
        assertEquals(REGULATORY.length(), lines.getLast()[1]);
    }
}

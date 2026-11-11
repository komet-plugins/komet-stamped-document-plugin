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

import java.util.function.ToDoubleFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunningHeadsTest {

    private static final ToDoubleFunction<String> SIX = text -> text.length() * 6.0;
    private static final String TITLE = "Applying Decision Theory to Create Transparent and Reproducible Regulatory Decisions";

    @Test
    void theTitleStandsWholeWhereItHasRoom() {
        assertEquals(TITLE, RunningHeads.fit(TITLE, TITLE.length() * 6.0, SIX));
        assertEquals("Short", RunningHeads.fit("Short", 400, SIX));
    }

    @Test
    void aTitleWithTooLittleRoomIsCutAtAWordWithAnEllipsis() {
        assertEquals("Applying Decision Theory to Create Transparent and…", RunningHeads.fit(TITLE, 51 * 6.0, SIX), "the words that fit, the ellipsis counted");
        assertEquals("Applying Decision Theory to Create Transparent…", RunningHeads.fit(TITLE, 51 * 6.0 - 1, SIX), "a point short costs a word");
        assertEquals("Applying Decision Theory…", RunningHeads.fit(TITLE, 25 * 6.0, SIX), "down to three words");
        assertTrue(SIX.applyAsDouble(RunningHeads.fit(TITLE, 200, SIX)) <= 200);
    }

    @Test
    void aTitleThatCannotKeepThreeWordsIsLeftOut() {
        assertEquals(3, TypstWriter.HEAD_WORDS);
        assertEquals("", RunningHeads.fit(TITLE, 25 * 6.0 - 1, SIX), "two words of a title say nothing: the section alone is the head");
        assertEquals("", RunningHeads.fit(TITLE, -40, SIX), "a section wider than the measure leaves no room at all");
        assertEquals("", RunningHeads.fit("Two words", 30, SIX), "a short title is whole or absent, never cut");
        assertEquals("Two words", RunningHeads.fit("Two words", 54, SIX));
    }

    @Test
    void theWriterCutsTheTitleTheSameWay() {
        assertTrue(TypstWriter.HEAD_HELPER.contains("while n > 3 and measure(shown).width > room {"), TypstWriter.HEAD_HELPER);
        assertTrue(TypstWriter.HEAD_HELPER.contains("shown = words.slice(0, n).join(\" \") + \"…\""));
        assertTrue(TypstWriter.HEAD_HELPER.contains("if measure(shown).width > room { \"\" } else { shown }"));
        String typst = TypstWriter.write(new DocumentModel(TITLE, java.util.List.of(new DocumentModel.Paragraph("Prose."))), TypstWriter.PageSetup.DEFAULT);
        assertTrue(typst.contains(TypstWriter.HEAD_HELPER), "the helper comes with the running head");
        assertTrue(typst.contains("    set par(justify: false)\n    layout(size => grid(columns: (1fr, auto), align: (left, right), ike-fit(\"" + TITLE
                + "\", size.width - measure(current).width - 1.5em.to-absolute()), current))\n"), "one ragged line: the title gives way to the section: " + typst);
    }
}

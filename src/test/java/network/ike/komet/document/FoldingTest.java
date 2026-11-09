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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reader's folds over the Reading view's blocks: a section folds behind its heading, a run
 * behind its first block, folds never overlap, a block scrolled to is opened, and folds are
 * carried by keys across a rebuild.
 */
class FoldingTest {

    /**
     * A document of ten blocks: the title, a level-1 heading with two paragraphs and a level-2
     * section of two paragraphs under it, then another level-1 heading with two paragraphs.
     */
    private static final int[] LEVELS = {0, 1, -1, -1, 2, -1, -1, 1, -1, -1};
    private static final String[] KEYS = {"Title", "First", "p1", "p2", "Inner", "p3", "p4", "Second", "p5", "p6"};

    private static int levelAt(int index) {
        return LEVELS[index];
    }

    private static int indexOf(String key) {
        return List.of(KEYS).indexOf(key);
    }

    @Test
    void aSectionRunsToTheNextHeadingOfItsLevelOrHigher() {
        assertEquals(6, Folding.sectionEnd(FoldingTest::levelAt, LEVELS.length, 1), "the first section takes its inner section with it");
        assertEquals(6, Folding.sectionEnd(FoldingTest::levelAt, LEVELS.length, 4), "the inner section ends where its parent's sibling begins");
        assertEquals(9, Folding.sectionEnd(FoldingTest::levelAt, LEVELS.length, 7), "the last section runs to the end");
        assertEquals(9, Folding.sectionEnd(FoldingTest::levelAt, LEVELS.length, 0), "the title's section is the whole document");
    }

    @Test
    void aSectionFoldsBehindItsHeading() {
        Folding folding = new Folding();
        Folding.Fold fold = folding.foldSection(1, 6).orElseThrow();
        assertTrue(fold.atHeading());
        assertFalse(folding.hidden(1), "the heading stays");
        for (int i = 2; i <= 6; i++) {
            assertTrue(folding.hidden(i), "block " + i + " is folded");
        }
        assertFalse(folding.hidden(7));
        assertEquals(Optional.of(fold), folding.at(1));
        assertEquals(Optional.of(fold), folding.hiding(4));
        assertEquals(Optional.empty(), folding.at(2));
    }

    @Test
    void aHeadingWithNothingUnderItDoesNotFold() {
        Folding folding = new Folding();
        assertEquals(Optional.empty(), folding.foldSection(7, 7));
        assertTrue(folding.isEmpty());
    }

    @Test
    void aRunFoldsBehindItsFirstBlock() {
        Folding folding = new Folding();
        Folding.Fold fold = folding.foldRun(5, 2);
        assertEquals(new Folding.Fold(2, 2, 5), fold, "the run is taken in the blocks' order whichever end was chosen first");
        assertFalse(fold.atHeading());
        assertFalse(folding.hidden(2), "the first block stays, and shows what is folded");
        assertTrue(folding.hidden(3));
        assertTrue(folding.hidden(5));
        assertFalse(folding.hidden(6));
    }

    @Test
    void aNewFoldTakesThePlaceOfTheFoldsItTouches() {
        Folding folding = new Folding();
        folding.foldRun(2, 3);
        folding.foldSection(7, 9);
        folding.foldSection(1, 6);
        assertEquals(List.of(new Folding.Fold(1, 2, 6), new Folding.Fold(7, 8, 9)), folding.all(), "the run inside the section went into it");
        folding.foldRun(6, 8);
        assertEquals(List.of(new Folding.Fold(6, 6, 8)), folding.all(), "a run across two folds replaces both");
    }

    @Test
    void unfoldingAndRevealing() {
        Folding folding = new Folding();
        folding.foldSection(1, 6);
        folding.foldSection(7, 9);
        assertFalse(folding.unfold(2), "only an anchor unfolds");
        assertTrue(folding.unfold(1));
        assertFalse(folding.hidden(3));
        assertFalse(folding.reveal(3), "nothing hides it now");
        assertTrue(folding.reveal(8), "a block scrolled to opens the fold that hid it");
        assertTrue(folding.isEmpty());
    }

    @Test
    void foldsAreCarriedByKeys() {
        Folding folding = new Folding();
        folding.foldSection(7, 9);
        folding.foldRun(2, 3);
        List<String> keys = folding.keys(i -> KEYS[i]);
        assertEquals(List.of("p1\tp2", "Second"), keys, "a section by its heading, a run by its first and last block");

        Folding again = new Folding();
        again.restore(keys, FoldingTest::indexOf, FoldingTest::levelAt, LEVELS.length);
        assertEquals(folding.all(), again.all());
    }

    @Test
    void aKeyTheDocumentNoLongerAnswersIsDropped() {
        Folding folding = new Folding();
        folding.restore(List.of("Gone", "p1\tGone", "p2\tp1", "p1"), FoldingTest::indexOf, FoldingTest::levelAt, LEVELS.length);
        assertTrue(folding.isEmpty(), "a missing heading, a missing end, a run turned round, and a heading key naming a paragraph fold nothing");
    }

    @Test
    void aFoldWithNoKeyIsNotRemembered() {
        Folding folding = new Folding();
        folding.foldRun(2, 3);
        assertEquals(List.of(), folding.keys(i -> i == 3 ? "" : KEYS[i]));
    }
}

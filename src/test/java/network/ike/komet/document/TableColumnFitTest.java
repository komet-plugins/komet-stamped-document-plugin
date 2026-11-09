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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How a table's columns share a row's width on screen. */
class TableColumnFitTest {

    private static final List<Integer> WEIGHTS = List.of(7, 4, 4, 8);

    private static double sum(double[] share) {
        double sum = 0;
        for (double s : share) {
            sum += s;
        }
        return sum;
    }

    @Test
    @DisplayName("With room for every word, each column takes its weight's share")
    void weightsDecideWhenEveryWordFits() {
        double[] share = TableColumnFit.shares(WEIGHTS, new double[] {40, 30, 30, 60}, 460);
        assertEquals(140, share[0], 1e-9);
        assertEquals(80, share[1], 1e-9);
        assertEquals(80, share[2], 1e-9);
        assertEquals(160, share[3], 1e-9);
    }

    @Test
    @DisplayName("A column too narrow for its widest word takes that width, and the columns with room give it up in proportion")
    void aWordIsKeptWholeAtTheCostOfTheRoomyColumns() {
        // 391 wide: the weights give 119, 68, 68 and 136, and "scanned" in bold needs 74
        double[] least = {60, 74, 74, 80};
        double[] share = TableColumnFit.shares(WEIGHTS, least, 391);
        assertEquals(74, share[1], 1e-9);
        assertEquals(74, share[2], 1e-9);
        assertEquals(391, sum(share), 1e-9, "the columns fill the row");
        assertTrue(share[0] < 119 && share[0] > least[0], "the first column gives up some of its spare room: " + share[0]);
        assertTrue(share[3] < 136 && share[3] > least[3], "the last column gives up some of its spare room: " + share[3]);
        // each gives in proportion to what it had to spare: 59 and 56 of 115, for the 12 lacking
        assertEquals(119 - 12 * 59.0 / 115, share[0], 1e-9);
        assertEquals(136 - 12 * 56.0 / 115, share[3], 1e-9);
    }

    @Test
    @DisplayName("When the words alone need more than the row, the columns share it in proportion to those needs")
    void aRowTooNarrowForItsWordsSharesByNeed() {
        double[] share = TableColumnFit.shares(WEIGHTS, new double[] {100, 50, 50, 200}, 200);
        assertEquals(50, share[0], 1e-9);
        assertEquals(25, share[1], 1e-9);
        assertEquals(25, share[2], 1e-9);
        assertEquals(100, share[3], 1e-9);
    }
}

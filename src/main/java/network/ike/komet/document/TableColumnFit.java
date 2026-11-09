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

import java.util.List;

/**
 * How a table's columns share a width on screen. A column takes the share its {@code cols} weight
 * gives it, unless that is less than its widest word needs: a word split inside itself reads worse
 * than a column a little off its weight. The columns with room to spare give up what the others
 * lack, each in proportion to its spare room. When the words alone need more than the width, no
 * share can keep them whole, and the columns share the width in proportion to those needs.
 */
final class TableColumnFit {

    private TableColumnFit() {
    }

    /**
     * Shares a width among columns.
     *
     * @param weights the relative widths {@code cols} names, one for each column
     * @param least   the least width each column needs to keep its widest word whole
     * @param width   the width to share
     * @return each column's width; they sum to {@code width}
     */
    static double[] shares(List<Integer> weights, double[] least, double width) {
        int columns = weights.size();
        double[] share = new double[columns];
        double total = Math.max(1, weights.stream().mapToInt(Integer::intValue).sum());
        double needed = 0;
        for (int c = 0; c < columns; c++) {
            needed += least[c];
        }
        if (needed >= width) {
            for (int c = 0; c < columns; c++) {
                share[c] = needed <= 0 ? width / columns : width * least[c] / needed;
            }
            return share;
        }
        double over = -width;
        double spare = 0;
        for (int c = 0; c < columns; c++) {
            share[c] = Math.max(width * weights.get(c) / total, least[c]);
            over += share[c];
            spare += share[c] - least[c];
        }
        if (over > 0 && spare > 0) {
            for (int c = 0; c < columns; c++) {
                share[c] -= over * (share[c] - least[c]) / spare;
            }
        }
        return share;
    }
}

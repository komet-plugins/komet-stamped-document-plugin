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
 * share can keep them whole, and the columns share the width in proportion to those needs. A
 * screen need not come to that: {@link #holds} says whether a width holds the columns at all, and
 * a table it does not hold is drawn another way, a row's cells one under another.
 */
final class TableColumnFit {

    private TableColumnFit() {
    }

    /**
     * Whether a width holds a table's columns side by side, each at the least it needs. Narrower,
     * and every column is cut below what its words or its lines want: columns a few letters wide
     * and a page tall, which read worse than the same cells one under another.
     *
     * @param least the least width each column needs
     * @param width the width to share
     * @return whether the needs together are no more than the width
     */
    static boolean holds(double[] least, double width) {
        double needed = 0;
        for (double one : least) {
            needed += one;
        }
        return needed <= width + 0.01;
    }

    /**
     * The relative widths a table's columns are printed with: the weights {@code cols} names when
     * every column's share of the measure holds its widest word, and else the shares that do, as
     * weights in hundredths of a point. Typst sets a word no line can hold out past its cell's
     * edge, over the rule and into the cell beside it, and a page of JavaFX breaks it between
     * two letters; a column a little wider than its weight says has neither.
     *
     * @param weights the relative widths {@code cols} names, one for each column
     * @param least   the least width each column needs to keep its widest word whole, in points
     * @param width   the measure the table is set to, in points
     * @return the weights to print with: the same list when the columns fit as they are
     */
    static List<Integer> printed(List<Integer> weights, double[] least, double width) {
        double total = Math.max(1, weights.stream().mapToInt(Integer::intValue).sum());
        boolean fits = true;
        for (int c = 0; c < weights.size(); c++) {
            fits &= least[c] <= width * weights.get(c) / total + 0.01;
        }
        if (fits || width <= 0) {
            return weights;
        }
        double[] share = shares(weights, least, width);
        List<Integer> out = new java.util.ArrayList<>(share.length);
        for (double one : share) {
            out.add((int) Math.max(1, Math.round(one * 100)));
        }
        return List.copyOf(out);
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

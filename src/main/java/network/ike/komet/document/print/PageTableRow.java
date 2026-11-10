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

import java.util.ArrayList;
import java.util.List;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

/**
 * One row of a table on a page, drawn as Typst draws it: the cells side by side across the
 * content width, each its column's share of it, every cell stroked in a hairline and its text
 * inset, and each cell's text standing at the middle of the row's height. The row is as tall as
 * its tallest cell, which the texts decide once they are laid out in their widths, so the row
 * sizes itself when it lays its cells out.
 *
 * <p>The page sets the row's top where the first cell's first line would begin if that cell were
 * the tallest. When another cell is taller the first cell's text is centered lower, so the row
 * shifts itself up by half the difference, keeping that first line on the baseline the layout
 * gave it.
 */
public final class PageTableRow extends Region {

    /** Typst's table stroke, {@code 0.5pt + luma(150)}. */
    static final String STROKE = "#969696";

    private final List<StackPane> cells = new ArrayList<>();
    private final List<Integer> weights;
    private final double total;

    /**
     * @param cells   each column's text, or {@code null} for an empty cell
     * @param weights the table's relative column widths, one per column
     * @param first   whether this is the table's first row, which draws the table's top edge
     */
    public PageTableRow(List<Node> cells, List<Integer> weights, boolean first) {
        this.weights = List.copyOf(weights);
        double sum = 0;
        for (int weight : weights) {
            sum += Math.max(1, weight);
        }
        this.total = Math.max(1, sum);
        setMinWidth(0);
        for (int c = 0; c < weights.size(); c++) {
            StackPane cell = new StackPane();
            cell.setAlignment(Pos.CENTER_LEFT);
            cell.setMinSize(0, 0);
            cell.setStyle("-fx-background-color: transparent; -fx-border-color: " + STROKE + "; -fx-border-width: "
                    + (first ? 0.5 : 0) + " 0.5 0.5 " + (c == 0 ? 0.5 : 0) + ";");
            Node text = c < cells.size() ? cells.get(c) : null;
            if (text != null) {
                cell.getChildren().add(text);
            }
            this.cells.add(cell);
            getChildren().add(cell);
        }
    }

    /** @return a column's width at a row width */
    double columnWidth(int column, double width) {
        return width * Math.max(1, weights.get(column)) / total;
    }

    @Override
    protected double computePrefHeight(double width) {
        double w = width < 0 ? getWidth() : width;
        double tallest = 0;
        for (int c = 0; c < cells.size(); c++) {
            tallest = Math.max(tallest, cells.get(c).prefHeight(columnWidth(c, w)));
        }
        return Math.ceil(tallest);
    }

    @Override
    protected void layoutChildren() {
        double width = getWidth();
        double[] heights = new double[cells.size()];
        double tallest = 0;
        for (int c = 0; c < cells.size(); c++) {
            heights[c] = cells.get(c).prefHeight(columnWidth(c, width));
            tallest = Math.max(tallest, heights[c]);
        }
        tallest = Math.ceil(tallest);
        if (Math.abs(tallest - getHeight()) > 0.5) {
            // the page gives the row a height before its texts are laid out: the row takes its own once they are
            setHeight(tallest);
        }
        double x = 0;
        for (int c = 0; c < cells.size(); c++) {
            double w = columnWidth(c, width);
            cells.get(c).resizeRelocate(Math.round(x), 0, Math.ceil(w), tallest);
            x += w;
        }
        // the first cell's text is centered in the row: lift the row so its first line keeps its baseline;
        // an empty first cell has no line to keep, and the row stands where the page put it
        boolean firstHasText = !cells.isEmpty() && !cells.getFirst().getChildren().isEmpty();
        setTranslateY(firstHasText ? -(tallest - heights[0]) / 2 : 0);
    }
}

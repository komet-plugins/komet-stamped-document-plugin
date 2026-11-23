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
package network.ike.komet.document.surface;

import javafx.geometry.Orientation;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;

/**
 * Two labels on one baseline, the second beneath the first where the width does not hold both: a
 * heading and the time after it. The first is cut to the width there is.
 */
final class HeadingPane extends Pane {

    private final Label first;
    private final Label second;
    private final double gap;

    /**
     * Two labels.
     *
     * @param first  the heading
     * @param second what follows it
     * @param gap    the room between them on one line, in px
     */
    HeadingPane(Label first, Label second, double gap) {
        super(first, second);
        this.first = first;
        this.second = second;
        this.gap = gap;
    }

    /** Whether both labels fit on one line. */
    private boolean oneLine(double width) {
        return width < 0 || first.prefWidth(-1) + gap + second.prefWidth(-1) <= width + 0.5;
    }

    @Override
    public Orientation getContentBias() {
        return Orientation.HORIZONTAL;
    }

    @Override
    protected double computeMinWidth(double height) {
        return Math.max(first.minWidth(-1), second.minWidth(-1));
    }

    @Override
    protected double computePrefWidth(double height) {
        return first.prefWidth(-1) + gap + second.prefWidth(-1);
    }

    @Override
    protected double computeMinHeight(double width) {
        return computePrefHeight(width);
    }

    @Override
    protected double computePrefHeight(double width) {
        double inside = width < 0 ? -1 : width - getInsets().getLeft() - getInsets().getRight();
        double edges = getInsets().getTop() + getInsets().getBottom();
        if (!oneLine(inside)) {
            return edges + first.prefHeight(-1) + second.prefHeight(-1);
        }
        double above = Math.max(first.getBaselineOffset(), second.getBaselineOffset());
        return edges + above + Math.max(first.prefHeight(-1) - first.getBaselineOffset(),
                second.prefHeight(-1) - second.getBaselineOffset());
    }

    @Override
    protected void layoutChildren() {
        double left = getInsets().getLeft();
        double top = getInsets().getTop();
        double inside = getWidth() - left - getInsets().getRight();
        double firstHeight = first.prefHeight(-1);
        double secondHeight = second.prefHeight(-1);
        double firstWidth = Math.min(first.prefWidth(-1), inside);
        double secondWidth = Math.min(second.prefWidth(-1), inside);
        if (oneLine(inside)) {
            double above = Math.max(first.getBaselineOffset(), second.getBaselineOffset());
            first.resizeRelocate(left, top + above - first.getBaselineOffset(), firstWidth, firstHeight);
            second.resizeRelocate(left + firstWidth + gap, top + above - second.getBaselineOffset(), secondWidth, secondHeight);
        } else {
            first.resizeRelocate(left, top, firstWidth, firstHeight);
            second.resizeRelocate(left, top + firstHeight, secondWidth, secondHeight);
        }
    }
}

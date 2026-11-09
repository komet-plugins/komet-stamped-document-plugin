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
package network.ike.komet.document.diagram;

import network.ike.komet.document.DecisionFigure;
import network.ike.komet.document.DecisionFigureText;
import network.ike.komet.document.StructuredBlockKind;
import network.ike.komet.document.print.FigureNode;

import java.util.List;
import java.util.Optional;
import javafx.scene.Node;

/**
 * The {@code [graphviz]} block as a diagram of the document's own: the DOT it holds is read as
 * boxes and arrows ({@link DotGraph}) and drawn as a figure ({@link DiagramFigure}) that the
 * Reading view shows and the pages print, where the block was shown as its source. The block is
 * edited as its text.
 */
public final class DiagramBlockKind implements StructuredBlockKind {

    /** The block style, as the documents write it. */
    public static final String STYLE = "graphviz";

    @Override
    public String style() {
        return STYLE;
    }

    @Override
    public String name() {
        return "diagram";
    }

    @Override
    public Node view(Source source, Context context) {
        DecisionFigure figure = DiagramFigure.of(DotGraph.parse(source.content()));
        FittedFigureNode node = new FittedFigureNode(figure);
        node.getStyleClass().add("stamped-document-diagram");
        return node;
    }

    @Override
    public Optional<Editor> editor(Source source, Context context) {
        return Optional.empty();
    }

    /** The diagram on a page: the same figure, measured for the page's face. */
    @Override
    public List<DecisionFigure> figures(Source source, Context context) {
        try {
            DotGraph graph = DotGraph.parse(source.content());
            return List.of(DecisionFigureText.inSerif(() -> DiagramFigure.of(graph)));
        } catch (IllegalArgumentException unreadable) {
            return List.of();                                            // the page prints the source, as before
        }
    }

    /**
     * A figure drawn at its natural size when the column has room for it, and scaled down to the
     * column otherwise, never wider than what it is given and what the margins beside it lend.
     */
    static final class FittedFigureNode extends javafx.scene.layout.Region {
        private final DecisionFigure figure;
        private javafx.scene.layout.Pane drawn;
        private double drawnScale = -1;

        FittedFigureNode(DecisionFigure figure) {
            this.figure = figure;
            setMinWidth(0);
            redraw(1);
        }

        private void redraw(double scale) {
            if (drawnScale == scale) {
                return;
            }
            drawnScale = scale;
            // the Reading view's face is a sans, the one the figure's text was measured for
            drawn = FigureNode.of(figure, scale, "Helvetica");
            getChildren().setAll(drawn);
        }

        private double scaleAt(double width) {
            if (width <= 0 || figure.width() <= 0) {
                return 1;
            }
            javafx.geometry.Insets bleed = network.ike.komet.document.surface.DocumentSurface.bleedBeside(this, width);
            double available = width + bleed.getLeft() + bleed.getRight();
            return Math.min(1, available / figure.width());
        }

        @Override
        public javafx.geometry.Orientation getContentBias() {
            return javafx.geometry.Orientation.HORIZONTAL;
        }

        @Override
        protected double computePrefWidth(double height) {
            return figure.width();
        }

        @Override
        protected double computeMinWidth(double height) {
            return 0;
        }

        @Override
        protected double computePrefHeight(double width) {
            return Math.ceil(figure.height() * scaleAt(width));
        }

        @Override
        protected double computeMinHeight(double width) {
            return computePrefHeight(width);
        }

        @Override
        protected void layoutChildren() {
            double width = getWidth();
            double scale = scaleAt(width);
            redraw(scale);
            double drawnWidth = figure.width() * scale;
            // a figure that fits stays with the column; a wider one stands into the margins evenly
            double over = drawnWidth - width;
            javafx.geometry.Insets bleed = network.ike.komet.document.surface.DocumentSurface.bleedBeside(this, width);
            double x = over > 0 ? -Math.min(bleed.getLeft(), over / 2) : 0;
            drawn.resizeRelocate(x, 0, drawnWidth, figure.height() * scale);
        }
    }
}

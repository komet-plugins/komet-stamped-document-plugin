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

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.Node;
import javafx.scene.layout.Region;
import javafx.scene.transform.Scale;

/**
 * Shows one node magnified, or reduced, and laid out to fit: at a zoom of 1.5 the node is given
 * two thirds of this pane's width and height and drawn half as large again, so it fills the pane
 * exactly. What the node holds is laid out in the smaller room, so text wraps sooner and nothing
 * runs off the side, and everything in it grows together: text, marks, pictures, tables, pages.
 *
 * <p>The node knows nothing of the zoom. Its own coordinates are as they were, so a scroll pane
 * inside it scrolls and measures as before, and whatever it places by the screen, a menu or a
 * popover, lands where the pointer is.
 */
public final class ZoomPane extends Region {

    /** The least zoom: half size. */
    public static final double LEAST = 0.5;
    /** The greatest zoom: three times the size. */
    public static final double GREATEST = 3.0;
    /** The zooms a step goes through, as a browser's. */
    private static final double[] STEPS = {0.5, 0.67, 0.75, 0.8, 0.9, 1.0, 1.1, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0};

    private final Scale scale = new Scale(1, 1, 0, 0);
    private final DoubleProperty zoom = new SimpleDoubleProperty(this, "zoom", 1) {
        @Override
        protected void invalidated() {
            double to = clamp(get());
            if (to != get()) {
                set(to);
                return;
            }
            scale.setX(to);
            scale.setY(to);
            requestLayout();
        }
    };
    private Node content;

    /** An empty pane, at a zoom of 1. */
    public ZoomPane() {
        // what is drawn larger than the pane, by a rounding of its size, does not show outside it
        javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);
    }

    /**
     * A pane that shows a node, at a zoom of 1.
     *
     * @param content the node
     */
    public ZoomPane(Node content) {
        this();
        setContent(content);
    }

    /**
     * Shows a node in place of the one shown.
     *
     * @param node the node, or {@code null} to show none
     */
    public void setContent(Node node) {
        if (content != null) {
            content.getTransforms().remove(scale);
        }
        content = node;
        if (node == null) {
            getChildren().clear();
            return;
        }
        node.getTransforms().add(scale);
        getChildren().setAll(node);
    }

    /** @return the node shown, or {@code null} */
    public Node getContent() {
        return content;
    }

    /**
     * How large the node is drawn: 1 is its own size. A value outside {@value #LEAST} to
     * {@value #GREATEST} is brought within it.
     *
     * @return the property
     */
    public DoubleProperty zoomProperty() {
        return zoom;
    }

    /** @return how large the node is drawn */
    public double getZoom() {
        return zoom.get();
    }

    /** @param value how large the node is drawn */
    public void setZoom(double value) {
        zoom.set(value);
    }

    /**
     * Brings a zoom within what a pane shows.
     *
     * @param value a zoom
     * @return the zoom, no less than {@value #LEAST} and no more than {@value #GREATEST}; 1 for what is not a number
     */
    public static double clamp(double value) {
        return Double.isNaN(value) ? 1 : Math.clamp(value, LEAST, GREATEST);
    }

    /**
     * The zoom a step away from a zoom, through the steps a browser's zoom takes.
     *
     * @param from the zoom now
     * @param by   how many steps: more than zero for larger, less for smaller
     * @return the zoom stepped to, which is {@code from} at either end
     */
    public static double step(double from, int by) {
        double at = clamp(from);
        for (int n = Math.abs(by); n > 0; n--) {
            double next = at;
            if (by > 0) {
                for (double step : STEPS) {
                    if (step > at + 0.001) {
                        next = step;
                        break;
                    }
                }
            } else {
                for (int i = STEPS.length - 1; i >= 0; i--) {
                    if (STEPS[i] < at - 0.001) {
                        next = STEPS[i];
                        break;
                    }
                }
            }
            at = next;
        }
        return at;
    }

    /**
     * A zoom as a person reads it.
     *
     * @param value a zoom
     * @return its percentage, as {@code 125%}
     */
    public static String percent(double value) {
        return Math.round(clamp(value) * 100) + "%";
    }

    @Override
    protected void layoutChildren() {
        if (content == null) {
            return;
        }
        double by = zoom.get();
        double left = snappedLeftInset();
        double top = snappedTopInset();
        double width = Math.max(0, getWidth() - left - snappedRightInset());
        double height = Math.max(0, getHeight() - top - snappedBottomInset());
        // the node is given the room that, drawn at the zoom, fills the pane
        content.resizeRelocate(left, top, width / by, height / by);
    }

    @Override
    protected double computePrefWidth(double height) {
        double by = zoom.get();
        return content == null ? 0 : snappedLeftInset() + content.prefWidth(height < 0 ? -1 : height / by) * by + snappedRightInset();
    }

    @Override
    protected double computePrefHeight(double width) {
        double by = zoom.get();
        return content == null ? 0 : snappedTopInset() + content.prefHeight(width < 0 ? -1 : width / by) * by + snappedBottomInset();
    }

    @Override
    protected double computeMinWidth(double height) {
        return 0;
    }

    @Override
    protected double computeMinHeight(double width) {
        return 0;
    }
}

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

import javafx.geometry.Bounds;
import javafx.scene.layout.Region;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A zoomed node is laid out in the room that, drawn at the zoom, fills the pane: so what it holds
 * wraps to fit and nothing runs off the side.
 */
class ZoomPaneTest {

    private static ZoomPane shown(Region content, double zoom) {
        ZoomPane pane = new ZoomPane(content);
        pane.setZoom(zoom);
        pane.resize(600, 400);
        pane.layout();
        return pane;
    }

    @Test
    void atItsOwnSizeTheNodeFillsThePane() {
        Region content = new Region();
        shown(content, 1);
        assertEquals(600, content.getWidth(), 0.001);
        assertEquals(400, content.getHeight(), 0.001);
    }

    @Test
    void magnifiedTheNodeIsLaidOutSmallerAndDrawnToFillThePane() {
        Region content = new Region();
        ZoomPane pane = shown(content, 1.5);
        assertEquals(400, content.getWidth(), 0.001, "two thirds of the pane's width, so its text wraps sooner");
        assertEquals(400 / 1.5, content.getHeight(), 0.001);
        Bounds drawn = content.getBoundsInParent();
        assertEquals(0, drawn.getMinX(), 0.001, "drawn from the pane's corner");
        assertEquals(0, drawn.getMinY(), 0.001);
        assertEquals(600, drawn.getWidth(), 0.001, "and half as large again, it fills the pane");
        assertEquals(400, drawn.getHeight(), 0.001);
        assertEquals(1.5, pane.getZoom(), 0.001);
    }

    @Test
    void reducedTheNodeIsLaidOutLarger() {
        Region content = new Region();
        shown(content, 0.5);
        assertEquals(1200, content.getWidth(), 0.001);
        assertEquals(600, content.getBoundsInParent().getWidth(), 0.001);
    }

    @Test
    void aZoomIsKeptWithinWhatAPaneShows() {
        ZoomPane pane = new ZoomPane(new Region());
        pane.setZoom(9);
        assertEquals(ZoomPane.GREATEST, pane.getZoom(), 0.001);
        pane.setZoom(0.01);
        assertEquals(ZoomPane.LEAST, pane.getZoom(), 0.001);
        pane.setZoom(Double.NaN);
        assertEquals(1, pane.getZoom(), 0.001, "what is not a number is the node's own size");
    }

    @Test
    void aStepGoesThroughTheZoomsABrowserTakes() {
        assertEquals(1.1, ZoomPane.step(1, 1), 0.001);
        assertEquals(1.25, ZoomPane.step(1, 2), 0.001);
        assertEquals(0.9, ZoomPane.step(1, -1), 0.001);
        assertEquals(1.25, ZoomPane.step(1.2, 1), 0.001, "from between two steps, to the next one");
        assertEquals(1.1, ZoomPane.step(1.2, -1), 0.001);
        assertEquals(ZoomPane.GREATEST, ZoomPane.step(ZoomPane.GREATEST, 1), 0.001, "and stops at either end");
        assertEquals(ZoomPane.LEAST, ZoomPane.step(ZoomPane.LEAST, -1), 0.001);
        assertEquals("125%", ZoomPane.percent(1.25));
        assertEquals("67%", ZoomPane.percent(0.67));
    }

    @Test
    void theNodeShownCanBeChanged() {
        Region first = new Region();
        Region second = new Region();
        ZoomPane pane = shown(first, 2);
        pane.setContent(second);
        pane.layout();
        assertSame(second, pane.getContent());
        assertEquals(300, second.getWidth(), 0.001, "the node that takes its place is zoomed as it was");
        assertEquals(0, first.getTransforms().size(), "and the node that left is drawn at its own size again");
        pane.setContent(null);
        assertNull(pane.getContent());
        assertEquals(0, pane.getChildrenUnmodifiable().size());
    }
}

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

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.Region;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A page's table row shares its width among its cells by the table's column weights, stands as
 * tall as its tallest cell, and keeps its first cell's first line where the page put it.
 */
class PageTableRowTest {

    @BeforeAll
    static void startJavaFx() {
        try {
            Class.forName("com.sun.glass.ui.monocle.MonoclePlatformFactory");
            System.setProperty("glass.platform", "Monocle");
            System.setProperty("monocle.platform", "Headless");
            System.setProperty("prism.order", "sw");
            System.setProperty("prism.text", "t2k");
            System.setProperty("java.awt.headless", "true");
        } catch (ClassNotFoundException ignored) {
            // the default toolkit
        }
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException alreadyRunning) {
            // started by another test
        }
    }

    private static <T> T onFx(Supplier<T> work) throws Exception {
        CompletableFuture<T> done = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                done.complete(work.get());
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        return done.get(20, TimeUnit.SECONDS);
    }

    private static Region text(double height) {
        Region text = new Region();
        text.setPrefHeight(height);
        text.setMinHeight(height);
        return text;
    }

    @Test
    void cellsShareTheWidthByWeightAndTheRowIsItsTallestCell() throws Exception {
        double[] out = onFx(() -> {
            Region first = text(20);
            Region second = text(60);
            PageTableRow row = new PageTableRow(List.of(first, second), List.of(1, 3), true, false);
            new Scene(new Group(row), 500, 300);
            row.resizeRelocate(0, 100, 400, 10);
            row.applyCss();
            row.layout();
            Node firstCell = first.getParent();
            Node secondCell = second.getParent();
            return new double[] {
                    firstCell.getLayoutBounds().getWidth(), secondCell.getLayoutBounds().getWidth(),
                    row.getHeight(), row.getTranslateY(), firstCell.getLayoutBounds().getHeight(),
                    first.getBoundsInParent().getMinY(), second.getBoundsInParent().getMinY()};
        });
        assertEquals(100, out[0], 1, "the first column takes a quarter of the row");
        assertEquals(300, out[1], 1, "the second takes three quarters");
        assertEquals(61, out[2], 0.5, "the row is as tall as its tallest cell and its hairline strokes: " + Arrays.toString(out));
        assertEquals(0, out[3], 0.01, "a body row's cells begin at its top: the row stands where the page put it, however short its first cell");
        assertEquals(61, out[4], 0.5, "every cell stands the row's height");
        assertEquals(out[6], out[5], 1, "the short cell's text begins where the tall cell's does, at the row's top: " + Arrays.toString(out));
    }

    @Test
    void aHeaderRowsCellsStandOnTheirLastLines() throws Exception {
        double[] out = onFx(() -> {
            Region first = text(20);
            Region second = text(60);
            PageTableRow row = new PageTableRow(List.of(first, second), List.of(1, 3), true, true);
            new Scene(new Group(row), 500, 300);
            row.resizeRelocate(0, 100, 400, 10);
            row.applyCss();
            row.layout();
            return new double[] {row.getHeight(), row.getTranslateY(), first.getBoundsInParent().getMaxY(), second.getBoundsInParent().getMaxY()};
        });
        assertEquals(61, out[0], 0.5, Arrays.toString(out));
        assertEquals(out[3], out[2], 1, "a short head ends where a head of more lines ends, at the row's foot: " + Arrays.toString(out));
        assertEquals(-40, out[1], 0.5, "its first line stands that much lower than the row's top, so the row lifts by the difference to keep its baseline");
    }

    @Test
    void anEmptyCellStillTakesItsColumn() throws Exception {
        double[] out = onFx(() -> {
            Region only = text(30);
            PageTableRow row = new PageTableRow(Arrays.asList(null, only), List.of(1, 1), false, false);
            new Scene(new Group(row), 500, 300);
            row.resizeRelocate(0, 0, 200, 10);
            row.applyCss();
            row.layout();
            return new double[] {only.getParent().getLayoutX(), row.getHeight(), row.getTranslateY()};
        });
        assertEquals(100, out[0], 1, "the text's cell begins after the empty one");
        assertEquals(31, out[1], 0.5, "the cell and its strokes");
        assertEquals(0, out[2], 0.5, "an empty first cell has no line to keep: the row stands where the page put it");
    }
}

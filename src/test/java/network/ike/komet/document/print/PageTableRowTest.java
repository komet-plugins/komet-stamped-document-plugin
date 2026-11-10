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
            PageTableRow row = new PageTableRow(List.of(first, second), List.of(1, 3), true);
            new Scene(new Group(row), 500, 300);
            row.resizeRelocate(0, 100, 400, 10);
            row.applyCss();
            row.layout();
            Node firstCell = first.getParent();
            Node secondCell = second.getParent();
            return new double[] {
                    firstCell.getLayoutBounds().getWidth(), secondCell.getLayoutBounds().getWidth(),
                    row.getHeight(), row.getTranslateY(), firstCell.getLayoutBounds().getHeight()};
        });
        assertEquals(100, out[0], 1, "the first column takes a quarter of the row");
        assertEquals(300, out[1], 1, "the second takes three quarters");
        assertEquals(61, out[2], 0.5, "the row is as tall as its tallest cell and its hairline strokes: " + Arrays.toString(out));
        assertEquals(-20.5, out[3], 0.5, "a shorter first cell is centered, so the row lifts by half the difference to keep its first line");
        assertEquals(61, out[4], 0.5, "every cell stands the row's height");
    }

    @Test
    void anEmptyCellStillTakesItsColumn() throws Exception {
        double[] out = onFx(() -> {
            Region only = text(30);
            PageTableRow row = new PageTableRow(Arrays.asList(null, only), List.of(1, 1), false);
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

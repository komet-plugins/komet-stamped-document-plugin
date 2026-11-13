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

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.Region;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A long document is built as it is read: blocks near the viewport stand built, blocks far from
 * it stand as placeholders of their expected height, and scrolling builds and lets go.
 */
class DocumentSurfaceVirtualTest {

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

    private static DocumentBlock block(int index, AtomicInteger built) {
        Region body = new Region();
        body.setPrefHeight(100);
        body.setMinHeight(100);
        return DocumentBlock.of(body, "block " + index).withEstimatedHeight(100).whenRealized(built::incrementAndGet);
    }

    @Test
    void aLongDocumentBuildsOnlyWhatIsNearTheViewport() throws Exception {
        AtomicInteger built = new AtomicInteger();
        DocumentSurface surface = onFx(() -> {
            DocumentSurface s = new DocumentSurface(null);
            List<DocumentBlock> blocks = new ArrayList<>();
            for (int i = 0; i < 400; i++) {
                blocks.add(block(i, built));
            }
            new Scene(s, 600, 500);
            s.setBlocks(blocks);
            return s;
        });
        settle(surface);
        long realized = onFx(() -> count(surface));
        assertTrue(realized > 0 && realized < 60, "a few screens' worth are built, not all 400: " + realized);
        assertTrue(onFx(() -> surface.block(0).isRealized()), "the first block is built");
        assertFalse(onFx(() -> surface.block(399).isRealized()), "the last is not");
        assertEquals(realized, built.get(), "the host's work ran once per built block");

        // scrolled to the end: the end is built and the start let go
        onFx(() -> {
            surface.revealBlock(399);
            return null;
        });
        settle(surface);
        assertTrue(onFx(() -> surface.block(399).isRealized()), "the last block is built after scrolling to it");
        assertFalse(onFx(() -> surface.block(0).isRealized()), "the first is let go, far from the viewport");
        assertTrue(onFx(() -> count(surface)) < 80, "still only a few screens' worth are built");
        double height = onFx(() -> surface.block(0).stackNode().getBoundsInParent().getHeight());
        assertEquals(100, height, 1, "a block let go keeps the room it stood at");
    }

    @Test
    void aShortDocumentIsBuiltWhole() throws Exception {
        AtomicInteger built = new AtomicInteger();
        DocumentSurface surface = onFx(() -> {
            DocumentSurface s = new DocumentSurface(null);
            List<DocumentBlock> blocks = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                blocks.add(block(i, built));
            }
            new Scene(s, 600, 500);
            s.setBlocks(blocks);
            return s;
        });
        settle(surface);
        assertEquals(20, onFx(() -> count(surface)));
        assertEquals(20, built.get());
    }

    @Test
    void aPinnedBlockStaysBuilt() throws Exception {
        AtomicInteger built = new AtomicInteger();
        DocumentSurface surface = onFx(() -> {
            DocumentSurface s = new DocumentSurface(null);
            List<DocumentBlock> blocks = new ArrayList<>();
            for (int i = 0; i < 400; i++) {
                blocks.add(block(i, built));
            }
            new Scene(s, 600, 500);
            s.setBlocks(blocks);
            return s;
        });
        settle(surface);
        onFx(() -> {
            surface.block(0).setPinned(true);
            surface.revealBlock(399);
            return null;
        });
        settle(surface);
        assertTrue(onFx(() -> surface.block(0).isRealized()), "a pinned block, edited for one, is built and stays built far from the viewport");
        assertTrue(onFx(() -> surface.block(399).isRealized()));
    }

    /**
     * A block that is built 60 high where it was expected to stand 100 high. A real document's
     * blocks are expected at a height worked out from their source lines, which is seldom the
     * height they are built at.
     */
    private static DocumentBlock misjudged(int index) {
        Region body = new Region();
        body.setPrefHeight(60);
        body.setMinHeight(60);
        body.setMaxHeight(60);
        return DocumentBlock.of(body, "block " + index).withEstimatedHeight(100);
    }

    /** Where a block's top is in the window. */
    private static double screenTop(DocumentSurface surface, int index) {
        javafx.scene.Node node = surface.block(index).stackNode();
        return node.localToScene(node.getBoundsInLocal()).getMinY();
    }

    /** Lets the window's own pulses lay the surface out, look again and keep the reader's place. */
    private static void pulses() throws Exception {
        for (int i = 0; i < 8; i++) {
            Thread.sleep(40);
            onFx(() -> null);
        }
    }

    @Test
    void aBuiltBlockLetsItsStandInsRoomGoWithoutWaitingForTheReaderToMove() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("Monocle".equals(System.getProperty("glass.platform")), "a window of the test's own");
        DocumentSurface surface = onFx(() -> {
            DocumentSurface s = new DocumentSurface(null);
            List<DocumentBlock> blocks = new ArrayList<>();
            for (int i = 0; i < 400; i++) {
                blocks.add(misjudged(i));
            }
            javafx.stage.Stage stage = new javafx.stage.Stage();
            stage.setScene(new Scene(s, 600, 500));
            stage.show();
            s.setBlocks(blocks);
            return s;
        });
        // nothing moves and nothing is asked of the window: the blocks in view come to their own height by themselves
        Thread.sleep(600);
        assertTrue(onFx(() -> surface.block(0).isRealized()));
        assertEquals(60, onFx(() -> surface.block(0).stackNode().getBoundsInParent().getHeight()), 1,
                "a built block stands at its own height soon after it is built, and not at its stand-in's until the next scroll");
    }

    @Test
    void theTextInViewStaysInPlaceWhenBlocksAboveItAreBuilt() throws Exception {
        // pulses need a window that is showing, and a window of a real toolkit would open on the person's screen
        org.junit.jupiter.api.Assumptions.assumeTrue("Monocle".equals(System.getProperty("glass.platform")), "a window of the test's own");
        DocumentSurface surface = onFx(() -> {
            DocumentSurface s = new DocumentSurface(null);
            List<DocumentBlock> blocks = new ArrayList<>();
            for (int i = 0; i < 400; i++) {
                blocks.add(misjudged(i));
            }
            javafx.stage.Stage stage = new javafx.stage.Stage();
            stage.setScene(new Scene(s, 600, 500));
            stage.show();
            s.setBlocks(blocks);
            return s;
        });
        pulses();
        // a jump into the middle of the document, as a link in its contents makes: the blocks far above are not built
        onFx(() -> {
            surface.revealBlock(200);
            return null;
        });
        pulses();
        assertFalse(onFx(() -> surface.block(170).isRealized()), "the blocks far above the place jumped to are stand-ins still");
        javafx.scene.control.ScrollPane scroll = (javafx.scene.control.ScrollPane) onFx(() -> surface.lookup(".claude-doc-surface"));
        javafx.scene.layout.Region stack = (javafx.scene.layout.Region) onFx(() -> surface.block(0).stackNode().getParent());
        double heightAtFirst = onFx(stack::getHeight);

        // scrolled up 40 at a time, as a wheel or a trackpad does: block 200 moves down the window by 40 each time, and by no more
        double was = onFx(() -> screenTop(surface, 200));
        for (int step = 1; step <= 60; step++) {
            onFx(() -> {
                double range = stack.getHeight() - scroll.getViewportBounds().getHeight();
                scroll.setVvalue(Math.clamp(scroll.getVvalue() - 40 / range, 0, 1));
                return null;
            });
            pulses();
            double now = onFx(() -> screenTop(surface, 200));
            assertEquals(was + 40, now, 1, "step " + step + ": the text moved by what was scrolled, though blocks above it were built");
            was = now;
        }
        assertTrue(onFx(() -> surface.block(170).isRealized()), "blocks above were built on the way up");
        assertTrue(onFx(stack::getHeight) < heightAtFirst - 400,
                "and stood lower than they were expected to: the document is shorter by what they were misjudged by");
    }

    private static long count(DocumentSurface surface) {
        long n = 0;
        for (int i = 0; i < surface.blockCount(); i++) {
            if (surface.block(i).isRealized()) {
                n++;
            }
        }
        return n;
    }

    /** A few pulses, for the surface to lay out and look again. */
    private static void settle(DocumentSurface surface) throws Exception {
        for (int i = 0; i < 6; i++) {
            onFx(() -> {
                surface.applyCss();
                surface.layout();
                return null;
            });
        }
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> body) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                result.set(body.call());
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        if (!done.await(30, TimeUnit.SECONDS)) {
            fail("the JavaFX thread did not answer");
        }
        if (failure.get() instanceof Exception e) {
            throw e;
        }
        if (failure.get() instanceof Error e) {
            throw e;
        }
        return result.get();
    }
}

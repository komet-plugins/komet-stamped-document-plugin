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

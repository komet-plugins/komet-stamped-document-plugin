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
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import network.ike.komet.document.HeadlessFx;
import network.ike.komet.document.surface.MarkdownRichText.Entry;
import network.ike.komet.document.surface.MarkdownRichText.Role;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A turn's heading says when the turn was said: after the name that opens a reply, and after the
 * title of the exchange a question opens, on screen and on a page.
 */
class TurnHeadingTest {

    private static final ZoneId PACIFIC = ZoneId.of("America/Los_Angeles");

    @BeforeAll
    static void startJavaFx() {
        HeadlessFx.select();
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException alreadyRunning) {
            // started by another test
        }
        // Every control here is made on the JavaFX thread. One made on this thread while the toolkit starts
        // can deadlock with it, each waiting for a class the other is initializing.
        onFx(() -> null);
    }

    private static Instant at(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 30, 0, PACIFIC).toInstant();
    }

    @Test
    void aTimeReadsAsAPersonWouldSayIt() {
        assertEquals("Nov 23rd, 11:42 am", TurnTime.text(at(2026, 11, 23, 11, 42), PACIFIC, false));
        assertEquals("Nov 23rd, 2026, 11:42 am", TurnTime.text(at(2026, 11, 23, 11, 42), PACIFIC, true));
        assertEquals("Sep 1st, 12:05 am", TurnTime.text(at(2026, 9, 1, 0, 5), PACIFIC, false), "midnight");
        assertEquals("May 2nd, 12:00 pm", TurnTime.text(at(2026, 5, 2, 12, 0), PACIFIC, false), "noon");
        assertEquals("Jun 11th, 3:09 pm", TurnTime.text(at(2026, 6, 11, 15, 9), PACIFIC, false), "eleventh, not eleven-first");
        assertEquals("Nov 23rd, 2:42 pm", TurnTime.text(at(2026, 11, 23, 11, 42), ZoneId.of("America/New_York"), false),
                "in the time zone it is read in");
    }

    @Test
    void theYearIsSaidOnlyWhenItIsAnotherYear() {
        int year = ZonedDateTime.now().getYear();
        Instant now = Instant.now();
        assertEquals(TurnTime.text(now, ZoneId.systemDefault(), false), TurnTime.onScreen(now));
        Instant earlier = at(year - 2, 3, 3, 9, 0);
        assertTrue(TurnTime.onScreen(earlier).contains(", " + (year - 2) + ", "), TurnTime.onScreen(earlier));
        assertTrue(TurnTime.onPage(now).contains(", " + now.atZone(ZoneId.systemDefault()).getYear() + ", "),
                "a page is read in later years");
    }

    @Test
    void aReplyIsHeadedByItsNameAndTime() {
        Instant said = Instant.now();
        DocumentBlock heading = onFx(() -> new BlockFactory((dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator) null, 13)
                .turnBlocks(new Entry(Role.ASSISTANT, "Four.", true, said)).getFirst());
        assertEquals("Komet Assistant: " + TurnTime.onScreen(said), heading.plainText(), "find reads it as it is shown");
        assertEquals(List.of("Komet Assistant:", TurnTime.onScreen(said)), texts(heading.node()));
    }

    @Test
    void aReplyWithNoTimeIsHeadedByItsNameAlone() {
        DocumentBlock heading = onFx(() -> new BlockFactory((dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator) null, 13)
                .turnBlocks(new Entry(Role.ASSISTANT, "Four.", true)).getFirst());
        assertEquals("Komet Assistant", heading.plainText());
        assertEquals(List.of("Komet Assistant"), texts(heading.node()));
    }

    @Test
    void aPageSaysTheYear() {
        Instant said = Instant.now();
        List<DocumentBlock> blocks = onFx(() -> BlockFactory.forPrint(null, 11, "Open Sans")
                .turnBlocks(Entry.user("What is two and two?").saidAt(said)));
        assertEquals("You: " + TurnTime.onPage(said), blocks.getFirst().plainText());
    }

    @Test
    void theTimeGoesBeneathTheNameWhereTheyDoNotFitTogether() {
        onFx(() -> {
            Label name = new Label("Komet Assistant:");
            Label time = new Label("Nov 23rd, 11:42 am");
            HeadingPane pane = new HeadingPane(name, time, 5);
            new Scene(new StackPane(pane), 600, 200);
            pane.getParent().applyCss();
            double together = name.prefWidth(-1) + 5 + time.prefWidth(-1);

            pane.resize(together + 20, pane.prefHeight(together + 20));
            pane.layout();
            assertEquals(name.getLayoutX() + name.getWidth() + 5, time.getLayoutX(), 0.6, "beside the name");
            assertEquals(name.getLayoutY() + name.getBaselineOffset(), time.getLayoutY() + time.getBaselineOffset(), 0.6,
                    "on one baseline");
            double oneLine = pane.prefHeight(together + 20);

            double narrow = together - 20;
            pane.resize(narrow, pane.prefHeight(narrow));
            pane.layout();
            assertEquals(0, time.getLayoutX(), 0.6, "beneath the name");
            assertTrue(time.getLayoutY() >= name.getHeight() - 0.6);
            assertTrue(pane.prefHeight(narrow) > oneLine * 1.5, "with room for both lines");
            assertTrue(name.getWidth() <= narrow + 0.6 && time.getWidth() <= narrow + 0.6, "inside the width");
            return null;
        });
    }

    @Test
    void anExchangeSaysWhenItWasAskedAndKeepsItsTitle() {
        Instant asked = Instant.now();
        DocumentSurface surface = onFx(() -> {
            DocumentSurface s = new DocumentSurface(new BlockFactory((dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator) null, 13));
            new Scene(s, 500, 400);
            s.setTurns(List.of(Entry.user("What is two and two?").saidAt(asked),
                    new Entry(Role.ASSISTANT, "Four.", true, asked.plusSeconds(20)),
                    Entry.user("And three and three?")));
            s.applyCss();
            s.layout();
            return s;
        });
        assertEquals(List.of("▾", "P1", "What is two and two?", TurnTime.onScreen(asked)), texts(surface.block(0).node()));
        onFx(() -> {
            surface.setExchangeTitle(0, "Simple addition");
            return null;
        });
        assertEquals(List.of("▾", "P1", "Simple addition", TurnTime.onScreen(asked)), texts(surface.block(0).node()),
                "a title that comes later is shown before the time");
        Node band = onFx(() -> surface.block(0).node());
        onFx(() -> {
            surface.setAllExchangesCollapsed(true);
            return null;
        });
        assertEquals("▸", texts(band).getFirst(), "the exchange folds as before");
        int last = onFx(() -> surface.turns().size());
        assertEquals(3, last);
        List<String> second = texts(onFx(() -> lastBand(surface)));
        assertEquals(List.of("▸", "P2", "And three and three?"), second, "a question with no time is headed as before");
    }

    @Test
    void aTurnKeepsItsTimeWhenItsTextIsRestated() {
        Instant said = Instant.now();
        Entry entry = new Entry(Role.ASSISTANT, "before", true, said);
        assertEquals(new Entry(Role.ASSISTANT, "after", true, said), entry.restated("after"));
        assertSame(Role.USER, Entry.user("a question").saidAt(said).role());
        assertNull(new Entry(Role.ERROR, "failed", false).saidAt());
    }

    private static Node lastBand(DocumentSurface surface) {
        Node found = null;
        for (Node band : surface.lookupAll(".claude-exchange-header")) {
            if (texts(band).contains("P2")) {
                found = band;
            }
        }
        return found;
    }

    /** The labels of a heading, in order. */
    private static List<String> texts(Node node) {
        if (node instanceof Label label) {
            return List.of(label.getText());
        }
        if (node instanceof Parent parent) {
            return parent.getChildrenUnmodifiable().stream().flatMap(child -> texts(child).stream()).toList();
        }
        return List.of();
    }

    private static <T> T onFx(Supplier<T> work) {
        CompletableFuture<T> done = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                done.complete(work.get());
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        try {
            return done.get(30, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(e.getCause());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

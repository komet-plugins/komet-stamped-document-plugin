/*
 * Copyright © 2026 Knowledge Graphlet / IKE Network
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package network.ike.komet.document;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bar that settles a pending edit, with its real controls on the FX thread: what it says,
 * what it shows for each state, and what it asks of its card. How it looks on a card is checked
 * in a running Komet.
 */
class PendingEditBarTest {

    /** A card that records what the bar asked of it. */
    private static final class Asked implements PendingEditBar.Host {
        final List<String> asked = new ArrayList<>();

        @Override
        public void commitPending(String message, boolean sign) {
            asked.add("commit[" + message + "]" + (sign ? " signed" : " unsigned"));
        }

        @Override
        public void discardPending() {
            asked.add("discard");
        }

        @Override
        public void restorePending() {
            asked.add("restore");
        }

        @Override
        public void setSignsCommits(boolean sign) {
            asked.add("sign " + sign);
        }
    }

    @BeforeAll
    static void startFx() throws Exception {
        HeadlessFx.select();
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(30, TimeUnit.SECONDS), "the FX toolkit started");
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
        return done.get(30, TimeUnit.SECONDS);
    }

    @SuppressWarnings("unchecked")
    private static <N extends Node> N node(PendingEditBar bar, String id) {
        return (N) bar.getChildren().stream().filter(n -> id.equals(n.getId())).findFirst().orElseThrow();
    }

    @Test
    void theSummaryCountsTheEditedLinesAndTheDocumentsTheyAreIn() {
        assertEquals("1 line edited", PendingEditBar.summary(1, 1));
        assertEquals("12 lines edited", PendingEditBar.summary(12, 1));
        assertEquals("12 lines edited in 2 documents", PendingEditBar.summary(12, 2));
        assertEquals("Edit not committed", PendingEditBar.summary(0, 1), "an edit that only removed lines leaves none to count");
    }

    @Test
    void theQuestionBeforeDiscardingSaysHowManyEditedLinesWillBeLost() {
        assertEquals("Discard the 3 edited lines?", PendingEditBar.discardQuestion(3, 1));
        assertEquals("Discard the 1 edited line?", PendingEditBar.discardQuestion(1, 1));
        assertEquals("Discard the 7 edited lines in 2 documents?", PendingEditBar.discardQuestion(7, 2));
        assertEquals("Discard the edit that is not committed?", PendingEditBar.discardQuestion(0, 1), "an edit that only removed lines");
        assertEquals("The document will read as last committed. Restore as pending brings the discarded edit back.", PendingEditBar.discardConsequence(1));
        assertTrue(PendingEditBar.discardConsequence(2).startsWith("The documents will read"));
        assertEquals("Discarded 1 edited line. Restore as pending brings it back.", PendingEditBar.discarded(1));
        assertEquals("Discarded 2 edited lines. Restore as pending brings them back.", PendingEditBar.discarded(2));
        assertEquals("Discarded the edit. Restore as pending brings it back.", PendingEditBar.discarded(0));
    }

    @Test
    void anEmptyMessageTakesTheDraftAndATypedOneIsTheCommitsMessage() {
        String draft = "Edit a.adoc\n\nEdited in Komet: 1 line(s) written, 1 line(s) replaced or removed.\n";
        assertEquals(draft, PendingEditBar.messageOr("", draft));
        assertEquals(draft, PendingEditBar.messageOr("   ", draft));
        assertEquals(draft, PendingEditBar.messageOr(null, draft));
        assertEquals("Mark the dose bold\n", PendingEditBar.messageOr("  Mark the dose bold ", draft));
    }

    @Test
    void theBarIsThereOnlyWhileAnEditIsPendingOrOneCanBeRestored() throws Exception {
        List<String> seen = onFx(() -> {
            PendingEditBar bar = new PendingEditBar(new Asked());
            List<String> states = new ArrayList<>();
            states.add("new: " + bar.isVisible() + " " + bar.isManaged());
            bar.show(true, false, 3, 1);
            states.add("pending: " + bar.isVisible() + " " + ((Label) node(bar, "stampedDocumentEditedBadge")).getText()
                    + " commit=" + node(bar, "stampedDocumentCommit").isVisible() + " message=" + node(bar, "stampedDocumentCommitMessage").isVisible()
                    + " discard=" + node(bar, "stampedDocumentDiscard").isVisible() + " restore=" + node(bar, "stampedDocumentRestore").isVisible());
            bar.show(false, true, 0, 0);
            states.add("restorable: " + bar.isVisible() + " " + ((Label) node(bar, "stampedDocumentEditedBadge")).getText()
                    + " commit=" + node(bar, "stampedDocumentCommit").isManaged() + " message=" + node(bar, "stampedDocumentCommitMessage").isManaged()
                    + " discard=" + node(bar, "stampedDocumentDiscard").isManaged() + " restore=" + node(bar, "stampedDocumentRestore").isVisible());
            states.add("restorable is plain: " + bar.getStyleClass().contains("restore-only"));
            bar.show(true, true, 1, 1);
            states.add("pending again is not: " + bar.getStyleClass().contains("restore-only"));
            bar.show(false, false, 0, 0);
            states.add("settled: " + bar.isVisible() + " " + bar.isManaged());
            return states;
        });
        assertEquals(List.of("new: false false",
                "pending: true 3 lines edited commit=true message=true discard=true restore=false",
                "restorable: true An earlier edit can be restored commit=false message=false discard=false restore=true",
                "restorable is plain: true", "pending again is not: false",
                "settled: false false"), seen);
    }

    @Test
    void enterInTheMessageCommitsAsTheButtonDoesWithTheMessageAndTheSigningChoice() throws Exception {
        Asked card = new Asked();
        onFx(() -> {
            PendingEditBar bar = new PendingEditBar(card);
            bar.show(true, false, 1, 1);
            TextField message = node(bar, "stampedDocumentCommitMessage");
            message.setText("  Mark the dose bold ");
            message.getOnAction().handle(new javafx.event.ActionEvent());          // Enter in the field
            bar.showSigned(true);                                                   // the card's standing choice: not a change to remember
            bar.clearMessage();
            ((Button) node(bar, "stampedDocumentCommit")).fire();
            ((CheckBox) node(bar, "stampedDocumentCommitSign")).setSelected(false);  // the person's change: remembered
            ((Button) node(bar, "stampedDocumentDiscard")).fire();
            ((Button) node(bar, "stampedDocumentRestore")).fire();
            return null;
        });
        assertEquals(List.of("commit[Mark the dose bold] unsigned", "commit[] signed", "sign false", "discard", "restore"), card.asked);
    }

    @Test
    void aBarShownAgainIsEnabledSoACommitUnderWayDoesNotLeaveItDead() throws Exception {
        assertFalse(onFx(() -> {
            PendingEditBar bar = new PendingEditBar(new Asked());
            bar.show(true, false, 1, 1);
            bar.setDisable(true);
            bar.show(true, false, 2, 1);
            return bar.isDisable();
        }));
    }
}

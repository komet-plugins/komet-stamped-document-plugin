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

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * The bar at the bottom of a STAMPed Document card while an edit made in Komet is not yet
 * committed: how many lines are edited, a field for the commit's message, whether the commit is
 * signed, Commit, and Discard… at the far end. A mark over selected words is saved at once as an
 * uncommitted edit, so edits are pending often; the bar is where a person settles them, and it
 * is gone when nothing is pending, which leaves the card's toolbar to the document.
 *
 * <p>The message is one line and may be left empty: the commit then takes the draft the card
 * writes from what the edit changed. Enter in the field commits, as the button does.
 *
 * <p>After an edit was discarded, or a commit left part of one out, the bar offers Restore as
 * pending alone. The bar decides nothing: the card commits, discards and restores.
 */
final class PendingEditBar extends HBox {

    /** What the bar asks of its card. */
    interface Host {

        /**
         * Commits the pending edit.
         *
         * @param message the message typed, stripped; empty to take the card's draft
         * @param sign    whether to sign the commit
         */
        void commitPending(String message, boolean sign);

        /** Asks whether to discard the pending edit, and discards it on a yes. */
        void discardPending();

        /** Brings a discarded edit back as pending. */
        void restorePending();

        /**
         * Remembers whether commits are signed.
         *
         * @param sign whether to sign
         */
        void setSignsCommits(boolean sign);
    }

    private final Label summary = new Label();
    private final TextField message = new TextField();
    private final CheckBox sign = new CheckBox("Sign");
    private final Button commit = new Button("Commit");
    private final Button restore = new Button("Restore as pending");
    private final Button discard = new Button("Discard…");
    private final Region gap = new Region();
    private boolean showingSign;

    /**
     * A bar for a card, hidden until {@link #show} finds an edit pending or one to restore.
     *
     * @param host the card
     */
    PendingEditBar(Host host) {
        super(8);
        setId("stampedDocumentPendingBar");
        getStyleClass().add("stamped-pending-bar");
        setAlignment(Pos.CENTER_LEFT);
        summary.setId("stampedDocumentEditedBadge");
        summary.getStyleClass().add("stamped-pending-summary");
        summary.setMinWidth(Region.USE_PREF_SIZE);
        message.setId("stampedDocumentCommitMessage");
        message.setPromptText("Message (optional)");
        message.setTooltip(new Tooltip("What the edit is for, in a line. Left empty, the commit names the document and lists what changed. Enter commits."));
        message.setMinWidth(60);
        message.setOnAction(e -> commit.fire());
        HBox.setHgrow(message, Priority.ALWAYS);
        sign.setId("stampedDocumentCommitSign");
        sign.setTooltip(new Tooltip("Sign the commit with your key, so that it can be shown to be yours. The choice is remembered, and your annotations follow it."));
        sign.setMinWidth(Region.USE_PREF_SIZE);
        sign.selectedProperty().addListener((obs, was, is) -> {
            if (!showingSign) {
                host.setSignsCommits(is);
            }
        });
        commit.setId("stampedDocumentCommit");
        commit.getStyleClass().add("stamped-pending-commit");
        commit.setTooltip(new Tooltip("Write the document with your edits into its folder and record them there as your own git commit"));
        commit.setMinWidth(Region.USE_PREF_SIZE);
        commit.setOnAction(e -> host.commitPending(message.getText() == null ? "" : message.getText().strip(), sign.isSelected()));
        restore.setId("stampedDocumentRestore");
        restore.getStyleClass().add("stamped-pending-quiet");
        restore.setTooltip(new Tooltip("Bring back the lines of an edit that was discarded, or that a commit left out, as a pending edit of the document as now committed"));
        restore.setMinWidth(Region.USE_PREF_SIZE);
        restore.setOnAction(e -> host.restorePending());
        discard.setId("stampedDocumentDiscard");
        discard.getStyleClass().add("stamped-pending-quiet");
        discard.setTooltip(new Tooltip("Discard the edits that are not committed, so the document reads as it was last committed. You are asked first."));
        discard.setMinWidth(Region.USE_PREF_SIZE);
        discard.setOnAction(e -> host.discardPending());
        gap.setMinWidth(4);
        getChildren().addAll(summary, message, sign, commit, gap, restore, discard);
        show(false, false, 0, 0);
    }

    /**
     * Shows the bar for what is pending, or hides it when nothing is.
     *
     * @param pending    whether an edit is pending
     * @param restorable whether an edit that was discarded, or left out of a commit, can be restored
     * @param lines      the lines edited and not yet committed
     * @param documents  the documents the pending edits are in: the one opened and those it includes
     */
    void show(boolean pending, boolean restorable, long lines, int documents) {
        summary.setText(pending ? summary(lines, documents) : "An earlier edit can be restored");
        for (Region control : new Region[] {message, sign, commit, discard}) {
            control.setVisible(pending);
            control.setManaged(pending);
        }
        restore.setVisible(restorable);
        restore.setManaged(restorable);
        // With no message field to take the width, the gap puts Restore at the far end.
        HBox.setHgrow(gap, pending ? Priority.NEVER : Priority.ALWAYS);
        // An edit to restore asks for nothing, so its bar is plain; one that is pending is in the edited mark's amber.
        getStyleClass().remove("restore-only");
        if (!pending) {
            getStyleClass().add("restore-only");
        }
        commit.setTooltip(new Tooltip(documents > 1
                ? "Write the edited documents into their folder and record them there as your own git commits, one document to a commit. Each Commit records the next."
                : "Write the document with your edits into its folder and record them there as your own git commit"));
        setDisable(false);
        setVisible(pending || restorable);
        setManaged(pending || restorable);
    }

    /**
     * Shows whether commits are signed, without telling the card of a change.
     *
     * @param signed whether they are
     */
    void showSigned(boolean signed) {
        showingSign = true;
        try {
            sign.setSelected(signed);
        } finally {
            showingSign = false;
        }
    }

    /** Empties the message, after its commit was made or another document was opened. */
    void clearMessage() {
        message.clear();
    }

    /** Puts the caret in the message field. */
    void focusMessage() {
        message.requestFocus();
    }

    /**
     * How much is edited, for the bar: "1 line edited", "12 lines edited in 2 documents". An edit
     * that only removed lines leaves none to count, and reads "Edit not committed".
     *
     * @param lines     the lines edited and not yet committed
     * @param documents the documents they are in
     * @return the summary
     */
    static String summary(long lines, int documents) {
        if (lines <= 0) {
            return "Edit not committed";
        }
        return lines + (lines == 1 ? " line edited" : " lines edited") + (documents > 1 ? " in " + documents + " documents" : "");
    }

    /**
     * The question asked before discarding, naming what will be lost: "Discard the 3 edited
     * lines?".
     *
     * @param lines     the lines edited and not yet committed
     * @param documents the documents they are in
     * @return the question
     */
    static String discardQuestion(long lines, int documents) {
        String what = lines <= 0 ? "the edit that is not committed"
                : lines == 1 ? "the 1 edited line" : "the " + lines + " edited lines";
        return "Discard " + what + (lines > 0 && documents > 1 ? " in " + documents + " documents" : "") + "?";
    }

    /**
     * What discarding does, said under the question.
     *
     * @param documents the documents the pending edits are in
     * @return the consequence
     */
    static String discardConsequence(int documents) {
        return (documents > 1 ? "The documents" : "The document") + " will read as last committed. "
                + "Restore as pending brings the discarded edit back.";
    }

    /**
     * What the status line says once an edit is discarded.
     *
     * @param lines the edited lines that were discarded
     * @return the report
     */
    static String discarded(long lines) {
        return lines <= 0 ? "Discarded the edit. Restore as pending brings it back."
                : lines == 1 ? "Discarded 1 edited line. Restore as pending brings it back."
                : "Discarded " + lines + " edited lines. Restore as pending brings them back.";
    }

    /**
     * The message a commit is made with: what was typed, or the card's draft when nothing was.
     *
     * @param typed the message typed, which may be blank or {@code null}
     * @param draft the draft, written from what the edit changed
     * @return the message
     */
    static String messageOr(String typed, String draft) {
        return typed == null || typed.isBlank() ? draft : typed.strip() + "\n";
    }
}

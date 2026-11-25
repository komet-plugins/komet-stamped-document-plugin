/*
 * Copyright © 2026 IKE Network (support@ike.network)
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

import dev.ikm.komet.actions.KometActionResult;
import dev.ikm.komet.actions.KometActions;
import dev.ikm.komet.kview.controls.Toast;
import dev.ikm.komet.terms.KometConcept;
import dev.ikm.tinkar.common.alert.AlertObject;
import dev.ikm.tinkar.common.alert.AlertStreams;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.Coordinates;
import dev.ikm.tinkar.coordinate.edit.EditCoordinate;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import javafx.application.Platform;
import javafx.scene.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The person's gesture for {@link RemoveFromKnowledgeBase}, from the document card's menu or the
 * Documents tray (IKE-Network/ike-issues#1313). It asks no question and uses no Trash, since
 * opening a file again reads its document back in (KEC, 2026-11-22, decision 1). The action runs
 * off the JavaFX thread; its outcome is shown as a toast in the window it was asked from and kept
 * as a line in Warnings and Errors, and every card showing a removed document says it was removed.
 */
final class DocumentRemoval {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentRemoval.class);

    /** The gesture's name, as the menus show it and the Warnings and Errors line is titled. */
    static final String TITLE = "Remove from Knowledge Base";

    private DocumentRemoval() {
    }

    /**
     * Removes documents from the knowledge base, together, and reports how it went.
     *
     * @param from    a node in the window the person asked from, for the toast
     * @param anchors the documents' anchors
     * @param view    the view the action reads in
     * @param edit    the edit coordinate it is performed under; nothing is written under it
     * @param after   what to do with the result on the JavaFX thread, after it is reported
     */
    static void perform(Node from, List<PublicId> anchors, ViewCalculator view, EditCoordinate edit,
                        Consumer<KometActionResult> after) {
        CompletableFuture.supplyAsync(() -> KometActions.expect(KometConcept.REMOVE_FROM_KNOWLEDGE_BASE)
                        .perform(RemoveFromKnowledgeBase.input(anchors), view, edit))
                .whenComplete((result, failure) -> Platform.runLater(() -> {
                    if (failure != null) {
                        Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                        LOG.warn("Removing {} document(s) from the knowledge base failed", anchors.size(), cause);
                        String message = "Could not remove " + (anchors.size() == 1 ? "the document" : "the documents") + ": " + cause.getMessage();
                        report(from, false, message);
                        if (after != null) {
                            after.accept(KometActionResult.failed(RemoveFromKnowledgeBase.input(anchors), message));
                        }
                        return;
                    }
                    report(from, result.completed(), result.message());
                    if (result.completed()) {
                        LOG.info("{}: {}", TITLE, result.message());
                        StampedDocumentCard.documentsRemoved(Set.copyOf(anchors));
                    }
                    DocumentNavigatorTray.readAgain(from);
                    if (after != null) {
                        after.accept(result);
                    }
                }));
    }

    /**
     * Removes documents with the default view and edit coordinates, for a gesture that has no
     * view of its own, such as the Documents tray's.
     */
    static void perform(Node from, List<PublicId> anchors, Consumer<KometActionResult> after) {
        perform(from, anchors, Calculators.View.Default(), Coordinates.Edit.Default(), after);
    }

    /**
     * Shows the outcome as a toast in the window of a node, when it is still shown, and keeps it as
     * a line in Warnings and Errors: a note when it was done, a warning when it was not. Call on the
     * JavaFX thread.
     */
    static void report(Node from, boolean done, String message) {
        AlertStreams.getRoot().dispatch(AlertObject.makeOutcome(done, TITLE, message));
        if (from != null && from.getScene() != null && from.getScene().getWindow() != null) {
            new Toast(from).show(done ? Toast.Status.SUCCESS : Toast.Status.FAILURE, message);
        }
    }
}

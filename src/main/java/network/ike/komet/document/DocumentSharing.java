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
package network.ike.komet.document;

import dev.ikm.komet.actions.KometActionInput;
import dev.ikm.komet.actions.KometActionResult;
import dev.ikm.komet.actions.KometActions;
import dev.ikm.komet.kview.controls.Toast;
import dev.ikm.komet.terms.KometConcept;
import dev.ikm.tinkar.common.alert.AlertObject;
import dev.ikm.tinkar.common.alert.AlertStreams;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.edit.EditCoordinate;
import dev.ikm.tinkar.terms.ConceptFacade;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The person's gestures that share documents, make shared ones local, and delete them, from the document
 * card's menu or the Documents tray (IKE-Network/ike-issues#1313, step 4 of
 * {@code design-2026-11-22-knowledge-shared-local}). Each asks first, naming the documents and saying who
 * gets or loses them, since it acts for everyone who syncs the knowledge base. It runs off the JavaFX
 * thread, and its outcome is shown as a toast and kept as a line in Warnings and Errors.
 */
public final class DocumentSharing {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentSharing.class);

    private DocumentSharing() {
    }

    /**
     * Reads a file in as its document and shares it, after asking: for a conversation with the assistant,
     * which is shared as the document it is saved as (the second question before step 4). Call on the
     * JavaFX thread.
     *
     * @param from a node in the window the person asked from
     * @param file the file, in a git working tree
     * @param edit the edit coordinate of the person
     */
    public static void shareFile(Node from, java.nio.file.Path file, EditCoordinate edit) {
        CompletableFuture.supplyAsync(() -> {
            java.nio.file.Path root = design.knowledge.stamp.adoc.git.GitRepo.findRoot(file).map(found -> found.toAbsolutePath().normalize())
                    .orElseThrow(() -> new SharedDocuments.Refused(file.getFileName() + " is in a folder without git, so a shared copy could not follow it."));
            java.nio.file.Path absolute = file.toAbsolutePath().normalize();
            try {
                PublicId anchor = GitDocumentMaterializer.materialize(root, absolute).anchor();
                DocumentFiles.remember(anchor, root, root.relativize(absolute).toString().replace('\\', '/'), java.nio.file.Files.readAllLines(absolute));
                return anchor;
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }).whenComplete((anchor, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                report(from, Gesture.SHARE, false, message(failure));
            } else if (SharedDocuments.shared(anchor)) {
                report(from, Gesture.SHARE, true, DocumentScopes.name(anchor) + " is shared already. What was added goes to the others at the next sync.");
                StampedDocumentCard.sharedDataChanged();
            } else {
                perform(from, Gesture.SHARE, List.of(anchor), edit, null);
            }
        }));
    }

    /** A gesture: its name in a menu, what it is for, and its dialog. */
    enum Gesture {
        /** Shares documents with everyone who syncs. */
        SHARE(KometConcept.SHARE_DOCUMENT, "Share…", "Share this document, and the documents it includes, with everyone who syncs this knowledge base. Its file stays its record", "documentShareDialog"),
        /** Makes shared documents local. */
        MAKE_LOCAL(KometConcept.MAKE_DOCUMENT_LOCAL, "Make Local…", "Keep this document here and take it away from everyone else who syncs this knowledge base", "documentMakeLocalDialog"),
        /** Deletes shared documents for everyone, or documents kept only here. */
        DELETE(KometConcept.DELETE_DOCUMENT, "Delete Document…", "Delete this document, for everyone who syncs this knowledge base when it is shared", "documentDeleteDialog");

        final ConceptFacade action;
        final String title;
        final String tooltip;
        final String dialog;

        Gesture(ConceptFacade action, String title, String tooltip, String dialog) {
            this.action = action;
            this.title = title;
            this.tooltip = tooltip;
            this.dialog = dialog;
        }

        /** @return whether the gesture is offered for a document */
        boolean offered(PublicId anchor) {
            return switch (this) {
                case SHARE -> SharedDocuments.kind(anchor) == dev.ikm.tinkar.entity.boundary.DataKind.FILE;
                case MAKE_LOCAL -> SharedDocuments.shared(anchor);
                case DELETE -> SharedDocuments.shared(anchor) || SharedDocuments.keptHereOnly(anchor);
            };
        }
    }

    /**
     * Asks, then does what was asked, and reports how it went.
     *
     * @param from    a node in the window the person asked from
     * @param gesture what they asked
     * @param anchors the documents' anchors
     * @param edit    the edit coordinate of the person
     * @param after   told on the JavaFX thread whether it was done; may be {@code null}
     */
    static void perform(Node from, Gesture gesture, List<PublicId> anchors, EditCoordinate edit, Consumer<Boolean> after) {
        CompletableFuture.supplyAsync(() -> {
            List<PublicId> all = gesture == Gesture.SHARE ? SharedDocuments.withThoseItRefersTo(anchors).stream()
                    .filter(anchor -> !SharedDocuments.shared(anchor)).toList() : anchors;
            return new Asked(all, all.stream().map(DocumentScopes::name).toList(),
                    all.stream().anyMatch(SharedDocuments::shared), all.stream().anyMatch(anchor -> DocumentFiles.rootOf(anchor).isPresent()),
                    SharedDocuments.commentsInNoFile(all));
        }).whenComplete((asked, failure) -> Platform.runLater(() -> {
            if (failure != null || asked.anchors().isEmpty() || !confirm(from, gesture, asked)) {
                if (failure != null) {
                    report(from, gesture, false, message(failure));
                }
                if (after != null) {
                    after.accept(false);
                }
                return;
            }
            CompletableFuture.supplyAsync(() -> KometActions.expect(gesture.action)
                    .perform(DocumentGroupInput.of(gesture.action, asked.anchors()), Calculators.View.Default(), edit)).whenComplete((result, failed) -> Platform.runLater(() -> {
                boolean done = failed == null && result.completed();
                report(from, gesture, done, failed == null ? result.message() : message(failed));
                if (done) {
                    StampedDocumentCard.sharedDataChanged();       // which empties a card that showed what was deleted
                    DocumentNavigatorTray.readAgain(from);
                }
                if (after != null) {
                    after.accept(done);
                }
            }));
        }));
    }

    /**
     * What is asked about: the documents, their names, whether any is shared, whether the file of any is here,
     * and how many comments on them no file holds.
     */
    private record Asked(List<PublicId> anchors, List<String> names, boolean shared, boolean files, int commentsInNoFile) { }

    /** The name of the person an edit coordinate is for. */
    static String name(EditCoordinate edit) {
        return StampNames.name(Calculators.View.Default(), edit.getAuthorNidForChanges());
    }

    /**
     * Performs one of the actions on shared documents.
     *
     * @param input  the run's input
     * @param action the action performing it
     * @param act    what it does to the documents the input names
     * @return what the run did
     */
    static KometActionResult run(KometActionInput input, ConceptFacade action, Function<List<PublicId>, SharedDocuments.Done> act) {
        try {
            SharedDocuments.Done done = act.apply(DocumentGroupInput.anchors(input, action));
            return new KometActionResult(input.id(), KometConcept.ACTION_COMPLETED, Optional.empty(), done.report());
        } catch (SharedDocuments.Refused | DocumentGroupInput.Unreadable refused) {
            return KometActionResult.refused(input, refused.getMessage());
        } catch (RuntimeException failed) {
            LOG.warn("{} failed", action.description(), failed);
            return KometActionResult.failed(input, message(failed));
        }
    }

    private static boolean confirm(Node from, Gesture gesture, Asked asked) {
        int n = asked.names().size();
        String them = n == 1 ? asked.names().getFirst() : "these " + n + " documents";
        String header = switch (gesture) {
            case SHARE -> "Share " + them + " with everyone who syncs this knowledge base?";
            case MAKE_LOCAL -> "Make " + them + " local? Everyone else who syncs this knowledge base will lose " + (n == 1 ? "it." : "them.");
            case DELETE -> asked.shared() ? "Delete " + them + " for everyone who syncs this knowledge base?" : "Delete " + them + "?";
        };
        StringBuilder text = new StringBuilder();
        if (n > 1) {
            asked.names().stream().limit(8).forEach(name -> text.append(name).append('\n'));
            if (n > 8) {
                text.append("and ").append(n - 8).append(" more\n");
            }
            text.append('\n');
        }
        text.append(switch (gesture) {
            case SHARE -> "They get " + (n == 1 ? "it" : "them") + " when the knowledge base is next synced, and can read " + (n == 1 ? "it" : "them")
                    + " and comment on " + (n == 1 ? "it" : "them") + ". The " + (n == 1 ? "file stays" : "files stay") + " where " + (n == 1 ? "it is" : "they are")
                    + ": you edit and commit " + (n == 1 ? "it" : "them") + " as now, and the shared copy follows. Do not share anything about a real patient.";
            case MAKE_LOCAL -> (asked.files() ? "You keep it, read from its file." + (asked.commentsInNoFile() == 0 ? "" : asked.commentsInNoFile() == 1
                            ? " A comment that another person made is not in the file, and goes when you quit Komet."
                            : " " + asked.commentsInNoFile() + " comments that others made are not in the file, and go when you quit Komet.")
                            : "You keep it here, as data that never syncs.")
                    + " The others lose it, with what was said of it, at their next sync.";
            case DELETE -> asked.shared()
                    ? (asked.files() ? "Its file stays where it is. " : "") + "The others lose it, with what was said of it, at their next sync."
                    : "Nothing else contains it.";
        });
        ButtonType yes = new ButtonType(switch (gesture) {
            case SHARE -> "Share";
            case MAKE_LOCAL -> "Make Local";
            case DELETE -> "Delete";
        }, ButtonBar.ButtonData.OK_DONE);
        Alert ask = new Alert(gesture == Gesture.SHARE ? Alert.AlertType.CONFIRMATION : Alert.AlertType.WARNING, text.toString(), yes, ButtonType.CANCEL);
        if (from != null && from.getScene() != null) {
            ask.initOwner(from.getScene().getWindow());
        }
        ask.setTitle(gesture.title.replace("…", ""));
        ask.setHeaderText(header);
        ask.getDialogPane().setId(gesture.dialog);
        return ask.showAndWait().filter(answer -> answer == yes).isPresent();
    }

    private static void report(Node from, Gesture gesture, boolean done, String message) {
        LOG.info("{}: {}", gesture.title.replace("…", ""), message);
        AlertStreams.getRoot().dispatch(AlertObject.makeAdvisory(gesture.title.replace("…", ""), message));
        if (from != null && from.getScene() != null && from.getScene().getWindow() != null) {
            new Toast(from).show(done ? Toast.Status.SUCCESS : Toast.Status.FAILURE, message);
        }
    }

    private static Throwable cause(Throwable failure) {
        return failure.getCause() != null ? failure.getCause() : failure;
    }

    private static String message(Throwable failure) {
        Throwable cause = cause(failure);
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }
}

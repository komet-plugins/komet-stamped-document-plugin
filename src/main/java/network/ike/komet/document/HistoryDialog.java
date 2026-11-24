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

import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.StampEntity;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;
import network.ike.komet.document.GitDocumentMaterializer.StoredLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.LongConsumer;

/**
 * The History view (ike-lab-documents topic {@code dev-stamped-document-surface}, phase 4):
 * the commit list of the open file or of the whole repository, read through the person's own
 * git — SHA, author, committer where it is not the author, date and subject — pinned until closed. Selecting a commit highlights the
 * lines it introduced in the Reading view, through the store's STAMP for that commit. When
 * lines are selected, a second tab lists their STAMPs and annotations in force, the Show history
 * of the context menu.
 */
final class HistoryDialog {

    private static final Logger LOG = LoggerFactory.getLogger(HistoryDialog.class);
    private static final int LIMIT = 500;

    private HistoryDialog() { }

    /** A commit row of the table. */
    public static final class Row {
        private final SimpleStringProperty sha;
        private final SimpleStringProperty author;
        private final SimpleStringProperty committer;
        private final Optional<Committers.Committed> committed;
        private final SimpleStringProperty date;
        private final SimpleStringProperty summary;
        private final GitOperations.Commit commit;

        Row(GitOperations.Commit commit, Optional<Committers.Committed> committed) {
            this.commit = commit;
            this.committed = committed;
            this.committer = new SimpleStringProperty(committed.map(Committers.Committed::name).orElse(""));
            this.sha = new SimpleStringProperty(commit.shortSha());
            this.author = new SimpleStringProperty(commit.author());
            this.date = new SimpleStringProperty(commit.date());
            this.summary = new SimpleStringProperty(commit.summary());
        }

        public SimpleStringProperty shaProperty() {
            return sha;
        }

        public SimpleStringProperty authorProperty() {
            return author;
        }

        /** @return the committer when it is not the author, else empty */
        public SimpleStringProperty committerProperty() {
            return committer;
        }

        public SimpleStringProperty dateProperty() {
            return date;
        }

        public SimpleStringProperty summaryProperty() {
            return summary;
        }
    }

    /** The committer of a row's commit; a marked one in the mark's colour, with the whole of what is said in its tooltip. */
    private static final class CommitterCell extends TableCell<Row, String> {
        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            Optional<Committers.Committed> committed = empty || getTableRow() == null || getTableRow().getItem() == null
                    ? Optional.empty() : getTableRow().getItem().committed;
            setText(empty ? null : item);
            setStyle(committed.filter(Committers.Committed::differs).isPresent()
                    ? "-fx-text-fill: " + Committers.Committed.MARK_COLOUR + "; -fx-font-weight: bold;" : "");
            setTooltip(committed.map(by -> new Tooltip(by.text())).orElse(null));
        }
    }

    /**
     * Shows the view.
     *
     * @param owner        the owning window, or {@code null}
     * @param root         the working tree, or {@code null} when none is known (the commit list is then empty)
     * @param file         the open file relative to the root, or {@code null}
     * @param summaries    the document's commit summaries: the STAMP each SHA stands for
     * @param highlight    highlights the runs the STAMP with that nid introduced; 0 clears
     * @param selected     the selected lines, for the second tab; may be empty
     * @param view         the view that names authors
     * @param identicon    renders a STAMP's identicon
     * @param documentName the document's name, for the title
     */
    static void show(Window owner, Path root, Path file, CommitSummaries summaries, LongConsumer highlight, List<StoredLine> selected,
                     ViewCalculator view, Function<StampEntity<?>, Image> identicon, String documentName) {
        Map<String, Long> stampBySha = new HashMap<>();
        summaries.shaByStamp().forEach((stamp, sha) -> stampBySha.put(sha, stamp));

        TableView<Row> table = new TableView<>();
        table.setId("stampedDocumentHistoryTable");
        table.setPlaceholder(new Label(root == null ? "No working tree holds the document's file; Open file… names one." : "Reading the log …"));
        TableColumn<Row, String> sha = new TableColumn<>("Commit");
        sha.setCellValueFactory(r -> r.getValue().shaProperty());
        sha.setPrefWidth(80);
        TableColumn<Row, String> author = new TableColumn<>("Author");
        author.setCellValueFactory(r -> r.getValue().authorProperty());
        author.setPrefWidth(140);
        TableColumn<Row, String> date = new TableColumn<>("Date");
        date.setCellValueFactory(r -> r.getValue().dateProperty());
        date.setPrefWidth(90);
        TableColumn<Row, String> summary = new TableColumn<>("Summary");
        summary.setCellValueFactory(r -> r.getValue().summaryProperty());
        summary.setPrefWidth(380);
        // the committer is written where it is not the author, and marked where an assistant's work was not committed by its person
        TableColumn<Row, String> committer = new TableColumn<>("Committer");
        committer.setCellValueFactory(r -> r.getValue().committerProperty());
        committer.setCellFactory(column -> new CommitterCell());
        committer.setPrefWidth(140);
        table.getColumns().addAll(List.of(sha, author, committer, date, summary));
        table.getSelectionModel().selectedItemProperty().addListener((obs, was, is) -> {
            if (is == null) {
                highlight.accept(0);
                return;
            }
            Long stamp = stampBySha.get(is.commit.sha());
            highlight.accept(stamp == null ? 0 : stamp);
        });

        // The scope as one segmented control, file or repository: exactly one selected.
        String fileLabel = file == null ? "This file" : file.getFileName().toString();
        SegmentedControl<String> scope = new SegmentedControl<>(List.of(fileLabel, "Repository"), v -> v, null,
                v -> v.equals("Repository") ? "stampedDocumentHistoryRepository" : "stampedDocumentHistoryFile");
        if (file == null) {
            scope.select("Repository");
            scope.setDisable(true);
        }
        Label note = new Label("Selecting a commit highlights the lines it introduced in the Reading view.");
        note.setStyle("-fx-text-fill: #5f6b7a; -fx-font-size: 11;");
        HBox scopes = new HBox(10, scope, note);
        scopes.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        scopes.setPadding(new Insets(0, 0, 6, 0));
        Runnable load = () -> {
            boolean whole = scope.getValue().equals("Repository") || file == null;
            if (root == null) {
                table.setItems(FXCollections.observableArrayList());
                return;
            }
            CompletableFuture.supplyAsync(() -> {
                try {
                    return GitOperations.log(root, whole ? null : file, LIMIT);
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }).whenComplete((commits, failure) -> Platform.runLater(() -> {
                if (failure != null) {
                    table.setPlaceholder(new Label("git log failed: " + (failure.getCause() == null ? failure : failure.getCause()).getMessage()));
                    LOG.warn("Reading the history of {} failed", root, failure);
                    return;
                }
                table.setPlaceholder(new Label("No commits."));
                table.setItems(FXCollections.observableArrayList(commits.stream()
                        .map(commit -> new Row(commit, summaries.committers().of(view, commit.sha()))).toList()));
            }));
        };
        scope.valueProperty().addListener((obs, was, is) -> load.run());
        load.run();
        BorderPane commitsPane = new BorderPane(table);
        commitsPane.setTop(scopes);
        commitsPane.setPadding(new Insets(6));

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        Tab commitsTab = new Tab("Commits", commitsPane);
        tabs.getTabs().add(commitsTab);
        if (!selected.isEmpty()) {
            VBox box = new VBox(6);
            box.setPadding(new Insets(8));
            int shown = 0;
            for (StoredLine line : selected) {
                if (shown++ == 200) {
                    box.getChildren().add(new Label("… " + (selected.size() - 200) + " more line(s)"));
                    break;
                }
                box.getChildren().add(ProvenancePopover.content(line, view, summaries, identicon, line.text()));
            }
            ScrollPane scroll = new ScrollPane(box);
            scroll.setFitToWidth(true);
            tabs.getTabs().add(new Tab(selected.size() + " selected line(s)", scroll));
        }
        tabs.setPrefSize(860, 520);

        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("History");
        dialog.setHeaderText(documentName + (root == null ? "" : " — " + root.getFileName()));
        dialog.getDialogPane().setId("stampedDocumentHistoryDialog");
        java.net.URL css = HistoryDialog.class.getResource("/network/ike/komet/document/stamped-document.css");
        if (css != null) {
            dialog.getDialogPane().getStylesheets().add(css.toExternalForm());
        }
        dialog.getDialogPane().setContent(tabs);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.initModality(Modality.NONE);
        dialog.initOwner(owner);
        dialog.setOnHidden(e -> highlight.accept(0));
        dialog.show();
    }
}

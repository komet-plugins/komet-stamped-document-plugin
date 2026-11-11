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

import dev.ikm.tinkar.coordinate.Calculators;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import network.ike.komet.document.surface.MarkdownRichText;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The strip a STAMPed Document card shows while it materializes a file and the files it
 * includes: how far the run has come, the file being written, Pause or Resume, and Stop. An
 * include that cannot be found is listed here as soon as the run knows of it, and the run goes
 * on. A stopped run lists the files it did not reach. The text is rich text, so a person can
 * select and copy a file name out of it.
 *
 * <p>The strip is hidden while no run is under way. A run that wrote every file and found every
 * include hides it again when it ends; one with something to report keeps it until Close.
 */
final class MaterializeProgress extends VBox {

    private final ProgressBar bar = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
    private final MarkdownEditPane text = new MarkdownEditPane("", false,
            markdown -> new MarkdownRichText(Calculators.View.Default(), MarkdownRichText.DEFAULT_BASE).renderMarkdown(markdown));
    private final Button pause = new Button("Pause");
    private final Button stop = new Button("Stop");
    private final Button close = new Button("Close");
    private final Hyperlink more = new Hyperlink();
    private final HBox controls;
    /** Whether a long list of files is shown in full; a short summary otherwise. */
    private boolean expanded;
    private Progress progress;
    private AssemblyRun run;

    MaterializeProgress() {
        setId("stampedDocumentMaterializing");
        getStyleClass().add("stamped-materializing");
        setPadding(new Insets(6, 12, 6, 12));
        setSpacing(4);
        bar.setId("stampedDocumentMaterializingBar");
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setPrefHeight(8);
        HBox.setHgrow(bar, Priority.ALWAYS);
        pause.setId("stampedDocumentMaterializePause");
        pause.setTooltip(new Tooltip("Wait after the file being materialized, until you resume"));
        pause.setMinWidth(Region.USE_PREF_SIZE);
        pause.setOnAction(e -> togglePause());
        stop.setId("stampedDocumentMaterializeStop");
        stop.setTooltip(new Tooltip("Keep the files already materialized and leave the rest as they are"));
        stop.setMinWidth(Region.USE_PREF_SIZE);
        stop.setOnAction(e -> {
            if (run != null) {
                run.stop();
            }
        });
        close.setId("stampedDocumentMaterializeClose");
        close.setTooltip(new Tooltip("Hide this report"));
        close.setMinWidth(Region.USE_PREF_SIZE);
        close.setOnAction(e -> hideStrip());
        controls = new HBox(8, bar, pause, stop, close);
        controls.setAlignment(Pos.CENTER_LEFT);
        text.setId("stampedDocumentMaterializingText");
        text.useContentHeight();
        text.renderedView().getStyleClass().add("stamped-materializing-text");
        more.setId("stampedDocumentMaterializingMore");
        more.setOnAction(e -> {
            expanded = !expanded;
            show();
        });
        getChildren().addAll(controls, text, more);
        // A paused run waits for a person; once its card has left the window nobody can resume it.
        sceneProperty().addListener((obs, was, is) -> {
            if (is == null && run != null && run.state() == AssemblyRun.State.PAUSED) {
                run.stop();
            }
        });
        hideStrip();
    }

    /** @return whether a run is under way on this card */
    boolean running() {
        return progress != null && !progress.ended();
    }

    /** @return the run under way or last run, for automation and tests; {@code null} before the first */
    AssemblyRun run() {
        return run;
    }

    /** @return the strip's text as Markdown, for automation and tests */
    String markdown() {
        return progress == null ? "" : progress.markdown(true);
    }

    /**
     * Shows the strip for a new run and returns the run, whose reports reach the strip on the
     * JavaFX application thread. Must be called on that thread.
     *
     * @param root the working tree
     * @param host the file opened, relative to the working tree
     * @return the run to write the files under
     */
    AssemblyRun start(Path root, Path host) {
        progress = new Progress(root, host);
        expanded = false;
        run = new AssemblyRun(new AssemblyRun.Listener() {
            @Override
            public void planned(List<Path> files, List<GitDocumentMaterializer.Unresolved> unresolved) {
                Platform.runLater(() -> progress.planned(files, unresolved));
                Platform.runLater(MaterializeProgress.this::show);
            }

            @Override
            public void writing(int number, int total, Path file) {
                Platform.runLater(() -> {
                    progress.writing(number, file);
                    show();
                });
            }

            @Override
            public void written(int number, int total, GitDocumentMaterializer.Result result) {
                Platform.runLater(() -> {
                    progress.written(number);
                    show();
                });
            }

            @Override
            public void changed(AssemblyRun.State state) {
                Platform.runLater(() -> {
                    progress.state(state);
                    show();
                });
            }
        });
        setVisible(true);
        setManaged(true);
        show();
        return run;
    }

    /**
     * Shows how the run ended. A run that wrote every file and found every include hides the
     * strip; any other keeps it, with Close.
     *
     * @param result what was written, or {@code null} when the run failed
     * @param failure why the run failed, or {@code null}
     */
    void finished(GitDocumentMaterializer.AssemblyResult result, Throwable failure) {
        if (progress == null) {
            return;
        }
        progress.ended(result, failure);
        show();
        if (failure == null && result != null && !result.stopped() && result.unresolved().isEmpty()) {
            hideStrip();
        }
    }

    private void togglePause() {
        if (run == null) {
            return;
        }
        if (run.state() == AssemblyRun.State.PAUSED) {
            run.resume();
        } else {
            run.pause();
        }
    }

    private void show() {
        if (progress == null) {
            return;
        }
        text.setText(progress.markdown(expanded));
        int listed = progress.listed();
        more.setText(expanded ? "Hide the list" : listed == 1 ? "Show the file" : "Show the " + listed + " files");
        show(more, listed > Progress.SHOWN_IN_FULL);
        boolean ended = progress.ended();
        AssemblyRun.State state = run == null ? AssemblyRun.State.RUNNING : run.state();
        pause.setText(state == AssemblyRun.State.PAUSED ? "Resume" : "Pause");
        pause.setTooltip(new Tooltip(state == AssemblyRun.State.PAUSED ? "Go on with the next file"
                : "Wait after the file being materialized, until you resume"));
        pause.setDisable(ended || state == AssemblyRun.State.STOPPED);
        stop.setDisable(ended || state == AssemblyRun.State.STOPPED);
        show(pause, !ended);
        show(stop, !ended);
        show(close, ended);
        controls.setAlignment(ended ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);     // Close at the right once the bar is gone
        bar.setProgress(progress.total() == 0 ? ProgressBar.INDETERMINATE_PROGRESS : (double) progress.written() / progress.total());
        show(bar, !ended);
    }

    private static void show(javafx.scene.Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    private void hideStrip() {
        setVisible(false);
        setManaged(false);
    }

    /**
     * What the strip says, kept apart from the controls so it reads the same in a test with no
     * window. Every method is called on one thread.
     */
    static final class Progress {

        /** A list this long or shorter is shown in full; a longer one waits for "Show". */
        static final int SHOWN_IN_FULL = 5;

        private final Path root;
        private final Path host;
        private List<Path> files = List.of();
        private List<GitDocumentMaterializer.Unresolved> unresolved = List.of();
        private int written;
        private Path current;
        private boolean writingNow;
        private AssemblyRun.State state = AssemblyRun.State.RUNNING;
        private boolean ended;
        private GitDocumentMaterializer.AssemblyResult result;
        private Throwable failure;

        /**
         * @param root the working tree
         * @param host the file opened, relative to the working tree
         */
        Progress(Path root, Path host) {
            this.root = root.toAbsolutePath().normalize();
            this.host = host;
        }

        void planned(List<Path> files, List<GitDocumentMaterializer.Unresolved> unresolved) {
            this.files = files;
            this.unresolved = unresolved;
        }

        void writing(int number, Path file) {
            current = file;
            writingNow = true;
            written = number - 1;
        }

        void written(int number) {
            written = number;
            writingNow = false;
        }

        void state(AssemblyRun.State state) {
            this.state = state;
        }

        void ended(GitDocumentMaterializer.AssemblyResult result, Throwable failure) {
            this.ended = true;
            this.writingNow = false;
            this.result = result;
            this.failure = failure;
            if (result != null) {
                this.written = result.documents().size();
                this.unresolved = result.unresolved();
            }
        }

        boolean ended() {
            return ended;
        }

        int total() {
            return files.size();
        }

        int written() {
            return written;
        }

        /** @return the strip's text with every list in full, as Markdown */
        String markdown() {
            return markdown(true);
        }

        /** @return how many files and includes the lists name, together */
        int listed() {
            return notReached().size() + unresolved.size();
        }

        private List<Path> notReached() {
            return ended && result != null ? result.notWritten() : List.of();
        }

        /**
         * The strip's text, as Markdown.
         *
         * @param full whether to list every file; lists longer than {@link #SHOWN_IN_FULL} together
         *             are left out otherwise, and the counts stand for them
         * @return the text
         */
        String markdown(boolean full) {
            boolean lists = full || listed() <= SHOWN_IN_FULL;
            StringBuilder md = new StringBuilder(headline());
            List<Path> left = notReached();
            if (!left.isEmpty()) {
                md.append("\n\n").append(left.size() == 1 ? "The run did not reach one file." : "The run did not reach the other " + left.size() + " files.")
                        .append(" A file the knowledge base does not hold yet shows as its include line in the document.")
                        .append(" Open the file again to materialize the rest.");
                if (lists) {
                    md.append("\n\n");
                    for (Path file : left) {
                        md.append("- ").append(bold(shown(file))).append('\n');
                    }
                }
            }
            if (!unresolved.isEmpty()) {
                md.append("\n\n").append(unresolved.size() == 1 ? "One include could not be found and is left out."
                        : unresolved.size() + " includes could not be found and are left out.");
                if (lists) {
                    md.append("\n\n");
                    for (GitDocumentMaterializer.Unresolved u : unresolved) {
                        md.append("- ").append(bold(u.target())).append(", line ").append(u.line()).append(" of ")
                                .append(bold(String.valueOf(u.including()))).append(": ").append(escape(u.reason())).append('\n');
                    }
                }
            }
            return md.toString().stripTrailing();
        }

        private String headline() {
            int total = total();
            if (failure != null) {
                Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                return "Materializing stopped at " + (current == null ? bold(host.toString()) : bold(name(current)))
                        + " because of an error. " + escape(String.valueOf(cause.getMessage()));
            }
            if (ended && result != null) {
                if (result.stopped()) {
                    return "Stopped after " + written + " of " + total + " files.";
                }
                return total == 1 ? "Materialized " + bold(host.getFileName().toString()) + "."
                        : "Materialized all " + total + " files.";
            }
            if (total == 0) {
                return "Finding the files that " + bold(host.getFileName().toString()) + " includes …";
            }
            if (state == AssemblyRun.State.STOPPED) {
                return writingNow ? "Stopping when " + bold(name(current)) + " is materialized." : "Stopping …";
            }
            if (state == AssemblyRun.State.PAUSED) {
                if (writingNow) {
                    return "Pausing when " + bold(name(current)) + " is materialized.";
                }
                return written == 0 ? "Paused before the first of " + total + " files."
                        : "Paused after " + written + " of " + total + " files.";
            }
            int number = writingNow ? written + 1 : Math.min(written + 1, total);
            Path file = writingNow ? current : files.get(Math.min(written, total - 1));
            return "Materializing " + number + " of " + total + ": " + bold(name(file));
        }

        /** A file as the strip names it: relative to the working tree when it is under it. */
        private String shown(Path file) {
            Path absolute = file.toAbsolutePath().normalize();
            return absolute.startsWith(root) ? root.relativize(absolute).toString() : absolute.toString();
        }

        private static String name(Path file) {
            return file.getFileName().toString();
        }

        private static String bold(String name) {
            return "**" + escape(name) + "**";
        }

        /** Escapes the characters Markdown would read as markup, so a file name reads as written. */
        static String escape(String text) {
            StringBuilder out = new StringBuilder(text.length());
            for (char c : text.toCharArray()) {
                if ("\\`*_[]<>#|".indexOf(c) >= 0) {
                    out.append('\\');
                }
                out.append(c);
            }
            return out.toString();
        }
    }

    /** @return the files a stopped run did not reach, for automation; empty otherwise */
    List<Path> notWritten() {
        return progress == null || progress.result == null ? List.of() : new ArrayList<>(progress.result.notWritten());
    }
}

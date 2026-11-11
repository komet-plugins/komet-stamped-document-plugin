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

import design.knowledge.stamp.adoc.model.AnnotationKind;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;
import jfx.incubator.scene.control.richtext.RichTextArea;
import network.ike.komet.document.GitDocumentMaterializer.StoredBlock;
import network.ike.komet.document.GitDocumentMaterializer.StoredDocument;
import network.ike.komet.document.surface.BlockFactory;
import network.ike.komet.document.surface.DocumentSurface;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A date and a STAMP are drawn where they change. In the Reading view a block whose date or
 * STAMPs are those of the block above leaves that margin empty; in the Source view a line the
 * same STAMP introduced as the line above carries a bar where the identicon would repeat; and
 * the header keeps the date and STAMPs of what is at the top of the view.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ShowOnChangeIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");

    private static final String FIRST = """
            :topic-id: show-on-change
            = Show on Change

            First paragraph, as first written.

            Second paragraph, as first written.

            Third paragraph, as first written.

            Fourth paragraph, as first written.

            == A Section

            Fifth paragraph, as first written.
            """;

    @TempDir
    Path root;

    @BeforeAll
    void setup() {
        assertTrue(PB_STARTER_DATA.exists(), "Starter data must be present at " + PB_STARTER_DATA.getAbsolutePath());
        try {
            HeadlessFx.select();
            Platform.startup(() -> { });
        } catch (IllegalStateException alreadyStarted) {
            // the toolkit outlives a test class
        } catch (RuntimeException | Error noToolkit) {
            Assumptions.abort("no JavaFX toolkit here: " + noToolkit.getMessage());
        }
        PrimitiveData.selectControllerByName("Load Memory-Only 64-bit Store");
        PrimitiveData.start();
        new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute();
    }

    @AfterAll
    void teardown() {
        PrimitiveData.stop();
    }

    /** What one block's margins show: its text, its date and whether the date is drawn, and whether its STAMPs are. */
    private record Margins(String text, String date, boolean dateDrawn, boolean stampsDrawn) { }

    @Test
    void aDateAndAStampAreDrawnWhereTheyChange() throws Exception {
        // the document, then its third paragraph written again a month on, then its fourth later the same day
        Path dir = Files.createDirectories(root.resolve("change"));
        git(dir, "init", "-q", "-b", "main");
        git(dir, "config", "user.name", "author");
        git(dir, "config", "user.email", "author@example.test");
        Path file = dir.resolve("change.adoc");
        Files.writeString(file, FIRST);
        git(dir, "add", "change.adoc");
        gitOn(dir, "2026-03-01T12:00:00Z", "commit", "-q", "--no-gpg-sign", "-m", "The document");
        Files.writeString(file, FIRST.replace("Third paragraph, as first written.", "Third paragraph, written again."));
        gitOn(dir, "2026-04-02T12:00:00Z", "commit", "-q", "--no-gpg-sign", "-am", "The third paragraph again");
        Files.writeString(file, Files.readString(file).replace("Fourth paragraph, as first written.", "Fourth paragraph, written again."));
        // an hour later: two commits of one author in the same second are one STAMP
        gitOn(dir, "2026-04-02T13:00:00Z", "commit", "-q", "--no-gpg-sign", "-am", "The fourth paragraph again");
        StoredDocument document = GitDocumentMaterializer.load(GitDocumentMaterializer.materialize(dir, file).anchor(), true).orElseThrow();

        DocumentSurface surface = onFx(() -> new DocumentSurface(new BlockFactory(Calculators.View.Default(), 13)));
        DocumentSurfaceView view = onFx(() -> {
            Stage stage = new Stage();
            stage.setScene(new Scene(surface, 900, 700));
            stage.show();
            DocumentSurfaceView v = new DocumentSurfaceView(host(), surface);
            v.show(DocumentAssembly.single(document, "change.adoc"));
            return v;
        });
        pulses();

        List<Margins> margins = onFx(() -> {
            List<Margins> read = new ArrayList<>();
            for (int i = 0; i < surface.blockCount(); i++) {
                Node row = surface.block(i).stackNode();
                Label date = (Label) row.lookup(".stamped-document-date");
                Node stamps = row.lookup(".stamped-document-stamps");
                read.add(new Margins(surface.block(i).plainText().strip(), date.getText(), date.isVisible(), stamps != null && stamps.isVisible()));
            }
            return read;
        });
        Margins title = margins.getFirst();
        Margins second = block(margins, "Second paragraph");
        Margins third = block(margins, "Third paragraph");
        Margins fourth = block(margins, "Fourth paragraph");
        Margins section = block(margins, "== A Section");
        Margins fifth = block(margins, "Fifth paragraph");
        assertTrue(title.dateDrawn() && title.stampsDrawn(), "the first block shows its date and its STAMP: " + margins);
        assertTrue(!second.dateDrawn() && !second.stampsDrawn(), "a block the same commit wrote as the block above shows neither: " + margins);
        assertEquals(title.date(), second.date(), "though its date is the same one, kept for the pointer");
        assertTrue(third.dateDrawn() && third.stampsDrawn(), "the block a later commit wrote shows both: " + margins);
        assertTrue(!third.date().equals(title.date()), "its date is the later day: " + margins);
        assertTrue(!fourth.dateDrawn() && fourth.stampsDrawn(),
                "a block another commit of the same day wrote shows its STAMP and not the day again: " + margins);
        assertTrue(section.dateDrawn() && section.stampsDrawn(), "and where the first commit's blocks resume, both show again: " + margins);
        assertTrue(!fifth.dateDrawn() && !fifth.stampsDrawn(), margins.toString());

        // under the pointer a block shows the date and STAMP it shares with the block above, faintly, and lets them go after
        if ("Monocle".equals(System.getProperty("glass.platform"))) {                // a robot on a real screen would move the person's pointer
            int at = margins.indexOf(second);
            pointAt(surface, at);
            assertEquals(List.of(true, true, 0.5, 0.5), onFx(() -> drawn(surface, at)),
                    "the pointer on a block draws its date and its STAMP, fainter than those that mark a change");
            pointAt(surface, 0);
            assertEquals(List.of(false, false, 0.5, 0.5), onFx(() -> drawn(surface, at)), "and they go when the pointer leaves");
            assertEquals(List.of(true, true, 1.0, 1.0), onFx(() -> drawn(surface, 0)), "a date and a STAMP that mark a change are drawn in full");
        }

        // the header says what is at the top of the view: here the first block's day and its one STAMP
        assertEquals(title.date(), onFx(() -> runDate(surface)), "the header keeps the date at the top of the view");
        assertEquals(1, onFx(() -> runStamps(surface)), "and its STAMP");

        // the Source view: an identicon where the STAMP changes, a bar on each line it does not
        onFx(() -> {
            view.setView(DocumentSurfaceView.View.SOURCE);
            return null;
        });
        pulses();
        int[] gutter = onFx(() -> {
            RichTextArea ledger = surface.block(0).richText();
            Node column = ledger.lookup(".left-side");
            int identicons = 0;
            for (Node node : column.lookupAll("*")) {
                if (node instanceof ImageView) {
                    identicons++;
                }
            }
            return new int[] {ledger.getParagraphCount(), identicons, column.lookupAll(".stamped-document-run").size()};
        });
        assertEquals(5, gutter[1], "an identicon heads each run: the document's, the third paragraph's, the first commit's empty line "
                + "between the two, the fourth paragraph's, and the document's again");
        assertEquals(gutter[0] - 5, gutter[2], "and every other line carries its run's bar");
        assertEquals(1, onFx(() -> runStamps(surface)), "the header shows the STAMP of the line at the top");
    }

    /** Moves the pointer to the middle of a block's body and waits for the view to follow. */
    private static void pointAt(DocumentSurface surface, int block) throws Exception {
        onFx(() -> {
            Node body = surface.block(block).node();
            javafx.geometry.Bounds on = body.localToScreen(body.getBoundsInLocal());
            new javafx.scene.robot.Robot().mouseMove(on.getCenterX(), on.getCenterY());
            return null;
        });
        pulses();
    }

    /** Whether a block's date and its STAMPs are drawn, and how opaque each is. */
    private static List<Object> drawn(DocumentSurface surface, int block) {
        Node row = surface.block(block).stackNode();
        Node date = row.lookup(".stamped-document-date");
        Node stamps = row.lookup(".stamped-document-stamps");
        return List.of(date.isVisible(), stamps.isVisible(), date.getOpacity(), stamps.getOpacity());
    }

    private static Margins block(List<Margins> margins, String startingWith) {
        return margins.stream().filter(m -> m.text().startsWith(startingWith)).findFirst()
                .orElseThrow(() -> new AssertionError("no block starts with " + startingWith + ": " + margins));
    }

    private static String runDate(DocumentSurface surface) {
        return ((Label) surface.lookup("#stampedDocumentHeaderRun").lookup(".label")).getText();
    }

    private static int runStamps(DocumentSurface surface) {
        int identicons = 0;
        for (Node node : surface.lookup("#stampedDocumentHeaderRun").lookupAll("*")) {
            if (node instanceof ImageView) {
                identicons++;
            }
        }
        return identicons;
    }

    /** Lets the window's pulses lay the view out and the header catch up with it. */
    private static void pulses() throws Exception {
        for (int i = 0; i < 12; i++) {
            Thread.sleep(60);
            onFx(() -> null);
        }
    }

    private static DocumentSurfaceView.Host host() {
        return new DocumentSurfaceView.Host() {
            @Override
            public ViewCalculator view() {
                return Calculators.View.Default();
            }

            @Override
            public CommitSummaries summaries() {
                return CommitSummaries.NONE;
            }

            @Override
            public void status(String text) {
            }

            @Override
            public void annotate(AnnotationKind kind) {
            }

            @Override
            public void saveEdit(StoredBlock edited, String text) {
            }

            @Override
            public void showHistory() {
            }

            @Override
            public void selectionChanged() {
            }

            @Override
            public void showingMember(DocumentAssembly.Member member) {
            }
        };
    }

    private static void git(Path dir, String... args) throws Exception {
        gitOn(dir, null, args);
    }

    /** Runs git with the commit's time set, the author's and the committer's alike. */
    private static void gitOn(Path dir, String day, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true);
        if (day != null) {
            builder.environment().put("GIT_AUTHOR_DATE", day);
            builder.environment().put("GIT_COMMITTER_DATE", day);
        }
        Process p = builder.start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
        }
    }

    private static <T> T onFx(Callable<T> work) throws Exception {
        if (Platform.isFxApplicationThread()) {
            return work.call();
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(work.call());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result.get(30, TimeUnit.SECONDS);
    }
}

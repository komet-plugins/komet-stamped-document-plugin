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
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
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
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Source view of a document with many includes: each file's ledger stands in room as tall
 * as its lines, none drawn over the next, when the view is first shown, restacked, and scrolled
 * a long way; and one horizontal scrollbar moves every ledger's text together.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SourceLedgerIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");
    private static final int TOPICS = 45;

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

    @Test
    void everyLedgerStandsInRoomAsTallAsItsLines() throws Exception {
        Path dir = Files.createDirectories(root.resolve("ledger"));
        DocumentAssembly assembly = book(dir);

        DocumentSurface surface = onFx(() -> new DocumentSurface(new BlockFactory(Calculators.View.Default(), 13)));
        DocumentSurfaceView view = onFx(() -> {
            Stage stage = new Stage();
            stage.setScene(new Scene(surface, 700, 600));
            stage.show();
            // every layout the window makes is looked at, so room that is short for a pulse or two is seen as well
            stage.getScene().addPostLayoutPulseListener(() -> {
                String wrong = shortOfRoom(surface);
                if (!wrong.isEmpty() && shortRoom.get() == null) {
                    shortRoom.set(wrong);
                }
            });
            DocumentSurfaceView v = new DocumentSurfaceView(host(), surface);
            v.show(assembly);
            v.setView(DocumentSurfaceView.View.SOURCE);
            return v;
        });
        settle(surface);
        assertTrue(onFx(surface::blockCount) > 80, "long enough to be built as it is read: " + onFx(surface::blockCount));
        assertRoom(surface, "as first shown");

        onFx(() -> {
            view.setLayer(DocumentSurfaceView.Layer.TINTS, true);
            return null;
        });
        settle(surface);
        assertRoom(surface, "restacked by a layer");

        onFx(() -> {
            surface.revealBlock(surface.blockCount() / 2);
            return null;
        });
        settle(surface);
        assertRoom(surface, "after a long scroll");

        onFx(() -> {
            view.show(assembly);
            return null;
        });
        settle(surface);
        assertRoom(surface, "shown again");
        assertEquals(null, shortRoom.get(), "at no layout along the way is a built ledger given less room than its lines take");
    }

    @Test
    void oneScrollbarMovesEveryLedgerTogether() throws Exception {
        Path dir = Files.createDirectories(root.resolve("sideways"));
        DocumentAssembly assembly = book(dir);
        DocumentSurface surface = onFx(() -> new DocumentSurface(new BlockFactory(Calculators.View.Default(), 13)));
        onFx(() -> {
            Stage stage = new Stage();
            stage.setScene(new Scene(surface, 700, 600));
            stage.show();
            DocumentSurfaceView v = new DocumentSurfaceView(host(), surface);
            v.show(assembly);
            v.setView(DocumentSurfaceView.View.SOURCE);
            return v;
        });
        settle(surface);

        assertTrue(onFx(() -> surface.horizontalScroll().isShowing()), "a line wider than the window shows the surface's one bar");
        String bars = onFx(() -> {
            for (RichTextArea area : builtLedgers(surface)) {
                for (Node bar : area.lookupAll(".scroll-bar")) {
                    if (bar instanceof javafx.scene.control.ScrollBar own && own.getOrientation() == javafx.geometry.Orientation.HORIZONTAL
                            && own.isVisible() && (own.getHeight() > 0 || own.prefHeight(-1) > 0)) {
                        return area.getId() + " has a bar of its own " + own.getHeight() + " tall";
                    }
                }
            }
            return "";
        });
        assertEquals("", bars, "and no ledger has a bar of its own");

        List<Integer> before = onFx(() -> firstCharacters(surface));
        assertTrue(before.size() >= 3, "several ledgers are built: " + before);
        assertTrue(before.stream().allMatch(c -> c <= 1), "each ledger starts at its lines' first characters: " + before);
        // ten characters of the mono font, which the mark of each line's first character measures
        double ten = onFx(() -> {
            javafx.scene.text.Text text = new javafx.scene.text.Text("0123456789");
            text.setFont(javafx.scene.text.Font.font("Menlo", 12));
            return text.getLayoutBounds().getWidth();
        });
        onFx(() -> {
            surface.horizontalScroll().scrollBy(ten);
            return null;
        });
        settle(surface);
        assertEquals(Math.round(ten), onFx(() -> surface.horizontalScroll().offset()), 0.01, "the text stands ten characters to the left");
        List<Integer> after = onFx(() -> firstCharacters(surface));
        assertEquals(before.size(), after.size());
        assertTrue(after.stream().allMatch(c -> c >= 9 && c <= 11),
                "every ledger moved ten characters, those whose lines all fit the window with the rest: " + after);

        onFx(() -> {
            surface.revealBlock(surface.blockCount() / 2);
            return null;
        });
        settle(surface);
        List<Integer> further = onFx(() -> firstCharacters(surface));
        assertTrue(!further.isEmpty() && further.stream().allMatch(c -> c >= 9 && c <= 11),
                "ledgers built later stand where the rest do: " + further);
    }

    /** A book of {@link #TOPICS} included topics, committed and materialized, as the assembly the card would show. */
    private DocumentAssembly book(Path dir) throws Exception {
        git(dir, "init", "-q", "-b", "main");
        git(dir, "config", "user.name", "author");
        git(dir, "config", "user.email", "author@example.test");
        Files.createDirectories(dir.resolve("topics"));
        String name = dir.getFileName().toString();
        StringBuilder book = new StringBuilder(":topic-id: " + name + "-book\n= A Book of Many Topics\n\nWhat the book is for.\n\n");
        for (int t = 0; t < TOPICS; t++) {
            StringBuilder topic = new StringBuilder(":topic-id: " + name + "-topic-" + t + "\n= Topic " + t + "\n\n");
            // one topic is longer than a ledger area's 200 lines, and each has a line wider than the window
            int paragraphs = t == 3 ? 90 : 8;
            for (int p = 0; p < paragraphs; p++) {
                topic.append("Paragraph ").append(p).append(" of topic ").append(t).append(" says one thing,\n")
                        .append("and then says another on a second line.\n\n");
            }
            topic.append(":keywords: ").append("a keyword that runs on, ".repeat(12)).append("\n");
            Files.writeString(dir.resolve("topics/topic-" + t + ".adoc"), topic);
            book.append("== Part ").append(t).append("\n\nWhat part ").append(t).append(" holds.\n\n")
                    .append("include::topics/topic-").append(t).append(".adoc[leveloffset=+1]\n\n");
        }
        Path file = dir.resolve("book.adoc");
        Files.writeString(file, book);
        git(dir, "add", ".");
        git(dir, "commit", "-q", "--no-gpg-sign", "-m", "The book");
        GitDocumentMaterializer.AssemblyResult made = GitDocumentMaterializer.materializeAssembly(dir, file);
        assertEquals(TOPICS + 1, made.documents().size(), "the book and its topics: " + made.unresolved());
        StoredDocument document = GitDocumentMaterializer.load(GitDocumentMaterializer.documentAnchor(dir, "book.adoc"), true).orElseThrow();
        DocumentAssembly assembly = DocumentAssembly.of(document, "book.adoc",
                path -> GitDocumentMaterializer.load(GitDocumentMaterializer.documentAnchor(dir, path), true));
        assertEquals(TOPICS, assembly.children().size(), "every include is resolved");
        return assembly;
    }

    private static List<RichTextArea> builtLedgers(DocumentSurface surface) {
        List<RichTextArea> built = new ArrayList<>();
        for (int i = 0; i < surface.blockCount(); i++) {
            if (surface.block(i).isRealized() && surface.block(i).richText() instanceof RichTextArea area && area.getHeight() > 0) {
                built.add(area);
            }
        }
        return built;
    }

    /**
     * For each built ledger with a line of twenty characters or more, the character of that line
     * drawn at the left edge of the text, just right of the ledger's column of numbers and marks.
     * The column is the region the area's skin names {@code left-side}.
     */
    private static List<Integer> firstCharacters(DocumentSurface surface) {
        List<Integer> characters = new ArrayList<>();
        for (RichTextArea area : builtLedgers(surface)) {
            int lines = area.getParagraphCount();
            double pitch = (area.getHeight() - 4) / lines;
            for (int line = 0; line < lines; line++) {
                if (area.getPlainText(line).length() >= 20) {
                    // the column's right edge, the padding before the text, and a little of the first character
                    Node column = area.lookup(".left-side");
                    double edge = area.screenToLocal(column.localToScreen(column.getBoundsInLocal().getMaxX(), 0)).getX();
                    javafx.geometry.Point2D at = area.localToScreen(edge + 4 + 2, 2 + (line + 0.5) * pitch);
                    characters.add(area.getTextPosition(at.getX(), at.getY()).offset());
                    break;
                }
            }
        }
        return characters;
    }

    /** The first built ledger whose block is laid out with less room than its lines take, or nothing. */
    private static String shortOfRoom(DocumentSurface surface) {
        for (int i = 0; i < surface.blockCount(); i++) {
            if (!surface.block(i).isRealized() || !(surface.block(i).richText() instanceof RichTextArea area)
                    || !(surface.block(i).stackNode() instanceof javafx.scene.layout.Region room) || area.getScene() == null) {
                continue;
            }
            // the area's flow, the one region its skin adds, sets its preferred height to the height of the lines it has laid out
            double text = area.getChildrenUnmodifiable().isEmpty() || !(area.getChildrenUnmodifiable().getFirst() instanceof javafx.scene.layout.Region flow)
                    ? 0 : flow.getPrefHeight();
            // a ledger not yet laid out stands in the room its lines are expected to take, which is the font's line
            // times their number: within a line or so of what they do take, where a block left to ask for its own
            // room has a single line's until its first layout
            if (text > 0 && room.getHeight() + Math.max(14, text * 0.03) < text) {
                return "block " + i + " of " + area.getParagraphCount() + " lines has " + room.getHeight() + " of room for " + text + " of text";
            }
        }
        return "";
    }

    private final java.util.concurrent.atomic.AtomicReference<String> shortRoom = new java.util.concurrent.atomic.AtomicReference<>();

    /** Every built ledger area is as tall as its lines, in a block as tall as it, clear of the block below. */
    private static void assertRoom(DocumentSurface surface, String when) throws Exception {
        String wrong = onFx(() -> {
            int built = 0;
            for (int i = 0; i < surface.blockCount(); i++) {
                if (!surface.block(i).isRealized() || !(surface.block(i).richText() instanceof RichTextArea area)) {
                    continue;
                }
                built++;
                int lines = area.getParagraphCount();
                double text = area.getHeight();
                Bounds room = surface.block(i).stackNode().getBoundsInParent();
                if (text < lines * 10) {
                    return "block " + i + " holds " + lines + " lines in an area " + text + " tall";
                }
                if (room.getHeight() < text) {
                    return "block " + i + " draws " + text + " of text in " + room.getHeight() + " of room";
                }
                if (i + 1 < surface.blockCount()) {
                    double next = surface.block(i + 1).stackNode().getBoundsInParent().getMinY();
                    if (next < room.getMinY() + text) {
                        return "block " + (i + 1) + " starts at " + next + ", above the foot of block " + i + " at " + (room.getMinY() + text);
                    }
                }
            }
            return built == 0 ? "nothing was built" : "";
        });
        assertTrue(wrong.isEmpty(), when + ": " + wrong);
    }

    /** Waits for the window's own pulses to build what is near the viewport and for the blocks' places to stop moving. */
    private static void settle(DocumentSurface surface) throws Exception {
        int same = 0;
        String was = "";
        for (int i = 0; i < 400 && same < 10; i++) {
            Thread.sleep(50);
            String is = onFx(() -> {
                StringBuilder places = new StringBuilder();
                for (int b = 0; b < surface.blockCount(); b++) {
                    Node node = surface.block(b).stackNode();
                    places.append(surface.block(b).isRealized() ? 'b' : '-').append(Math.round(node.getBoundsInParent().getMinY())).append(' ');
                }
                return places.toString();
            });
            same = is.equals(was) ? same + 1 : 0;
            was = is;
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
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
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

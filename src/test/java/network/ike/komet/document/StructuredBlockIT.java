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

import design.knowledge.stamp.adoc.dialect.Block;
import design.knowledge.stamp.adoc.model.AnnotationKind;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.Coordinates;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import network.ike.komet.document.HeadlessFx;
import network.ike.komet.document.surface.BlockFactory;
import network.ike.komet.document.surface.DocumentSurface;
import network.ike.komet.document.GitDocumentMaterializer.StoredBlock;
import network.ike.komet.document.GitDocumentMaterializer.StoredDocument;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A structured block on the document surface (IKE-Network/ike-issues#1312): a listing block whose
 * style a registered kind names is read through the kind's control and edited in its editor, and
 * the editor's save is an ordinary pending edit of the block's text. The kind here is a stand-in
 * for the ANF statement and complex concept kinds, which register the same way.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StructuredBlockIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");
    private static final String STYLE = "test-structure";
    private static final String DOCUMENT = """
            :topic-id: structured
            = Structured

            A paragraph before it.

            [test-structure,kind=sample]
            ----
            alpha
            beta
            ----

            A paragraph after it.
            """;

    private static final String RUN_STYLE = "test-run";
    private static final String RUN_DOCUMENT = """
            :topic-id: structured-run
            = Structured run

            [test-run]
            ----
            first
            ----

            [test-run]
            ----
            second
            ----

            A paragraph ends the run.

            [test-run]
            ----
            third
            ----
            """;

    private static final String FORM_STYLE = "test-form";
    private static final String FORM_DOCUMENT = """
            :topic-id: structured-form
            = Structured form

            [test-form]
            ----
            one first
            ----

            [test-form]
            ----
            one second
            ----

            [test-form]
            ----
            one third
            ----
            """;

    @TempDir
    Path root;

    /**
     * A kind whose control is a form that shows its own edits: a field that saves its text. Its blocks read as a
     * run, and each reads one thing of the run before it, the first word of the block directly before.
     */
    private static final StructuredBlockKind FORM_KIND = new StructuredBlockKind() {
        @Override
        public String style() {
            return FORM_STYLE;
        }

        @Override
        public String name() {
            return "test form";
        }

        @Override
        public boolean readsAsRun() {
            return true;
        }

        @Override
        public boolean showsOwnEdits() {
            return true;
        }

        @Override
        public String readOfRun(List<Source> before) {
            return before.isEmpty() ? "" : before.getLast().content().split(" ")[0];
        }

        @Override
        public Node view(Source source, Context context) {
            return view(source, List.of(), context);
        }

        @Override
        public Node view(Source source, List<Source> before, Context context) {
            TextField field = new TextField(source.content());
            field.setUserData(readOfRun(before));
            field.setOnAction(e -> context.save().accept(source.withContent(field.getText())));
            return field;
        }

        @Override
        public Optional<Editor> editor(Source source, Context context) {
            return Optional.empty();
        }
    };

    /** A kind whose blocks read as a run: its control says what the block holds and what the run before it holds. */
    private static final StructuredBlockKind RUN_KIND = new StructuredBlockKind() {
        @Override
        public String style() {
            return RUN_STYLE;
        }

        @Override
        public String name() {
            return "test run";
        }

        @Override
        public boolean readsAsRun() {
            return true;
        }

        @Override
        public Node view(Source source, Context context) {
            return view(source, List.of(), context);
        }

        @Override
        public Node view(Source source, List<Source> before, Context context) {
            return new Label(source.content() + " after " + before.stream().map(Source::content).toList());
        }

        @Override
        public Optional<Editor> editor(Source source, Context context) {
            return Optional.empty();
        }
    };

    /** A kind that reads its content as one label and edits it in a text area. */
    private static final StructuredBlockKind KIND = new StructuredBlockKind() {
        @Override
        public String style() {
            return STYLE;
        }

        @Override
        public String name() {
            return "test structure";
        }

        @Override
        public Node view(Source source, Context context) {
            Label label = new Label(source.content().replace("\n", "|"));
            label.setId("testStructureView");
            return label;
        }

        @Override
        public Optional<Editor> editor(Source source, Context context) {
            TextArea area = new TextArea(source.content());
            return Optional.of(new Editor() {
                @Override
                public Node node() {
                    return area;
                }

                @Override
                public String content() {
                    if (area.getText().isBlank()) {
                        throw new IllegalStateException("A test structure holds at least one line");
                    }
                    return area.getText();
                }
            });
        }
    };

    @BeforeAll
    void setup() {
        assertTrue(PB_STARTER_DATA.exists(), "Starter data must be present at " + PB_STARTER_DATA.getAbsolutePath());
        try {
            HeadlessFx.select();
            Platform.startup(() -> { });
        } catch (IllegalStateException alreadyStarted) {
            // Started by another test class in this JVM.
        } catch (RuntimeException | Error noToolkit) {
            Assumptions.abort("no JavaFX toolkit here: " + noToolkit);
        }
        PrimitiveData.selectControllerByName("Load Memory-Only 64-bit Store");
        PrimitiveData.start();
        new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute();
        StructuredBlocks.register(KIND);
        StructuredBlocks.register(RUN_KIND);
        StructuredBlocks.register(FORM_KIND);
    }

    @AfterAll
    void teardown() {
        StructuredBlocks.unregister(STYLE);
        StructuredBlocks.unregister(RUN_STYLE);
        StructuredBlocks.unregister(FORM_STYLE);
        PrimitiveData.stop();
    }

    private static <T> T onFx(Callable<T> work) throws Exception {
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

    private static StoredBlock structuredBlock(StoredDocument document) {
        return document.blocks().values().stream()
                .filter(block -> block.kind() == Block.Kind.LISTING && block.parameters().startsWith("[" + STYLE))
                .findFirst().orElseThrow(() -> new AssertionError("no " + STYLE + " listing block"));
    }

    private static Node lookup(Node node, String id) {
        if (id.equals(node.getId())) {
            return node;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Node found = lookup(child, id);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Test
    void aStyledListingBlockReadsAndEditsThroughItsKind() throws Exception {
        Path dir = repository();
        Path file = dir.resolve("structured.adoc");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, file).anchor();
        StoredDocument document = GitDocumentMaterializer.load(anchor, true).orElseThrow();
        StoredBlock block = structuredBlock(document);

        // The block is flat lines, so a save of its text is an ordinary edit of those lines.
        assertTrue(document.lines().stream().map(GitDocumentMaterializer.StoredLine::nid).toList()
                .containsAll(block.members()), "a listing block holds lines only");
        assertEquals(KIND, StructuredBlocks.kindOf(block).orElseThrow());
        StructuredBlockKind.Source source = StructuredBlocks.source(document, block);
        assertEquals("alpha\nbeta", source.content());

        AtomicReference<String> saved = new AtomicReference<>();
        List<String> statuses = new ArrayList<>();
        DocumentSurfaceView.Host host = host(saved, statuses);

        DocumentSurfaceView surface = onFx(() -> {
            DocumentSurface documentSurface = new DocumentSurface(new BlockFactory(Calculators.View.Default(), 13));
            DocumentSurfaceView view = new DocumentSurfaceView(host, documentSurface);
            view.show(DocumentAssembly.single(document, "structured.adoc"));
            return view;
        });

        // Read through the kind's control, in place of the text.
        Node view = onFx(() -> surface.structuredViewOfBlock(block.nid()).orElseThrow());
        assertEquals("alpha|beta", ((Label) view).getText());
        assertTrue(view.isVisible());

        // Edit through the kind's editor; Save writes its content between the block's fences.
        assertTrue(onFx(() -> surface.editBlock(block.nid())));
        Parent holder = onFx(view::getParent);
        TextArea editor = (TextArea) onFx(() -> lookup(holder, "stampedDocumentStructuredEditor"));
        Button save = (Button) onFx(() -> lookup(holder, "stampedDocumentSaveEdit"));
        assertFalse(onFx(view::isVisible), "the editor takes the control's place");

        onFx(() -> {
            editor.setText("");
            save.fire();
            return null;
        });
        assertEquals(null, saved.get(), "an editor that holds nothing to save refuses, and nothing is saved");
        assertTrue(statuses.contains("A test structure holds at least one line"), statuses.toString());

        onFx(() -> {
            editor.setText("alpha\ngamma");
            save.fire();
            return null;
        });
        assertEquals("[test-structure,kind=sample]\n----\nalpha\ngamma\n----", saved.get());

        // The saved text is an ordinary pending edit of the block's lines.
        DocumentEditor.editBlock(anchor, block.nid(), saved.get(), Coordinates.Edit.Default());
        StoredDocument pending = GitDocumentMaterializer.load(anchor, true).orElseThrow();
        assertEquals("alpha\ngamma", StructuredBlocks.source(pending, structuredBlock(pending)).content());
        DocumentEditor.cancel(anchor);
    }

    @Test
    void aBlockOfAKindThatReadsAsARunIsShownKnowingTheRunBeforeIt() throws Exception {
        // a document, a topic and a day of its own: one store holds every document of this class
        Path dir = repository("structured-run", "structured-run.adoc", RUN_DOCUMENT, "2026-03-02T12:00:00");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, dir.resolve("structured-run.adoc")).anchor();
        StoredDocument document = GitDocumentMaterializer.load(anchor, true).orElseThrow();
        List<Long> order = document.lines().stream().map(GitDocumentMaterializer.StoredLine::nid).toList();
        List<StoredBlock> runs = document.blocks().values().stream()
                .filter(block -> block.kind() == Block.Kind.LISTING && block.parameters().startsWith("[" + RUN_STYLE))
                .sorted(java.util.Comparator.comparingInt((StoredBlock block) -> order.indexOf(block.members().getFirst()))).toList();
        assertEquals(3, runs.size());
        assertEquals(List.of(), StructuredBlocks.runBefore(document, runs.get(0)));
        assertEquals(List.of("first"), StructuredBlocks.runBefore(document, runs.get(1)).stream().map(StructuredBlockKind.Source::content).toList());
        assertEquals(List.of(), StructuredBlocks.runBefore(document, runs.get(2)), "a paragraph ends the run");

        DocumentSurfaceView surface = onFx(() -> {
            DocumentSurface documentSurface = new DocumentSurface(new BlockFactory(Calculators.View.Default(), 13));
            DocumentSurfaceView view = new DocumentSurfaceView(host(new AtomicReference<>(), new ArrayList<>()), documentSurface);
            view.show(DocumentAssembly.single(document, "structured-run.adoc"));
            return view;
        });
        List<String> shown = new ArrayList<>();
        for (StoredBlock block : runs) {
            shown.add(onFx(() -> ((Label) surface.structuredViewOfBlock(block.nid()).orElseThrow()).getText()));
        }
        assertEquals(List.of("first after []", "second after [first]", "third after []"), shown,
                "each block's control is made with the blocks of the run directly before it");
    }

    @Test
    void aFormThatSavedItsBlocksEditStaysAsItStandsWhenTheBlockIsReadAgain() throws Exception {
        // a document, a topic and a day of its own: one store contains every document of this class
        String file = "structured-form.adoc";
        Path dir = repository("structured-form", file, FORM_DOCUMENT, "2026-03-03T12:00:00");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, dir.resolve(file)).anchor();
        StoredDocument document = GitDocumentMaterializer.load(anchor, true).orElseThrow();
        List<Long> order = document.lines().stream().map(GitDocumentMaterializer.StoredLine::nid).toList();
        List<Long> forms = document.blocks().values().stream()
                .filter(block -> block.kind() == Block.Kind.LISTING && block.parameters().startsWith("[" + FORM_STYLE))
                .sorted(java.util.Comparator.comparingInt((StoredBlock block) -> order.indexOf(block.members().getFirst())))
                .map(StoredBlock::nid).toList();
        assertEquals(3, forms.size());

        AtomicReference<String> saved = new AtomicReference<>();
        AtomicReference<StoredBlock> savedTo = new AtomicReference<>();
        DocumentSurfaceView surface = onFx(() -> {
            DocumentSurface documentSurface = new DocumentSurface(new BlockFactory(Calculators.View.Default(), 13));
            new Scene(documentSurface, 700, 500);
            DocumentSurfaceView view = new DocumentSurfaceView(host(saved, savedTo, new ArrayList<>()), documentSurface);
            view.show(DocumentAssembly.single(document, file));
            // the scroll pane has its content in the scene once it has a skin, and only then are the blocks there
            documentSurface.applyCss();
            documentSurface.layout();
            return view;
        });
        TextField first = field(surface, forms.get(0));
        TextField second = field(surface, forms.get(1));
        TextField third = field(surface, forms.get(2));
        assertEquals(List.of("", "one", "one"), List.of(first.getUserData(), second.getUserData(), third.getUserData()));

        // a person types in the first form, and the form saves its text: the keyboard is in it, the caret after four letters
        onFx(() -> {
            first.requestFocus();
            first.setText("one first, changed");
            first.positionCaret(4);
            first.getOnAction().handle(new ActionEvent());
            return null;
        });
        assertEquals("[test-form]\n----\none first, changed\n----", saved.get());
        assertEquals(forms.get(0), savedTo.get().nid());
        assertSame(first, onFx(() -> first.getScene().getFocusOwner()), "the test's field has the keyboard");

        StoredDocument edited = readAgain(surface, anchor, forms.get(0), saved.get(), file);
        assertSame(first, field(surface, forms.get(0)), "the form that saved the edit is the form the block is shown with");
        assertEquals("one first, changed", onFx(first::getText));
        assertSame(first, onFx(() -> first.getScene().getFocusOwner()), "the keyboard is where it was");
        assertEquals(4, (int) onFx(first::getCaretPosition), "and the caret with it");
        assertSame(second, field(surface, forms.get(1)), "the block after it reads nothing that changed, and stays as it was drawn");
        assertSame(third, field(surface, forms.get(2)));

        // the form's next save is of the block as the document now has it; it changes the word the next block reads
        onFx(() -> {
            first.setText("two first");
            first.getOnAction().handle(new ActionEvent());
            return null;
        });
        assertSame(edited.blocks().get(forms.get(0)), savedTo.get(), "a later save is of the block as it was read again");
        readAgain(surface, anchor, forms.get(0), saved.get(), file);
        assertSame(first, field(surface, forms.get(0)), "the form stays through every save of its own");
        TextField secondAgain = field(surface, forms.get(1));
        assertNotSame(second, secondAgain, "the block after it reads the word that changed, and is made again");
        assertEquals("two", secondAgain.getUserData());
        assertSame(third, field(surface, forms.get(2)), "the block after that reads the block before it, which did not change");

        // an edit that is not the form's own is one the form does not show: the block is made again
        readAgain(surface, anchor, forms.get(0), "[test-form]\n----\ntwo first, by another hand\n----", file);
        TextField firstAgain = field(surface, forms.get(0));
        assertNotSame(first, firstAgain, "an edit from elsewhere draws the block's control again");
        assertEquals("two first, by another hand", onFx(firstAgain::getText));
        DocumentEditor.cancel(anchor);
    }

    private static TextField field(DocumentSurfaceView surface, long block) throws Exception {
        return (TextField) onFx(() -> surface.structuredViewOfBlock(block).orElseThrow());
    }

    /** Saves a block's text as a pending edit, reads the document again and shows it as a card does after a save. */
    private static StoredDocument readAgain(DocumentSurfaceView surface, PublicId anchor, long block, String text, String file) throws Exception {
        DocumentEditor.editBlock(anchor, block, text, Coordinates.Edit.Default());
        StoredDocument pending = GitDocumentMaterializer.load(anchor, true).orElseThrow();
        onFx(() -> {
            surface.showChanged(DocumentAssembly.single(pending, file));
            return null;
        });
        // the keyboard is given back once the block's new row stands in the scene
        onFx(() -> null);
        return pending;
    }

    /** A host that keeps what the surface asks it to save and what it reports. */
    private static DocumentSurfaceView.Host host(AtomicReference<String> saved, List<String> statuses) {
        return host(saved, new AtomicReference<>(), statuses);
    }

    /** A host that keeps what the surface asks it to save, the block it is asked to save it to, and what it reports. */
    private static DocumentSurfaceView.Host host(AtomicReference<String> saved, AtomicReference<StoredBlock> savedTo, List<String> statuses) {
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
                statuses.add(text);
            }

            @Override
            public void annotate(AnnotationKind kind) {
            }

            @Override
            public void saveEdit(StoredBlock edited, String text) {
                saved.set(text);
                savedTo.set(edited);
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

    private Path repository() throws Exception {
        return repository("structured", "structured.adoc", DOCUMENT, "2026-03-01T12:00:00");
    }

    private Path repository(String folder, String file, String text, String day) throws Exception {
        Path dir = Files.createDirectories(root.resolve(folder));
        git(dir, "init", "-q", "-b", "main");
        git(dir, "config", "user.name", "author");
        git(dir, "config", "user.email", "author@example.test");
        git(dir, "config", "commit.gpgsign", "false");
        Files.writeString(dir.resolve(file), text);
        git(dir, "add", file);
        git(dir, "commit", "-q", "--no-gpg-sign", "--date=" + day, "-m", "Add the document");
        return dir;
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
        }
    }
}

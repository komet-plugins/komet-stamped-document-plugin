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
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import jfx.incubator.scene.control.richtext.RichTextArea;
import jfx.incubator.scene.control.richtext.TextPos;
import jfx.incubator.scene.control.richtext.model.RichParagraph;
import jfx.incubator.scene.control.richtext.model.StyleAttributeMap;
import jfx.incubator.scene.control.richtext.model.StyledSegment;
import jfx.incubator.scene.control.richtext.model.StyledTextModel;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A lab brief in the Reading view, as a reader reads it (IKE-Network/ike-issues#1395): the
 * document's markup, its header's attribute entries and comments, a conditional that holds an
 * entry, a passthrough style sheet, an included topic's header and anchor, and a page break, is
 * its source and is not drawn; a labeled list reads as terms and their definitions; the
 * typographic apostrophe reads as one; a table the document unsets the caption word for is
 * captioned by its title alone. An include the store cannot find stays as its line. Escape
 * closes the block editor, and asks again before it discards changes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReadingMarkupIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");

    /** A brief written as the lab's are: its header broken by comments, its style sheet and byline in passthroughs. */
    private static final String BRIEF = """
            = A Brief for a Reader
            :doctype: article
            :toc!:
            // The byline is set by hand below,
            // as the lab's briefs set it.
            :case-author: Someone Writing
            :table-caption!:

            // Where the topics are.
            ifndef::adoc[:adoc: topics]

            :drafting-notes:

            ++++
            <style>
            body { font-size: 10pt; }
            </style>
            ++++

            [subs="attributes"]
            ++++
            <div class="byline"><p>{case-author}</p></div>
            ++++

            include::topics/NAME-in-brief.adoc[leveloffset=+1]

            include::topics/NAME-missing.adoc[leveloffset=+1]

            == Handing It On

            Give the session he asked for::
            From my address,
            and pass his answers to you.

            Timing:: The lists may be due within the year.

            How diabetic members`' eyes are examined.

            .Vehicles
            |===
            | Vehicle | Route
            | Sponsored project | The research office
            |===

            ifdef::drafting-notes[]
            <<<

            Drafting notes follow.
            endif::[]
            """;

    private static final String TOPIC = """
            // NAME-in-brief
            // Topic: In Brief
            :topic-id: NAME-in-brief
            :topic-type: concept
            :topic-scope-note: What the brief says, \\
              in a paragraph of its own.

            [[NAME-in-brief]]
            = In Brief

            The first paragraph of the topic.
            """;

    /** What no drawn text holds: each a piece of the markup above. */
    private static final List<String> MARKUP = List.of(":doctype", ":toc!", "// The byline", "as the lab's briefs", ":case-author", ":table-caption",
            "Where the topics", "ifndef::", ":adoc:", ":drafting-notes", "++++", "<style>", "font-size", "byline", "subs=", "-in-brief",
            "Topic: In Brief", ":topic-", "in a paragraph of its own", "[[", "<<<", "ifdef::", "endif::");

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
    void aBriefReadsAsItsWordsAndNotItsMarkup() throws Exception {
        DocumentAssembly assembly = brief("reads", 1);
        DocumentSurface body = onFx(() -> new DocumentSurface(new BlockFactory(Calculators.View.Default(), 13)));
        onFx(() -> {
            new DocumentSurfaceView(host(new ArrayList<>()), body).show(assembly);
            return null;
        });

        List<String> texts = new ArrayList<>();
        List<String> captions = new ArrayList<>();
        List<String> bold = new ArrayList<>();
        List<Double> indents = new ArrayList<>();
        List<String> terms = new ArrayList<>();
        onFx(() -> {
            for (int b = 0; b < body.blockCount(); b++) {
                for (Node caption : body.block(b).node().lookupAll(".stamped-document-caption")) {
                    captions.add(((Label) caption).getText());
                }
                StyledTextModel model = body.block(b).model();
                RichTextArea drawn = body.block(b).richText();
                if (model == null || drawn != null && !drawn.isVisible()) {
                    continue;                                    // a caption's carrier: its line is not drawn, its caption is
                }
                StringBuilder text = new StringBuilder();
                for (int p = 0; p < model.size(); p++) {
                    text.append(p == 0 ? "" : "\n").append(model.getPlainText(p));
                    RichParagraph paragraph = model.getParagraph(p);
                    StyleAttributeMap attributes = paragraph.getParagraphAttributes();
                    if (attributes != null && attributes.get(StyleAttributeMap.SPACE_LEFT) != null) {
                        indents.add(attributes.get(StyleAttributeMap.SPACE_LEFT));
                        terms.add(p > 0 ? model.getPlainText(p - 1) : "");
                    }
                    for (int k = 0; k < paragraph.getSegmentCount(); k++) {
                        StyledSegment segment = paragraph.getSegment(k);
                        StyleAttributeMap style = segment.getStyleAttributeMap(null);
                        if (segment.getType() == StyledSegment.Type.TEXT && style != null && style.isBold() && !segment.getText().isBlank()) {
                            bold.add(segment.getText());
                        }
                    }
                }
                texts.add(text.toString());
            }
            return null;
        });

        for (String markup : MARKUP) {
            assertTrue(texts.stream().noneMatch(t -> t.contains(markup)), "markup is the source, not drawn: " + markup + " in " + texts);
        }
        assertEquals("A Brief for a Reader", texts.getFirst(), "the title is the first thing drawn, nothing of the header after it: " + texts);
        assertTrue(texts.contains("In Brief"), "the included topic's title is drawn: " + texts);
        assertTrue(texts.contains("The first paragraph of the topic."), "and its words: " + texts);
        assertTrue(texts.contains("include::topics/reads-missing.adoc[leveloffset=+1]"), "an include the store cannot find stays as its line: " + texts);

        assertTrue(texts.contains("Give the session he asked for\nFrom my address, and pass his answers to you."),
                "a term on its own line, then its definition, with no bullet and no colons: " + texts);
        assertTrue(bold.contains("Give the session he asked for"), "the term is bold: " + bold);
        assertEquals(List.of("Give the session he asked for"), terms, "its definition stands in from it, and no other paragraph does");
        assertTrue(indents.getFirst() > 0);
        assertTrue(texts.contains("Timing The lists may be due within the year."), "a definition on its term's line runs in after it: " + texts);
        assertTrue(bold.contains("Timing"), bold.toString());
        assertTrue(texts.stream().noneMatch(t -> t.startsWith("•")), "a labeled list has no bullets: " + texts);

        assertTrue(texts.contains("How diabetic members’ eyes are examined."), "the typographic apostrophe is one: " + texts);
        assertTrue(captions.contains("Vehicles"), "the table is captioned by its title alone: " + captions);
        assertTrue(captions.stream().noneMatch(c -> c.startsWith("Table")), captions.toString());
        assertTrue(texts.contains("Drafting notes follow."), "what the conditional keeps is drawn: " + texts);
    }

    @Test
    void escapeClosesTheBlockEditorAndAsksAgainBeforeItDiscardsChanges() throws Exception {
        DocumentAssembly assembly = brief("escape", 2);
        List<String> saved = new ArrayList<>();
        DocumentSurface body = onFx(() -> new DocumentSurface(new BlockFactory(Calculators.View.Default(), 13)));
        DocumentSurfaceView view = onFx(() -> {
            javafx.stage.Stage stage = new javafx.stage.Stage();
            stage.setScene(new javafx.scene.Scene(body, 900, 1400));
            stage.show();
            DocumentSurfaceView made = new DocumentSurfaceView(host(saved), body);
            made.show(assembly);
            return made;
        });
        pulses();
        StoredBlock paragraph = assembly.host().document().blocks().values().stream()
                .filter(b -> b.kind() == Block.Kind.PARAGRAPH && b.members().stream()
                        .anyMatch(nid -> assembly.host().document().line(nid).map(l -> l.text().startsWith("How diabetic")).orElse(false)))
                .findFirst().orElseThrow();

        // unchanged, one Escape closes it
        assertTrue(onFx(() -> view.editBlock(paragraph.nid())), "the paragraph opens for editing");
        pulses();
        RichTextArea editor = onFx(() -> (RichTextArea) body.lookup("#stampedDocumentBlockEditor"));
        assertNotNull(editor);
        onFx(() -> press(editor, KeyCode.ESCAPE));
        assertNull(onFx(() -> body.lookup("#stampedDocumentBlockEditor")), "Escape closes an editor with nothing changed");
        assertNull(onFx(() -> body.lookup("#stampedDocumentEditButtons")), "and its buttons");
        assertFalse(onFx(editor::isEditable), "the block is read again");
        onFx(() -> press(editor, KeyCode.ESCAPE));                 // a reader's Escape on the block afterwards does nothing to it

        // changed, the first Escape warns, a key between takes the warning back, and a second Escape discards
        assertTrue(onFx(() -> view.editBlock(paragraph.nid())), "the paragraph opens again");
        pulses();
        RichTextArea again = onFx(() -> (RichTextArea) body.lookup("#stampedDocumentBlockEditor"));
        onFx(() -> {
            again.insertText(TextPos.ofLeading(0, 0), "Now: ", StyleAttributeMap.EMPTY);
            return null;
        });
        onFx(() -> press(again, KeyCode.ESCAPE));
        assertNotNull(onFx(() -> body.lookup("#stampedDocumentBlockEditor")), "a change is not discarded by one Escape");
        assertTrue(hint(body).contains(DocumentSurfaceView.ESCAPE_AGAIN), "the hint says what a second one does: " + hint(body));
        onFx(() -> press(again, KeyCode.LEFT));
        assertFalse(hint(body).contains(DocumentSurfaceView.ESCAPE_AGAIN), "another key takes the warning back");
        onFx(() -> press(again, KeyCode.ESCAPE));
        assertNotNull(onFx(() -> body.lookup("#stampedDocumentBlockEditor")), "so the next Escape warns again");
        onFx(() -> press(again, KeyCode.ESCAPE));
        assertNull(onFx(() -> body.lookup("#stampedDocumentBlockEditor")), "and the one after it discards the change");
        assertEquals(List.of(), saved, "nothing was saved");
        assertTrue(onFx(() -> again.getModel() instanceof LineRunModel), "the block reads as it was");
    }

    /** Lets the toolkit lay out and build what a show or an edit asked for. */
    private static void pulses() throws Exception {
        for (int i = 0; i < 12; i++) {
            Thread.sleep(60);
            onFx(() -> null);
        }
    }

    /** The texts of the hint labels beneath an open editor. */
    private static String hint(DocumentSurface body) throws Exception {
        return onFx(() -> {
            Node buttons = body.lookup("#stampedDocumentEditButtons");
            StringBuilder said = new StringBuilder();
            if (buttons != null) {
                for (Node label : buttons.lookupAll(".label")) {
                    said.append(((Label) label).getText()).append('\n');
                }
            }
            return said.toString();
        });
    }

    private static Object press(Node node, KeyCode code) {
        node.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
        return null;
    }

    /** The brief and its topic in a repository of their own, materialized as the card does, and assembled. */
    private DocumentAssembly brief(String name, int day) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name));
        git(dir, "init", "-q", "-b", "main");
        git(dir, "config", "user.name", "reviewer");
        git(dir, "config", "user.email", "reviewer@example.test");
        git(dir, "config", "commit.gpgsign", "false");
        String briefFile = name + "-brief.adoc";
        String topicFile = "topics/" + name + "-in-brief.adoc";
        Files.writeString(dir.resolve(briefFile), BRIEF.replace("NAME", name));
        Files.createDirectories(dir.resolve("topics"));
        Files.writeString(dir.resolve(topicFile), TOPIC.replace("NAME", name));
        git(dir, "add", briefFile, topicFile);
        git(dir, "commit", "-q", "--no-gpg-sign", "--date=2026-11-" + String.format("%02d", day) + "T12:00:00", "-m", "The " + name + " brief");
        GitDocumentMaterializer.AssemblyResult made = GitDocumentMaterializer.materializeAssembly(dir, dir.resolve(briefFile));
        StoredDocument brief = GitDocumentMaterializer.load(made.assembly().anchor(), true).orElseThrow();
        StoredDocument topic = GitDocumentMaterializer.load(GitDocumentMaterializer.documentAnchor(dir, topicFile), true).orElseThrow();
        return DocumentAssembly.of(brief, briefFile, path -> path.equals(topicFile) ? Optional.of(topic) : Optional.empty());
    }

    private static DocumentSurfaceView.Host host(List<String> saved) {
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
                saved.add(text);
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

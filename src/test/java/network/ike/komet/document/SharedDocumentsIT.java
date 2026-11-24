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
import dev.ikm.tinkar.coordinate.Coordinates;
import dev.ikm.tinkar.coordinate.edit.EditCoordinateRecord;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.boundary.DataKind;
import dev.ikm.tinkar.entity.boundary.EntityBoundaries;
import dev.ikm.tinkar.entity.boundary.ModuleKinds;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.entity.scope.SharedFolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sharing a document, reading and commenting on one whose file is elsewhere, and making it local or deleting
 * it (IKE-Network/ike-issues#1313; step 4 of {@code design-2026-11-22-knowledge-shared-local}, its
 * questions settled by KEC on 2026-11-23).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SharedDocumentsIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");

    @TempDir
    Path root;

    private EditCoordinateRecord edit;

    @BeforeAll
    void setupDatabase() {
        assertTrue(PB_STARTER_DATA.exists(), "Starter data must be present at " + PB_STARTER_DATA.getAbsolutePath());
        PrimitiveData.selectControllerByName("Load Memory-Only 64-bit Store");
        PrimitiveData.start();
        assertTrue(new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute().getTotalCount() > 0);
        edit = Coordinates.Edit.Default();
    }

    @AfterAll
    void teardownDatabase() {
        ModuleKinds.clear();
        EntityBoundaries.clear();
        PrimitiveData.stop();
    }

    @Test
    void aDocumentIsSharedWithTheDocumentsItIncludesAndTheirLinesWithThem() throws Exception {
        Path dir = repository("assembly");
        Files.writeString(dir.resolve("part.adoc"), "= Part\n\nA part.\n");
        Files.writeString(dir.resolve("host.adoc"), "= Host\n\nBefore.\n\ninclude::part.adoc[leveloffset=+1]\n");
        Files.writeString(dir.resolve("other.adoc"), "= Other\n\nNot included.\n");
        commit(dir, "Write them");
        GitDocumentMaterializer.materializeAssembly(dir, dir.resolve("host.adoc"));
        PublicId host = GitDocumentMaterializer.documentAnchor(dir, "host.adoc");
        PublicId part = GitDocumentMaterializer.documentAnchor(dir, "part.adoc");
        PublicId other = GitDocumentMaterializer.materialize(dir, dir.resolve("other.adoc")).anchor();
        assertThrows(SharedDocuments.Refused.class, () -> SharedDocuments.share(List.of(host), edit, root.resolve("shared-assembly")),
                "Komet does not know yet where its file is");
        DocumentFiles.remember(host, dir, "host.adoc", Files.readAllLines(dir.resolve("host.adoc")));
        DocumentFiles.remember(part, dir, "part.adoc", Files.readAllLines(dir.resolve("part.adoc")));

        SharedDocuments.Done done = SharedDocuments.share(List.of(host), edit, root.resolve("shared-assembly"));
        assertEquals(Set.of(host, part), Set.copyOf(done.anchors()), done.report());
        assertTrue(SharedDocuments.shared(host) && SharedDocuments.shared(part));
        assertFalse(SharedDocuments.shared(other), "only what it includes");
        for (GitDocumentMaterializer.StoredLine line : GitDocumentMaterializer.load(part).orElseThrow().lines()) {
            assertEquals(DataKind.SHARED, EntityBoundaries.kindOf(line.nid()), line.text());
        }
        assertFalse(SharedDocuments.readOnly(host), "its file is here");
        SharedDocuments.Refused held = assertThrows(SharedDocuments.Refused.class,
                () -> SharedDocuments.makeLocal(List.of(part), edit, "Ada", root.resolve("shared-assembly")));
        assertTrue(held.getMessage().contains("includes assembly/part.adoc") || held.getMessage().contains("includes part.adoc"), held.getMessage());
        assertTrue(SharedDocuments.shared(part), "a shared document includes it");
        SharedDocuments.delete(List.of(host, part), "Ada", root.resolve("shared-assembly"));
        assertEquals(2, SharedFolder.removals(root.resolve("shared-assembly")).size(), "both together, a marker each");
        assertFalse(SharedDocuments.shared(host) || SharedDocuments.shared(part));
    }

    @Test
    void theSharedCopyFollowsItsFileAndIsNotDroppedOrRebuilt() throws Exception {
        Path dir = repository("follows");
        Path file = dir.resolve("note.adoc");
        Files.writeString(file, "= Note\n\nFirst.\n");
        commit(dir, "Write it");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, file).anchor();
        DocumentFiles.remember(anchor, dir, "note.adoc", Files.readAllLines(file));
        Path folder = root.resolve("shared-follows");
        SharedDocuments.share(List.of(anchor), edit, folder);

        Files.writeString(file, "= Note\n\nFirst.\n\nSecond.\n");
        commit(dir, "Add a paragraph, outside Komet");
        assertEquals(1, SharedDocuments.bringUpToDate(), "before a sync sends");
        assertEquals(Files.readAllLines(file), lines(anchor));
        assertEquals(DataKind.SHARED, EntityBoundaries.kindOf(line(anchor, "Second.")), "what the commit added is shared");
        assertEquals(0, SharedDocuments.bringUpToDate(), "nothing new");

        DocumentScopes.dropCopies();
        assertTrue(SharedDocuments.shared(anchor), "a shared document is no copy to drop");
        IllegalStateException rebuilt = assertThrows(IllegalStateException.class, () -> DocumentScopes.dropForRebuild(anchor));
        assertTrue(rebuilt.getMessage().contains("what others said of it"), rebuilt.getMessage());
        DocumentScopes.RemovalRefused removed = assertThrows(DocumentScopes.RemovalRefused.class, () -> DocumentScopes.remove(List.of(anchor)));
        assertTrue(removed.getMessage().contains("Delete"), removed.getMessage());

        DocumentEditor.editBlock(anchor, paragraph(anchor, "First."), "First, edited.\n", edit);
        SharedDocuments.Refused pending = assertThrows(SharedDocuments.Refused.class,
                () -> SharedDocuments.makeLocal(List.of(anchor), edit, "Ada", folder));
        assertTrue(pending.getMessage().contains("not committed"), pending.getMessage());
        DocumentEditor.cancel(anchor);

        SharedDocuments.Done local = SharedDocuments.makeLocal(List.of(anchor), edit, "Ada Lovelace", folder);
        assertEquals(DataKind.FILE, SharedDocuments.kind(anchor), local.report());
        SharedFolder.Removal marker = SharedFolder.removals(folder).getFirst();
        assertEquals(SharedFolder.Act.MADE_LOCAL, marker.act());
        assertTrue(marker.words().startsWith("follows/note.adoc was made local by Ada Lovelace on "), marker.words());
        SharedDocuments.share(List.of(anchor), edit, folder);
        assertEquals(List.of(), SharedFolder.removals(folder), "sharing it again takes the marker away");
        DocumentScopes.delete(anchor);
    }

    @Test
    void withoutItsFileItIsReadAndCommentedOnAndNotEdited() throws Exception {
        Path dir = repository("elsewhere");
        Path file = dir.resolve("note.adoc");
        Files.writeString(file, "= Note\n\nA line.\n");
        commit(dir, "Write it");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, file).anchor();
        DocumentFiles.remember(anchor, dir, "note.adoc", Files.readAllLines(file));
        Path folder = root.resolve("shared-elsewhere");
        SharedDocuments.share(List.of(anchor), edit, folder);
        assertThrows(SharedDocuments.Refused.class, () -> SharedDocuments.comment(anchor, List.of(line(anchor, "A line.")), "here", edit),
                "where the file is here a comment is a commit");

        Files.move(dir, root.resolve("gone"));                                     // as on a computer that never had the file
        assertTrue(SharedDocuments.readOnly(anchor));
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> DocumentEditor.editBlock(anchor, paragraph(anchor, "A line."), "Another line.\n", edit));
        assertTrue(refused.getMessage().contains("you can comment on it"), refused.getMessage());
        assertTrue(DocumentEditor.pending(anchor).isEmpty(), "nothing was written");

        PublicId comment = SharedDocuments.comment(anchor, List.of(line(anchor, "A line.")), "What does it mean?", edit);
        assertEquals(DataKind.SHARED, EntityBoundaries.kindOf(EntityHandle.get(comment).expectEntity().nid()), "it goes where the document goes");
        GitDocumentMaterializer.StoredAnnotation said = GitDocumentMaterializer.load(anchor).orElseThrow().lines().stream()
                .filter(l -> l.text().equals("A line.")).findFirst().orElseThrow().annotations().getFirst();
        assertEquals(AnnotationKind.COMMENT, said.kind());
        assertEquals("What does it mean?", said.text());
        assertEquals(edit.getAuthorNidForChanges(), said.stamp().authorNid(), "the person's own");
        assertEquals(1, SharedDocuments.commentsInNoFile(List.of(anchor)), "counted for who makes it local");

        SharedDocuments.makeLocal(List.of(anchor), edit, "Ada", folder);
        assertEquals(DataKind.LOCAL, SharedDocuments.kind(anchor), "kept here, with no file");
        assertTrue(SharedDocuments.keptHereOnly(anchor) && SharedDocuments.readOnly(anchor));
        SharedDocuments.Done deleted = SharedDocuments.delete(List.of(anchor), "Ada", folder);
        assertTrue(deleted.report().endsWith("is deleted."), deleted.report());
        assertEquals(DataKind.KNOWLEDGE, SharedDocuments.kind(anchor), "it is gone");
        assertEquals(1, SharedFolder.removals(folder).size(), "a document kept only here leaves no marker of its own");
    }

    @Test
    void deletingASharedDocumentLeavesAMarkerAndItsFile() throws Exception {
        Path dir = repository("deleted");
        Path file = dir.resolve("note.adoc");
        Files.writeString(file, "= Note\n\nA line.\n");
        commit(dir, "Write it");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, file).anchor();
        DocumentFiles.remember(anchor, dir, "note.adoc", Files.readAllLines(file));
        Path folder = root.resolve("shared-deleted");
        assertThrows(SharedDocuments.Refused.class, () -> SharedDocuments.delete(List.of(anchor), "Ada", folder), "not shared yet");
        SharedDocuments.share(List.of(anchor), edit, folder);
        SharedDocuments.delete(List.of(anchor), "Ada", folder);
        assertEquals(DataKind.KNOWLEDGE, SharedDocuments.kind(anchor), "it is gone");
        assertEquals(SharedFolder.Act.DELETED, SharedFolder.removals(folder).getFirst().act());
        assertTrue(Files.exists(file), "its file stays");
        PublicId again = GitDocumentMaterializer.materialize(dir, file).anchor();
        assertEquals(DataKind.FILE, SharedDocuments.kind(again), "opening the file reads it in as a local document");
    }

    @Test
    void anotherFileOfTheSameNameAndPathIsNotReadIntoASharedDocument() throws Exception {
        Path mine = repository("same/notes");
        Files.writeString(mine.resolve("x.adoc"), "= Mine\n\nMine.\n");
        commit(mine, "Mine");
        PublicId anchor = GitDocumentMaterializer.materialize(mine, mine.resolve("x.adoc")).anchor();
        DocumentFiles.remember(anchor, mine, "x.adoc", Files.readAllLines(mine.resolve("x.adoc")));
        SharedDocuments.share(List.of(anchor), edit, root.resolve("shared-same"));
        Path theirs = repository("elsewhere-again/notes");
        Files.writeString(theirs.resolve("x.adoc"), "= Theirs\n\nTheirs.\n");
        commit(theirs, "Theirs");
        IOException refused = assertThrows(IOException.class, () -> GitDocumentMaterializer.materialize(theirs, theirs.resolve("x.adoc")));
        assertTrue(refused.getMessage().contains("no commit in common"), refused.getMessage());
        assertEquals(List.of("= Mine", "", "Mine."), lines(anchor), "the shared document is as it was");
    }

    /** The gestures are Komet actions, found like the others, and the Storage window offers what a kind allows. */
    @Test
    void theGesturesAreKometActionsAndStorageOffersWhatAKindAllows() throws Exception {
        Path dir = repository("actions");
        Path file = dir.resolve("note.adoc");
        Files.writeString(file, "= Note\n\nA line.\n");
        commit(dir, "Write it");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, file).anchor();
        DocumentFiles.remember(anchor, dir, "note.adoc", Files.readAllLines(file));
        for (dev.ikm.komet.terms.KometConcept action : List.of(dev.ikm.komet.terms.KometConcept.SHARE_DOCUMENT,
                dev.ikm.komet.terms.KometConcept.MAKE_DOCUMENT_LOCAL, dev.ikm.komet.terms.KometConcept.DELETE_DOCUMENT)) {
            assertTrue(dev.ikm.komet.actions.KometActions.forConcept(action).isPresent(), action.description());
        }
        assertEquals(List.of("storageShare", "storageRemove"), offers(anchor));
        dev.ikm.komet.actions.KometActionResult memoryOnly = new ShareDocument().perform(ShareDocument.input(List.of(anchor)),
                dev.ikm.tinkar.coordinate.Calculators.View.Default(), edit);
        assertEquals(dev.ikm.komet.terms.KometConcept.ACTION_REFUSED, memoryOnly.outcome());
        assertTrue(memoryOnly.message().contains("in memory only"), memoryOnly.message());
        assertEquals(DataKind.FILE, SharedDocuments.kind(anchor), "nothing was changed");
        SharedDocuments.share(List.of(anchor), edit, root.resolve("shared-actions"));
        assertEquals(List.of("storageMakeLocal", "storageDelete"), offers(anchor));
        IllegalArgumentException again = assertThrows(IllegalArgumentException.class,
                () -> DocumentStorage.offer(dev.ikm.komet.terms.KometConcept.SHARE_DOCUMENT, List.of(anchor)));
        assertTrue(again.getMessage().contains("note.adoc"), again.getMessage());
        String label = DocumentStorage.offer(dev.ikm.komet.terms.KometConcept.DELETE_DOCUMENT, List.of(anchor)).label();
        assertTrue(label.startsWith("Delete ") && label.endsWith("note.adoc…"), label);
        dev.ikm.komet.actions.KometActionResult wrong = new ShareDocument().perform(DeleteDocument.input(List.of(anchor)),
                dev.ikm.tinkar.coordinate.Calculators.View.Default(), edit);
        assertEquals(dev.ikm.komet.terms.KometConcept.ACTION_REFUSED, wrong.outcome(), "an input of another action");
        SharedDocuments.delete(List.of(anchor), "Ada", root.resolve("shared-actions"));
        assertEquals(List.of(), offers(anchor), "it is gone");
    }

    private static List<String> offers(PublicId anchor) {
        return new DocumentStorage().groups().stream()
                .filter(group -> PrimitiveData.get().hasPublicId(anchor)
                        && PublicId.equals(group.root(), PrimitiveData.publicId(DocumentScopes.rootNid(anchor))))
                .flatMap(group -> group.offers().stream()).map(dev.ikm.komet.layout.storage.KlStorageOffer::id).toList();
    }

    @Test
    void whatCannotBeSharedSaysWhy() throws Exception {
        Path plain = Files.createDirectories(root.resolve("plain"));
        Files.writeString(plain.resolve("n.adoc"), "= N\n\nText.\n");
        PublicId version = VertexMaterializer.materializeVersion(plain, Path.of("n.adoc"), dev.ikm.tinkar.terms.KernelConcept.USER).anchor();
        SharedDocuments.Refused noGit = assertThrows(SharedDocuments.Refused.class,
                () -> SharedDocuments.share(List.of(version), edit, root.resolve("shared-plain")));
        assertTrue(noGit.getMessage().contains("Start Git History"), noGit.getMessage());
        SharedDocuments.Refused memory = assertThrows(SharedDocuments.Refused.class, () -> SharedDocuments.share(List.of(version), edit));
        assertTrue(memory.getMessage().contains("in memory only"), memory.getMessage());
    }

    private static List<String> lines(PublicId anchor) {
        return GitDocumentExporter.export(anchor, false).orElseThrow().lines();
    }

    private static long line(PublicId anchor, String text) {
        return GitDocumentMaterializer.load(anchor).orElseThrow().lines().stream().filter(l -> l.text().equals(text)).findFirst().orElseThrow().nid();
    }

    private static long paragraph(PublicId anchor, String firstLine) {
        GitDocumentMaterializer.StoredDocument document = GitDocumentMaterializer.load(anchor, true).orElseThrow();
        long line = document.lines().stream().filter(l -> l.text().equals(firstLine)).findFirst().orElseThrow().nid();
        return document.blocks().values().stream()
                .filter(b -> b.kind() == Block.Kind.PARAGRAPH && !b.members().isEmpty() && b.members().getFirst() == line)
                .findFirst().orElseThrow().nid();
    }

    private Path repository(String name) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name));
        git(dir, "init", "-q", "-b", "main");
        git(dir, "config", "user.name", "Ada Lovelace");
        git(dir, "config", "user.email", "ada@example.test");
        git(dir, "config", "commit.gpgsign", "false");
        return dir;
    }

    private static void commit(Path dir, String message) throws Exception {
        git(dir, "add", "--all", ".");
        git(dir, "commit", "-q", "-m", message);
        Thread.sleep(1100);                                                    // git keeps seconds
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes()).strip();
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
        }
    }
}

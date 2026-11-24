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
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Coordinates;
import dev.ikm.tinkar.coordinate.edit.EditCoordinateRecord;
import dev.ikm.tinkar.entity.boundary.DataKind;
import dev.ikm.tinkar.entity.boundary.EntityBoundaries;
import dev.ikm.tinkar.entity.boundary.ModuleKinds;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Documents saved in files (IKE-Network/ike-issues#1313; step 2 of
 * {@code design-2026-11-22-knowledge-shared-local}, settled by KEC on 2026-11-22): a change saved in
 * the file and not committed is read as an uncommitted edit by the person Komet opens as; Save writes
 * an edit into the file without committing it, and never over a change made outside Komet; Discard
 * puts the file back; and as a knowledge base opens, the copies of documents saved in files are
 * dropped, except those holding edits their files do not.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentFilesIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");
    private static final String NOTE = """
            :topic-id: dev-files-%s

            = %s

            The first paragraph.
            It has two lines.

            The second paragraph.

            The third paragraph.
            """;

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
    void aChangeSavedAndNotCommittedIsReadAsAnUncommittedEditByThePersonKometOpensAs() throws Exception {
        Path dir = repository("saved", "note.adoc");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, dir.resolve("note.adoc")).anchor();
        List<String> committed = lines(anchor, false);
        List<String> file = new ArrayList<>(committed);
        file.set(file.indexOf("It has two lines."), "It has two lines, and one changed.");
        int third = file.indexOf("The third paragraph.");
        file.subList(third - 1, third + 1).clear();                                // the third paragraph and its blank line are gone
        file.addAll(file.indexOf("It has two lines, and one changed.") + 1, List.of("", "A new paragraph."));
        Files.write(dir.resolve("note.adoc"), file);

        WorkingTreeEdit.Read read = WorkingTreeEdit.readFile(anchor, dir, "note.adoc", edit);

        assertEquals(WorkingTreeEdit.Outcome.READ, read.outcome(), read.reason());
        assertTrue(read.report("note.adoc").contains("1 block edited, 1 added, 1 deleted"), read.report("note.adoc"));
        assertEquals(1, read.edited(), "the first paragraph is edited");
        assertEquals(1, read.added(), "a paragraph is added");
        assertEquals(1, read.deleted(), "the third paragraph is deleted");
        assertEquals(file, lines(anchor, true), "the pending edit is the file, line for line");
        assertEquals(committed, lines(anchor, false), "the committed document is as git has it");
        assertEquals(edit.getAuthorNidForChanges(), DocumentEditor.pendingAuthor(anchor).orElseThrow(),
                "the person Komet opens as wrote the edit");
        assertEquals(WorkingTreeEdit.Outcome.PENDING, WorkingTreeEdit.readFile(anchor, dir, "note.adoc", edit).outcome(),
                "a pending edit is not read over again");
        assertFalse(DocumentFiles.unsaved(anchor), "the file holds the edit");
        DocumentEditor.cancel(anchor);
    }

    @Test
    void aParagraphReplacedInPlaceIsReadAsAnEditOfIt() throws Exception {
        Path dir = repository("replaced", "note.adoc");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, dir.resolve("note.adoc")).anchor();
        List<String> file = new ArrayList<>(lines(anchor, false));
        file.set(file.indexOf("The third paragraph."), "Another paragraph altogether.");
        Files.write(dir.resolve("note.adoc"), file);

        WorkingTreeEdit.Read read = WorkingTreeEdit.readFile(anchor, dir, "note.adoc", edit);

        assertEquals(List.of(1, 0, 0), List.of(read.edited(), read.added(), read.deleted()), read.reason());
        assertEquals(file, lines(anchor, true));
        DocumentEditor.cancel(anchor);
    }

    @Test
    void aChangeToTheTitleIsNotReadAndTheDocumentShowsItsLastCommit() throws Exception {
        Path dir = repository("title", "note.adoc");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, dir.resolve("note.adoc")).anchor();
        List<String> file = new ArrayList<>(lines(anchor, false));
        file.set(file.indexOf("= note"), "= A renamed note");
        Files.write(dir.resolve("note.adoc"), file);

        WorkingTreeEdit.Read read = WorkingTreeEdit.readFile(anchor, dir, "note.adoc", edit);

        assertEquals(WorkingTreeEdit.Outcome.UNREAD, read.outcome());
        assertTrue(DocumentEditor.pending(anchor).isEmpty(), "nothing is left pending");
        assertTrue(read.report("note.adoc").contains("commit the changes in git to see them here"), read.report("note.adoc"));
    }

    @Test
    void saveWritesTheFileWithoutCommittingAndNeverOverAChangeMadeOutsideKomet() throws Exception {
        Path dir = repository("save", "note.adoc");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, dir.resolve("note.adoc")).anchor();
        assertEquals(WorkingTreeEdit.Outcome.SAME, WorkingTreeEdit.readFile(anchor, dir, "note.adoc", edit).outcome());
        String head = gitOut(dir, "rev-parse", "HEAD");
        DocumentEditor.editBlock(anchor, paragraph(anchor, "The second paragraph."), "The second paragraph, edited.", edit);
        assertTrue(DocumentFiles.unsaved(anchor));

        assertEquals(DocumentFiles.Outcome.SAVED, DocumentFiles.save(anchor, dir).outcome());
        assertEquals(lines(anchor, true), Files.readAllLines(dir.resolve("note.adoc")), "the file holds the edit");
        assertEquals(head, gitOut(dir, "rev-parse", "HEAD"), "nothing is committed");
        assertFalse(DocumentFiles.unsaved(anchor));
        assertEquals(DocumentFiles.Outcome.ALREADY, DocumentFiles.save(anchor, dir).outcome());

        DocumentEditor.editBlock(anchor, paragraph(anchor, "The third paragraph."), "The third paragraph, edited.", edit);
        assertEquals(DocumentFiles.Outcome.SAVED, DocumentFiles.save(anchor, dir).outcome(),
                "a file Komet saved an earlier state of the edit into is saved over");

        List<String> outside = new ArrayList<>(Files.readAllLines(dir.resolve("note.adoc")));
        outside.add("");
        outside.add("A paragraph written in another editor.");
        Files.write(dir.resolve("note.adoc"), outside);
        DocumentEditor.editBlock(anchor, paragraph(anchor, "The first paragraph."), "The first paragraph, edited.\nIt has two lines.", edit);
        assertEquals(DocumentFiles.Outcome.CHANGED_OUTSIDE, DocumentFiles.save(anchor, dir).outcome());
        assertEquals(outside, Files.readAllLines(dir.resolve("note.adoc")), "the change made outside Komet stays");
        assertEquals(DocumentFiles.Outcome.CHANGED_OUTSIDE, DocumentFiles.revert(anchor, dir).outcome(),
                "nor does putting the file back overwrite it");
        DocumentEditor.cancel(anchor);
    }

    @Test
    void discardingPutsTheFileBackAsLastCommitted() throws Exception {
        Path dir = repository("discard", "note.adoc");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, dir.resolve("note.adoc")).anchor();
        List<String> committed = lines(anchor, false);
        WorkingTreeEdit.readFile(anchor, dir, "note.adoc", edit);
        DocumentEditor.editBlock(anchor, paragraph(anchor, "The second paragraph."), "The second paragraph, edited.", edit);
        DocumentFiles.save(anchor, dir);

        DocumentEditor.cancel(anchor);
        assertEquals(DocumentFiles.Outcome.SAVED, DocumentFiles.revert(anchor, dir).outcome());

        assertEquals(committed, Files.readAllLines(dir.resolve("note.adoc")));
        assertEquals("", gitOut(dir, "status", "--porcelain"), "the working tree is as committed");
    }

    @Test
    void aKnowledgeBaseOpeningDropsTheCopiesAndKeepsOnlyUnsavedEdits() throws Exception {
        Path dir = repository("opening", "saved.adoc", "unsaved.adoc", "plain.adoc");
        PublicId saved = GitDocumentMaterializer.materialize(dir, dir.resolve("saved.adoc")).anchor();
        PublicId unsaved = GitDocumentMaterializer.materialize(dir, dir.resolve("unsaved.adoc")).anchor();
        PublicId plain = GitDocumentMaterializer.materialize(dir, dir.resolve("plain.adoc")).anchor();
        for (PublicId anchor : List.of(saved, unsaved, plain)) {
            String path = GitDocumentMaterializer.recordedPath(anchor).orElseThrow();
            WorkingTreeEdit.readFile(anchor, dir, path, edit);                      // as opening the file does
        }
        DocumentEditor.editBlock(saved, paragraph(saved, "The second paragraph."), "The second paragraph, saved.", edit);
        DocumentFiles.save(saved, dir);
        DocumentEditor.editBlock(unsaved, paragraph(unsaved, "The second paragraph."), "The second paragraph, not saved.", edit);
        List<String> savedText = Files.readAllLines(dir.resolve("saved.adoc"));
        UUID savedTransaction = DocumentEditor.pending(saved).orElseThrow().transactionUuid();
        long plainModule = PrimitiveData.nid(dev.ikm.tinkar.common.id.PublicIds.of(design.knowledge.stamp.adoc.identity.GitIdentityAdapter
                .fromMailmap(dir).module(DocumentModules.component(dir.getFileName().toString(), "plain.adoc")).uuid()));
        long savedModule = PrimitiveData.nid(dev.ikm.tinkar.common.id.PublicIds.of(design.knowledge.stamp.adoc.identity.GitIdentityAdapter
                .fromMailmap(dir).module(DocumentModules.component(dir.getFileName().toString(), "saved.adoc")).uuid()));
        assertTrue(dev.ikm.tinkar.entity.StampService.get().getModuleNidsInUse().contains(plainModule));
        PrimitiveData.save();                       // a store saving records the open transactions and what they wrote

        DocumentScopes.Dropped dropped = DocumentScopes.dropCopies();

        assertTrue(dropped.left().isEmpty(), String.valueOf(dropped.left()));
        assertTrue(dropped.dropped() >= 2, "the saved and the plain document are dropped: " + dropped);
        assertEquals(List.of(unsaved), dropped.unsaved(), "the document whose file does not hold its edit is kept");
        assertFalse(PrimitiveData.get().hasPublicId(saved));
        assertFalse(PrimitiveData.get().hasPublicId(plain));
        assertFalse(dev.ikm.tinkar.entity.StampService.get().getModuleNidsInUse().contains(plainModule),
                "a dropped document's module is no longer in use, so no menu of modules names it");
        assertFalse(dev.ikm.tinkar.entity.StampService.get().getModuleNidsInUse().contains(savedModule),
                "nor is the module of a document whose saved edit was cancelled first, which its cancelled STAMP still names");
        long record = PrimitiveData.nid(dev.ikm.tinkar.common.id.PublicIds.of(savedTransaction));
        assertTrue(dev.ikm.tinkar.entity.transaction.Transaction.endedRecord(record),
                "the cancelled edit's record stays, as bookkeeping that kept nothing from being dropped");
        assertTrue(DocumentEditor.pending(unsaved).isPresent(), "with its edit");
        assertTrue(DocumentScopes.takeUnsaved(unsaved), "its card asks about it");
        assertFalse(DocumentScopes.takeUnsaved(unsaved), "once");

        PublicId again = GitDocumentMaterializer.materialize(dir, dir.resolve("saved.adoc")).anchor();
        assertEquals(saved, again, "the document comes back under its identity");
        assertEquals(WorkingTreeEdit.Outcome.READ, WorkingTreeEdit.readFile(again, dir, "saved.adoc", edit).outcome());
        assertEquals(savedText, lines(again, true), "and its saved edit with it, read from the file");
        DocumentEditor.cancel(again);
        DocumentEditor.cancel(unsaved);
    }

    @Test
    void aNarrationWrittenAsKnowledgeBeforeTheEighthIsMovedIntoTheDocumentsRepository() throws Exception {
        Path dir = repository("narrated", "note.adoc");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, dir.resolve("note.adoc")).anchor();
        WorkingTreeEdit.readFile(anchor, dir, "note.adoc", edit);
        String head = gitOut(dir, "rev-parse", "HEAD");
        long stamp = GitDocumentMaterializer.commitStamp(anchor, head).orElseThrow().nid();
        PublicId narration = oldNarration(stamp, "Adds three paragraphs.");
        assertEquals(dev.ikm.tinkar.entity.boundary.DataKind.KNOWLEDGE, EntityBoundaries.kindOf(PrimitiveData.nid(narration)),
                "written as knowledge, as before the eighth");

        OldNarrations.Moved moved = OldNarrations.move();

        assertEquals(1, moved.moved(), String.valueOf(moved.left()));
        assertEquals(0, moved.reported());
        assertFalse(PrimitiveData.get().hasPublicId(narration), "the knowledge copy is erased");
        String commit = gitOut(dir, "log", "-1", "--format=%B");
        assertTrue(commit.contains("Adds three paragraphs."), commit);
        assertTrue(commit.contains(narration.asUuidArray()[0].toString()), "the annotation keeps the narration's identity: " + commit);
        assertTrue(commit.contains("Komet Narrator (Claude)"), "and its narrator: " + commit);
        assertEquals(OldNarrations.Moved.NONE, OldNarrations.move(), "nothing is left to move");

        DocumentScopes.Dropped dropped = DocumentScopes.dropCopies();
        assertTrue(dropped.left().isEmpty(), "nothing in knowledge keeps the document now: " + dropped.left());
        assertFalse(PrimitiveData.get().hasPublicId(anchor));
        PublicId again = GitDocumentMaterializer.materialize(dir, dir.resolve("note.adoc")).anchor();
        long readStamp = GitDocumentMaterializer.commitStamp(again, head).orElseThrow().nid();
        List<String> comments = new ArrayList<>();
        dev.ikm.tinkar.entity.EntityService.get().forEachSemanticOfPattern(dev.ikm.tinkar.terms.KernelPattern.COMMENT_PATTERN.nid(), c -> {
            if (c.referencedComponentNid() == readStamp) {
                comments.add(String.valueOf(c.versions().getFirst().fieldValues().get(0)));
            }
        });
        assertEquals(List.of("Adds three paragraphs."), comments, "opening the document reads the narration back from git");
    }

    @Test
    void aFileInAFolderWithoutGitIsReadAsOneVersionAndSavedWithoutCommitting() throws Exception {
        Path folder = Files.createDirectories(root.resolve("plain-folder"));
        Path file = folder.resolve("note.adoc");
        Files.writeString(file, NOTE.formatted("plain-note", "note"));
        long changed = java.time.Instant.parse("2026-11-20T09:30:00Z").toEpochMilli();
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(changed));
        dev.ikm.tinkar.terms.ConceptFacade person = dev.ikm.tinkar.terms.EntityProxy.Concept.make(edit.getAuthorNidForChanges());

        GitDocumentMaterializer.Result read = VertexMaterializer.materializeVersion(folder, Path.of("note.adoc"), person);

        PublicId anchor = read.anchor();
        assertEquals(GitDocumentMaterializer.documentAnchor(folder, "note.adoc"), anchor, "the folder plays the working tree's part");
        assertTrue(VertexMaterializer.textRevision(read.headSha()), read.headSha());
        assertEquals(Files.readAllLines(file), lines(anchor, false));
        GitDocumentMaterializer.StoredDocument stored = GitDocumentMaterializer.load(anchor, false).orElseThrow();
        assertEquals(java.util.Set.of(changed), stored.lines().stream().map(l -> l.introduced().time()).collect(java.util.stream.Collectors.toSet()),
                "every line has the time the file was last changed");
        assertEquals(java.util.Set.of(edit.getAuthorNidForChanges()),
                stored.lines().stream().map(l -> l.introduced().authorNid()).collect(java.util.stream.Collectors.toSet()),
                "and the person Komet opens as");
        assertEquals(dev.ikm.tinkar.entity.boundary.DataKind.FILE, EntityBoundaries.kindOf(PrimitiveData.nid(anchor)));
        assertEquals(0, VertexMaterializer.materializeVersion(folder, Path.of("note.adoc"), person).commits(), "the same text is not read again");

        DocumentFiles.remember(anchor, folder, "note.adoc", Files.readAllLines(file));
        DocumentEditor.editBlock(anchor, paragraph(anchor, "The second paragraph."), "The second paragraph, saved.", edit);
        DocumentFiles.Saved saved = DocumentFiles.save(anchor, folder);

        assertEquals(DocumentFiles.Outcome.SAVED, saved.outcome());
        assertEquals("Saved note.adoc.", saved.report(), "nothing is left to commit");
        assertTrue(DocumentEditor.pending(anchor).isEmpty(), "the save is the record: the edit is committed in the knowledge base");
        assertEquals(Files.readAllLines(file), lines(anchor, false));
        assertFalse(Files.exists(folder.resolve(".git")), "no repository is made");

        // Starting git history: the folder becomes a repository, and the document is read from git under the same identity.
        git(folder, "init", "-q", "-b", "main");
        git(folder, "config", "user.name", "Rita Reviewer");
        git(folder, "config", "user.email", "rita@example.test");
        git(folder, "config", "commit.gpgsign", "false");
        git(folder, "add", "note.adoc");
        git(folder, "commit", "-q", "-m", "Add note.adoc");
        DocumentScopes.dropForRebuild(anchor);
        PublicId fromGit = GitDocumentMaterializer.materialize(folder, file).anchor();
        assertEquals(anchor, fromGit);
        assertFalse(VertexMaterializer.textRevision(GitDocumentMaterializer.load(fromGit).orElseThrow().commit()));
        assertEquals(Files.readAllLines(file), lines(fromGit, false));
    }

    /** A narration as the narrator wrote one before 2026-11-08: a knowledge comment, in the module Narration, on a document's commit. */
    private static PublicId oldNarration(long stamp, String text) {
        UUID namespace = UUID.fromString("a7d3e8f0-2b14-4c96-9e5a-1f0c8b2d6e34");
        dev.ikm.tinkar.terms.EntityProxy.Concept author = dev.ikm.tinkar.terms.EntityProxy.Concept.make(
                dev.ikm.tinkar.common.id.PublicIds.of(dev.ikm.tinkar.common.util.uuid.UuidT5Generator.get(namespace, "komet-narrator-author")));
        dev.ikm.tinkar.terms.EntityProxy.Concept module = dev.ikm.tinkar.terms.EntityProxy.Concept.make(OldNarrations.NARRATION_MODULE);
        for (var seed : List.of(java.util.Map.entry(author, "Komet Narrator (Claude)"), java.util.Map.entry(module, "Narration"))) {
            if (!PrimitiveData.get().hasPublicId(seed.getKey().publicId())) {
                try (dev.ikm.tinkar.entity.transaction.StampedWriter writer = dev.ikm.tinkar.entity.transaction.StampedWriter.open("seed",
                        dev.ikm.tinkar.terms.State.ACTIVE, dev.ikm.tinkar.terms.KernelConcept.USER, dev.ikm.komet.terms.KometConcept.DEVELOPMENT_MODULE,
                        dev.ikm.tinkar.terms.KernelConcept.DEVELOPMENT_PATH)) {
                    writer.concept(seed.getKey().publicId());
                    writer.fullyQualifiedName(seed.getKey().publicId(), seed.getValue());
                    writer.commit();
                }
            }
        }
        PublicId id = dev.ikm.tinkar.common.id.PublicIds.of(UUID.fromString("0b4ad1f6-6d4c-4c62-9a59-2f8e3c1d7a10"));
        String mode = System.getProperty(dev.ikm.tinkar.entity.boundary.CommitBoundary.MODE_PROPERTY);
        System.setProperty(dev.ikm.tinkar.entity.boundary.CommitBoundary.MODE_PROPERTY, "off");   // the rule did not exist yet
        try (dev.ikm.tinkar.entity.transaction.StampedWriter writer = dev.ikm.tinkar.entity.transaction.StampedWriter.open("komet-narrator",
                dev.ikm.tinkar.terms.State.ACTIVE, author, module, dev.ikm.tinkar.terms.KernelConcept.DEVELOPMENT_PATH)) {
            writer.semantic(id, dev.ikm.tinkar.terms.KernelPattern.COMMENT_PATTERN.publicId(), dev.ikm.tinkar.terms.EntityProxy.make(stamp), text);
            writer.commit();
        } finally {
            if (mode == null) {
                System.clearProperty(dev.ikm.tinkar.entity.boundary.CommitBoundary.MODE_PROPERTY);
            } else {
                System.setProperty(dev.ikm.tinkar.entity.boundary.CommitBoundary.MODE_PROPERTY, mode);
            }
        }
        return id;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    @Test
    void aDocumentsOwnModuleIsFileDataFromItsFirstRecord() throws Exception {
        // Each record goes to a change set as it is put. Before 2026-11-23 the module counted as knowledge until
        // its parent was stated, so its STAMP and concept went to the knowledge change set, which is synced and
        // is loaded when the knowledge base next opens: the module came back after its document was dropped.
        Path dir = repository("own-module", "note.adoc");
        java.util.Map<Long, DataKind> putAs = new java.util.LinkedHashMap<>();
        dev.ikm.tinkar.common.util.broadcast.Subscriber<Long> watching = nid ->
                dev.ikm.tinkar.entity.EntityHandle.get(nid).entity().ifPresent(entity -> entity.versions().forEach(version ->
                        putAs.putIfAbsent(nid, ModuleKinds.resolve(EntityBoundaries.moduleOf(version.stampNid())).kind())));
        dev.ikm.tinkar.entity.Entity.provider().addSubscriberWithWeakReference(watching);
        PublicId anchor;
        try {
            anchor = GitDocumentMaterializer.materialize(dir, dir.resolve("note.adoc")).anchor();
        } finally {
            dev.ikm.tinkar.entity.Entity.provider().removeSubscriber(watching);
        }
        long module = EntityBoundaries.moduleOf(dev.ikm.tinkar.entity.EntityHandle.get(anchor).expectEntity().versions().getFirst().stampNid());
        List<Long> own = putAs.keySet().stream().filter(nid -> dev.ikm.tinkar.entity.EntityHandle.get(nid).entity().stream()
                .flatMap(entity -> entity.versions().stream()).anyMatch(v -> EntityBoundaries.moduleOf(v.stampNid()) == module)).toList();
        assertTrue(own.contains(module), "the module's own concept is among what was put");
        for (long nid : own) {
            assertEquals(DataKind.FILE, putAs.get(nid), () -> "file data as it was put: "
                    + dev.ikm.tinkar.entity.EntityHandle.get(nid).expectEntity().publicId().idString());
        }
    }

    private static List<String> lines(PublicId anchor, boolean pending) {
        return GitDocumentExporter.export(anchor, pending).orElseThrow().lines();
    }

    /** The paragraph whose first line is the given text. */
    private static long paragraph(PublicId anchor, String firstLine) {
        GitDocumentMaterializer.StoredDocument document = GitDocumentMaterializer.load(anchor, true).orElseThrow();
        long line = document.lines().stream().filter(l -> l.text().equals(firstLine)).findFirst().orElseThrow().nid();
        return document.blocks().values().stream()
                .filter(b -> b.kind() == Block.Kind.PARAGRAPH && !b.members().isEmpty() && b.members().getFirst() == line)
                .findFirst().orElseThrow().nid();
    }

    private Path repository(String name, String... files) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name));
        git(dir, "init", "-q", "-b", "main");
        git(dir, "config", "user.name", "Rita Reviewer");
        git(dir, "config", "user.email", "rita@example.test");
        git(dir, "config", "commit.gpgsign", "false");
        for (String file : files) {
            String title = file.replace(".adoc", "");
            Files.writeString(dir.resolve(file), NOTE.formatted(name + "-" + title, title));
            git(dir, "add", file);
        }
        ProcessBuilder commit = new ProcessBuilder("git", "commit", "-q", "-m", "Write " + String.join(", ", files),
                "--date", "2026-11-22T10:00:00Z").directory(dir.toFile()).redirectErrorStream(true);
        commit.environment().put("GIT_COMMITTER_DATE", "2026-11-22T10:00:00Z");
        Process p = commit.start();
        String out = new String(p.getInputStream().readAllBytes()).strip();
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git commit failed: " + out);
        }
        return dir;
    }

    private static void git(Path dir, String... args) throws Exception {
        gitOut(dir, args);
    }

    private static String gitOut(Path dir, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes()).strip();
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
        }
        return out;
    }
}

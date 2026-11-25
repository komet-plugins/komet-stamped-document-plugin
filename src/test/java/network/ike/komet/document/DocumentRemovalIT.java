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

import design.knowledge.stamp.adoc.dialect.Block;
import design.knowledge.stamp.adoc.git.AnnotationCommit;
import design.knowledge.stamp.adoc.identity.GitIdentityAdapter;
import design.knowledge.stamp.adoc.model.Annotation;
import design.knowledge.stamp.adoc.model.AnnotationKind;
import design.knowledge.stamp.adoc.model.AnnotationTarget;
import design.knowledge.stamp.adoc.model.KonceptRef;
import dev.ikm.komet.actions.KometActionResult;
import dev.ikm.komet.actions.KometActions;
import dev.ikm.komet.terms.KometConcept;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.Coordinates;
import dev.ikm.tinkar.coordinate.edit.EditCoordinateRecord;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.EntityHandle;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Removing documents read in from git from the knowledge base (IKE-Network/ike-issues#1313, plan
 * {@code plan-2026-11-22-remove-export-import-data}, step 1): a folder's documents leave in one
 * step through the {@code Remove from knowledge base} action, with their annotations, while
 * the rest of the working tree stays; opening the files again brings them back under the same
 * identities, with the annotations from the commits. A refused removal leaves everything as it was.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentRemovalIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");
    private static final UUID NARRATOR = UUID.fromString("06f3a6e6-3627-5115-94fb-238c789b8743");
    private static final String NOTE = """
            :topic-id: dev-removal-%s

            = %s

            The first claim.
            The second claim.
            """;

    @TempDir
    Path root;

    private EditCoordinateRecord edit;
    private ViewCalculator view;

    @BeforeAll
    void setupDatabase() {
        assertTrue(PB_STARTER_DATA.exists(), "Starter data must be present at " + PB_STARTER_DATA.getAbsolutePath());
        PrimitiveData.selectControllerByName("Load Memory-Only 64-bit Store");
        PrimitiveData.start();
        assertTrue(new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute().getTotalCount() > 0);
        edit = Coordinates.Edit.Default();
        view = Calculators.View.Default();
    }

    @AfterAll
    void teardownDatabase() {
        ModuleKinds.clear();
        EntityBoundaries.clear();
        PrimitiveData.stop();
    }

    @Test
    void aFolderLeavesInOneStepAndComesBackWithItsAnnotations() throws Exception {
        Path dir = repository("library", "regulatory/intro.adoc", "regulatory/part/detail.adoc");
        add(dir, "2026-11-22T12:00:00Z", "library", "other.adoc");                 // a commit of its own, a STAMP of its own
        PublicId intro = GitDocumentMaterializer.materialize(dir, dir.resolve("regulatory/intro.adoc")).anchor();
        PublicId detail = GitDocumentMaterializer.materialize(dir, dir.resolve("regulatory/part/detail.adoc")).anchor();
        PublicId other = GitDocumentMaterializer.materialize(dir, dir.resolve("other.adoc")).anchor();
        PublicId comment = narrate(dir, "Two claims, first stated.");
        GitDocumentMaterializer.materialize(dir, dir.resolve("regulatory/intro.adoc"), true);
        assertTrue(PrimitiveData.get().hasPublicId(comment), "the narration is read in with its document");
        int introLines = GitDocumentMaterializer.load(intro).orElseThrow().lines().size();
        PublicId introModule = moduleOf(dir, "regulatory/intro.adoc");
        PublicId otherModule = moduleOf(dir, "other.adoc");
        assertNotEquals(introModule, otherModule, "each document has a module of its own");
        assertEquals(DataKind.FILE, ModuleKinds.resolve(PrimitiveData.nid(introModule)).kind(), "a kind of File module");
        assertEquals(DataKind.FILE, EntityBoundaries.kindOf(PrimitiveData.nid(introModule)),
                "written under itself, so the module concept is the document's own file data");
        dev.ikm.tinkar.entity.scope.Scope introScope = DocumentScopes.of(intro);
        assertTrue(introScope.members().contains(PrimitiveData.nid(introModule)), "the module is part of the document's scope");
        assertFalse(introScope.knowledge().contains(PrimitiveData.nid(introModule)),
                "so an export carries it, and no other knowledge base needs it already");

        List<PublicId> under = DocumentScopes.storedUnder(dir, dir.resolve("regulatory"));
        assertEquals(Set.of(intro, detail), Set.copyOf(under), "the folder's documents at any depth, and no other");

        KometActionResult result = remove(under);
        assertTrue(result.completed(), result.message());
        assertEquals("Removed 2 documents from the knowledge base. Their files stay where they are, and opening one reads it back in.",
                result.message());
        assertFalse(PrimitiveData.get().hasPublicId(intro), "the first document is gone");
        assertFalse(PrimitiveData.get().hasPublicId(detail), "the document in the folder below is gone");
        assertFalse(PrimitiveData.get().hasPublicId(comment), "its narration goes with it");
        assertTrue(PrimitiveData.get().hasPublicId(other), "a document outside the folder stays");
        assertFalse(PrimitiveData.get().hasPublicId(introModule), "a document's own module goes with it");
        assertTrue(PrimitiveData.get().hasPublicId(otherModule), "and another document's module stays");
        assertEquals(List.of(), DocumentScopes.storedUnder(dir, dir.resolve("regulatory")));
        assertTrue(Files.isRegularFile(dir.resolve("regulatory/intro.adoc")), "the file stays where it is");

        assertEquals(intro, GitDocumentMaterializer.materialize(dir, dir.resolve("regulatory/intro.adoc")).anchor(),
                "opening the file again reads the document back in under the same identity");
        assertEquals(detail, GitDocumentMaterializer.materialize(dir, dir.resolve("regulatory/part/detail.adoc")).anchor());
        assertTrue(PrimitiveData.get().hasPublicId(comment), "with its narration from the commit");
        assertTrue(PrimitiveData.get().hasPublicId(introModule), "and its own module");
        assertEquals(1, EntityHandle.get(comment).expectSemantic().versions().size());
        assertEquals(introLines, GitDocumentMaterializer.load(intro).orElseThrow().lines().size());
    }

    @Test
    void aPendingEditRefusesTheRemovalAndNothingLeaves() throws Exception {
        Path dir = repository("pending", "pending.adoc");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, dir.resolve("pending.adoc")).anchor();
        DocumentEditor.editBlock(anchor, DocumentEditingIT.blockStartingWith(anchor, Block.Kind.PARAGRAPH, "The first claim."),
                "The first claim, revised.\nThe second claim.\n", edit);

        KometActionResult refused = remove(List.of(anchor));
        assertEquals(KometConcept.ACTION_REFUSED.nid(), refused.outcome().nid(), refused.message());
        assertEquals("An edit of pending.adoc is not yet saved to git. Commit it in git, or discard the edit, before"
                + " removing the document.", refused.message());
        assertTrue(PrimitiveData.get().hasPublicId(anchor), "nothing is removed");
        assertTrue(DocumentEditor.pending(anchor).isPresent(), "the edit stays pending");

        assertTrue(DocumentEditor.cancel(anchor));
        assertTrue(remove(List.of(anchor)).completed(), "once the edit is discarded, the document can be removed");
        assertFalse(PrimitiveData.get().hasPublicId(anchor));
    }

    @Test
    void knowledgeIsNotRemoved() {
        PublicId knowledge = KometConcept.KOMET_ACTION.publicId();
        KometActionResult refused = remove(List.of(knowledge));
        assertEquals(KometConcept.ACTION_REFUSED.nid(), refused.outcome().nid(), refused.message());
        assertTrue(refused.message().endsWith(" is not from git, so it is not removed this way."), refused.message());
        assertTrue(PrimitiveData.get().hasPublicId(knowledge));
    }

    /** A document's own module: its working tree's folder name and its path (KEC, 2026-11-22). */
    private static PublicId moduleOf(Path dir, String path) throws Exception {
        return PublicIds.of(GitIdentityAdapter.fromMailmap(dir).module(DocumentModules.component(dir.getFileName().toString(), path)).uuid());
    }

    private KometActionResult remove(List<PublicId> anchors) {
        return KometActions.expect(KometConcept.REMOVE_FROM_KNOWLEDGE_BASE)
                .perform(RemoveFromKnowledgeBase.input(anchors), view, edit);
    }

    /** Signs a narration of HEAD into the repository, as {@link NarrationIT} does, and returns its id. */
    private static PublicId narrate(Path dir, String text) throws Exception {
        String head = gitOut(dir, "log", "-1", "--format=%H", "--", "regulatory/intro.adoc");
        PublicId id = PublicIds.of(UUID.fromString("5b0c8e1a-2f3d-5e6a-8b7c-9d0e1f2a3b4c"));
        Annotation narration = new Annotation(AnnotationKind.COMMENT.ref(),
                new AnnotationTarget.ChangeSet(new KonceptRef(GitIdentityAdapter.fromMailmap(dir).stamp(head).uuid(),
                        head.substring(0, 7))),
                new KonceptRef(NARRATOR, "Komet Narrator (Claude)"), Instant.parse("2026-11-22T18:00:00Z"), id.asUuidArray()[0]);
        AnnotationCommit.Result written = AnnotationCommit.write(dir, List.of(narration), text, false);
        assertTrue(written.ok(), written.output());
        return id;
    }

    private Path repository(String name, String... files) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name));
        git(dir, "init", "-q", "-b", "main");
        git(dir, "config", "user.name", "Rita Reviewer");
        git(dir, "config", "user.email", "rita@example.test");
        git(dir, "config", "commit.gpgsign", "false");
        add(dir, "2026-11-22T10:00:00Z", name, files);
        return dir;
    }

    /** Writes and commits notes at a given time, so commits made in the same second differ. */
    private static void add(Path dir, String when, String name, String... files) throws Exception {
        for (String file : files) {
            Path path = dir.resolve(file);
            Files.createDirectories(path.getParent());
            String title = path.getFileName().toString().replace(".adoc", "");
            Files.writeString(path, NOTE.formatted(name + "-" + title, title));
            git(dir, "add", file);
        }
        ProcessBuilder commit = new ProcessBuilder("git", "commit", "-q", "-m", "Write " + String.join(", ", files), "--date", when)
                .directory(dir.toFile()).redirectErrorStream(true);
        commit.environment().put("GIT_COMMITTER_DATE", when);
        Process p = commit.start();
        String out = new String(p.getInputStream().readAllBytes()).strip();
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git commit failed: " + out);
        }
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

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

import design.knowledge.stamp.adoc.git.AnnotationCommit;
import design.knowledge.stamp.adoc.git.GitRepo;
import design.knowledge.stamp.adoc.identity.GitIdentityAdapter;
import design.knowledge.stamp.adoc.model.Annotation;
import design.knowledge.stamp.adoc.model.AnnotationKind;
import design.knowledge.stamp.adoc.model.AnnotationTarget;
import design.knowledge.stamp.adoc.model.KonceptRef;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.StampEntity;
import dev.ikm.tinkar.entity.boundary.DataKind;
import dev.ikm.tinkar.entity.boundary.EntityBoundaries;
import dev.ikm.tinkar.entity.boundary.ModuleKinds;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.entity.scope.Scope;
import dev.ikm.tinkar.terms.KernelPattern;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A narration of a stamped document's commit, contained in the document's git repository
 * (IKE-Network/ike-issues#1355): rebuilding the document from git writes it as a Comment on the commit's
 * STAMP, by its annotator, under the document's ephemeral module, inside the document's scope; rebuilding
 * again adds nothing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NarrationIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");
    private static final UUID NARRATOR = UUID.fromString("06f3a6e6-3627-5115-94fb-238c789b8743");
    private static final String NOTE = """
            :topic-id: dev-narrated-%s

            = Narrated

            The first claim.
            The second claim.
            """;

    @TempDir
    Path root;

    @BeforeAll
    void setupDatabase() {
        assertTrue(PB_STARTER_DATA.exists(), "Starter data must be present at " + PB_STARTER_DATA.getAbsolutePath());
        PrimitiveData.selectControllerByName("Load Memory-Only 64-bit Store");
        PrimitiveData.start();
        assertTrue(new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute().getTotalCount() > 0);
        System.setProperty(VertexMaterializer.MODEL_PROPERTY, "vertex");
    }

    @AfterAll
    void teardownDatabase() {
        System.clearProperty(VertexMaterializer.MODEL_PROPERTY);
        ModuleKinds.clear();
        EntityBoundaries.clear();
        PrimitiveData.stop();
    }

    @Test
    void aNarrationInGitIsACommentOnTheCommitsStampAndARebuildAddsNothing() throws Exception {
        Path dir = repository("rebuilt");
        Path file = dir.resolve("narrated.adoc");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, file).anchor();
        String narrated;
        try (GitRepo repo = GitRepo.open(dir)) {
            narrated = repo.headRevision();
        }
        StampEntity<?> stamp = GitDocumentMaterializer.commitStamp(anchor, narrated).orElseThrow();
        PublicId id = PublicIds.of(UUID.fromString("0d6f1c2e-7a3b-5c4d-9e8f-1a2b3c4d5e6f"));
        Annotation narration = new Annotation(AnnotationKind.COMMENT.ref(),
                new AnnotationTarget.ChangeSet(new KonceptRef(GitIdentityAdapter.fromMailmap(dir).stamp(narrated).uuid(),
                        narrated.substring(0, 7))),
                new KonceptRef(NARRATOR, "Komet Narrator (Claude)"), Instant.parse("2026-11-08T18:00:00Z"), id.asUuidArray()[0]);

        AnnotationCommit.Result written = AnnotationCommit.write(dir, List.of(narration), "Two claims, first stated.", false);
        assertTrue(written.ok(), written.output());
        GitDocumentMaterializer.materialize(dir, file, true);

        SemanticEntity<?> comment = EntityHandle.get(id).expectSemantic();
        assertEquals(KernelPattern.COMMENT_PATTERN.nid(), comment.patternNid());
        assertEquals(stamp.nid(), comment.referencedComponentNid(), "on the narrated commit's STAMP");
        assertEquals(1, comment.versions().size());
        SemanticEntityVersion version = (SemanticEntityVersion) comment.versions().getFirst();
        assertEquals("Two claims, first stated.", String.valueOf(version.fieldValues().getFirst()).strip());
        assertEquals(PublicIds.of(NARRATOR), version.stamp().author().publicId(), "by its annotator");
        assertEquals(Instant.parse("2026-11-08T18:00:00Z").toEpochMilli(), version.stamp().time(), "at its time");
        assertEquals(stamp.lastVersion().moduleNid(), version.stamp().moduleNid(), "under the document's module");
        assertEquals(DataKind.EPHEMERAL, EntityBoundaries.kindOf(comment.nid()));

        Scope scope = DocumentScopes.of(anchor);
        assertTrue(scope.members().contains(comment.nid()), "the narration goes with its document");
        assertTrue(scope.closed(), scope.incoming() + " " + scope.outgoing() + " " + scope.dangling());

        GitDocumentMaterializer.materialize(dir, file, false);
        assertEquals(1, EntityHandle.get(id).expectSemantic().versions().size(), "a rebuild from git adds nothing");
    }

    private Path repository(String name) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name));
        git(dir, "init", "-q", "-b", "main");
        git(dir, "config", "user.name", "Rita Reviewer");
        git(dir, "config", "user.email", "rita@example.test");
        git(dir, "config", "commit.gpgsign", "false");
        Files.writeString(dir.resolve("narrated.adoc"), NOTE.formatted(name));
        git(dir, "add", "narrated.adoc");
        git(dir, "commit", "-q", "-m", "Write the narrated note");
        return dir;
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
}

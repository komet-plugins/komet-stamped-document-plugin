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

import dev.ikm.komet.preferences.KometPreferences;
import dev.ikm.komet.preferences.KometPreferencesImpl;
import dev.ikm.tinkar.common.id.PublicId;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * A document's file, which is its record (IKE-Network/ike-issues#1313; step 2 of
 * {@code design-2026-11-22-knowledge-shared-local}, settled by KEC on 2026-11-22). Save writes a
 * document with its pending edit into its file without committing it; Komet saves every changed
 * document when it quits; and Discard puts the file back as last committed.
 *
 * <p>Komet never overwrites a file that changed after Komet last read or wrote it, since that
 * change is someone else's: it says so, and the person chooses. What Komet last read or wrote is
 * kept for each document in the knowledge base's configuration preferences, as a digest of the
 * text and where the file is, so a file can be found again after Komet restarts.
 */
public final class DocumentFiles {

    /** Where each document's file is remembered, one node per document. */
    private static final String KNOWN = "network/ike/komet/document/files";

    private DocumentFiles() { }

    /** What saving a document came to. */
    public enum Outcome {
        /** The file now holds the document with its pending edit. */
        SAVED,
        /** The file already held it. */
        ALREADY,
        /** The file changed after Komet last read or wrote it, so it was not overwritten. */
        CHANGED_OUTSIDE,
        /** No edit is pending, or Komet cannot tell where the document's file is. */
        NOTHING
    }

    /**
     * What saving did.
     *
     * @param outcome what it came to
     * @param file    the file, or {@code null} when none is known
     * @param git     whether the file has a git history, so the edit is still to be committed
     */
    public record Saved(Outcome outcome, Path file, boolean git) {

        /**
         * @param outcome what it came to
         * @param file    the file, or {@code null} when none is known
         */
        public Saved(Outcome outcome, Path file) {
            this(outcome, file, true);
        }

        /** @return the saving in words for a status line */
        public String report() {
            String name = file == null ? "The document" : String.valueOf(file.getFileName());
            return switch (outcome) {
                case SAVED -> git ? "Saved " + name + ". The edit is not committed; Commit records it in git." : "Saved " + name + ".";
                case ALREADY -> name + " already holds the edit.";
                case CHANGED_OUTSIDE -> name + " changed after Komet read it, so Komet did not overwrite it."
                        + " Commit or discard that change in git, or discard the edit here.";
                case NOTHING -> file == null ? "Komet cannot tell where the document's file is." : "No edit is pending.";
            };
        }
    }

    /**
     * Remembers the text Komet read from a document's file or wrote to it, and where the file is.
     *
     * @param anchor the document anchor's id
     * @param root   the working tree, or the folder, that holds the file
     * @param path   the file's path in it
     * @param lines  the file's lines
     */
    static void remember(PublicId anchor, Path root, String path, List<String> lines) {
        KometPreferences known = known(anchor);
        known.put("digest", digest(lines));
        known.put("root", root.toAbsolutePath().normalize().toString());
        known.put("path", path);
        try {
            known.flush();
        } catch (java.util.prefs.BackingStoreException e) {
            org.slf4j.LoggerFactory.getLogger(DocumentFiles.class).warn("Could not remember the file of {}", anchor.idString(), e);
        }
    }

    /**
     * The working tree or folder that holds a document's file: where Komet last read or wrote it,
     * else the working trees opened this session and the document library.
     *
     * @param anchor the document anchor's id
     * @return the folder, or empty when Komet cannot tell
     */
    public static Optional<Path> rootOf(PublicId anchor) {
        KometPreferences known = known(anchor);
        String root = known.get("root", "");
        String path = known.get("path", "");
        if (!root.isEmpty() && !path.isEmpty() && Files.isRegularFile(Path.of(root).resolve(path))
                && PublicId.equals(GitDocumentMaterializer.documentAnchor(Path.of(root), path), anchor)) {
            return Optional.of(Path.of(root));
        }
        Optional<String> recorded = GitDocumentMaterializer.recordedPath(anchor);
        if (recorded.isEmpty()) {
            return Optional.empty();
        }
        return DocumentHome.find(recorded.get(), DocumentNarratives.workingTrees(), DocumentLibrary.root(),
                (tree, file) -> PublicId.equals(GitDocumentMaterializer.documentAnchor(tree, file), anchor));
    }

    /**
     * Whether a file's text is not what Komet last read from it or wrote to it. When Komet has no
     * record, the file is taken as read when it holds the document as last committed.
     *
     * @param anchor  the document anchor's id
     * @param current the file's lines now
     * @return whether someone else changed it
     */
    static boolean changedOutside(PublicId anchor, List<String> current) {
        String digest = known(anchor).get("digest", "");
        if (!digest.isEmpty()) {
            return !digest.equals(digest(current));
        }
        return GitDocumentExporter.export(anchor, false).map(committed -> !committed.lines().equals(current)).orElse(true);
    }

    /**
     * Whether a document's file is not what Komet last read from it or wrote to it, or Komet has
     * no record of reading it: cheap enough to ask each time a document is shown.
     *
     * @param anchor the document anchor's id
     * @param root   the working tree or folder that holds the file
     * @param path   the file's path in it
     * @return whether the file should be read again
     */
    static boolean changedSinceRead(PublicId anchor, Path root, String path) {
        String digest = known(anchor).get("digest", "");
        if (digest.isEmpty()) {
            return true;
        }
        try {
            return !digest.equals(digest(Files.readAllLines(root.resolve(path))));
        } catch (IOException unreadable) {
            return false;
        }
    }

    /**
     * Writes a document with its pending edit into its file, without committing it, unless the
     * file changed after Komet last read or wrote it.
     *
     * @param anchor the document anchor's id
     * @param root   the working tree or folder that holds the file
     * @return what saving did
     * @throws IOException when the file cannot be read or written
     */
    public static synchronized Saved save(PublicId anchor, Path root) throws IOException {
        Optional<GitDocumentExporter.Exported> pending = DocumentEditor.pending(anchor).isPresent()
                ? GitDocumentExporter.export(anchor, true) : Optional.empty();
        if (pending.isEmpty()) {
            return new Saved(Outcome.NOTHING, root == null ? null : root.resolve(GitDocumentMaterializer.recordedPath(anchor).orElse("")));
        }
        Path file = root.resolve(pending.get().path());
        List<String> current = Files.isRegularFile(file) ? Files.readAllLines(file) : List.of();
        if (current.equals(pending.get().lines())) {
            remember(anchor, root, pending.get().path(), current);
            return new Saved(Outcome.ALREADY, file);
        }
        if (Files.isRegularFile(file) && changedOutside(anchor, current)) {
            return new Saved(Outcome.CHANGED_OUTSIDE, file);
        }
        GitDocumentExporter.writeTo(pending.get(), root);
        remember(anchor, root, pending.get().path(), pending.get().lines());
        if (VertexMaterializer.textRevision(pending.get().commit())) {
            // A file with no git history: the save is the record, and the lines it changed take its time.
            DocumentEditor.pending(anchor).ifPresent(dev.ikm.tinkar.entity.transaction.Transaction::commit);
            return new Saved(Outcome.SAVED, file, false);
        }
        return new Saved(Outcome.SAVED, file);
    }

    /**
     * Puts a document's file back as last committed, as discarding its edit does, unless the file
     * changed after Komet last read or wrote it.
     *
     * @param anchor the document anchor's id
     * @param root   the working tree or folder that holds the file
     * @return what was done: {@link Outcome#SAVED} when the file was written back
     * @throws IOException when the file cannot be read or written
     */
    static synchronized Saved revert(PublicId anchor, Path root) throws IOException {
        Optional<GitDocumentExporter.Exported> committed = GitDocumentExporter.export(anchor, false);
        if (committed.isEmpty()) {
            return new Saved(Outcome.NOTHING, null);
        }
        Path file = root.resolve(committed.get().path());
        List<String> current = Files.isRegularFile(file) ? Files.readAllLines(file) : List.of();
        if (current.equals(committed.get().lines())) {
            remember(anchor, root, committed.get().path(), current);
            return new Saved(Outcome.ALREADY, file);
        }
        if (Files.isRegularFile(file) && changedOutside(anchor, current)) {
            return new Saved(Outcome.CHANGED_OUTSIDE, file);
        }
        GitDocumentExporter.writeTo(committed.get(), root);
        remember(anchor, root, committed.get().path(), committed.get().lines());
        return new Saved(Outcome.SAVED, file);
    }

    /**
     * Whether a document holds an edit its file does not: one is pending, and the file, where
     * Komet can find it, holds something else. A document whose file Komet cannot find counts as
     * unsaved, so its edit is never dropped unseen.
     *
     * @param anchor the document anchor's id
     * @return whether the edit is not saved
     */
    public static boolean unsaved(PublicId anchor) {
        if (DocumentEditor.pending(anchor).isEmpty()) {
            return false;
        }
        Optional<Path> root = rootOf(anchor);
        Optional<GitDocumentExporter.Exported> pending = GitDocumentExporter.export(anchor, true);
        if (root.isEmpty() || pending.isEmpty()) {
            return true;
        }
        Path file = root.get().resolve(pending.get().path());
        try {
            return !Files.isRegularFile(file) || !Files.readAllLines(file).equals(pending.get().lines());
        } catch (IOException unreadable) {
            return true;
        }
    }

    /**
     * What saving every edited document did as Komet quit.
     *
     * @param saved    the documents whose files now hold their edits
     * @param notSaved the documents left unsaved, each with why
     */
    public record SavedAll(int saved, List<String> notSaved) { }

    /**
     * Saves every document saved in a file that holds an edit its file does not, as Komet quits.
     * A file that changed after Komet read it is not overwritten: the document is then kept when
     * the knowledge base next opens, and its card asks.
     *
     * @return what was saved and what was not
     */
    public static SavedAll saveAll() {
        if (GitStampVocabulary.entityAbsent(network.ike.foundation.ike.bindings.IkePattern.GIT_SOURCE_PATTERN)) {
            return new SavedAll(0, List.of());
        }
        java.util.Set<PublicId> stored = new java.util.LinkedHashSet<>();
        GitDocumentMaterializer.storedDocumentsByPath().values().forEach(stored::addAll);
        int saved = 0;
        List<String> notSaved = new java.util.ArrayList<>();
        for (PublicId anchor : stored) {
            if (DocumentEditor.pending(anchor).isEmpty() || !hasRecordInFile(anchor)) {
                continue;
            }
            String name = DocumentScopes.name(anchor);
            Optional<Path> root = rootOf(anchor);
            if (root.isEmpty()) {
                notSaved.add(name + ": Komet cannot tell where its file is");
                continue;
            }
            try {
                Saved done = save(anchor, root.get());
                switch (done.outcome()) {
                    case SAVED -> saved++;
                    case ALREADY, NOTHING -> { }
                    case CHANGED_OUTSIDE -> notSaved.add(name + ": its file changed after Komet read it");
                }
            } catch (IOException | RuntimeException failed) {
                notSaved.add(name + ": " + failed.getMessage());
            }
        }
        return new SavedAll(saved, List.copyOf(notSaved));
    }

    /** Whether a document's record is a file: one saved in a file, or a shared one whose file is on this computer. */
    static boolean hasRecordInFile(PublicId anchor) {
        dev.ikm.tinkar.entity.boundary.DataKind kind = dev.ikm.tinkar.entity.boundary.EntityBoundaries.kindOf(
                dev.ikm.tinkar.entity.EntityService.get().nidForPublicId(anchor));
        return kind == dev.ikm.tinkar.entity.boundary.DataKind.FILE
                || kind == dev.ikm.tinkar.entity.boundary.DataKind.SHARED && rootOf(anchor).isPresent();
    }

    private static KometPreferences known(PublicId anchor) {
        return KometPreferencesImpl.getConfigurationRootPreferences().node(KNOWN + "/" + anchor.asUuidArray()[0]);
    }

    static String digest(List<String> lines) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(String.join("\n", lines).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}

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
import design.knowledge.stamp.adoc.dialect.IkeDialectParser;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.coordinate.edit.EditCoordinate;
import dev.ikm.tinkar.entity.transaction.Transaction;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads a document's file as it is (IKE-Network/ike-issues#1313; the first question before step 2
 * of {@code design-2026-11-22-knowledge-shared-local}, settled by KEC on 2026-11-22). Komet saves
 * a document to its file without committing it, so the file can differ from the last commit, which
 * is what a materialization reads. Where it differs, the difference is written as a pending edit by
 * the person Komet opens as, the person git would name if they committed it now: the edit the
 * bar at the bottom of the card offers to commit, as for an edit made in Komet.
 *
 * <p>The difference is read as the edits Komet itself makes: a block whose text changed is edited
 * ({@link DocumentEditor#editBlock}), a new block is added after the one before it
 * ({@link DocumentEditor#addBlock}), and a block that is gone is deleted
 * ({@link DocumentEditor#deleteBlock}). The blocks are matched in order on their kind and text, and
 * a change to a block that holds other blocks, such as a section's heading, is not read this way.
 * The result is then compared with the file line for line; when they differ, or an edit is refused,
 * nothing is left pending and the document shows its last commit, with the reason.
 *
 * <p>Nothing is read when the document already has an edit pending in Komet: that edit stands, and
 * whether the file holds it is for saving to settle.
 */
final class WorkingTreeEdit {

    private WorkingTreeEdit() { }

    /** What reading the file came to. */
    enum Outcome {
        /** The file holds the last commit's text: nothing to read. */
        SAME,
        /** The file's difference from the last commit is now a pending edit. */
        READ,
        /** An edit made in Komet is already pending, so the file was not read over it. */
        PENDING,
        /** The difference could not be read as an edit; the document shows its last commit. */
        UNREAD
    }

    /**
     * What reading the file did.
     *
     * @param outcome what it came to
     * @param edited  blocks whose text changed
     * @param added   blocks added
     * @param deleted blocks deleted
     * @param reason  why the difference was not read, for {@link Outcome#UNREAD}; else empty
     */
    record Read(Outcome outcome, int edited, int added, int deleted, String reason) {

        static Read of(Outcome outcome) {
            return new Read(outcome, 0, 0, 0, "");
        }

        static Read unread(String reason) {
            return new Read(Outcome.UNREAD, 0, 0, 0, reason);
        }

        /**
         * @param path the file's path, for the sentence
         * @return the reading in words for the card's status line; empty when there was nothing to say
         */
        String report(String path) {
            return switch (outcome) {
                case SAME, PENDING -> "";
                case READ -> path + " has changes that are saved and not committed: "
                        + counted(edited, "block edited", "blocks edited") + ", "
                        + counted(added, "added", "added") + ", " + counted(deleted, "deleted", "deleted")
                        + ". They are shown as an uncommitted edit; Commit records them in git.";
                case UNREAD -> path + " has changes that are saved and not committed, which Komet cannot show as an edit ("
                        + reason + "). The card shows the last commit; commit the changes in git to see them here.";
            };
        }

        private static String counted(int n, String one, String many) {
            return n + " " + (n == 1 ? one : many);
        }
    }

    /** One step of the edit the difference is read as, in the new text's order. */
    private sealed interface Step permits Edit, Add, Delete { }

    /** A block whose text changed. */
    private record Edit(long block, String text) implements Step { }

    /** A new block, after the stored block {@code after}, or after the new block of an earlier step when {@code afterStep} is not negative. */
    private record Add(long after, int afterStep, Block.Kind kind, String text) implements Step { }

    /** A block that is gone. */
    private record Delete(long block) implements Step { }

    /**
     * Reads a file's difference from the document's last commit as a pending edit; a file that is
     * gone from its folder leaves the document as committed. A file read whole, as it is or as the
     * last commit, is remembered as what Komet last read ({@link DocumentFiles#remember}), so a
     * later save can tell a change someone else made since.
     *
     * @param anchor the document anchor's id
     * @param root   the working tree or folder that holds the file
     * @param path   the file's path in it
     * @param edit   the edit coordinate of the person Komet opens as
     * @return what reading came to
     */
    static Read readFile(PublicId anchor, java.nio.file.Path root, String path, EditCoordinate edit) {
        java.nio.file.Path file = root.resolve(path);
        if (!java.nio.file.Files.isRegularFile(file)) {
            return Read.of(Outcome.SAME);
        }
        try {
            List<String> lines = java.nio.file.Files.readAllLines(file);
            Read read = read(anchor, lines, edit);
            if (read.outcome() == Outcome.SAME || read.outcome() == Outcome.READ) {
                DocumentFiles.remember(anchor, root, path, lines);
            }
            return read;
        } catch (java.io.IOException unreadable) {
            return Read.unread("the file cannot be read: " + unreadable.getMessage());
        }
    }

    /**
     * Reads the file's difference from the document's last commit as a pending edit.
     *
     * @param anchor the document anchor's id
     * @param file   the file's lines as they are
     * @param edit   the edit coordinate of the person Komet opens as
     * @return what reading came to
     */
    static synchronized Read read(PublicId anchor, List<String> file, EditCoordinate edit) {
        if (DocumentEditor.pending(anchor).isPresent()) {
            return Read.of(Outcome.PENDING);
        }
        Optional<GitDocumentMaterializer.StoredDocument> loaded = GitDocumentMaterializer.load(anchor, false);
        if (loaded.isEmpty()) {
            return Read.unread("the knowledge base holds no text for it");
        }
        GitDocumentMaterializer.StoredDocument committed = loaded.get();
        List<String> head = committed.lines().stream().map(GitDocumentMaterializer.StoredLine::text).toList();
        if (head.equals(file)) {
            return Read.of(Outcome.SAME);
        }
        List<Block> oldBlocks = DocumentEditor.structural(IkeDialectParser.parse(head));
        List<GitDocumentMaterializer.StoredBlock> stored = new DocumentOutline(committed).nodes().stream()
                .map(DocumentOutline.Node::block).toList();
        if (oldBlocks.size() != stored.size() || !DocumentEditor.sameKinds(oldBlocks, stored)) {
            return Read.unread("its stored structure disagrees with its last commit");
        }
        List<Block> newBlocks = DocumentEditor.structural(IkeDialectParser.parse(file));
        Optional<List<Step>> planned = plan(oldBlocks, head, stored, newBlocks, file);
        if (planned.isEmpty()) {
            return Read.unread("a change touches a block that holds other blocks, such as a section's heading");
        }
        int edited = 0;
        int added = 0;
        int deleted = 0;
        try {
            List<Long> made = new ArrayList<>();
            List<Step> steps = planned.get();
            for (Step step : steps) {
                if (step instanceof Edit e) {
                    DocumentEditor.editBlock(anchor, e.block(), e.text(), edit);
                    edited++;
                }
            }
            for (Step step : steps) {
                if (step instanceof Add a) {
                    long after = a.afterStep() >= 0 ? made.get(a.afterStep()) : a.after();
                    made.add(DocumentEditor.addBlock(anchor, after, a.kind(), a.text(), edit).block());
                    added++;
                } else {
                    made.add(0L);
                }
            }
            for (Step step : steps) {
                if (step instanceof Delete d) {
                    DocumentEditor.deleteBlock(anchor, d.block(), edit);
                    deleted++;
                }
            }
        } catch (IllegalArgumentException | IllegalStateException refused) {
            withdraw(anchor);
            return Read.unread(refused.getMessage());
        }
        List<String> read = GitDocumentExporter.export(anchor, true).map(GitDocumentExporter.Exported::lines).orElse(List.of());
        if (!read.equals(file)) {
            withdraw(anchor);
            return Read.unread("the edit read from it does not give the file's text back line for line");
        }
        return new Read(Outcome.READ, edited, added, deleted, "");
    }

    /** Cancels what a reading left pending, without keeping it to restore: the file still holds it. */
    private static void withdraw(PublicId anchor) {
        DocumentEditor.pending(anchor).ifPresent(Transaction::cancel);
    }

    /**
     * The steps from the old blocks to the new, or empty when a change touches a block that holds
     * other blocks. The blocks are paired in order on their kind and text; between two pairs, the
     * old and new blocks left are paired by position while their kinds agree (an edit), and the
     * rest are added and deleted.
     */
    private static Optional<List<Step>> plan(List<Block> oldBlocks, List<String> head, List<GitDocumentMaterializer.StoredBlock> stored,
                                             List<Block> newBlocks, List<String> file) {
        List<String> oldKeys = oldBlocks.stream().map(b -> key(b, head)).toList();
        List<String> newKeys = newBlocks.stream().map(b -> key(b, file)).toList();
        boolean[][] matched = DocumentEditor.matchInOrder(oldKeys, newKeys);
        List<Step> steps = new ArrayList<>();
        long previous = 0;                                        // the stored block the next new block follows
        int previousStep = -1;                                    // or the step that adds it
        boolean previousLeaf = false;
        int i = 0;
        int j = 0;
        while (i < oldBlocks.size() || j < newBlocks.size()) {
            if (i < oldBlocks.size() && j < newBlocks.size() && matched[0][i] && matched[1][j]) {
                previous = stored.get(i).nid();
                previousStep = -1;
                previousLeaf = leaf(oldBlocks.get(i));
                i++;
                j++;
                continue;
            }
            int oldFrom = i;
            while (i < oldBlocks.size() && !matched[0][i]) {
                i++;
            }
            int newFrom = j;
            while (j < newBlocks.size() && !matched[1][j]) {
                j++;
            }
            for (int k = oldFrom; k < i; k++) {
                if (!leaf(oldBlocks.get(k))) {
                    return Optional.empty();
                }
            }
            for (int k = newFrom; k < j; k++) {
                if (!leaf(newBlocks.get(k))) {
                    return Optional.empty();
                }
            }
            int paired = Math.min(i - oldFrom, j - newFrom);
            for (int k = 0; k < j - newFrom; k++) {
                Block made = newBlocks.get(newFrom + k);
                String text = text(made, file);
                if (k < paired && oldBlocks.get(oldFrom + k).kind() == made.kind()) {
                    steps.add(new Edit(stored.get(oldFrom + k).nid(), text));
                    previous = stored.get(oldFrom + k).nid();
                    previousStep = -1;
                    previousLeaf = true;
                    continue;
                }
                if (!DocumentEditor.isLeafKind(made.kind()) || !previousLeaf || (previous == 0 && previousStep < 0)) {
                    return Optional.empty();                      // a block Komet cannot add there
                }
                if (k < paired) {
                    steps.add(new Delete(stored.get(oldFrom + k).nid()));
                }
                steps.add(new Add(previous, previousStep, made.kind(), text));
                previousStep = steps.size() - 1;
                previousLeaf = true;
            }
            for (int k = oldFrom + paired; k < i; k++) {
                steps.add(new Delete(stored.get(k).nid()));
            }
        }
        return Optional.of(steps);
    }

    /** Whether a block is edited as its text: it holds no other blocks, or it is a structured open block. */
    private static boolean leaf(Block block) {
        return block.children().isEmpty() || DocumentEditor.structuredOpen(block.kind(), block.parameters());
    }

    /** A block's kind, level and own text, which pair it with the same block in the other text. */
    private static String key(Block block, List<String> lines) {
        return block.kind() + ":" + block.level() + "\u0000" + text(block, lines);
    }

    /**
     * The text a block is edited as: a leaf's lines, a structured open block's with those of the
     * blocks inside it, and for a block that holds others, the lines it owns itself.
     */
    private static String text(Block block, List<String> lines) {
        List<String> own = new ArrayList<>();
        if (DocumentEditor.structuredOpen(block.kind(), block.parameters())) {
            for (int line = block.firstLine(); line <= block.lastLine(); line++) {
                own.add(lines.get(line - 1));
            }
        } else {
            for (int line : block.lines()) {
                own.add(lines.get(line - 1));
            }
        }
        return String.join("\n", own);
    }
}

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

import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.boundary.DataKind;
import dev.ikm.tinkar.entity.boundary.EntityBoundaries;
import dev.ikm.tinkar.entity.scope.Scope;
import dev.ikm.tinkar.entity.scope.ScopeOperations;
import network.ike.foundation.ike.bindings.IkePattern;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A stored document's scope (IKE-Network/ike-issues#1313): everything that document is in the
 * store, which a scope operation exports, imports or deletes whole. A document stored as vertices
 * is rooted at its own pattern; a document stored as prose elements at its anchor concept, whose
 * lines, blocks, manifest, references, git source, annotations and names all cite it.
 */
public final class DocumentScopes {

    private DocumentScopes() {
    }

    /**
     * A document's scope.
     *
     * @param anchor the document's anchor: a vertex document's root vertex, or a prose-element document's anchor concept
     * @return its scope
     */
    public static Scope of(PublicId anchor) {
        return Scope.of(rootNid(anchor));
    }

    /**
     * The root a document's scope is resolved from: a vertex document's own pattern, or a
     * prose-element document's anchor concept.
     */
    static long rootNid(PublicId anchor) {
        long anchorNid = EntityService.get().nidForPublicId(anchor);
        if (VertexReader.isVertexDocument(anchor)) {
            return ((SemanticEntity<?>) EntityHandle.get(anchorNid).expectEntity()).patternNid();
        }
        return anchorNid;
    }

    /**
     * Why documents were not removed from the knowledge base, in words for the person who asked.
     * Nothing was removed.
     */
    public static final class RemovalRefused extends IllegalStateException {
        RemovalRefused(String reason) {
            super(reason);
        }
    }

    /**
     * What removing documents from the knowledge base did.
     *
     * @param documents  the documents removed
     * @param components the entities erased
     * @param stamps     the STAMPs collected
     */
    public record Removed(int documents, int components, int stamps) { }

    /**
     * Removes documents read in from git from the knowledge base in one step
     * (IKE-Network/ike-issues#1313): each with its annotations and narrations. They are resolved
     * as one scope, so references among them, such as an assembly's includes, do not stop the
     * removal. Their files and commits stay in git, and materializing a file again brings its
     * document back under the same identities, with its annotations from the commits' trailers.
     * Nothing is left for the person to commit.
     *
     * <p>Refused before anything is erased: no document named, a document the knowledge base does
     * not hold, a document with an edit pending in Komet, a document that is not from git, and a
     * reference into them from outside.
     *
     * @param anchors the documents' anchors
     * @return what was removed
     * @throws RemovalRefused why nothing was removed, in words for the person
     */
    public static Removed remove(Collection<PublicId> anchors) {
        Set<PublicId> distinct = new LinkedHashSet<>(anchors);
        if (distinct.isEmpty()) {
            throw new RemovalRefused("No document was named to remove.");
        }
        long[] roots = new long[distinct.size()];
        int at = 0;
        for (PublicId anchor : distinct) {
            if (!PrimitiveData.get().hasPublicId(anchor)) {
                throw new RemovalRefused(name(anchor) + " is not in the knowledge base.");
            }
            if (DocumentEditor.pending(anchor).isPresent()) {
                throw new RemovalRefused("An edit of " + name(anchor) + " is not yet saved to git. Commit it in git, or discard"
                        + " the edit, before removing the document.");
            }
            long root = rootNid(anchor);
            if (EntityBoundaries.kindOf(root) != DataKind.FILE) {
                throw new RemovalRefused(SharedDocuments.shared(anchor)
                        ? name(anchor) + " is shared. Delete Shared Document removes it for everyone who syncs this knowledge base, and Make Local"
                                + " removes it for everyone else."
                        : name(anchor) + " is not from git, so it is not removed this way.");
            }
            roots[at++] = root;
        }
        String them = distinct.size() == 1 ? name(distinct.iterator().next()) : "these " + distinct.size() + " documents";
        Scope scope;
        try {
            scope = Scope.of(roots);
        } catch (IllegalArgumentException mixed) {
            throw new RemovalRefused("Not removed: " + mixed.getMessage());
        }
        if (!scope.deletable()) {
            throw new RemovalRefused("Something outside " + them + " refers to " + (distinct.size() == 1 ? "it" : "them")
                    + ", so nothing was removed. The first is " + referrer(scope.incoming().getFirst()) + ".");
        }
        ScopeOperations.Deleted done;
        try {
            done = ScopeOperations.delete(scope);
        } catch (IllegalStateException into) {
            String said = into.getMessage() == null ? "" : into.getMessage();
            if (!said.startsWith("The scope cannot be deleted")) {
                throw into;
            }
            int first = said.indexOf("first: ");
            throw new RemovalRefused("Something outside " + them + " refers to " + (distinct.size() == 1 ? "it" : "them")
                    + ", so nothing was removed." + (first < 0 ? "" : " The first is " + referrer(said.substring(first + 7)) + "."));
        } catch (UnsupportedOperationException cannot) {
            throw new RemovalRefused("This knowledge base cannot remove data: " + cannot.getMessage());
        }
        return new Removed(distinct.size(), done.components(), done.stamps());
    }

    /**
     * The stored documents of a working tree under one of its folders, at any depth: each file of
     * the folder that the knowledge base holds, read under the identity the file materializes as,
     * including files since deleted from the working tree.
     *
     * @param repoRoot the working-tree root
     * @param folder   a folder in it, or the root itself
     * @return the anchors of the stored documents under it, in path order
     */
    public static List<PublicId> storedUnder(Path repoRoot, Path folder) {
        Path root = repoRoot.toAbsolutePath().normalize();
        String prefix = root.relativize(folder.toAbsolutePath().normalize()).toString().replace('\\', '/');
        List<PublicId> found = new ArrayList<>();
        for (Map.Entry<String, List<PublicId>> stored : GitDocumentMaterializer.storedDocumentsByPath().entrySet()) {
            String path = stored.getKey();
            if (prefix.isEmpty() || path.equals(prefix) || path.startsWith(prefix + "/")) {
                PublicId anchor = GitDocumentMaterializer.documentAnchor(root, path);
                if (stored.getValue().contains(anchor)) {
                    found.add(anchor);
                }
            }
        }
        return found;
    }

    private static final java.util.regex.Pattern UUID_TEXT =
            java.util.regex.Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    /**
     * The component that refers into removed documents, in words for the person: what it is, who
     * wrote it, when, and in which module, read from the first component a scope's own words name.
     * Those words come back unchanged when the component cannot be read.
     *
     * @param inWords a reference into a scope, as the scope describes it
     * @return the referring component in words
     */
    static String referrer(String inWords) {
        java.util.regex.Matcher uuid = UUID_TEXT.matcher(inWords);
        if (!uuid.find()) {
            return inWords;
        }
        try {
            PublicId id = dev.ikm.tinkar.common.id.PublicIds.of(java.util.UUID.fromString(uuid.group()));
            if (!PrimitiveData.get().hasPublicId(id)) {
                return inWords;
            }
            dev.ikm.tinkar.entity.Entity<?> entity = EntityHandle.get(id).expectEntity();
            dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator view = dev.ikm.tinkar.coordinate.Calculators.View.Default();
            String what = switch (entity) {
                case SemanticEntity<?> semantic -> "a semantic of the " + view.getDescriptionTextOrNid(semantic.patternNid());
                case dev.ikm.tinkar.entity.PatternEntity<?> _ -> "the pattern " + view.getDescriptionTextOrNid(entity.nid());
                default -> "the concept " + view.getDescriptionTextOrNid(entity.nid());
            };
            dev.ikm.tinkar.entity.StampEntity<?> written = entity.versions().stream()
                    .map(version -> (dev.ikm.tinkar.entity.StampEntity<?>) version.stamp())
                    .min(java.util.Comparator.comparingLong(dev.ikm.tinkar.entity.StampEntity::time)).orElseThrow();
            String when = written.time() == Long.MAX_VALUE ? "not yet committed"
                    : "on " + java.time.Instant.ofEpochMilli(written.time()).toString().substring(0, 10);
            return what + ", written by " + view.getDescriptionTextOrNid(written.authorNid()) + " " + when
                    + " in the module " + view.getDescriptionTextOrNid(written.moduleNid());
        } catch (RuntimeException unread) {
            return inWords;
        }
    }

    /** A document in words for a message: its recorded path, or its id when the store records none. */
    static String name(PublicId anchor) {
        try {
            return GitDocumentMaterializer.recordedPath(anchor).orElse(anchor.idString());
        } catch (RuntimeException unread) {
            return anchor.idString();
        }
    }

    /**
     * Deletes a document's scope: every entity of it, and the STAMPs only it is written under. A
     * document in git comes back by materializing its file again.
     *
     * @param anchor the document's anchor
     * @return what was erased
     * @throws IllegalStateException when something outside the document cites into it, or an edit of it is pending in Komet
     */
    public static ScopeOperations.Deleted delete(PublicId anchor) {
        if (DocumentEditor.pending(anchor).isPresent()) {
            throw new IllegalStateException("An edit of the document is pending in Komet; commit or cancel it first");
        }
        return ScopeOperations.delete(of(anchor));
    }

    /**
     * Drops a document from the store so it can be materialized again from git
     * (IKE-Network/ike-issues#1313): allowed only for local data saved in a file, whose record is
     * the repository, never for other local data, whose only record is the knowledge base.
     *
     * @param anchor the document's anchor
     * @return what was erased
     * @throws IllegalStateException when the document is not saved in a file, an edit of it is pending in
     *                               Komet, or something outside it cites into it
     */
    public static ScopeOperations.Deleted dropForRebuild(PublicId anchor) {
        Scope scope = of(anchor);
        if (scope.kind() != dev.ikm.tinkar.entity.boundary.DataKind.FILE) {
            throw new IllegalStateException(scope.kind() == dev.ikm.tinkar.entity.boundary.DataKind.SHARED
                    ? "The document is shared, and it holds what others said of it, which git does not; it follows its file without being rebuilt"
                    : "The document is not saved in a file: git is not its record, so it is not rebuilt from git");
        }
        return delete(anchor);
    }

    /**
     * Rebuilds a document from git: drops it, then materializes its file again. Every identity is a
     * UUID5 of the working tree, the path and the commit, so the document comes back under the same
     * public ids, with its annotations from the commits' trailers.
     *
     * @param anchor   the document's anchor
     * @param repoRoot the working tree holding its file
     * @param file     the file
     * @return what the materialization wrote
     * @throws java.io.IOException when the repository cannot be read
     */
    public static GitDocumentMaterializer.Result rebuild(PublicId anchor, java.nio.file.Path repoRoot, java.nio.file.Path file)
            throws java.io.IOException {
        dropForRebuild(anchor);
        return GitDocumentMaterializer.materialize(repoRoot, file, false);
    }

    /**
     * What dropping the copies of documents saved in files did when a knowledge base opened.
     *
     * @param dropped the documents dropped
     * @param unsaved the documents kept because they hold edits their files do not
     * @param left    the documents left for another reason, each with why
     */
    public record Dropped(int dropped, List<PublicId> unsaved, List<String> left) { }

    /** The documents a knowledge base kept when it opened because they hold unsaved edits, for a card to ask about once. */
    private static final Set<PublicId> KEPT_UNSAVED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Drops the copies of documents saved in files, as a knowledge base opens
     * (IKE-Network/ike-issues#1313; step 2 of {@code design-2026-11-22-knowledge-shared-local},
     * settled by KEC on 2026-11-22). A document saved in a file is kept in the knowledge base only
     * while it is open; its file is its record, and opening it reads it in again. A document with
     * an edit its file does not hold, which a crash leaves, is kept, and the card that next shows
     * it asks whether to save the edit or discard it ({@link #takeUnsaved}). A document whose file
     * holds its edit is dropped with the edit, which reading the file brings back. The documents
     * are dropped together, so references among them do not stop it; when something outside them
     * refers to one, each is dropped on its own where that is safe, and the rest are left.
     *
     * @return what was dropped and what was kept
     */
    public static Dropped dropCopies() {
        if (GitStampVocabulary.entityAbsent(IkePattern.GIT_SOURCE_PATTERN)) {
            return new Dropped(0, List.of(), List.of());
        }
        Set<PublicId> stored = new LinkedHashSet<>();
        GitDocumentMaterializer.storedDocumentsByPath().values().forEach(stored::addAll);
        stored.addAll(GitDocumentMaterializer.superseded());
        List<PublicId> candidates = new ArrayList<>();
        List<PublicId> unsaved = new ArrayList<>();
        for (PublicId anchor : stored) {
            if (EntityBoundaries.kindOf(rootNid(anchor)) != DataKind.FILE) {
                continue;
            }
            if (DocumentEditor.pending(anchor).isPresent()) {
                if (DocumentFiles.unsaved(anchor)) {
                    unsaved.add(anchor);
                    continue;
                }
                // The file holds the edit: reading the file brings it back.
                DocumentEditor.pending(anchor).ifPresent(dev.ikm.tinkar.entity.transaction.Transaction::cancel);
            }
            candidates.add(anchor);
        }
        KEPT_UNSAVED.addAll(unsaved);
        if (candidates.isEmpty()) {
            return new Dropped(0, List.copyOf(unsaved), List.of());
        }
        try {
            remove(candidates);
            return new Dropped(candidates.size(), List.copyOf(unsaved), List.of());
        } catch (IllegalStateException | IllegalArgumentException | UnsupportedOperationException together) {
            // Something outside them refers to one: drop each that is safe on its own, until none is.
        }
        int dropped = 0;
        List<PublicId> remaining = new ArrayList<>(candidates);
        Map<PublicId, String> why = new java.util.LinkedHashMap<>();
        boolean progress = true;
        while (progress && !remaining.isEmpty()) {
            progress = false;
            for (java.util.Iterator<PublicId> it = remaining.iterator(); it.hasNext(); ) {
                PublicId anchor = it.next();
                try {
                    remove(List.of(anchor));
                    dropped++;
                    it.remove();
                    progress = true;
                } catch (IllegalStateException | IllegalArgumentException | UnsupportedOperationException refused) {
                    why.put(anchor, refused.getMessage());
                }
            }
        }
        List<String> left = new ArrayList<>();
        for (PublicId anchor : remaining) {
            left.add(name(anchor) + ": " + why.get(anchor));
        }
        return new Dropped(dropped, List.copyOf(unsaved), List.copyOf(left));
    }

    /**
     * Whether a document was kept with unsaved edits when the knowledge base opened, and not yet
     * asked about: true once, so the question is asked once.
     *
     * @param anchor the document anchor's id
     * @return whether to ask about it now
     */
    public static boolean takeUnsaved(PublicId anchor) {
        return KEPT_UNSAVED.remove(anchor);
    }

    /** @return the documents kept with unsaved edits and not yet asked about */
    public static List<PublicId> keptUnsaved() {
        return List.copyOf(KEPT_UNSAVED);
    }

    /**
     * What deleting the superseded documents did.
     *
     * @param deleted    the documents deleted
     * @param components the entities erased
     * @param stamps     the STAMPs collected
     * @param skipped    the documents left, each with why
     */
    public record Cleared(int deleted, int components, int stamps, List<String> skipped) { }

    /**
     * Deletes the prose-element documents that the vertex documents of the same working tree and
     * path supersede (IKE-Network/ike-issues#1313), which the document lists already leave out.
     * They are deleted together, as one scope, so the references between them, such as the
     * 2026-10-11 line-identity collision left, are inside it; when something outside them still
     * references one, each is deleted on its own wherever that is safe, and the rest are left. A
     * document with an edit pending in Komet is left, and so is one that still classifies as
     * knowledge: its working tree's module is stated a kind of File module when a file of
     * that tree is next materialized.
     *
     * @return what was deleted and what was left
     */
    public static Cleared deleteSuperseded() {
        List<String> skipped = new ArrayList<>();
        List<PublicId> candidates = new ArrayList<>();
        for (PublicId anchor : GitDocumentMaterializer.superseded()) {
            if (DocumentEditor.pending(anchor).isPresent()) {
                skipped.add(anchor.idString() + ": an edit of it is pending in Komet");
            } else if (dev.ikm.tinkar.entity.boundary.EntityBoundaries.kindOf(EntityService.get().nidForPublicId(anchor))
                    == dev.ikm.tinkar.entity.boundary.DataKind.KNOWLEDGE) {
                skipped.add(anchor.idString() + ": it classifies as knowledge until a file of its working tree is materialized again");
            } else {
                candidates.add(anchor);
            }
        }
        if (candidates.isEmpty()) {
            return new Cleared(0, 0, 0, List.copyOf(skipped));
        }
        try {
            long[] roots = candidates.stream().mapToLong(a -> EntityService.get().nidForPublicId(a)).toArray();
            ScopeOperations.Deleted done = ScopeOperations.delete(Scope.of(roots));
            return new Cleared(candidates.size(), done.components(), done.stamps(), List.copyOf(skipped));
        } catch (IllegalStateException together) {
            // Something outside them references one: delete each that is safe on its own, until none is.
        }
        int deleted = 0;
        int components = 0;
        int stamps = 0;
        List<PublicId> remaining = new ArrayList<>(candidates);
        java.util.Map<PublicId, String> why = new java.util.LinkedHashMap<>();
        boolean progress = true;
        while (progress && !remaining.isEmpty()) {
            progress = false;
            for (java.util.Iterator<PublicId> it = remaining.iterator(); it.hasNext(); ) {
                PublicId anchor = it.next();
                try {
                    ScopeOperations.Deleted done = delete(anchor);
                    deleted++;
                    components += done.components();
                    stamps += done.stamps();
                    it.remove();
                    progress = true;
                } catch (IllegalStateException | IllegalArgumentException | UnsupportedOperationException left) {
                    why.put(anchor, left.getMessage());
                }
            }
        }
        for (PublicId anchor : remaining) {
            skipped.add(anchor.idString() + ": " + why.get(anchor));
        }
        return new Cleared(deleted, components, stamps, List.copyOf(skipped));
    }
}

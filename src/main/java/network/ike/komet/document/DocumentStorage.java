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

import dev.ikm.komet.kview.storage.StorageGestures;
import dev.ikm.komet.layout.storage.KlStorageOffer;
import dev.ikm.komet.layout.storage.KlStorageProvider;
import dev.ikm.komet.layout.storage.KlStoredGroup;
import dev.ikm.komet.terms.KometConcept;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.PatternEntity;
import dev.ikm.tinkar.entity.boundary.DataKind;
import dev.ikm.tinkar.entity.scope.ScopeOperations;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.EntityFacade;
import javafx.application.Platform;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The documents the Storage window lists (IKE-Network/ike-issues#1313; step 3 of
 * {@code plan-2026-11-22-remove-export-import-data}): each with where its file is, and with the
 * gestures that its kind of data allows, the ones the document card and the Documents tray have.
 */
public final class DocumentStorage implements KlStorageProvider {

    /** Creates the provider; called by the service loader. */
    public DocumentStorage() {
    }

    @Override
    public String sourceName() {
        return "Documents";
    }

    @Override
    public List<KlStoredGroup> groups() {
        List<KlStoredGroup> groups = new ArrayList<>();
        GitDocumentMaterializer.storedDocumentsByPath().forEach((path, anchors) -> anchors.forEach(anchor ->
                groups.add(group(anchor, path, false))));
        for (PublicId anchor : GitDocumentMaterializer.superseded()) {
            groups.add(group(anchor, DocumentScopes.name(anchor), true));
        }
        return groups;
    }

    private KlStoredGroup group(PublicId anchor, String path, boolean superseded) {
        DataKind kind = SharedDocuments.kind(anchor);
        Optional<Path> folder = DocumentFiles.rootOf(anchor);
        String home = System.getProperty("user.home");
        String from = folder.map(Path::toString).map(in -> in.startsWith(home) ? "~" + in.substring(home.length()) : in)
                .orElse("Its file is not on this computer");
        if (superseded) {
            from += " · an older copy";
        }
        // A document is listed in the Documents tray by its file, and shared or kept ones in the card's chooser.
        boolean shown = !superseded && (folder.isPresent() || kind != DataKind.FILE);
        List<KlStorageOffer> offers = new ArrayList<>();
        switch (kind) {
            case FILE -> {
                if (!superseded && folder.isPresent()) {
                    offers.add(sharing(anchor, DocumentSharing.Gesture.SHARE, "storageShare"));
                }
                offers.add(new KlStorageOffer("storageRemove", "Remove", "Remove this document from the knowledge base. Its file stays where it is, and"
                        + " opening it reads the document back in",
                        (node, edit, done) -> DocumentRemoval.perform(node, List.of(anchor), Calculators.View.Default(), edit, _ -> done.run())));
            }
            case SHARED -> {
                offers.add(sharing(anchor, DocumentSharing.Gesture.MAKE_LOCAL, "storageMakeLocal"));
                offers.add(sharing(anchor, DocumentSharing.Gesture.DELETE, "storageDelete"));
            }
            case LOCAL -> {
                offers.add(new KlStorageOffer("storageExport", "Export…", "Write this document to a file that another knowledge base can import",
                        (node, edit, done) -> StorageGestures.export(node, anchor, path, edit, done)));
                offers.add(sharing(anchor, DocumentSharing.Gesture.DELETE, "storageDelete"));
            }
            case KNOWLEDGE -> { }
        }
        return new KlStoredGroup(this, PrimitiveData.publicId(DocumentScopes.rootNid(anchor)), kind, path, from, shown, offers);
    }

    /**
     * Offers one of the actions on documents, for the assistant to show the person: the gesture they
     * would make themselves, with the question it asks.
     *
     * @param action  the action: Remove from knowledge base, Share document, Make document local or Delete document
     * @param anchors the documents' anchors
     * @return the offer
     * @throws IllegalArgumentException why it is not offered for one of them
     */
    public static KlStorageOffer offer(ConceptFacade action, List<PublicId> anchors) {
        String them = anchors.size() == 1 ? DocumentScopes.name(anchors.getFirst()) : anchors.size() + " documents";
        for (DocumentSharing.Gesture gesture : DocumentSharing.Gesture.values()) {
            if (EntityFacade.same(gesture.action, action)) {
                for (PublicId anchor : anchors) {
                    if (!gesture.offered(anchor)) {
                        throw new IllegalArgumentException(DocumentScopes.name(anchor) + switch (gesture) {
                            case SHARE -> " is not a document saved in a file here that is not shared already";
                            case MAKE_LOCAL -> " is not shared";
                            case DELETE -> " is neither shared nor kept in this knowledge base alone";
                        });
                    }
                }
                return new KlStorageOffer("assistantOffer" + gesture.name(), switch (gesture) {
                    case SHARE -> "Share " + them + "…";
                    case MAKE_LOCAL -> "Make " + them + " local…";
                    case DELETE -> "Delete " + them + "…";
                }, gesture.tooltip, (node, edit, done) -> DocumentSharing.perform(node, gesture, anchors, edit, _ -> done.run()));
            }
        }
        if (!EntityFacade.same(action, KometConcept.REMOVE_FROM_KNOWLEDGE_BASE)) {
            throw new IllegalArgumentException(action.description() + " is not an action on documents");
        }
        for (PublicId anchor : anchors) {
            if (SharedDocuments.kind(anchor) != DataKind.FILE) {
                throw new IllegalArgumentException(DocumentScopes.name(anchor) + " is not a document saved in a file");
            }
        }
        return new KlStorageOffer("assistantOfferREMOVE", "Remove " + them + " from the knowledge base", "Its file stays where it is, and opening it reads the document back in",
                (node, edit, done) -> DocumentRemoval.perform(node, anchors, Calculators.View.Default(), edit, _ -> done.run()));
    }

    private static KlStorageOffer sharing(PublicId anchor, DocumentSharing.Gesture gesture, String id) {
        return new KlStorageOffer(id, gesture == DocumentSharing.Gesture.DELETE ? "Delete…" : gesture.title, gesture.tooltip,
                (node, edit, done) -> DocumentSharing.perform(node, gesture, List.of(anchor), edit, _ -> done.run()));
    }

    /** A document kept in one knowledge base alone and exported from there: told by its recorded path. */
    @Override
    public Optional<String> imported(ScopeOperations.Imported imported, Map<String, byte[]> entries) {
        for (PublicId root : imported.roots()) {
            if (!(EntityHandle.get(PrimitiveData.nid(root)).entity().orElse(null) instanceof PatternEntity<?> pattern)) {
                continue;
            }
            List<String> paths = new ArrayList<>();
            EntityService.get().forEachSemanticOfPattern(pattern.nid(), element -> {
                if (element.referencedComponentNid() == pattern.nid()) {
                    GitDocumentMaterializer.recordedPath(element.publicId()).ifPresent(paths::add);
                }
            });
            if (!paths.isEmpty()) {
                Platform.runLater(StampedDocumentCard::sharedDataChanged);
                return Optional.of("the document " + paths.getFirst());
            }
        }
        return Optional.empty();
    }
}

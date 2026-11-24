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

import dev.ikm.komet.actions.KometAction;
import dev.ikm.komet.actions.KometActionInput;
import dev.ikm.komet.actions.KometActionResult;
import dev.ikm.komet.terms.KometConcept;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.coordinate.edit.EditCoordinate;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.terms.ConceptFacade;

import java.util.List;
import java.util.Optional;

/**
 * The {@code Remove from knowledge base} action: removes documents saved in files from the
 * knowledge base in one step, each with its annotations and narrations, through
 * {@link DocumentScopes#remove}. The document card's menu, the Documents tray and the Storage window
 * remove documents the same way, and the assistant offers to (IKE-Network/ike-issues#1313;
 * ike-lab-documents topic {@code dev-komet-actions}).
 *
 * <p>The input is a {@link DocumentGroupInput}. The result takes the {@code Version change result
 * pattern}; nothing is written, so its version is the outcome concept, as a refusal's is. The files
 * stay where they are, and opening a file again reads its document back in under the same identities.
 *
 * <p>Refused, with nothing removed: an edit of one of the documents pending in Komet, a document
 * that is not saved in a file, and something outside the documents that refers to one of them.
 */
public final class RemoveFromKnowledgeBase implements KometAction {

    /** For the service loader. */
    public RemoveFromKnowledgeBase() { }

    @Override
    public ConceptFacade concept() {
        return KometConcept.REMOVE_FROM_KNOWLEDGE_BASE;
    }

    /**
     * @param anchors the anchors of the documents to remove together; the first is the subject
     * @return a new input asking for their removal
     * @throws IllegalArgumentException when no document is named
     */
    public static KometActionInput input(List<PublicId> anchors) {
        return DocumentGroupInput.of(KometConcept.REMOVE_FROM_KNOWLEDGE_BASE, anchors);
    }

    @Override
    public KometActionResult perform(KometActionInput input, ViewCalculator view, EditCoordinate edit) {
        List<PublicId> anchors;
        try {
            anchors = DocumentGroupInput.anchors(input, concept());
        } catch (DocumentGroupInput.Unreadable unreadable) {
            return KometActionResult.refused(input, unreadable.getMessage());
        }
        DocumentScopes.Removed removed;
        try {
            removed = DocumentScopes.remove(anchors);
        } catch (DocumentScopes.RemovalRefused refused) {
            return KometActionResult.refused(input, refused.getMessage());
        } catch (RuntimeException e) {
            return KometActionResult.failed(input, "Could not remove " + (anchors.size() == 1 ? "the document" : "the documents")
                    + ": " + e.getMessage());
        }
        return new KometActionResult(input.id(), KometConcept.ACTION_COMPLETED, Optional.empty(), message(removed));
    }

    /** What a removal tells the person. */
    static String message(DocumentScopes.Removed removed) {
        return removed.documents() == 1
                ? "Removed 1 document from the knowledge base. Its file stays in git, and opening it reads it back in."
                : "Removed " + removed.documents() + " documents from the knowledge base. Their files stay in git, and"
                        + " opening one reads it back in.";
    }
}

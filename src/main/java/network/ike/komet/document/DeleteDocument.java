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

/**
 * The {@code Delete document} action: deletes shared documents for everyone who syncs the knowledge
 * base, or documents kept in this knowledge base alone, through {@link SharedDocuments#delete}. The
 * input is a {@link DocumentGroupInput}. A person performs it and the assistant offers it
 * (IKE-Network/ike-issues#1313; step 4 of {@code design-2026-11-22-knowledge-shared-local}).
 */
public final class DeleteDocument implements KometAction {

    /** For the service loader. */
    public DeleteDocument() { }

    @Override
    public ConceptFacade concept() {
        return KometConcept.DELETE_DOCUMENT;
    }

    /**
     * @param anchors the documents' anchors
     * @return a new input asking for it
     * @throws IllegalArgumentException when no document is named
     */
    public static KometActionInput input(List<PublicId> anchors) {
        return DocumentGroupInput.of(KometConcept.DELETE_DOCUMENT, anchors);
    }

    @Override
    public KometActionResult perform(KometActionInput input, ViewCalculator view, EditCoordinate edit) {
        return DocumentSharing.run(input, concept(), anchors -> SharedDocuments.delete(anchors, DocumentSharing.name(edit)));
    }
}

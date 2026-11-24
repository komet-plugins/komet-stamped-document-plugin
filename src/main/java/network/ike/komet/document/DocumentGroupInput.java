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

import dev.ikm.komet.actions.KometActionInput;
import dev.ikm.tinkar.common.id.LongIdCollection;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIdCollection;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.EntityFacade;
import dev.ikm.tinkar.terms.EntityProxy;
import org.eclipse.collections.api.factory.Lists;

import java.util.ArrayList;
import java.util.List;

/**
 * The input of the actions on whole documents, which take the {@code Data group input pattern}: the
 * subject is one document's anchor, and the one field, {@code Acted on together}, the other documents
 * acted on in the same step, empty for a single document (IKE-Network/ike-issues#1313).
 */
final class DocumentGroupInput {

    private DocumentGroupInput() {
    }

    /**
     * @param action  the action asked for
     * @param anchors the anchors of the documents to act on together; the first is the subject
     * @return a new input
     * @throws IllegalArgumentException when no document is named
     */
    static KometActionInput of(ConceptFacade action, List<PublicId> anchors) {
        if (anchors.isEmpty()) {
            throw new IllegalArgumentException("No document was named");
        }
        return new KometActionInput(PublicIds.newRandom(), action, EntityProxy.make("document", anchors.getFirst()),
                Lists.immutable.of(PublicIds.set.of(anchors.subList(1, anchors.size()))));
    }

    /**
     * The documents an input names.
     *
     * @param input  the input
     * @param action the action performing it
     * @return the documents' anchors, the subject first
     * @throws Unreadable why the input cannot be performed
     */
    static List<PublicId> anchors(KometActionInput input, ConceptFacade action) {
        if (!EntityFacade.same(input.action(), action)) {
            throw new Unreadable("This input asks for " + input.action().description() + ", not " + action.description() + ".");
        }
        if (input.fields().size() > 1) {
            throw new Unreadable("This action takes one field, the documents acted on together with the subject.");
        }
        List<PublicId> anchors = new ArrayList<>();
        anchors.add(input.subject().publicId());
        if (!input.fields().isEmpty()) {
            add(input.fields().getFirst(), anchors);
        }
        return anchors;
    }

    /** Why an input cannot be performed. */
    static final class Unreadable extends IllegalArgumentException {
        Unreadable(String reason) {
            super(reason);
        }
    }

    /** The documents an {@code Acted on together} value names: public ids, components, or the nids a stored semantic holds. */
    private static void add(Object value, List<PublicId> anchors) {
        switch (value) {
            case PublicIdCollection<?> ids -> ids.forEach(anchors::add);
            case LongIdCollection nids -> {
                for (long nid : nids.toArray()) {
                    anchors.add(PrimitiveData.publicId(nid));
                }
            }
            case Iterable<?> items -> {
                for (Object item : items) {
                    switch (item) {
                        case PublicId id -> anchors.add(id);
                        case EntityFacade facade -> anchors.add(facade.publicId());
                        default -> throw new Unreadable("Acted on together is not a set of documents.");
                    }
                }
            }
            default -> throw new Unreadable("Acted on together is not a set of documents.");
        }
    }
}

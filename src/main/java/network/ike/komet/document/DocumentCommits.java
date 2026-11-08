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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Tells whoever listens that a person committed a stamped document to its git repository through Komet, once
 * the commit is in the store (IKE-Network/ike-issues#1355). A rebuild of a document from git is not a
 * commit and is never announced, so what listens, such as the commit narrator, responds to what a person
 * did and never to a rebuild. The stamped document ships without the assistant, so it names no listener;
 * a listener registers itself.
 */
public final class DocumentCommits {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentCommits.class);

    private static final List<Consumer<Committed>> LISTENERS = new CopyOnWriteArrayList<>();

    private DocumentCommits() {
    }

    /**
     * A document a person committed.
     *
     * @param root the repository's working tree
     * @param file the document, relative to it, as git names it
     * @param sha  the commit
     */
    public record Committed(Path root, String file, String sha) {
    }

    /**
     * Listens for document commits.
     *
     * @param listener told of each, on the thread that committed
     */
    public static void listen(Consumer<Committed> listener) {
        LISTENERS.add(listener);
    }

    /**
     * Stops listening.
     *
     * @param listener as given to {@link #listen}
     */
    public static void stopListening(Consumer<Committed> listener) {
        LISTENERS.remove(listener);
    }

    /**
     * Announces a document commit, once it is in the store. A listener that fails is logged and the rest
     * are still told.
     *
     * @param committed the commit
     */
    static void committed(Committed committed) {
        for (Consumer<Committed> listener : LISTENERS) {
            try {
                listener.accept(committed);
            } catch (RuntimeException e) {
                LOG.warn("A listener failed on the commit of {} at {}", committed.file(), committed.sha(), e);
            }
        }
    }
}

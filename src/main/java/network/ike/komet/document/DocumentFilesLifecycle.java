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

import dev.ikm.tinkar.common.service.LifecyclePhase;
import dev.ikm.tinkar.common.service.ServiceLifecycle;
import dev.ikm.tinkar.common.service.ServiceLifecyclePhase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps documents saved in files out of the knowledge base between sessions
 * (IKE-Network/ike-issues#1313; step 2 of {@code design-2026-11-22-knowledge-shared-local}, settled
 * by KEC on 2026-11-22). As a knowledge base opens, the narrations written before 2026-11-08 as
 * knowledge about documents are moved into the documents' repositories ({@link OldNarrations}),
 * and the copies of documents saved in files are dropped ({@link DocumentScopes#dropCopies}),
 * except those holding edits their files do not; as
 * Komet quits, every edited document is saved to its file ({@link DocumentFiles#saveAll}), in
 * {@link #beforeShutdown()}, while the store still answers: by the time a service shuts down, the
 * store is no longer available to it.
 *
 * <p>Neither step is essential to Komet running, so a failure is logged and Komet goes on: a copy
 * not dropped is read again from its file when it is next opened, and an edit not saved is kept.
 */
@LifecyclePhase(value = ServiceLifecyclePhase.APPLICATION_SERVICES, subPriority = 40)
public class DocumentFilesLifecycle implements ServiceLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentFilesLifecycle.class);

    /** Creates the service; called by the service loader. */
    public DocumentFilesLifecycle() {
    }

    @Override
    public void startup() {
        try {
            // Old narrations are knowledge that refers to documents, and would keep them from being dropped.
            OldNarrations.Moved moved = OldNarrations.move();
            if (moved.moved() > 0 || moved.reported() > 0) {
                LOG.info("Moved {} narration(s) written before 2026-11-08 into their documents' repositories;"
                        + " {} whose document could not be found are in {}", moved.moved(), moved.reported(), moved.report());
            }
            for (String left : moved.left()) {
                LOG.warn("A narration written before 2026-11-08 was not moved: {}", left);
            }
        } catch (RuntimeException e) {
            LOG.error("Could not move the narrations written before 2026-11-08 into their documents' repositories", e);
        }
        try {
            long began = System.nanoTime();
            DocumentScopes.Dropped dropped = DocumentScopes.dropCopies();
            if (dropped.dropped() > 0 || !dropped.unsaved().isEmpty() || !dropped.left().isEmpty()) {
                LOG.info("Dropped the copies of {} document(s) saved in files as the knowledge base opened, in {} ms;"
                                + " {} kept with unsaved edits", dropped.dropped(), (System.nanoTime() - began) / 1_000_000,
                        dropped.unsaved().size());
            }
            for (String left : dropped.left()) {
                LOG.info("Kept the copy of {}", left);
            }
        } catch (RuntimeException e) {
            LOG.error("Could not drop the copies of documents saved in files; each is read again from its file when opened", e);
        }
    }

    @Override
    public void shutdown() {
        // Saving happens before the store saves, while it still answers: see beforeShutdown.
    }

    /** The shared copies follow their files before a sync sends them (step 4 of the same design). */
    @Override
    public void beforeSync() {
        SharedDocuments.bringUpToDate();
    }

    /** What others shared, deleted or made local shows in the open cards and the Documents tray. */
    @Override
    public void afterSync() {
        javafx.application.Platform.runLater(StampedDocumentCard::sharedDataChanged);
    }

    @Override
    public void beforeShutdown() {
        try {
            DocumentFiles.SavedAll saved = DocumentFiles.saveAll();
            if (saved.saved() > 0) {
                LOG.info("Saved {} edited document(s) to their files as Komet quit", saved.saved());
            }
            for (String notSaved : saved.notSaved()) {
                LOG.warn("Not saved as Komet quit: {}; the edit is kept, and its card asks about it", notSaved);
            }
        } catch (RuntimeException e) {
            LOG.error("Could not save the edited documents as Komet quit; their edits are kept", e);
        }
    }
}

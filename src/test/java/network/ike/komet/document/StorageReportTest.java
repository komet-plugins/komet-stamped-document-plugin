/*
 * Copyright © 2026 Knowledge Graphlet / IKE Network
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

import dev.ikm.tinkar.common.alert.AlertLog;
import dev.ikm.tinkar.common.alert.AlertType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * How the gestures on a document's storage report: what worked is kept as a success, and what did
 * not as a warning, so a warning in Warnings and Errors says that something did not go as asked.
 */
class StorageReportTest {

    @Test
    void sharingThatWorkedIsASuccess() {
        AlertLog log = AlertLog.root();
        DocumentSharing.report(null, DocumentSharing.Gesture.SHARE, true, "Shared notes/a.adoc.");
        assertLast(log, AlertType.SUCCESS, "Share", "Shared notes/a.adoc.");
        DocumentSharing.report(null, DocumentSharing.Gesture.SHARE, false, "It is in a folder without git.");
        assertLast(log, AlertType.WARNING, "Share", "It is in a folder without git.");
    }

    @Test
    void removalThatWorkedIsASuccess() {
        AlertLog log = AlertLog.root();
        DocumentRemoval.report(null, true, "Removed 1 document.");
        assertLast(log, AlertType.SUCCESS, DocumentRemoval.TITLE, "Removed 1 document.");
        DocumentRemoval.report(null, false, "Could not remove the document.");
        assertLast(log, AlertType.WARNING, DocumentRemoval.TITLE, "Could not remove the document.");
    }

    @Test
    void aRemovalSaysTheFilesStayWhereTheyAre() {
        assertEquals("Removed 1 document from the knowledge base. Its file stays where it is, and opening it reads it back in.",
                RemoveFromKnowledgeBase.message(new DocumentScopes.Removed(1, 12, 3)), "the folder may have no git");
        assertEquals("Removed 2 documents from the knowledge base. Their files stay where they are, and opening one reads it back in.",
                RemoveFromKnowledgeBase.message(new DocumentScopes.Removed(2, 24, 3)));
    }

    private static void assertLast(AlertLog log, AlertType type, String title, String description) {
        AlertLog.Entry last = log.entries().getLast();
        assertEquals(type, last.type());
        assertEquals(title, last.title());
        assertEquals(description, last.description());
    }
}

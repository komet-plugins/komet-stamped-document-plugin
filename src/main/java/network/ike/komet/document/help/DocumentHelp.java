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
package network.ike.komet.document.help;

import dev.ikm.komet.layout.help.KlHelpTopic;
import dev.ikm.komet.layout.help.KlResourceHelpProvider;

/**
 * The stamped document's help book: AsciiDoc in this jar, under
 * {@code META-INF/komet-help/komet-stamped-document-plugin}, found by a card's help drawer through
 * PluggableService.
 */
public final class DocumentHelp extends KlResourceHelpProvider {

    /** The book's identifier, the directory its pages are in. */
    public static final String BOOK_ID = "komet-stamped-document-plugin";

    /** The stamped-document card's topic. */
    public static final KlHelpTopic STAMPED_DOCUMENT = new KlHelpTopic(BOOK_ID, "stamped-document");

    /** Public no-arg constructor, for the service loader. */
    public DocumentHelp() {
        super(BOOK_ID, "Stamped documents");
    }
}

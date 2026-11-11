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

import java.util.function.LongFunction;

/**
 * How the lines of one showing of a document are read before their inline markup is
 * (IKE-Network/ike-issues#1351): each line's attribute references as the values in force at
 * that line ({@link DocumentAttributes}), then its cross references by what they find in the
 * assembly ({@link DocumentTargets}). The Reading view, the pages and the print model read a
 * line through the same reading, so they cannot read it differently. A listing is not read
 * through it: its lines are its own text.
 */
public final class DocumentReading {

    private static final DocumentReading PLAIN = new DocumentReading(nid -> DocumentAttributes.intrinsic(), DocumentTargets.none());

    private final LongFunction<DocumentAttributes> at;
    private final DocumentTargets targets;

    /**
     * A reading.
     *
     * @param at      the attributes each line is read in, by the line's nid
     * @param targets what a cross reference can find
     */
    public DocumentReading(LongFunction<DocumentAttributes> at, DocumentTargets targets) {
        this.at = at;
        this.targets = targets;
    }

    /** @return a reading with the attributes every document starts with, and no target */
    public static DocumentReading plain() {
        return PLAIN;
    }

    /** @return the attributes a line is read in */
    public DocumentAttributes at(long lineNid) {
        return at.apply(lineNid);
    }

    /** @return what a cross reference can find */
    public DocumentTargets targets() {
        return targets;
    }

    /** @return a reading of one block's lines, in order */
    public Block block() {
        return new Block();
    }

    /**
     * One text read on its own, a title or a caption: at a line's attributes.
     *
     * @param text    the text
     * @param lineNid the line whose attributes it is read in
     * @return the text as read
     */
    public String text(String text, long lineNid) {
        return text(text, at.apply(lineNid));
    }

    /**
     * One text read on its own, in given attributes.
     *
     * @param text       the text
     * @param attributes the attributes it is read in
     * @return the text as read
     */
    public String text(String text, DocumentAttributes attributes) {
        return targets.read(DocumentAttributes.reading().line(text, attributes));
    }

    /**
     * The reading of one block's lines. It is one for the block because a passthrough element may
     * open in a reference on one line and close in a reference on a later one.
     */
    public final class Block {

        private final DocumentAttributes.Reading references = DocumentAttributes.reading();

        private Block() { }

        /**
         * One line as it is read.
         *
         * @param lineNid the line's nid
         * @param text    the line's text, or a part of it such as a table's cell
         * @return the text as read
         */
        public String line(long lineNid, String text) {
            return targets.read(references.line(text, at.apply(lineNid)));
        }

        /**
         * A part of a line read in given attributes.
         *
         * @param text       the text
         * @param attributes the attributes it is read in
         * @return the text as read
         */
        public String line(String text, DocumentAttributes attributes) {
            return targets.read(references.line(text, attributes));
        }
    }
}

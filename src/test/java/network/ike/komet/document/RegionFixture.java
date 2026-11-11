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

import network.ike.komet.document.GitDocumentMaterializer.StoredBlock;
import network.ike.komet.document.GitDocumentMaterializer.StoredDocument;
import network.ike.komet.document.GitDocumentMaterializer.StoredLine;
import network.ike.komet.document.print.StoredFixture;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stored documents for an assembly of several files, built with no store: {@link StoredFixture}
 * numbers every document's lines from 1, so two of them hold the same nids; here each document's
 * nids are moved by an offset of its own, as two files' lines in a store never share a nid.
 */
public final class RegionFixture {

    private RegionFixture() { }

    /**
     * A document as the materializer stores one, its nids moved.
     *
     * @param text   the AsciiDoc text
     * @param offset what is added to every line's and block's nid
     * @return the document
     */
    public static StoredDocument parse(String text, long offset) {
        StoredDocument parsed = StoredFixture.parse(text);
        List<StoredLine> lines = new ArrayList<>();
        for (StoredLine line : parsed.lines()) {
            lines.add(new StoredLine(line.nid() + offset, line.text(), line.introduced(), line.latest(), line.annotations()));
        }
        Map<Long, StoredBlock> blocks = new LinkedHashMap<>();
        for (StoredBlock block : parsed.blocks().values()) {
            blocks.put(block.nid() + offset, moved(block, offset));
        }
        return new StoredDocument(parsed.anchor(), parsed.commit(), lines, parsed.manifestVersions(), parsed.attributes(),
                blocks.get(parsed.root().nid() + offset), blocks, Map.of());
    }

    private static StoredBlock moved(StoredBlock block, long offset) {
        return new StoredBlock(block.nid() + offset, block.kind(), block.level(), block.title(), block.parameters(),
                block.members().stream().map(nid -> nid + offset).toList(), block.stamp());
    }
}

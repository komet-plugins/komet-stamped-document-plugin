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

import design.knowledge.stamp.adoc.dialect.Block;
import dev.ikm.tinkar.common.id.PublicIds;
import javafx.scene.Node;
import network.ike.komet.document.GitDocumentMaterializer.StoredBlock;
import network.ike.komet.document.GitDocumentMaterializer.StoredDocument;
import network.ike.komet.document.GitDocumentMaterializer.StoredLine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The run a structured block continues: the blocks of its kind directly before it, with nothing but blank lines between. */
class StructuredBlocksRunTest {

    private static final String STYLE = "run-test-statement";

    private static final StructuredBlockKind KIND = new StructuredBlockKind() {
        @Override
        public String style() {
            return STYLE;
        }

        @Override
        public String name() {
            return "run test statement";
        }

        @Override
        public Node view(Source source, Context context) {
            return null;
        }

        @Override
        public Optional<Editor> editor(Source source, Context context) {
            return Optional.empty();
        }
    };

    @BeforeAll
    static void register() {
        StructuredBlocks.register(KIND);
    }

    @AfterAll
    static void unregister() {
        StructuredBlocks.unregister(STYLE);
    }

    private final List<StoredLine> lines = new ArrayList<>();
    private final Map<Long, StoredBlock> blocks = new HashMap<>();
    private long next = 1;

    private long line(String text) {
        long nid = next++;
        lines.add(new StoredLine(nid, text, null, null, List.of()));
        return nid;
    }

    /** A listing block of a style, saying one word. */
    private StoredBlock listing(String style, String says) {
        List<Long> members = List.of(line("[" + style + "]"), line("----"), line(says), line("----"));
        StoredBlock block = new StoredBlock(next++, Block.Kind.LISTING, 0, "", "[" + style + "]", members, null);
        blocks.put(block.nid(), block);
        return block;
    }

    private StoredBlock paragraph(String says) {
        StoredBlock block = new StoredBlock(next++, Block.Kind.PARAGRAPH, 0, "", "", List.of(line(says)), null);
        blocks.put(block.nid(), block);
        return block;
    }

    private static List<String> said(List<StructuredBlockKind.Source> sources) {
        return sources.stream().map(StructuredBlockKind.Source::content).toList();
    }

    @Test
    void aRunIsTheBlocksOfTheKindDirectlyBefore() {
        StoredBlock opening = paragraph("Before the statements.");
        StoredBlock a = listing(STYLE, "a");
        long blank = line("");
        StoredBlock b = listing(STYLE, "b");
        StoredBlock c = listing(STYLE, "c");
        StoredBlock between = paragraph("A paragraph between.");
        StoredBlock d = listing(STYLE, "d");
        StoredBlock other = listing("a-style-no-kind-names", "x");
        StoredBlock e = listing(STYLE, "e");
        StoredBlock root = new StoredBlock(next++, Block.Kind.DOCUMENT, 0, "", "", List.of(opening.nid(), a.nid(), blank, b.nid(), c.nid(),
                between.nid(), d.nid(), other.nid(), e.nid()), null);
        blocks.put(root.nid(), root);
        StoredDocument document = new StoredDocument(PublicIds.newRandom(), "", lines, 0, List.of(), root, blocks, Map.of(), Map.of());

        assertEquals(List.of(), said(StructuredBlocks.runBefore(document, a)), "the first of a run has none before it");
        assertEquals(List.of("a"), said(StructuredBlocks.runBefore(document, b)), "a blank line parts nothing");
        assertEquals(List.of("a", "b"), said(StructuredBlocks.runBefore(document, c)), "in document order, the nearest last");
        assertEquals(List.of(), said(StructuredBlocks.runBefore(document, d)), "a paragraph ends the run");
        assertEquals(List.of(), said(StructuredBlocks.runBefore(document, e)), "a block of another kind ends the run");
        assertEquals(List.of(), said(StructuredBlocks.runBefore(document, between)), "a block that is not structured has no run");
    }
}

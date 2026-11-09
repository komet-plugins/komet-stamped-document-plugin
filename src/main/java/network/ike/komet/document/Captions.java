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
import network.ike.komet.document.GitDocumentMaterializer.StoredBlock;
import network.ike.komet.document.GitDocumentMaterializer.StoredDocument;
import network.ike.komet.document.GitDocumentMaterializer.StoredLine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The captions of an assembly: every titled figure and every titled table, numbered in reading
 * order through the host and the documents it includes, so "Figure 3" names the same block in
 * the Reading view, on the page and in the PDF, and the lists of figures and tables have their
 * entries. A figure's caption stands under it and a table's above it, as the convention has it;
 * a block with no title has no caption, and is not counted, so a small working table stays out
 * of the list. The headings are gathered on the same walk, for the table of contents.
 */
public final class Captions {

    /** What a captioned block is. */
    public enum Kind {
        FIGURE("Figure"),
        TABLE("Table");

        private final String word;

        Kind(String word) {
            this.word = word;
        }

        /** @return the word the caption begins with */
        public String word() {
            return word;
        }
    }

    /**
     * One caption.
     *
     * @param kind   what the block is
     * @param number its number among its kind, from 1
     * @param title  the block's title
     * @param block  the block's nid
     * @param member the document the block is in
     */
    public record Caption(Kind kind, int number, String title, long block, DocumentAssembly.Member member) {

        /** @return the caption's label, {@code Figure 3} */
        public String label() {
            return kind.word() + " " + number;
        }

        /** @return the caption as it is read, {@code Figure 3. The control structure} */
        public String text() {
            return label() + ". " + title;
        }
    }

    /**
     * A heading, for the table of contents.
     *
     * @param level  its level, an included document's offset by its depth
     * @param text   its text
     * @param block  the heading block's nid
     * @param member the document it is in
     */
    public record Heading(int level, String text, long block, DocumentAssembly.Member member) {
    }

    private static final Captions NONE = new Captions(Map.of(), List.of(), List.of());

    private final Map<Long, Caption> byBlock;
    private final List<Caption> all;
    private final List<Heading> headings;

    private Captions(Map<Long, Caption> byBlock, List<Caption> all, List<Heading> headings) {
        this.byBlock = byBlock;
        this.all = all;
        this.headings = headings;
    }

    /** No captions. */
    public static Captions none() {
        return NONE;
    }

    /**
     * Numbers the titled figures and tables of an assembly.
     *
     * @param assembly the assembly
     * @param isFigure whether a block is a figure: a diagram, an image, or a block a kind draws
     * @return the captions
     */
    public static Captions of(DocumentAssembly assembly, Predicate<StoredBlock> isFigure) {
        Walk walk = new Walk(assembly, isFigure);
        walk.member(assembly.host());
        return new Captions(Map.copyOf(walk.byBlock), List.copyOf(walk.all), List.copyOf(walk.headings));
    }

    /** @return the headings in reading order, an included document's title among them at its depth */
    public List<Heading> headings() {
        return headings;
    }

    /** @return the caption of a block, when it has one */
    public Optional<Caption> of(long blockNid) {
        return Optional.ofNullable(byBlock.get(blockNid));
    }

    /** @return the caption's text for a block, or its title alone when it has no caption */
    public String text(StoredBlock block) {
        Caption caption = byBlock.get(block.nid());
        return caption == null ? block.title() : caption.text();
    }

    /** @return every caption in reading order */
    public List<Caption> all() {
        return all;
    }

    /** @return the captions of one kind, in reading order */
    public List<Caption> of(Kind kind) {
        return all.stream().filter(c -> c.kind() == kind).toList();
    }

    public boolean isEmpty() {
        return all.isEmpty();
    }

    /** The reading-order walk: a document's blocks, and at a resolved include the child's blocks in its place. */
    private static final class Walk {
        final DocumentAssembly assembly;
        final Predicate<StoredBlock> isFigure;
        final Map<Long, Caption> byBlock = new HashMap<>();
        final List<Caption> all = new ArrayList<>();
        final List<Heading> headings = new ArrayList<>();
        final Set<String> walked = new HashSet<>();
        int figures;
        int tables;

        Walk(DocumentAssembly assembly, Predicate<StoredBlock> isFigure) {
            this.assembly = assembly;
            this.isFigure = isFigure;
        }

        void member(DocumentAssembly.Member member) {
            if (member == null || member.document() == null || member.document().root() == null || !walked.add(member.path())) {
                return;
            }
            block(member, member.document().root());
        }

        private void block(DocumentAssembly.Member member, StoredBlock block) {
            StoredDocument document = member.document();
            if (block.kind() == Block.Kind.SECTION && !block.title().isEmpty()) {
                headings.add(new Heading(block.level() + member.depth(), block.title(), block.nid(), member));
            } else if (block.kind() == Block.Kind.DOCUMENT && !member.host() && !block.title().isEmpty()) {
                headings.add(new Heading(Math.max(1, member.depth()), block.title(), block.nid(), member));
            }
            if (!block.title().isEmpty() && block.kind() != Block.Kind.SECTION && block.kind() != Block.Kind.DOCUMENT) {
                Kind kind = block.kind() == Block.Kind.TABLE ? Kind.TABLE : isFigure.test(block) ? Kind.FIGURE : null;
                if (kind != null) {
                    Caption caption = new Caption(kind, kind == Kind.FIGURE ? ++figures : ++tables, block.title(), block.nid(), member);
                    byBlock.put(block.nid(), caption);
                    all.add(caption);
                    if (kind == Kind.TABLE) {
                        return;                                          // a table's rows and cells are its own
                    }
                }
            }
            for (long nid : block.members()) {
                Optional<StoredLine> line = document.line(nid);
                if (line.isPresent()) {
                    assembly.includeOf(line.get().nid()).filter(DocumentAssembly.Include::resolved).ifPresent(include -> member(include.child()));
                    continue;
                }
                StoredBlock child = document.blocks().get(nid);
                if (child != null) {
                    block(member, child);
                }
            }
        }
    }
}

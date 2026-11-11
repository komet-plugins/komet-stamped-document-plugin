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
import network.ike.komet.document.print.DocumentModel;
import network.ike.komet.document.print.InlineText;
import network.ike.komet.document.print.StoredDocumentModel;

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
    public record Heading(int level, String text, long block, DocumentAssembly.Member member, String number) {

        /** @return the heading as read: its number before its text when it has one */
        public String read() {
            return number.isEmpty() ? text : number + " " + text;
        }
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
        return of(assembly, isFigure, 0);
    }

    /**
     * Numbers the titled figures and tables of an assembly, and its headings to a depth.
     *
     * @param assembly      the assembly
     * @param isFigure      whether a block is a figure
     * @param numberedLevels how many heading levels take a section number, {@code 1}, {@code 1.1}, {@code 1.1.1}; 0 for none
     * @return the captions
     */
    public static Captions of(DocumentAssembly assembly, Predicate<StoredBlock> isFigure, int numberedLevels) {
        Walk walk = new Walk(assembly, isFigure, numberedLevels);
        walk.member(assembly.host());
        return new Captions(Map.copyOf(walk.byBlock), List.copyOf(walk.all), List.copyOf(walk.headings));
    }

    /** @return a heading's section number, {@code 1.2}, or an empty string for a heading with none */
    public String number(long blockNid) {
        for (Heading heading : headings) {
            if (heading.block() == blockNid) {
                return heading.number();
            }
        }
        return "";
    }

    /** @return a heading as read: its number before its text when it has one */
    public String heading(StoredBlock block, String text) {
        String number = number(block.nid());
        return number.isEmpty() ? text : number + " " + text;
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

    /**
     * The lists a header asks for, with their entries, as the Reading view lists them and the
     * pages and the PDF print them: the contents to the depth asked, every heading as read with
     * its number; the figures and the tables by caption. A list with nothing to say is left out.
     * An entry names its block by {@link StoredDocumentModel#key}. A footnote in a heading or a
     * caption stays with it on its own page: the entry leaves it out
     * ({@link InlineText#withoutNotes}).
     *
     * @param lists what the header asks for
     * @return the lists in front-matter order
     */
    public List<DocumentModel.FrontList> frontLists(DocumentModel.Lists lists) {
        List<DocumentModel.FrontList> out = new ArrayList<>();
        if (lists.contents()) {
            List<DocumentModel.Entry> entries = headings.stream().filter(h -> h.level() <= lists.depth())
                    .map(h -> new DocumentModel.Entry(InlineText.withoutNotes(h.read()), Math.max(0, h.level() - 1), StoredDocumentModel.key(h.block()))).toList();
            if (!entries.isEmpty()) {
                out.add(new DocumentModel.FrontList(DocumentModel.FrontList.Kind.CONTENTS, entries));
            }
        }
        if (lists.figures()) {
            frontList(DocumentModel.FrontList.Kind.FIGURES, of(Kind.FIGURE), out);
        }
        if (lists.tables()) {
            frontList(DocumentModel.FrontList.Kind.TABLES, of(Kind.TABLE), out);
        }
        return out;
    }

    private static void frontList(DocumentModel.FrontList.Kind kind, List<Caption> captions, List<DocumentModel.FrontList> out) {
        if (!captions.isEmpty()) {
            out.add(new DocumentModel.FrontList(kind, captions.stream()
                    .map(c -> new DocumentModel.Entry(InlineText.withoutNotes(c.text()), 0, StoredDocumentModel.key(c.block()))).toList()));
        }
    }

    /** The reading-order walk: a document's blocks, and at a resolved include the child's blocks in its place. */
    private static final class Walk {
        final DocumentAssembly assembly;
        final Predicate<StoredBlock> isFigure;
        final Map<Long, Caption> byBlock = new HashMap<>();
        final List<Caption> all = new ArrayList<>();
        final List<Heading> headings = new ArrayList<>();
        /** The showings walked: a file shown twice is walked in each place, for what the second showing reads that the first did not. */
        final Set<DocumentAssembly.Member> walked = new HashSet<>();
        /**
         * The headings listed, by block: a section its file shows twice is one section. It is numbered
         * and listed where it is first shown, and reads the same number wherever it is shown again.
         */
        final Set<Long> listed = new HashSet<>();
        final int numberedLevels;
        /** The section counters by level, 1 the first. */
        final int[] counters = new int[16];
        int figures;
        int tables;
        /**
         * Whether the headings walked now take numbers: on from the start where numbering is asked at
         * all, off from a {@code :sectnums!:} line until the next {@code :sectnums:}, as AsciiDoc has
         * it, so a book numbers its chapters and leaves its glossary and index plain.
         */
        boolean numbering = true;

        Walk(DocumentAssembly assembly, Predicate<StoredBlock> isFigure, int numberedLevels) {
            this.assembly = assembly;
            this.isFigure = isFigure;
            this.numberedLevels = numberedLevels;
        }

        /** The next section number at a level: this level counts up, the levels beneath start again. */
        private String number(int level) {
            if (!numbering || level < 1 || level > numberedLevels || level >= counters.length) {
                return "";
            }
            counters[level]++;
            for (int deeper = level + 1; deeper < counters.length; deeper++) {
                counters[deeper] = 0;
            }
            StringBuilder number = new StringBuilder();
            for (int at = 1; at <= level; at++) {
                number.append(at > 1 ? "." : "").append(counters[at]);
            }
            return number.toString();
        }

        void member(DocumentAssembly.Member member) {
            if (member == null || member.shown() == null || member.shown().root() == null || !walked.add(member)) {
                return;
            }
            block(member, member.shown().root());
        }

        private void block(DocumentAssembly.Member member, StoredBlock block) {
            StoredDocument document = member.shown();
            if (block.kind() == Block.Kind.SECTION && !block.title().isEmpty()) {
                if (listed.add(block.nid())) {
                    int level = block.level() + member.depth();
                    headings.add(new Heading(level, block.title(), block.nid(), member, number(level)));
                }
            } else if (block.kind() == Block.Kind.DOCUMENT && !member.host() && !block.title().isEmpty()) {
                if (listed.add(block.nid())) {
                    int level = Math.max(1, member.depth());
                    headings.add(new Heading(level, block.title(), block.nid(), member, number(level)));
                }
            }
            if (!block.title().isEmpty() && block.kind() != Block.Kind.SECTION && block.kind() != Block.Kind.DOCUMENT) {
                Kind kind = block.kind() == Block.Kind.TABLE ? Kind.TABLE : isFigure.test(block) ? Kind.FIGURE : null;
                if (kind != null) {
                    if (!byBlock.containsKey(block.nid())) {
                        // a figure or a table its file shows twice is one: counted and listed where it is first shown
                        Caption caption = new Caption(kind, kind == Kind.FIGURE ? ++figures : ++tables, block.title(), block.nid(), member);
                        byBlock.put(block.nid(), caption);
                        all.add(caption);
                    }
                    if (kind == Kind.TABLE) {
                        return;                                          // a table's rows and cells are its own
                    }
                }
            }
            for (long nid : block.members()) {
                Optional<StoredLine> line = document.line(nid);
                if (line.isPresent()) {
                    String text = line.get().text().strip();
                    if (text.startsWith(":sectnums!:")) {
                        numbering = false;
                    } else if (text.startsWith(":sectnums:")) {
                        numbering = true;
                    }
                    assembly.includeOf(member, line.get().nid()).filter(DocumentAssembly.Include::resolved).ifPresent(include -> member(include.child()));
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

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
package network.ike.komet.document.print;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A document as a print writer reads it: a title and a flat run of blocks, each a plain-text
 * construct with no source lines, no provenance and no control wiring. It is the one model
 * both outputs of a stamped document consume — the paged JavaFX preview and the Typst
 * writer — and it is what the Typst writer library in {@code ike-docs} would take when the
 * writer moves there ({@code dev-document-surface-pages}, IKE-Network/ike-issues#1307).
 *
 * <p>Headings carry the section a running head names from that point on; everything else is
 * content. Paragraph text is the lines of the source paragraph joined by spaces; verbatim text
 * keeps its line breaks; a table is rows of cells with the header rows counted.
 *
 * <p>The lists a header asks for — the contents, the figures, the tables — are blocks of their
 * own at the front ({@link FrontList}), each entry naming the block it points at by that block's
 * {@code key}, so a writer can print the page the block lands on and a layout oracle can place
 * every entry as a unit of text.
 *
 * <p>An abstract is a block of its own before the lists ({@link Abstract}): what the title page
 * prints beneath the title and the author ({@code dev-document-front-matter}).
 *
 * @param title  the document title, possibly empty
 * @param blocks the blocks in reading order: the abstract first when the document has one, then the front lists, then the body
 * @param lists  what the header asks for
 * @param byline the author and the date set beneath the title
 */
public record DocumentModel(String title, List<Block> blocks, Lists lists, Byline byline) {

    /**
     * What stands beneath a document's title: its author, or its authors joined as one line, and
     * its date, both centered and smaller than the title ({@link StoredDocumentModel#byline}).
     *
     * @param author the author line, empty for none
     * @param date   the date line, empty for none
     */
    public record Byline(String author, String date) {
        /** No author and no date. */
        public static final Byline NONE = new Byline("", "");

        public Byline {
            author = author == null ? "" : author.strip();
            date = date == null ? "" : date.strip();
        }

        /** @return whether there is nothing to set */
        public boolean isEmpty() {
            return author.isEmpty() && date.isEmpty();
        }
    }

    /** A model with the lists a header asks for and no byline. */
    public DocumentModel(String title, List<Block> blocks, Lists lists) {
        this(title, blocks, lists, Byline.NONE);
    }

    /**
     * The lists a document asks to be generated in its front matter: a table of contents to a
     * depth, a list of figures, a list of tables.
     *
     * @param contents whether the document asks for a table of contents, {@code :toc:}
     * @param depth    how many heading levels it lists, {@code :toclevels:}, 2 by default
     * @param figures  whether it asks for a list of figures, {@code :list-of-figures:}
     * @param tables   whether it asks for a list of tables, {@code :list-of-tables:}
     * @param sectionNumbers whether its headings are numbered, {@code :sectnums:}, as {@code 1}, {@code 1.1}, {@code 1.1.1}
     * @param sectionLevels  how many heading levels are numbered, {@code :sectnumlevels:}, 3 by default
     */
    public record Lists(boolean contents, int depth, boolean figures, boolean tables, boolean sectionNumbers, int sectionLevels) {
        /** No lists, and no section numbers. */
        public static final Lists NONE = new Lists(false, 2, false, false, false, 3);

        /** Lists alone, with no section numbers. */
        public Lists(boolean contents, int depth, boolean figures, boolean tables) {
            this(contents, depth, figures, tables, false, 3);
        }

        /** @return whether any list is asked for */
        public boolean any() {
            return contents || figures || tables;
        }
    }

    /** A model with no generated lists. */
    public DocumentModel(String title, List<Block> blocks) {
        this(title, blocks, Lists.NONE);
    }

    public DocumentModel {
        Objects.requireNonNull(title, "title is null");
        Objects.requireNonNull(lists, "lists is null");
        byline = byline == null ? Byline.NONE : byline;
        blocks = List.copyOf(blocks);
    }

    /**
     * Whether the document has front matter ({@code dev-document-front-matter}): an abstract, or
     * a list its header asks for, which stands before the body. Such a document's title stands on
     * a page of its own, its front matter is numbered in lowercase roman numerals, and its body
     * starts at page 1. A list with nothing to list is left out of the model, and is no front
     * matter; nor is an abstract with nothing after it, which has no body to stand in front of.
     *
     * @return whether a front list stands among the blocks, or an abstract with a block after it
     */
    public boolean frontMatter() {
        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            if (block instanceof FrontList || block instanceof Abstract && i + 1 < blocks.size()) {
                return true;
            }
        }
        return false;
    }

    /** @return the document's abstract, which the title page prints beneath the title; empty for a document with none */
    public Optional<Abstract> abstractBlock() {
        return !blocks.isEmpty() && blocks.getFirst() instanceof Abstract first ? Optional.of(first) : Optional.empty();
    }

    /** @return whether the title stands on a page of its own, counted as page i and showing no number: a titled document with front matter */
    public boolean titlePage() {
        return !title.isEmpty() && frontMatter();
    }

    /** @return the front lists' entries, in order */
    public List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        for (Block block : blocks) {
            if (block instanceof FrontList list) {
                out.addAll(list.entries());
            }
        }
        return out;
    }

    /** @return the keys of the blocks the front lists' entries name */
    public Set<String> targets() {
        Set<String> out = new LinkedHashSet<>();
        for (Entry entry : entries()) {
            out.add(entry.target());
        }
        return out;
    }

    /**
     * This model with the front lists' entries carrying the pages their targets begin on.
     *
     * @param pages the folio number of each target's page, by key; a target not named keeps the page it has
     * @return the model, the same one when it has no front list
     */
    public DocumentModel withEntryPages(Map<String, Integer> pages) {
        if (entries().isEmpty()) {
            return this;
        }
        List<Block> out = new ArrayList<>(blocks.size());
        for (Block block : blocks) {
            out.add(block instanceof FrontList list ? list.withPages(pages) : block);
        }
        return new DocumentModel(title, out, lists, byline);
    }

    /** One block of the document. */
    public sealed interface Block permits Heading, Paragraph, Admonition, Verbatim, ListBlock, Table, Image, Drawn, Container, Abstract, FrontList { }

    /** The key of a block no list entry names. */
    private static String keyOrNone(String key) {
        return key == null ? "" : key;
    }

    /**
     * A heading. Level 0 is a document title; an assembly's child titles arrive at the level
     * the include offsets them to.
     *
     * @param level the level, 0 for a title, 1 for a top-level section
     * @param text  the heading text
     * @param key   what a front list's entry names the heading by, empty for a heading no entry can name
     */
    public record Heading(int level, String text, String key) implements Block {
        /** A heading no entry names. */
        public Heading(int level, String text) {
            this(level, text, "");
        }

        public Heading {
            key = keyOrNone(key);
        }
    }

    /**
     * A paragraph of prose.
     *
     * @param text the lines joined by spaces
     */
    public record Paragraph(String text) implements Block { }

    /**
     * An admonition.
     *
     * @param label  {@code NOTE}, {@code TIP}, {@code WARNING}, {@code IMPORTANT} or {@code CAUTION}
     * @param blocks the admonition's content: one paragraph for the paragraph form, the body's blocks for the block form
     */
    public record Admonition(String label, List<Block> blocks) implements Block { }

    /**
     * A verbatim block: a listing, a literal block or a diagram source.
     *
     * @param title the block title, possibly empty
     * @param text  the content with its line breaks, delimiters excluded
     * @param key   what a front list's entry names the block by (a titled diagram is a figure), empty for none
     */
    public record Verbatim(String title, String text, String key) implements Block {
        /** A verbatim block no entry names. */
        public Verbatim(String title, String text) {
            this(title, text, "");
        }

        public Verbatim {
            key = keyOrNone(key);
        }
    }

    /**
     * A block drawn rather than set: a figure of marks, whole on one page, scaled to the page's
     * measure when it is wider. Typst and the Page layout draw the same marks.
     *
     * @param figure  the figure
     * @param caption the caption drawn beneath it, empty for none
     * @param key     what a front list's entry names the figure by, empty for none
     * @param picture the picture file set at the figure's head, for an image block whose file was found; else {@code null}
     */
    public record Drawn(network.ike.komet.document.DecisionFigure figure, String caption, String key, Picture picture) implements Block {
        /** A drawn block with no caption. */
        public Drawn(network.ike.komet.document.DecisionFigure figure) {
            this(figure, "", "", null);
        }

        /** A drawn block no entry names. */
        public Drawn(network.ike.komet.document.DecisionFigure figure, String caption) {
            this(figure, caption, "", null);
        }

        /** A drawn block of marks alone. */
        public Drawn(network.ike.komet.document.DecisionFigure figure, String caption, String key) {
            this(figure, caption, key, null);
        }

        public Drawn {
            key = keyOrNone(key);
        }
    }

    /**
     * A list.
     *
     * @param kind  the list kind
     * @param title the list title, possibly empty
     * @param items the items in order
     */
    public record ListBlock(Kind kind, String title, List<Item> items) implements Block {

        /** The list kinds a writer distinguishes. */
        public enum Kind { UNORDERED, ORDERED, DESCRIPTION, BIBLIOGRAPHY }
    }

    /**
     * One list item.
     *
     * @param term   a description list's term, empty for the other kinds
     * @param text   the item's principal text, its wrapped lines joined by spaces
     * @param blocks the blocks nested in the item: nested lists, attached blocks
     */
    public record Item(String term, String text, List<Block> blocks) { }

    /**
     * A table.
     *
     * @param title      the table title, possibly empty
     * @param widths     the relative column widths, one per column, from {@code cols} or all 1
     * @param headerRows how many leading rows are header rows
     * @param rows       the rows, each a list of cell texts, short rows padded by the writer
     * @param key        what a front list's entry names the table by, empty for none
     * @param spans      for each row, how many columns each of its cells takes; a row with none here, or beyond
     *                   the list's end, has a cell to a column
     */
    public record Table(String title, List<Integer> widths, int headerRows, List<List<String>> rows, String key, List<List<Integer>> spans)
            implements Block {

        /** A table no entry names. */
        public Table(String title, List<Integer> widths, int headerRows, List<List<String>> rows) {
            this(title, widths, headerRows, rows, "", List.of());
        }

        /** A table whose every cell takes one column. */
        public Table(String title, List<Integer> widths, int headerRows, List<List<String>> rows, String key) {
            this(title, widths, headerRows, rows, key, List.of());
        }

        public Table {
            key = keyOrNone(key);
        }

        /**
         * How many columns each cell of a row takes.
         *
         * @param row the row's index
         * @return a span for each of the row's cells, all 1 for a row with no spanning cell
         */
        public List<Integer> spansOf(int row) {
            List<Integer> own = row < spans.size() ? spans.get(row) : List.of();
            return own.size() == rows.get(row).size() ? own : java.util.Collections.nCopies(rows.get(row).size(), 1);
        }

        /** @return the relative widths of a row's cells: its columns', a spanning cell's the sum of those it takes */
        public List<Integer> weightsOf(int row) {
            List<Integer> own = spansOf(row);
            return own.stream().allMatch(span -> span == 1) ? widths : TableCells.weights(own, widths);
        }

        /** @return the column count */
        public int columns() {
            return widths.size();
        }
    }

    /**
     * A picture file an image block names, with its own size: the size it is set at unless the
     * page is narrower.
     *
     * @param file   the file, in the working tree the document was read from
     * @param width  its width, in points
     * @param height its height, in points
     */
    public record Picture(java.nio.file.Path file, double width, double height) { }

    /**
     * A block image whose file was not found, named rather than embedded: the writers draw its caption and target.
     *
     * @param target the image target as written
     * @param title  the block title, possibly empty
     * @param key    what a front list's entry names the image by, empty for none
     */
    public record Image(String target, String title, String key) implements Block {
        /** An image no entry names. */
        public Image(String target, String title) {
            this(target, title, "");
        }

        public Image {
            key = keyOrNone(key);
        }
    }

    /**
     * A delimited container: an example, a sidebar, a quote or an open block.
     *
     * @param kind   the container kind
     * @param title  the block title, possibly empty
     * @param blocks the contained blocks
     */
    public record Container(Kind kind, String title, List<Block> blocks) implements Block {

        /** The container kinds a writer distinguishes. */
        public enum Kind { EXAMPLE, SIDEBAR, QUOTE, OPEN }
    }

    /**
     * The document's abstract ({@code dev-document-front-matter}): the block written with
     * AsciiDoc's {@code abstract} style before the first section. It is the model's first block,
     * and there is at most one. On the pages it is printed on the title page, beneath the title
     * and the author; what does not fit runs on to the next pages, which are front matter. The
     * lists and the body follow on pages of their own. It is no entry of the contents.
     *
     * @param blocks what the abstract holds: its paragraph, or its open block with the paragraphs and lists inside it
     */
    public record Abstract(List<Block> blocks) implements Block {

        public Abstract {
            blocks = List.copyOf(blocks);
        }
    }

    /**
     * A list the header asks for at the front of the document: the contents, the figures or
     * the tables, each entry the text of what it names, its depth, and the key of the block
     * it names, whose page the writers print after a leader.
     *
     * @param kind    which list
     * @param entries the entries in reading order
     */
    public record FrontList(Kind kind, List<Entry> entries) implements Block {

        /** The lists, each with the title it is printed under. */
        public enum Kind {
            CONTENTS("Contents"),
            FIGURES("Figures"),
            TABLES("Tables");

            private final String title;

            Kind(String title) {
                this.title = title;
            }

            /** @return the list's title */
            public String title() {
                return title;
            }
        }

        public FrontList {
            Objects.requireNonNull(kind, "kind is null");
            entries = List.copyOf(entries);
        }

        /** @return the list's title, {@code Contents}, {@code Figures} or {@code Tables} */
        public String title() {
            return kind.title();
        }

        /** @return this list with its entries' pages taken from the map, by target; an entry whose target is not named keeps its page */
        public FrontList withPages(Map<String, Integer> pages) {
            List<Entry> out = new ArrayList<>(entries.size());
            for (Entry entry : entries) {
                Integer page = pages.get(entry.target());
                out.add(page == null ? entry : entry.withPage(page));
            }
            return new FrontList(kind, out);
        }
    }

    /**
     * One entry of a front list.
     *
     * @param text   what it says: a heading as read, with its number; a caption, {@code Figure 3. Title}
     * @param depth  how far it is indented, 0 for a top-level heading or any caption
     * @param target the key of the block it names
     * @param page   the number of the folio the target's page prints as last laid out ({@link Folios}), 0 while unknown;
     *               a writer that prints the document asks the layout for the page itself, a measured source prints this
     */
    public record Entry(String text, int depth, String target, int page) {

        /** An entry whose target's page is not known yet. */
        public Entry(String text, int depth, String target) {
            this(text, depth, target, 0);
        }

        public Entry {
            Objects.requireNonNull(text, "text is null");
            Objects.requireNonNull(target, "target is null");
        }

        /** @return this entry on a page */
        public Entry withPage(int page) {
            return new Entry(text, depth, target, page);
        }
    }
}

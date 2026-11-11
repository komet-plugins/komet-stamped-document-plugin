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

import design.knowledge.stamp.adoc.dialect.Block;
import network.ike.komet.document.DecisionFigure;
import network.ike.komet.document.GitDocumentMaterializer.StoredBlock;
import network.ike.komet.document.GitDocumentMaterializer.StoredDocument;
import network.ike.komet.document.GitDocumentMaterializer.StoredLine;
import network.ike.komet.document.StructuredBlocks;
import network.ike.komet.document.print.DocumentModel.Admonition;
import network.ike.komet.document.print.DocumentModel.Container;
import network.ike.komet.document.print.DocumentModel.Heading;
import network.ike.komet.document.print.DocumentModel.Image;
import network.ike.komet.document.print.DocumentModel.Item;
import network.ike.komet.document.print.DocumentModel.ListBlock;
import network.ike.komet.document.print.DocumentModel.Paragraph;
import network.ike.komet.document.print.DocumentModel.Table;
import network.ike.komet.document.print.DocumentModel.Verbatim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a {@link StoredDocument}'s blocks into a {@link DocumentModel}: the same walk the
 * Reading view makes over the stored structure, reduced to what a page shows. Headings are
 * read from the heading line, prose lines are joined by spaces with a trailing hard-break
 * backslash dropped, list markers and continuation marks are stripped, delimiters, block
 * titles and attribute lines are dropped from verbatim and container blocks, and a table's
 * rows are split into cells on their bars with the {@code cols} and {@code header} options
 * read from the block's parameters.
 *
 * <p>An assembly is read as one document: where a host's include line names a document the
 * store holds, that child flows in place — its title a heading at the include's
 * {@code leveloffset}, its sections offset with it — so the children paginate continuously
 * under one running head and one folio sequence.
 */
public final class StoredDocumentModel {

    private static final Pattern MARKER = Pattern.compile("^\\s*(\\*+|-|\\.+|\\d+\\.)\\s+");
    private static final Pattern DELIMITER = Pattern.compile("^(-{4,}|\\.{4,}|={4,}|_{4,}|\\*{4,}|--|\\|===|/{4,}|\\+{4,})$");
    private static final Pattern ATTRIBUTE_LINE = Pattern.compile("^\\[(?!\\[)([^\\]]*)\\]$");
    private static final Pattern ANCHOR_LINE = Pattern.compile("^\\[\\[[^\\]]*\\]\\]$");
    private static final Pattern BLOCK_TITLE = Pattern.compile("^\\.([^\\s.].*)$");
    private static final Pattern ADMONITION_PREFIX = Pattern.compile("^(NOTE|TIP|WARNING|IMPORTANT|CAUTION):\\s*");
    private static final Pattern ADMONITION_STYLE = Pattern.compile("\\[(NOTE|TIP|WARNING|IMPORTANT|CAUTION)(?:[,\\]].*)?");
    private static final Pattern COLS = Pattern.compile("cols\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern COL_SPEC = Pattern.compile("^(?:(\\d+)\\*)?[<^>.]*(\\d+)?[<^>.]*[a-z]?$");
    private static final Pattern IMAGE_MACRO = Pattern.compile("^image::([^\\[]*)\\[(.*)\\]$");
    private static final Pattern DESCRIPTION_TERM = Pattern.compile("^(?!\\s)(.+?)(::+)(?:\\s+(.*))?$");
    private static final Pattern LEVEL_OFFSET = Pattern.compile("leveloffset\\s*=\\s*([+-]?\\d+)");
    private static final Pattern INDEX_TERM_MACRO = Pattern.compile("indexterm:\\[[^\\]]*\\]\\s*");
    private static final Pattern CONCEALED_TERM = Pattern.compile("\\(\\(\\([^()]+\\)\\)\\)\\s*");
    private static final Pattern FLOW_TERM = Pattern.compile("\\(\\(([^()]+)\\)\\)");

    /**
     * A flowed child of an assembly: an included document and the level its title lands at.
     *
     * @param document    the child as the store holds it
     * @param levelOffset the include's {@code leveloffset}, added to the including document's own; the child's
     *                    title becomes a heading at that level, at least 1
     */
    public record Child(StoredDocument document, int levelOffset) {

        /** The level offset an include's parameters name, or 0. */
        public static int levelOffset(String includeParameters) {
            if (includeParameters == null) {
                return 0;
            }
            Matcher m = LEVEL_OFFSET.matcher(includeParameters);
            return m.find() ? Integer.parseInt(m.group(1)) : 0;
        }
    }

    /** What a block is drawn as on a page; none prints it as its text. */
    @FunctionalInterface
    public interface Figures {
        /** A document with no block drawn, its structured blocks printing as their source. */
        Figures NONE = (document, block) -> List.of();

        /**
         * The figures of a block.
         *
         * @param document the block's document
         * @param block    the block
         * @return its figures in order, or none
         */
        List<DecisionFigure> of(StoredDocument document, StoredBlock block);

        /**
         * The caption of a block, as the document's captions read it, {@code Figure 3. Title}:
         * the block's title by default, which a surface that numbers its figures and tables
         * replaces.
         *
         * @param document the block's document
         * @param block    the block
         * @return the caption, or an empty string for a block with no title
         */
        default String caption(StoredDocument document, StoredBlock block) {
            return block.title();
        }

        /**
         * A heading as the document reads it: its number before its text where the document
         * numbers its sections, {@code 1.2 The structure}; the text alone by default.
         *
         * @param document the heading's document
         * @param block    the heading block
         * @param text     the heading's text
         * @return the heading as read
         */
        default String heading(StoredDocument document, StoredBlock block, String text) {
            return text;
        }

        /**
         * The lists the header asks for, with their entries: none by default — a surface that
         * numbers its headings and captions supplies them, as {@link network.ike.komet.document.Captions#frontLists}
         * builds them. An entry names its block by {@link StoredDocumentModel#key}.
         *
         * @param document the host document
         * @param lists    what the header asks for
         * @return the lists in front-matter order, an empty one left out
         */
        default List<DocumentModel.FrontList> frontLists(StoredDocument document, DocumentModel.Lists lists) {
            return List.of();
        }

        /**
         * A reading that numbers headings and captions as an assembly's captions have them, and
         * lists them at the front, drawing nothing: what a document is read with where no surface
         * draws its blocks.
         *
         * @param captions the assembly's captions
         * @return the reading
         */
        static Figures captioned(network.ike.komet.document.Captions captions) {
            return new Figures() {
                @Override
                public List<DecisionFigure> of(StoredDocument document, StoredBlock block) {
                    return List.of();
                }

                @Override
                public String caption(StoredDocument document, StoredBlock block) {
                    return block.title().isEmpty() ? "" : captions.text(block);
                }

                @Override
                public String heading(StoredDocument document, StoredBlock block, String text) {
                    return captions.heading(block, text);
                }

                @Override
                public List<DocumentModel.FrontList> frontLists(StoredDocument document, DocumentModel.Lists lists) {
                    return captions.frontLists(lists);
                }
            };
        }
    }

    /**
     * The key a front list's entry names a block by, and the block carries: from the block's nid,
     * so the same block has the same key in the model and on the surface, and a Typst label can
     * spell it.
     *
     * @param blockNid the block's nid
     * @return the key
     */
    public static String key(long blockNid) {
        return "b" + Long.toString(blockNid).replace('-', 'n');
    }

    private final StoredDocument document;
    private final Figures figures;
    private final Map<Long, StoredLine> linesByNid = new HashMap<>();
    private final int levelOffset;
    /** The child flowed in place of an include line, by the line's nid, across every member. */
    private final Map<Long, Child> includes;

    private StoredDocumentModel(StoredDocument document, int levelOffset, Map<Long, Child> includes, Figures figures) {
        this.document = document;
        this.figures = figures;
        this.levelOffset = levelOffset;
        this.includes = includes;
        for (StoredLine line : document.lines()) {
            linesByNid.put(line.nid(), line);
        }
    }

    /**
     * Reads one document; its include lines print nothing.
     *
     * @param document the document as the store holds it
     * @return the model
     */
    public static DocumentModel of(StoredDocument document) {
        return of(document, Map.of());
    }

    /**
     * Reads an assembly: the host document with each resolved child flowed in place of its
     * include line, the children's own includes resolved the same way.
     *
     * @param document the host as the store holds it
     * @param includes the child shown in place of an include line, by the nid of that line, for the host and every child
     * @return the model, the children's headings offset by their includes
     */
    public static DocumentModel of(StoredDocument document, Map<Long, Child> includes) {
        return of(document, includes, Figures.NONE);
    }

    /**
     * Reads an assembly, drawing the blocks that have figures.
     *
     * @param document the host document
     * @param includes the child flowed in place of each resolved include line, by the line's nid
     * @param figures  what a block is drawn as
     * @return the model
     */
    public static DocumentModel of(StoredDocument document, Map<Long, Child> includes, Figures figures) {
        DocumentModel.Lists lists = lists(document);
        // the lists stand at the front, after the title, as the printed document and the pages have them
        List<DocumentModel.Block> blocks = new ArrayList<>(figures.frontLists(document, lists));
        String title = new StoredDocumentModel(document, 0, Map.copyOf(includes), figures).read(blocks, true);
        List<StoredDocument> all = new ArrayList<>();
        all.add(document);
        includes.values().forEach(child -> all.add(child.document()));
        return new DocumentModel(title, blocks, lists, byline(document, all));
    }

    private static final Pattern AUTHOR = Pattern.compile("^:author:\\s*(.*)$");
    private static final Pattern AUTHORS = Pattern.compile("^:authors:\\s*(.*)$");
    private static final Pattern REVDATE = Pattern.compile("^:revdate:\\s*(.*)$");
    /** An AsciiDoc revision line: a version, then the date after a comma, then a remark after a colon; or a date alone. */
    private static final Pattern REVISION_LINE = Pattern.compile("^v?\\d[\\w.\\-]*(?:,\\s*([^:]+))?(?::.*)?$");
    private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    /** How a date prints beneath the title: the month spelled out, as a title page has it. */
    private static final java.time.format.DateTimeFormatter DATE = java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy", java.util.Locale.US);

    /**
     * Where a document's header names its author and date, and what it names.
     *
     * @param authorLine the line that names the author ({@code :author:}, {@code :authors:}, or the author line under the title), or null
     * @param author     the author, or the authors joined as one line ("A, B and C"), empty for none
     * @param dateLine   the line that names the date ({@code :revdate:}, or the revision line), or null
     * @param date       the date as the header names it, empty for none
     */
    public record Header(StoredLine authorLine, String author, StoredLine dateLine, String date) { }

    /**
     * The author and date a document's header names. The author is {@code :author:} or
     * {@code :authors:}, else the author line directly under the title, its addresses dropped;
     * authors separated by {@code ;} are joined as one line. The date is {@code :revdate:}, else
     * the date of the revision line under the author line.
     *
     * @param document the host document
     * @return what the header names
     */
    public static Header header(StoredDocument document) {
        StoredLine authorLine = null;
        String author = "";
        StoredLine dateLine = null;
        String date = "";
        List<StoredLine> lines = document.lines();
        for (StoredLine line : lines) {
            String text = line.text().strip();
            Matcher m;
            if (authorLine == null && ((m = AUTHOR.matcher(text)).matches() || (m = AUTHORS.matcher(text)).matches())) {
                authorLine = line;
                author = authors(m.group(1));
            } else if (dateLine == null && (m = REVDATE.matcher(text)).matches()) {
                dateLine = line;
                date = m.group(1).strip();
            }
        }
        // the implicit lines: the author line and the revision line directly under the title, in the header
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).text().startsWith("= ")) {
                continue;
            }
            int at = i + 1;
            if (at < lines.size() && implicit(lines.get(at).text())) {
                if (authorLine == null) {
                    authorLine = lines.get(at);
                    author = authors(lines.get(at).text().replaceAll("<[^>]*>", ""));
                }
                at++;
                if (at < lines.size() && implicit(lines.get(at).text())) {
                    Matcher revision = REVISION_LINE.matcher(lines.get(at).text().strip());
                    if (dateLine == null && revision.matches()) {
                        dateLine = lines.get(at);
                        date = revision.group(1) == null ? lines.get(at).text().strip() : revision.group(1).strip();
                    }
                }
            }
            break;
        }
        return new Header(authorLine, author, dateLine, date);
    }

    /** Whether a header line is an implicit one, an author or a revision line: not blank, not an attribute entry, a comment or a block. */
    private static boolean implicit(String text) {
        String t = text.strip();
        return !t.isEmpty() && !t.startsWith(":") && !t.startsWith("//") && !t.startsWith("[") && !t.startsWith("include::")
                && !t.startsWith("=") && !t.startsWith("+") && !t.startsWith(".");
    }

    /** Authors separated by semicolons, joined as one line: "A", "A and B", "A, B and C". */
    static String authors(String value) {
        List<String> names = new ArrayList<>();
        for (String name : value.split(";")) {
            if (!name.isBlank()) {
                names.add(name.strip().replaceAll("\\s+", " "));
            }
        }
        if (names.size() <= 1) {
            return names.isEmpty() ? "" : names.getFirst();
        }
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.getLast();
    }

    /**
     * What stands beneath a document's title: the author its header names, and the date — the
     * header's, when it names one, else the day of the newest commit that introduced a line the
     * document or anything it includes still reads: the day its text was last written. Only the
     * STAMP that introduced a line counts, as in the Reading view's date margin; a line's later
     * versions may be the store's own upkeep, such as a store repaired when it opens, which
     * dated the regulatory book the day it was opened (2026-11-10). An uncommitted change has no
     * day yet, and is not counted. A header that names neither an author nor a date has
     * nothing beneath its title: a topic is not dated for being committed.
     *
     * @param host      the host document
     * @param documents the host and every document it includes
     * @return the byline, {@link DocumentModel.Byline#NONE} for a header that names no author and no date
     */
    public static DocumentModel.Byline byline(StoredDocument host, java.util.Collection<StoredDocument> documents) {
        Header header = header(host);
        if (header.author().isEmpty() && header.date().isEmpty()) {
            return DocumentModel.Byline.NONE;
        }
        String date = header.date();
        if (ISO_DATE.matcher(date).matches()) {
            date = java.time.LocalDate.parse(date).format(DATE);
        } else if (date.isEmpty()) {
            long newest = committed(documents);
            date = newest > 0 ? date(newest) : "";
        }
        return new DocumentModel.Byline(header.author(), date);
    }

    /** @return a time as the date a title page prints: the month spelled out, in this machine's time zone */
    public static String date(long epochMillis) {
        return java.time.Instant.ofEpochMilli(epochMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate().format(DATE);
    }

    /** @return the time of the newest commit that introduced a line of the documents, 0 for none */
    public static long committed(java.util.Collection<StoredDocument> documents) {
        long newest = 0;
        for (StoredDocument document : documents) {
            for (StoredLine line : document.lines()) {
                var stamp = line.introduced();
                if (stamp != null && stamp.time() != Long.MAX_VALUE && stamp.time() != Long.MIN_VALUE) {
                    newest = Math.max(newest, stamp.time());
                }
            }
        }
        return newest;
    }

    private static final Pattern TOC_LEVELS = Pattern.compile("^:toclevels:\\s*(\\d+)\\s*$");
    private static final Pattern SECTION_LEVELS = Pattern.compile("^:sectnumlevels:\\s*(\\d+)\\s*$");

    /**
     * The lists a document asks for in its header: {@code :toc:}, with {@code :toclevels:}, and
     * the dialect's {@code :list-of-figures:} and {@code :list-of-tables:}; and whether it numbers
     * its sections at all — a {@code :sectnums!:} later in the document ends a numbered range,
     * which the captions honour heading by heading, rather than taking the numbering back.
     *
     * @param document the host document
     * @return the lists
     */
    public static DocumentModel.Lists lists(StoredDocument document) {
        boolean contents = false;
        boolean figures = false;
        boolean tables = false;
        boolean sectionNumbers = false;
        int depth = 2;
        int sectionLevels = 3;
        for (StoredLine line : document.lines()) {
            String text = line.text().strip();
            if (text.startsWith(":toc:") && !text.startsWith(":toc!:")) {
                contents = true;
            } else if (text.startsWith(":list-of-figures:")) {
                figures = true;
            } else if (text.startsWith(":list-of-tables:")) {
                tables = true;
            } else if (text.startsWith(":sectnums:")) {
                sectionNumbers = true;
            } else {
                Matcher levels = TOC_LEVELS.matcher(text);
                if (levels.matches()) {
                    depth = Integer.parseInt(levels.group(1));
                }
                Matcher sections = SECTION_LEVELS.matcher(text);
                if (sections.matches()) {
                    sectionLevels = Integer.parseInt(sections.group(1));
                }
            }
        }
        return new DocumentModel.Lists(contents, depth, figures, tables, sectionNumbers, sectionLevels);
    }

    /** Reads this document's blocks onto the list; returns its title. A child's title is a heading; the host's is the model's title. */
    private String read(List<DocumentModel.Block> out, boolean isRoot) {
        String title = document.attributes().isEmpty() ? "" : document.attributes().getFirst();
        if (document.root() == null) {
            // No structure in the store: every line is a paragraph of its own, as the Reading view shows it.
            for (StoredLine line : document.lines()) {
                out.add(new Paragraph(line.text()));
            }
            return title;
        }
        if (title.isEmpty()) {
            title = document.root().title();
        }
        if (!isRoot && !title.isEmpty()) {
            out.add(new Heading(levelOffset, figures.heading(document, document.root(), title), key(document.root().nid())));
        }
        for (Member member : members(document.root())) {
            if (member.block() != null) {
                block(member.block(), out);
            }
        }
        return title;
    }

    /** A block's member: a line or a child block, in document order. */
    private record Member(StoredLine line, StoredBlock block) { }

    private List<Member> members(StoredBlock block) {
        List<Member> members = new ArrayList<>();
        for (long nid : block.members()) {
            StoredLine line = linesByNid.get(nid);
            if (line != null) {
                members.add(new Member(line, null));
            } else {
                StoredBlock child = document.blocks().get(nid);
                if (child != null) {
                    members.add(new Member(null, child));
                }
            }
        }
        return members;
    }

    private List<StoredLine> ownLines(StoredBlock block) {
        return members(block).stream().map(Member::line).filter(l -> l != null).toList();
    }

    private List<StoredBlock> childBlocks(StoredBlock block) {
        return members(block).stream().map(Member::block).filter(b -> b != null).toList();
    }

    private void block(StoredBlock block, List<DocumentModel.Block> out) {
        List<DecisionFigure> drawn = figures.of(document, block);
        if (!drawn.isEmpty()) {
            // the first figure carries the block's caption, which the figures source has drawn beneath it
            String caption = figures.caption(document, block);
            for (int i = 0; i < drawn.size(); i++) {
                out.add(new DocumentModel.Drawn(drawn.get(i), i == 0 ? caption : "", i == 0 ? key(block.nid()) : ""));
            }
            return;
        }
        if (block.kind() == Block.Kind.OPEN && StructuredBlocks.kindOf(block).isPresent()) {
            // one block of source, as a listing is: the parser's paragraphs of it are not paragraphs
            out.add(new Verbatim(block.title(), String.join("\n",
                    StructuredBlocks.content(document, block).stream().map(StoredLine::text).toList()), key(block.nid())));
            return;
        }
        List<StoredLine> own = ownLines(block);
        List<StoredBlock> children = childBlocks(block);
        switch (block.kind()) {
            case DOCUMENT -> children(children, out);
            case SECTION -> {
                String heading = own.stream().map(StoredLine::text).filter(t -> t.matches("^=+\\s.*")).findFirst()
                        .map(t -> t.replaceFirst("^=+\\s*", "").strip()).orElse(block.title());
                out.add(new Heading(block.level() + levelOffset, figures.heading(document, block, heading), key(block.nid())));
                children(children, out);
            }
            case PARAGRAPH -> {
                String text = prose(contentLines(own));
                if (!text.isEmpty()) {
                    out.add(new Paragraph(text));
                }
            }
            case ADMONITION -> {
                String label = admonitionLabel(block, own);
                List<DocumentModel.Block> body = new ArrayList<>();
                String text = prose(contentLines(own));
                if (!text.isEmpty()) {
                    body.add(new Paragraph(ADMONITION_PREFIX.matcher(text).replaceFirst("")));
                }
                children(children, body);
                out.add(new Admonition(label, body));
            }
            case LISTING, LITERAL, DIAGRAM -> out.add(new Verbatim(block.title(), String.join("\n", verbatimLines(own).stream().map(StoredLine::text).toList()),
                    key(block.nid())));
            case EXAMPLE, QUOTE, SIDEBAR, OPEN -> {
                List<DocumentModel.Block> body = new ArrayList<>();
                children(children, body);
                out.add(new Container(switch (block.kind()) {
                    case EXAMPLE -> Container.Kind.EXAMPLE;
                    case QUOTE -> Container.Kind.QUOTE;
                    case SIDEBAR -> Container.Kind.SIDEBAR;
                    default -> Container.Kind.OPEN;
                }, block.title(), body));
            }
            case LIST -> out.add(list(block, children));
            case LIST_ITEM, BIBLIOGRAPHY_ENTRY -> { } // only inside a LIST
            case TABLE -> out.add(table(block, children));
            case TABLE_ROW, TABLE_CELL -> { } // only inside a TABLE
            case IMAGE -> {
                String target = own.stream().map(StoredLine::text).map(IMAGE_MACRO::matcher).filter(Matcher::matches)
                        .map(m -> m.group(1)).findFirst().orElse("");
                out.add(new Image(target, block.title(), key(block.nid())));
            }
            case OPAQUE -> {
                // Comments, attribute entries and passthrough print nothing; an include line whose child
                // the store holds flows that child in here, at the include's level offset on top of ours.
                for (StoredLine line : own) {
                    Child child = includes.get(line.nid());
                    if (child != null) {
                        new StoredDocumentModel(child.document(), Math.max(1, levelOffset + child.levelOffset()), includes, figures).read(out, false);
                    }
                }
            }
        }
    }

    private void children(List<StoredBlock> children, List<DocumentModel.Block> out) {
        for (StoredBlock child : children) {
            block(child, out);
        }
    }

    private static String admonitionLabel(StoredBlock block, List<StoredLine> own) {
        Matcher style = ADMONITION_STYLE.matcher(block.parameters());
        if (style.find()) {
            return style.group(1);
        }
        for (StoredLine line : own) {
            Matcher m = ADMONITION_PREFIX.matcher(line.text());
            if (m.find()) {
                return m.group(1);
            }
        }
        return "NOTE";
    }

    private ListBlock list(StoredBlock block, List<StoredBlock> children) {
        String parameters = block.parameters();
        ListBlock.Kind kind = parameters.contains("[bibliography]") ? ListBlock.Kind.BIBLIOGRAPHY
                : parameters.contains("description") ? ListBlock.Kind.DESCRIPTION
                : parameters.contains("ordered") && !parameters.contains("unordered") ? ListBlock.Kind.ORDERED
                : ListBlock.Kind.UNORDERED;
        List<Item> items = new ArrayList<>();
        for (StoredBlock item : children) {
            if (item.kind() != Block.Kind.LIST_ITEM && item.kind() != Block.Kind.BIBLIOGRAPHY_ENTRY) {
                continue;
            }
            List<StoredLine> own = ownLines(item);
            if (kind == ListBlock.Kind.UNORDERED && !own.isEmpty()) {
                String first = own.getFirst().text().stripLeading();
                if (!first.isEmpty() && (first.startsWith(".") || Character.isDigit(first.charAt(0)))) {
                    kind = ListBlock.Kind.ORDERED; // the parser names the kind by the first marker; a bare list says nothing
                }
            }
            String term = "";
            List<String> texts = new ArrayList<>();
            for (int i = 0; i < own.size(); i++) {
                String text = own.get(i).text();
                if (text.strip().equals("+")) {
                    continue;
                }
                if (i == 0) {
                    if (kind == ListBlock.Kind.DESCRIPTION) {
                        Matcher d = DESCRIPTION_TERM.matcher(text);
                        if (d.matches()) {
                            term = d.group(1).strip();
                            text = d.group(3) == null ? "" : d.group(3);
                        }
                    } else if (kind == ListBlock.Kind.BIBLIOGRAPHY) {
                        text = text.replaceFirst("^\\s*(\\*+|-)\\s+", "").replaceFirst("^\\[\\[\\[[^\\]]+\\]\\]\\]\\s*", "");
                    } else {
                        text = MARKER.matcher(text).replaceFirst("");
                    }
                }
                texts.add(text);
            }
            List<DocumentModel.Block> nested = new ArrayList<>();
            children(childBlocks(item), nested);
            items.add(new Item(term, prose(texts), nested));
        }
        return new ListBlock(kind, block.title(), items);
    }

    private Table table(StoredBlock block, List<StoredBlock> children) {
        List<String> lines = new ArrayList<>();
        for (StoredBlock row : children) {
            if (row.kind() == Block.Kind.TABLE_ROW) {
                ownLines(row).forEach(line -> lines.add(line.text()));
            }
        }
        List<List<TableCells.Cell>> cells = TableCells.rows(lines, block.parameters());
        List<List<String>> rows = cells.stream().map(row -> row.stream().map(TableCells.Cell::text).toList()).toList();
        List<Integer> widths = widths(block.parameters(), TableCells.columns(cells));
        boolean header = block.parameters().contains("header") && !rows.isEmpty();
        return new Table(figures.caption(document, block), widths, header ? 1 : 0, rows, key(block.nid()));
    }

    /** @return how many columns {@code cols} names, 0 when the table has none */
    public static int namedColumns(String parameters) {
        return COLS.matcher(parameters == null ? "" : parameters).find() ? widths(parameters, 0).size() : 0;
    }

    /** The relative widths {@code cols} names, repeated specs expanded, padded or cut to the column count. */
    public static List<Integer> widths(String parameters, int columns) {
        List<Integer> widths = new ArrayList<>();
        Matcher m = COLS.matcher(parameters == null ? "" : parameters);
        String named = m.find() ? m.group(1).strip() : "";
        if (named.matches("\\d+")) {
            widths.addAll(Collections.nCopies(Integer.parseInt(named), 1)); // cols="3" is three of one width
        } else if (!named.isEmpty()) {
            for (String spec : named.split(",")) {
                Matcher s = COL_SPEC.matcher(spec.strip());
                int repeat = 1;
                int width = 1;
                if (s.matches()) {
                    repeat = s.group(1) == null ? 1 : Integer.parseInt(s.group(1));
                    width = s.group(2) == null ? 1 : Integer.parseInt(s.group(2));
                }
                for (int i = 0; i < repeat; i++) {
                    widths.add(Math.max(1, width));
                }
            }
        }
        // A cols that names more columns than any row holds stands; rows are padded to it. Fewer, or
        // none, and the widest row decides, the missing columns weighted 1.
        while (widths.size() < Math.max(1, columns)) {
            widths.add(1);
        }
        return widths;
    }

    /** The lines of a paragraph-like block that are content: not an anchor, title, attribute or delimiter line. */
    private static List<String> contentLines(List<StoredLine> own) {
        List<String> lines = new ArrayList<>();
        for (StoredLine line : own) {
            String text = line.text();
            if (ANCHOR_LINE.matcher(text).matches() || ATTRIBUTE_LINE.matcher(text).matches() || DELIMITER.matcher(text).matches()
                    || (BLOCK_TITLE.matcher(text).matches() && lines.isEmpty())) {
                continue;
            }
            lines.add(text);
        }
        return lines;
    }

    /**
     * The content of a verbatim block: what lies between its first and last delimiter line.
     *
     * @param own the block's own lines
     * @return the lines it prints, every one as it stands: none of them is markup
     */
    public static List<StoredLine> verbatimLines(List<StoredLine> own) {
        int first = -1;
        int last = -1;
        for (int i = 0; i < own.size(); i++) {
            if (DELIMITER.matcher(own.get(i).text()).matches()) {
                if (first < 0) {
                    first = i;
                }
                last = i;
            }
        }
        if (first < 0) {
            return own;
        }
        if (last == first) {
            return own.subList(first + 1, own.size());
        }
        return own.subList(first + 1, last);
    }

    /**
     * Prose lines joined by spaces, each stripped: index-term macros and concealed terms are
     * dropped (they print nothing), a flow term keeps its words, a trailing hard-break
     * {@code +} ends a line of the paragraph (a newline in the text), a trailing backslash is dropped.
     */
    static String prose(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        boolean breakBefore = false;
        for (String line : lines) {
            String t = inline(line).strip();
            if (t.endsWith("\\")) {
                t = t.substring(0, t.length() - 1).stripTrailing();
            }
            boolean hardBreak = t.equals("+") || t.endsWith(" +");
            if (hardBreak) {
                t = t.substring(0, t.length() - 1).stripTrailing();
            }
            if (!t.isEmpty()) {
                if (!sb.isEmpty()) {
                    sb.append(breakBefore ? '\n' : ' ');
                }
                sb.append(t);
                breakBefore = false;
            }
            breakBefore |= hardBreak;
        }
        return sb.toString();
    }

    /** One line with the inline constructs that print nothing removed. */
    static String inline(String text) {
        String t = INDEX_TERM_MACRO.matcher(text).replaceAll("");
        t = CONCEALED_TERM.matcher(t).replaceAll("");
        return FLOW_TERM.matcher(t).replaceAll("$1");
    }
}

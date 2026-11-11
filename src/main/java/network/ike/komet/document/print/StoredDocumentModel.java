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
import network.ike.komet.document.DocumentAssembly;
import network.ike.komet.document.DocumentAttributes;
import network.ike.komet.document.DocumentReading;
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
import java.util.Optional;
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
 * <p>What is read is what the document reads as ({@link DocumentAssembly#read}): a line that a
 * conditional directive leaves out is not there, and nor is the directive. An attribute
 * reference in prose, in a title or in a table's cell prints as its value
 * ({@link DocumentAttributes}); in a listing it stays as written, since a listing is its source.
 * A cross reference that finds its target in the assembly prints as a link to the target's
 * block, and a bibliography entry begins with the label its citations print
 * ({@link network.ike.komet.document.DocumentTargets}).
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
    /** A line comment, which is not a block comment's delimiter: it prints nothing, inside a paragraph as outside one. */
    private static final Pattern LINE_COMMENT = Pattern.compile("^//(?!//).*$");
    private static final Pattern FLOW_TERM = Pattern.compile("\\(\\(([^()]+)\\)\\)");

    /**
     * A flowed child of an assembly: an included document and the level its title lands at.
     *
     * @param document    the child as its include shows it: the document as the store holds it, or, for an include
     *                    that names tags, the document cut down to the tagged regions
     *                    ({@link network.ike.komet.document.DocumentAssembly.Member#shown})
     * @param levelOffset the include's {@code leveloffset}, added to the including document's own; the child's
     *                    title becomes a heading at that level, at least 1
     * @param reading     how the child's lines are read before their inline markup
     */
    public record Child(StoredDocument document, int levelOffset, DocumentReading reading) {

        /** A child read with the attributes every document starts with, its references as written. */
        public Child(StoredDocument document, int levelOffset) {
            this(document, levelOffset, DocumentReading.plain());
        }

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
         * The relative widths a table's columns are printed with: those {@code cols} names by
         * default. A surface that knows the page's measure and its fonts widens a column too
         * narrow for its longest word, so the PDF and the pages set the table in the same widths.
         *
         * @param table      the table's block
         * @param rows       its rows, each cell's text as the model has it
         * @param spans      for each row, how many columns each cell takes; empty when no cell spans
         * @param headerRows how many leading rows are header rows, set bold
         * @param widths     the widths {@code cols} names
         * @return the widths to print with
         */
        default List<Integer> columns(StoredBlock table, List<List<String>> rows, List<List<Integer>> spans, int headerRows, List<Integer> widths) {
            return widths;
        }

        /**
         * The picture file a figure is drawn from: none by default. A surface that knows the
         * working tree a document was read from draws an image block as a figure of its file's
         * size ({@link #of}), and says here which file, so the PDF embeds it.
         *
         * @param figure a figure {@link #of} gave
         * @return the picture set at the figure's head, or empty for a figure of marks alone
         */
        default Optional<DocumentModel.Picture> picture(DecisionFigure figure) {
            return Optional.empty();
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

    /** A block's key where the block is first read, and none where its file is shown again: a front list's entry names one place. */
    private String keyOf(StoredBlock block) {
        return keyed.add(block.nid()) ? key(block.nid()) : "";
    }

    private final StoredDocument document;
    private final Figures figures;
    private final Map<Long, StoredLine> linesByNid = new HashMap<>();
    private final int levelOffset;
    /** The child flowed in place of an include line, by the line's nid, across every member. */
    private final Map<Long, Child> includes;
    /**
     * The blocks that have given their key, across the host and every child: a block is shown twice
     * when its file is included twice, and a key names one place, so the first showing carries it.
     */
    private final java.util.Set<Long> keyed;
    /** How the document's lines are read before their inline markup. */
    private final DocumentReading reading;

    private StoredDocumentModel(StoredDocument document, int levelOffset, Map<Long, Child> includes, Figures figures, java.util.Set<Long> keyed,
                                DocumentReading reading) {
        this.reading = reading;
        this.document = document;
        this.figures = figures;
        this.levelOffset = levelOffset;
        this.includes = includes;
        this.keyed = keyed;
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
     * The children of an assembly as the model reads them: for every include line that shows a
     * document in place, what it shows, the whole file or the tagged regions the include names
     * ({@code dev-document-front-matter}), and the level its headings are offset by.
     *
     * @param assembly the assembly
     * @return the child flowed in place of each resolved include line, by the line's nid
     */
    public static Map<Long, Child> children(network.ike.komet.document.DocumentAssembly assembly) {
        Map<Long, Child> includes = new HashMap<>();
        Map<network.ike.komet.document.DocumentAssembly.Member, Integer> offsets = new HashMap<>();
        for (network.ike.komet.document.DocumentAssembly.Include include : assembly.includes()) {   // surface order: an including member precedes its children
            if (!include.resolved()) {
                continue;
            }
            int offset = offsets.getOrDefault(include.in(), 0) + Child.levelOffset(include.line().text());
            offsets.put(include.child(), offset);
            network.ike.komet.document.DocumentAssembly.Member child = include.child();
            // a file shown twice follows its own includes in each showing; the line's first showing is the one the model reads
            includes.putIfAbsent(include.line().nid(), new Child(assembly.read(child), offset, assembly.reading(child)));
        }
        return includes;
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
        // the host's own attributes and conditionals, read as a document alone has them
        DocumentAssembly alone = DocumentAssembly.single(document, "");
        return of(alone.read(alone.host()), alone.reading(alone.host()), includes, figures);
    }

    /**
     * Reads an assembly, drawing the blocks that have figures: the host and every child as they
     * read, each line's attribute references given the values in force at it.
     *
     * @param assembly the assembly
     * @param figures  what a block is drawn as
     * @return the model
     */
    public static DocumentModel of(DocumentAssembly assembly, Figures figures) {
        DocumentAssembly.Member host = assembly.host();
        return of(assembly.read(host), assembly.reading(host), children(assembly), figures);
    }

    private static DocumentModel of(StoredDocument document, DocumentReading reading, Map<Long, Child> includes, Figures figures) {
        DocumentModel.Lists lists = lists(document);
        StoredDocumentModel reader = new StoredDocumentModel(document, 0, Map.copyOf(includes), figures, new java.util.HashSet<>(), reading);
        // the abstract stands first, on the title page; the lists follow it, after the title, as the printed document and the pages have them
        List<DocumentModel.Block> blocks = new ArrayList<>();
        abstractOf(document).ifPresent(block -> {
            List<DocumentModel.Block> held = reader.abstractBlocks(block);
            if (held.stream().anyMatch(StoredDocumentModel::sets)) {
                blocks.add(new DocumentModel.Abstract(held));
            } else {
                blocks.addAll(held);                              // an abstract that sets nothing on a page is no abstract
            }
        });
        blocks.addAll(figures.frontLists(document, lists));
        String title = reader.read(blocks, true);
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

    /**
     * The document's abstract ({@code dev-document-front-matter}): the first paragraph or open
     * block of the host that is written with AsciiDoc's {@code abstract} style and comes before
     * its first section. There is at most one: a second block with the style is an ordinary
     * block. An include line ends the search as a section does, since what it includes may open
     * one. An abstract of an included document is not the host's, and is not looked for.
     *
     * @param document the host document
     * @return the abstract's block, or empty for a document with none
     */
    public static Optional<StoredBlock> abstractOf(StoredDocument document) {
        if (document.root() == null) {
            return Optional.empty();
        }
        for (long nid : document.root().members()) {
            StoredBlock child = document.blocks().get(nid);
            if (child == null) {
                continue;                                         // one of the root's own lines: the title, the header's attributes
            }
            if (child.kind() == Block.Kind.SECTION) {
                break;
            }
            if (child.kind() == Block.Kind.OPAQUE && child.members().stream().map(document::line).flatMap(Optional::stream)
                    .anyMatch(line -> line.text().startsWith("include::"))) {
                break;
            }
            if ((child.kind() == Block.Kind.PARAGRAPH || child.kind() == Block.Kind.OPEN) && abstractStyle(child.parameters())) {
                return Optional.of(child);
            }
        }
        return Optional.empty();
    }

    /** Whether a block's attribute lines give it the {@code abstract} style: {@code [abstract]}, with or without a role or an id. */
    static boolean abstractStyle(String parameters) {
        if (parameters == null) {
            return false;
        }
        for (String line : parameters.split("\n")) {
            if (StructuredBlocks.style(line).filter("abstract"::equals).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /** Whether a block sets anything on a page: a container, an admonition or a list with nothing in it sets nothing. */
    private static boolean sets(DocumentModel.Block block) {
        return switch (block) {
            case Container c -> !c.title().isEmpty() || c.blocks().stream().anyMatch(StoredDocumentModel::sets);
            case Admonition a -> a.blocks().stream().anyMatch(StoredDocumentModel::sets);
            case ListBlock l -> !l.title().isEmpty() || !l.items().isEmpty();
            default -> true;
        };
    }

    /** What an abstract holds: its paragraph, or its open block with the paragraphs and lists inside it, read as any block is. */
    private List<DocumentModel.Block> abstractBlocks(StoredBlock block) {
        List<DocumentModel.Block> held = new ArrayList<>();
        block(block, held);
        return held;
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
        if (document.root() != null && !title.isEmpty()) {
            title = valued(title, document.root());
        }
        if (document.root() == null) {
            // No structure in the store: every line is a paragraph of its own, as the Reading view shows it.
            for (StoredLine line : document.lines()) {
                out.add(new Paragraph(line.text()));
            }
            return title;
        }
        if (title.isEmpty()) {
            title = title(document.root());
        }
        if (!isRoot && !title.isEmpty()) {
            out.add(new Heading(levelOffset, figures.heading(document, document.root(), title), keyOf(document.root())));
        }
        // the host's abstract is read first, onto the title page: it is not read again where it was written
        long titlePage = isRoot ? abstractOf(document).map(StoredBlock::nid).orElse(Long.MIN_VALUE) : Long.MIN_VALUE;
        for (Member member : members(document.root())) {
            if (member.block() != null && member.block().nid() != titlePage) {
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

    /** The first line a block holds, its child blocks' among them, or null. */
    private StoredLine firstLine(StoredBlock block) {
        for (StoredBlock in = block; in != null; ) {
            if (in.members().isEmpty()) {
                return null;
            }
            long first = in.members().getFirst();
            StoredLine line = linesByNid.get(first);
            if (line != null) {
                return line;
            }
            in = document.blocks().get(first);
        }
        return null;
    }

    /** Text that stands for a block, its title or its caption, read where the block begins: its attribute references as their values, its cross references by what they find. */
    private String valued(String text, StoredBlock block) {
        StoredLine first = text.indexOf('{') < 0 && text.indexOf('<') < 0 && text.indexOf("xref:") < 0 ? null : firstLine(block);
        return first == null ? text : reading.text(text, first.nid());
    }

    /** A block's title as it reads. */
    private String title(StoredBlock block) {
        return valued(block.title(), block);
    }

    private void block(StoredBlock block, List<DocumentModel.Block> out) {
        List<DecisionFigure> drawn = figures.of(document, block);
        if (!drawn.isEmpty()) {
            // the first figure carries the block's caption, which the figures source has drawn beneath it
            String caption = valued(figures.caption(document, block), block);
            for (int i = 0; i < drawn.size(); i++) {
                out.add(new DocumentModel.Drawn(drawn.get(i), i == 0 ? caption : "", i == 0 ? keyOf(block) : "", figures.picture(drawn.get(i)).orElse(null)));
            }
            return;
        }
        if (block.kind() == Block.Kind.OPEN && StructuredBlocks.kindOf(block).isPresent()) {
            // one block of source, as a listing is: the parser's paragraphs of it are not paragraphs
            out.add(new Verbatim(title(block), String.join("\n",
                    StructuredBlocks.content(document, block).stream().map(StoredLine::text).toList()), keyOf(block)));
            return;
        }
        List<StoredLine> own = ownLines(block);
        List<StoredBlock> children = childBlocks(block);
        switch (block.kind()) {
            case DOCUMENT -> children(children, out);
            case SECTION -> {
                String heading = own.stream().filter(line -> line.text().matches("^=+\\s.*")).findFirst()
                        .map(line -> reading.text(line.text().replaceFirst("^=+\\s*", "").strip(), line.nid()))
                        .orElse(title(block));
                out.add(new Heading(block.level() + levelOffset, figures.heading(document, block, heading), keyOf(block)));
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
            case LISTING, LITERAL, DIAGRAM -> out.add(new Verbatim(title(block), String.join("\n", verbatimLines(own).stream().map(StoredLine::text).toList()),
                    keyOf(block)));
            case EXAMPLE, QUOTE, SIDEBAR, OPEN -> {
                List<DocumentModel.Block> body = new ArrayList<>();
                children(children, body);
                out.add(new Container(switch (block.kind()) {
                    case EXAMPLE -> Container.Kind.EXAMPLE;
                    case QUOTE -> Container.Kind.QUOTE;
                    case SIDEBAR -> Container.Kind.SIDEBAR;
                    default -> Container.Kind.OPEN;
                }, title(block), body));
            }
            case LIST -> out.add(list(block, children));
            case LIST_ITEM, BIBLIOGRAPHY_ENTRY -> { } // only inside a LIST
            case TABLE -> out.add(table(block, children));
            case TABLE_ROW, TABLE_CELL -> { } // only inside a TABLE
            case IMAGE -> {
                String target = own.stream().map(StoredLine::text).map(IMAGE_MACRO::matcher).filter(Matcher::matches)
                        .map(m -> m.group(1)).findFirst().orElse("");
                out.add(new Image(target, title(block), keyOf(block)));
            }
            case OPAQUE -> {
                // Comments, attribute entries and passthrough print nothing; an include line whose child
                // the store holds flows that child in here, at the include's level offset on top of ours.
                for (StoredLine line : own) {
                    Child child = includes.get(line.nid());
                    if (child != null) {
                        new StoredDocumentModel(child.document(), Math.max(1, levelOffset + child.levelOffset()), includes, figures, keyed, child.reading())
                                .read(out, false);
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
            DocumentReading.Block references = reading.block();
            for (int i = 0; i < own.size(); i++) {
                String text = own.get(i).text();
                if (text.strip().equals("+")) {
                    continue;
                }
                if (i == 0) {
                    if (kind == ListBlock.Kind.DESCRIPTION) {
                        Matcher d = DESCRIPTION_TERM.matcher(text);
                        if (d.matches()) {
                            term = reading.text(d.group(1).strip(), own.get(i).nid());
                            text = d.group(3) == null ? "" : d.group(3);
                        }
                    } else if (kind == ListBlock.Kind.BIBLIOGRAPHY) {
                        // the entry's anchor is read with the line: it gives the entry the label its citations print
                        text = text.replaceFirst("^\\s*(\\*+|-)\\s+", "");
                    } else {
                        text = MARKER.matcher(text).replaceFirst("");
                    }
                }
                texts.add(references.line(own.get(i).nid(), text));
            }
            List<DocumentModel.Block> nested = new ArrayList<>();
            children(childBlocks(item), nested);
            items.add(new Item(term, prose(texts), nested));
        }
        return new ListBlock(kind, title(block), items);
    }

    private Table table(StoredBlock block, List<StoredBlock> children) {
        List<String> lines = new ArrayList<>();
        for (StoredBlock row : children) {
            if (row.kind() == Block.Kind.TABLE_ROW) {
                ownLines(row).forEach(line -> lines.add(line.text()));
            }
        }
        List<List<TableCells.Cell>> cells = TableCells.rows(lines, block.parameters());
        // a cell's references are read once the row is cut into cells: a value that holds a bar is not a cell's edge
        StoredLine first = firstLine(block);
        DocumentAttributes in = first == null ? DocumentAttributes.intrinsic() : reading.at(first.nid());
        List<List<String>> rows = cells.stream().map(row -> {
            DocumentReading.Block references = reading.block();
            return row.stream().map(cell -> references.line(cell.text(), in)).toList();
        }).toList();
        List<Integer> widths = widths(block.parameters(), TableCells.columns(cells));
        boolean header = block.parameters().contains("header") && !rows.isEmpty();
        List<List<Integer>> spans = cells.stream().anyMatch(row -> row.stream().anyMatch(cell -> cell.span() > 1))
                ? cells.stream().map(TableCells::spans).toList() : List.of();
        return new Table(valued(figures.caption(document, block), block), figures.columns(block, rows, spans, header ? 1 : 0, widths), header ? 1 : 0,
                rows, keyOf(block), spans);
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

    /**
     * The lines of a paragraph-like block that are content: not an anchor, title, attribute or
     * delimiter line. Each is given as it reads, its attribute references as their values.
     */
    private List<String> contentLines(List<StoredLine> own) {
        List<String> lines = new ArrayList<>();
        DocumentReading.Block references = reading.block();
        for (StoredLine line : own) {
            String text = line.text();
            if (ANCHOR_LINE.matcher(text).matches() || ATTRIBUTE_LINE.matcher(text).matches() || DELIMITER.matcher(text).matches()
                    || (BLOCK_TITLE.matcher(text).matches() && lines.isEmpty()) || LINE_COMMENT.matcher(text.strip()).matches()) {
                continue;                                         // a comment among a paragraph's lines prints nothing, a tag line included
            }
            lines.add(references.line(line.nid(), text));
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

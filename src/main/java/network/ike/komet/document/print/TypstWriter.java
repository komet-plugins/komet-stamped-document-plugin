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

import network.ike.komet.document.print.DocumentModel.Admonition;
import network.ike.komet.document.print.DocumentModel.Block;
import network.ike.komet.document.print.DocumentModel.Container;
import network.ike.komet.document.print.DocumentModel.Heading;
import network.ike.komet.document.print.DocumentModel.Image;
import network.ike.komet.document.print.DocumentModel.Item;
import network.ike.komet.document.print.DocumentModel.ListBlock;
import network.ike.komet.document.print.DocumentModel.Paragraph;
import network.ike.komet.document.print.DocumentModel.Table;
import network.ike.komet.document.print.DocumentModel.Verbatim;
import network.ike.komet.document.print.InlineText.Note;
import network.ike.komet.document.print.InlineText.Run;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Writes a {@link DocumentModel} as Typst source: page setup, running head, folios, headings,
 * prose paragraphs, lists, admonitions, verbatim blocks and tables with header rows. It
 * depends on nothing of Komet or JavaFX — the model and a {@link PageSetup} in, one string
 * out — so it is the shape the Typst writer library in {@code ike-docs} takes when it moves
 * there ({@code dev-document-surface-pages}); the escaping and fence rules follow that
 * library's {@code TypstText}.
 *
 * <p>Prose reaches the page through {@link InlineText}: inline code as {@code raw} at the raw
 * size, strong and emphasis, links, and footnotes at the foot of the page.
 *
 * <p>The lists the header asks for are written as the model has them ({@link DocumentModel.FrontList}),
 * not as Typst's own {@code outline}: a heading and one entry block per entry, with the target's
 * page after a leader, so the measured source holds every entry as labelled text a layout oracle
 * can place, and the pages paginate as the PDF does. Every block an entry names carries a label
 * the printed document reads the page counter at.
 *
 * <p>The running head shows the document title on the left and the current section on the
 * right — the last heading of level 1 or 2 before the page, so a flowed child's title names
 * the section while the child runs. A page that opens something carries none: the title's page,
 * and the page each part or top-level section begins on ({@link RunningHeads}); a folio the head
 * would carry drops to the foot there.
 *
 * <p>The title is display type ({@link TitleLines}): centered, never hyphenated, tied at its
 * phrase boundaries and broken in the narrowest measure that sets it in as few lines as the full
 * measure does, so its lines run to similar lengths. Headings are flush left, ragged and never
 * hyphenated.
 *
 * <p>A document with no front matter numbers its pages in arabic from 1. A document with front
 * matter ({@link DocumentModel#frontMatter()}, {@code dev-document-front-matter}) numbers the
 * front matter in lowercase roman numerals, shows no number on its title page, and starts the
 * body at 1: see {@link #folioHelper}. {@link Folios} gives the same numbers to the Page layout.
 *
 * <p>An abstract ({@link DocumentModel.Abstract}) is written straight after the title and the
 * author, so it is printed on the title page; what the page has no room for runs on to the next
 * pages, which are front matter. A page break follows it, before the first list or, in a document
 * with no list, before the body.
 */
public final class TypstWriter {

    /** The deepest heading level the running head names: a top-level section, or a flowed child's title. */
    private static final int RUNNING_HEAD_DEPTH = 2;

    /** The size the writer's raw rule asks for, in ems of the size Typst's own raw rule leaves. */
    private static final double RAW_RULE_EM = 0.85;
    /** The size Typst's own raw rule sets, in ems of the text around it. */
    private static final double TYPST_RAW_EM = 0.8;
    /**
     * The size raw text prints at, as a fraction of the text around it: the two rules compound, so
     * a listing in a 12 pt body is set at 8.16 pt. What the page draws raw text at, so that JavaFX
     * and Typst set a listing at one size.
     */
    public static final double RAW_SCALE = RAW_RULE_EM * TYPST_RAW_EM;

    /** The size of a block's title, in ems of the body: what {@code ike-block-title} sets it at. */
    public static final double TITLE_SCALE = 0.95;

    /** The size of the author line beneath the title, in ems of the body. */
    public static final double AUTHOR_SCALE = 1.2;

    /** The size of the date line beneath the title, in ems of the body. */
    public static final double DATE_SCALE = 1.0;

    /** The space from the title's last baseline to the top of the line beneath it (its cap height), in ems of the body. */
    public static final double BYLINE_GAP = 1.4;

    /** The space from the author line's last baseline to the top of the date line, in ems of the body. */
    public static final double DATE_GAP = 0.5;

    /** The size of a boxed block's title, in ems of the body, as the print model has every container's. */
    public static final double BOX_TITLE_SCALE = 0.9;

    private TypstWriter() { }

    /** Where the folio goes. */
    public enum Folio { NONE, HEADER_RIGHT, FOOTER_CENTER, FOOTER_RIGHT }

    /**
     * The page: paper, margins, fonts and furniture.
     *
     * @param paper        a Typst paper name ({@code "us-letter"}, {@code "a4"}, {@code "us-legal"})
     * @param landscape    whether the paper is turned
     * @param marginTop    the top margin in points
     * @param marginRight  the right margin in points
     * @param marginBottom the bottom margin in points
     * @param marginLeft   the left margin in points
     * @param bodyFonts    the body font families in preference order
     * @param monoFonts    the verbatim font families in preference order
     * @param fontSize     the body size in points
     * @param runningHead  whether the running head is drawn
     * @param folio        where the page number goes
     */
    public record PageSetup(String paper, boolean landscape, double marginTop, double marginRight, double marginBottom,
                            double marginLeft, List<String> bodyFonts, List<String> monoFonts, double fontSize,
                            boolean runningHead, Folio folio) {

        public PageSetup {
            Objects.requireNonNull(paper, "paper is null");
            bodyFonts = List.copyOf(bodyFonts);
            monoFonts = List.copyOf(monoFonts);
            Objects.requireNonNull(folio, "folio is null");
        }

        /** US Letter, one-inch margins, a serif body at 12 pt, running head and a centered footer folio. */
        public static final PageSetup DEFAULT = new PageSetup("us-letter", false, 72, 72, 72, 72,
                List.of(DocumentFonts.SERIF, "Libertinus Serif", "Georgia"), List.of(DocumentFonts.MONO, "Menlo", "DejaVu Sans Mono"),
                12, true, Folio.FOOTER_CENTER);
    }

    /**
     * Writes the document.
     *
     * @param document the document
     * @param setup    the page
     * @return Typst source
     */
    public static String write(DocumentModel document, PageSetup setup) {
        return write(document, setup, false);
    }

    /** The label every top-level block carries when written for measurement, queried with {@code typst query}. */
    public static final String BLOCK_LABEL = "ike-block";
    /** The label after the last block when written for measurement: its page is the page count. */
    public static final String END_LABEL = "ike-end";

    /**
     * Writes the document, optionally with a zero-size {@code metadata} element labelled
     * {@link #BLOCK_LABEL} before the title and before every top-level block, so
     * {@code typst query <file> "<ike-block>" --field location} answers where each block starts
     * — the measurement the JavaFX Page layout is checked against.
     *
     * @param document the document
     * @param setup    the page
     * @param labelled whether to mark the blocks
     * @return Typst source
     */
    public static String write(DocumentModel document, PageSetup setup, boolean labelled) {
        return write(document, setup, labelled ? Labels.BLOCKS : Labels.NONE);
    }

    /** What a measured document is labelled with. */
    public enum Labels {
        /** Nothing: the document as printed. */
        NONE,
        /** Every top-level block, with its index, and the end. */
        BLOCKS,
        /** The blocks, and every word with its text ({@link TypstOracle#WORD_RULE}): the layout oracle's source. */
        WORDS
    }

    /**
     * Writes the document with the given labels: a zero-size {@code metadata} element carrying
     * the block's index, labelled {@link #BLOCK_LABEL}, before the title and before every
     * top-level block, one labelled {@link #END_LABEL} after the last, and, for
     * {@link Labels#WORDS}, the show rule that labels every word with its text — what the
     * {@link TypstOracle} reads the lines from.
     *
     * @param document the document
     * @param setup    the page
     * @param labels   what to label
     * @return Typst source
     */
    public static String write(DocumentModel document, PageSetup setup, Labels labels) {
        StringBuilder content = new StringBuilder(4096);
        CHUNK_WORDS.set(labels == Labels.WORDS);
        NOTES.set(new InlineText.Numbering());
        // a block is labelled when something names it: a front list's entry, or a link in the text
        java.util.Set<String> named = new java.util.LinkedHashSet<>(document.targets());
        document.blocks().forEach(block -> texts(block, text -> {
            java.util.regex.Matcher link = INTERNAL_LINK.matcher(text);
            while (link.find()) {
                named.add(link.group(1));
            }
        }));
        TARGETS.set(named);
        try {
            body(document, labels, content);
        } finally {
            CHUNK_WORDS.set(false);
            NOTES.remove();
            TARGETS.remove();
        }
        StringBuilder out = new StringBuilder(content.length() + 2048);
        out.append(preamble(InlineText.of(document.title()).text(), setup, document.frontMatter(), document.titlePage()));
        // only where it is used, so a document with no drawn block is the source it always was
        if (content.indexOf("ike-figure(") >= 0) {                       // bare, or within a captioned figure
            out.append(figureHelper(setup));
        }
        if (content.indexOf("ike-entry(") >= 0) {
            out.append(ENTRY_HELPERS);
        }
        if (content.indexOf("ike-ref(") >= 0) {
            out.append(REF_HELPER);
        }
        if (content.indexOf("ike-head-note(") >= 0) {
            out.append(HEAD_NOTE_HELPER);
        }
        if (labels == Labels.WORDS) {
            out.append(TypstOracle.WORD_RULE);
        }
        return out.append(content).toString();
    }

    /** The prefix of the label a front list's entry names its block by: {@code <ike-b123>} for the key {@code b123}. */
    public static final String TARGET_LABEL = "ike-";

    /** The room between the end of an entry's text and its leader, in ems: see {@link #ENTRY_HELPERS}. */
    public static final double ENTRY_GAP = 0.4;

    /** The least leader of an entry, in ems: with {@link #ENTRY_GAP}, the room every line of its text keeps clear. */
    public static final double ENTRY_LEAD = 2;

    /** The room between an entry's leader and the column of its page number, in ems. */
    public static final double ENTRY_GUTTER = 0.4;

    /** The room between two dots of an entry's leader, in ems. */
    public static final double ENTRY_DOT_GAP = 0.15;

    /** The room between an entry's mark ({@link FrontLists#mark}) and its text, in ems: what the column of marks is wider than its widest mark. */
    public static final double ENTRY_MARK_GAP = 0.75;

    /** How far a depth none of whose entries opens with a mark sets the depth under it in, in ems. */
    public static final double ENTRY_STEP = 1;

    /** The space above a chief entry, an outermost one of a list that has levels ({@link FrontLists#levelled}), in ems. */
    public static final double ENTRY_GROUP = 1.1;

    /** The page number every entry's column has room for: a wider number widens its own entry's column. */
    public static final String ENTRY_NUMBER_ROOM = "000";

    /**
     * A front list's entry, and the page its target lands on. An entry is a block that never
     * breaks over a page, in two columns: its text and its leader, then its page number, set
     * against the measure's right edge on the text's last line. The number is in a column of
     * its own, as wide as {@link #ENTRY_NUMBER_ROOM} or as the number when that is wider, so it
     * is no part of the text's line and cannot wrap away from it: set after the text on one
     * line, as it once was, a number with no room left went under the text at the left edge
     * with no leader, and one with a hair of room kept a leader of a dot or two (the regulatory
     * book, 2026-11-10).
     *
     * <p>The entry's mark, its section or caption number ({@link FrontLists#mark}), stands in a
     * column of its own before the text: as wide as the widest mark of the entry's depth in its
     * list ({@link FrontLists#patterns}) and {@link #ENTRY_MARK_GAP}, the mark ending that gap
     * before the text whatever its length. So the texts of a depth begin at one edge, an entry
     * with no mark among them, and every line of a text returns to it. A depth begins where the
     * text of the depth above does; a depth with no marks at all has no column, and sets the
     * depth under it {@link #ENTRY_STEP} in.
     *
     * <p>In a list that has levels the outermost entries are its chief ones: bold, mark, text
     * and page number, with {@link #ENTRY_GROUP} above each, so an entry and those under it read
     * as a group and a turned-over line of a chief entry is not taken for the entry under it. A
     * list of one level has none. The page numbers of a list with chief entries have room for
     * {@link #ENTRY_NUMBER_ROOM} in bold, so the leaders of all its entries end at one edge.
     *
     * <p>The text is set ragged and never hyphenated in a measure {@link #ENTRY_GAP} and
     * {@link #ENTRY_LEAD} short of the page number's gutter, so every entry of a depth turns
     * over at the same edge and its last line always has room for a leader. A short last word
     * is tied to the word before it ({@link FrontLists#tied}), so it never turns over alone. The
     * leader takes the rest of the last line and runs on over the room the text kept clear; its
     * dots stand {@link #ENTRY_DOT_GAP} apart from the leader's right edge leftward, so the dots
     * of one entry stand over those of the next.
     *
     * <p>In the printed document the whole entry, mark, text, leader and page number, is a link
     * to what it names, so a reader of the PDF goes there by clicking any part of it; a measured
     * source names no target, and a target the document does not hold is no link.
     *
     * <p>The printed document asks the page counter at the target's label, so Typst's own number
     * stands there; a measured source ({@link Labels#WORDS}) holds the room of the number the
     * last layout pass found, an empty box as wide as the digits would set, and no dots: the
     * oracle would read dots and digits as words. The pages follow the same rule
     * ({@link network.ike.komet.document.DocumentSurfaceView}, {@link FrontLists#reserve}). A
     * target the document does not hold prints no page rather than failing the compile.
     */
    static final String ENTRY_HELPERS = """
            #let ike-page(key) = context {
              let found = query(key)
              if found.len() > 0 { str(counter(page).at(found.first().location()).first()) }
            }
            #let ike-page-room(number) = context box(width: measure(number).width)
            #let ike-leader = box(width: 1fr, move(dx: GAP, box(width: 100% + LEAD, align(right, repeat(justify: false, gap: DOT_GAP)[.]))))
            #let ike-entry(marks, depth, mark, dots, target, body, page) = context {
              let weight(d) = if marks.len() > 1 and d == 0 { "bold" } else { "regular" }
              let chief = marks.len() > 1 and depth == 0
              let columns = marks.enumerate().map(((d, m)) => if m == "" { 0pt } else { measure(text(weight: weight(d), m)).width + MARK_GAP.to-absolute() })
              let edge = columns.slice(0, depth).map(c => if c > 0pt { c } else { STEP.to-absolute() }).sum(default: 0pt)
              set text(weight: weight(depth))
              let row = grid(columns: (columns.at(depth), 1fr, calc.max(measure(text(weight: weight(0))[ROOM]).width, measure(text(weight: weight(depth), page)).width)),
                column-gutter: (0pt, GUTTER), align: (left + top, left + top, bottom + right),
                if mark == none { [] } else { box(width: columns.at(depth) - MARK_GAP.to-absolute(), align(right, mark)) },
                box(width: 100% - GAP - LEAD, par(justify: false,
                  text(hyphenate: false)[#body#if dots { text(weight: "regular", ike-leader) } else { box(width: 1fr) }])),
                page)
              block(width: 100%, breakable: false, above: if chief { GROUP } else { 0.65em }, below: 0.65em, inset: (left: edge),
                if target != none and query(target).len() > 0 { link(target, row) } else { row })
            }
            """.replace("MARK_GAP", ems(ENTRY_MARK_GAP)).replace("DOT_GAP", ems(ENTRY_DOT_GAP)).replace("GAP", ems(ENTRY_GAP))
            .replace("LEAD", ems(ENTRY_LEAD)).replace("GUTTER", ems(ENTRY_GUTTER)).replace("STEP", ems(ENTRY_STEP))
            .replace("GROUP", ems(ENTRY_GROUP)).replace("ROOM", ENTRY_NUMBER_ROOM);

    /** A length in ems as Typst reads it. */
    private static String ems(double value) {
        return (value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value)) + "em";
    }

    /**
     * A drawn block: scaled to the measure, whole on a page, as {@link LayoutOracle.Unit#figureScale}
     * reckons it. The scale reflows, so the figure takes the room of its scaled marks: without
     * that Typst lays the marks' box at its unscaled size inside the scaled one, and a figure
     * scaled to fit a page still spilled it, landing on a page of its own in the printed document
     * while the measured one (whose figure body is empty) kept it in the flow — six pages'
     * difference over the regulatory book (2026-11-09).
     */
    private static String figureHelper(PageSetup setup) {
        return "#let ike-figure(w, h, body) = context {\n"
                + "  let s = calc.min(1, (page.width - " + points(setup.marginLeft() + setup.marginRight()) + ") / w, (page.height - "
                + points(setup.marginTop() + setup.marginBottom()) + ") / h)\n"
                + "  box(width: w * s, height: h * s, scale(x: s * 100%, y: s * 100%, origin: top + left, reflow: true, body))\n}\n";
    }

    private static void body(DocumentModel document, Labels labels, StringBuilder out) {
        int index = 0;
        if (!document.title().isEmpty()) {
            if (labels != Labels.NONE) {
                out.append(marker(index++));
            }
            // the title's ties as text and as markup: the helper finds its narrowest measure from the widest tied piece
            String tied = TitleLines.tie(document.title());
            out.append("#ike-title(").append(string(InlineText.of(tied).text()));
            // the author and the date beneath it, smaller: part of the title's block, as the title page sets them
            DocumentModel.Byline byline = document.byline();
            if (!byline.author().isEmpty()) {
                out.append(", author: [").append(markup(byline.author())).append("]");
            }
            if (!byline.date().isEmpty()) {
                out.append(", date: [").append(markup(byline.date())).append("]");
            }
            out.append(")[").append(markup(tied)).append("]\n\n");
        }
        Block previous = null;
        boolean opened = false;                                   // whether the body has been opened
        for (Block block : document.blocks()) {
            boolean list = block instanceof DocumentModel.FrontList;
            if (list || previous instanceof DocumentModel.FrontList || previous instanceof DocumentModel.Abstract) {
                // each front list opens a page, and so does the body after the last of them, or after an abstract with no
                // list: the title page stands alone, with the abstract when there is one;
                // weak, so a page already empty (a document with no title) is not left blank
                out.append(PAGE_BREAK);
                if (!list && !opened) {
                    out.append(BODY_START);                       // the body's first page is page 1
                    opened = true;
                }
            }
            if (labels != Labels.NONE) {
                out.append(marker(index++));
            }
            block(block, out, "");
            previous = block;
        }
        if (labels != Labels.NONE) {
            out.append("#metadata(none) <").append(END_LABEL).append(">\n");
        }
    }

    /** The page break before a front list and before the body that follows the lists or the abstract: see {@link #body}. */
    public static final String PAGE_BREAK = "#pagebreak(weak: true)\n\n";

    /**
     * The label the title's helper sets on the title's page: with every level-1 heading, a page
     * it stands on opens something and carries no running head ({@link RunningHeads}).
     */
    public static final String OPENER_LABEL = "ike-opener";

    /**
     * Whether the page being laid out opens something: the title stands on it, or a level-1
     * heading (a part, a top-level section, a front list) begins on it. Asked in the header's and
     * footer's context.
     */
    static final String OPENING_HELPER = "#let ike-opening() = {\n"
            + "  let at = here().page()\n"
            + "  query(selector(heading.where(level: 1)).or(<" + OPENER_LABEL + ">)).any(it => it.location().page() == at)\n"
            + "}\n";

    /** The least room between the running head's title and what stands to its right, in ems. */
    public static final double HEAD_GAP = 1.5;

    /** The fewest words of a title the running head shows cut short: a title that cannot keep so many is left out. */
    public static final int HEAD_WORDS = 3;

    /**
     * The running head's title, short enough to stand on one line beside what is to its right:
     * the title whole where it fits the room, else as many of its words from the first as fit
     * with an ellipsis after them, {@link #HEAD_WORDS} at least, else nothing. A long title set
     * in the room a long section left it once broke over two lines, and over four, hyphenated
     * and justified (the regulatory book, 2026-11-10); the section a page is in says more about
     * the page than the end of the book's title, and a word or two of a title says nothing. The
     * pages cut the title the same way ({@link RunningHeads#fit}).
     */
    static final String HEAD_HELPER = """
            #let ike-fit(title, room) = {
              let words = title.split(" ")
              let n = words.len()
              let shown = title
              while n > WORDS and measure(shown).width > room {
                n -= 1
                shown = words.slice(0, n).join(" ") + "…"
              }
              if measure(shown).width > room { "" } else { shown }
            }
            """.replace("WORDS", Integer.toString(HEAD_WORDS));

    /** The label at the top of the body's first page, in a document with front matter: where {@link #folioHelper} counts from. */
    public static final String BODY_LABEL = "ike-body";

    /**
     * What stands at the top of the body's first page, after the page break that ends the front
     * matter: the label the folios are counted from, and the page counter set to 1, so a front
     * list's entry, which asks the counter at its target, prints the target's folio.
     */
    public static final String BODY_START = "#metadata(none) <" + BODY_LABEL + ">\n#counter(page).update(1)\n";

    /** The label on the folio every page of a document with front matter prints, carrying the folio's text: see {@link #folioHelper}. */
    public static final String FOLIO_LABEL = "ike-folio";

    /**
     * The folio of a page in a document with front matter, as {@link Folios} reckons it for the
     * Page layout: lowercase roman before the body, none on a title page, arabic from 1 in the
     * body. The folio is reckoned from the page's place and the place of the body's first page
     * ({@link #BODY_LABEL}), not read from the page counter: Typst lays a running head out
     * before the page's content, so on the body's first page a head that asked the counter
     * would still print the front matter's count (14 over a footer's 1, typst 0.15.1).
     *
     * <p>Each page's folio stands beside a {@code metadata} element labelled
     * {@link #FOLIO_LABEL} that carries its text, empty for a title page, so what every page
     * prints can be asked of Typst: {@code query(<ike-folio>).map(it => it.value)}.
     *
     * @param titlePage whether the first page is a title page, which shows no number
     * @return the helper's definition
     */
    static String folioHelper(boolean titlePage) {
        return "#let ike-folio = context {\n"
                + "  let body = query(<" + BODY_LABEL + ">)\n"
                + "  let at = here().page()\n"
                + "  let folio = if body.len() == 0 or at < body.first().location().page() {\n"
                + (titlePage ? "    if at > 1 { numbering(\"i\", at) } else { \"\" }\n" : "    numbering(\"i\", at)\n")
                + "  } else {\n"
                + "    numbering(\"1\", at - body.first().location().page() + 1)\n"
                + "  }\n"
                + "  [#metadata(folio) <" + FOLIO_LABEL + ">#folio]\n"
                + "}\n";
    }

    /** The label on a block a front list's entry names, with its leading space; empty for every other block. */
    private static String target(String key) {
        return !key.isEmpty() && TARGETS.get().contains(key) ? " <" + TARGET_LABEL + key + ">" : "";
    }

    /** A front list: its title as a heading, then its entries. */
    private static void frontList(DocumentModel.FrontList list, StringBuilder out, String indent) {
        out.append(indent).append("= ").append(markup(list.title())).append("\n\n");
        // the widest mark of each depth, by which the entry block sizes that depth's column of marks
        List<String> patterns = FrontLists.patterns(list.entries().stream().map(DocumentModel.Entry::text).toList(),
                list.entries().stream().map(DocumentModel.Entry::depth).toList());
        out.append(indent).append("#let ike-marks = (");
        for (String pattern : patterns) {
            out.append('"').append(pattern).append("\", ");
        }
        out.append(")\n");
        for (DocumentModel.Entry entry : list.entries()) {
            // the measured form holds the room of the number the last pass found, and no dots: the oracle reads neither as words
            String page = CHUNK_WORDS.get() ? "#ike-page-room([" + entry.page() + "])" : "#ike-page(<" + TARGET_LABEL + entry.target() + ">)";
            String mark = FrontLists.mark(entry.text());
            String text = mark.isEmpty() ? entry.text() : entry.text().substring(mark.length() + 1);
            out.append(indent).append("#ike-entry(ike-marks, ").append(Math.max(0, entry.depth())).append(", ")
                    .append(mark.isEmpty() ? "none" : '"' + mark + '"').append(", ").append(!CHUNK_WORDS.get())
                    .append(", ").append(CHUNK_WORDS.get() ? "none" : "<" + TARGET_LABEL + entry.target() + ">")
                    .append(")[").append(markup(tied(InlineText.of(text, NOTES.get())))).append("][").append(page).append("]\n");
        }
        out.append('\n');
    }

    /** An entry's text with its short last word tied to the word before it ({@link FrontLists#tied}): its spans stand where they did. */
    private static InlineText tied(InlineText entry) {
        return new InlineText(FrontLists.tied(entry.text()), entry.runs(), entry.notes(), entry.offsets(), entry.anchors());
    }

    /** The page setup and the styles every construct relies on, for a document with no front matter. */
    static String preamble(String title, PageSetup setup) {
        return preamble(title, setup, false, false);
    }

    /**
     * The page setup and the styles every construct relies on.
     *
     * @param title       the document title
     * @param setup       the page
     * @param frontMatter whether the document has front matter, numbered in roman before a body that starts at 1
     * @param titlePage   whether its first page is a title page, which shows no number
     */
    static String preamble(String title, PageSetup setup, boolean frontMatter, boolean titlePage) {
        String t = string(title);
        // a document with no front matter is the source it always was
        String folio = frontMatter ? "ike-folio" : "counter(page).display(\"1\")";
        StringBuilder sb = new StringBuilder(2048);
        sb.append("// Written by Komet's stamped document surface — do not edit; the source is the AsciiDoc in git.\n");
        sb.append("#set document(title: ").append(t).append(")\n");
        if (frontMatter && setup.folio() != Folio.NONE) {
            sb.append(folioHelper(titlePage));
        }
        boolean headerFolio = setup.folio() == Folio.HEADER_RIGHT;
        if (setup.runningHead() || headerFolio) {
            sb.append(OPENING_HELPER);
        }
        if (setup.runningHead()) {
            sb.append(HEAD_HELPER);
        }
        sb.append("#set page(\n");
        sb.append("  paper: ").append(string(setup.paper())).append(",\n");
        if (setup.landscape()) {
            sb.append("  flipped: true,\n");
        }
        sb.append("  margin: (top: ").append(points(setup.marginTop())).append(", bottom: ").append(points(setup.marginBottom()))
                .append(", left: ").append(points(setup.marginLeft())).append(", right: ").append(points(setup.marginRight())).append("),\n");
        if (setup.runningHead() || headerFolio) {
            // none on a page that opens something: the title's, a part's, a chapter's
            sb.append("  header: context if not ike-opening() {\n");
            sb.append("    set text(size: 0.8em, fill: luma(70))\n");
            sb.append("    show footnote: none\n");   // a heading's note is the heading's, not the running head's
            sb.append("    let section = query(selector(heading).before(here())).filter(h => h.level <= ").append(RUNNING_HEAD_DEPTH).append(")\n");
            sb.append("    let current = if section.len() > 0 { section.last().body } else { [] }\n");
            if (setup.runningHead()) {
                // one line, ragged: the title gives way to the section and the folio, never the other way about
                sb.append("    set par(justify: false)\n");
                String gap = ems(HEAD_GAP) + ".to-absolute()";
                if (headerFolio) {
                    sb.append("    layout(size => grid(columns: (1fr, auto), align: (left, right), [#ike-fit(").append(t)
                            .append(", size.width - measure(current).width - measure(").append(folio).append(").width - 1em.to-absolute() - ").append(gap)
                            .append(") #h(1em) #current], ").append(folio).append("))\n");
                } else {
                    sb.append("    layout(size => grid(columns: (1fr, auto), align: (left, right), ike-fit(").append(t)
                            .append(", size.width - measure(current).width - ").append(gap).append("), current))\n");
                }
            } else {
                sb.append("    align(right, ").append(folio).append(")\n");
            }
            if (setup.runningHead()) {
                sb.append("    v(-0.4em)\n    line(length: 100%, stroke: 0.4pt + luma(170))\n");
            }
            sb.append("  },\n");
        }
        if (setup.folio() == Folio.FOOTER_CENTER || setup.folio() == Folio.FOOTER_RIGHT) {
            sb.append("  footer: context align(").append(setup.folio() == Folio.FOOTER_CENTER ? "center" : "right")
                    .append(", text(size: 0.8em, fill: luma(70), ").append(folio).append(")),\n");
        } else if (headerFolio) {
            // a page that opens something drops its folio to the foot
            sb.append("  footer: context if ike-opening() { align(center, text(size: 0.8em, fill: luma(70), ").append(folio).append(")) },\n");
        }
        sb.append(")\n");
        sb.append("#set text(font: ").append(fonts(setup.bodyFonts())).append(", size: ").append(points(setup.fontSize()))
                .append(", lang: \"en\", hyphenate: true)\n");
        sb.append("""
                #set par(justify: true, leading: 0.65em, spacing: 1.1em)
                #set heading(numbering: none)
                #show heading: set text(weight: "bold", hyphenate: false)
                #show heading: set par(justify: false)
                """);
        // Absolute sizes: Typst's own heading rule already scales a level-1 heading by 1.4em and a level-2 by
        // 1.2em, and an em here would compound with it (a level-1 heading came out at 2.1 times the body).
        sb.append("#show heading.where(level: 1): set text(size: ").append(points(setup.fontSize() * 1.5)).append(")\n");
        sb.append("#show heading.where(level: 2): set text(size: ").append(points(setup.fontSize() * 1.25)).append(")\n");
        sb.append("#show heading.where(level: 3): set text(size: ").append(points(setup.fontSize() * 1.1)).append(")\n");
        sb.append("#show heading.where(level: 4): set text(size: ").append(points(setup.fontSize())).append(", style: \"italic\")\n");
        sb.append("""
                #show heading: it => { v(0.9em, weak: true); it; v(0.5em, weak: true) }
                """);
        // Relative, unlike the headings' sizes: raw text sits inside whatever is around it, and takes its size from that.
        sb.append("#show raw: set text(font: ").append(fonts(setup.monoFonts())).append(", size: ").append(RAW_RULE_EM).append("em)\n");
        sb.append("""
                #show raw.where(block: true): it => block(width: 100%, fill: luma(247), inset: 8pt, radius: 3pt, it)
                #set table(stroke: 0.5pt + luma(150), inset: 5pt, align: left + top)
                #show table.cell: set par(justify: false)
                #show table.cell: set text(hyphenate: false)
                #show table.cell.where(y: 0): set text(weight: "bold")
                #show figure.where(kind: table): set figure.caption(position: top)
                #show figure.caption: set text(size: 0.85em, weight: "bold")
                #show figure.caption: set align(left)
                #show figure.caption: it => block(sticky: true, it)
                #show figure.where(kind: table): set block(breakable: true)
                #set table.cell(breakable: false)
                #set figure(numbering: none)
                #show figure.where(kind: image): it => it.body
                #set list(indent: 1em)
                #set enum(indent: 1em)
                #set terms(hanging-indent: 1.5em, separator: [: ])

                #let ike-title(words, body, author: none, date: none) = block(below: 1.2em, width: 100%, {
                  [#metadata(none) <OPENER>]
                  block(below: 0pt, width: 100%, {
                    set text(size: 1.8em, weight: "bold", hyphenate: false)
                    set par(justify: false, linebreaks: "simple")
                    layout(size => {
                      let at(w) = block(width: w, align(center, body))
                      let floor = calc.max(0pt, ..words.split(" ").map(w => measure(w).width))
                      let lines = measure(at(size.width)).height
                      let (lo, hi) = (calc.min(floor, size.width), size.width)
                      for _ in range(STEPS) {
                        let mid = (lo + hi) / 2
                        if measure(at(mid)).height <= lines + 0.01pt { hi = mid } else { lo = mid }
                      }
                      align(center, at(hi))
                    })
                  })
                  set par(justify: false, linebreaks: "simple")
                  set text(hyphenate: false)
                  if author != none {
                    block(above: BYLINE_GAP, below: 0pt, width: 100%, align(center, text(size: AUTHOR_SCALE, author)))
                  }
                  if date != none {
                    block(above: if author != none { DATE_GAP } else { BYLINE_GAP }, below: 0pt, width: 100%,
                      align(center, text(size: DATE_SCALE, date)))
                  }
                })
                #let ike-block-title(body) = block(above: 1em, below: 0.5em, text(weight: "bold", size: 0.95em, body))
                #let ike-admonition-colors = (
                  NOTE: rgb("#2a5a8a"), TIP: rgb("#3b8c2f"), IMPORTANT: rgb("#b8860b"),
                  WARNING: rgb("#c25e00"), CAUTION: rgb("#b00020"),
                )
                #let ike-admonition(kind, body) = {
                  let c = ike-admonition-colors.at(kind, default: luma(90))
                  block(width: 100%, inset: (left: 10pt, right: 8pt, top: 6pt, bottom: 6pt),
                    stroke: (left: 2pt + c), fill: luma(248),
                    [#text(weight: "bold", size: 0.8em, fill: c, kind) \\ #body])
                }
                #let ike-example(title, body) = block(width: 100%, inset: 8pt, stroke: 0.5pt + luma(190), radius: 2pt,
                  [#if title != none [#text(weight: "bold", size: 0.9em, title) \\ ]#body])
                #let ike-sidebar(title, body) = block(width: 100%, inset: 8pt, fill: luma(244), radius: 2pt,
                  [#if title != none [#text(weight: "bold", size: 0.9em, title) \\ ]#body])
                #let ike-image(target, title) = block(width: 100%, inset: 6pt, stroke: 0.5pt + luma(190),
                  [#text(size: 0.85em, fill: luma(90))[Image: #raw(target)]#if title != none [ — #title]])

                """.replace("<OPENER>", "<" + OPENER_LABEL + ">").replace("range(STEPS)", "range(" + TitleLines.STEPS + ")")
                .replace("BYLINE_GAP", ems(BYLINE_GAP)).replace("DATE_GAP", ems(DATE_GAP))
                .replace("AUTHOR_SCALE", ems(AUTHOR_SCALE)).replace("DATE_SCALE", ems(DATE_SCALE)));
        return sb.toString();
    }

    private static String marker(int index) {
        return "#metadata(" + index + ") <" + BLOCK_LABEL + ">\n";
    }

    private static void blocks(List<Block> blocks, StringBuilder out, String indent) {
        for (Block block : blocks) {
            block(block, out, indent);
        }
    }

    private static void block(Block block, StringBuilder out, String indent) {
        switch (block) {
            case Heading h -> out.append(indent).append("=".repeat(Math.clamp(h.level(), 1, 6))).append(' ')
                    .append(markup(h.text())).append(target(h.key())).append("\n\n");
            case DocumentModel.FrontList f -> frontList(f, out, indent);
            case DocumentModel.Abstract a -> blocks(a.blocks(), out, indent);   // as it would be set anywhere: its place is what makes it the abstract
            case Paragraph p -> out.append(indent).append(markup(p.text()).replace("\n", "\n" + indent)).append("\n\n");
            case Admonition a -> {
                out.append(indent).append("#ike-admonition(").append(string(a.label())).append(")[\n");
                blocks(a.blocks(), out, indent + "  ");
                trimBlank(out);
                out.append('\n').append(indent).append("]\n\n");
            }
            case Verbatim v -> {
                // a titled diagram is a figure: the list of figures names it by the label on its title
                if (!v.title().isEmpty()) {
                    out.append(indent).append("#ike-block-title[").append(markup(v.title())).append("]").append(target(v.key())).append('\n');
                }
                String fence = fence(v.text());
                out.append(indent).append(fence).append('\n');
                for (String line : v.text().split("\n", -1)) {
                    out.append(indent).append(line).append('\n');
                }
                out.append(indent).append(fence).append(v.title().isEmpty() ? target(v.key()) : "").append("\n\n");
            }
            case ListBlock l -> {
                if (!l.title().isEmpty()) {
                    out.append(indent).append("#ike-block-title[").append(markup(l.title())).append("]\n");
                }
                for (Item item : l.items()) {
                    String marker = switch (l.kind()) {
                        case ORDERED -> "+ ";
                        case DESCRIPTION -> "/ " + markup(item.term()) + ": ";
                        case UNORDERED, BIBLIOGRAPHY -> "- ";
                    };
                    out.append(indent).append(marker).append(markup(item.text()).replace("\n", "\n" + indent + "  ")).append('\n');
                    if (!item.blocks().isEmpty()) {
                        StringBuilder nested = new StringBuilder();
                        blocks(item.blocks(), nested, indent + "  ");
                        trimBlank(nested);
                        out.append(nested).append('\n');
                    }
                }
                out.append('\n');
            }
            case Table t -> table(t, out, indent);
            case DocumentModel.Drawn d -> {
                double w = d.figure().width();
                double h = d.figure().height();
                // A captioned drawing is a figure, so the list of figures finds it; the caption is drawn in the
                // figure's own marks, beneath it, and the figure shows its body alone (see the preamble).
                boolean captioned = !d.caption().isEmpty();
                String open = captioned ? "#figure(kind: image, supplement: none, numbering: none, caption: [" + markup(d.caption()) + "], " : "#";
                String close = (captioned ? ")" : "") + target(d.key());
                if (CHUNK_WORDS.get()) {
                    // measured as a box of its size: its marks' words are not the document's, and its one word is its line
                    out.append(indent).append("#metadata(\"").append(LayoutOracle.FIGURE_WORD).append("\") <").append(TypstOracle.WORD_LABEL)
                            .append(">#metadata(false) <").append(TypstOracle.WORD_END_LABEL).append(">");
                    out.append(open).append("ike-figure(").append(points(w)).append(", ").append(points(h)).append(", [])").append(close).append("\n\n");
                } else {
                    out.append(indent).append(open).append("ike-figure(").append(points(w)).append(", ").append(points(h)).append(", [\n")
                            .append(network.ike.komet.document.DecisionFigureTypst.write(d.figure()))
                            .append(indent).append("])").append(close).append("\n\n");
                }
            }
            case Image i -> out.append(indent).append("#ike-image(").append(string(i.target())).append(", ")
                    .append(i.title().isEmpty() ? "none" : "[" + markup(i.title()) + "]").append(")").append(target(i.key())).append("\n\n");
            case Container c -> {
                switch (c.kind()) {
                    case EXAMPLE, SIDEBAR -> {
                        out.append(indent).append(c.kind() == Container.Kind.EXAMPLE ? "#ike-example(" : "#ike-sidebar(")
                                .append(c.title().isEmpty() ? "none" : "[" + markup(c.title()) + "]").append(")[\n");
                        blocks(c.blocks(), out, indent + "  ");
                        trimBlank(out);
                        out.append('\n').append(indent).append("]\n\n");
                    }
                    case QUOTE -> {
                        if (!c.title().isEmpty()) {
                            out.append(indent).append("#ike-block-title[").append(markup(c.title())).append("]\n");
                        }
                        out.append(indent).append("#quote(block: true)[\n");
                        blocks(c.blocks(), out, indent + "  ");
                        trimBlank(out);
                        out.append('\n').append(indent).append("]\n\n");
                    }
                    case OPEN -> {
                        if (!c.title().isEmpty()) {
                            out.append(indent).append("#ike-block-title[").append(markup(c.title())).append("]\n");
                        }
                        blocks(c.blocks(), out, indent);
                    }
                }
            }
        }
    }

    private static void table(Table t, StringBuilder out, String indent) {
        int columns = Math.max(1, t.columns());
        out.append(indent).append("#figure(\n");
        out.append(indent).append("  table(\n");
        out.append(indent).append("    columns: (");
        for (int i = 0; i < columns; i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(i < t.widths().size() ? t.widths().get(i) : 1).append("fr");
        }
        out.append("),\n");
        int header = Math.min(t.headerRows(), t.rows().size());
        if (header > 0) {
            out.append(indent).append("    table.header(repeat: true,\n");
            for (int r = 0; r < header; r++) {
                row(t.rows().get(r), t.spansOf(r), columns, false, true, out, indent + "      ");
            }
            out.append(indent).append("    ),\n");
        }
        for (int r = header; r < t.rows().size(); r++) {
            row(t.rows().get(r), t.spansOf(r), columns, overPage(t, r, columns), false, out, indent + "    ");
        }
        out.append(indent).append("  ),\n");
        if (!t.title().isEmpty()) {
            out.append(indent).append("  caption: [").append(markup(t.title())).append("],\n");
        }
        out.append(indent).append("  kind: table,\n");
        out.append(indent).append(")").append(target(t.key())).append("\n\n");
    }

    /**
     * The characters a row's cell may hold, reckoned at the full measure, before the row is let
     * break over a page: about half a page of body text, so the reckoning can run well short.
     */
    static final int WHOLE_ROW_CHARACTERS = 1500;

    /**
     * Whether a row may be too tall for a page. The preamble keeps rows whole, which is what the
     * page's one line a row relies on, but Typst runs a whole row taller than a page off its foot
     * and its text is lost, so such a row keeps Typst's own rule and breaks. How tall a cell sets
     * is Typst's to know, so this reckons from the text at the cell's share of the measure.
     */
    private static boolean overPage(Table t, int r, int columns) {
        List<String> cells = t.rows().get(r);
        List<Integer> weights = t.weightsOf(r);                  // a spanning cell has the room of its columns together
        int total = 0;
        for (int c = 0; c < columns; c++) {
            total += c < t.widths().size() ? t.widths().get(c) : 1;
        }
        for (int c = 0; c < Math.min(columns, cells.size()); c++) {
            int width = c < weights.size() ? weights.get(c) : 1;
            if ((long) cells.get(c).length() * total / width > WHOLE_ROW_CHARACTERS) {
                return true;
            }
        }
        return false;
    }

    /**
     * A note in a table's header row. The header is set again at the top of every page the table
     * runs on to, and Typst sets a footnote in it again each time, with the next number: a table
     * of three pages gave its one note three times, and moved every later note's number by two.
     * A note is given once, on the page its row first stands on; where the header is repeated the
     * mark stands again, pointing at the same note. The counter is the note's own, stepped each
     * time the header is set, so the first setting is the one that finds it at nothing.
     */
    static final String HEAD_NOTE_HELPER = "#let ike-head-note(n, body) = context { let key = \"ike-head-note-\" + str(n); "
            + "let again = counter(key).get().first() > 0; counter(key).step(); "
            + "if again { footnote(label(key)) } else [#footnote(body)#label(key)] }\n";

    /** The alignment of a header row's cells: column heads stand on their last lines, over the column they head. */
    static final String HEAD_CELL = "table.cell(align: left + bottom)[";

    /**
     * A row's cells. A body row's cells begin at the row's top, so a reader's eye runs along
     * the first lines of a row, the preamble's {@code align: left + top}; a header row's cells
     * stand on their last lines ({@link #HEAD_CELL}), a short head beside a head of two lines
     * sitting just above the rule under them. That is the usual rule of the style guides
     * (Chicago: column heads align on the bottom line, the cells of a row on their first).
     */
    private static void row(List<String> cells, List<Integer> spans, int columns, boolean breakable, boolean header, StringBuilder out, String indent) {
        out.append(indent);
        int filled = 0;
        for (int c = 0; filled < columns; c++) {
            String cell = c < cells.size() ? cells.get(c) : "";   // a short row is padded to the table's columns
            int span = c < spans.size() ? Math.clamp(spans.get(c), 1, columns - filled) : 1;
            filled += span;
            if (span > 1) {
                // a cell over several columns, a heading within the table for one: Typst's own cell says how many
                out.append("table.cell(colspan: ").append(span).append(header ? ", align: left + bottom" : breakable ? ", breakable: true" : "").append(")[");
            } else {
                out.append(header ? HEAD_CELL : breakable ? "table.cell(breakable: true)[" : "[");
            }
            out.append(markup(InlineText.of(cell, NOTES.get()), header)).append("], ");
        }
        out.append('\n');
    }

    /** Drops the trailing blank lines a block run leaves, so a closing bracket follows the content. */
    private static void trimBlank(StringBuilder sb) {
        while (!sb.isEmpty() && sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }
    }

    // ── text ──────────────────────────────────────────────────────────────────

    /** Prose as markup: its inline spans real Typst, the rest escaped, the document's notes numbered as read. */
    private static String markup(String text) {
        return markup(InlineText.of(text, NOTES.get()));
    }

    /** Printed text as markup. */
    static String markup(InlineText inline) {
        return markup(inline, false);
    }

    /**
     * Printed text as markup.
     *
     * @param head whether the text is a cell of a table's header row, where a note is given once however often the header is set
     */
    private static String markup(InlineText inline, boolean head) {
        String text = inline.text();
        StringBuilder out = new StringBuilder(text.length() + 32);
        int run = 0;
        int note = 0;
        int anchor = 0;
        int at = 0;
        while (at < text.length() || anchor < inline.anchors().size()) {
            if (anchor < inline.anchors().size() && inline.anchors().get(anchor).at() <= at) {
                out.append(anchor(inline.anchors().get(anchor++).id()));
                continue;
            }
            Run nextRun = run < inline.runs().size() ? inline.runs().get(run) : null;
            Note nextNote = note < inline.notes().size() ? inline.notes().get(note) : null;
            int next = Math.min(nextRun == null ? text.length() : nextRun.from(), nextNote == null ? text.length() : nextNote.from());
            if (anchor < inline.anchors().size()) {
                next = Math.min(next, Math.max(at, inline.anchors().get(anchor).at()));
            }
            if (next > at) {
                out.append(plain(text.substring(at, next)));
                at = next;
            } else if (nextNote != null && nextNote.from() == at) {
                out.append(head && !nextNote.repeat() && nextNote.id() == null
                        ? "#ike-head-note(" + nextNote.number() + ")[" + markup(nextNote.body()) + "];" : footnote(nextNote));
                at = nextNote.to();
                note++;
            } else if (nextRun != null) {
                out.append(span(text.substring(nextRun.from(), nextRun.to()), nextRun));
                at = nextRun.to();
                run++;
            }
        }
        return out.toString();
    }

    /** Plain text as markup: escaped, with a hard line break where the text breaks a line. */
    private static String plain(String text) {
        String out = escape(text).replace("\n", " \\\n");
        return CHUNK_WORDS.get() ? chunk(out) : out;
    }

    /**
     * A styled run: {@code raw}, {@code strong}, {@code emph} and {@code link} as functions, since
     * the marks of {@code *} and {@code _} do not open inside a word. Each ends in a semicolon,
     * so that what follows cannot read as more of the expression ("{@code #raw("x").foo}").
     */
    private static String span(String text, Run run) {
        String body = run.mono() ? "#raw(" + string(text.replace('\n', ' ')) + ");" : plain(text);
        if (run.italic()) {
            body = "#emph[" + body + "];";
        }
        if (run.bold()) {
            body = "#strong[" + body + "];";
        }
        // a line under or through a phrase and the colour behind one draw over the text and take no room of their own
        if (run.underline()) {
            body = "#underline[" + body + "];";
        }
        if (run.strike()) {
            body = "#strike[" + body + "];";
        }
        if (run.highlight()) {
            body = "#highlight(fill: rgb(\"#fff3a3\"))[" + body + "];";
        }
        if (run.href() == null) {
            return body;
        }
        if (run.href().startsWith(InlineText.INTERNAL) && LABEL_NAME.matcher(run.href().substring(1)).matches()) {
            return "#ike-ref(<" + TARGET_LABEL + run.href().substring(1) + ">)[" + body + "];";
        }
        return "#link(" + string(run.href()) + ")[" + body + "];";
    }

    /** What a label's name may hold; an anchor or a link's target with anything else is not written as a label. */
    private static final java.util.regex.Pattern LABEL_NAME = java.util.regex.Pattern.compile("[A-Za-z0-9_:.-]+");

    /** A link into the document as its reading wrote it: {@code link:#b1234[text]}. */
    private static final java.util.regex.Pattern INTERNAL_LINK = java.util.regex.Pattern.compile("link:#([A-Za-z0-9_:.-]+)\\[");

    /** The colour of a link into the document, on paper as on the screen. */
    public static final String LINK_COLOUR = "#2a5db0";

    /**
     * A link into the document: to the block its target names, in the link's colour, where the
     * document has that block, and the plain text where it has not. The link is to the first
     * place the label stands, since a file shown twice stands its anchors twice.
     */
    static final String REF_HELPER = "#let ike-ref(target, body) = context { let found = query(target); "
            + "if found.len() > 0 { link(found.first().location(), text(fill: rgb(\"" + LINK_COLOUR + "\"), body)) } else { body } }\n";

    /** An anchor in the text: nothing on the page, and a place a link points at. */
    private static String anchor(String id) {
        return LABEL_NAME.matcher(id).matches() ? "#metadata(none)<" + TARGET_LABEL + id + ">" : "";
    }

    /** Every text of a block that is read for its inline markup, its inner blocks' among them. */
    private static void texts(Block block, java.util.function.Consumer<String> to) {
        switch (block) {
            case Heading h -> to.accept(h.text());
            case Paragraph p -> to.accept(p.text());
            case Admonition a -> a.blocks().forEach(inner -> texts(inner, to));
            case Verbatim v -> to.accept(v.title());
            case ListBlock l -> {
                to.accept(l.title());
                for (Item item : l.items()) {
                    to.accept(item.term());
                    to.accept(item.text());
                    item.blocks().forEach(inner -> texts(inner, to));
                }
            }
            case Table t -> {
                to.accept(t.title());
                t.rows().forEach(row -> row.forEach(to));
            }
            case Image i -> to.accept(i.title());
            case DocumentModel.Drawn d -> to.accept(d.caption());
            case Container c -> {
                to.accept(c.title());
                c.blocks().forEach(inner -> texts(inner, to));
            }
            case DocumentModel.Abstract a -> a.blocks().forEach(inner -> texts(inner, to));
            case DocumentModel.FrontList f -> f.entries().forEach(entry -> to.accept(entry.text()));
        }
    }

    /** A footnote; a named one labelled, so a later mark can point at it. */
    private static String footnote(Note note) {
        if (note.repeat()) {
            return "#footnote(<" + note.id() + ">);";
        }
        String body = "#footnote[" + markup(note.body()) + "]";
        return note.id() == null ? body + ";" : body + "<" + note.id() + ">";
    }

    /** The document's footnote count while it is written: {@link #write} sets it. */
    private static final ThreadLocal<InlineText.Numbering> NOTES = ThreadLocal.withInitial(InlineText.Numbering::new);

    /** Whether paragraphs are being written for the word-labelled measurement: see {@link #chunk}. */
    private static final ThreadLocal<Boolean> CHUNK_WORDS = ThreadLocal.withInitial(() -> false);

    /** The keys of the blocks the document's front lists name, which carry a label while it is written: {@link #write} sets it. */
    private static final ThreadLocal<java.util.Set<String>> TARGETS = ThreadLocal.withInitial(java.util.Set::of);
    /** Words per text element in the word-labelled form. */
    static final int CHUNK = 120;

    /**
     * The word-labelling show rule wraps every match of one text element in content of its own,
     * and typst realizes those as nested groups: a paragraph of some hundred words exceeds its
     * grouping depth. A zero-width space ({@code #h(0pt)}) after every {@value #CHUNK}th word ends
     * the text element there without adding a glyph, a space or a break opportunity the following
     * space does not already give, so each element stays shallow; the layout is pixel-identical.
     */
    static String chunk(String markup) {
        StringBuilder out = new StringBuilder(markup.length() + 64);
        int words = 0;
        for (int i = 0; i < markup.length(); i++) {
            char c = markup.charAt(i);
            if (c == ' ' && i > 0 && markup.charAt(i - 1) != ' ' && markup.charAt(i - 1) != '\\') {
                if (++words % CHUNK == 0) {
                    out.append("#h(0pt)");
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private static String points(double value) {
        return (value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value)) + "pt";
    }

    private static String fonts(List<String> families) {
        if (families.size() == 1) {
            return string(families.getFirst());
        }
        List<String> quoted = new ArrayList<>();
        for (String f : families) {
            quoted.add(string(f));
        }
        return "(" + String.join(", ", quoted) + ")";
    }

    /**
     * A Typst string literal.
     *
     * @param plain the plain text
     * @return the quoted, escaped literal
     */
    public static String string(String plain) {
        StringBuilder sb = new StringBuilder(plain.length() + 2);
        sb.append('"');
        for (int i = 0; i < plain.length(); i++) {
            char c = plain.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /**
     * A raw block fence the content cannot close: one more backtick than the longest run
     * inside, never fewer than three.
     *
     * @param content the raw content
     * @return the fence
     */
    public static String fence(String content) {
        int longest = 0;
        int run = 0;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '`') {
                run++;
                longest = Math.max(longest, run);
            } else {
                run = 0;
            }
        }
        return "`".repeat(Math.max(3, longest + 1));
    }

    /**
     * Escapes plain text for Typst markup: the characters that open expressions, styling,
     * math, labels, references and comments, the shorthands ({@code --}, {@code -?},
     * {@code ...}), a {@code ;}, and the line-leading characters that start lists, headings
     * and terms.
     *
     * @param plain plain text
     * @return escaped markup text
     */
    public static String escape(String plain) {
        StringBuilder sb = new StringBuilder(plain.length() + 8);
        boolean lineStart = true;
        for (int i = 0; i < plain.length(); i++) {
            char c = plain.charAt(i);
            if (lineStart && c != ' ' && c != '\t') {
                if (startsBlockMarker(plain, i)) {
                    sb.append('\\');
                }
                lineStart = false;
            }
            char next = i + 1 < plain.length() ? plain.charAt(i + 1) : 0;
            char prev = i > 0 ? plain.charAt(i - 1) : 0;
            switch (c) {
                case '\\', '#', '*', '_', '`', '$', '<', '>', '@', '[', ']', '~', ';' -> sb.append('\\').append(c);
                case '-' -> {
                    if (next == '-' || next == '?' || prev == '-') {
                        sb.append("\\-");
                    } else {
                        sb.append(c);
                    }
                }
                case '.' -> {
                    if (next == '.' && i + 2 < plain.length() && plain.charAt(i + 2) == '.') {
                        sb.append("\\.");
                    } else {
                        sb.append(c);
                    }
                }
                case '/' -> {
                    if (next == '/' || next == '*') {
                        sb.append("\\/");
                    } else {
                        sb.append(c);
                    }
                }
                case '\n' -> {
                    sb.append(c);
                    lineStart = true;
                }
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Whether the text at {@code i} (a line's first non-blank) would start a list item, heading or term. */
    private static boolean startsBlockMarker(String s, int i) {
        char c = s.charAt(i);
        boolean spaceAfter = i + 1 < s.length() && (s.charAt(i + 1) == ' ' || s.charAt(i + 1) == '\t');
        if ((c == '-' || c == '+' || c == '/') && (spaceAfter || i + 1 == s.length())) {
            return true;
        }
        if (c == '=') {
            return true;
        }
        if (Character.isDigit(c)) {
            int j = i;
            while (j < s.length() && Character.isDigit(s.charAt(j))) {
                j++;
            }
            return j < s.length() && s.charAt(j) == '.' && (j + 1 == s.length() || s.charAt(j + 1) == ' ');
        }
        return false;
    }
}

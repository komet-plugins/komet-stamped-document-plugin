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

import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableBody;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.LinkReferenceDefinition;
import org.commonmark.node.ListBlock;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns Markdown, as the assistant writes it, into AsciiDoc in the IKE dialect, so a conversation
 * saved to a file is an IKE Document (IKE-Network/ike-issues#1313; the second question before step 3
 * of {@code design-2026-11-22-knowledge-shared-local}, settled by KEC on 2026-11-23).
 *
 * <p>Headings become sections below the section the text is placed in, numbered by rank so no level
 * is skipped; paragraphs stay paragraphs, line for line; lists keep their nesting, with {@code +}
 * attaching a list item's further blocks; fenced code becomes a listing with its language, so a
 * {@code koncept-tree} keeps its name; GFM tables become tables with a header row; a quotation
 * becomes a quote block. Koncept tokens ({@code k:sctid=…[Name]}) and bare URLs are written as they
 * are, since AsciiDoc reads them the same way.
 *
 * <p>Text that AsciiDoc would read as markup it was never meant to be is kept literal. A line that
 * would open a block (a list marker, a block title, an attribute entry, a delimiter) starts with
 * {@code {empty}}, a description-list marker is written with a character reference, and a run whose
 * marks could pair up, such as two {@code *} or an attribute reference, is a passthrough.
 */
public final class MarkdownToAsciiDoc {

    private static final Parser PARSER = Parser.builder().extensions(List.of(TablesExtension.create())).build();

    /** The deepest section AsciiDoc has: {@code ======}. */
    private static final int DEEPEST = 5;

    /** Koncept tokens and bare URLs, written as they are. */
    private static final Pattern KEPT = Pattern.compile(
            "k:(?:sctid|uuid|id|nid)=[0-9a-fA-F-]+(?:\\[[^\\[\\]]*])?|https?://[^\\s\\[\\]<>)]+");

    /** What could make AsciiDoc read a run of text as markup. */
    private static final Pattern MARKUP = Pattern.compile(
            "\\{[A-Za-z0-9_][A-Za-z0-9_-]*}|\\[\\[|<<|\\(\\(|\\+$"
                    + "|\\b(?:link|xref|footnote|footnoteref|pass|kbd|btn|menu|image|indexterm|anchor|stem|latexmath|asciimath|mailto):");

    /** Superscript and subscript: a mark, characters with no space, the mark again. */
    private static final Pattern SCRIPT = Pattern.compile("\\^[^\\s^]+\\^|~[^\\s~]+~");

    /** A line AsciiDoc would read as the start of a block rather than as text. */
    private static final Pattern BLOCK_START = Pattern.compile(
            "^(?:=+\\s|\\.[^\\s.]|\\[.*]$|:[A-Za-z0-9_][A-Za-z0-9_-]*!?:(?:\\s|$)|(?:NOTE|TIP|WARNING|IMPORTANT|CAUTION):\\s"
                    + "|\\s*(?:\\*+|-)\\s|\\s*(?:\\.+|\\d+\\.)\\s|//|\\+$|\\|"
                    + "|-{4,}$|\\.{4,}$|={4,}$|_{4,}$|\\*{4,}$|--$|/{4,}$|\\+{4,}$|'{3,}$|<{3,}$|\\w+::\\S*\\[.*]$)");

    /** A description-list marker: two or more colons before a space or the end of the line. */
    private static final Pattern DESCRIPTION_MARKER = Pattern.compile("(?<=\\S)(::+)(?=\\s|$)");

    private final Map<Integer, Integer> levels = new TreeMap<>();
    private final int sectionLevel;

    private MarkdownToAsciiDoc(int sectionLevel) {
        this.sectionLevel = sectionLevel;
    }

    /**
     * Converts Markdown to AsciiDoc.
     *
     * @param markdown     the Markdown
     * @param sectionLevel the level of the section the text is placed in, 1 for {@code ==}; its
     *                     headings become sections below it
     * @return the AsciiDoc, its lines joined with {@code \n}, with no blank line at either end
     */
    public static String convert(String markdown, int sectionLevel) {
        Node document = PARSER.parse(markdown == null ? "" : markdown);
        MarkdownToAsciiDoc converter = new MarkdownToAsciiDoc(sectionLevel);
        converter.rankHeadings(document);
        List<String> lines = converter.blocks(document);
        while (!lines.isEmpty() && lines.getLast().isBlank()) {
            lines.removeLast();
        }
        return String.join("\n", lines);
    }

    // ── headings ─────────────────────────────────────────────────────────────

    /** Numbers the heading levels the text uses, so the first becomes the section just below its own. */
    private void rankHeadings(Node document) {
        TreeSet<Integer> used = new TreeSet<>();
        collectHeadings(document, used);
        int rank = 0;
        for (int level : used) {
            levels.put(level, Math.min(sectionLevel + 1 + rank++, DEEPEST));
        }
    }

    private static void collectHeadings(Node node, TreeSet<Integer> used) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Heading heading) {
                used.add(heading.getLevel());
            }
            collectHeadings(child, used);
        }
    }

    // ── blocks ───────────────────────────────────────────────────────────────

    /** The blocks of a container, separated by blank lines; two lists in a row are kept apart. */
    private List<String> blocks(Node container) {
        List<String> lines = new ArrayList<>();
        Node previous = null;
        for (Node child = container.getFirstChild(); child != null; child = child.getNext()) {
            List<String> block = block(child, 0, 0);
            if (block.isEmpty()) {
                continue;
            }
            if (!lines.isEmpty()) {
                lines.add("");
                if (previous instanceof ListBlock && child instanceof ListBlock) {
                    lines.add("//-");                          // else AsciiDoc joins the two lists
                    lines.add("");
                }
            }
            lines.addAll(block);
            previous = child;
        }
        return lines;
    }

    /**
     * One block's lines.
     *
     * @param node      the block
     * @param bullets   how deep in unordered lists it is
     * @param numbers   how deep in ordered lists it is
     */
    private List<String> block(Node node, int bullets, int numbers) {
        List<String> lines = new ArrayList<>();
        switch (node) {
            case Heading heading -> {
                int level = levels.getOrDefault(heading.getLevel(), Math.min(sectionLevel + 1, DEEPEST));
                lines.add("=".repeat(level + 1) + " " + inline(heading).replace('\n', ' '));
            }
            case Paragraph paragraph -> lines.addAll(textLines(inline(paragraph)));
            case BulletList list -> list(list, bullets + 1, numbers, lines);
            case OrderedList list -> list(list, bullets, numbers + 1, lines);
            case FencedCodeBlock code -> listing(code.getInfo(), code.getLiteral(), lines);
            case IndentedCodeBlock code -> listing("", code.getLiteral(), lines);
            case BlockQuote quote -> {
                lines.add("____");
                lines.addAll(blocks(quote));
                lines.add("____");
            }
            case ThematicBreak ignored -> lines.add("'''");
            case TableBlock table -> table(table, lines);
            case HtmlBlock html -> listing("", html.getLiteral(), lines);
            case LinkReferenceDefinition ignored -> { }
            default -> {
                String text = inline(node);
                if (!text.isBlank()) {
                    lines.addAll(textLines(text));
                }
            }
        }
        return lines;
    }

    private void list(ListBlock list, int bullets, int numbers, List<String> lines) {
        boolean ordered = list instanceof OrderedList;
        String marker = ordered ? ".".repeat(Math.max(numbers, 1)) : "*".repeat(Math.max(bullets, 1));
        if (list instanceof OrderedList numbered) {
            Integer start = numbered.getMarkerStartNumber();
            if (start != null && start != 1) {
                lines.add("[start=" + start + "]");
            }
        }
        for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
            if (item instanceof ListItem listItem) {
                item(listItem, marker, bullets, numbers, lines);
            }
        }
    }

    /** A list item: its first paragraph after the marker, a nested list next, any other block attached with {@code +}. */
    private void item(ListItem item, String marker, int bullets, int numbers, List<String> lines) {
        boolean first = true;
        for (Node child = item.getFirstChild(); child != null; child = child.getNext()) {
            if (first) {
                first = false;
                if (child instanceof Paragraph paragraph) {
                    List<String> text = textLines(inline(paragraph));
                    lines.add(marker + " " + text.getFirst());
                    lines.addAll(text.subList(1, text.size()));
                    continue;
                }
                lines.add(marker + " {empty}");
            }
            if (child instanceof ListBlock nested) {
                lines.addAll(block(nested, bullets, numbers));
            } else {
                lines.add("+");
                lines.addAll(block(child, bullets, numbers));
            }
        }
        if (first) {
            lines.add(marker + " {empty}");
        }
    }

    /** A listing block, with its language when it has one; a delimiter longer than any line of the code like it. */
    private static void listing(String info, String literal, List<String> lines) {
        String language = info == null ? "" : info.strip().split("\\s+")[0];
        List<String> code = new ArrayList<>(List.of(literal.replace("\r\n", "\n").split("\n", -1)));
        if (!code.isEmpty() && code.getLast().isEmpty()) {
            code.removeLast();
        }
        String delimiter = "----";
        while (code.contains(delimiter)) {
            delimiter += "-";
        }
        lines.add(language.isEmpty() ? "[listing]" : "[source," + language + "]");
        lines.add(delimiter);
        lines.addAll(code);
        lines.add(delimiter);
    }

    private void table(TableBlock table, List<String> lines) {
        List<List<String>> head = new ArrayList<>();
        List<List<String>> body = new ArrayList<>();
        for (Node part = table.getFirstChild(); part != null; part = part.getNext()) {
            List<List<String>> rows = part instanceof TableHead ? head : body;
            if (part instanceof TableHead || part instanceof TableBody) {
                for (Node row = part.getFirstChild(); row != null; row = row.getNext()) {
                    if (row instanceof TableRow) {
                        List<String> cells = new ArrayList<>();
                        for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
                            if (cell instanceof TableCell) {
                                cells.add(inline(cell).replace('\n', ' ').replace("|", "\\|"));
                            }
                        }
                        rows.add(cells);
                    }
                }
            }
        }
        if (!head.isEmpty()) {
            lines.add("[%header]");
        }
        lines.add("|===");
        for (List<String> row : head) {
            lines.add(row(row));
        }
        for (List<String> row : body) {
            lines.add(row(row));
        }
        lines.add("|===");
    }

    private static String row(List<String> cells) {
        StringBuilder row = new StringBuilder();
        for (String cell : cells) {
            if (!row.isEmpty()) {
                row.append(' ');
            }
            row.append('|').append(cell);
        }
        return row.toString();
    }

    /** A paragraph's lines, each kept from being read as the start of a block. */
    private static List<String> textLines(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            String kept = DESCRIPTION_MARKER.matcher(line).replaceAll(m -> "&#58;" + m.group(1).substring(1));
            lines.add(BLOCK_START.matcher(kept).lookingAt() ? "{empty}" + kept : kept);
        }
        if (lines.isEmpty()) {
            lines.add("");
        }
        return lines;
    }

    // ── inline ───────────────────────────────────────────────────────────────

    /** The inline content of a node. */
    private String inline(Node node) {
        StringBuilder out = new StringBuilder();
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            inline(child, out);
        }
        return out.toString();
    }

    private void inline(Node node, StringBuilder out) {
        switch (node) {
            case Text text -> text(text.getLiteral(), out);
            case SoftLineBreak ignored -> out.append('\n');
            case HardLineBreak ignored -> out.append(" +\n");
            case Emphasis emphasis -> marked("_", inline(emphasis), node, out);
            case StrongEmphasis strong -> marked("*", inline(strong), node, out);
            case Code code -> monospace(code.getLiteral(), node, out);
            case Link link -> link(link.getDestination(), inline(link), out);
            case Image image -> out.append("image:").append(image.getDestination()).append('[')
                    .append(bracketed(inline(image))).append(']');
            case HtmlInline html -> text(html.getLiteral(), out);
            default -> out.append(inline(node));
        }
    }

    /** Text as it is, with any run whose characters AsciiDoc could read as markup kept literal. */
    private static void text(String literal, StringBuilder out) {
        Matcher kept = KEPT.matcher(literal);
        int at = 0;
        while (kept.find()) {
            plain(literal.substring(at, kept.start()), out);
            out.append(kept.group());
            at = kept.end();
        }
        plain(literal.substring(at), out);
    }

    private static void plain(String run, StringBuilder out) {
        if (run.isEmpty()) {
            return;
        }
        if (!couldBeMarkup(run) || run.contains("++")) {
            out.append(run);
            return;
        }
        out.append("++").append(run).append("++");
    }

    /**
     * Whether a run holds marks that could pair up, by Asciidoctor's rules, or anything else AsciiDoc
     * reads as markup. A constrained mark opens only after a character that is not part of a word and
     * before one that is not a space, and closes only after one that is not a space and before one that
     * is not part of a word, so {@code C# and F#} and {@code snake_case} stay text; a doubled mark pairs
     * anywhere; superscript and subscript hold no space.
     */
    static boolean couldBeMarkup(String run) {
        for (char mark : new char[] {'*', '_', '`', '#'}) {
            String doubled = "" + mark + mark;
            int first = run.indexOf(doubled);
            if (first >= 0 && run.indexOf(doubled, first + 2) >= 0) {
                return true;
            }
            boolean opened = false;
            for (int i = 0; i < run.length(); i++) {
                if (run.charAt(i) != mark) {
                    continue;
                }
                char before = i == 0 ? ' ' : run.charAt(i - 1);
                char after = i + 1 < run.length() ? run.charAt(i + 1) : ' ';
                if (opened && !Character.isWhitespace(before) && !Character.isLetterOrDigit(after)) {
                    return true;
                }
                if (!Character.isLetterOrDigit(before) && !Character.isWhitespace(after)) {
                    opened = true;
                }
            }
        }
        return SCRIPT.matcher(run).find() || MARKUP.matcher(run).find();
    }

    /**
     * Emphasis or strong text: constrained ({@code _x_}) where a word does not touch the marks,
     * unconstrained ({@code __x__}) inside a word.
     */
    private static void marked(String mark, String content, Node node, StringBuilder out) {
        boolean constrained = !wordBefore(out) && !wordAfter(node) && !content.isEmpty()
                && !Character.isWhitespace(content.charAt(0)) && !Character.isWhitespace(content.charAt(content.length() - 1));
        String marks = constrained ? mark : mark + mark;
        out.append(marks).append(content).append(marks);
    }

    /** Code: monospace, its text kept literal. */
    private static void monospace(String code, Node node, StringBuilder out) {
        boolean constrained = !wordBefore(out) && !wordAfter(node);
        String tick = constrained ? "`" : "``";
        String content;
        if (code.isEmpty()) {
            content = "{empty}";
        } else if (!couldBeMarkup(code) && code.indexOf('`') < 0 && code.indexOf('+') < 0) {
            content = code;
        } else if (!code.contains("+")) {
            content = "+" + code + "+";
        } else {
            content = "pass:c[" + code.replace("]", "\\]") + "]";
        }
        out.append(tick).append(content).append(tick);
    }

    private static void link(String destination, String text, StringBuilder out) {
        boolean web = destination.startsWith("http://") || destination.startsWith("https://") || destination.startsWith("mailto:");
        if (web && (text.isEmpty() || text.equals(destination))) {
            out.append(destination);
            return;
        }
        out.append(web ? "" : "link:").append(destination).append('[').append(bracketed(text)).append(']');
    }

    private static String bracketed(String text) {
        return text.replace("]", "\\]").replace('\n', ' ');
    }

    private static boolean wordBefore(StringBuilder out) {
        return !out.isEmpty() && Character.isLetterOrDigit(out.charAt(out.length() - 1));
    }

    private static boolean wordAfter(Node node) {
        return node.getNext() instanceof Text next && !next.getLiteral().isEmpty()
                && Character.isLetterOrDigit(next.getLiteral().charAt(0));
    }
}

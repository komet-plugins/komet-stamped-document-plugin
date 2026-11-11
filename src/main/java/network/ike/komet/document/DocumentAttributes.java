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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The attributes of a document at one of its lines (IKE-Network/ike-issues#1351): what
 * {@code {name}} reads as there, and what {@code ifdef::name[]} finds set. AsciiDoc reads a
 * document from its first line to its last, through every include, and an attribute entry holds
 * from its line on, so the attributes are a state that a {@link Walk} carries through the lines
 * in reading order, and each line keeps the state it was read in. A state is never changed: an
 * entry gives the lines after it a new one.
 *
 * <p>Three things are read from it. A reference, {@code {topics}}, is the entry's value; a
 * reference to nothing set stays as written, as AsciiDoc leaves it. A conditional directive,
 * {@code ifdef}, {@code ifndef} or {@code ifeval} to its {@code endif}, says whether the lines
 * between are read at all. And a value written as a passthrough, {@code pass:[<span
 * class="note">]}, is markup of the web page the document also makes, which a page of paper
 * cannot show; it is read by what the document's own style sheet says of it in print
 * ({@link PrintStyles}): an element the sheet floats to the foot of the page is a footnote, and
 * one it does not display is left out.
 *
 * <p>Komet is the backend: {@code backend} is {@code komet} and {@code backend-komet} is set,
 * and no other backend's name is. What a document keeps for a backend that makes an index is
 * not read here, where none is made.
 */
public final class DocumentAttributes {

    /** {@code :name: value}, {@code :name!:} and {@code :!name:}. */
    private static final Pattern ENTRY = Pattern.compile("^:(!?)([A-Za-z0-9_][A-Za-z0-9_-]*)(!?):(?:[ \\t]+(.*))?$");
    private static final Pattern PASS_VALUE = Pattern.compile("^pass:[a-z,]*\\[(.*)\\]$");
    private static final Pattern REFERENCE = Pattern.compile("(\\\\?)\\{([A-Za-z0-9_][A-Za-z0-9_-]*)\\}");
    /** A conditional directive on a line of its own; one with text in its brackets holds that text alone. */
    private static final Pattern CONDITIONAL = Pattern.compile("^(ifdef|ifndef|ifeval|endif)::([^\\[]*)\\[(.*)\\]$");
    private static final Pattern FENCE = Pattern.compile("^(-{4,}|\\.{4,}|\\+{4,}|/{4,})$");
    private static final Pattern EVALUATION = Pattern.compile("^(.+?)\\s*(==|!=|<=|>=|<|>)\\s*(.+)$");
    private static final Pattern TAG = Pattern.compile("<(/?)([A-Za-z][A-Za-z0-9]*)((?:\\s[^<>]*|/)?)>");
    private static final Pattern CLASS = Pattern.compile("\\bclass\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern ENTITY = Pattern.compile("&(#\\d+|#x[0-9A-Fa-f]+|[A-Za-z]+);");

    /** What stands on the stack for an element that is not displayed: its words are left out to its end. */
    private static final String HIDDEN = "\u0000";

    /**
     * An attribute's value.
     *
     * @param text the value
     * @param pass whether it was written as a passthrough: markup of the document's web page, not AsciiDoc
     */
    private record Value(String text, boolean pass) { }

    private static final DocumentAttributes INTRINSIC = intrinsicAttributes();

    private final Map<String, Value> values;
    private final PrintStyles styles;

    private DocumentAttributes(Map<String, Value> values, PrintStyles styles) {
        this.values = values;
        this.styles = styles;
    }

    /**
     * The attributes every document starts with: AsciiDoc's character replacements,
     * {@code {startsb}} for a bracket that must not open an attribute list among them, and Komet
     * as the backend.
     *
     * @return the state before a document's first line
     */
    public static DocumentAttributes intrinsic() {
        return INTRINSIC;
    }

    private static DocumentAttributes intrinsicAttributes() {
        Map<String, Value> values = new HashMap<>();
        String[][] characters = {
                {"blank", ""}, {"empty", ""}, {"sp", " "}, {"nbsp", "\u00a0"}, {"zwsp", "\u200b"}, {"wj", "\u2060"},
                {"apos", "'"}, {"quot", "\""}, {"lsquo", "\u2018"}, {"rsquo", "\u2019"}, {"ldquo", "\u201c"}, {"rdquo", "\u201d"},
                {"deg", "\u00b0"}, {"plus", "+"}, {"brvbar", "\u00a6"}, {"vbar", "|"}, {"amp", "&"}, {"lt", "<"}, {"gt", ">"},
                {"startsb", "["}, {"endsb", "]"}, {"caret", "^"}, {"asterisk", "*"}, {"tilde", "~"}, {"backslash", "\\"},
                {"backtick", "`"}, {"two-colons", "::"}, {"two-semicolons", ";;"}, {"cpp", "C++"}, {"pp", "++"},
                {"backend", "komet"}, {"backend-komet", ""}
        };
        for (String[] character : characters) {
            values.put(character[0], new Value(character[1], false));
        }
        return new DocumentAttributes(Map.copyOf(values), PrintStyles.NONE);
    }

    /** @return whether the attribute is set, to a value or to none */
    public boolean defined(String name) {
        return values.containsKey(name);
    }

    /** @return the attribute's value as written, or empty when it is not set */
    public Optional<String> value(String name) {
        return Optional.ofNullable(values.get(name)).map(Value::text);
    }

    /** @return what the document's style sheet says of an element in print */
    public PrintStyles styles() {
        return styles;
    }

    private DocumentAttributes with(String name, Value value) {
        Map<String, Value> next = new HashMap<>(values);
        next.put(name, value);
        return new DocumentAttributes(Map.copyOf(next), styles);
    }

    private DocumentAttributes without(String name) {
        if (!values.containsKey(name)) {
            return this;
        }
        Map<String, Value> next = new HashMap<>(values);
        next.remove(name);
        return new DocumentAttributes(Map.copyOf(next), styles);
    }

    private DocumentAttributes withStyles(PrintStyles added) {
        return new DocumentAttributes(values, added);
    }

    /** The references of a value, read as it is set: a value is fixed at its entry, not where it is used. */
    private Value entered(String written) {
        Matcher pass = PASS_VALUE.matcher(written);
        if (pass.matches()) {
            return new Value(pass.group(1), true);
        }
        boolean passes = false;
        StringBuilder out = new StringBuilder();
        Matcher m = REFERENCE.matcher(written);
        while (m.find()) {
            Value value = values.get(m.group(2));
            if (!m.group(1).isEmpty()) {
                m.appendReplacement(out, Matcher.quoteReplacement(m.group().substring(1)));
            } else if (value == null) {
                m.appendReplacement(out, Matcher.quoteReplacement(m.group()));
            } else {
                passes |= value.pass();
                m.appendReplacement(out, Matcher.quoteReplacement(value.text()));
            }
        }
        m.appendTail(out);
        return new Value(out.toString(), passes);
    }

    /** Whether a conditional's target is met: {@code a,b} is any of them set, {@code a+b} all of them. */
    private boolean set(String target) {
        if (target.contains(",")) {
            for (String name : target.split(",", -1)) {
                if (defined(name.strip())) {
                    return true;
                }
            }
            return false;
        }
        for (String name : target.split("\\+", -1)) {
            if (!defined(name.strip())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a conditional on a line of its own reads what it holds. {@code ifndef::a,b[]} reads
     * only when neither is set, and {@code ifndef::a+b[]} unless both are, as Asciidoctor has it.
     */
    private boolean reads(String directive, String target, String brackets) {
        return switch (directive) {
            case "ifdef" -> set(target);
            case "ifndef" -> !set(target);
            default -> evaluates(brackets);
        };
    }

    /** An {@code ifeval} expression: two values compared, as numbers when both are, else as text. An expression that cannot be read is not met. */
    private boolean evaluates(String expression) {
        Matcher m = EVALUATION.matcher(new Reading(false).line(expression, this).strip());
        if (!m.matches()) {
            return false;
        }
        String left = operand(m.group(1));
        String right = operand(m.group(3));
        int order;
        try {
            order = Double.compare(Double.parseDouble(left), Double.parseDouble(right));
        } catch (NumberFormatException notNumbers) {
            order = left.compareTo(right);
        }
        return switch (m.group(2)) {
            case "==" -> order == 0;
            case "!=" -> order != 0;
            case "<" -> order < 0;
            case "<=" -> order <= 0;
            case ">" -> order > 0;
            default -> order >= 0;
        };
    }

    private static String operand(String written) {
        String t = written.strip();
        return t.length() >= 2 && (t.startsWith("\"") && t.endsWith("\"") || t.startsWith("'") && t.endsWith("'")) ? t.substring(1, t.length() - 1) : t;
    }

    /**
     * Whether a line is a conditional that holds its text in its brackets,
     * {@code ifdef::draft[Not yet reviewed.]}: where its condition is met it is a line of prose,
     * that text, and not a directive.
     *
     * @param line the line
     * @return whether the line is such a conditional
     */
    public static boolean holdsText(String line) {
        Matcher held = CONDITIONAL.matcher(line);
        return held.matches() && (held.group(1).equals("ifdef") || held.group(1).equals("ifndef")) && !held.group(3).isEmpty();
    }

    /** @return a reading of one block's lines: the references of each line given their values */
    public static Reading reading() {
        return new Reading(true);
    }

    /** The marks of inline markup: one in a value is the character itself, and must not be read as a mark. */
    private static final String MARKS = "*_`#+^~";

    /**
     * A plain value as AsciiDoc that reads as exactly its characters. AsciiDoc gives a reference
     * its value after it has read the marks of the text around it, so a value is never markup:
     * {@code EL{plus}{plus}} twice in a paragraph is "EL++" twice, not a passthrough between
     * them. Here the value goes into the text before the marks are read, so a mark that could
     * pair with another is escaped: one at the value's edge, a doubled one, and the two that
     * need no pair's boundary. A single mark inside a value, an underscore in an address, stays,
     * since a backslash there would be kept in a link's target.
     */
    private static String shielded(String value) {
        StringBuilder out = null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean mark = MARKS.indexOf(c) >= 0 && (i == 0 || i == value.length() - 1 || c == '^' || c == '~'
                    || value.charAt(i - 1) == c || value.charAt(i + 1) == c);
            if (mark && out == null) {
                out = new StringBuilder(value.substring(0, i));
            }
            if (out != null) {
                out.append(mark ? "\\" : "").append(c);
            }
        }
        return out == null ? value : out.toString();
    }

    /**
     * Reads the references of one block's lines, a line at a time in order. It is one reading for
     * the block because a passthrough element may open in one reference and close in another, on
     * a later line: {@code {fn}} opens a note that {@code {fnend}} closes.
     */
    public static final class Reading {

        /** What closes each passthrough element that is open, the innermost last; {@link #HIDDEN} for one not displayed. */
        private final Deque<String> open = new ArrayDeque<>();
        /** Whether a plain value's marks are escaped, as text that goes on to be read for its markup needs. */
        private final boolean shields;

        private Reading(boolean shields) {
            this.shields = shields;
        }

        /**
         * One line with its references given their values: plain values as they stand, and a
         * passthrough value as the AsciiDoc that prints what its markup means on paper. A
         * conditional that holds its text in its brackets, {@code ifdef::draft[Not yet reviewed.]},
         * reads as that text.
         *
         * @param text the line, or a part of one such as a table's cell
         * @param at   the attributes in force at the line
         * @return the line as it is read; the same text when it names nothing set
         */
        public String line(String text, DocumentAttributes at) {
            if (text.indexOf('{') < 0 && open.isEmpty() && !text.startsWith("if")) {
                return text;
            }
            Matcher held = CONDITIONAL.matcher(text);
            String source = held.matches() && !held.group(1).equals("endif") && !held.group(1).equals("ifeval") && !held.group(3).isEmpty()
                    ? held.group(3) : text;
            StringBuilder out = new StringBuilder();
            Matcher m = REFERENCE.matcher(source);
            int from = 0;
            while (m.find()) {
                Value value = at.values.get(m.group(2));
                plain(source, from, m.start(), out);
                from = m.end();
                if (!m.group(1).isEmpty()) {
                    plain(m.group().substring(1), out);           // an escaped reference is its own words
                } else if (value == null) {
                    plain(m.group(), out);
                } else if (value.pass()) {
                    markup(value.text(), at.styles, out);
                } else {
                    plain(shields ? shielded(value.text()) : value.text(), out);
                }
            }
            plain(source, from, source.length(), out);
            return out.toString();
        }

        private void plain(String text, int from, int to, StringBuilder out) {
            if (from < to) {
                plain(text.substring(from, to), out);
            }
        }

        /** Words of the line itself: left out while an element that is not displayed is open. */
        private void plain(String text, StringBuilder out) {
            if (!open.contains(HIDDEN)) {
                out.append(text);
            }
        }

        /** A passthrough value: its elements as what they mean in print, its words as words. */
        private void markup(String value, PrintStyles styles, StringBuilder out) {
            Matcher tag = TAG.matcher(value);
            int from = 0;
            while (tag.find()) {
                plain(entities(value.substring(from, tag.start())), out);
                from = tag.end();
                String name = tag.group(2).toLowerCase(java.util.Locale.ROOT);
                if (!tag.group(1).isEmpty()) {
                    String closes = open.pollLast();
                    if (closes != null && !closes.equals(HIDDEN)) {
                        plain(closes, out);
                    }
                    continue;
                }
                if (tag.group(3).endsWith("/") || name.equals("br") || name.equals("hr") || name.equals("img")) {
                    continue;                                     // an element with nothing in it prints nothing
                }
                Matcher classes = CLASS.matcher(tag.group(3));
                PrintStyles.Effect effect = styles.of(name, classes.find() ? List.of(classes.group(1).strip().split("\\s+")) : List.of());
                if (effect == PrintStyles.Effect.HIDDEN || open.contains(HIDDEN)) {
                    open.addLast(HIDDEN);
                } else if (effect == PrintStyles.Effect.FOOTNOTE) {
                    out.append("footnote:[");
                    open.addLast("]");
                } else {
                    String mark = switch (name) {
                        case "sup" -> "^";
                        case "sub" -> "~";
                        case "b", "strong" -> "**";
                        case "i", "em" -> "__";
                        default -> "";
                    };
                    out.append(mark);
                    open.addLast(mark);
                }
            }
            plain(entities(value.substring(from)), out);
        }

        private static String entities(String text) {
            if (text.indexOf('&') < 0) {
                return text;
            }
            Matcher m = ENTITY.matcher(text);
            StringBuilder out = new StringBuilder();
            while (m.find()) {
                String name = m.group(1);
                String character = switch (name) {
                    case "amp" -> "&";
                    case "lt" -> "<";
                    case "gt" -> ">";
                    case "quot" -> "\"";
                    case "apos" -> "'";
                    case "nbsp" -> "\u00a0";
                    case "dagger" -> "\u2020";
                    default -> {
                        if (name.startsWith("#")) {
                            try {
                                int code = name.startsWith("#x") ? Integer.parseInt(name.substring(2), 16) : Integer.parseInt(name.substring(1));
                                yield Character.toString(code);
                            } catch (IllegalArgumentException notACharacter) {
                                yield m.group();
                            }
                        }
                        yield m.group();
                    }
                };
                m.appendReplacement(out, Matcher.quoteReplacement(character));
            }
            m.appendTail(out);
            return out.toString();
        }
    }

    /**
     * The attributes carried through a document's lines in reading order: each line given says
     * whether it is read, and leaves the state the next line is read in. A conditional directive
     * is not read itself, and nor is a line between a directive that is not met and its
     * {@code endif}; an attribute entry among those is not made. The lines of an included
     * document are given where its include line stands, so what the including document sets
     * holds in it, and what it sets holds after it.
     *
     * <p>Entries and style sheets are not looked for in a listing or a literal block, whose lines
     * are their own text, and nothing is looked for in a comment block. A conditional is read
     * in a listing, as Asciidoctor reads it there.
     */
    public static final class Walk {

        private DocumentAttributes state;
        /** Whether each open conditional reads its lines, the innermost last. */
        private final Deque<Boolean> conditionals = new ArrayDeque<>();
        /** The delimiter of the verbatim block the walk is in, or null. */
        private String fence;
        /** The lines of the passthrough block the walk is in. */
        private final StringBuilder passthrough = new StringBuilder();
        /** The entry whose value runs on to the next line, and the value so far. */
        private String continued;
        private boolean continuedUnsets;
        private final StringBuilder value = new StringBuilder();

        /**
         * A walk from a state.
         *
         * @param from the state before the first line
         */
        public Walk(DocumentAttributes from) {
            this.state = from;
        }

        /** A walk from the attributes every document starts with. */
        public Walk() {
            this(intrinsic());
        }

        /** @return the attributes after the lines given so far */
        public DocumentAttributes state() {
            return state;
        }

        /**
         * Reads the next line.
         *
         * @param text the line
         * @return whether the document reads it: false for a conditional directive on a line of its own, and for
         *         a line a conditional leaves out
         */
        public boolean line(String text) {
            if (fence != null && fence.startsWith("/")) {
                if (text.equals(fence)) {
                    fence = null;
                }
                return met();                                 // nothing in a comment block is a directive
            }
            Matcher conditional = CONDITIONAL.matcher(text);
            if (conditional.matches()) {
                String directive = conditional.group(1);
                String brackets = conditional.group(3);
                if (directive.equals("endif")) {
                    conditionals.pollLast();
                    return false;
                }
                if (!directive.equals("ifeval") && !brackets.isEmpty()) {
                    // the text is in the brackets: the line is read when the condition is met, and opens nothing
                    return met() && state.reads(directive, conditional.group(2).strip(), brackets);
                }
                conditionals.addLast(met() && state.reads(directive, conditional.group(2).strip(), brackets));
                return false;
            }
            if (!met()) {
                return false;
            }
            if (continued != null) {
                enter(text);
                return true;
            }
            if (fence != null) {
                if (text.equals(fence)) {
                    if (fence.startsWith("+")) {
                        PrintStyles.read(passthrough.toString(), state.styles).ifPresent(styles -> state = state.withStyles(styles));
                        passthrough.setLength(0);
                    }
                    fence = null;
                } else if (fence.startsWith("+")) {
                    passthrough.append(text).append('\n');
                }
                return true;
            }
            if (FENCE.matcher(text).matches()) {
                fence = text;
                return true;
            }
            Matcher entry = ENTRY.matcher(text);
            if (entry.matches()) {
                continued = entry.group(2);
                continuedUnsets = !entry.group(1).isEmpty() || !entry.group(3).isEmpty();
                value.setLength(0);
                enter(entry.group(4) == null ? "" : entry.group(4));
            }
            return true;
        }

        /** Whether the lines here are read: every open conditional is met. */
        private boolean met() {
            return !conditionals.contains(Boolean.FALSE);
        }

        /** Adds a line to the entry being read, and makes the entry when the line does not run on. */
        private void enter(String text) {
            String part = text.strip();
            if (part.endsWith(" \\") || part.equals("\\")) {
                value.append(part, 0, part.length() - 1);         // the value runs on: the next line is more of it
                return;
            }
            value.append(part);
            state = continuedUnsets ? state.without(continued) : state.with(continued, state.entered(value.toString().strip()));
            continued = null;
        }
    }

    /**
     * What a document's own style sheet says of an element on paper. A document that is also a
     * web page may write, in a passthrough block, how its marks are shown there and how they
     * print: a dagger with a tooltip on the screen, and the tooltip's words as a footnote on
     * paper. Two declarations decide what a page of paper shows, and they are the two read:
     * {@code display: none}, an element that is left out, and {@code float: footnote}, an element
     * whose words go to the foot of the page. A rule applies as it would in print: one outside
     * any {@code @media}, and one under {@code @media print}; a later rule for the same selector
     * wins.
     */
    public static final class PrintStyles {

        /** What an element is on paper. */
        public enum Effect {
            /** Its words where they stand. */
            INLINE,
            /** Nothing. */
            HIDDEN,
            /** Its words at the foot of the page, a mark where it stood. */
            FOOTNOTE
        }

        static final PrintStyles NONE = new PrintStyles(Map.of());

        private static final Pattern STYLE = Pattern.compile("<style[^>]*>(.*?)</style>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        private static final Pattern COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
        private static final Pattern DECLARATION = Pattern.compile("(display|float)\\s*:\\s*([A-Za-z-]+)");

        /** The declarations read, by selector as written: {@code span.note}, {@code .note}, {@code sup}. */
        private final Map<String, Map<String, String>> rules;

        private PrintStyles(Map<String, Map<String, String>> rules) {
            this.rules = rules;
        }

        /**
         * Reads the style sheets of a passthrough block's lines.
         *
         * @param block the block's lines
         * @param known the rules read before it
         * @return the rules with the block's added, or empty when the block holds no style sheet
         */
        static Optional<PrintStyles> read(String block, PrintStyles known) {
            Matcher sheet = STYLE.matcher(block);
            Map<String, Map<String, String>> rules = null;
            while (sheet.find()) {
                if (rules == null) {
                    rules = new HashMap<>();
                    for (Map.Entry<String, Map<String, String>> rule : known.rules.entrySet()) {
                        rules.put(rule.getKey(), new HashMap<>(rule.getValue()));
                    }
                }
                rules(COMMENT.matcher(sheet.group(1)).replaceAll(""), rules);
            }
            return rules == null ? Optional.empty() : Optional.of(new PrintStyles(rules));
        }

        /** The rules of a sheet, or of what an {@code @media} that applies in print holds. */
        private static void rules(String css, Map<String, Map<String, String>> rules) {
            int at = 0;
            while (at < css.length()) {
                int open = css.indexOf('{', at);
                if (open < 0) {
                    return;
                }
                int close = closing(css, open);
                String prelude = css.substring(at, open).strip();
                String body = css.substring(open + 1, close);
                at = close + 1;
                if (prelude.startsWith("@media")) {
                    String media = prelude.substring("@media".length()).strip();
                    if (media.isEmpty() || media.contains("print") || media.contains("all")) {
                        rules(body, rules);
                    }
                    continue;
                }
                if (prelude.startsWith("@")) {
                    continue;
                }
                Matcher declaration = DECLARATION.matcher(body);
                while (declaration.find()) {
                    for (String selector : prelude.split(",")) {
                        rules.computeIfAbsent(selector.strip().toLowerCase(java.util.Locale.ROOT), s -> new HashMap<>())
                                .put(declaration.group(1), declaration.group(2).toLowerCase(java.util.Locale.ROOT));
                    }
                }
            }
        }

        /** The brace that closes the one at an offset, or the sheet's end. */
        private static int closing(String css, int open) {
            int depth = 0;
            for (int i = open; i < css.length(); i++) {
                if (css.charAt(i) == '{') {
                    depth++;
                } else if (css.charAt(i) == '}' && --depth == 0) {
                    return i;
                }
            }
            return css.length();
        }

        /**
         * What an element is on paper.
         *
         * @param element its name, lower case
         * @param classes its classes
         * @return the effect; inline when the sheet says nothing of it
         */
        public Effect of(String element, List<String> classes) {
            // the element alone, then each class, then the element with each class: the more exact selector wins
            List<String> selectors = new ArrayList<>();
            selectors.add(element);
            for (String name : classes) {
                selectors.add("." + name.toLowerCase(java.util.Locale.ROOT));
            }
            for (String name : classes) {
                selectors.add(element + "." + name.toLowerCase(java.util.Locale.ROOT));
            }
            Map<String, String> declared = new HashMap<>();
            for (String selector : selectors) {
                declared.putAll(rules.getOrDefault(selector, Map.of()));
            }
            if ("none".equals(declared.get("display"))) {
                return Effect.HIDDEN;
            }
            return "footnote".equals(declared.get("float")) ? Effect.FOOTNOTE : Effect.INLINE;
        }
    }
}

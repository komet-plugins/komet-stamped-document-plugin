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

import design.knowledge.stamp.adoc.dialect.InlineMarkup;
import design.knowledge.stamp.adoc.dialect.InlineMarkup.Span;
import network.ike.komet.document.AsciiDocMarkdown;
import network.ike.komet.document.KonceptTokens;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Prose as a page prints it: AsciiDoc inline markup read once, by {@link InlineMarkup}, into the
 * text that is drawn and the styled runs over it, with the markup gone. The Typst writer writes
 * from it and the page draws from it, so the PDF and the Page layout cannot read a span
 * differently, and an oracle measures a word in the font it is drawn in, not by counting
 * backticks.
 *
 * <p>What prints: strong, emphasis and monospace, in any nesting; a link and a cross reference
 * as their text, a bare cross reference as its target's words ({@code <<anf-data-types>>}
 * prints "anf data types", as the help drawer does); a curved quotation; a visible index term
 * as its text; a footnote as its mark, its text going to the foot of the page. A link whose
 * target begins with {@link #INTERNAL} points into the document: a cross reference that found
 * its target is read as one before the text comes here. Anchors and concealed index terms
 * print nothing; an anchor is kept as a place such a link can point at. An attribute reference stays as written. A trailing
 * {@code +} stays too: on a page it stands for the hard break, outside every line's range.
 *
 * <p>A concept token, {@code k:uuid=<UUID>[Name]} or {@code k:sctid=<SCTID>[Name]}
 * ({@link KonceptTokens}), prints as the concept's name and never as its code: the words of its
 * label, else the name the open store gives the concept ({@link KonceptTokens.Found#name()}). A
 * token with neither, and one in monospace, which shows code as written, stays as written. The
 * Reading view draws a token as the concept's chip, so it reads prose {@link #onScreen} and keeps
 * the token for the chip to take its place.
 *
 * @param text    the drawn text; a footnote's mark is its number in superscript digits
 * @param runs    the styled ranges of the text, ascending; what they leave out is plain
 * @param notes   the footnotes marked in the text, in order
 * @param offsets for each offset of the source, and its end, the offset of the drawn text it became
 * @param anchors the anchors written in the text, in order: each prints nothing, and is a place a link can point at
 */
public record InlineText(String text, List<Run> runs, List<Note> notes, int[] offsets, List<Anchor> anchors) {

    /** The mark an internal link's target begins with: {@code #b1234} names a block of the document, by its key. */
    public static final String INTERNAL = "#";

    /** Text with no anchor in it. */
    public InlineText(String text, List<Run> runs, List<Note> notes, int[] offsets) {
        this(text, runs, notes, offsets, List.of());
    }

    /**
     * An anchor written in the text.
     *
     * @param id the anchor's id
     * @param at the offset of the drawn text it stands before
     */
    public record Anchor(String id, int at) { }

    private static final String SUPERSCRIPT = "⁰¹²³⁴⁵⁶⁷⁸⁹";

    public InlineText {
        Objects.requireNonNull(text, "text is null");
        runs = List.copyOf(runs);
        notes = List.copyOf(notes);
        anchors = List.copyOf(anchors);
    }

    /**
     * A styled range of the text.
     *
     * @param from   the first character
     * @param to     the character after the last
     * @param mono   whether it is set as inline raw, in the mono font at {@link TypstWriter#RAW_SCALE} of the size
     * @param bold   whether it is strong
     * @param italic whether it is emphasized
     * @param href   the link it is, or {@code null}
     * @param underline whether it is underlined: a phrase of the role {@code underline}
     * @param strike    whether it is struck through: a phrase of the role {@code line-through}
     * @param highlight whether it is highlighted: {@code #marked#}
     */
    public record Run(int from, int to, boolean mono, boolean bold, boolean italic, String href, boolean underline, boolean strike, boolean highlight) {

        /** A run of the styles every reading had before the lines over, through and behind a phrase were read. */
        public Run(int from, int to, boolean mono, boolean bold, boolean italic, String href) {
            this(from, to, mono, bold, italic, href, false, false, false);
        }

        /** @return the same style over another range */
        public Run over(int newFrom, int newTo) {
            return new Run(newFrom, newTo, mono, bold, italic, href, underline, strike, highlight);
        }

        private static final Run PLAIN = new Run(0, 0, false, false, false, null);

        private Run withMono() {
            return new Run(from, to, true, bold, italic, href, underline, strike, highlight);
        }

        private Run withBold() {
            return new Run(from, to, mono, true, italic, href, underline, strike, highlight);
        }

        private Run withItalic() {
            return new Run(from, to, mono, bold, true, href, underline, strike, highlight);
        }

        private Run withHref(String link) {
            return new Run(from, to, mono, bold, italic, link, underline, strike, highlight);
        }

        private Run withUnderline() {
            return new Run(from, to, mono, bold, italic, href, true, strike, highlight);
        }

        private Run withStrike() {
            return new Run(from, to, mono, bold, italic, href, underline, true, highlight);
        }

        private Run withHighlight() {
            return new Run(from, to, mono, bold, italic, href, underline, strike, true);
        }

        private boolean sameStyle(Run other) {
            return mono == other.mono && bold == other.bold && italic == other.italic && Objects.equals(href, other.href)
                    && underline == other.underline && strike == other.strike && highlight == other.highlight;
        }
    }

    /**
     * A footnote: where its mark stands, and its text.
     *
     * @param number the printed number
     * @param from   the mark's first character in the text
     * @param to     the character after the mark
     * @param id     the name given in {@code footnote:name[...]}, or {@code null}
     * @param repeat whether this mark points at a note already given, as {@code footnote:name[]} does
     * @param body   the note's text
     * @param source the source offset of the note's macro
     */
    public record Note(int number, int from, int to, String id, boolean repeat, InlineText body, int source) {

        /** @return the note as the foot of the page draws it: its number in superscript digits, then its text */
        public InlineText drawn() {
            String mark = superscript(number);
            List<Run> shifted = body.runs().stream().map(r -> r.over(r.from() + mark.length(), r.to() + mark.length())).toList();
            return new InlineText(mark + body.text(), shifted, List.of(), new int[0]);
        }
    }

    /** Where a footnote's mark stands in a unit's text, and the note's number. */
    public record Mark(int at, int note) { }

    /** A document's footnote count: one per document, whichever way it is read. */
    public static final class Numbering {
        private int last;
        private final Map<String, Integer> named = new HashMap<>();

        /** A count from the start of a document. */
        public Numbering() { }

        /**
         * A count that goes on from a number: for text read a second time, in parts, whose notes
         * must carry the numbers its first reading gave them, as a table row's cells do after the row.
         *
         * @param last the number of the last note before the text
         * @return the count
         */
        public static Numbering after(int last) {
            Numbering numbering = new Numbering();
            numbering.last = last;
            return numbering;
        }

        /** @return the number of the last note counted, 0 before the first */
        public int last() {
            return last;
        }

        /** @return how many notes have been counted by name: a text that names none leaves this as it was */
        public int names() {
            return named.size();
        }

        /**
         * Counts on from a number, for a text whose notes were numbered in an earlier reading
         * that is kept as it was drawn: the notes after it take the numbers after its last.
         *
         * @param number the number of the last note of the text kept
         */
        public void countedTo(int number) {
            last = Math.max(last, number);
        }

        private int next(String id) {
            last++;
            if (id != null) {
                named.put(id, last);
            }
            return last;
        }
    }

    /**
     * Reads prose on its own, its notes numbered from 1.
     *
     * @param markup the text, physical lines joined by {@code \n}
     * @return the printed text
     */
    public static InlineText of(String markup) {
        return of(markup, new Numbering());
    }

    /**
     * Reads prose, continuing a document's footnote numbers.
     *
     * @param markup    the text, physical lines joined by {@code \n}
     * @param numbering the document's count
     * @return the printed text
     */
    public static InlineText of(String markup, Numbering numbering) {
        return read(markup, numbering, true);
    }

    /**
     * Reads prose as the Reading view draws it: as {@link #of(String, Numbering)} reads it, but a
     * concept token stays as written, since the screen draws it as the concept's chip.
     *
     * @param markup    the text, physical lines joined by {@code \n}
     * @param numbering the document's count
     * @return the text as the screen draws it before its chips are put in
     */
    public static InlineText onScreen(String markup, Numbering numbering) {
        return read(markup, numbering, false);
    }

    private static InlineText read(String markup, Numbering numbering, boolean names) {
        Reader reader = new Reader(markup, numbering, names);
        reader.walk(InlineMarkup.parse(markup), Run.PLAIN);
        return reader.done(markup.length());
    }

    /**
     * Prose with its footnotes left out, markup and all: what a front list's entry prints, since
     * a note belongs with the text it annotates on that text's own page, not in the list that
     * names the block. The space before a dropped note goes with it, as its mark took it.
     *
     * @param markup the text
     * @return the text without its footnote macros; the same text when it has none
     */
    public static String withoutNotes(String markup) {
        List<Span> notes = new ArrayList<>();
        collect(InlineMarkup.parse(markup), InlineMarkup.Element.FOOTNOTE, notes);
        if (notes.isEmpty()) {
            return markup;
        }
        StringBuilder out = new StringBuilder(markup);
        for (Span note : notes.reversed()) {
            int from = note.start();
            while (from > 0 && out.charAt(from - 1) == ' ') {
                from--;
            }
            out.delete(from, note.end());
        }
        return out.toString();
    }

    /** Every span of an element, at any depth, in source order. */
    private static void collect(List<Span> spans, InlineMarkup.Element element, List<Span> out) {
        for (Span span : spans) {
            if (span.element() == element) {
                out.add(span);
            } else {
                collect(span.children(), element, out);
            }
        }
    }

    /** @return text with no markup at all, read as it stands */
    public static InlineText literal(String text) {
        int[] offsets = new int[text.length() + 1];
        for (int i = 0; i < offsets.length; i++) {
            offsets[i] = i;
        }
        return new InlineText(text, List.of(), List.of(), offsets);
    }

    /** @return the number in superscript digits */
    public static String superscript(int number) {
        StringBuilder out = new StringBuilder();
        for (char c : Integer.toString(number).toCharArray()) {
            out.append(SUPERSCRIPT.charAt(c - '0'));
        }
        return out.toString();
    }

    /** @return the offset of the drawn text a source offset became */
    public int drawnAt(int source) {
        return offsets[Math.clamp(source, 0, offsets.length - 1)];
    }

    /** @return the marks of the notes, shifted by an offset in the text they end up in */
    public List<Mark> marks(int shift) {
        return notes.stream().map(n -> new Mark(n.from() + shift, n.number())).toList();
    }

    /** Reads the spans of one text, or of one footnote's body. */
    private static final class Reader {
        private final String source;
        private final Numbering numbering;
        private final StringBuilder out = new StringBuilder();
        private final List<Run> runs = new ArrayList<>();
        private final List<Note> notes = new ArrayList<>();
        private final List<Anchor> anchors = new ArrayList<>();
        private final int[] offsets;
        /** Whether a concept token is read as its name, as a page prints it, or kept as written for the screen's chip. */
        private final boolean names;
        private int at;

        Reader(String source, Numbering numbering, boolean names) {
            this.source = source;
            this.numbering = numbering;
            this.names = names;
            this.offsets = new int[source.length() + 1];
        }

        void walk(List<Span> spans, Run style) {
            for (Span span : spans) {
                skipTo(span.start());
                switch (span.element()) {
                    case TEXT -> text(span, style);
                    case STRONG -> walk(span.children(), style.withBold());
                    case EMPHASIS -> walk(span.children(), style.withItalic());
                    case MONOSPACE -> walk(span.children(), style.withMono());
                    case HIGHLIGHT -> walk(span.children(), style.withHighlight());
                    case PHRASE -> {
                        // a phrase is its role: underlined, struck through; any other role reads as its words
                        String role = span.attributes().getOrDefault("role", "");
                        walk(span.children(), role.contains("underline") ? style.withUnderline() : role.contains("line-through") ? style.withStrike() : style);
                    }
                    case CROSS_REFERENCE -> {
                        if (span.children().isEmpty()) {
                            emit(AsciiDocMarkdown.topicOf(span.attributes().getOrDefault("linkend", "")).replace('-', ' '), style);
                        } else {
                            walk(span.children(), style);
                        }
                    }
                    case LINK -> {
                        String href = span.attributes().getOrDefault("href", "");
                        if (span.children().isEmpty()) {
                            emit(href, style.withHref(href));
                        } else {
                            walk(span.children(), style.withHref(href));
                        }
                    }
                    case FOOTNOTE -> note(span);
                    case LINE_BREAK -> emit("+", style);
                    case QUOTATION -> {
                        boolean twice = "double".equals(span.attributes().get("marks"));
                        emit(twice ? "“" : "‘", style);
                        walk(span.children(), style);
                        emit(twice ? "”" : "’", style);
                    }
                    case INDEX_TERM -> walk(span.children(), style);      // a concealed term has none
                    case ANCHOR -> anchors.add(new Anchor(span.attributes().getOrDefault("id", ""), out.length()));
                    default -> {
                        if (span.children().isEmpty()) {
                            emit(span.text(), style);
                        } else {
                            walk(span.children(), style);
                        }
                    }
                }
                skipTo(span.end());
            }
        }

        /**
         * A text run: its characters, each kept against its source offset; an escape's backslash
         * becomes nothing. Read for a page, a concept token is its name, in the run's style.
         */
        private void text(Span span, Run style) {
            String text = span.text();
            int i = span.start();
            int k = 0;
            for (KonceptTokens.Found token : names && !style.mono() ? KonceptTokens.find(text) : List.<KonceptTokens.Found>of()) {
                Optional<String> name = token.name();
                if (name.isEmpty()) {
                    continue;                                     // nothing to name it by: it stays as written
                }
                // a token written inside brackets of its own, as the assistant writes some, is the reference: the brackets go
                // with it, as they go with its chip on screen
                boolean bracketed = token.start() > k && text.charAt(token.start() - 1) == '['
                        && token.end() < text.length() && text.charAt(token.end()) == ']';
                int from = bracketed ? token.start() - 1 : token.start();
                int to = bracketed ? token.end() + 1 : token.end();
                for (; k < from; k++) {
                    i = take(text.charAt(k), i, span.end());
                    emit(String.valueOf(text.charAt(k)), style);
                }
                // a label's words are drawn where they are written, the token's other characters standing at their edges;
                // a name the store gives stands where the token began
                int words = token.label() != null && !token.label().isBlank() ? token.labelStart() + token.label().indexOf(name.get()) : -1;
                for (; k < to; k++) {
                    i = take(text.charAt(k), i, span.end());
                    if (words >= 0 && k >= words && k < words + name.get().length()) {
                        emit(String.valueOf(text.charAt(k)), style);
                    }
                }
                if (words < 0) {
                    emit(name.get(), style);
                }
            }
            for (; k < text.length(); k++) {
                i = take(text.charAt(k), i, span.end());
                emit(String.valueOf(text.charAt(k)), style);
            }
            at = Math.max(at, i);
        }

        /**
         * Takes a character of a run from the source, at an offset or after it: the characters
         * passed over, an escape's backslash, stand where it does in the drawn text.
         *
         * @return the source offset after it
         */
        private int take(char c, int i, int end) {
            while (i < end && source.charAt(i) != c) {
                offsets[i++] = out.length();
            }
            if (i < end) {
                offsets[i++] = out.length();
            }
            return i;
        }

        /** A footnote's mark, which Typst sets tight against the word before: the space between goes. */
        private void note(Span span) {
            while (!out.isEmpty() && out.charAt(out.length() - 1) == ' ') {
                out.setLength(out.length() - 1);
            }
            while (!runs.isEmpty() && runs.getLast().to() > out.length()) {
                Run run = runs.removeLast();
                if (run.from() < out.length()) {
                    runs.add(run.over(run.from(), out.length()));
                }
            }
            String id = span.attributes().get("id");
            boolean repeat = id != null && span.children().isEmpty() && numbering.named.containsKey(id);
            if (!repeat && id != null && numbering.named.containsKey(id)) {
                id = null;                                        // a name given twice: Typst's label must stay unique
            }
            int number = repeat ? numbering.named.get(id) : numbering.next(id);
            Reader reader = new Reader(source, numbering, names);
            reader.at = span.start();
            reader.walk(span.children(), Run.PLAIN);
            String mark = superscript(number);
            int from = out.length();
            out.append(mark);
            notes.add(new Note(number, from, out.length(), id, repeat, reader.done(span.end()), span.start()));
        }

        private void emit(String text, Run style) {
            int from = out.length();
            out.append(text);
            if (style == Run.PLAIN) {
                return;
            }
            Run last = runs.isEmpty() ? null : runs.getLast();
            if (last != null && last.to() == from && last.sameStyle(style)) {
                runs.set(runs.size() - 1, last.over(last.from(), out.length()));
            } else {
                runs.add(style.over(from, out.length()));
            }
        }

        private void skipTo(int to) {
            while (at < to) {
                offsets[at++] = out.length();
            }
        }

        InlineText done(int end) {
            skipTo(end);
            for (int i = at; i < offsets.length; i++) {
                offsets[i] = out.length();
            }
            // Spaces given up to a footnote's mark were counted when they were read.
            int ceiling = out.length();
            for (int i = offsets.length - 1; i >= 0; i--) {
                ceiling = Math.min(ceiling, offsets[i]);
                offsets[i] = ceiling;
            }
            return new InlineText(out.toString(), runs, notes, offsets, anchors);
        }
    }
}

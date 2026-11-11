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

import network.ike.komet.document.print.InlineText;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Where words selected in a line's drawn text stand in the line's source, so a mark chosen over
 * a selection in the Reading view or on a page is written into the stored line with no editor
 * opened (ike-lab-documents topic {@code dev-document-edit-menu}, revised 2026-11-10).
 *
 * <p>The drawn text is not the source: its markup is gone, a list item's marker is the view's
 * own, index terms are left out, and a concept the prose names is a chip. So the selection is
 * found, not computed: the line's source is read as the views read it, the selected words are
 * looked for in that reading with the text around them, and the place found is carried back to
 * the source through the reading's own offsets. Then it is checked: the source stretch must read
 * as exactly the selected words. A selection that cannot be placed that way, one that holds a
 * chip, a footnote's mark or half of a marked phrase, gives nothing, and the caller says so
 * rather than write a mark in the wrong place.
 *
 * <p>A selection is taken as whole words: an end inside a word moves out to the word's edge,
 * since the marks written are the constrained ones, which hold only at a word's edges.
 */
public final class SourceSpan {

    /** How much of the text around a selection tells one occurrence of its words from another. */
    private static final int CONTEXT = 24;
    /** How much of a selection that runs over lines is looked for on its first and last line. */
    private static final int FRAGMENT = 40;

    private SourceSpan() { }

    /**
     * A selection placed in a line's source.
     *
     * @param from   the first character of the source stretch
     * @param to     the character after its last
     * @param outerFrom the stretch's start with the marks of a phrase the words are the whole of: where a mark of another kind is written
     * @param outerTo   its end with those marks
     * @param bold   whether the stretch lies inside a longer phrase that is already bold
     * @param italic whether it lies inside a longer phrase that is already italic
     * @param mono   whether it lies inside a longer phrase that is already monospace
     * @param words  the selected words as drawn, whole, their white space single
     * @param nth    which occurrence of those words in the line's drawn text this is, from 0
     */
    public record Located(int from, int to, int outerFrom, int outerTo, boolean bold, boolean italic, boolean mono, String words, int nth) { }

    /**
     * Places a selection made within one line's drawn text.
     *
     * @param source the stored line's text
     * @param inline whether the drawn text is the line's prose read, its markup gone; false for a line drawn as written
     * @param share  the line's drawn text in the view, as its run holds it
     * @param from   the selection's start in the share
     * @param to     the selection's end in the share
     * @return the place in the source, or empty when the selection cannot be placed exactly
     */
    public static Optional<Located> locate(String source, boolean inline, String share, int from, int to) {
        Optional<Placed> placed = place(source, inline, share, from, to);
        if (placed.isEmpty()) {
            return Optional.empty();
        }
        Placed at = placed.get();
        int tightFrom = tightBefore(at, source, inline);
        int tightTo = tightAfter(at, source, inline);
        if (tightFrom < 0 || tightTo <= tightFrom) {
            return Optional.empty();
        }
        // The words alone, or with the marks of a phrase that ends or begins among them: a selection that takes in
        // a marked phrase whole takes its marks too, so a mark written around it nests, never crosses.
        int wideFrom = wideBefore(at, source, inline, tightFrom);
        int wideTo = wideAfter(at, source, inline, tightTo);
        int[][] tries = {{tightFrom, tightTo}, {tightFrom, wideTo}, {wideFrom, tightTo}, {wideFrom, wideTo}};
        for (int[] stretch : tries) {
            if (reads(source, inline, stretch[0], stretch[1], at.selected())) {
                boolean bold = false;
                boolean italic = false;
                boolean mono = false;
                for (InlineText.Run run : at.read().runs()) {
                    if (run.from() <= at.d1() && run.to() >= at.d2() && (run.from() < at.d1() || run.to() > at.d2())) {
                        bold |= run.bold();
                        italic |= run.italic();
                        mono |= run.mono();
                    }
                }
                // the same words with every mark that stands around them: a mark of another kind goes outside those, so the two nest
                int[] outer = stretch;
                for (int[] wider : new int[][] {{wideFrom, wideTo}, {tightFrom, wideTo}, {wideFrom, tightTo}}) {
                    if (wider[0] <= stretch[0] && wider[1] >= stretch[1] && reads(source, inline, wider[0], wider[1], at.selected())) {
                        outer = wider;
                        break;
                    }
                }
                return Optional.of(new Located(stretch[0], stretch[1], outer[0], outer[1], bold, italic, mono, at.selected(), at.nth()));
            }
        }
        return Optional.empty();                                  // no stretch reads as the words: a mark's edge inside them, a macro, a note
    }

    /**
     * Where a selection that runs on to later lines begins in its first line's source: before
     * the marks of a phrase that begins with its first word, and nowhere inside a marked phrase.
     *
     * @param source the first line's text
     * @param inline whether the drawn text is the line's prose read
     * @param share  the line's drawn text in the view
     * @param from   the selection's start in the share; the selection runs to the share's end
     * @return the offset in the source, or empty when it cannot be placed exactly
     */
    public static OptionalInt start(String source, boolean inline, String share, int from) {
        int end = share.stripTrailing().length();
        int[] words = words(share, from, end);
        if (words == null) {
            return OptionalInt.empty();
        }
        int to = words[1];
        if (to - words[0] > FRAGMENT) {
            to = words[0] + FRAGMENT;
            while (to > words[0] && !Character.isWhitespace(share.charAt(to))) {
                to--;                                             // back to the end of a whole word
            }
            if (to == words[0]) {
                to = words[1];
            }
        }
        Optional<Placed> placed = place(source, inline, share, words[0], to);
        if (placed.isEmpty()) {
            return OptionalInt.empty();
        }
        Placed at = placed.get();
        int tight = tightBefore(at, source, inline);
        if (tight < 0) {
            return OptionalInt.empty();
        }
        for (InlineText.Run run : at.read().runs()) {
            if (run.from() < at.d1() && run.to() > at.d1()) {
                return OptionalInt.empty();                       // it begins inside a marked phrase
            }
            if (run.from() == at.d1()) {
                int wide = wideBefore(at, source, inline, tight);
                return wide < tight ? OptionalInt.of(wide) : OptionalInt.empty();
            }
        }
        return OptionalInt.of(tight);
    }

    /**
     * Where a selection that began on an earlier line ends in its last line's source: after the
     * marks of a phrase that ends with its last word, and nowhere inside a marked phrase.
     *
     * @param source the last line's text
     * @param inline whether the drawn text is the line's prose read
     * @param share  the line's drawn text in the view
     * @param to     the selection's end in the share; the selection runs from the share's start
     * @return the offset in the source after the selection's last character, or empty when it cannot be placed exactly
     */
    public static OptionalInt end(String source, boolean inline, String share, int to) {
        int[] words = words(share, 0, to);
        if (words == null) {
            return OptionalInt.empty();
        }
        int from = words[0];
        if (words[1] - from > FRAGMENT) {
            from = words[1] - FRAGMENT;
            while (from < words[1] && !Character.isWhitespace(share.charAt(from - 1))) {
                from++;                                           // on to the start of a whole word
            }
            if (from == words[1]) {
                from = words[0];
            }
        }
        Optional<Placed> placed = place(source, inline, share, from, words[1]);
        if (placed.isEmpty()) {
            return OptionalInt.empty();
        }
        Placed at = placed.get();
        int tight = tightAfter(at, source, inline);
        if (tight < 0) {
            return OptionalInt.empty();
        }
        for (InlineText.Run run : at.read().runs()) {
            if (run.from() < at.d2() && run.to() > at.d2()) {
                return OptionalInt.empty();                       // it ends inside a marked phrase
            }
            if (run.to() == at.d2()) {
                int wide = wideAfter(at, source, inline, tight);
                return wide > tight ? OptionalInt.of(wide) : OptionalInt.empty();
            }
        }
        return OptionalInt.of(tight);
    }

    /**
     * Selected words found in a line's reading.
     *
     * @param read     the line as the views read it
     * @param d1       the words' first character in the reading
     * @param d2       the character after their last
     * @param selected the words, their white space single
     * @param nth      which occurrence of the words in the view's text they are, from 0
     */
    private record Placed(InlineText read, int d1, int d2, String selected, int nth) { }

    /** Finds the selected words in the line's reading: by their order among the same words, or by the text around them. */
    private static Optional<Placed> place(String source, boolean inline, String share, int from, int to) {
        int[] words = words(share, from, to);
        if (words == null) {
            return Optional.empty();
        }
        InlineText read = inline ? InlineText.of(source) : InlineText.literal(source);
        Normal drawn = new Normal(read.text());
        Normal view = new Normal(share);
        int a = view.at[words[0]];
        int b = view.at[words[1]];
        String selected = view.text.substring(a, b);
        List<Integer> found = occurrences(drawn.text, selected);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        List<Integer> shown = occurrences(view.text, selected);
        int nth = shown.indexOf(a);
        int at;
        if (found.size() == shown.size() && nth >= 0) {
            at = found.get(nth);                                  // the same words the same number of times: the same one
        } else {
            String left = view.text.substring(Math.max(0, a - CONTEXT), a);
            String right = view.text.substring(b, Math.min(view.text.length(), b + CONTEXT));
            int best = -1;
            int bestScore = -1;
            boolean tied = false;
            for (int candidate : found) {
                int score = commonSuffix(drawn.text, candidate, left) + commonPrefix(drawn.text, candidate + selected.length(), right);
                if (score > bestScore) {
                    best = candidate;
                    bestScore = score;
                    tied = false;
                } else if (score == bestScore) {
                    tied = true;
                }
            }
            if (tied && found.size() > 1) {
                return Optional.empty();                          // nothing around the words says which they are
            }
            at = best;
        }
        return Optional.of(new Placed(read, drawn.origin[at], drawn.origin[at + selected.length() - 1] + 1, selected, Math.max(0, nth)));
    }

    /** The source character the words' first character was read from, or -1. */
    private static int tightBefore(Placed at, String source, boolean inline) {
        return inline ? sourceOf(at.read(), source.length(), at.d1()) : at.d1();
    }

    /** The offset after the source character the words' last character was read from, or -1. */
    private static int tightAfter(Placed at, String source, boolean inline) {
        int last = inline ? sourceOf(at.read(), source.length(), at.d2() - 1) : at.d2() - 1;
        return last < 0 ? -1 : last + 1;
    }

    /** The start moved back over what stands between the words and the character drawn before them: a phrase's opening marks. */
    private static int wideBefore(Placed at, String source, boolean inline, int tight) {
        if (!inline) {
            return tight;
        }
        int before = at.d1() == 0 ? -1 : sourceOf(at.read(), source.length(), at.d1() - 1);
        if (at.d1() > 0 && before < 0) {
            return tight;
        }
        int s = before + 1;
        while (s < tight && Character.isWhitespace(source.charAt(s))) {
            s++;
        }
        return s;
    }

    /** The end moved on over what stands between the words and the character drawn after them: a phrase's closing marks. */
    private static int wideAfter(Placed at, String source, boolean inline, int tight) {
        if (!inline) {
            return tight;
        }
        int next = at.d2() >= at.read().text().length() ? source.length() : sourceOf(at.read(), source.length(), at.d2());
        if (next < 0) {
            return tight;
        }
        int e = next;
        while (e > tight && Character.isWhitespace(source.charAt(e - 1))) {
            e--;
        }
        return e;
    }

    /** Whether a stretch of the source reads as exactly the selected words. */
    private static boolean reads(String source, boolean inline, int from, int to, String selected) {
        if (from < 0 || to > source.length() || from >= to) {
            return false;
        }
        String back = inline ? InlineText.of(source.substring(from, to)).text() : source.substring(from, to);
        return new Normal(back).text.strip().equals(selected);
    }

    /**
     * A selection as whole words: white space at its ends left out, an end inside a word moved
     * to the word's edge.
     *
     * @return {@code {from, to}}, or {@code null} when the selection holds no word
     */
    static int[] words(String share, int from, int to) {
        int a = Math.clamp(Math.min(from, to), 0, share.length());
        int b = Math.clamp(Math.max(from, to), 0, share.length());
        while (a < b && Character.isWhitespace(share.charAt(a))) {
            a++;
        }
        while (b > a && Character.isWhitespace(share.charAt(b - 1))) {
            b--;
        }
        if (a >= b) {
            return null;
        }
        while (a > 0 && wordly(share.charAt(a - 1)) && wordly(share.charAt(a))) {
            a--;
        }
        while (b < share.length() && wordly(share.charAt(b - 1)) && wordly(share.charAt(b))) {
            b++;
        }
        return new int[] {a, b};
    }

    private static boolean wordly(char c) {
        return Character.isLetterOrDigit(c);
    }

    /** The source character a drawn character was read from, or -1 when the reading made it up (a reference's words, a quotation mark). */
    private static int sourceOf(InlineText read, int length, int drawn) {
        for (int s = 0; s < length; s++) {
            if (read.drawnAt(s + 1) > drawn) {
                return read.drawnAt(s) == drawn && read.drawnAt(s + 1) == drawn + 1 ? s : -1;
            }
        }
        return -1;
    }

    private static List<Integer> occurrences(String text, String words) {
        List<Integer> out = new ArrayList<>();
        for (int i = text.indexOf(words); i >= 0; i = text.indexOf(words, i + 1)) {
            out.add(i);
        }
        return out;
    }

    /** How many characters before an offset of a text match the end of a context. */
    private static int commonSuffix(String text, int before, String context) {
        int n = 0;
        while (n < context.length() && n < before && text.charAt(before - 1 - n) == context.charAt(context.length() - 1 - n)) {
            n++;
        }
        return n;
    }

    /** How many characters from an offset of a text match the start of a context. */
    private static int commonPrefix(String text, int from, String context) {
        int n = 0;
        while (n < context.length() && from + n < text.length() && text.charAt(from + n) == context.charAt(n)) {
            n++;
        }
        return n;
    }

    /** A text with every run of white space as one space, and where each of its characters came from. */
    private static final class Normal {
        final String text;
        /** For each character of {@link #text}, and its end, the offset in the original. */
        final int[] origin;
        /** For each offset of the original, and its end, the offset in {@link #text}. */
        final int[] at;

        Normal(String original) {
            StringBuilder out = new StringBuilder(original.length());
            int[] from = new int[original.length() + 1];
            at = new int[original.length() + 1];
            for (int i = 0; i < original.length(); i++) {
                char c = original.charAt(i);
                at[i] = out.length();
                if (Character.isWhitespace(c)) {
                    if (out.isEmpty() || out.charAt(out.length() - 1) != ' ') {
                        from[out.length()] = i;
                        out.append(' ');
                    } else {
                        at[i] = out.length() - 1;                 // the rest of a run stands on its one space
                    }
                } else {
                    from[out.length()] = i;
                    out.append(c);
                }
            }
            at[original.length()] = out.length();
            from[out.length()] = original.length();
            text = out.toString();
            origin = java.util.Arrays.copyOf(from, out.length() + 1);
        }
    }
}

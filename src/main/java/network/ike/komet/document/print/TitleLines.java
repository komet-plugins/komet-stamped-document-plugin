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
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * How a document's title is set: as display type, centered, never hyphenated, broken into
 * lines of similar length (Bringhurst, <i>The Elements of Typographic Style</i> 2.1.6 and
 * 8.5; the <i>Chicago Manual of Style</i> 8.159 on breaking display lines by sense).
 *
 * <p>Two rules keep the breaks at phrase boundaries. A short word that leads a phrase — an
 * article, a preposition, a conjunction ({@link #BINDERS}) — is tied to the word after it, so
 * no line ends on one; and the last two words of a title of three or more are tied, so no
 * line is a lone last word. A tie is a no-break space: the Typst writer sets it in the title,
 * and {@link #breaks} honours it.
 *
 * <p>Balance comes from the measure: the lines are broken greedily, ragged, in the narrowest
 * measure that still sets the title in as many lines as the full measure does, never
 * narrower than its widest unbreakable piece. Typst has no balanced breaking of its own, so
 * the writer's {@code ike-title} finds that measure the same way, by measuring; this class is
 * the rule for the Page layout's first pass. "Applying Decision Theory to Create Transparent
 * and Reproducible Regulatory Decisions" sets as "Applying Decision Theory to Create /
 * Transparent and Reproducible / Regulatory Decisions".
 */
public final class TitleLines {

    private TitleLines() { }

    /** The no-break space a tie sets. */
    public static final char TIE = ' ';

    /** The short words that lead a phrase and are tied to the word after them, lower-cased. */
    static final Set<String> BINDERS = Set.of(
            "a", "an", "the",
            "of", "to", "in", "on", "at", "by", "for", "from", "with", "into", "onto", "upon", "via", "per", "as",
            "and", "or", "nor", "but");

    /** How many halvings the search for the narrowest measure takes: a measure of 500 pt to a thirtieth of a point. */
    static final int STEPS = 14;

    /**
     * The title with its ties set: the space after every {@link #BINDERS binder}, and the space
     * between the last two words of a title of three words or more. A title of one or two words
     * is left as it is. The result has the title's length, a tie standing where a space stood.
     *
     * @param title the title, as plain text or as markup
     * @return the title with no-break spaces at its ties
     */
    public static String tie(String title) {
        char[] chars = title.toCharArray();
        List<int[]> words = new ArrayList<>();                   // {from, to}
        int from = -1;
        for (int i = 0; i <= chars.length; i++) {
            boolean space = i == chars.length || chars[i] == ' ';
            if (!space && from < 0) {
                from = i;
            } else if (space && from >= 0) {
                words.add(new int[] {from, i});
                from = -1;
            }
        }
        for (int w = 0; w + 1 < words.size(); w++) {
            int[] word = words.get(w);
            if (BINDERS.contains(bare(title.substring(word[0], word[1])))) {
                tieAfter(chars, word[1], words.get(w + 1)[0]);
            }
        }
        if (words.size() >= 3) {
            tieAfter(chars, words.get(words.size() - 2)[1], words.getLast()[0]);
        }
        return new String(chars);
    }

    /** Ties the one space between two words; a run of spaces is left breakable. */
    private static void tieAfter(char[] chars, int end, int next) {
        if (next == end + 1) {
            chars[end] = TIE;
        }
    }

    /** A word lower-cased, with any markup or punctuation around it taken off. */
    private static String bare(String word) {
        int from = 0;
        int to = word.length();
        while (from < to && !Character.isLetter(word.charAt(from))) {
            from++;
        }
        while (to > from && !Character.isLetter(word.charAt(to - 1))) {
            to--;
        }
        return word.substring(from, to).toLowerCase(Locale.ROOT);
    }

    /** Measures a stretch of the title's text, from one character up to another. */
    @FunctionalInterface
    public interface Width {
        /**
         * @param from the first character
         * @param to   the character after the last
         * @return the stretch's width in points, in the title's font
         */
        double of(int from, int to);
    }

    /**
     * The lines of a title: ragged, unhyphenated, broken only at untied spaces, balanced by the
     * narrowest measure that keeps the line count the full measure gives.
     *
     * @param text    the title's drawn text, with plain spaces: its ties are found by {@link #tie}
     * @param width   the width of a stretch of it
     * @param measure the full measure in points
     * @return the lines as {@code {from, to, 0}} over the text, at least one; a line's range
     *         takes the space that ends it, as the page's lines do
     */
    public static List<int[]> breaks(String text, Width width, double measure) {
        List<int[]> pieces = pieces(text);
        if (pieces.isEmpty()) {
            return List.of(new int[] {0, text.length(), 0});
        }
        List<int[]> full = greedy(text, pieces, width, measure);
        if (full.size() == 1) {
            return full;
        }
        double floor = 0;
        for (int[] piece : pieces) {
            floor = Math.max(floor, width.of(piece[0], piece[1]));
        }
        double lo = Math.min(floor, measure);
        double hi = measure;
        for (int step = 0; step < STEPS; step++) {
            double mid = (lo + hi) / 2;
            if (greedy(text, pieces, width, mid).size() <= full.size()) {
                hi = mid;
            } else {
                lo = mid;
            }
        }
        return greedy(text, pieces, width, hi);
    }

    /** The unbreakable pieces of a title: its words, tied words as one, as {@code {from, to}}. */
    static List<int[]> pieces(String text) {
        String tied = tie(text);
        List<int[]> out = new ArrayList<>();
        int from = -1;
        for (int i = 0; i <= tied.length(); i++) {
            boolean space = i == tied.length() || tied.charAt(i) == ' ' || tied.charAt(i) == '\n';
            if (!space && from < 0) {
                from = i;
            } else if (space && from >= 0) {
                out.add(new int[] {from, i});
                from = -1;
            }
        }
        return out;
    }

    /** Greedy ragged lines in a measure: each takes the pieces that fit, a piece too wide for any line standing alone on one. */
    private static List<int[]> greedy(String text, List<int[]> pieces, Width width, double measure) {
        List<int[]> lines = new ArrayList<>();
        int from = pieces.getFirst()[0];
        int to = pieces.getFirst()[1];
        for (int p = 1; p < pieces.size(); p++) {
            int[] piece = pieces.get(p);
            if (width.of(from, piece[1]) > measure + 1e-6) {
                lines.add(new int[] {from, piece[0], 0});         // the line takes its trailing space
                from = piece[0];
            }
            to = piece[1];
        }
        lines.add(new int[] {from, Math.max(to, text.length()), 0});
        if (lines.getFirst()[0] > 0) {
            lines.getFirst()[0] = 0;                              // leading space, if any, is the first line's
        }
        return lines;
    }
}

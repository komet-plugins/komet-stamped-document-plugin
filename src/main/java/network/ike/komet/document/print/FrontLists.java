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

import network.ike.komet.document.print.LayoutOracle.Layout;
import network.ike.komet.document.print.LayoutOracle.Unit;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pages a document's front lists print beside their entries, and how a layout settles on
 * them. A measured source prints each entry's page as a literal number, which must be the folio
 * of the page the layout it measures puts the target on — a number of another width could move a line, so
 * the layout is asked again with the pages it found until no entry's page changes, which takes
 * two passes as a rule: one to learn the pages, one to confirm them. The printed document asks
 * Typst's page counter instead ({@link TypstWriter}), so the two agree once settled.
 */
public final class FrontLists {

    /** How many passes a layout may take to settle before the last one is taken as it stands. */
    public static final int PASSES = 4;

    private FrontLists() { }

    /**
     * One pass of a layout oracle over the document with the entries' pages filled in.
     */
    @FunctionalInterface
    public interface Pass {
        /**
         * @param numbered the model, its entries carrying the pages the pass before found
         * @return the oracle's answer; a layout of no lines when it did not answer
         * @throws IOException if the source could not be written
         */
        Layout layout(DocumentModel numbered) throws IOException;
    }

    /**
     * What the passes settled on.
     *
     * @param model  the model as last measured, its entries carrying the pages measured
     * @param layout the last pass's layout
     * @param passes how many passes were taken
     * @param pages  the page each target begins on in that layout, by key
     */
    public record Settled(DocumentModel model, Layout layout, int passes, Map<String, Integer> pages) { }

    /** An entry's mark: a section number, or a caption's word and number with its full stop, before the text it numbers. */
    private static final Pattern MARK = Pattern.compile("^(?:\\d+(?:\\.\\d+)*\\.?|\\p{Lu}\\p{L}* \\d+\\.)(?= \\S)");

    /**
     * The mark an entry opens with: the number of the section it names ({@code 4.1}), or the
     * word and number of a caption ({@code Figure 3.}). A mark stands in a column of its own
     * before the entry's text ({@link #places}).
     *
     * @param text the entry's text
     * @return the mark, without the space after it; empty for an entry that opens with none
     */
    public static String mark(String text) {
        Matcher m = MARK.matcher(text);
        return m.find() ? m.group() : "";
    }

    /** The longest last word of an entry, in letters and digits, that is tied to the word before it ({@link #tied}). */
    public static final int TIE = 4;

    /** The space before an entry's short last word, which may end in one mark of punctuation. */
    private static final Pattern TIED = Pattern.compile("(?<=\\S) (?=[\\p{L}\\p{N}]{1," + TIE + "}\\p{Punct}?$)");

    /**
     * An entry's text with its last word tied to the word before it by a no-break space when
     * that word is of {@link #TIE} letters or fewer, so a short word never turns over onto a
     * line by itself: the two stay on a line or turn over together. The text keeps its length,
     * so an offset into it means what it did.
     *
     * @param text the entry's text as drawn
     * @return the text, tied where its last word is short
     */
    public static String tied(String text) {
        return TIED.matcher(text).replaceFirst("\u00a0");
    }

    /**
     * The mark that stands for the widest of each depth of a list, by which the writer and the
     * pages size that depth's column of marks: the longest mark of the depth with every digit
     * read as a zero, so a column is as wide for {@code 3.1} as for {@code 7.4} whatever the
     * font's digits, and both ends measure one and the same text. Of marks of one length the
     * first in order is taken.
     *
     * @param texts  the entries' texts, in order
     * @param depths the entries' depths, 0 for the outermost, as many as the texts
     * @return a mark for each depth from 0 to the deepest, empty for a depth none of whose entries opens with one
     */
    public static List<String> patterns(List<String> texts, List<Integer> depths) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            int depth = Math.max(0, depths.get(i));
            while (out.size() <= depth) {
                out.add("");
            }
            String zeroed = mark(texts.get(i)).replaceAll("\\d", "0");
            String widest = out.get(depth);
            if (zeroed.length() > widest.length() || zeroed.length() == widest.length() && zeroed.compareTo(widest) < 0) {
                out.set(depth, zeroed);
            }
        }
        return out;
    }

    /**
     * Where an entry stands in its list, as the writer's entry block sets it.
     *
     * @param left   the left edge of the entry's mark, from the measure's left edge, in points
     * @param column the width of the column its mark stands in, 0 where no entry of its depth opens with a mark
     */
    public record Place(double left, double column) {

        /** @return the left edge of the entry's text, on every line of it, from the measure's left edge */
        public double text() {
            return left + column;
        }
    }

    /**
     * An entry's depth in its list, which its unit says by its indent: an em a level
     * ({@link ModelUnits}). Where the entry stands is not that indent but its {@link Place},
     * reckoned from the marks of its list.
     *
     * @param entry the entry's unit
     * @return the depth, 0 for the outermost
     */
    public static int depth(Unit entry) {
        return (int) Math.max(0, Math.round(entry.indent() / entry.fontSize()));
    }

    /**
     * Where every entry of every front list stands. The marks of a depth stand in a column as
     * wide as the depth's widest ({@link #patterns}) and {@link TypstWriter#ENTRY_MARK_GAP}, so
     * the texts of a depth begin at one edge, and an entry that opens with no mark begins there
     * too. A depth begins where the text of the depth above it does: its marks stand under the
     * words they are parts of. A depth none of whose entries opens with a mark has no column,
     * and the depth under it stands {@link TypstWriter#ENTRY_STEP} in.
     *
     * @param units the document's units
     * @param width the measure of text as drawn
     * @return each entry's place, by its unit's index
     */
    public static Map<Integer, Place> places(List<Unit> units, LayoutOracle.Measure width) {
        Map<Integer, List<Unit>> lists = new LinkedHashMap<>();
        for (Unit unit : units) {
            if (unit.kind() == LayoutOracle.Kind.ENTRY) {
                lists.computeIfAbsent(unit.block(), b -> new ArrayList<>()).add(unit);
            }
        }
        Map<Integer, Place> out = new LinkedHashMap<>();
        for (List<Unit> list : lists.values()) {
            List<String> patterns = patterns(list.stream().map(Unit::text).toList(), list.stream().map(FrontLists::depth).toList());
            double[] column = new double[patterns.size()];
            double[] left = new double[patterns.size()];
            Unit first = list.getFirst();
            for (int d = 0; d < column.length; d++) {
                column[d] = patterns.get(d).isEmpty() ? 0
                        : width.width(patterns.get(d), first, false, false, false) + first.fontSize() * TypstWriter.ENTRY_MARK_GAP;
                left[d] = d == 0 ? 0 : left[d - 1] + (column[d - 1] > 0 ? column[d - 1] : first.fontSize() * TypstWriter.ENTRY_STEP);
            }
            for (Unit entry : list) {
                int d = depth(entry);
                out.put(entry.index(), new Place(left[d], column[d]));
            }
        }
        return out;
    }

    /**
     * The room at the measure's right edge that no line of an entry's text runs into, as the
     * writer's entry block sets it: the column of the page number, its gutter, the least
     * leader and the gap before it.
     *
     * @param size   the entry's font size in points
     * @param column the width of the page number's column in points ({@link #column})
     * @return the room in points
     */
    public static double reserve(double size, double column) {
        return column + size * (TypstWriter.ENTRY_GUTTER + TypstWriter.ENTRY_LEAD + TypstWriter.ENTRY_GAP);
    }

    /**
     * As {@link #reserve(double, double)}, for a page number no wider than
     * {@link TypstWriter#ENTRY_NUMBER_ROOM}: what a layout oracle keeps clear before it knows
     * the number, which holds for a document of up to 999 pages.
     *
     * @param entry the entry's unit
     * @param width the measure of text as drawn
     * @return the room in points
     */
    public static double reserve(Unit entry, LayoutOracle.Measure width) {
        return reserve(entry.fontSize(), column(width.width(TypstWriter.ENTRY_NUMBER_ROOM, entry, false, false, false), 0));
    }

    /**
     * The width of an entry's page number column: room for {@link TypstWriter#ENTRY_NUMBER_ROOM},
     * or for the entry's own number when that is wider.
     *
     * @param room   the width of {@link TypstWriter#ENTRY_NUMBER_ROOM} in the entry's font
     * @param number the width of the entry's page number in that font
     * @return the column's width in points
     */
    public static double column(double room, double number) {
        return Math.max(room, number);
    }

    /**
     * The page each entry's target begins on in a layout, as the page prints its number: the
     * folio ({@link Folios}), which for a target in the body of a document with front matter is
     * counted from the body's first page, not from the title page. A reader sent to page 12
     * finds the page that prints 12.
     *
     * @param layout      the layout
     * @param targetUnits the unit each target begins with, by the target's key
     * @return the number of each target's folio in the order given; 0 for a target whose unit has no line
     */
    public static Map<String, Integer> pages(Layout layout, Map<String, Integer> targetUnits) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> target : targetUnits.entrySet()) {
            int page = layout.pageOf(target.getValue());
            out.put(target.getKey(), page < 0 ? 0 : layout.folios().number(page));
        }
        return out;
    }

    /**
     * Repeats a pass until no entry's page changes, or {@link #PASSES} have been taken, or the
     * oracle gives no answer. A document with no front list takes one pass.
     *
     * @param model       the model, its entries' pages as they stand (usually unknown)
     * @param first       the pages to print on the first pass: an estimate, such as a greedy layout's
     * @param targetUnits the unit each target begins with, by the target's key
     * @param pass        the oracle's pass
     * @return what was settled on
     * @throws IOException if a pass could not write its source
     */
    public static Settled settle(DocumentModel model, Map<String, Integer> first, Map<String, Integer> targetUnits, Pass pass) throws IOException {
        Objects.requireNonNull(model, "model is null");
        Map<String, Integer> numbers = first;
        int passes = 0;
        while (true) {
            DocumentModel numbered = model.withEntryPages(numbers);
            Layout layout = pass.layout(numbered);
            passes++;
            Map<String, Integer> found = pages(layout, targetUnits);
            if (layout.lines().isEmpty() || found.equals(numbers) || passes >= PASSES) {
                return new Settled(numbered, layout, passes, found);
            }
            numbers = found;
        }
    }
}

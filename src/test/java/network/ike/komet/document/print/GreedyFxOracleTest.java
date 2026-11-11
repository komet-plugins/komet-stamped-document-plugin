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

import javafx.application.Platform;
import javafx.scene.text.Font;
import network.ike.komet.document.print.LayoutOracle.Kind;
import network.ike.komet.document.print.LayoutOracle.Unit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The first pass's line breaker, headless: the pieces a line may begin at, Typst's cost of a
 * line, and the breaks themselves — a word hyphenated where the line would otherwise stretch
 * too far, left whole where its spaces can take the gap, never in a table cell or an inline
 * raw span.
 */
class GreedyFxOracleTest {

    private static GreedyFxOracle oracle;

    @BeforeAll
    static void startJavaFx() {
        try {
            Class.forName("com.sun.glass.ui.monocle.MonoclePlatformFactory");
            System.setProperty("glass.platform", "Monocle");
            System.setProperty("monocle.platform", "Headless");
            System.setProperty("prism.order", "sw");
            System.setProperty("prism.text", "t2k");
            System.setProperty("java.awt.headless", "true");
        } catch (ClassNotFoundException ignored) {
            // the default toolkit
        }
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException alreadyRunning) {
            // started by another test
        }
        DocumentFonts.loadForJavaFx();
        oracle = new GreedyFxOracle(DocumentFonts.SERIF, DocumentFonts.MONO, 1.0);
    }

    private static Unit prose(String text) {
        return new Unit(0, 0, Kind.PARAGRAPH, text, 12, false, 0, 0, 0, false);
    }

    @Test
    void aJustifiedLineStaysInsideTheMeasureWithItsSlack() {
        assertEquals(1, LayoutOracle.lineScale(400, 9, 468, true), "room to spare");
        assertEquals(468 / 469.0, LayoutOracle.lineScale(460, 9, 468, true), 1e-9, "the text fits but the laid width does not");
        assertEquals(1, LayoutOracle.lineScale(460, 9, 468, false), "a ragged line's slack paints nothing");
        assertEquals(468 / 480.0, LayoutOracle.lineScale(480, 9, 468, false), 1e-9, "wider than the measure");
    }

    @Test
    void aUnitThatOpensAPageStartsOneUnlessThePageIsEmpty() {
        LayoutOracle.Geometry geometry = new LayoutOracle.Geometry(612, 792, 72, 72, 468, 648);
        Unit title = new Unit(0, 0, Kind.TITLE, "Title", 21.6, true, 0, 0, 14.4, false);
        Unit contents = new Unit(1, 1, Kind.HEADING, "Contents", 18, true, 0, 16.2, 9, true).withPageBefore(true);
        Unit entry = new Unit(2, 1, Kind.ENTRY, "1 One", 12, false, 0, 13.2, 0, false);
        Unit body = new Unit(3, 2, Kind.HEADING, "1 One", 18, true, 0, 16.2, 9, true).withPageBefore(true);
        Unit text = new Unit(4, 2, Kind.PARAGRAPH, "The body.", 12, false, 0, 13.2, 0, false);
        var layout = oracle.layout(List.of(title, contents, entry, body, text), geometry);

        assertEquals(3, layout.pages(), "the title page, the contents, the body");
        assertEquals(0, layout.pageOf(0));
        assertEquals(1, layout.pageOf(1), "the list opens a page");
        assertEquals(1, layout.pageOf(2));
        assertEquals(2, layout.pageOf(3), "the body opens a page after the lists");
        assertEquals(72 + 18 * LayoutOracle.CAP_HEIGHT, layout.ofUnit(1).getFirst().baseline(), 1e-6, "at the top of its page, with no space above");

        assertEquals(Folios.NONE, layout.folios(), "no unit opens a body: the pages are numbered from 1");

        var alone = oracle.layout(List.of(contents, entry), geometry);
        assertEquals(1, alone.pages(), "a page already empty is not left blank");
        assertEquals(0, alone.pageOf(1));
    }

    @Test
    void anEntrysMarkStandsInItsColumnAndItsTextTurnsOverShortOfThePageNumbersRoom() {
        LayoutOracle.Geometry geometry = new LayoutOracle.Geometry(612, 792, 72, 72, 468, 648);
        Unit outer = new Unit(0, 0, Kind.ENTRY, "4 Clearing", 12, false, 0, 13.2, 0, false);
        Unit entry = new Unit(1, 0, Kind.ENTRY, "4.1 " + "characterization ".repeat(12).strip(), 12, false, 12, 7.8, 0, false);
        Unit plain = new Unit(2, 0, Kind.ENTRY, "Thanks", 12, false, 12, 7.8, 0, false);
        List<Unit> units = List.of(outer, entry, plain);
        FrontLists.Place place = FrontLists.places(units, oracle::width).get(1);
        assertEquals(oracle.width("0", outer, false, true, false) + 12 * TypstWriter.ENTRY_MARK_GAP, place.left(), 1e-9, "under the outer depth's text, whose marks are bold");
        assertEquals(oracle.width("0.0", entry) + 12 * TypstWriter.ENTRY_MARK_GAP, place.column(), 1e-9);
        double edge = 72 + 468 - FrontLists.reserve(12, place.room());
        assertEquals(oracle.width("000", outer, false, true, false), place.room(), 1e-9, "room for a bold page number, a chief entry's");

        var layout = oracle.layout(units, geometry);
        var lines = layout.ofUnit(1);

        assertEquals(new LayoutOracle.Line(1, 0, 72 + place.mark(oracle.width("4.1", entry), 12), lines.getFirst().baseline(), 0, 3, false), lines.getFirst(),
                "the mark is a line of its own, in its column, ending a gap before the text");
        assertTrue(lines.size() >= 4, lines.toString());
        assertEquals(4, lines.get(1).from(), "the text begins after the mark and its space");
        assertEquals(lines.getFirst().baseline(), lines.get(1).baseline(), "on the mark's baseline");
        assertEquals(entry.text().length(), lines.getLast().to(), "the lines cover the entry");
        for (int k = 1; k < lines.size(); k++) {
            LayoutOracle.Line line = lines.get(k);
            assertEquals(72 + place.text(), line.x(), 1e-6, "every line of the text stands after the column of marks: line " + k);
            assertFalse(line.hyphenated(), "an entry is never hyphenated");
            double end = line.x() + oracle.width(line.text(entry), entry);
            assertTrue(end <= edge + 1e-6, "line " + k + " keeps the leader's and the number's room clear: " + end + " > " + edge);
            if (k + 1 < lines.size()) {
                String next = entry.text().substring(lines.get(k + 1).from()).split(" ")[0];
                assertTrue(end + oracle.width(" " + next, entry) > edge, "line " + k + " takes every word that fits");
            }
        }
        assertEquals(lines.get(1).baseline() + 12 * LayoutOracle.PITCH, lines.get(2).baseline(), 1e-6);
        assertEquals(List.of(new LayoutOracle.Line(2, 0, 72 + place.text(), layout.ofUnit(2).getFirst().baseline(), 0, 6, false)), layout.ofUnit(2),
                "an entry with no mark is one line, begun where its depth's texts begin");
        assertEquals(List.of(0, 2), layout.ofUnit(0).stream().map(LayoutOracle.Line::from).toList(), "a short entry is its mark and its text");
    }

    @Test
    void anEntrysShortLastWordTurnsOverWithTheWordBeforeIt() {
        LayoutOracle.Geometry geometry = new LayoutOracle.Geometry(612, 792, 72, 72, 468, 648);
        Unit probe = new Unit(0, 0, Kind.ENTRY, "1 x", 12, false, 0, 13.2, 0, false);
        FrontLists.Place place = FrontLists.places(List.of(probe), oracle::width).get(0);
        double room = 468 - place.text() - FrontLists.reserve(12, place.room());
        // An entry whose words but the last fill its text's measure: untied, "it" alone would turn over.
        StringBuilder words = new StringBuilder("Asking");
        while (oracle.width(words + " again and", probe) <= room) {
            words.append(" again");
        }
        while (oracle.width(words + " and it", probe) <= room) {
            words.append('n');                                    // a letter is narrower than the last word: the room closes without overflowing
        }
        String filled = words + " and";
        assertTrue(oracle.width(filled, probe) <= room && oracle.width(filled + " it", probe) > room, "the last word alone has no room");
        Unit entry = new Unit(0, 0, Kind.ENTRY, "1 " + filled + " it", 12, false, 0, 13.2, 0, false);
        Unit longer = new Unit(0, 0, Kind.ENTRY, "1 " + filled + " items", 12, false, 0, 13.2, 0, false);

        var tied = oracle.layout(List.of(entry), geometry).ofUnit(0);
        var untied = oracle.layout(List.of(longer), geometry).ofUnit(0);

        assertEquals(3, tied.size(), "the mark, and two lines of text");
        assertEquals("and it", tied.getLast().text(entry), "the short word takes the word before it down with it");
        assertEquals(3, untied.size());
        assertEquals("items", untied.getLast().text(longer), "a longer last word turns over by itself");
    }

    @Test
    void theLayoutSaysWhereTheBodyBeginsSoThePagesCanBeNumbered() {
        LayoutOracle.Geometry geometry = new LayoutOracle.Geometry(612, 792, 72, 72, 468, 648);
        Unit title = new Unit(0, 0, Kind.TITLE, "Title", 21.6, true, 0, 0, 14.4, false);
        Unit contents = new Unit(1, 1, Kind.HEADING, "Contents", 18, true, 0, 16.2, 9, true).withPageBefore(true);
        Unit entry = new Unit(2, 1, Kind.ENTRY, "1 One", 12, false, 0, 13.2, 0, false);
        Unit body = new Unit(3, 2, Kind.HEADING, "1 One", 18, true, 0, 16.2, 9, true).withPageBefore(true).withOpensBody(true);
        Unit text = new Unit(4, 2, Kind.PARAGRAPH, "The body.", 12, false, 0, 13.2, 0, false);

        var layout = oracle.layout(List.of(title, contents, entry, body, text), geometry);

        assertEquals(new Folios(2, true), layout.folios(), "a title page and a page of contents, then the body");
        assertEquals(List.of("", "ii", "1"), java.util.stream.IntStream.range(0, layout.pages()).mapToObj(layout.folios()::label).toList());
        assertEquals(java.util.Map.of("a", 1), FrontLists.pages(layout, java.util.Map.of("a", 3)), "the entry for the first heading prints 1");
    }

    @Test
    void aFigureThatDoesNotFitMovesToTheNextPageWhole() {
        LayoutOracle.Geometry geometry = new LayoutOracle.Geometry(612, 792, 72, 72, 468, 648);
        Unit first = new Unit(0, 0, Kind.FIGURE, LayoutOracle.FIGURE_WORD, 12, false, 0, 0, 0, false, 468, 500);
        Unit second = new Unit(1, 1, Kind.FIGURE, LayoutOracle.FIGURE_WORD, 12, false, 0, 0, 0, false, 936, 400);
        var layout = oracle.layout(List.of(first, second), geometry);
        assertEquals(2, layout.pages());
        assertEquals(0, layout.ofUnit(0).getFirst().page());
        assertEquals(72 + 500, layout.ofUnit(0).getFirst().baseline(), 1e-6, "a figure's baseline is its foot");
        assertEquals(1, layout.ofUnit(1).getFirst().page());
        assertEquals(72 + 200, layout.ofUnit(1).getFirst().baseline(), 1e-6, "scaled to the measure: half its height");
    }

    private static final LayoutOracle.Geometry LETTER = new LayoutOracle.Geometry(612, 792, 72, 72, 468, 648);

    /** A table of one-line rows in a block of its own, its header bold, its first row at a unit index. */
    private static List<Unit> table(int first, int block, int rows) {
        List<Unit> units = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            units.add(new Unit(first + r, block, Kind.TABLE_ROW, "cell " + r + "  \u2502  other", 12, r == 0, 0,
                    r == 0 ? 13.2 : 2 * UnitSpacing.CELL_INSET, 0, false));
        }
        return units;
    }

    /** Filler that leaves the page's room for a given number of one-line rows. */
    private static Unit filler(double height) {
        return new Unit(0, 0, Kind.FIGURE, LayoutOracle.FIGURE_WORD, 12, false, 0, 0, 0, false, 100, height);
    }

    @Test
    void aTableBreaksBetweenItsRowsWithItsHeaderOnEveryPage() {
        List<Unit> units = table(0, 0, 90);

        var layout = oracle.layout(units, LETTER);

        double row = 12 * LayoutOracle.CAP_HEIGHT + 2 * UnitSpacing.CELL_INSET;
        int perPage = (int) (LETTER.bandHeight() / row);
        assertEquals(3, layout.pages(), "a page holds " + perPage + " rows");
        assertEquals(List.of(0, 1, 2), layout.ofUnit(0).stream().map(LayoutOracle.Line::page).toList(), "a header on every page");
        assertEquals(0, layout.ofUnit(perPage - 1).getFirst().page());
        assertEquals(1, layout.ofUnit(perPage).getFirst().page());
        assertEquals(layout.ofUnit(0).get(1).baseline(), layout.ofUnit(0).getFirst().baseline(), 1e-6, "at the page's top");
        assertEquals(layout.ofUnit(0).get(1).baseline() + row, layout.ofUnit(perPage).getFirst().baseline(), 1e-6, "the rows below it");
        for (int u = 1; u < units.size(); u++) {
            assertEquals(1, layout.ofUnit(u).size(), "one line for row " + u);
        }
        assertEquals(List.of(perPage, 2 * perPage - 1), layout.breaks(), "a repeat begins no page");
    }

    @Test
    void aTableStartsWhereItsHeaderAndFirstRowFit() {
        double row = 12 * LayoutOracle.CAP_HEIGHT + 2 * UnitSpacing.CELL_INSET;
        double gap = 13.2;
        List<Unit> fits = new ArrayList<>(List.of(filler(LETTER.bandHeight() - gap - 2 * row)));
        fits.addAll(table(1, 1, 3));
        List<Unit> short1 = new ArrayList<>(List.of(filler(LETTER.bandHeight() - gap - 2 * row + 1)));
        short1.addAll(table(1, 1, 3));

        assertEquals(0, oracle.layout(fits, LETTER).ofUnit(2).getFirst().page(), "both rows fit");
        assertEquals(1, oracle.layout(fits, LETTER).ofUnit(3).getFirst().page(), "the third goes over");
        var moved = oracle.layout(short1, LETTER);
        assertEquals(1, moved.ofUnit(1).getFirst().page(), "a header is not left at the foot without a row");
        assertEquals(1, moved.ofUnit(1).size(), "and has nothing to repeat");
    }

    @Test
    void aCaptionStaysWithItsTable() {
        List<Unit> units = new ArrayList<>(List.of(filler(LETTER.bandHeight() - 60)));
        units.add(new Unit(1, 1, Kind.OTHER, "A caption", 12, true, 0, 12, 12 * 1.1 + UnitSpacing.CELL_INSET - 0.4, false));
        units.addAll(table(2, 1, 3));

        var layout = oracle.layout(units, LETTER);

        assertEquals(1, layout.ofUnit(1).getFirst().page());
        assertEquals(1, layout.ofUnit(2).getFirst().page());
    }

    @Test
    void aBodyRowsCellsBeginAtItsTopAndAHeaderRowsStandOnTheirLastLines() {
        String wide = "many words of a cell that runs to a second line in half the measure, ".repeat(2).strip();
        Unit row = new Unit(0, 0, Kind.TABLE_ROW, "one  \u2502  " + wide, 12, false, 0, 0, 0, false);
        int lines = oracle.breaks(new Unit(0, 0, Kind.PARAGRAPH, wide, 12, false, 0, 0, 0, false), LETTER.contentWidth() / 2 - 10, false).size();
        assertTrue(lines > 1, "a tall cell beside the short one: " + lines);
        double pitch = 12 * LayoutOracle.PITCH;

        var layout = oracle.layout(List.of(row), LETTER);

        double top = LETTER.contentTop() + UnitSpacing.CELL_INSET + 12 * LayoutOracle.CAP_HEIGHT;
        assertEquals(top, layout.ofUnit(0).getFirst().baseline(), 1e-6, "a short first cell begins at the row's top, beside the tall one's first line");
        assertEquals((lines - 1) * pitch + 12 * LayoutOracle.CAP_HEIGHT + 2 * UnitSpacing.CELL_INSET, oracle.rowHeight(row, LETTER), 1e-6);

        Unit head = new Unit(0, 0, Kind.TABLE_ROW, "one  \u2502  " + wide, 12, true, 0, 0, 0, false);
        int headLines = oracle.breaks(new Unit(0, 0, Kind.PARAGRAPH, wide, 12, true, 0, 0, 0, false), LETTER.contentWidth() / 2 - 10, false).size();
        assertEquals(top + (headLines - 1) * pitch, oracle.layout(List.of(head), LETTER).ofUnit(0).getFirst().baseline(), 1e-6,
                "a short head stands on the row's last line, where the head of more lines ends");
    }

    @Test
    void aRowTallerThanAPageRunsOnUnderTheHeader() {
        Unit header = table(0, 0, 1).getFirst();
        String tall = "words ".repeat(1400).strip();
        Unit row = new Unit(1, 0, Kind.TABLE_ROW, tall, 12, false, 0, 2 * UnitSpacing.CELL_INSET, 0, false);
        Unit after = new Unit(2, 0, Kind.TABLE_ROW, "after  \u2502  it", 12, false, 0, 2 * UnitSpacing.CELL_INSET, 0, false);
        assertTrue(oracle.rowHeight(row, LETTER) > LETTER.bandHeight());

        var layout = oracle.layout(List.of(header, row, after), LETTER);

        assertEquals(0, layout.ofUnit(1).getFirst().page(), "it starts where it stands");
        assertTrue(layout.ofUnit(2).getFirst().page() > 0, "what follows it follows its end");
        assertEquals(layout.ofUnit(2).getFirst().page() + 1, layout.ofUnit(0).size(), "a header on every page it reaches");
    }

    private static List<List<Integer>> plain(List<int[]> lines) {
        return lines.stream().map(l -> List.of(l[0], l[1], l[2])).toList();
    }

    @Test
    void piecesBeginAfterSpacesHyphensAndSlashes() {
        List<int[]> pieces = GreedyFxOracle.pieces("self-contained words/and-more  end -x 1/2");
        assertEquals(List.of(List.of(0, 5, 1), List.of(5, 14, 0), List.of(15, 21, 1), List.of(21, 25, 0), List.of(25, 29, 0),
                List.of(31, 34, 1), List.of(35, 37, 1), List.of(38, 41, 1)), plain(pieces),
                "self-|contained words/|and-|more end -x 1/2: no break after a lone dash, nor before a digit after a slash");
    }

    @Test
    void aLineCostsAsTypstCountsIt() {
        double sp = 3;
        // A perfect line costs one; stretching within the spaces' natural half costs the cube of the ratio a hundredfold.
        assertEquals(1, GreedyFxOracle.cost(100, 100, 4, sp, 12, true, -1, -1, false), 1e-9);
        assertEquals(Math.pow(1 + 100 * Math.pow(0.5, 3), 2), GreedyFxOracle.cost(100, 97, 4, sp, 12, true, -1, -1, false), 1e-9,
                "a gap of 3 over a stretch of 4 spaces × 1.5");
        // Beyond that, the extra per space over half an em.
        double extra = (12 - 6) / 4.0 / 6;
        assertEquals(Math.pow(1 + 100 * Math.pow(1 + extra, 3), 2), GreedyFxOracle.cost(100, 88, 4, sp, 12, true, -1, -1, false), 1e-9);
        // Shrinking is allowed to a third of the spaces when justified, never otherwise.
        assertEquals(Math.pow(1 + 100 * Math.pow(0.5, 3), 2), GreedyFxOracle.cost(100, 102, 4, sp, 12, true, -1, -1, false), 1e-9);
        assertTrue(GreedyFxOracle.cost(100, 105, 4, sp, 12, true, -1, -1, false) > 1e12, "overfull");
        assertTrue(GreedyFxOracle.cost(100, 101, 4, sp, 12, false, -1, -1, false) > 1e12, "a ragged line cannot shrink");
        // A hyphen's penalty, raised by 15% for every letter under five on either side, doubled after a dash.
        assertEquals(Math.pow(1 + GreedyFxOracle.HYPHEN_COST, 2), GreedyFxOracle.cost(100, 100, 4, sp, 12, true, 6, 9, false), 1e-9);
        assertEquals(Math.pow(1 + 1.45 * GreedyFxOracle.HYPHEN_COST, 2), GreedyFxOracle.cost(100, 100, 4, sp, 12, true, 2, 13, false), 1e-9);
        assertEquals(Math.pow(1 + 2 * GreedyFxOracle.HYPHEN_COST, 2), GreedyFxOracle.cost(100, 100, 4, sp, 12, true, 6, 9, true), 1e-9);
    }

    @Test
    void widthsAddUpWordByWord() {
        Font font = oracle.font(prose(""));
        double sp = oracle.width(" ", font);
        double sum = oracle.width("aaaa", font) + sp + oracle.width("bbbb", font) + sp + oracle.width("materi-", font);
        assertEquals(oracle.width("aaaa bbbb materi-", font), sum, 0.02, "no kerning: a line's width is its words' and spaces'");
    }

    @Test
    void hyphenatesTheWordThatDidNotFitWhereTheLineWouldStretchTooFar() {
        Unit unit = prose("aaaa bbbb materialization cccc");
        Font font = oracle.font(unit);
        assertArrayEquals(new int[] {2, 4, 6, 8, 11}, Hyphenator.english().points("materialization"), "ma|te|ri|al|iza|tion");
        double avail = oracle.width("aaaa bbbb materi-", font);

        List<int[]> lines = oracle.breaks(unit, avail);

        assertEquals(List.of(List.of(0, 16, 1), List.of(16, 30, 0)), plain(lines),
                "'aaaa bbbb materi-' with the hyphen, then 'alization cccc' from the cut");
    }

    @Test
    void leavesTheWordWholeWhereTheSpacesCanTakeTheGap() {
        // Nineteen spaces stretch by half a space each: the gap a four-letter head would fill costs less than the hyphen.
        String filler = "aa ".repeat(19) + "aa";
        Unit unit = prose(filler + " materialization zz");
        Font font = oracle.font(unit);
        double avail = oracle.width(filler + " mate-", font);

        List<int[]> lines = oracle.breaks(unit, avail);

        assertEquals(List.of(List.of(0, filler.length(), 0), List.of(filler.length() + 1, unit.text().length(), 0)), plain(lines));
    }

    @Test
    void aTableCellIsNeitherHyphenatedNorShrunk() {
        Unit unit = prose("aaaa bbbb materialization cccc");
        Font font = oracle.font(unit);
        double avail = oracle.width("aaaa bbbb materi-", font);

        assertEquals(List.of(List.of(0, 9, 0), List.of(10, 25, 0), List.of(26, 30, 0)), plain(oracle.breaks(unit, avail, false)));

        Unit three = prose("aaaa bbbb cccc");
        double tight = oracle.width("aaaa bbbb cccc", font) - 0.5;
        assertEquals(List.of(List.of(0, 14, 0)), plain(oracle.breaks(three, tight, true)), "justified: the spaces shrink half a point");
        assertEquals(List.of(List.of(0, 9, 0), List.of(10, 14, 0)), plain(oracle.breaks(three, tight, false)), "ragged: they do not");
        Unit row = new Unit(0, 0, Kind.TABLE_ROW, "aaaa bbbb cccc", 12, false, 0, 0, 0, false);
        assertEquals(List.of(List.of(0, 9, 0), List.of(10, 14, 0)), plain(oracle.breaks(row, tight)), "a row's text is a cell's");
    }

    @Test
    void anInlineRawSpanIsNotHyphenated() {
        Unit unit = prose("aaaa bbbb materialization cccc")
                .withInline(List.of(new InlineText.Run(10, 25, true, false, false, null)), List.of(), 0);
        Font font = oracle.font(unit);
        double avail = oracle.width("aaaa bbbb materi-", font);

        List<int[]> lines = oracle.breaks(unit, avail);

        assertEquals(List.of(0, 9, 0), List.of(lines.getFirst()[0], lines.getFirst()[1], lines.getFirst()[2]));
        assertTrue(lines.stream().noneMatch(l -> l[2] == 1), lines.stream().map(l -> l[0] + "-" + l[1] + "/" + l[2]).toList().toString());
    }

    @Test
    void aWordLongerThanTheLineIsHyphenatedWhereItsHeadFits() {
        Unit unit = prose("materialization");
        Font font = oracle.font(unit);
        double avail = oracle.width("materi-", font) + 1;

        assertEquals(List.of(List.of(0, 6, 1), List.of(6, 11, 1), List.of(11, 15, 0)), plain(oracle.breaks(unit, avail)),
                "materi- / aliza- / tion: the tail is too wide too");
        assertEquals(List.of(List.of(0, 15, 0)), plain(oracle.breaks(unit, avail, false)), "a cell's word overflows whole");
    }

    @Test
    void verbatimAndParagraphsKeepTheirOwnLines() {
        Unit raw = new Unit(0, 0, Kind.VERBATIM, "alpha beta gamma\n\ndelta", 10, false, 0, 0, 0, false);
        assertEquals(List.of(List.of(0, 16, 0), List.of(17, 17, 0), List.of(18, 23, 0)), plain(oracle.breaks(raw, 20)));
        Unit two = prose("aaaa bbbb\ncccc");
        double avail = oracle.width("aaaa bbbb cccc dddd", oracle.font(two));
        assertEquals(List.of(List.of(0, 9, 0), List.of(10, 14, 0)), plain(oracle.breaks(two, avail)), "a paragraph break is a line break");
    }
}

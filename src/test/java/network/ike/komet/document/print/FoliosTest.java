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

import network.ike.komet.document.print.LayoutOracle.Kind;
import network.ike.komet.document.print.LayoutOracle.Line;
import network.ike.komet.document.print.LayoutOracle.Unit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The numbers a document's pages print: roman before the body, none on the title page, arabic
 * from 1 in the body, and arabic throughout for a document with no front matter.
 */
class FoliosTest {

    /** A title page, a contents page, a tables page, then the body: as the regulatory book opens, in small. */
    private static final Folios BOOK = new Folios(3, true);

    private static List<String> labels(Folios folios, int pages) {
        return IntStream.range(0, pages).mapToObj(folios::label).toList();
    }

    @Test
    void aDocumentWithNoFrontMatterIsNumberedInArabicFromOne() {
        assertEquals(List.of("1", "2", "3", "4"), labels(Folios.NONE, 4));
        assertFalse(Folios.NONE.frontMatter());
        assertFalse(Folios.NONE.front(0));
        assertEquals(1, Folios.NONE.number(0));
    }

    @Test
    void theFrontMatterIsRomanTheTitlePageShowsNoNumberAndTheBodyStartsAtOne() {
        assertEquals(List.of("", "ii", "iii", "1", "2", "3"), labels(BOOK, 6));
        assertTrue(BOOK.frontMatter());
        assertTrue(BOOK.front(2), "the last list's page");
        assertFalse(BOOK.front(3), "the body's first page");
    }

    @Test
    void theTitlePageIsCountedAsPageOneOfTheFrontMatter() {
        assertEquals("i", BOOK.counted(0), "counted, though it shows no number");
        assertEquals(1, BOOK.number(0));
        assertEquals("ii", BOOK.counted(1));
        assertEquals(1, BOOK.number(3), "the body starts again at 1");
        assertEquals(3, BOOK.number(5));
    }

    @Test
    void frontMatterWithNoTitleShowsItsFirstPagesNumber() {
        assertEquals(List.of("i", "ii", "1"), labels(new Folios(2, false), 3), "a list on the first page, not a title page");
    }

    @Test
    void aTitlePageNeedsFrontMatter() {
        assertEquals(Folios.NONE, new Folios(0, true), "a title at the top of page 1 is no title page");
        assertThrows(IllegalArgumentException.class, () -> new Folios(-1, false));
    }

    @Test
    void romanNumeralsAreWrittenAsTypstWritesThem() {
        assertEquals(List.of("i", "ii", "iii", "iv", "v", "vi", "ix", "x", "xiii", "xiv", "xl", "xc", "cd", "mcmxciv"),
                IntStream.of(1, 2, 3, 4, 5, 6, 9, 10, 13, 14, 40, 90, 400, 1994).mapToObj(Folios::roman).toList());
        assertThrows(IllegalArgumentException.class, () -> Folios.roman(0));
    }

    @Test
    void aFolioAsTypedNamesItsPage() {
        assertEquals(OptionalInt.of(3), BOOK.pageOf("1", 6), "page 1 is the body's first page, not the title page");
        assertEquals(OptionalInt.of(5), BOOK.pageOf(" 3 ", 6));
        assertEquals(OptionalInt.of(1), BOOK.pageOf("ii", 6));
        assertEquals(OptionalInt.of(2), BOOK.pageOf("III", 6), "in either case");
        assertEquals(OptionalInt.of(0), BOOK.pageOf("i", 6), "the title page is counted");
        assertEquals(OptionalInt.empty(), BOOK.pageOf("iv", 6), "the front matter has three pages");
        assertEquals(OptionalInt.empty(), BOOK.pageOf("4", 6), "the body has three pages");
        assertEquals(OptionalInt.empty(), BOOK.pageOf("0", 6));
        assertEquals(OptionalInt.empty(), BOOK.pageOf("iiii", 6), "not a numeral as it is written");
        assertEquals(OptionalInt.empty(), BOOK.pageOf("two", 6));
        assertEquals(OptionalInt.empty(), BOOK.pageOf("", 6));
        assertEquals(OptionalInt.empty(), BOOK.pageOf(null, 6));
        assertEquals(OptionalInt.empty(), BOOK.pageOf("99999999999", 6));
        assertEquals(OptionalInt.of(2), Folios.NONE.pageOf("3", 4), "with no front matter a number is the page's place");
        assertEquals(OptionalInt.empty(), Folios.NONE.pageOf("i", 4), "and no page is roman");
    }

    private static Unit unit(int index, Kind kind) {
        return new Unit(index, index, kind, "text", 12, false, 0, 0, 0, false);
    }

    @Test
    void theBodyBeginsOnThePageOfTheUnitThatOpensIt() {
        List<Unit> units = List.of(unit(0, Kind.TITLE), unit(1, Kind.HEADING), unit(2, Kind.ENTRY),
                unit(3, Kind.HEADING).withOpensBody(true), unit(4, Kind.PARAGRAPH));
        List<Line> lines = List.of(new Line(0, 0, 72, 100, 0, 4, false), new Line(1, 1, 72, 100, 0, 4, false), new Line(2, 1, 72, 120, 0, 4, false),
                new Line(2, 2, 72, 100, 0, 4, false), new Line(3, 3, 72, 100, 0, 4, false), new Line(4, 3, 72, 120, 0, 4, false));

        assertEquals(new Folios(3, true), Folios.of(units, lines), "the contents run over two pages, so the body is the fourth");
    }

    @Test
    void aLayoutWithNoUnitThatOpensABodyHasNoFrontMatter() {
        List<Unit> units = List.of(unit(0, Kind.TITLE), unit(1, Kind.PARAGRAPH));
        List<Line> lines = List.of(new Line(0, 0, 72, 100, 0, 4, false), new Line(1, 0, 72, 120, 0, 4, false));

        assertEquals(Folios.NONE, Folios.of(units, lines));
        assertEquals(Folios.NONE, Folios.of(List.of(), List.of()));
    }

    @Test
    void aBodyWhoseFirstUnitHasNoLineBeginsAtTheNextThatHas() {
        List<Unit> units = List.of(unit(0, Kind.HEADING), unit(1, Kind.ENTRY), unit(2, Kind.HEADING).withOpensBody(true), unit(3, Kind.PARAGRAPH));
        List<Line> lines = List.of(new Line(0, 0, 72, 100, 0, 4, false), new Line(1, 0, 72, 120, 0, 4, false), new Line(3, 1, 72, 100, 0, 4, false));

        assertEquals(new Folios(1, false), Folios.of(units, lines), "no title unit, so no title page");
        assertEquals(new Folios(1, false), Folios.of(units, lines.subList(0, 2)), "no line of the body yet: every page laid out is front matter");
    }
}

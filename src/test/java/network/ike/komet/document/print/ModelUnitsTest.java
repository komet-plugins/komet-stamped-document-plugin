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

import network.ike.komet.document.print.DocumentModel.Heading;
import network.ike.komet.document.print.DocumentModel.Item;
import network.ike.komet.document.print.DocumentModel.ListBlock;
import network.ike.komet.document.print.DocumentModel.Paragraph;
import network.ike.komet.document.print.DocumentModel.Table;
import network.ike.komet.document.print.DocumentModel.Verbatim;
import network.ike.komet.document.print.LayoutOracle.Kind;
import network.ike.komet.document.print.LayoutOracle.Unit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Units from a print model: numbered as the writer labels blocks, with Typst's markers and spacing. */
class ModelUnitsTest {

    @Test
    void theTitleAndEveryTopLevelBlockAreNumberedAsTheWriterLabelsThem() {
        DocumentModel model = new DocumentModel("Title", List.of(
                new Heading(1, "Section"),
                new Paragraph("Prose here."),
                new ListBlock(ListBlock.Kind.UNORDERED, "", List.of(
                        new Item("", "first", List.of(new ListBlock(ListBlock.Kind.UNORDERED, "", List.of(new Item("", "nested", List.of()))))),
                        new Item("", "second", List.of()))),
                new Table("Caption", List.of(1, 1), 1, List.of(List.of("A", "B"), List.of("c", "d"))),
                new Verbatim("", "raw one\nraw two")));

        List<Unit> units = ModelUnits.of(model, 12);

        assertEquals(List.of(0, 1, 2, 3, 3, 3, 4, 4, 4, 5), units.stream().map(Unit::block).toList());
        assertEquals(Kind.TITLE, units.get(0).kind());
        assertEquals(21.6, units.get(0).fontSize(), 0.001);
        assertEquals(Kind.HEADING, units.get(1).kind());
        assertEquals(18, units.get(1).fontSize(), 0.001);
        assertTrue(units.get(1).keepWithNext());
        assertEquals("• first", units.get(3).text());
        assertEquals("‣ nested", units.get(4).text(), "a nested item carries Typst's second-level marker");
        assertEquals(24, units.get(4).indent(), 0.001);
        assertEquals("• second", units.get(5).text());
        assertEquals(Kind.OTHER, units.get(6).kind());
        assertEquals("Caption", units.get(6).text());
        assertEquals("A  │  B", units.get(7).text());
        assertTrue(units.get(7).bold(), "a header row is bold");
        assertEquals(Kind.VERBATIM, units.get(9).kind());
        assertEquals(8.16, units.get(9).fontSize(), 0.001, "raw text at the size Typst sets it: the writer's 0.85em on Typst's own 0.8em");
        assertEquals("raw one\nraw two", units.get(9).text());
        assertEquals(ModelUnits.bullet(0), ModelUnits.bullet(3));
    }

    @Test
    void theAuthorAndTheDateAreUnitsOfTheTitlesBlockBeneathIt() {
        DocumentModel model = new DocumentModel("Title", List.of(new Paragraph("Text.")), DocumentModel.Lists.NONE,
                new DocumentModel.Byline("An Author", "November 10, 2026"));
        List<Unit> units = ModelUnits.of(model, 12);
        assertEquals(List.of(Kind.TITLE, Kind.BYLINE, Kind.BYLINE, Kind.PARAGRAPH), units.stream().map(Unit::kind).toList());
        assertEquals(List.of(0, 0, 0, 1), units.stream().map(Unit::block).toList(), "the byline is the title's block, as the writer labels it");
        assertEquals(12 * TypstWriter.AUTHOR_SCALE, units.get(1).fontSize(), 1e-9);
        assertEquals(12 * TypstWriter.DATE_SCALE, units.get(2).fontSize(), 1e-9);
        assertEquals(false, units.get(1).bold());
        assertEquals(network.ike.komet.document.surface.print.settings.TitleSpacing.STANDARD.authors().in(12), UnitSpacing.gap(units.get(0), units.get(1)), 1e-9, "the gap the writer leaves under the title");
        assertEquals(network.ike.komet.document.surface.print.settings.TitleSpacing.STANDARD.revision().in(12), UnitSpacing.gap(units.get(1), units.get(2)), 1e-9, "and under the author");
        assertEquals(12 * 1.2, UnitSpacing.gap(units.get(2), units.get(3)), 1e-9, "the title block's own space below");
        assertTrue(units.get(0).opensChapter() && !units.get(1).opensChapter());
    }

    @Test
    void aFrontListIsItsTitleAsAHeadingAndAnEntryUnitPerEntry() {
        DocumentModel model = new DocumentModel("Title", List.of(
                new DocumentModel.FrontList(DocumentModel.FrontList.Kind.CONTENTS, List.of(
                        new DocumentModel.Entry("1 One", 0, "a", 2), new DocumentModel.Entry("1.1 Deeper", 1, "b", 3))),
                new Heading(1, "1 One", "a")));

        List<Unit> units = ModelUnits.of(model, 12);

        assertEquals(List.of(0, 1, 1, 1, 2), units.stream().map(Unit::block).toList(), "a list is one block of the writer's");
        assertEquals(Kind.HEADING, units.get(1).kind());
        assertEquals("Contents", units.get(1).text());
        assertEquals(18, units.get(1).fontSize(), 0.001, "a level-1 heading");
        assertEquals(Kind.ENTRY, units.get(2).kind());
        assertEquals("1 One", units.get(2).text(), "the entry's text alone: its page is drawn after a leader by the renderer");
        assertEquals(0, units.get(2).indent(), 0.001);
        assertEquals(12, units.get(3).indent(), 0.001, "an em of indent a depth");
        assertTrue(!units.get(2).keepWithNext());
        assertTrue(units.get(2).bold() && !units.get(3).bold(), "the outermost entries of a list that has levels are its chief ones, set bold");
        assertEquals(12 * TypstWriter.ENTRY_GROUP, UnitSpacing.gap(units.get(1), units.get(2)), 0.001,
                "a chief entry keeps its own space above, larger than the heading's below");
        assertEquals(0.65 * 12, UnitSpacing.gap(units.get(2), units.get(3)), 0.001, "an entry under it stands a leading from it");
        assertEquals(12 * TypstWriter.ENTRY_GROUP, UnitSpacing.gap(units.get(3), units.get(2)), 0.001, "and a chief entry after it keeps the group's space");

        List<Unit> flat = ModelUnits.of(new DocumentModel("Title", List.of(
                new DocumentModel.FrontList(DocumentModel.FrontList.Kind.FIGURES, List.of(
                        new DocumentModel.Entry("Figure 1. A tree", 0, "a", 2), new DocumentModel.Entry("Figure 2. A tornado", 0, "b", 3))),
                new Heading(1, "1 One", "a"))), 12);
        assertTrue(!flat.get(2).bold() && !flat.get(3).bold(), "a list of one level has no chief entries");
        assertEquals(9, UnitSpacing.gap(flat.get(1), flat.get(2)), 0.001, "the heading's weak spacing stands over its first entry");
        assertEquals(0.65 * 12, UnitSpacing.gap(flat.get(2), flat.get(3)), 0.001, "its entries a leading apart");
        assertEquals(16.2, UnitSpacing.gap(units.get(3), units.get(4)), 0.001, "a heading after the last entry keeps its own space");
    }

    @Test
    void eachFrontListOpensAPageAndSoDoesTheBodyAfterTheLists() {
        DocumentModel model = new DocumentModel("Title", List.of(
                new DocumentModel.FrontList(DocumentModel.FrontList.Kind.CONTENTS, List.of(new DocumentModel.Entry("1 One", 0, "a", 3))),
                new DocumentModel.FrontList(DocumentModel.FrontList.Kind.TABLES, List.of(new DocumentModel.Entry("Table 1. Costs", 0, "t", 3))),
                new Heading(1, "1 One", "a"),
                new Paragraph("Prose."),
                new Table("Table 1. Costs", List.of(1), 0, List.of(List.of("x")), "t")));

        List<Unit> units = ModelUnits.of(model, 12);

        assertEquals(List.of("Title", "Contents", "1 One", "Tables", "Table 1. Costs", "1 One", "Prose.", "Table 1. Costs", "x"),
                units.stream().map(Unit::text).toList());
        assertEquals(List.of(false, true, false, true, false, true, false, false, false), units.stream().map(Unit::pageBefore).toList(),
                "the lists' titles and the body's first unit open pages: the title page stands alone");
        assertEquals(List.of(false, false, false, false, false, true, false, false, false), units.stream().map(Unit::opensBody).toList(),
                "the body's first unit opens it, and no other: its page is page 1");
        assertTrue(ModelUnits.of(new DocumentModel("Title", List.of(new Heading(1, "One"), new Paragraph("Prose."))), 12).stream()
                .noneMatch(Unit::pageBefore), "no list, no page opened");
        assertTrue(ModelUnits.of(new DocumentModel("Title", List.of(new Heading(1, "One"), new Paragraph("Prose."))), 12).stream()
                .noneMatch(Unit::opensBody), "no front matter, no body to open");
    }

    @Test
    void spacingFollowsTypstsRules() {
        Unit title = new Unit(0, 0, Kind.TITLE, "T", 21.6, true, 0, 0, 14.4, false);
        Unit heading = new Unit(1, 1, Kind.HEADING, "H", 18, true, 0, 16.2, 9, true);
        Unit prose = new Unit(2, 2, Kind.PARAGRAPH, "p", 12, false, 0, 13.2, 0, false);
        Unit item = new Unit(3, 3, Kind.LIST_ITEM, "i", 12, false, 12, 7.8, 0, false);
        Unit raw = new Unit(4, 4, Kind.VERBATIM, "r", 8.16, false, 0, 13.2, 0, false);

        assertEquals(14.4, UnitSpacing.gap(title, prose), 0.001, "the title's gap is 1.2em of the body");
        assertEquals(9, UnitSpacing.gap(heading, prose), 0.001, "a heading's weak spacing replaces the paragraph gap");
        assertEquals(16.2, UnitSpacing.gap(prose, heading), 0.001);
        assertEquals(13.2, UnitSpacing.gap(prose, prose), 0.001);
        assertEquals(7.8, UnitSpacing.gap(item, item), 0.001, "tight items a leading apart");
        assertEquals(13.2 + UnitSpacing.VERBATIM_INSET, UnitSpacing.gap(prose, raw), 0.001);
        assertEquals(13.2 + UnitSpacing.VERBATIM_INSET, UnitSpacing.gap(raw, prose), 0.001);
        assertEquals(LayoutOracle.PITCH * 8.16, UnitSpacing.pitch(raw), 0.001);
        assertEquals(LayoutOracle.PITCH * 12, UnitSpacing.pitch(prose), 0.001);
        assertEquals(0.65 * 12, UnitSpacing.of(Kind.LIST_ITEM, Kind.LIST_ITEM, 12, 12)[0], 0.001);
        assertEquals(0.65 * 12, UnitSpacing.of(Kind.OTHER, Kind.PARAGRAPH, 12, 12, true)[1], 0.001, "a caption a leading above its table");
        Unit row = new Unit(5, 5, Kind.TABLE_ROW, "r", 12, false, 0, UnitSpacing.of(Kind.TABLE_ROW, Kind.TABLE_ROW, 12, 12)[0], 0, false);
        assertEquals(2 * UnitSpacing.CELL_INSET, UnitSpacing.gap(row, row), 0.001, "rows only their cells' insets apart");
        assertEquals(13.2 + UnitSpacing.CELL_INSET, UnitSpacing.gap(row, prose), 0.001, "and its last");
    }
}

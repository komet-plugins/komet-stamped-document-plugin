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
import network.ike.komet.document.print.DocumentModel.Drawn;
import network.ike.komet.document.print.DocumentModel.Image;
import network.ike.komet.document.print.DocumentModel.Item;
import network.ike.komet.document.print.DocumentModel.ListBlock;
import network.ike.komet.document.print.DocumentModel.Paragraph;
import network.ike.komet.document.print.DocumentModel.Table;
import network.ike.komet.document.print.DocumentModel.Verbatim;
import network.ike.komet.document.print.LayoutOracle.Kind;
import network.ike.komet.document.print.LayoutOracle.Unit;

import java.util.ArrayList;
import java.util.List;

/**
 * The layout oracles' units straight from a print model, without the Komet store or the
 * surface: what the layout fidelity harness lays out. The units mirror the ones the
 * stamped document surface builds from its print areas — one per paragraph-like piece, the
 * title and every top-level block numbered as the Typst writer labels them, the same kinds
 * and the same spacing rules — with the model's own text, which is what the Typst writer
 * writes, so the oracle aligns Typst's words to it directly.
 */
public final class ModelUnits {

    private ModelUnits() { }

    /**
     * The units of a model.
     *
     * @param model the print model
     * @param base  the body size in points
     * @return the units in document order
     */
    public static List<Unit> of(DocumentModel model, double base) {
        List<Unit> out = new ArrayList<>();
        InlineText.Numbering notes = new InlineText.Numbering();
        int block = 0;
        Kind previous = null;
        if (!model.title().isEmpty()) {
            previous = add(out, notes, block, Kind.TITLE, model.title(), base * 1.8, true, 0, previous, base);
            out.set(0, out.getFirst().withOpensChapter(true));
            // the author and the date beneath it are the title's block, as the writer sets them
            if (!model.byline().author().isEmpty()) {
                previous = add(out, notes, block, Kind.BYLINE, model.byline().author(), base * TypstWriter.AUTHOR_SCALE, false, 0, previous, base);
            }
            if (!model.byline().date().isEmpty()) {
                previous = add(out, notes, block, Kind.BYLINE, model.byline().date(), base * TypstWriter.DATE_SCALE, false, 0, previous, base);
            }
            block++;
        }
        boolean afterFront = false;                               // whether the block before was a front list or the abstract
        boolean opened = false;                                   // whether a unit has opened the body
        for (Block top : model.blocks()) {
            int first = out.size();
            previous = block(out, notes, block++, top, base, previous, 0);
            boolean list = top instanceof DocumentModel.FrontList;
            if ((list || afterFront) && out.size() > first) {
                // each front list opens a page, and so does the body after the last of them, or after an abstract with no
                // list: the title page stands alone, with the abstract when there is one;
                // the body's first unit opens it, and its page is page 1
                out.set(first, out.get(first).withPageBefore(true).withOpensBody(!list && !opened));
                opened |= !list;
            }
            afterFront = list || top instanceof DocumentModel.Abstract;
        }
        return out;
    }

    /** Typst's list markers by nesting depth, cycling: {@code list.marker} defaults to ([•], [‣], [–]). */
    public static String bullet(int depth) {
        return switch (depth % 3) {
            case 0 -> "•";
            case 1 -> "‣";
            default -> "–";
        };
    }

    private static Kind block(List<Unit> out, InlineText.Numbering notes, int block, Block b, double base, Kind previous, int depth) {
        switch (b) {
            case Heading h -> {
                double scale = switch (Math.max(1, h.level())) {
                    case 1 -> 1.5;
                    case 2 -> 1.25;
                    case 3 -> 1.1;
                    default -> 1.0;
                };
                int at = out.size();
                Kind kind = add(out, notes, block, Kind.HEADING, h.text(), base * scale, true, 0, previous, base);
                if (h.level() <= 1) {
                    out.set(at, out.get(at).withOpensChapter(true));     // a part or a top-level section: a chapter, as the writer's level 1
                }
                return kind;
            }
            case Paragraph p -> {
                return add(out, notes, block, Kind.PARAGRAPH, p.text(), base, false, 0, previous, base);
            }
            case Admonition a -> {
                Kind last = previous;
                boolean first = true;
                for (Block inner : a.blocks()) {
                    if (first && inner instanceof Paragraph p) {
                        last = add(out, notes, block, Kind.PARAGRAPH, a.label() + " — " + p.text(), base, false, 0, last, base);
                    } else {
                        last = block(out, notes, block, inner, base, last, depth);
                    }
                    first = false;
                }
                return last;
            }
            case Verbatim v -> {
                Kind last = previous;
                if (!v.title().isEmpty()) {
                    last = add(out, notes, block, Kind.OTHER, v.title(), base * TypstWriter.TITLE_SCALE, true, 0, last, base);
                }
                return add(out, notes, block, Kind.VERBATIM, v.text(), base * TypstWriter.RAW_SCALE, false, 0, last, base);
            }
            case ListBlock l -> {
                Kind last = previous;
                if (!l.title().isEmpty()) {
                    last = add(out, notes, block, Kind.OTHER, l.title(), base * TypstWriter.TITLE_SCALE, true, 0, last, base);
                }
                int n = 0;
                for (Item item : l.items()) {
                    n++;
                    String marker = switch (l.kind()) {
                        case ORDERED -> n + ".";
                        case DESCRIPTION -> item.term() + ":";
                        case UNORDERED, BIBLIOGRAPHY -> bullet(depth);
                    };
                    last = add(out, notes, block, Kind.LIST_ITEM, marker + " " + item.text(), base, false, base * (1 + depth), last, base);
                    for (Block inner : item.blocks()) {
                        last = block(out, notes, block, inner, base, last, depth + 1);
                    }
                }
                return last;
            }
            case Table t -> {
                Kind last = previous;
                if (!t.title().isEmpty()) {
                    last = add(out, notes, block, Kind.OTHER, t.title(), base, true, 0, last, base, true);
                }
                int r = 0;
                for (List<String> row : t.rows()) {
                    boolean header = r++ < t.headerRows();
                    int at = out.size();
                    last = add(out, notes, block, Kind.TABLE_ROW, String.join(TableCells.BAR, row), base, header, 0, last, base);
                    out.set(at, out.get(at).withColumns(t.weightsOf(r - 1)));   // a spanning cell is as wide as its columns together
                }
                return last;
            }
            case Image i -> {
                return add(out, notes, block, Kind.OTHER, "Image: " + i.target().replace("_", "\\_").replace("*", "\\*") + (i.title().isEmpty() ? "" : " — " + i.title()), base * 0.85, false, 0, previous, base);
            }
            case Drawn d -> {
                double[] spacing = UnitSpacing.of(Kind.FIGURE, previous, base, base);
                out.add(new Unit(out.size(), block, Kind.FIGURE, LayoutOracle.FIGURE_WORD, base, false, 0, spacing[0], spacing[1], false,
                        d.figure().width(), d.figure().height()));
                return Kind.FIGURE;
            }
            case Container c -> {
                Kind last = previous;
                if (!c.title().isEmpty()) {
                    last = add(out, notes, block, Kind.OTHER, c.title(), base * TypstWriter.BOX_TITLE_SCALE, true, 0, last, base);
                }
                for (Block inner : c.blocks()) {
                    last = block(out, notes, block, inner, base, last, depth);
                }
                return last;
            }
            case DocumentModel.Abstract a -> {
                // on the title page, set as the blocks it holds: one block of the model, as the writer labels it
                Kind last = previous;
                for (Block inner : a.blocks()) {
                    last = block(out, notes, block, inner, base, last, depth);
                }
                return last;
            }
            case DocumentModel.FrontList f -> {
                // its title is a level-1 heading; an entry's text alone is the unit, the page after its leader is the renderer's
                int at = out.size();
                Kind last = add(out, notes, block, Kind.HEADING, f.title(), base * 1.5, true, 0, previous, base);
                out.set(at, out.get(at).withOpensChapter(true));
                // the outermost entries of a list that has levels are its chief ones: bold, with their own space above
                boolean levelled = FrontLists.levelled(f.entries().stream().map(DocumentModel.Entry::depth).toList());
                for (DocumentModel.Entry entry : f.entries()) {
                    last = add(out, notes, block, Kind.ENTRY, entry.text(), base, levelled && entry.depth() <= 0, base * Math.max(0, entry.depth()), last, base);
                }
                return last;
            }
        }
    }

    private static Kind add(List<Unit> out, InlineText.Numbering notes, int block, Kind kind, String text, double size, boolean bold, double indent, Kind previous, double base) {
        return add(out, notes, block, kind, text, size, bold, indent, previous, base, false);
    }

    private static Kind add(List<Unit> out, InlineText.Numbering notes, int block, Kind kind, String text, double size, boolean bold, double indent, Kind previous, double base,
                            boolean caption) {
        double[] spacing = kind == Kind.ENTRY ? UnitSpacing.ofEntry(bold, previous, base) : UnitSpacing.of(kind, previous, size, base, caption);
        InlineText inline = kind == Kind.VERBATIM ? InlineText.literal(text) : InlineText.of(text, notes);
        out.add(new Unit(out.size(), block, kind, inline.text(), size, bold, indent, spacing[0], spacing[1], kind == Kind.HEADING)
                .withInline(inline.runs(), inline.marks(0), 0));
        for (InlineText.Note note : inline.notes()) {
            if (!note.repeat()) {
                InlineText drawn = note.drawn();
                out.add(new Unit(out.size(), block, Kind.FOOTNOTE, drawn.text(), base * LayoutOracle.FOOTNOTE_SCALE, false, 0, 0, 0, false)
                        .withInline(drawn.runs(), List.of(), note.number()));
            }
        }
        return kind;
    }
}

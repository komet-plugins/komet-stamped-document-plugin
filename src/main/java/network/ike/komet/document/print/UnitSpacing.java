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
import network.ike.komet.document.print.LayoutOracle.Unit;

/**
 * The space between units, in points, as Typst sets it for the writer's page — read off the
 * golden layouts of the fidelity corpus (2026-10-20) rather than guessed from the preamble:
 *
 * <ul>
 *   <li>paragraphs are {@code par.spacing} = 1.1em of the body apart; tight list items a leading
 *       (0.65em) apart; table rows only the 5pt their cells inset at top and bottom;</li>
 *   <li>a heading's weak spacing — 0.9em of its own size above, 0.5em below — replaces the
 *       paragraph spacing next to it rather than competing with it; two weak spacings collapse
 *       to the larger;</li>
 *   <li>the title's {@code block(below: 1.2em)} is 1.2em of the body; a block title keeps 1em above
 *       and 0.5em below; a table's caption a leading (0.65em) below, over the first row's inset;</li>
 *   <li>a raw block adds its 8pt inset above and below its lines, which are the same 1.364em apart as any;</li>
 *   <li>a front list's entries stand a leading (0.65em) apart, as the writer's entry block keeps it; a chief
 *       entry keeps {@link TypstWriter#ENTRY_GROUP} above it ({@link #ofEntry}).</li>
 * </ul>
 *
 * One place for the rule, so the surface's units and the model's agree.
 */
public final class UnitSpacing {

    /** The inset a raw block keeps above and below its lines. */
    public static final double VERBATIM_INSET = 8;
    /** The inset a table cell keeps above and below its text. */
    public static final double CELL_INSET = 5;

    private UnitSpacing() { }

    /**
     * @param kind     the unit's kind
     * @param previous the kind of the unit before it, or {@code null} for the first
     * @param size     the unit's font size
     * @param base     the body size
     * @param caption  whether an {@link Kind#OTHER} unit is a table's caption (set above the table) rather than a block title
     * @return {@code {above, below}} in points; a positive {@code below} is a weak spacing of the unit's own
     */
    public static double[] of(Kind kind, Kind previous, double size, double base, boolean caption) {
        return of(kind, previous, size, base, caption, network.ike.komet.document.surface.print.settings.TitleSpacing.STANDARD);
    }

    /**
     * The spacing of a unit, on a title page spaced as given.
     *
     * @param kind     the unit's kind
     * @param previous the kind of the unit before it, or {@code null} for the first
     * @param size     the unit's font size
     * @param base     the body size
     * @param caption  whether an {@link Kind#OTHER} unit is a table's caption
     * @param title    the title page's spacing, every space given: the theme's, with what the document asks for
     * @return {@code {above, below}} in points
     */
    public static double[] of(Kind kind, Kind previous, double size, double base, boolean caption,
                              network.ike.komet.document.surface.print.settings.TitleSpacing title) {
        double above = switch (kind) {
            case TITLE -> 0;
            // the author beneath the title, or the date beneath the author: the writer's ike-title sets the gaps
            case BYLINE -> previous == Kind.BYLINE ? title.revision().in(base) : title.authors().in(base);
            case HEADING -> size * 0.9;
            case LIST_ITEM -> previous == Kind.LIST_ITEM ? base * 0.65 : base * 1.1;
            case ENTRY -> previous == Kind.ENTRY ? base * 0.65 : base * 1.1;      // the writer's entry block keeps 0.65em above and below
            case TABLE_ROW -> previous == Kind.TABLE_ROW ? 2 * CELL_INSET : base * 1.1;
            case OTHER, FIGURE -> base;
            default -> base * 1.1;
        };
        double below = switch (kind) {
            case TITLE, BYLINE -> base * 1.2;
            case HEADING -> size * 0.5;
            case OTHER -> caption ? base * 0.65 : base * 0.5;
            default -> 0;
        };
        return new double[] {above, below};
    }

    /**
     * The spacing of a front list's entry. A chief entry, an outermost one of a list that has
     * levels ({@link FrontLists#levelled}), keeps {@link TypstWriter#ENTRY_GROUP} above it
     * whatever stands before it; any other stands a leading from the entry before.
     *
     * @param chief    whether the entry is a chief one
     * @param previous the kind of the unit before it, or {@code null} for the first
     * @param base     the body size
     * @return {@code {above, below}} in points
     */
    public static double[] ofEntry(boolean chief, Kind previous, double base) {
        double[] spacing = of(Kind.ENTRY, previous, base, base, false);
        return chief ? new double[] {base * TypstWriter.ENTRY_GROUP, spacing[1]} : spacing;
    }

    /** As {@link #of(Kind, Kind, double, double, boolean)} for a unit that is no caption. */
    public static double[] of(Kind kind, Kind previous, double size, double base) {
        return of(kind, previous, size, base, false);
    }

    /**
     * The space from the previous unit's last baseline to this unit's first line's top (its cap
     * height), as Typst leaves it: the previous unit's weak spacing where it has one, this
     * unit's otherwise, the larger when both are weak, plus the raw inset a verbatim unit adds.
     *
     * @param previous the unit before, or {@code null} at a page's top
     * @param unit     the unit
     * @return the space in points
     */
    public static double gap(Unit previous, Unit unit) {
        double inset = unit.kind() == Kind.VERBATIM ? VERBATIM_INSET
                : unit.kind() == Kind.TABLE_ROW && (previous == null || previous.kind() != Kind.TABLE_ROW) ? CELL_INSET : 0;
        if (previous == null) {
            return inset;
        }
        if (unit.kind() == Kind.BYLINE && (previous.kind() == Kind.TITLE || previous.kind() == Kind.BYLINE)) {
            return unit.spaceAbove();                             // within the title's block: its own gap, not the block's below
        }
        boolean previousWeak = previous.spaceBelow() > 0;
        // a chief entry of a front list, the one bold kind of entry, keeps its own space above under a heading too:
        // Typst takes the larger of the heading's below and the entry block's above
        boolean unitWeak = unit.kind() == Kind.HEADING || unit.kind() == Kind.OTHER || unit.kind() == Kind.ENTRY && unit.bold();
        double gap;
        if (previousWeak && unitWeak) {
            gap = Math.max(previous.spaceBelow(), unit.spaceAbove());
        } else if (previousWeak) {
            gap = previous.spaceBelow();
        } else {
            gap = unit.spaceAbove();
        }
        if (previous.kind() == Kind.VERBATIM) {
            gap += VERBATIM_INSET;
        }
        if (previous.kind() == Kind.TABLE_ROW && unit.kind() != Kind.TABLE_ROW) {
            gap += CELL_INSET;
        }
        return gap + inset;
    }

    /** @return the pitch between a unit's baselines, in points */
    public static double pitch(Unit unit) {
        return unit.fontSize() * LayoutOracle.PITCH;
    }
}

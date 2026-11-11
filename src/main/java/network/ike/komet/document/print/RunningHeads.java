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

import network.ike.komet.document.print.LayoutOracle.Line;
import network.ike.komet.document.print.LayoutOracle.Unit;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which pages carry a running head. Book convention leaves it off a page that opens
 * something: the title page, a part's title page, the opening page of each chapter (Chicago
 * Manual of Style 1.10 and 1.11; Bringhurst 4.2.2). A chapter here is a top-level section: a
 * unit that {@link Unit#opensChapter() opens a chapter} takes the running head off the page
 * it begins on, and its folio, if the head carries one, drops to the foot. The Typst writer
 * gives the printed document the same rule ({@link TypstWriter#OPENER_LABEL}).
 *
 * <p>A blank page would carry none either; the writer's page breaks are weak, so none is ever
 * made.
 *
 * @param openers the 0-based pages on which a chapter opens
 */
public record RunningHeads(Set<Integer> openers) {

    /** Every page has its running head. */
    public static final RunningHeads EVERY_PAGE = new RunningHeads(Set.of());

    public RunningHeads {
        openers = Set.copyOf(openers);
    }

    /**
     * The running head's title, short enough to stand on one line beside what is to its right,
     * as the Typst writer's {@code ike-fit} cuts it: the title whole where it fits the room,
     * else as many of its words from the first as fit with an ellipsis after them,
     * {@link TypstWriter#HEAD_WORDS} at least, else nothing.
     *
     * @param title the document's title
     * @param room  the room the title may take, in points
     * @param width the width of a text as the head draws it
     * @return the title as the head shows it, empty where even its first words have no room
     */
    public static String fit(String title, double room, java.util.function.ToDoubleFunction<String> width) {
        String[] words = title.split(" ");
        int n = words.length;
        String shown = title;
        while (n > TypstWriter.HEAD_WORDS && width.applyAsDouble(shown) > room) {
            n--;
            shown = String.join(" ", java.util.Arrays.copyOf(words, n)) + "…";
        }
        return width.applyAsDouble(shown) > room ? "" : shown;
    }

    /**
     * The pages that open a chapter in a layout.
     *
     * @param units the units laid out
     * @param lines the layout's lines
     * @return the rule for those pages
     */
    public static RunningHeads of(List<Unit> units, List<Line> lines) {
        Set<Integer> pages = new TreeSet<>();
        for (Line line : lines) {
            if (line.from() == 0 && line.unit() >= 0 && line.unit() < units.size() && units.get(line.unit()).opensChapter()) {
                pages.add(line.page());
            }
        }
        return new RunningHeads(pages);
    }

    /** @return whether a page carries the running head: every page but one a chapter opens on */
    public boolean shown(int page) {
        return !openers.contains(page);
    }
}

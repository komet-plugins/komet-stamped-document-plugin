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
import java.util.Locale;
import java.util.OptionalInt;

/**
 * The page numbers a document's pages print ({@code dev-document-front-matter}). A page number
 * printed on a page is a folio. A document with front matter, which is a title page and the
 * lists its header asks for, numbers those pages in lowercase roman numerals: the title page is
 * counted as i and shows no number, the lists are ii, iii and onward, and the body starts again
 * at 1 in arabic numerals. A document with no front matter numbers every page in arabic from 1.
 *
 * <p>A page here is a 0-based place in the stack of pages, as {@link Line#page()} has it. The
 * Typst writer prints the same folios ({@link TypstWriter}), so a page of the Page layout and
 * the page of the PDF with the same place show the same number.
 *
 * @param bodyPage  the page the body begins on: 0 for a document with no front matter
 * @param titlePage whether the first page is a title page, which is counted and shows no number
 */
public record Folios(int bodyPage, boolean titlePage) {

    /** The folios of a document with no front matter: arabic from 1. */
    public static final Folios NONE = new Folios(0, false);

    public Folios {
        if (bodyPage < 0) {
            throw new IllegalArgumentException("the body begins on page " + bodyPage);
        }
        titlePage = titlePage && bodyPage > 0;
    }

    /**
     * The folios of a layout: the body begins on the page of the first line of the unit that
     * opens it ({@link Unit#opensBody()}), or of the first unit after it that has a line.
     *
     * @param units the units laid out
     * @param lines the layout's lines
     * @return the folios; {@link #NONE} when no unit opens a body, which is a document with no front matter
     */
    public static Folios of(List<Unit> units, List<Line> lines) {
        int opens = -1;
        for (Unit unit : units) {
            if (unit.opensBody()) {
                opens = unit.index();
                break;
            }
        }
        if (opens < 0) {
            return NONE;
        }
        int body = Integer.MAX_VALUE;
        int last = 0;
        for (Line line : lines) {
            last = Math.max(last, line.page());
            if (line.unit() >= opens) {
                body = Math.min(body, line.page());
            }
        }
        boolean title = !units.isEmpty() && units.getFirst().kind() == LayoutOracle.Kind.TITLE;
        // a body with no line yet leaves every page laid out in the front matter
        return new Folios(body == Integer.MAX_VALUE ? last + 1 : body, title);
    }

    /** @return whether the document has front matter */
    public boolean frontMatter() {
        return bodyPage > 0;
    }

    /** @return whether a page is front matter */
    public boolean front(int page) {
        return page < bodyPage;
    }

    /**
     * @param page the 0-based page
     * @return the number the page is counted as in its own sequence: 1 for the title page and for the body's first page
     */
    public int number(int page) {
        return front(page) ? page + 1 : page - bodyPage + 1;
    }

    /**
     * @param page the 0-based page
     * @return the folio the page is counted as: lowercase roman in the front matter, the title page included, arabic in the body
     */
    public String counted(int page) {
        return front(page) ? roman(number(page)) : Integer.toString(number(page));
    }

    /**
     * @param page the 0-based page
     * @return the folio printed on the page; empty for the title page, which shows none
     */
    public String label(int page) {
        return titlePage && page == 0 ? "" : counted(page);
    }

    /**
     * The page a folio names, as a reader types it: an arabic number is a page of the body, a
     * roman numeral in either case a page of the front matter.
     *
     * @param folio the folio as typed
     * @param pages the page count
     * @return the 0-based page, or empty when the text is no folio of these pages
     */
    public OptionalInt pageOf(String folio, int pages) {
        String text = folio == null ? "" : folio.strip().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return OptionalInt.empty();
        }
        int page;
        if (text.chars().allMatch(c -> c >= '0' && c <= '9')) {
            if (text.length() > 9) {
                return OptionalInt.empty();
            }
            page = bodyPage + Integer.parseInt(text) - 1;
            if (page < bodyPage) {
                return OptionalInt.empty();
            }
        } else {
            int number = arabic(text);
            page = number - 1;
            if (number < 1 || page >= bodyPage) {
                return OptionalInt.empty();
            }
        }
        return page < pages ? OptionalInt.of(page) : OptionalInt.empty();
    }

    private static final int[] VALUES = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
    private static final String[] NUMERALS = {"m", "cm", "d", "cd", "c", "xc", "l", "xl", "x", "ix", "v", "iv", "i"};

    /**
     * A number in lowercase roman numerals, as Typst's {@code "i"} numbering writes it.
     *
     * @param number the number, at least 1
     * @return the numeral
     */
    public static String roman(int number) {
        if (number < 1) {
            throw new IllegalArgumentException("no roman numeral for " + number);
        }
        StringBuilder out = new StringBuilder();
        int rest = number;
        for (int i = 0; i < VALUES.length; i++) {
            while (rest >= VALUES[i]) {
                out.append(NUMERALS[i]);
                rest -= VALUES[i];
            }
        }
        return out.toString();
    }

    /** The number a lowercase roman numeral writes, or 0 when the text is not one as {@link #roman} writes it. */
    private static int arabic(String numeral) {
        int total = 0;
        int at = 0;
        for (int i = 0; i < VALUES.length && at < numeral.length(); i++) {
            while (numeral.startsWith(NUMERALS[i], at)) {
                total += VALUES[i];
                at += NUMERALS[i].length();
                if (total > 3999) {
                    return 0;
                }
            }
        }
        return at == numeral.length() && total > 0 && roman(total).equals(numeral) ? total : 0;
    }
}

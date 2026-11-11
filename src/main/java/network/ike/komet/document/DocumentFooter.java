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

import network.ike.komet.document.print.Folios;

import java.util.ArrayList;
import java.util.List;

/**
 * The line a STAMPed Document card's footer shows for a document: what is in it and what is not
 * yet where it belongs, one line at a card's width. A count of nothing says nothing, and what a
 * person would do next is the toolbar's and the Repository panel's to say; the rest stands in
 * the line's tooltip.
 */
final class DocumentFooter {

    private DocumentFooter() {
    }

    /**
     * The footer's line.
     *
     * @param lines      the lines shown
     * @param blocks     the blocks, or a negative number for a document with no structure
     * @param references the references
     * @param affirmed   the lines affirmed
     * @param included   the included documents shown in place
     * @param edited     the lines edited in Komet and not yet committed
     * @param unresolved what is left of the includes that could not be resolved, empty for none
     * @return the parts that apply, joined
     */
    static String line(int lines, int blocks, int references, int affirmed, int included, long edited, String unresolved) {
        List<String> parts = new ArrayList<>();
        parts.add(count(lines, "line"));
        parts.add(blocks < 0 ? "no structure; re-materialize to add it" : count(blocks, "block"));
        if (references > 0) {
            parts.add(count(references, "reference"));
        }
        if (affirmed > 0) {
            parts.add(affirmed + " affirmed");
        }
        if (included > 0) {
            parts.add(included + " included");
        }
        if (edited > 0) {
            parts.add(edited + " edited, uncommitted");
        }
        if (!unresolved.isEmpty()) {
            parts.add(unresolved);
        }
        return String.join(" \u00b7 ", parts);
    }

    /**
     * What the footer names the page under the eye by in the Page layout
     * ({@code dev-document-front-matter}). For a document with no front matter the footer reads
     * "Page 3 of 12". For a document with front matter it gives the folio as the page prints it,
     * so the screen can be matched to a printed copy, and then the page's place among all the
     * pages: "Page iii, 3 of 196". A title page prints no number, and the footer reads "Title
     * page, 1 of 196".
     *
     * @param folios the folios of the pages
     * @param page   the 0-based page
     * @param pages  the page count
     * @return the footer's text
     */
    static String page(Folios folios, int page, int pages) {
        String place = (page + 1) + " of " + pages;
        if (!folios.frontMatter()) {
            return "Page " + place;
        }
        String folio = folios.label(page);
        return (folio.isEmpty() ? "Title page" : "Page " + folio) + ", " + place;
    }

    /**
     * The pages a reader may ask to go to, as the pages print their numbers: "Page 1 to 12", or
     * for a document with front matter "Page i to xiii, then 1 to 183".
     *
     * @param folios the folios of the pages
     * @param pages  the page count
     * @return the range, for the Go to page dialog
     */
    static String pageRange(Folios folios, int pages) {
        int last = Math.max(0, pages - 1);
        if (!folios.frontMatter() || folios.bodyPage() > last) {
            return "Page " + folios.counted(0) + " to " + folios.counted(last);
        }
        return "Page " + folios.counted(0) + " to " + folios.counted(folios.bodyPage() - 1) + ", then 1 to " + folios.counted(last);
    }

    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }
}

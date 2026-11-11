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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DocumentFooterTest {

    @Test
    void aCountOfNothingSaysNothing() {
        assertEquals("37 lines \u00b7 3 blocks \u00b7 34 edited, uncommitted", DocumentFooter.line(37, 3, 0, 0, 0, 34, ""));
    }

    @Test
    void whatIsThereIsCountedInTheSingularToo() {
        assertEquals("1 line \u00b7 1 block \u00b7 1 reference \u00b7 2 affirmed \u00b7 1 included",
                DocumentFooter.line(1, 1, 1, 2, 1, 0, ""));
    }

    @Test
    void aDocumentWithNoFrontMatterNamesThePageByItsPlace() {
        assertEquals("Page 3 of 12", DocumentFooter.page(Folios.NONE, 2, 12));
        assertEquals("Page 1 to 12", DocumentFooter.pageRange(Folios.NONE, 12));
        assertEquals("Page 1 to 1", DocumentFooter.pageRange(Folios.NONE, 1));
    }

    @Test
    void aDocumentWithFrontMatterNamesThePageByItsPrintedFolioAndThenItsPlace() {
        Folios book = new Folios(13, true);                       // the regulatory book: a title page, twelve pages of lists, then the body
        assertEquals("Page iii, 3 of 196", DocumentFooter.page(book, 2, 196));
        assertEquals("Page xiii, 13 of 196", DocumentFooter.page(book, 12, 196));
        assertEquals("Page 1, 14 of 196", DocumentFooter.page(book, 13, 196), "the body's first page");
        assertEquals("Page 183, 196 of 196", DocumentFooter.page(book, 195, 196));
        assertEquals("Title page, 1 of 196", DocumentFooter.page(book, 0, 196), "the title page prints no number");
        assertEquals("Page i, 1 of 9", DocumentFooter.page(new Folios(2, false), 0, 9), "a list on the first page prints its number");
        assertEquals("Page i to xiii, then 1 to 183", DocumentFooter.pageRange(book, 196));
        assertEquals("Page i to ii", DocumentFooter.pageRange(new Folios(2, false), 2), "pages laid out so far that are all front matter");
    }

    @Test
    void aDocumentWithNoStructureAndALostIncludeSaySo() {
        assertEquals("4 lines \u00b7 no structure; re-materialize to add it \u00b7 1 include left as lines: a.adoc",
                DocumentFooter.line(4, -1, 0, 0, 0, 0, "1 include left as lines: a.adoc"));
    }
}

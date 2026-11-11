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

import network.ike.komet.document.print.DocumentModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where an image block's file is looked for, and the size a picture gives itself. */
class DocumentPicturesTest {

    private static final String SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 760 430.5\" width=\"760\" height=\"430.5\"><rect width=\"760\" height=\"430.5\"/></svg>";

    @Test
    void aTargetIsLookedForFromTheBookThenFromTheTopicAndTheDirectoriesAboveIt(@TempDir Path tree) throws IOException {
        // the regulatory book's layout: the book, its topics in topics/rda, their pictures in topics/images/rda
        Path chart = Files.createDirectories(tree.resolve("regulatory/topics/images/rda")).resolve("tornado.svg");
        Files.writeString(chart, SVG);
        Files.createDirectories(tree.resolve("regulatory/topics/rda"));
        List<Path> roots = List.of(tree);
        String book = "regulatory/book.adoc";
        String topic = "regulatory/topics/rda/tornado-diagram.adoc";

        assertEquals(Optional.of(chart), DocumentPictures.file(roots, book, topic, "./images/rda/tornado.svg"),
                "not beside the book, nor beside the topic: in the directory above the topic's");
        assertEquals(Optional.of(chart), DocumentPictures.file(roots, book, topic, "topics/images/rda/tornado.svg"), "as AsciiDoc looks, from the book's directory");
        assertEquals(Optional.of(chart), DocumentPictures.file(roots, book, topic, "../images/rda/tornado.svg"), "from the topic's own directory");
        assertEquals(Optional.empty(), DocumentPictures.file(roots, book, topic, "images/rda/missing.svg"));
        assertEquals(Optional.empty(), DocumentPictures.file(roots, book, topic, "https://example.org/tornado.svg"), "an address is no file here");
        assertEquals(Optional.empty(), DocumentPictures.file(roots, book, topic, "../../../../etc/hosts"), "nothing outside the tree");
        assertEquals(Optional.empty(), DocumentPictures.file(List.of(), book, topic, "./images/rda/tornado.svg"), "no tree, no file");

        Path second = Files.createDirectories(tree.resolveSibling(tree.getFileName() + "-library"));
        try {
            assertEquals(Optional.of(chart), DocumentPictures.file(List.of(second, tree), book, topic, "./images/rda/tornado.svg"), "the first tree that holds it");
        } finally {
            Files.deleteIfExists(second);
        }
    }

    @Test
    void aPictureIsSetAtItsOwnSizeAPixelThreeQuartersOfAPoint(@TempDir Path tree) throws IOException {
        Path chart = tree.resolve("chart.svg");
        Files.writeString(chart, SVG);
        DocumentModel.Picture picture = DocumentPictures.picture(chart).orElseThrow();
        assertEquals(chart, picture.file());
        assertEquals(570, picture.width(), 0.001);
        assertEquals(322.875, picture.height(), 0.001);

        assertArrayEquals(new double[] {300, 150}, DocumentPictures.svgSize("<svg viewBox='0 0 400 200'>").orElseThrow(), 0.001, "with no size of its own, its view box");
        assertArrayEquals(new double[] {144, 72}, DocumentPictures.svgSize("<svg width=\"2in\" height=\"72pt\">").orElseThrow(), 0.001);
        assertArrayEquals(new double[] {75, 150}, DocumentPictures.svgSize("<?xml version=\"1.0\"?>\n<svg\n  width=\"100%\" height=\"100%\" viewBox=\"0 0 100 200\">").orElseThrow(), 0.001,
                "a size given as a share says nothing: the view box does");
        assertTrue(DocumentPictures.svgSize("<svg>").isEmpty());

        // the width an image line asks for: its second place, or width=, in pixels or as a share of the measure
        DocumentModel.Picture asked = DocumentPictures.sized(picture, "\"Two bars, sorted, with a line at 308 dollars\", 680", 468);
        assertEquals(510, asked.width(), 0.001);
        assertEquals(322.875 * 510 / 570, asked.height(), 0.001, "it keeps its shape");
        assertEquals(234, DocumentPictures.sized(picture, "Chart, width=50%", 468).width(), 0.001);
        assertEquals(300, DocumentPictures.sized(picture, "width=400", 468).width(), 0.001);
        assertEquals(picture, DocumentPictures.sized(picture, "\"A chart, of 2 bars\"", 468), "a line that asks for none leaves it its own size");
        assertEquals(picture, DocumentPictures.sized(picture, "", 468));
        assertTrue(DocumentPictures.picture(tree.resolve("notes.txt")).isEmpty(), "not a picture");
        assertTrue(DocumentPictures.picture(tree.resolve("missing.svg")).isEmpty());
    }
}

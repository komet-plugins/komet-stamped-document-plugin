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

import network.ike.komet.document.surface.print.settings.PrintSettings;
import org.junit.jupiter.api.Test;
import network.ike.komet.document.TextFigure;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The writer's output compiles under the installed typst — every construct in one document —
 * and a broken source reports typst's own words. Skipped where no typst is installed.
 */
class TypstCompilerTest {

    private static final String EVERYTHING = """
            :topic-id: t-everything
            = Everything At Once

            A paragraph with *stars*, _underscores_, #hashes, [brackets] and a -- dash.

            == Lists

            * one
            * two
            ** nested

            . first
            . second

            Term:: Meaning.

            == Blocks

            NOTE: A note.

            [WARNING]
            ====
            A warning with a body.
            ====

            .Code
            [source,java]
            ----
            int x = 1; // `tick`
            ----

            .Elements
            [cols="2,3", options="header"]
            |===
            | Element | Topic

            | Gender
            | Female

            | Age
            | 58
            |===

            .An example
            ====
            Example text.
            ====

            image::images/missing.svg[A figure]
            """;

    @Test
    void aDrawnBlockCompiles(@TempDir Path folder) throws IOException {
        assumeTrue(TypstCompiler.locate().isPresent(), "typst is not installed");
        DocumentModel model = new DocumentModel("Drawn", List.of(new DocumentModel.Paragraph("Above it."),
                new DocumentModel.Drawn(TextFigure.of("complex-concept", Optional.of("A \"quoted\" name"), List.of("A sentence."),
                        List.of(List.of("given", "value"), List.of("a concept", "present")))),
                new DocumentModel.Paragraph("Below it.")));
        TypstPdf.Printed printed = TypstPdf.print(model, PrintSettings.DEFAULT, folder, "drawn");
        assertTrue(printed.ok(), printed.report());
        assertTrue(printed.result().output().isBlank(), "typst warned: " + printed.result().output());
    }

    @Test
    void aHeaderRowsNoteIsGivenOnceHoweverManyPagesTheTableRunsTo(@TempDir Path folder) throws IOException {
        assumeTrue(TypstCompiler.locate().isPresent(), "typst is not installed");
        StringBuilder text = new StringBuilder("""
                = A Long Table

                Before the table.footnote:[The first note.]

                .Values
                [cols="1,1", options="header"]
                |===
                | Quantity | Valuefootnote:[In dollars.]
                """);
        for (int row = 1; row <= 120; row++) {
            text.append("| Quantity ").append(row).append(" | ").append(row * 100).append('\n');
        }
        text.append("|===\n\nAfter the table.footnote:[The last note.]\n");
        DocumentModel model = StoredDocumentModel.of(StoredFixture.parse(text.toString()));
        TypstPdf.Printed printed = TypstPdf.print(model, PrintSettings.DEFAULT, folder, "head-note");
        assertTrue(printed.ok(), printed.report());
        assertTrue(printed.result().output().isBlank(), "typst warned: " + printed.result().output());
        // typst sets the header again on every page: before the note was given once, each setting gave it again, renumbered
        TypstCompiler.Result counted = TypstCompiler.evaluate(folder, printed.source(),
                "(notes: query(footnote.entry).len(), marks: query(footnote).len(), pages: query(footnote).map(it => it.location().page()).dedup().len())",
                folder.resolve("fonts"));
        assertTrue(counted.ok(), counted.output());
        String answer = counted.output().replaceAll("\\s", "");
        java.util.regex.Matcher pages = java.util.regex.Pattern.compile("\"pages\":(\\d+)").matcher(answer);
        assertTrue(pages.find() && Integer.parseInt(pages.group(1)) >= 3, "the table runs over pages: " + answer);
        assertTrue(answer.contains("\"notes\":3"), "three notes at the feet of the pages, the header's given once: " + answer);
        assertTrue(answer.contains("\"marks\":" + (2 + Integer.parseInt(pages.group(1)))), "and its mark stands in the header on every page: " + answer);
    }

    @Test
    void everyConstructCompilesToAPdf(@TempDir Path folder) throws IOException {
        assumeTrue(TypstCompiler.locate().isPresent(), "typst is not installed");
        DocumentModel model = StoredDocumentModel.of(StoredFixture.parse(EVERYTHING));
        TypstPdf.Printed printed = TypstPdf.print(model, PrintSettings.DEFAULT, folder, "everything");
        assertTrue(printed.ok(), printed.report());
        assertTrue(Files.size(printed.pdf()) > 1000);
        assertEquals(folder.resolve("everything.typ"), printed.source());
        assertTrue(printed.result().output().isBlank(), "typst warned: " + printed.result().output());
        TypstCompiler.Result png = TypstCompiler.renderPage(folder, printed.source(), folder.resolve("page-1.png"), 1);
        assertTrue(png.ok(), png.report());
    }

    @Test
    void aPrintSavesThePdfAloneWhereItWasAsked(@TempDir Path folder) throws IOException {
        assumeTrue(TypstCompiler.locate().isPresent(), "typst is not installed");
        Path target = folder.resolve("chosen name.pdf");
        Files.writeString(target, "an earlier print");
        DocumentModel model = new DocumentModel("Saved", List.of(new DocumentModel.Paragraph("A paragraph.")));
        TypstPdf.Printed printed = TypstPdf.printTo(model, PrintSettings.DEFAULT, "ignored", target);
        assertTrue(printed.ok(), printed.report());
        assertEquals("%PDF", new String(Files.readAllBytes(target), 0, 4, java.nio.charset.StandardCharsets.ISO_8859_1));
        try (var left = Files.list(folder)) {
            assertEquals(List.of(target), left.toList());
        }
    }

    @Test
    void aChosenNameEndsInPdf() {
        assertEquals(Path.of("/a/report.pdf"), TypstPdf.withPdfExtension(Path.of("/a/report")));
        assertEquals(Path.of("/a/report.PDF"), TypstPdf.withPdfExtension(Path.of("/a/report.PDF")));
        assertEquals(Path.of("/a/v1.2.pdf"), TypstPdf.withPdfExtension(Path.of("/a/v1.2")));
    }

    @Test
    void aBrokenSourceReportsTypstsOwnWords(@TempDir Path folder) throws IOException {
        assumeTrue(TypstCompiler.locate().isPresent(), "typst is not installed");
        Path source = folder.resolve("broken.typ");
        Files.writeString(source, "#set page(paper: \"no-such-paper\")\nHello\n");
        TypstCompiler.Result result = TypstCompiler.compile(folder, source, folder.resolve("broken.pdf"));
        assertFalse(result.ok());
        assertTrue(result.report().startsWith("typst compile failed: "), result.report());
        assertTrue(result.output().contains("error"), result.output());
    }
}

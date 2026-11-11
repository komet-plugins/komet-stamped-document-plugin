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
package network.ike.komet.document.surface.print.settings;

import network.ike.komet.document.surface.print.settings.TitleSpacing.Measure;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The white space of a title page: a theme's, and what a document's header asks for in its place. */
class TitleSpacingTest {

    @Test
    void aLengthIsPointsUnlessItSaysOtherwise() {
        assertEquals(Optional.of(Measure.points(12)), Measure.of("12"), "a bare number counts points, as Asciidoctor PDF's theme has it");
        assertEquals(Optional.of(Measure.ems(2.5)), Measure.of(" 2.5em "));
        assertEquals(Optional.of(Measure.points(72)), Measure.of("1in"));
        assertEquals(Optional.of(Measure.points(24)), Measure.of("2pc"));
        assertEquals(72, Measure.of("25.4mm").orElseThrow().value(), 1e-9);
        assertEquals(72, Measure.of("2.54 cm").orElseThrow().value(), 1e-9);
        assertEquals(Optional.of(Measure.points(9)), Measure.of("12px"));
        assertTrue(Measure.of("wide").isEmpty());
        assertTrue(Measure.of("").isEmpty());
        assertTrue(Measure.of("-3").isEmpty(), "less than nothing is no space");
        assertEquals(30, Measure.ems(2.5).in(12), 1e-9, "an em is the body's size");
        assertEquals(18, Measure.points(18).in(12), 1e-9);
        assertEquals("2em", Measure.ems(2).typst());
        assertEquals("0.5em", Measure.ems(0.5).typst());
        assertEquals("36pt", Measure.points(36).typst());
    }

    @Test
    void aDocumentAsksOnlyForWhatItNamesAndTheThemeGivesTheRest() {
        Map<String, String> header = Map.of(TitleSpacing.AUTHORS, "3em", TitleSpacing.ABSTRACT, "not a length");
        TitleSpacing asked = TitleSpacing.asked(name -> Optional.ofNullable(header.get(name)));
        assertEquals(new TitleSpacing(Measure.ems(3), null, null), asked, "what is no length is not asked for");
        assertEquals(new TitleSpacing(Measure.ems(3), Measure.ems(0.5), Measure.ems(3)), asked.over(TitleSpacing.STANDARD));
        assertSame(TitleSpacing.NONE, TitleSpacing.asked(name -> Optional.empty()));
        assertEquals(TitleSpacing.STANDARD, TitleSpacing.NONE.over(TitleSpacing.STANDARD));
        assertEquals(TitleSpacing.STANDARD, DocumentTheme.MANUSCRIPT.titleSpacing(), "every theme has the standard until one differs");
        assertEquals("title-page-authors-margin-top", TitleSpacing.AUTHORS, "Asciidoctor PDF's theme key, written flat");
        assertEquals("title-page-revision-margin-top", TitleSpacing.REVISION);
    }
}

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

import javafx.scene.paint.Color;
import jfx.incubator.scene.control.richtext.TextPos;
import jfx.incubator.scene.control.richtext.model.RichParagraph;
import jfx.incubator.scene.control.richtext.model.StyleAttributeMap;
import network.ike.komet.document.GitDocumentMaterializer.StoredLine;
import network.ike.komet.document.LineRunModel.LineRun;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The segment model: one run per source line, each carrying its line, found again from any position. */
class LineRunModelTest {

    private static final StyleAttributeMap PROSE = StyleAttributeMap.builder().setFontSize(14).build();

    private static StoredLine line(long nid, String text) {
        return new StoredLine(nid, text, null, null, List.of());
    }

    /** A paragraph of three lines joined by spaces, then a one-line paragraph. */
    private static LineRunModel.Spec spec() {
        return new LineRunModel.Spec(List.of(
                new LineRunModel.Paragraph(List.of(
                        new LineRunModel.Line(line(1, "One claim."), "One claim."),
                        new LineRunModel.Line(line(2, "A second,"), "A second,"),
                        new LineRunModel.Line(line(3, "and a third."), "and a third.")), " ", PROSE),
                new LineRunModel.Paragraph(List.of(new LineRunModel.Line(line(4, "Alone."), "Alone.")), "", PROSE)));
    }

    @Test
    void eachLineIsOneSegmentCarryingItsLine() {
        LineRunModel model = spec().build(LineRunModel.Decoration.NONE);

        assertEquals(2, model.size());
        assertEquals("One claim. A second, and a third.", model.getPlainText(0));
        assertEquals("Alone.", model.getPlainText(1));
        RichParagraph first = model.getParagraph(0);
        assertEquals(5, first.getSegmentCount(), "three runs and the two separators between them");
        assertEquals(2L, first.getSegment(2).getStyleAttributeMap(null).get(LineRunModel.LINE).nid(), "the middle run carries line 2");
        assertFalse(first.getSegment(1).getStyleAttributeMap(null).contains(LineRunModel.LINE), "a separator carries no line");
        assertEquals(List.of(1L, 2L, 3L, 4L), model.runs().stream().map(LineRun::nid).toList());
        assertEquals(new LineRun(0, 11, 20, line(2, "A second,")), model.runs().get(1));
    }

    @Test
    void anyPositionResolvesToItsRun() {
        LineRunModel model = spec().build(LineRunModel.Decoration.NONE);

        assertEquals(1L, model.runAt(TextPos.ofLeading(0, 0)).orElseThrow().nid(), "the first character");
        assertEquals(1L, model.runAt(TextPos.ofLeading(0, 9)).orElseThrow().nid(), "the last character of the first run");
        assertEquals(1L, model.runAt(TextPos.ofLeading(0, 10)).orElseThrow().nid(), "the separator belongs to the run before it");
        assertEquals(2L, model.runAt(TextPos.ofLeading(0, 11)).orElseThrow().nid(), "the second run starts after the separator");
        assertEquals(3L, model.runAt(TextPos.ofLeading(0, 33)).orElseThrow().nid(), "the end of the paragraph is its last run");
        assertEquals(3L, model.runAt(TextPos.ofLeading(0, 99)).orElseThrow().nid(), "past the end still resolves to the last run");
        assertEquals(4L, model.runAt(TextPos.ofLeading(1, 3)).orElseThrow().nid());
        assertTrue(model.runAt(TextPos.ofLeading(7, 0)).isEmpty(), "no such paragraph");
        assertEquals(2L, model.getStyleAttributeMap(null, TextPos.ofLeading(0, 15), false).get(LineRunModel.LINE).nid(),
                "the area's style lookup answers with the run too");
    }

    @Test
    void aSpanSnapsToWholeRunsEitherWayRound() {
        LineRunModel model = spec().build(LineRunModel.Decoration.NONE);

        List<LineRun> forward = model.runsBetween(TextPos.ofLeading(0, 4), TextPos.ofLeading(0, 25));
        assertEquals(List.of(1L, 2L, 3L), forward.stream().map(LineRun::nid).toList(), "mid-run to mid-run takes all three");
        List<LineRun> backward = model.runsBetween(TextPos.ofLeading(0, 25), TextPos.ofLeading(0, 15));
        assertEquals(List.of(2L, 3L), backward.stream().map(LineRun::nid).toList(), "reversed ends, document order");
        List<LineRun> across = model.runsBetween(TextPos.ofLeading(1, 2), TextPos.ofLeading(0, 30));
        assertEquals(List.of(3L, 4L), across.stream().map(LineRun::nid).toList(), "a span across paragraphs");
        assertEquals(TextPos.ofLeading(0, 11), forward.get(1).startPos());
        assertEquals(TextPos.ofLeading(0, 20), forward.get(1).endPos());
    }

    @Test
    void anEmptyParagraphStillHasARunToSelect() {
        LineRunModel model = new LineRunModel.Spec(List.of(
                new LineRunModel.Paragraph(List.of(new LineRunModel.Line(line(9, ""), "")), "", PROSE))).build(LineRunModel.Decoration.NONE);

        assertEquals(" ", model.getPlainText(0), "a blank line draws as one space so it can be hovered and selected");
        assertEquals(9L, model.runAt(TextPos.ZERO).orElseThrow().nid());
    }

    @Test
    void theDecorationIsBuiltIntoTheModelNotTheSpec() {
        LineRunModel.Spec spec = spec();
        LineRunModel plain = spec.build(LineRunModel.Decoration.NONE);
        LineRunModel decorated = spec.build(new LineRunModel.Decoration() {
            @Override
            public Color tint(StoredLine line) {
                return line.nid() == 2 ? Color.LIGHTBLUE : null;
            }

            @Override
            public boolean underline(StoredLine line) {
                return line.nid() == 3;
            }
        });

        assertSame(spec, decorated.spec(), "the same spec rebuilds with another layer");
        assertEquals(plain.runs(), decorated.runs(), "decoration changes nothing about where the runs are");
        assertFalse(plain.getParagraph(0).getSegment(4).getStyleAttributeMap(null).isUnderline());
        assertTrue(decorated.getParagraph(0).getSegment(4).getStyleAttributeMap(null).isUnderline(), "the review-queue underline is on the run");
        assertTrue(decorated.runsIntroducedBy(5).isEmpty(), "lines without a STAMP are introduced by no commit");
    }

    @Test
    void aSliceKeepsEachSourceLinesShareOfThePageLine() {
        // The paragraph's text is "One claim. A second, and a third." — a page line from inside the first line to inside the third.
        LineRunModel.Spec slice = spec().slice(0, 4, 26, false, false);

        assertEquals(1, slice.paragraphs().size());
        List<LineRunModel.Line> lines = slice.paragraphs().getFirst().lines();
        assertEquals(3, lines.size(), "the line touches all three source lines");
        assertEquals("claim.", lines.get(0).text());
        assertEquals(1, lines.get(0).line().nid());
        assertEquals("A second,", lines.get(1).text());
        assertEquals("and a", lines.get(2).text());
        assertEquals(3, lines.get(2).line().nid());
        LineRunModel model = slice.build(LineRunModel.Decoration.NONE);
        assertEquals("claim. A second, and a", model.getPlainText(0));
        assertEquals(3, model.runs().size());
        assertEquals("One claim. A second, and a third.", spec().text(spec().paragraphs().getFirst()));
        assertEquals("One claim. A second, and a third.\nAlone.", spec().plainText());
    }

    @Test
    void aLinkIntoTheDocumentIsDrawnInItsColourAndFoundUnderThePointer() {
        // as a line is read: a citation that found its entry is a link to the entry's block
        String first = "The value is that of link:#b77[[HHS 2026, Standard";
        String second = "Values]] and of https://example.org[another source].";
        LineRunModel.Spec read = new LineRunModel.Spec(List.of(new LineRunModel.Paragraph(List.of(
                new LineRunModel.Line(line(1, first), first), new LineRunModel.Line(line(2, second), second)), " ", PROSE)))
                .inlined(new network.ike.komet.document.print.InlineText.Numbering()).spec();
        LineRunModel model = read.build(LineRunModel.Decoration.NONE);
        String text = model.getPlainText(0);
        assertEquals("The value is that of [HHS 2026, Standard Values] and of another source.", text);

        int at = text.indexOf("[HHS");
        assertEquals("b77", model.internalLinkAt(TextPos.ofLeading(0, at)).orElseThrow(), "the block the link points at");
        assertEquals("b77", model.internalLinkAt(TextPos.ofLeading(0, text.indexOf("Values]"))).orElseThrow(), "on the source line it runs on to as well");
        assertTrue(model.internalLinkAt(TextPos.ofLeading(0, 4)).isEmpty(), "plain words are no link");
        assertTrue(model.internalLinkAt(TextPos.ofLeading(0, text.indexOf("another"))).isEmpty(), "a link out of the document is not followed here");
        assertTrue(model.internalLinkAt(TextPos.ofLeading(3, 0)).isEmpty());

        RichParagraph paragraph = model.getParagraph(0);
        Color link = null;
        Color plain = null;
        for (int k = 0; k < paragraph.getSegmentCount(); k++) {
            String piece = paragraph.getSegment(k).getText();
            Color colour = paragraph.getSegment(k).getStyleAttributeMap(null).getTextColor();
            if (piece.contains("HHS") || piece.contains("Values]")) {
                link = colour;
                assertEquals(LineRunModel.LINK, colour, piece);
            } else if (piece.contains("another")) {
                plain = colour;
            }
        }
        assertEquals(LineRunModel.LINK, link);
        assertEquals(null, plain, "only a link into the document takes the colour");

        // a page line cut from the paragraph keeps the link
        LineRunModel line = read.slice(0, at + 5, text.length(), false, false).build(LineRunModel.Decoration.NONE);
        assertEquals("b77", line.internalLinkAt(TextPos.ofLeading(0, 0)).orElseThrow());
    }

    @Test
    void aHyphenatedSliceDrawsTheHyphenOnItsLastRunAndAJustifiedOneFills() {
        StyleAttributeMap justified = StyleAttributeMap.builder().setFontSize(12).setTextAlignment(javafx.scene.text.TextAlignment.JUSTIFY).build();
        LineRunModel.Spec spec = new LineRunModel.Spec(List.of(new LineRunModel.Paragraph(List.of(
                new LineRunModel.Line(line(1, "see internationalization"), "see internationalization"),
                new LineRunModel.Line(line(2, "now"), "now")), " ", justified)));

        LineRunModel.Spec head = spec.slice(0, 0, 11, true, true);
        LineRunModel.Spec tail = spec.slice(0, 11, 28, false, false);

        assertEquals("see interna-", head.paragraphs().getFirst().lines().getFirst().text());
        assertEquals(1, head.paragraphs().getFirst().lines().getFirst().line().nid());
        assertTrue(Boolean.TRUE.equals(head.paragraphs().getFirst().style().get(LineRunModel.JUSTIFY_FILL)), "a line that is not the paragraph's last fills");
        LineRunModel headModel = head.build(LineRunModel.Decoration.NONE);
        assertEquals("see interna- ", headModel.getPlainText(0), "the filler's space follows the text");
        assertTrue(headModel.getParagraph(0).getSegmentCount() > 2, "the filler node is a segment of its own");
        assertEquals(javafx.scene.text.TextAlignment.LEFT, tail.paragraphs().getFirst().style().getTextAlignment(), "the paragraph's last line is ragged");
        assertEquals("tionalization now", LineRunModel.Spec.text(tail.paragraphs().getFirst()));
        assertEquals(2, tail.paragraphs().getFirst().lines().size());
        assertTrue(spec.slice(0, 100, 120, false, false).paragraphs().isEmpty(), "a range past the text yields nothing");
    }

    /** Reads {@code k:uuid=<word>[label]} as a token, as a store that holds every such concept would. */
    private static List<LineRunModel.Token> tokens(String text) {
        List<LineRunModel.Token> found = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("k:uuid=\\w+\\[[^\\]]*]").matcher(text);
        while (m.find()) {
            found.add(new LineRunModel.Token(m.start(), m.end(), () -> null));
        }
        return found;
    }

    @Test
    void aTokenGivesWayToOneCharacterWhereItsNodeStands() {
        // as inlined prose is: the lines are shares of one text, and the first token's label runs over the line's end
        String first = "The module is k:uuid=aa[Glo";
        String second = "bal Module], whose parent is k:uuid=bb[Module].";
        int parent = (first + second).indexOf("parent");
        LineRunModel.Spec spec = new LineRunModel.Spec(List.of(new LineRunModel.Paragraph(List.of(
                new LineRunModel.Line(line(1, first), first), new LineRunModel.Line(line(2, second), second)), "", PROSE,
                List.of(new network.ike.komet.document.print.InlineText.Run(parent, parent + 6, false, true, false, null)))));

        LineRunModel.Spec read = spec.withInlines(LineRunModelTest::tokens);
        LineRunModel.Paragraph p = read.paragraphs().getFirst();

        assertEquals("The module is  , whose parent is  .", LineRunModel.Spec.text(p), "each token is one character, its node's place");
        assertEquals(List.of("The module is  ", ", whose parent is  ."), p.lines().stream().map(LineRunModel.Line::text).toList(),
                "the line a token starts on keeps its place; the rest of the token is no one's");
        assertEquals(List.of(14, 33), p.inlines().stream().map(LineRunModel.Inline::at).toList());
        assertEquals("k:uuid=aa[Global Module]", p.inlines().getFirst().source(), "a node knows the text it stands for");
        assertEquals(1, p.runs().size());
        assertEquals("parent", LineRunModel.Spec.text(p).substring(p.runs().getFirst().from(), p.runs().getFirst().to()), "a span moves with its words");

        LineRunModel model = read.build(LineRunModel.Decoration.NONE);
        assertEquals("The module is  , whose parent is  .", model.getPlainText(0));
        RichParagraph drawn = model.getParagraph(0);
        List<jfx.incubator.scene.control.richtext.model.StyledSegment.Type> types = new java.util.ArrayList<>();
        int length = 0;
        for (int k = 0; k < drawn.getSegmentCount(); k++) {
            types.add(drawn.getSegment(k).getType());
            length += drawn.getSegment(k).getTextLength();
        }
        assertEquals(2, types.stream().filter(t -> t == jfx.incubator.scene.control.richtext.model.StyledSegment.Type.INLINE_NODE).count());
        assertEquals(model.getPlainText(0).length(), length, "the segments and the plain text count the same characters");
        assertEquals(1L, model.runAt(TextPos.ofLeading(0, 14)).orElseThrow().nid(), "a node belongs to the line its token starts on");
        assertEquals(2L, model.runAt(TextPos.ofLeading(0, 33)).orElseThrow().nid());
        assertSame(spec, spec.withInlines(text -> List.of()), "a spec with no tokens is itself");
    }

    @Test
    void aParagraphJoinedBySeparatorsIsReadALineAtATime() {
        LineRunModel.Spec spec = new LineRunModel.Spec(List.of(new LineRunModel.Paragraph(List.of(
                new LineRunModel.Line(line(1, "See k:uuid=aa[One]"), "See k:uuid=aa[One]"),
                new LineRunModel.Line(line(2, "and k:uuid=bb[Two]."), "and k:uuid=bb[Two].")), " ", PROSE)));

        LineRunModel.Paragraph p = spec.withInlines(LineRunModelTest::tokens).paragraphs().getFirst();

        assertEquals("See   and  .", LineRunModel.Spec.text(p));
        assertEquals(List.of(4, 10), p.inlines().stream().map(LineRunModel.Inline::at).toList(), "offsets are in the joined text, separators counted");
    }
}

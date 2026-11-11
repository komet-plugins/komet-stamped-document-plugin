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

import network.ike.komet.document.Captions;
import network.ike.komet.document.DocumentAssembly;
import network.ike.komet.document.DocumentTargets;
import network.ike.komet.document.GitDocumentMaterializer.StoredDocument;
import network.ike.komet.document.RegionFixture;
import network.ike.komet.document.print.DocumentModel.Heading;
import network.ike.komet.document.print.DocumentModel.ListBlock;
import network.ike.komet.document.print.DocumentModel.Paragraph;
import network.ike.komet.document.print.LayoutOracle.Kind;
import network.ike.komet.document.print.LayoutOracle.Unit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A book's cross references and citations, through the assembly's targets, the print model,
 * the Typst source and the model's units (IKE-Network/ike-issues#1351), written as the
 * regulatory book writes them: a topic names another by the anchor over its title, and cites a
 * source by the id of its entry in the references, which carries the label a citation prints.
 * The documents are built the way the materializer stores them, with no store.
 */
class CrossReferencesTest {

    private static final String BOOK = """
            = Regulatory Decisions
            :toc:
            :sectnums:

            include::topics/tests.adoc[leveloffset=+1]

            include::topics/tree.adoc[leveloffset=+1]

            include::topics/references.adoc[leveloffset=+1]
            """;

    private static final String TESTS = """
            [[rda-tests]]
            = Which Tests to Require

            The tree in xref:rda-tree[The Test Decision as a Tree] sets the options side by side,
            and <<rda-tree>> prices them. The values are those of <<hhsvalues2026>> and
            <<howard1966>>. The compendium's xref:dex-record[Device
            Record] is not in this book, nor is <<other-topic>>.
            """;

    private static final String TREE = """
            [[rda-tree]]
            = The Test Decision as a Tree

            [[tab-quantities]]
            .The quantities behind the tree
            |===
            | Quantity | Value
            |===

            See <<tab-quantities>>, and xref:rda-tests[] for the instruments.
            """;

    private static final String REFERENCES = """
            [[rda-references]]
            [bibliography]
            = References

            - [[[howard1966]]] Howard, R. A. (1966). Information value theory.
            - [[[hhsvalues2026,HHS 2026, Standard Values]]] Kearsley, A. (2026). _HHS Standard Values_.
            """;

    private static DocumentAssembly assembly() {
        StoredDocument host = RegionFixture.parse(BOOK, 0);
        StoredDocument tests = RegionFixture.parse(TESTS, 10_000);
        StoredDocument tree = RegionFixture.parse(TREE, 20_000);
        StoredDocument references = RegionFixture.parse(REFERENCES, 30_000);
        DocumentAssembly assembly = DocumentAssembly.of(host, "docs/book.adoc", path -> switch (path) {
            case "docs/topics/tests.adoc" -> Optional.of(tests);
            case "docs/topics/tree.adoc" -> Optional.of(tree);
            case "docs/topics/references.adoc" -> Optional.of(references);
            default -> Optional.empty();
        });
        assertTrue(assembly.unresolved().isEmpty(), "every include is followed");
        return assembly;
    }

    private static DocumentModel model() {
        DocumentAssembly assembly = assembly();
        return StoredDocumentModel.of(assembly, StoredDocumentModel.Figures.captioned(Captions.of(assembly, b -> false, 3)));
    }

    @Test
    void anAnchorNamesItsBlockAndTheWordsABareReferenceReadsAs() {
        DocumentTargets targets = assembly().targets();
        assertEquals("The Test Decision as a Tree", targets.of("rda-tree").orElseThrow().label(), "a title's anchor: the title");
        assertEquals("The quantities behind the tree", targets.of("tab-quantities").orElseThrow().label(), "a table's anchor: its title");
        assertEquals("[HHS 2026, Standard Values]", targets.of("hhsvalues2026").orElseThrow().label(), "an entry's label, in brackets, commas and all");
        assertEquals("[howard1966]", targets.of("howard1966").orElseThrow().label(), "an entry with no label is known by its id");
        assertTrue(targets.of("other-topic").isEmpty());
        assertEquals(targets.of("rda-tree"), targets.of("tree.adoc#rda-tree"), "a reference may name the file too");
        assertEquals(targets.of("hhsvalues2026").orElseThrow().block(), targets.block(targets.of("hhsvalues2026").orElseThrow().key()).orElseThrow());
        assertFalse(targets.of("hhsvalues2026").orElseThrow().block() == targets.of("howard1966").orElseThrow().block(), "each entry is its own block");
    }

    @Test
    void aReferenceThatFindsItsTargetIsReadAsALinkAndOneThatDoesNotStaysAsWritten() {
        DocumentTargets targets = assembly().targets();
        String tree = targets.of("rda-tree").orElseThrow().key();
        String values = targets.of("hhsvalues2026").orElseThrow().key();
        assertEquals("in link:#" + tree + "[The Test Decision as a Tree] and link:#" + tree + "[The Test Decision as a Tree].",
                targets.read("in xref:rda-tree[The Test Decision as a Tree] and <<rda-tree>>."));
        assertEquals("of link:#" + values + "[[HHS 2026, Standard Values]] and link:#" + values + "[the standard values]",
                targets.read("of <<hhsvalues2026>> and <<hhsvalues2026,the standard values>>"));
        assertEquals("see link:#" + tree + "[The Test Decision as a Tree]", targets.read("see xref:rda-tree[]"), "a macro with no text takes the target's words");
        assertEquals("The compendium's xref:dex-record[Device", targets.read("The compendium's xref:dex-record[Device"));
        assertEquals("nor is <<other-topic>>.", targets.read("nor is <<other-topic>>."));
        assertEquals("- [[" + values + "]][HHS 2026, Standard Values] Kearsley",
                targets.read("- [[[hhsvalues2026,HHS 2026, Standard Values]]] Kearsley"), "an entry begins with its label, and an anchor of its block");
        assertEquals("word<<rda-tree>>", targets.read("word<<rda-tree>>"), "a link is not read straight after a letter");
        assertEquals("\\<<rda-tree>>", targets.read("\\<<rda-tree>>"), "an escaped reference is its own words");
    }

    @Test
    void thePrintModelReadsACitationAsItsLabelAndAnEntryBeginsWithIt() {
        DocumentAssembly assembly = assembly();
        DocumentTargets targets = assembly.targets();
        DocumentModel model = model();
        String prose = model.blocks().stream().filter(Paragraph.class::isInstance).map(b -> ((Paragraph) b).text()).findFirst().orElseThrow();
        InlineText read = InlineText.of(prose);
        assertEquals("The tree in The Test Decision as a Tree sets the options side by side, and The Test Decision as a Tree prices them. "
                + "The values are those of [HHS 2026, Standard Values] and [howard1966]. The compendium’s Device Record is not in this book, "
                + "nor is other topic.", read.text().replace('\'', '’'));
        List<String> links = read.runs().stream().filter(r -> r.href() != null).map(r -> r.href() + " " + read.text().substring(r.from(), r.to())).toList();
        assertEquals(List.of("#" + targets.of("rda-tree").orElseThrow().key() + " The Test Decision as a Tree",
                "#" + targets.of("rda-tree").orElseThrow().key() + " The Test Decision as a Tree",
                "#" + targets.of("hhsvalues2026").orElseThrow().key() + " [HHS 2026, Standard Values]",
                "#" + targets.of("howard1966").orElseThrow().key() + " [howard1966]"), links,
                "what finds its target is a link to the target's block; what finds none is words alone");

        ListBlock entries = model.blocks().stream().filter(ListBlock.class::isInstance).map(ListBlock.class::cast).findFirst().orElseThrow();
        InlineText entry = InlineText.of(entries.items().get(1).text());
        assertEquals("[HHS 2026, Standard Values] Kearsley, A. (2026). HHS Standard Values.", entry.text());
        assertEquals(List.of(new InlineText.Anchor(targets.of("hhsvalues2026").orElseThrow().key(), 0)), entry.anchors(), "the entry is a place its citations point at");
        assertEquals("[howard1966] Howard, R. A. (1966). Information value theory.", InlineText.of(entries.items().get(0).text()).text());
    }

    @Test
    void theTypstSourceLabelsWhatIsPointedAtAndLinksToIt() {
        DocumentAssembly assembly = assembly();
        DocumentTargets targets = assembly.targets();
        String typst = TypstWriter.write(StoredDocumentModel.of(assembly, StoredDocumentModel.Figures.captioned(Captions.of(assembly, b -> false, 3))),
                TypstWriter.PageSetup.DEFAULT);
        assertEquals(1, typst.split(Pattern.quote(TypstWriter.REF_HELPER), -1).length - 1, "the helper that links where the target is");
        String tree = TypstWriter.TARGET_LABEL + targets.of("rda-tree").orElseThrow().key();
        String values = TypstWriter.TARGET_LABEL + targets.of("hhsvalues2026").orElseThrow().key();
        String table = TypstWriter.TARGET_LABEL + targets.of("tab-quantities").orElseThrow().key();
        assertTrue(typst.contains("#ike-ref(<" + tree + ">)[The Test Decision as a Tree];"), "a cross reference");
        assertTrue(typst.contains("#ike-ref(<" + values + ">)[\\[HHS 2026, Standard Values\\]];"), "a citation, in brackets");
        assertTrue(typst.contains("#ike-ref(<" + table + ">)[The quantities behind the tree];"), "a reference to a table");
        assertTrue(Pattern.compile("(?m)^= 2 The Test Decision as a Tree <" + Pattern.quote(tree) + ">$").matcher(typst).find(), "the heading pointed at carries its label");
        assertTrue(typst.contains("#metadata(none)<" + values + ">\\[HHS 2026, Standard Values\\] Kearsley"), "an entry carries its own at its start");
        assertTrue(typst.contains(" <" + table + ">\n"), "and so does the table");
        assertFalse(typst.contains("xref:") || typst.contains("<<") || typst.contains("link:#"), "no reference is left as written but the two that find nothing");
        assertTrue(typst.contains("Device Record") && typst.contains("other topic"));
    }

    @Test
    void theUnitsDrawTheSameWords() {
        List<Unit> units = ModelUnits.of(model(), 11);
        assertTrue(units.stream().anyMatch(u -> u.kind() == Kind.PARAGRAPH && u.text().contains("those of [HHS 2026, Standard Values] and [howard1966].")));
        assertTrue(units.stream().anyMatch(u -> u.kind() == Kind.LIST_ITEM && u.text().endsWith("[HHS 2026, Standard Values] Kearsley, A. (2026). HHS Standard Values.")));
        assertTrue(units.stream().anyMatch(u -> u.kind() == Kind.HEADING && u.text().equals("3 References")));
    }

    @Test
    void everyLinkOfTheCompiledDocumentGoesToItsTarget(@TempDir Path folder) throws IOException {
        assumeTrue(TypstCompiler.locate().isPresent(), "typst is not installed");
        TypstPdf.Printed printed = TypstPdf.print(model(), network.ike.komet.document.surface.print.settings.PrintSettings.DEFAULT, folder, "references");
        assertTrue(printed.ok(), printed.report());
        assertTrue(printed.result().output().isBlank(), "typst warned: " + printed.result().output());
        // the links typst made: the contents' three entries, and the six references that find a target
        TypstCompiler.Result counted = TypstCompiler.evaluate(folder, printed.source(), "query(link).len()", folder.resolve("fonts"));
        assertTrue(counted.ok(), counted.output());
        Matcher links = Pattern.compile("\\d+").matcher(counted.output());
        assertTrue(links.find(), counted.output());
        assertEquals(3 + 6, Integer.parseInt(links.group()), "three entries of the contents and six references; the two that find nothing are no links");
    }

    @Test
    void headingsAreNumberedAsBefore() {
        List<String> headings = model().blocks().stream().filter(Heading.class::isInstance).map(h -> ((Heading) h).text()).toList();
        assertEquals(List.of("1 Which Tests to Require", "2 The Test Decision as a Tree", "3 References"), headings);
    }
}

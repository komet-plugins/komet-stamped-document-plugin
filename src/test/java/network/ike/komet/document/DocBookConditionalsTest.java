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

import network.ike.komet.document.DocumentGraph.Kind;
import network.ike.komet.document.DocumentGraph.Node;
import network.ike.komet.document.GitDocumentMaterializer.StoredDocument;
import network.ike.komet.document.GitDocumentMaterializer.StoredLine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Graph view's Elements mode and AsciiDoc's conditional directives: the DocBook projection is
 * the document as it reads, so the lines an {@code ifdef} or {@code ifndef} leaves out, and the
 * directives themselves, are no part of it, as they are no part of the Reading view, the pages
 * or the PDF. The Latest and History modes show the document as the store holds it, every line.
 */
class DocBookConditionalsTest {

    /** A topic that says one thing in the compendium and another outside it; {@code book} is set, {@code compendium} is not. */
    private static final String TOPIC = """
            = A Topic
            :book:

            Before the conditionals.

            ifdef::compendium[]
            Only in the compendium.
            endif::[]

            ifndef::compendium[]
            Only outside the compendium.
            endif::[]

            ifdef::book[In the book, on one line.]

            ifdef::compendium[In the compendium, on one line.]

            After the conditionals.
            """;

    private static DocumentAssembly assembly(String text, Map<String, StoredDocument> store) {
        return DocumentAssembly.of(DocumentGraphTest.stored(text), "docs/book.adoc", path -> Optional.ofNullable(store.get(path)));
    }

    private static List<Node> all(Node node) {
        List<Node> out = new ArrayList<>();
        out.add(node);
        for (Node child : node.children()) {
            out.addAll(all(child));
        }
        return out;
    }

    /** The text runs of a projection, in document order. */
    private static List<String> texts(Node root) {
        return all(root).stream().filter(n -> n.kind() == Kind.TEXT).map(n -> n.markup().text()).toList();
    }

    private static List<String> elements(Node root) {
        return all(root).stream().filter(n -> n.markup() != null && n.kind() != Kind.TEXT).map(n -> n.markup().element()).toList();
    }

    private static long nid(DocumentAssembly assembly, String text) {
        return assembly.lines().stream().filter(l -> l.text().equals(text)).findFirst().orElseThrow().nid();
    }

    @Test
    void theDocBookProjectionLeavesOutWhatAConditionalLeavesOut() {
        DocumentAssembly assembly = assembly(TOPIC, Map.of());

        Node root = DocumentGraph.docbook(assembly);
        assertEquals(List.of("A Topic", "Before the conditionals.", "Only outside the compendium.", "In the book, on one line.", "After the conditionals."),
                texts(root), "the document as it reads: the passage for the compendium is no part of it, the passage for elsewhere is");
        assertFalse(elements(root).contains("Conditional"), "nor is a directive: a DocBook document is made after the conditionals are decided");
        assertEquals("", root.markup().attributes().get("book"), "the header's attribute entry is still the document's attribute");

        // what is read stands at its own line, so it selects that line on the other views
        Node outside = all(root).stream().filter(n -> n.kind() == Kind.TEXT && n.markup().text().equals("Only outside the compendium.")).findFirst().orElseThrow();
        int ordinal = assembly.ordinal(assembly.host(), nid(assembly, "Only outside the compendium."));
        assertEquals(new LineSelection(ordinal, ordinal), outside.selection().orElseThrow());
        Node oneLine = all(root).stream().filter(n -> n.kind() == Kind.TEXT && n.markup().text().equals("In the book, on one line.")).findFirst().orElseThrow();
        int held = assembly.ordinal(assembly.host(), nid(assembly, "ifdef::book[In the book, on one line.]"));
        assertEquals(new LineSelection(held, held), oneLine.selection().orElseThrow(),
                "the text a one-line conditional holds is content where its condition is met, on the directive's own line");

        // no node of the projection spans a line that is not read
        for (String left : List.of("ifdef::compendium[]", "Only in the compendium.", "ifndef::compendium[]", "ifdef::compendium[In the compendium, on one line.]")) {
            int at = assembly.ordinal(assembly.host(), nid(assembly, left));
            assertTrue(at >= 0, "the line is still a line of the assembly: " + left);
            assertTrue(all(root).stream().noneMatch(n -> n.kind() != Kind.DOCUMENT && n.kind() != Kind.BLOCK && n.markup() != null
                    && n.markup().lines().stream().anyMatch(l -> l.text().equals(left))), "no element is made of the line: " + left);
        }
    }

    @Test
    void theLatestModeShowsEveryLineTheStoreHolds() {
        DocumentAssembly assembly = assembly(TOPIC, Map.of());

        List<String> lines = all(DocumentGraph.of(assembly)).stream().filter(n -> n.kind() == Kind.LINE).map(n -> n.line().text()).toList();
        assertTrue(lines.containsAll(List.of("ifdef::compendium[]", "Only in the compendium.", "endif::[]", "ifndef::compendium[]",
                "Only outside the compendium.", "ifdef::book[In the book, on one line.]", "ifdef::compendium[In the compendium, on one line.]")),
                "the graph as the store holds it keeps the conditionals and both passages: " + lines);
        for (StoredLine line : assembly.lines()) {
            if (!line.text().isBlank()) {
                assertTrue(lines.contains(line.text()), "a line of the document has its row: " + line.text());
            }
        }
    }

    @Test
    void anIncludeAConditionalLeavesOutIsNoPartOfTheDocBookProjection() {
        StoredDocument forCompendium = DocumentGraphTest.stored("""
                = For the Compendium

                Compendium words.
                """);
        StoredDocument forBook = DocumentGraphTest.stored("""
                = For the Book

                Book words.
                """);
        DocumentAssembly assembly = assembly("""
                = A Book

                ifdef::compendium[]
                include::topics/compendium.adoc[]
                endif::[]

                ifndef::compendium[]
                include::topics/book.adoc[]
                endif::[]
                """, Map.of("docs/topics/compendium.adoc", forCompendium, "docs/topics/book.adoc", forBook));

        Node root = DocumentGraph.docbook(assembly);
        List<Node> included = all(root).stream().filter(n -> n.kind() == Kind.DOCUMENT && n != root).toList();
        assertEquals(List.of("For the Book"), included.stream().map(Node::label).toList(), "the include that is read is the included document, in place");
        assertTrue(texts(root).contains("Book words."));
        assertFalse(texts(root).contains("Compendium words."));
        assertFalse(elements(root).contains("Include"), "the include that is not read is not shown as an include left unresolved");
        assertFalse(elements(root).contains("Conditional"));
    }
}

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

import network.ike.komet.document.DocumentConditions.Condition;
import network.ike.komet.document.GitDocumentMaterializer.StoredDocument;
import network.ike.komet.document.GitDocumentMaterializer.StoredLine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a document's conditional directives leave out, for a person who wants to see and change
 * it: the directive over each line that is not read, in plain words; and a document read with an
 * attribute a reader set, so it reads as another document that includes the same topics reads it.
 */
class DocumentConditionsTest {

    /** A topic that says one thing in the compendium and another outside it; {@code book} is set, {@code compendium} is not. */
    private static final String TOPIC = """
            = A Topic
            :book:

            Before the conditionals.

            ifdef::compendium[]
            Only in the compendium.

            ifdef::book[]
            In the compendium's book.
            endif::[]
            endif::[]

            ifndef::compendium[]
            Only outside the compendium.
            endif::[]

            ifdef::book[]
            In any book.
            ifdef::compendium[]
            In a book of the compendium.
            endif::[]
            endif::[]

            ifdef::compendium[In the compendium, on one line.]

            ifdef::book[In the book, on one line.]

            After the conditionals.
            """;

    private static DocumentAssembly assembly(String text, Map<String, StoredDocument> store, List<String> set) {
        return DocumentAssembly.of(DocumentGraphTest.stored(text), "docs/book.adoc", path -> Optional.ofNullable(store.get(path)), rest -> List.of(), set);
    }

    private static StoredLine line(DocumentAssembly assembly, String text) {
        return assembly.lines().stream().filter(l -> l.text().equals(text)).findFirst().orElseThrow(() -> new AssertionError("no line " + text));
    }

    private static Optional<Condition> leftOut(DocumentConditions conditions, DocumentAssembly assembly, String text) {
        return conditions.leftOut(assembly.host(), line(assembly, text).nid());
    }

    private static List<String> read(DocumentAssembly assembly) {
        return assembly.read(assembly.host()).lines().stream().map(StoredLine::text).filter(t -> !t.isBlank()).toList();
    }

    @Test
    void aLineThatIsNotReadStandsUnderTheDirectiveThatLeavesItOut() {
        DocumentAssembly assembly = assembly(TOPIC, Map.of(), List.of());
        DocumentConditions conditions = DocumentConditions.of(assembly);

        Condition compendium = leftOut(conditions, assembly, "Only in the compendium.").orElseThrow();
        assertEquals("ifdef::compendium[]", compendium.directive());
        assertEquals("where compendium is set", compendium.says());
        assertEquals("Left out here: read only where compendium is set", compendium.label());
        assertSame(assembly.host(), compendium.member());
        assertEquals("ifdef::compendium[]", compendium.line().text());

        assertSame(compendium, leftOut(conditions, assembly, "In the compendium's book.").orElseThrow(),
                "a line under a met directive inside an unmet one is left out by the outer one: the passage is one");
        assertEquals("ifdef::compendium[]", leftOut(conditions, assembly, "In a book of the compendium.").orElseThrow().directive(),
                "a line under an unmet directive inside a met one is left out by the inner one");
        assertTrue(leftOut(conditions, assembly, "In a book of the compendium.").orElseThrow() != compendium, "which is another directive than the first");
        assertEquals("ifdef::compendium[In the compendium, on one line.]",
                leftOut(conditions, assembly, "ifdef::compendium[In the compendium, on one line.]").orElseThrow().directive(),
                "a conditional that holds its text on its own line is left out by itself");

        for (String read : List.of("Before the conditionals.", "Only outside the compendium.", "In any book.", "ifdef::book[In the book, on one line.]",
                "After the conditionals.")) {
            assertTrue(leftOut(conditions, assembly, read).isEmpty(), "read, so under no directive that leaves it out: " + read);
        }
        assertEquals(List.of("compendium", "book"), conditions.names(), "the names the directives ask about, as they are first asked");
        assertTrue(conditions.touches(assembly.host()));
    }

    @Test
    void everyLineThatIsNotReadIsADirectiveOrIsLeftOutByOne() {
        DocumentAssembly assembly = assembly(TOPIC, Map.of(), List.of());
        DocumentConditions conditions = DocumentConditions.of(assembly);
        StoredDocument reads = assembly.read(assembly.host());
        int directives = 0;
        for (StoredLine line : assembly.lines()) {
            boolean read = reads.line(line.nid()).isPresent();
            boolean directive = conditions.directive(assembly.host(), line.nid());
            boolean leftOut = conditions.leftOut(assembly.host(), line.nid()).isPresent();
            assertEquals(!read, directive || leftOut, "what the assembly does not read is a directive or a passage one leaves out: " + line.text());
            assertFalse(directive && leftOut, "never both: " + line.text());
            directives += directive ? 1 : 0;
        }
        assertEquals(10, directives, "five conditionals open and five close; the two that hold their text on one line open nothing");
    }

    @Test
    void aDirectiveSaysInPlainWordsWhenItsLinesAreRead() {
        assertEquals("where compendium is set", DocumentConditions.says("ifdef", "compendium", ""));
        assertEquals("where compendium is not set", DocumentConditions.says("ifndef", "compendium", ""));
        assertEquals("where compendium or book is set", DocumentConditions.says("ifdef", "compendium,book", ""));
        assertEquals("where compendium and book are both set", DocumentConditions.says("ifdef", "compendium+book", ""));
        assertEquals("where neither compendium nor regulatory-decision-analysis is set",
                DocumentConditions.says("ifndef", "compendium,regulatory-decision-analysis", ""), "a comma after ifndef: read only where none is set");
        assertEquals("where compendium and book are not both set", DocumentConditions.says("ifndef", "compendium+book", ""));
        assertEquals("where any of a, b, c is set", DocumentConditions.says("ifdef", "a,b,c", ""));
        assertEquals("where none of a, b, c is set", DocumentConditions.says("ifndef", "a,b,c", ""));
        assertEquals("where {level} > 2 holds", DocumentConditions.says("ifeval", "", "{level} > 2"));
    }

    @Test
    void theWordsOfADirectiveAgreeWithWhatTheAssemblyReads() {
        // each directive over a line of its own, read under every setting of two attributes: where the words say it is read, it is
        String text = """
                = Words

                ifdef::a,b[]
                any set
                endif::[]

                ifdef::a+b[]
                both set
                endif::[]

                ifndef::a,b[]
                neither set
                endif::[]

                ifndef::a+b[]
                not both set
                endif::[]
                """;
        for (List<String> set : List.of(List.<String>of(), List.of("a"), List.of("b"), List.of("a", "b"))) {
            List<String> read = read(assembly(text, Map.of(), set));
            boolean a = set.contains("a");
            boolean b = set.contains("b");
            assertEquals(a || b, read.contains("any set"), "ifdef::a,b[] with " + set);
            assertEquals(a && b, read.contains("both set"), "ifdef::a+b[] with " + set);
            assertEquals(!a && !b, read.contains("neither set"), "ifndef::a,b[] with " + set);
            assertEquals(!(a && b), read.contains("not both set"), "ifndef::a+b[] with " + set);
        }
    }

    @Test
    void anAttributeAReaderSetsMakesTheDocumentReadAsAnotherReadsIt() {
        DocumentAssembly asWritten = assembly(TOPIC, Map.of(), List.of());
        assertEquals(List.of(), asWritten.set());
        assertEquals(List.of("= A Topic", ":book:", "Before the conditionals.", "Only outside the compendium.", "In any book.",
                "ifdef::book[In the book, on one line.]", "After the conditionals."), read(asWritten));

        DocumentAssembly asCompendium = assembly(TOPIC, Map.of(), List.of("compendium"));
        assertEquals(List.of("compendium"), asCompendium.set());
        assertEquals(List.of("= A Topic", ":book:", "Before the conditionals.", "Only in the compendium.", "In the compendium's book.", "In any book.",
                        "In a book of the compendium.", "ifdef::compendium[In the compendium, on one line.]", "ifdef::book[In the book, on one line.]",
                        "After the conditionals."), read(asCompendium),
                "with compendium set before the first line, the passages kept for the compendium are read and the one kept from it is not");
        DocumentConditions conditions = DocumentConditions.of(asCompendium);
        assertEquals("where compendium is not set", leftOut(conditions, asCompendium, "Only outside the compendium.").orElseThrow().says());
        assertTrue(asCompendium.attributes(asCompendium.host(), asCompendium.lines().getLast().nid()).defined("compendium"));
        assertFalse(asWritten.attributes(asWritten.host(), asWritten.lines().getLast().nid()).defined("compendium"));
    }

    @Test
    void anIncludeUnderAConditionAReaderMeetsIsFollowed() {
        StoredDocument forCompendium = DocumentGraphTest.stored("""
                = For the Compendium

                Compendium words.
                """);
        String book = """
                = A Book

                ifdef::compendium[]
                include::topics/compendium.adoc[]
                endif::[]
                """;
        Map<String, StoredDocument> store = Map.of("docs/topics/compendium.adoc", forCompendium);
        assertEquals(List.of(), assembly(book, store, List.of()).children(), "as written, the include is left out and not followed");
        DocumentAssembly asCompendium = assembly(book, store, List.of("compendium"));
        assertEquals(List.of("docs/topics/compendium.adoc"), asCompendium.children().stream().map(DocumentAssembly.Member::path).toList(),
                "with the attribute set, the topic is shown in place");
        assertTrue(asCompendium.lines().stream().anyMatch(l -> l.text().equals("Compendium words.")));
    }

    @Test
    void theDocumentHasTheLastWordOverAnAttributeAReaderSet() {
        DocumentAssembly assembly = assembly("""
                = A Topic
                :compendium!:

                ifdef::compendium[]
                Only in the compendium.
                endif::[]
                """, Map.of(), List.of("compendium"));
        assertFalse(read(assembly).contains("Only in the compendium."), "a document that unsets the attribute itself is read without it from that line on");
    }

    @Test
    void theStatusLineSaysWhatTheCardSet() {
        assertEquals("", StampedDocumentCard.readWith(List.of()));
        assertEquals("Read with compendium set. ", StampedDocumentCard.readWith(List.of("compendium")));
        assertEquals("Read with compendium, draft set. ", StampedDocumentCard.readWith(List.of("compendium", "draft")));
    }
}

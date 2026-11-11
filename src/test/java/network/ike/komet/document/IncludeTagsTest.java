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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tagged regions an include names ({@code dev-document-front-matter}, the third step). What
 * each form shows was taken from Asciidoctor 2.0.26 itself on 2026-11-10: this file's lines were
 * included into a listing block with each attribute below, and the lines it printed are the
 * lines expected here.
 */
class IncludeTagsTest {

    /** A file with a region, a region inside it, a second region, and lines under no tag. */
    private static final List<String> FILE = List.of(
            "untagged one",
            "// tag::foo[]",
            "foo one",
            "// tag::bar[]",
            "bar one",
            "// end::bar[]",
            "foo two",
            "// end::foo[]",
            "untagged two",
            "// tag::baz[]",
            "baz one",
            "// end::baz[]",
            "untagged three");

    private static List<String> shown(String parameters) {
        IncludeTags tags = IncludeTags.of(parameters).orElseThrow(() -> new AssertionError("no tags read from [" + parameters + "]"));
        return tags.select(FILE).shown().stream().map(FILE::get).toList();
    }

    @Test
    void aTagShowsTheLinesBetweenItsTwoTagLinesAndNoTagLine() {
        assertEquals(List.of("foo one", "bar one", "foo two"), shown("tag=foo"), "the region, the region inside it too, and none of the four tag lines");
        assertEquals(List.of("bar one"), shown("tag=bar"));
        assertEquals(List.of("foo one", "bar one", "foo two"), shown("tag=foo,leveloffset=+1"), "beside another attribute");
        assertEquals(List.of("foo one", "bar one", "foo two"), shown("leveloffset=+1, tag=foo"));
    }

    @Test
    void tagsShowsSeveralRegionsInTheFilesOrder() {
        assertEquals(List.of("foo one", "bar one", "foo two", "baz one"), shown("tags=foo;baz"));
        assertEquals(List.of("foo one", "bar one", "foo two", "baz one"), shown("tags=\"foo,baz\""), "commas, when the value is quoted");
        assertEquals(List.of("foo one", "bar one", "foo two", "baz one"), shown("tags=baz;foo"), "the file's order, not the attribute's");
        assertEquals(List.of("foo one", "bar one", "foo two"), shown("tags=bar;foo"));
    }

    @Test
    void aNegatedTagLeavesItsRegionOut() {
        assertEquals(List.of("foo one", "foo two"), shown("tags=foo;!bar"), "the region without the region inside it");
        assertEquals(List.of("untagged one", "untagged two", "baz one", "untagged three"), shown("tags=!foo"),
                "an include that only leaves regions out starts from every line");
        assertEquals(List.of("untagged one", "untagged two", "baz one", "untagged three"), shown("tag=!foo"));
        assertEquals(List.of("untagged one", "foo one", "foo two", "untagged two", "baz one", "untagged three"), shown("tags=!bar"));
        assertEquals(List.of("untagged one", "untagged two", "untagged three"), shown("tags=!foo;!baz"));
    }

    @Test
    void theWildcardsStandForEveryRegionAndEveryLine() {
        assertEquals(List.of("foo one", "bar one", "foo two", "baz one"), shown("tags=*"), "every tagged region");
        assertEquals(List.of("untagged one", "untagged two", "untagged three"), shown("tags=!*"), "the lines under no tag");
        assertEquals(List.of("untagged one", "foo one", "bar one", "foo two", "untagged two", "baz one", "untagged three"), shown("tags=**"),
                "every line but the tag lines");
        assertEquals(List.of("untagged one", "untagged two", "baz one", "untagged three"), shown("tags=**;!foo"));
        assertEquals(List.of("untagged one", "untagged two", "untagged three"), shown("tags=**;!*"));
        assertEquals(List.of("foo one", "foo two"), shown("tags=foo;!*"), "a region without any region inside it");
        assertEquals(List.of("baz one"), shown("tags=*;!foo"));
        assertEquals(List.of("foo one", "foo two", "baz one"), shown("tags=*;!bar"));
        assertEquals(List.of("untagged one", "foo one", "foo two", "untagged two", "untagged three"), shown("tags=!*;foo"));
    }

    @Test
    void aTagTheFileDoesNotHaveIsNamedAndShowsNothing() {
        IncludeTags.Region none = IncludeTags.of("tag=nope").orElseThrow().select(FILE);
        assertEquals(List.of(), none.shown());
        assertEquals(List.of("nope"), none.missing());

        IncludeTags.Region some = IncludeTags.of("tags=foo;nope").orElseThrow().select(FILE);
        assertEquals(List.of(2, 4, 6), some.shown(), "the region that is there is shown");
        assertEquals(List.of("nope"), some.missing());

        assertEquals(List.of(), IncludeTags.of("tags=foo;!nope").orElseThrow().select(FILE).missing(), "a region to leave out need not be there");
    }

    @Test
    void anIncludeWithoutTagsShowsTheWholeFile() {
        assertTrue(IncludeTags.of("").isEmpty());
        assertTrue(IncludeTags.of(null).isEmpty());
        assertTrue(IncludeTags.of("leveloffset=+1").isEmpty());
        assertTrue(IncludeTags.of("tag=").isEmpty(), "an empty tag names nothing");
        assertTrue(IncludeTags.of("tag=!").isEmpty());
        assertTrue(IncludeTags.of("lines=1..3;tag=foo").isEmpty() && IncludeTags.of("tag=foo,lines=1..3").isEmpty(),
                "lines= has precedence over tags in Asciidoctor, and is not read here: the whole file is shown");
        assertEquals("tag=foo", IncludeTags.of("leveloffset=+1,tag=foo").orElseThrow().written());
        assertEquals("tags=foo;!bar", IncludeTags.of("tags=foo;!bar").orElseThrow().written());
    }

    @Test
    void aTagLineIsTheDirectiveAfterAWordBoundaryAndBeforeASpaceOrTheLinesEnd() {
        assertTrue(IncludeTags.directive("// tag::brief[]"));
        assertTrue(IncludeTags.directive("// end::brief[]"));
        assertTrue(IncludeTags.directive("# tag::timings[]"), "behind another language's comment");
        assertTrue(IncludeTags.directive("<!-- tag::name[] -->"));
        assertTrue(IncludeTags.directive("tag::bare[]"), "and with no comment at all, as Asciidoctor reads it");
        assertFalse(IncludeTags.directive("// tag::brief[]x"), "something after the brackets");
        assertFalse(IncludeTags.directive("// tag::[]"), "no name");
        assertFalse(IncludeTags.directive("// tag::two words[]"));
        assertFalse(IncludeTags.directive("We propose a way."));
        assertFalse(IncludeTags.directive("include::summary.adoc[tag=brief]"));
    }

    @Test
    void anEndThatIsNotTheInnermostOpenTagsClosesItsOwnRegion() {
        List<String> crossed = List.of("// tag::a[]", "a", "// tag::b[]", "a and b", "// end::a[]", "b", "// end::b[]", "after");
        IncludeTags tags = IncludeTags.of("tags=a;b").orElseThrow();
        assertEquals(List.of(1, 3, 5), tags.select(crossed).shown(), "as Asciidoctor closes it, with a warning: what both regions hold, and no line after");
    }
}

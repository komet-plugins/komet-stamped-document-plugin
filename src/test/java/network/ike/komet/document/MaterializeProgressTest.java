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

import dev.ikm.tinkar.common.id.PublicIds;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the materializing strip on a STAMPed Document card says as a run goes, pauses, stops and
 * ends, read from its text model with no window.
 */
class MaterializeProgressTest {

    private static final Path ROOT = Path.of("/work/adoc-demo").toAbsolutePath();
    private static final Path HOST = Path.of("regulatory/regulatory-decision-analysis.adoc");
    private static final List<Path> FILES = List.of(ROOT.resolve(HOST),
            ROOT.resolve("regulatory/topics/rda/rda_context.adoc"),
            ROOT.resolve("regulatory/topics/rda/rda-options.adoc"));

    private static MaterializeProgress.Progress planned() {
        MaterializeProgress.Progress progress = new MaterializeProgress.Progress(ROOT, HOST);
        progress.planned(FILES, List.of());
        return progress;
    }

    @Test
    void beforeThePlanTheStripNamesTheFileWhoseIncludesAreBeingFound() {
        MaterializeProgress.Progress progress = new MaterializeProgress.Progress(ROOT, HOST);

        assertEquals("Finding the files that **regulatory-decision-analysis.adoc** includes …", progress.markdown());
        assertEquals(0, progress.total());
    }

    @Test
    void aRunningRunCountsTheFileBeingWrittenAndNamesIt() {
        MaterializeProgress.Progress progress = planned();
        progress.writing(1, FILES.get(0));
        progress.written(1);
        progress.writing(2, FILES.get(1));

        assertEquals("Materializing 2 of 3: **rda\\_context.adoc**", progress.markdown(), "an underscore reads as written");
        assertEquals(1, progress.written());
        assertEquals(3, progress.total());
    }

    @Test
    void aPauseWaitsForTheFileBeingWrittenThenSaysWhereTheRunStands() {
        MaterializeProgress.Progress progress = planned();
        progress.writing(1, FILES.get(0));
        progress.state(AssemblyRun.State.PAUSED);

        assertEquals("Pausing when **regulatory-decision-analysis.adoc** is materialized.", progress.markdown());

        progress.written(1);

        assertEquals("Paused after 1 of 3 files.", progress.markdown());

        progress.state(AssemblyRun.State.RUNNING);
        progress.writing(2, FILES.get(1));

        assertEquals("Materializing 2 of 3: **rda\\_context.adoc**", progress.markdown());
    }

    @Test
    void aStoppedRunListsTheFilesItDidNotReachAndHowToWriteThem() {
        MaterializeProgress.Progress progress = planned();
        progress.writing(1, FILES.get(0));
        progress.state(AssemblyRun.State.STOPPED);

        assertEquals("Stopping when **regulatory-decision-analysis.adoc** is materialized.", progress.markdown());

        progress.written(1);
        progress.ended(new GitDocumentMaterializer.AssemblyResult(List.of(result("regulatory/regulatory-decision-analysis.adoc")),
                List.of(), FILES.subList(1, 3)), null);

        assertTrue(progress.ended());
        assertEquals("""
                Stopped after 1 of 3 files.

                The run did not reach the other 2 files. A file the knowledge base does not hold yet shows as its include line \
                in the document. Open the file again to materialize the rest.

                - **regulatory/topics/rda/rda\\_context.adoc**
                - **regulatory/topics/rda/rda-options.adoc**""", progress.markdown());
        assertEquals(2, progress.listed());
        assertEquals(progress.markdown(true), progress.markdown(false), "a short list is shown in full either way");
    }

    @Test
    void aLongListWaitsForShowAndTheCountsStandForIt() {
        List<Path> files = new java.util.ArrayList<>(List.of(ROOT.resolve(HOST)));
        for (int i = 1; i <= 8; i++) {
            files.add(ROOT.resolve("regulatory/topics/rda/topic-" + i + ".adoc"));
        }
        MaterializeProgress.Progress progress = new MaterializeProgress.Progress(ROOT, HOST);
        progress.planned(files, List.of());
        progress.ended(new GitDocumentMaterializer.AssemblyResult(List.of(result("host")), List.of(), files.subList(1, 9)), null);

        assertEquals(8, progress.listed());
        assertEquals("""
                Stopped after 1 of 9 files.

                The run did not reach the other 8 files. A file the knowledge base does not hold yet shows as its include line \
                in the document. Open the file again to materialize the rest.""", progress.markdown(false));
        assertTrue(progress.markdown(true).contains("- **regulatory/topics/rda/topic-8.adoc**"), "Show lists every file");
    }

    @Test
    void anIncludeThatCannotBeFoundIsListedAndTheRunGoesOn() {
        MaterializeProgress.Progress progress = new MaterializeProgress.Progress(ROOT, HOST);
        GitDocumentMaterializer.Unresolved absent = new GitDocumentMaterializer.Unresolved(HOST, 14, "topics/rda/absent.adoc", "not committed");
        progress.planned(FILES, List.of(absent));
        progress.writing(3, FILES.get(2));

        assertEquals("""
                Materializing 3 of 3: **rda-options.adoc**

                One include could not be found and is left out.

                - **topics/rda/absent.adoc**, line 14 of **regulatory/regulatory-decision-analysis.adoc**: not committed""",
                progress.markdown());
    }

    @Test
    void aRunThatWroteEveryFileSaysSo() {
        MaterializeProgress.Progress progress = planned();
        progress.ended(new GitDocumentMaterializer.AssemblyResult(List.of(result("a"), result("b"), result("c")), List.of()), null);

        assertEquals("Materialized all 3 files.", progress.markdown());
        assertEquals(3, progress.written());
    }

    @Test
    void aFailedRunNamesTheFileItFailedOn() {
        MaterializeProgress.Progress progress = planned();
        progress.writing(2, FILES.get(1));
        progress.ended(null, new IllegalStateException("The open store lacks the document vertex terminology"));

        assertEquals("Materializing stopped at **rda\\_context.adoc** because of an error. The open store lacks the document vertex terminology",
                progress.markdown());
    }

    @Test
    void markdownMarkupInANameIsEscaped() {
        assertEquals("a\\_b\\*c\\[d\\].adoc", MaterializeProgress.Progress.escape("a_b*c[d].adoc"));
        assertFalse(MaterializeProgress.Progress.escape("plain-name.adoc").contains("\\"));
    }

    private static GitDocumentMaterializer.Result result(String name) {
        return new GitDocumentMaterializer.Result(PublicIds.of(UUID.nameUUIDFromBytes(name.getBytes())), name, 1, 1, 0, 1, 0, "0000000");
    }
}

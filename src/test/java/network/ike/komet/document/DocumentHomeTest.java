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
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A stored document's repository, found among the places Komet knows without asking: the one
 * place that holds the file under the document's identity, and no answer when none or two do.
 */
class DocumentHomeTest {

    private static final String PATH = "value-graph/decision-tree.adoc";

    @TempDir
    Path folder;

    /** The identity the store gives a file: its repository folder's name and its path. */
    private static BiPredicate<Path, String> named(String repository) {
        return (root, path) -> root.getFileName().toString().equals(repository) && path.equals(PATH);
    }

    private Path repository(String at, boolean withFile) throws IOException {
        Path root = folder.resolve(at);
        Files.createDirectories(root.resolve(".git"));
        if (withFile) {
            Files.createDirectories(root.resolve(PATH).getParent());
            Files.writeString(root.resolve(PATH), "= The Decision as a Tree\n");
        }
        return root;
    }

    @Test
    void theLibraryHoldsTheDocument() throws Exception {
        Path library = repository("komet/adoc", true);

        assertEquals(Optional.of(library), DocumentHome.find(PATH, List.of(), library, named("adoc")),
                "a card that never opened the file finds it in the library");
    }

    @Test
    void aFolderInsideTheLibrarysRepositoryLeadsToItsTop() throws Exception {
        Path library = repository("komet/adoc", true);

        assertEquals(Optional.of(library), DocumentHome.find(PATH, List.of(), library.resolve("value-graph"), named("adoc")));
    }

    @Test
    void aWorkingTreeOpenedThisSessionHoldsTheDocument() throws Exception {
        Path library = repository("komet/adoc", false);
        Path opened = repository("work/ike-lab-documents", true);

        assertEquals(Optional.of(opened), DocumentHome.find(PATH, List.of(opened), library, named("ike-lab-documents")));
    }

    @Test
    void aFileOfTheSamePathInAnotherRepositoryIsNotTheDocument() throws Exception {
        Path library = repository("komet/adoc", true);

        assertEquals(Optional.empty(), DocumentHome.find(PATH, List.of(), library, named("ike-lab-documents")),
                "the path is there, but the library gives it another identity");
    }

    @Test
    void aPlaceWithoutTheFileIsNotTheAnswer() throws Exception {
        Path library = repository("komet/adoc", false);

        assertEquals(Optional.empty(), DocumentHome.find(PATH, List.of(), library, named("adoc")));
    }

    @Test
    void twoRepositoriesOfOneNameAreNotChosenBetween() throws Exception {
        Path library = repository("komet/adoc", true);
        Path copy = repository("elsewhere/adoc", true);

        assertEquals(Optional.empty(), DocumentHome.find(PATH, List.of(copy), library, named("adoc")),
                "both hold the file under the document's identity, so the person is asked");
    }

    @Test
    void theLibraryOpenedAsAWorkingTreeIsOnePlace() throws Exception {
        Path library = repository("komet/adoc", true);

        assertEquals(Optional.of(library), DocumentHome.find(PATH, List.of(library), library, named("adoc")));
    }

    @Test
    void aLibraryThatDoesNotExistIsNotLookedIn() throws Exception {
        Files.createDirectories(folder.resolve(".git"));
        Files.createDirectories(folder.resolve(PATH).getParent());
        Files.writeString(folder.resolve(PATH), "= The Decision as a Tree\n");

        assertEquals(Optional.empty(), DocumentHome.find(PATH, List.of(), folder.resolve("komet/adoc"), (root, path) -> true),
                "a repository above a missing library is not the library");
    }

    @Test
    void aDocumentWithNoRecordedPathHasNoHome() throws Exception {
        Path library = repository("komet/adoc", true);

        assertEquals(Optional.empty(), DocumentHome.find(null, List.of(), library, (root, path) -> true));
    }
}

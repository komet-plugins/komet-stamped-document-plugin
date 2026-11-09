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

import design.knowledge.stamp.adoc.git.GitRepo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Where a stored document's file is, found without asking. The store records what a document is,
 * its repository folder's name and its path there, and not where that folder stands on this
 * machine. A card learns the place when it opens a file. A card that shows a document it did not
 * open from a file, one chosen from the store or one a tool opened, has no place for it, and a
 * comment or a commit then had to ask for the repository.
 *
 * <p>The places Komet already knows are looked in first: the working trees a document card has
 * opened this session, and the document library. A place holds the document when the file is
 * there and the place gives it the identity the store has for it. One such place is the answer.
 * None, or more than one, is no answer: the person is asked, since two repositories of one name
 * that both hold the file cannot be told apart.
 */
final class DocumentHome {

    private DocumentHome() {
    }

    /**
     * Finds the working tree that holds a document.
     *
     * @param path       the document's repository-relative path
     * @param opened     the working trees opened this session
     * @param library    the document library's folder, which need not exist
     * @param isDocument whether a file at a path of a working tree is the document, by its identity
     * @return the one working tree that holds it; empty when none does, or more than one
     */
    static Optional<Path> find(String path, Collection<Path> opened, Path library, BiPredicate<Path, String> isDocument) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        Set<Path> places = new LinkedHashSet<>();
        for (Path tree : opened) {
            places.add(tree.toAbsolutePath().normalize());
        }
        if (library != null && Files.isDirectory(library)) {
            GitRepo.findRoot(library).map(root -> root.toAbsolutePath().normalize()).ifPresent(places::add);
        }
        Path found = null;
        for (Path place : places) {
            if (Files.isRegularFile(place.resolve(path)) && isDocument.test(place, path)) {
                if (found != null) {
                    return Optional.empty();
                }
                found = place;
            }
        }
        return Optional.ofNullable(found);
    }
}

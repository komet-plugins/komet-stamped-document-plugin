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

/**
 * Each document's own module (IKE-Network/ike-issues#1313; design
 * {@code design-2026-11-22-knowledge-shared-local}, settled by KEC on 2026-11-22). A document's
 * lines, blocks, annotations and anything else that belongs only to it are written under a module of
 * its own, so they are kept apart, counted, and removed or exported with it. The module is named
 * {@code <working tree>/<path>}, the component of the git identity adapter's frozen
 * {@code module:<component>} key, and is a kind of File module. Before 2026-11-22 a document's
 * module was its working tree's, named for the working tree alone, and documents read in then
 * keep it until they are read in again.
 */
final class DocumentModules {

    private DocumentModules() {
    }

    /**
     * @param workingTree the working tree's folder name
     * @param path        the file's path in the working tree
     * @return the component the document's own module is named by
     */
    static String component(String workingTree, String path) {
        return workingTree + "/" + path.replace('\\', '/');
    }

    /**
     * The working tree a module name gives for a file: a document's own module names its working
     * tree and path, and a working tree's module of before 2026-11-22 names the working tree alone.
     *
     * @param moduleName the module's name
     * @param path       the file's path in the working tree
     * @return the working tree's folder name
     */
    static String workingTree(String moduleName, String path) {
        if (moduleName == null) {
            return null;
        }
        String suffix = "/" + path.replace('\\', '/');
        return moduleName.endsWith(suffix) ? moduleName.substring(0, moduleName.length() - suffix.length()) : moduleName;
    }
}

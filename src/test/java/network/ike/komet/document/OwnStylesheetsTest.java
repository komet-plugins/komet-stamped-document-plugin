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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A stylesheet this plugin names is one of its own. In Komet the plugin is a named module, and a
 * class finds a resource in its own module alone: a sheet in another plugin's module is not found,
 * and the code that asks for it goes on without it, in JavaFX's default look. On the class path of
 * a test the same lookup succeeds, so the sources are read here instead.
 */
class OwnStylesheetsTest {

    private static final Path SOURCES = Path.of("src/main/java");
    private static final Path RESOURCES = Path.of("src/main/resources");
    private static final Pattern SHEET = Pattern.compile("\"([^\"\\s]+\\.css)\"");

    private static List<String> missing() throws IOException {
        List<String> missing = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher sheet = SHEET.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (sheet.find()) {
                    String name = sheet.group(1);
                    Path own = name.startsWith("/")
                            ? RESOURCES.resolve(name.substring(1))
                            : RESOURCES.resolve(SOURCES.relativize(file.getParent())).resolve(name);
                    if (!Files.isRegularFile(own)) {
                        missing.add(SOURCES.relativize(file) + " names " + name);
                    }
                }
            }
        }
        return missing;
    }

    @Test
    @DisplayName("Every stylesheet the sources name is among this plugin's own resources")
    void everyNamedStylesheetIsOwn() throws IOException {
        List<String> missing = missing();
        assertTrue(missing.isEmpty(), "a sheet outside this plugin is not found when the plugin is a module: " + missing);
    }

    @Test
    @DisplayName("The surface's sheet takes the default box off a prose block, and the card's sheet styles its pending-edit buttons")
    void theSheetsCarryTheRulesTheirClassesNeed() throws IOException {
        String surface = Files.readString(RESOURCES.resolve("network/ike/komet/document/surface/document-surface.css"), StandardCharsets.UTF_8);
        for (String styleClass : List.of(".claude-doc-surface", ".claude-doc-frame", ".claude-doc-stack .doc-prose-block",
                ".doc-header-region", ".doc-footer-region", ".doc-turn-header")) {
            assertTrue(surface.contains(styleClass), "document-surface.css has no rule for " + styleClass);
        }
        String card = Files.readString(RESOURCES.resolve("network/ike/komet/document/stamped-document.css"), StandardCharsets.UTF_8);
        String bar = Files.readString(SOURCES.resolve("network/ike/komet/document/PendingEditBar.java"), StandardCharsets.UTF_8);
        for (String styleClass : List.of("stamped-pending-bar", "stamped-pending-summary", "stamped-pending-commit", "stamped-pending-quiet")) {
            assertTrue(bar.contains('"' + styleClass + '"'), "the pending-edit bar no longer names " + styleClass);
            assertTrue(card.contains("." + styleClass), "stamped-document.css has no rule for ." + styleClass);
        }
        String sources = Files.readString(SOURCES.resolve("network/ike/komet/document/StampedDocumentCard.java"), StandardCharsets.UTF_8);
        assertFalse(sources.contains("claude-card-toolbar-button"), "the card names a style class only the assistant's sheet has");
    }
}

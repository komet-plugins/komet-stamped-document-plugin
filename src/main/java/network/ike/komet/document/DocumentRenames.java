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

import dev.ikm.tinkar.common.service.RenamedClasses;

import java.util.List;
import java.util.Optional;

/**
 * The classes this plugin brought from komet-claude-plugin under new packages
 * (IKE-Network/ike-issues#1351), so a saved window, card or area that names one by its former
 * name restores: {@code network.ike.komet.claude.stamp.StampedDocumentCard$Factory} is
 * {@code network.ike.komet.document.StampedDocumentCard$Factory} now.
 */
public final class DocumentRenames implements RenamedClasses {

    /** Each former package with the package its classes are in now, the deepest first. */
    private static final List<String[]> PACKAGES = List.of(
            new String[]{"network.ike.komet.claude.doc.print.settings.", "network.ike.komet.document.surface.print.settings."},
            new String[]{"network.ike.komet.claude.doc.print.", "network.ike.komet.document.surface.print."},
            new String[]{"network.ike.komet.claude.doc.", "network.ike.komet.document.surface."},
            new String[]{"network.ike.komet.claude.stamp.print.", "network.ike.komet.document.print."},
            new String[]{"network.ike.komet.claude.stamp.", "network.ike.komet.document."});

    /** The help viewer's classes, which left komet-claude-plugin's help package one by one. */
    private static final List<String> HELP = List.of("AsciiDocHelpViewer", "AsciiDocHelpViewerFactory", "HelpSearch");

    /** Public no-arg constructor, for the service loader. */
    public DocumentRenames() {
    }

    @Override
    public Optional<String> current(String formerName) {
        for (String[] rename : PACKAGES) {
            String rest = formerName.startsWith(rename[0]) ? formerName.substring(rename[0].length()) : null;
            // a class of the package itself, never one of a deeper package this list does not name
            if (rest != null && !rest.isEmpty() && Character.isUpperCase(rest.charAt(0))) {
                return Optional.of(rename[1] + rest);
            }
        }
        String help = "network.ike.komet.claude.help.";
        if (formerName.startsWith(help)) {
            String rest = formerName.substring(help.length());
            String simple = rest.contains("$") ? rest.substring(0, rest.indexOf('$')) : rest;
            if (HELP.contains(simple)) {
                return Optional.of("network.ike.komet.document.help." + rest);
            }
        }
        return Optional.empty();
    }
}

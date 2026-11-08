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

import dev.ikm.komet.preferences.KometPreferences;
import dev.ikm.komet.preferences.KometPreferencesImpl;
import network.ike.komet.document.surface.print.settings.DocumentSurfaceSettingKeys;
import network.ike.komet.document.surface.print.settings.DocumentTheme;
import network.ike.komet.document.surface.print.settings.FurnitureVisibility;
import network.ike.komet.document.surface.print.settings.MarginPreset;
import network.ike.komet.document.surface.print.settings.PageNumberPlacement;
import network.ike.komet.document.surface.print.settings.PageOrientation;
import network.ike.komet.document.surface.print.settings.PageSize;
import network.ike.komet.document.surface.print.settings.PrintSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A card or surface saved while the stamped document lived in komet-claude-plugin keeps what it
 * remembered (IKE-Network/ike-issues#1351): the keys are the ones Komet wrote on 2026-11-08 from an
 * image of that time, read back from its preferences files.
 */
class RenamedPreferenceKeysTest {

    /** The print settings key the settings enum gave while it was {@code network.ike.komet.claude.doc.print.settings}. */
    private static final String FORMER_PRINT_KEY =
            "s.DocumentSurfaceSettingKeys.PRINT_SETTINGS_10f3b915-ff44-328d-b96f-6e3b7d2a2b63";

    private KometPreferences node;

    @BeforeEach
    void freshNode() {
        node = KometPreferencesImpl.getConfigurationRootPreferences().node("renamed-preference-keys-" + UUID.randomUUID());
    }

    @AfterEach
    void removeNode() {
        try {
            node.removeNode();
        } catch (Exception neverWritten) {
            // a node never flushed has no folder to delete
        }
    }

    @Test
    void formerPrintKeyIsTheOneTheFormerEnumNameMakes() {
        UUID fromFormerName = UUID.nameUUIDFromBytes(
                "network.ike.komet.claude.doc.print.settings.DocumentSurfaceSettingKeys".getBytes(StandardCharsets.UTF_8));
        assertEquals(FORMER_PRINT_KEY.substring(FORMER_PRINT_KEY.lastIndexOf('_') + 1), fromFormerName.toString());
    }

    @Test
    void cardKeysMoveToTheirCurrentNames() {
        node.put("network.ike.komet.claude.stamp.document", "perrla.adoc");
        node.put("network.ike.komet.claude.stamp.view", "READING");
        node.put("network.ike.komet.claude.stamp.layer.TINTS", "true");
        node.put("dynamicCard.journalTopic", "c28d3514-e963-4bb5-9ee1-f70372a62836");

        RenamedPreferenceKeys.migrateCard(node);

        assertEquals("perrla.adoc", node.get("network.ike.komet.document.document", null));
        assertEquals("READING", node.get("network.ike.komet.document.view", null));
        assertEquals("true", node.get("network.ike.komet.document.layer.TINTS", null));
        assertNull(node.get("network.ike.komet.claude.stamp.document", null));
        assertNull(node.get("network.ike.komet.claude.stamp.view", null));
        assertEquals("c28d3514-e963-4bb5-9ee1-f70372a62836", node.get("dynamicCard.journalTopic", null));
    }

    @Test
    void aCurrentCardKeyWinsOverItsFormerName() {
        node.put("network.ike.komet.claude.stamp.view", "SOURCE");
        node.put("network.ike.komet.document.view", "GRAPH");

        RenamedPreferenceKeys.migrateCard(node);

        assertEquals("GRAPH", node.get("network.ike.komet.document.view", null));
        assertNull(node.get("network.ike.komet.claude.stamp.view", null));
    }

    @Test
    void printSettingsSavedUnderTheFormerNamesRestore() {
        PrintSettings chosen = new PrintSettings(PageSize.A4, PageOrientation.LANDSCAPE, MarginPreset.WIDE,
                DocumentTheme.DEFAULT, FurnitureVisibility.HIDDEN, PageNumberPlacement.FOOTER_RIGHT);
        node.putObject(DocumentSurfaceSettingKeys.PRINT_SETTINGS, chosen);
        String current = node.enumToGeneralKey(DocumentSurfaceSettingKeys.PRINT_SETTINGS);
        byte[] former = withClassName(node.getByteArray(current).orElseThrow(),
                "network.ike.komet.claude.doc.print.settings.PrintSettings");
        node.remove(current);
        node.putByteArray(FORMER_PRINT_KEY, former);

        RenamedPreferenceKeys.migrateSurfaceArea(node);

        assertArrayEquals(former, node.getByteArray(current).orElseThrow());
        assertNull(node.get(FORMER_PRINT_KEY, null));
        // the value names the former class, which DocumentRenames resolves
        assertEquals(chosen, node.getObject(DocumentSurfaceSettingKeys.PRINT_SETTINGS, PrintSettings.DEFAULT));
    }

    @Test
    void currentPrintSettingsStayPut() {
        node.putObject(DocumentSurfaceSettingKeys.PRINT_SETTINGS, PrintSettings.DEFAULT);
        String current = node.enumToGeneralKey(DocumentSurfaceSettingKeys.PRINT_SETTINGS);
        byte[] before = node.getByteArray(current).orElseThrow();

        RenamedPreferenceKeys.migrateSurfaceArea(node);

        assertArrayEquals(before, node.getByteArray(current).orElseThrow());
    }

    /**
     * The stored value as the former class wrote it: the same encoding version, its class name, then the
     * same fields (as in the saved {@code AAAACwAAADluZXR3b3Jr...}: version 11, a 57-byte name).
     */
    private static byte[] withClassName(byte[] stored, String className) {
        ByteBuffer in = ByteBuffer.wrap(stored);
        int version = in.getInt();
        int writtenLength = in.getInt();
        byte[] fields = Arrays.copyOfRange(stored, Integer.BYTES + Integer.BYTES + writtenLength, stored.length);
        byte[] name = className.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(Integer.BYTES + Integer.BYTES + name.length + fields.length)
                .putInt(version).putInt(name.length).put(name).put(fields).array();
    }
}

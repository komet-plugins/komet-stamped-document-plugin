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
import network.ike.komet.document.surface.print.settings.DocumentSurfaceSettingKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.prefs.BackingStoreException;

/**
 * Moves the preferences a card or area saved under the names of its former package to the names it
 * reads now (IKE-Network/ike-issues#1351). {@link DocumentRenames} lets a saved layout find the
 * moved classes; this keeps what those classes remembered. Two kinds of key carried the package:
 * <ul>
 *   <li>the STAMPed Document card's own keys, named {@code network.ike.komet.claude.stamp.view}
 *       and so on, now {@code network.ike.komet.document.view};</li>
 *   <li>the print settings of a document surface, whose key
 *       ({@link KometPreferences#enumToGeneralKey}) ends in a UUID made from the settings enum's
 *       class name, so the key changed when the enum moved.</li>
 * </ul>
 * Each moves once, on the first restore after the move: the value is copied as stored, and the
 * former key removed. A key the card already holds under its current name wins.
 */
public final class RenamedPreferenceKeys {

    private static final Logger LOG = LoggerFactory.getLogger(RenamedPreferenceKeys.class);

    /** The prefix of the card's keys while it lived in komet-claude-plugin. */
    static final String FORMER_CARD_PREFIX = "network.ike.komet.claude.stamp.";

    /** The prefix of the card's keys now. */
    static final String CARD_PREFIX = "network.ike.komet.document.";

    /** What every print settings key contains, under any class name: the enum and its constant. */
    private static final String PRINT_SETTINGS_KEY_PART = "." + DocumentSurfaceSettingKeys.class.getSimpleName()
            + "." + DocumentSurfaceSettingKeys.PRINT_SETTINGS.name() + "_";

    private RenamedPreferenceKeys() {
    }

    /**
     * Moves the STAMPed Document card's keys from their former names.
     *
     * @param card the card's preferences node
     */
    public static void migrateCard(KometPreferences card) {
        boolean moved = false;
        for (String key : keys(card)) {
            if (key.startsWith(FORMER_CARD_PREFIX)) {
                move(card, key, CARD_PREFIX + key.substring(FORMER_CARD_PREFIX.length()));
                moved = true;
            }
        }
        flushIf(moved, card);
    }

    /**
     * Moves a document surface's print settings from the key the former settings enum gave them.
     *
     * @param area the surface area's preferences node
     */
    public static void migrateSurfaceArea(KometPreferences area) {
        String current = area.enumToGeneralKey(DocumentSurfaceSettingKeys.PRINT_SETTINGS);
        boolean moved = false;
        for (String key : keys(area)) {
            if (!key.equals(current) && key.contains(PRINT_SETTINGS_KEY_PART)) {
                move(area, key, current);
                moved = true;
            }
        }
        flushIf(moved, area);
    }

    private static void move(KometPreferences node, String from, String to) {
        String value = node.get(from, null);
        if (value != null && node.get(to, null) == null) {
            node.put(to, value);
        }
        node.remove(from);
    }

    private static String[] keys(KometPreferences node) {
        try {
            return node.keys();
        } catch (BackingStoreException e) {
            LOG.warn("Could not list the keys of {} to move its former names", node, e);
            return new String[0];
        }
    }

    private static void flushIf(boolean moved, KometPreferences node) {
        if (!moved) {
            return;
        }
        try {
            node.flush();
        } catch (BackingStoreException e) {
            LOG.warn("Could not save {} after moving its former keys", node, e);
        }
    }
}

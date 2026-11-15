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

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A document card grows with its zoom, so the document keeps the room it had: in height, no
 * further than the journal shows, so the card's footer stays in view.
 */
class ZoomedCardHeightTest {

    @Test
    void theBodyKeepsItsRoomAtTheZoom() {
        assertEquals(900, StampedDocumentCard.heightAt(600, 1.5, 600, 2000), "half as tall again at one and a half");
        assertEquals(300, StampedDocumentCard.heightAt(600, 0.5, 600, 2000), "and half as tall at a half");
        assertEquals(600, StampedDocumentCard.heightAt(600, 1, 900, 2000), "its own height again when the zoom comes back to one");
    }

    @Test
    void theHeightIsHeldToWhatTheJournalShows() {
        assertEquals(700, StampedDocumentCard.heightAt(600, 1.5, 600, 700), "no taller than the journal shows");
        assertEquals(800, StampedDocumentCard.heightAt(600, 1.5, 800, 700), "a card already taller than that is left as tall as it is");
        assertEquals(900, StampedDocumentCard.heightAt(600, 1.5, 700, Double.MAX_VALUE), "a card in nothing that scrolls has no limit");
    }
}

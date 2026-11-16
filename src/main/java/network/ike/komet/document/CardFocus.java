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

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.input.MouseEvent;

/**
 * Keeps a card's keys with the card once it is pressed (IKE-Network/ike-issues#1383). A card's
 * shortcuts, Command-F among them, are a key filter on the card's root, so they are heard only
 * while the focus is inside the card. A press on a part of the card that takes no focus of its
 * own, as its tab, its toolbar's background or its footer, is not consumed there: it goes on up
 * to the journal's workspace, whose scroll pane takes the focus, and the next key goes to the
 * workspace instead of the card. So once a press inside the card has been handled, wherever it
 * went, the focus is brought into the card if it is not there already. A press on a control
 * that takes the focus itself, as a text or a tree, leaves it where that control put it.
 */
final class CardFocus {

    private CardFocus() { }

    /**
     * Brings the focus into a card after every press in it that left the focus outside.
     *
     * @param card the card's root, which takes the focus when nothing in it did
     */
    static void keepOnPress(Parent card) {
        card.addEventFilter(MouseEvent.MOUSE_PRESSED, e ->
                // after the press has gone everywhere it goes: the workspace takes the focus on its way back up
                Platform.runLater(() -> bringInto(card)));
    }

    /**
     * Gives a card the focus when its scene's focus is outside it.
     *
     * @param card the card's root
     */
    static void bringInto(Parent card) {
        Scene scene = card.getScene();
        if (scene != null && !holds(card, scene.getFocusOwner())) {
            card.requestFocus();
        }
    }

    /**
     * Whether a node is a card or stands inside it.
     *
     * @param card the card's root
     * @param node the node, or {@code null}
     * @return whether the node is the card or one of its descendants
     */
    static boolean holds(Parent card, Node node) {
        for (Node at = node; at != null; at = at.getParent()) {
            if (at == card) {
                return true;
            }
        }
        return false;
    }
}

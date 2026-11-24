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
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A card keeps its keys once it is pressed (IKE-Network/ike-issues#1383): the journal's workspace
 * is a scroll pane, which takes the focus on every press that reaches it, so a press on a part of
 * a card that takes no focus of its own, as its tab, left the focus with the workspace and the
 * card's Command-F unheard. Here the workspace is a real {@link ScrollPane}, the card a pane with
 * a label for its tab and a text field, as the stamped document card's root holds them.
 */
class CardFocusTest {

    private static Stage stage;

    @BeforeAll
    static void startJavaFx() {
        try {
            HeadlessFx.select();
            Platform.startup(() -> { });
        } catch (IllegalStateException alreadyStarted) {
            // the toolkit outlives a test class
        } catch (RuntimeException | Error noToolkit) {
            Assumptions.abort("no JavaFX toolkit here: " + noToolkit.getMessage());
        }
    }

    @AfterAll
    static void closeStage() throws Exception {
        onFx(() -> {
            if (stage != null) {
                stage.close();
            }
            return null;
        });
    }

    @Test
    void aPressAnywhereInACardLeavesTheFocusInTheCard() throws Exception {
        // Made on the JavaFX thread, as everything below is: the first control a JVM makes has
        // that thread load the default stylesheet, and a control made here meanwhile can wait
        // on it for ever, each thread holding a CSS class the other is initializing.
        Label tab = onFx(() -> new Label("IKE Document"));
        TextField text = onFx(() -> new TextField("words"));
        List<KeyCode> heard = new ArrayList<>();
        BorderPane card = onFx(() -> {
            BorderPane made = new BorderPane(text);
            made.setTop(tab);
            made.addEventFilter(KeyEvent.KEY_PRESSED, e -> heard.add(e.getCode()));
            return made;
        });
        ScrollPane workspace = onFx(() -> new ScrollPane(new StackPane(card)));
        Button elsewhere = onFx(() -> new Button("a journal's control"));
        onFx(() -> {
            stage = new Stage();
            stage.setScene(new Scene(new VBox(elsewhere, workspace), 400, 300));
            stage.show();
            elsewhere.requestFocus();
            return null;
        });
        Scene scene = onFx(card::getScene);

        // as it was: the press on the tab goes up to the workspace, which takes the focus
        press(tab);
        assertSame(workspace, onFx(scene::getFocusOwner), "the workspace takes the focus a tab cannot");
        key(scene);
        assertTrue(heard.isEmpty(), "and the card does not hear the key that follows");

        CardFocus.keepOnPress(card);
        onFx(() -> {
            elsewhere.requestFocus();
            return null;
        });
        press(tab);
        assertSame(card, onFx(scene::getFocusOwner), "once the press is handled, the focus is brought into the card");
        key(scene);
        assertEquals(List.of(KeyCode.F), heard, "where Command-F is heard");

        press(text);
        assertSame(text, onFx(scene::getFocusOwner), "a control that takes the focus itself keeps it");

        assertTrue(CardFocus.holds(card, text));
        assertTrue(CardFocus.holds(card, card));
        assertFalse(CardFocus.holds(card, workspace), "the workspace around the card is not in it");
        assertFalse(CardFocus.holds(card, null));
    }

    /** Presses the primary button on a node, as a click begins, and waits until what the press set going has run. */
    private static void press(Node node) throws Exception {
        onFx(() -> {
            Event.fireEvent(node, new MouseEvent(MouseEvent.MOUSE_PRESSED, 1, 1, 1, 1, MouseButton.PRIMARY, 1,
                    false, false, false, false, true, false, false, false, false, true, null));
            return null;
        });
        onFx(() -> null);                                        // the press's later work runs before this
    }

    /** Presses Command-F where the focus is. */
    private static void key(Scene scene) throws Exception {
        onFx(() -> {
            Node owner = scene.getFocusOwner();
            Event.fireEvent(owner, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F, false, false, false, true));
            return null;
        });
    }

    private static <T> T onFx(Callable<T> work) throws Exception {
        if (Platform.isFxApplicationThread()) {
            return work.call();
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(work.call());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result.get(30, TimeUnit.SECONDS);
    }
}

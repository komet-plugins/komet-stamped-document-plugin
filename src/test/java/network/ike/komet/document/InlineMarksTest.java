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

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The inline marks toggle on a selection, wrap an empty one, and answer to their keys. */
class InlineMarksTest {

    @Test
    void aSelectionIsWrappedAndStaysSelected() {
        InlineMarks.Edit edit = InlineMarks.BOLD.toggle("the regulator decides", 4, 13);
        assertEquals("the *regulator* decides", edit.text());
        assertEquals("regulator", edit.text().substring(edit.start(), edit.end()));
    }

    @Test
    void aMarkedSelectionIsUnwrapped() {
        InlineMarks.Edit again = InlineMarks.BOLD.toggle("the *regulator* decides", 5, 14);
        assertEquals("the regulator decides", again.text(), "the marks around the selection go");
        assertEquals(new InlineMarks.Edit("the regulator decides", 4, 13), again);
        InlineMarks.Edit whole = InlineMarks.ITALIC.toggle("see _this_ now", 4, 10);
        assertEquals("see this now", whole.text(), "a selection that holds its marks loses them");
        assertEquals("this", whole.text().substring(whole.start(), whole.end()));
    }

    @Test
    void nothingSelectedWritesThePairAndLeavesTheCaretBetween() {
        InlineMarks.Edit edit = InlineMarks.MONO.toggle("run ", 4, 4);
        assertEquals("run ``", edit.text());
        assertEquals(5, edit.start());
        assertEquals(5, edit.end());
        InlineMarks.Edit underline = InlineMarks.UNDERLINE.toggle("", 0, 0);
        assertEquals("[.underline]##", underline.text());
        assertEquals("[.underline]#".length(), underline.end());
    }

    @Test
    void theLongerMarksWrapAndUnwrapToo() {
        InlineMarks.Edit strike = InlineMarks.STRIKE.toggle("old words here", 4, 9);
        assertEquals("old [.line-through]#words# here", strike.text());
        assertEquals("words", strike.text().substring(strike.start(), strike.end()));
        InlineMarks.Edit back = InlineMarks.STRIKE.toggle(strike.text(), strike.start(), strike.end());
        assertEquals("old words here", back.text());
        InlineMarks.Edit light = InlineMarks.HIGHLIGHT.toggle("a key figure", 2, 12);
        assertEquals("a #key figure#", light.text());
    }

    @Test
    void aLinkTakesTheSelectionAsItsTextAndSelectsTheAddressToType() {
        InlineMarks.Edit edit = InlineMarks.LINK.toggle("read the handbook", 9, 17);
        assertEquals("read the https://[handbook]", edit.text());
        assertEquals("https://", edit.text().substring(edit.start(), edit.end()));
        InlineMarks.Edit address = InlineMarks.LINK.toggle("see https://example.org now", 4, 23);
        assertEquals("see https://example.org[] now", address.text(), "an address becomes the link, its text to be typed");
        assertEquals(address.start(), address.end());
        assertEquals('[', address.text().charAt(address.end() - 1));
    }

    @Test
    void aSelectionTurnedRoundIsTheSame() {
        assertEquals(InlineMarks.BOLD.toggle("abc def", 0, 3), InlineMarks.BOLD.toggle("abc def", 3, 0));
    }

    @Test
    void theKeysAreThePlatformsAndTheTypedCharactersWrap() {
        KeyEvent bold = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.B, false, false, false, true);
        assertEquals(Optional.of(InlineMarks.BOLD), InlineMarks.forKey(bold));
        KeyEvent mono = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.M, true, false, false, true);
        assertEquals(Optional.of(InlineMarks.MONO), InlineMarks.forKey(mono));
        KeyEvent plainM = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.M, false, false, false, true);
        assertEquals(Optional.empty(), InlineMarks.forKey(plainM), "monospace is shifted, so the plain key is not it");
        KeyEvent typing = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.B, false, false, false, false);
        assertEquals(Optional.empty(), InlineMarks.forKey(typing));
        assertEquals(Optional.of(InlineMarks.HIGHLIGHT), InlineMarks.forTyped("#"));
        assertEquals(Optional.empty(), InlineMarks.forTyped("x"));
        assertTrue(InlineMarks.STRIKE.keyText().contains("X"));
    }
}

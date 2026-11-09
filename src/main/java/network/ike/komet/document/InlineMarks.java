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

import java.util.Optional;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;

/**
 * The inline marks of AsciiDoc as an editor toggles them: bold, italic, underline, strike
 * through, monospace, highlight, and a link. Each has the key everyone knows for it, so the keys
 * need no learning, and each toggles: on a selection it wraps the text, or unwraps it when the
 * text is marked already; with nothing selected it writes the pair and leaves the caret between.
 * A block is edited as its source, so the marks are seen as marks and read as text on Save
 * (topic {@code dev-document-edit-menu}, settled 2026-11-07).
 *
 * <p>Pure over one line of text, so the rule is tested without a window; the editors apply the
 * edit to their own text and selection.
 */
public enum InlineMarks {
    BOLD("Bold", "*", "*", KeyCode.B, false),
    ITALIC("Italic", "_", "_", KeyCode.I, false),
    UNDERLINE("Underline", "[.underline]#", "#", KeyCode.U, false),
    STRIKE("Strike through", "[.line-through]#", "#", KeyCode.X, true),
    MONO("Monospace", "`", "`", KeyCode.M, true),
    HIGHLIGHT("Highlight", "#", "#", KeyCode.H, true),
    LINK("Link", "https://", "[]", KeyCode.K, false);

    /** The placeholder a link is written with, selected so typing replaces it. */
    static final String LINK_PLACEHOLDER = "https://";

    private final String label;
    private final String open;
    private final String close;
    private final KeyCode key;
    private final boolean shifted;

    InlineMarks(String label, String open, String close, KeyCode key, boolean shifted) {
        this.label = label;
        this.open = open;
        this.close = close;
        this.key = key;
        this.shifted = shifted;
    }

    /** @return the mark's name, for a button or a menu */
    public String label() {
        return label;
    }

    /** @return the mark's key, the platform's shortcut key with the letter, shifted for some */
    public KeyCombination key() {
        return shifted ? new javafx.scene.input.KeyCodeCombination(key, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN)
                : new javafx.scene.input.KeyCodeCombination(key, KeyCombination.SHORTCUT_DOWN);
    }

    /** @return the mark's key as a menu shows it */
    public String keyText() {
        return key().getDisplayText();
    }

    /** @return the mark a key press asks for, if any */
    public static Optional<InlineMarks> forKey(KeyEvent event) {
        for (InlineMarks mark : values()) {
            if (mark.key().match(event)) {
                return Optional.of(mark);
            }
        }
        return Optional.empty();
    }

    /**
     * The mark a character typed over a selection wraps it in: the single-character marks, as
     * code editors wrap a selection in the quote typed.
     */
    public static Optional<InlineMarks> forTyped(String character) {
        return switch (character) {
            case "*" -> Optional.of(BOLD);
            case "_" -> Optional.of(ITALIC);
            case "`" -> Optional.of(MONO);
            case "#" -> Optional.of(HIGHLIGHT);
            default -> Optional.empty();
        };
    }

    /**
     * An edit of one line of text.
     *
     * @param text  the line after the edit
     * @param start the selection's start after the edit
     * @param end   the selection's end after the edit, the caret
     */
    public record Edit(String text, int start, int end) {
    }

    /**
     * Toggles this mark on a selection of a line.
     *
     * @param text  the line
     * @param start the selection's start, an offset in the line
     * @param end   the selection's end, at least {@code start}
     * @return the edited line, and the selection after it
     */
    public Edit toggle(String text, int start, int end) {
        int from = Math.max(0, Math.min(start, end));
        int to = Math.min(text.length(), Math.max(start, end));
        String selected = text.substring(from, to);
        if (this == LINK) {
            return link(text, from, to, selected);
        }
        // marked around the selection: the marks go, the text stays selected
        if (from >= open.length() && text.startsWith(open, from - open.length()) && text.startsWith(close, to)) {
            String edited = text.substring(0, from - open.length()) + selected + text.substring(to + close.length());
            return new Edit(edited, from - open.length(), from - open.length() + selected.length());
        }
        // the selection is the marked text, marks included: they go
        if (selected.length() >= open.length() + close.length() && selected.startsWith(open) && selected.endsWith(close)) {
            String inner = selected.substring(open.length(), selected.length() - close.length());
            String edited = text.substring(0, from) + inner + text.substring(to);
            return new Edit(edited, from, from + inner.length());
        }
        String edited = text.substring(0, from) + open + selected + close + text.substring(to);
        return new Edit(edited, from + open.length(), from + open.length() + selected.length());
    }

    /**
     * A link: the selected text becomes the link's text, with the address placeholder selected
     * so that typing replaces it; a selection that is itself an address becomes the link, with
     * the caret in its empty text.
     */
    private static Edit link(String text, int from, int to, String selected) {
        if (selected.startsWith("http://") || selected.startsWith("https://")) {
            String edited = text.substring(0, from) + selected + "[]" + text.substring(to);
            int caret = from + selected.length() + 1;
            return new Edit(edited, caret, caret);
        }
        String edited = text.substring(0, from) + LINK_PLACEHOLDER + "[" + selected + "]" + text.substring(to);
        return new Edit(edited, from, from + LINK_PLACEHOLDER.length());
    }
}

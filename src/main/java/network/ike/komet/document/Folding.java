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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;
import java.util.function.ToIntFunction;

/**
 * What a reader has folded away in the Reading view: runs of the view's blocks, each hidden
 * behind one block that stays, the fold's anchor. A section folds at its heading, which stays
 * and carries the fold; any run of blocks a reader selects folds behind its first block, drawn
 * as a line that says what is folded. Folding is the reader's view of the document and not the
 * document: nothing here is written to it.
 *
 * <p>Blocks are known here by their index in the view's order. The view rebuilds its blocks
 * whenever the document changes, so folds are carried across a rebuild by keys the view derives
 * from the blocks' text, and remembered the same way. A key is one line of text, so a tab and a
 * line break are free to separate keys, and both survive in preferences kept as XML.
 */
final class Folding {

    /**
     * The separator between a run fold's two keys: a tab, which a heading or a line of text does
     * not hold, and which the preferences, kept as XML, can hold where a control character cannot.
     */
    static final String RUN_SEPARATOR = "\t";

    /**
     * One fold: the blocks {@code from} to {@code to} are hidden, but for the anchor, which stays
     * and shows what is folded. A section's anchor is its heading, before {@code from}; a run's
     * anchor is its first block, {@code from} itself.
     *
     * @param anchor the block that stays
     * @param from   the first block folded
     * @param to     the last block folded
     */
    record Fold(int anchor, int from, int to) {

        /** Whether the fold is a section's, carried by its heading. */
        boolean atHeading() {
            return anchor < from;
        }

        /** Whether a block is hidden by this fold. */
        boolean hides(int index) {
            return index >= from && index <= to && index != anchor;
        }

        /** The first block the fold touches: its anchor or its first hidden block. */
        int first() {
            return Math.min(anchor, from);
        }
    }

    private final List<Fold> folds = new ArrayList<>();

    /** @return every fold, in the order of the blocks */
    List<Fold> all() {
        folds.sort(java.util.Comparator.comparingInt(Fold::first));
        return List.copyOf(folds);
    }

    boolean isEmpty() {
        return folds.isEmpty();
    }

    /** @return whether a block is hidden by any fold */
    boolean hidden(int index) {
        return folds.stream().anyMatch(fold -> fold.hides(index));
    }

    /** @return the fold a block anchors, if it anchors one */
    Optional<Fold> at(int anchor) {
        return folds.stream().filter(fold -> fold.anchor == anchor).findFirst();
    }

    /** @return the fold hiding a block, if one does */
    Optional<Fold> hiding(int index) {
        return folds.stream().filter(fold -> fold.hides(index)).findFirst();
    }

    /**
     * The last block of a heading's section: the block before the next heading of the same or a
     * higher level, or the last block of the view.
     *
     * @param levelAt the heading level of a block, or a negative number for a block that is no heading
     * @param count   how many blocks the view has
     * @param heading the heading's block
     * @return the section's last block; the heading itself when the section has nothing in it
     */
    static int sectionEnd(IntUnaryOperator levelAt, int count, int heading) {
        int level = levelAt.applyAsInt(heading);
        for (int i = heading + 1; i < count; i++) {
            int at = levelAt.applyAsInt(i);
            if (at >= 0 && at <= level) {
                return i - 1;
            }
        }
        return count - 1;
    }

    /**
     * Folds a section at its heading.
     *
     * @param heading the heading's block
     * @param end     the section's last block, {@link #sectionEnd}
     * @return the fold, or empty when the section has nothing to fold
     */
    Optional<Fold> foldSection(int heading, int end) {
        if (end <= heading) {
            return Optional.empty();
        }
        return Optional.of(add(new Fold(heading, heading + 1, end)));
    }

    /**
     * Folds a run of blocks behind its first.
     *
     * @param from the first block
     * @param to   the last block
     * @return the fold
     */
    Fold foldRun(int from, int to) {
        return add(new Fold(Math.min(from, to), Math.min(from, to), Math.max(from, to)));
    }

    /** A new fold takes the place of every fold it touches: folds never nest or overlap. */
    private Fold add(Fold fold) {
        folds.removeIf(existing -> existing.to >= fold.first() && existing.first() <= fold.to);
        folds.add(fold);
        return fold;
    }

    /**
     * Opens the fold a block anchors.
     *
     * @return whether there was one
     */
    boolean unfold(int anchor) {
        return folds.removeIf(fold -> fold.anchor == anchor);
    }

    /** Opens every fold hiding a block, so the block can be seen. */
    boolean reveal(int index) {
        return folds.removeIf(fold -> fold.hides(index));
    }

    void clear() {
        folds.clear();
    }

    /**
     * The folds as keys the view can find its blocks by again: a section fold by its heading's
     * key, a run fold by the keys of its first and last block.
     *
     * @param keyOf a block's key, from its text; a block with no key is {@code null} or empty
     * @return the keys, one per fold, folds with no key left out
     */
    List<String> keys(IntFunction<String> keyOf) {
        List<String> keys = new ArrayList<>();
        for (Fold fold : all()) {
            String first = keyOf.apply(fold.atHeading() ? fold.anchor : fold.from);
            String last = fold.atHeading() ? first : keyOf.apply(fold.to);
            if (first == null || first.isEmpty() || last == null || last.isEmpty()) {
                continue;
            }
            keys.add(fold.atHeading() ? first : first + RUN_SEPARATOR + last);
        }
        return keys;
    }

    /**
     * Folds the blocks the keys name, where the view still has them.
     *
     * @param keys    keys from {@link #keys}
     * @param indexOf the block a key names, or a negative number for none
     * @param levelAt the heading level of a block, or a negative number for a block that is no heading
     * @param count   how many blocks the view has
     */
    void restore(List<String> keys, ToIntFunction<String> indexOf, IntUnaryOperator levelAt, int count) {
        for (String key : keys) {
            int separator = key.indexOf(RUN_SEPARATOR);
            if (separator < 0) {
                int heading = indexOf.applyAsInt(key);
                if (heading >= 0 && levelAt.applyAsInt(heading) >= 0) {
                    foldSection(heading, sectionEnd(levelAt, count, heading));
                }
            } else {
                int from = indexOf.applyAsInt(key.substring(0, separator));
                int to = indexOf.applyAsInt(key.substring(separator + RUN_SEPARATOR.length()));
                if (from >= 0 && to >= from) {
                    foldRun(from, to);
                }
            }
        }
    }
}

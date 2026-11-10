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
package network.ike.komet.document.print;

import network.ike.komet.document.print.LayoutOracle.Layout;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The pages a document's front lists print beside their entries, and how a layout settles on
 * them. A measured source prints each entry's page as a literal number, which must be the page
 * the layout it measures puts the target on — a number of another width could move a line, so
 * the layout is asked again with the pages it found until no entry's page changes, which takes
 * two passes as a rule: one to learn the pages, one to confirm them. The printed document asks
 * Typst's page counter instead ({@link TypstWriter}), so the two agree once settled.
 */
public final class FrontLists {

    /** How many passes a layout may take to settle before the last one is taken as it stands. */
    public static final int PASSES = 4;

    private FrontLists() { }

    /**
     * One pass of a layout oracle over the document with the entries' pages filled in.
     */
    @FunctionalInterface
    public interface Pass {
        /**
         * @param numbered the model, its entries carrying the pages the pass before found
         * @return the oracle's answer; a layout of no lines when it did not answer
         * @throws IOException if the source could not be written
         */
        Layout layout(DocumentModel numbered) throws IOException;
    }

    /**
     * What the passes settled on.
     *
     * @param model  the model as last measured, its entries carrying the pages measured
     * @param layout the last pass's layout
     * @param passes how many passes were taken
     * @param pages  the page each target begins on in that layout, by key
     */
    public record Settled(DocumentModel model, Layout layout, int passes, Map<String, Integer> pages) { }

    /**
     * The page each entry's target begins on in a layout.
     *
     * @param layout      the layout
     * @param targetUnits the unit each target begins with, by the target's key
     * @return the 1-based page of each target in the order given; 0 for a target whose unit has no line
     */
    public static Map<String, Integer> pages(Layout layout, Map<String, Integer> targetUnits) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> target : targetUnits.entrySet()) {
            int page = layout.pageOf(target.getValue());
            out.put(target.getKey(), page < 0 ? 0 : page + 1);
        }
        return out;
    }

    /**
     * Repeats a pass until no entry's page changes, or {@link #PASSES} have been taken, or the
     * oracle gives no answer. A document with no front list takes one pass.
     *
     * @param model       the model, its entries' pages as they stand (usually unknown)
     * @param first       the pages to print on the first pass: an estimate, such as a greedy layout's
     * @param targetUnits the unit each target begins with, by the target's key
     * @param pass        the oracle's pass
     * @return what was settled on
     * @throws IOException if a pass could not write its source
     */
    public static Settled settle(DocumentModel model, Map<String, Integer> first, Map<String, Integer> targetUnits, Pass pass) throws IOException {
        Objects.requireNonNull(model, "model is null");
        Map<String, Integer> numbers = first;
        int passes = 0;
        while (true) {
            DocumentModel numbered = model.withEntryPages(numbers);
            Layout layout = pass.layout(numbered);
            passes++;
            Map<String, Integer> found = pages(layout, targetUnits);
            if (layout.lines().isEmpty() || found.equals(numbers) || passes >= PASSES) {
                return new Settled(numbered, layout, passes, found);
            }
            numbers = found;
        }
    }
}

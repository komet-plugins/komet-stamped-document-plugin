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
package network.ike.komet.document.surface.print.settings;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The white space of a title page (IKE-Network/ike-issues#1351): between the title and the
 * author beneath it, between the author and the date, and between those and the abstract.
 *
 * <p>AsciiDoc keeps spacing out of the document: a header has no blocks, and no blank line or
 * break in it sets anything apart. The converter's theme has it. Asciidoctor PDF's theme names
 * the margins of the title page's parts, {@code title-page: authors: margin-top} and
 * {@code revision: margin-top} among them, and the values here are named for those, so a person
 * who knows that theme finds them where they look. The space above the abstract has no name
 * there, since that converter sets no abstract on its title page; it is named in the same way.
 *
 * <p>A print theme gives the three ({@link DocumentTheme#titleSpacing}), and a document may ask
 * for others in its header, with attributes of the theme's names written flat:
 * {@code :title-page-authors-margin-top: 2.5em}. Another converter passes over an attribute it
 * does not know, so the document reads the same there.
 *
 * @param authors       the space above the author line, from the title's last baseline; also above the date when
 *                      there is no author; {@code null} for a document that asks for none of its own
 * @param revision      the space above the date line, from the author line's last baseline; {@code null} when not asked
 * @param abstractAbove the space above the abstract, from the last line of the title's block; {@code null} when not asked
 */
public record TitleSpacing(Measure authors, Measure revision, Measure abstractAbove) {

    /** The attribute a document sets the space above its author line with, Asciidoctor PDF's theme key written flat. */
    public static final String AUTHORS = "title-page-authors-margin-top";
    /** The attribute for the space above the date line. */
    public static final String REVISION = "title-page-revision-margin-top";
    /** The attribute for the space above the abstract: this converter's own, named as the theme's are. */
    public static final String ABSTRACT = "title-page-abstract-margin-top";

    /** What a document asks for when it asks for nothing: every space the theme's. */
    public static final TitleSpacing NONE = new TitleSpacing(null, null, null);

    /**
     * The spacing every print theme has until one differs: the author two ems under the title,
     * the date close beneath the author since the two read as one, and three ems of white before
     * the abstract, so the title's block stands apart from the text under it.
     */
    public static final TitleSpacing STANDARD = new TitleSpacing(Measure.ems(2), Measure.ems(0.5), Measure.ems(3));

    private static final Pattern MEASURE = Pattern.compile("^(-?[0-9]*\\.?[0-9]+)\\s*(em|pt|in|mm|cm|pc|px)?$");

    /**
     * A length: so many ems of the body's size, or so many points.
     *
     * @param value the number
     * @param em    whether it counts ems of the body's size rather than points
     */
    public record Measure(double value, boolean em) {

        /** @return a length in ems of the body's size */
        public static Measure ems(double value) {
            return new Measure(value, true);
        }

        /** @return a length in points */
        public static Measure points(double value) {
            return new Measure(value, false);
        }

        /**
         * A length as a theme or an attribute writes it: a number of points, or a number with its
         * unit, {@code em}, {@code pt}, {@code in}, {@code mm}, {@code cm}, {@code pc} or {@code px}.
         *
         * @param written the value
         * @return the length, or empty when the value is not one, or is less than nothing
         */
        public static Optional<Measure> of(String written) {
            Matcher m = MEASURE.matcher(written == null ? "" : written.strip().toLowerCase(Locale.ROOT));
            if (!m.matches()) {
                return Optional.empty();
            }
            double value = Double.parseDouble(m.group(1));
            if (value < 0) {
                return Optional.empty();
            }
            return Optional.of(switch (m.group(2) == null ? "pt" : m.group(2)) {
                case "em" -> ems(value);
                case "in" -> points(value * 72);
                case "mm" -> points(value * 72 / 25.4);
                case "cm" -> points(value * 72 / 2.54);
                case "pc" -> points(value * 12);
                case "px" -> points(value * 0.75);
                default -> points(value);
            });
        }

        /**
         * The length in points.
         *
         * @param base the body's size in points
         * @return the length
         */
        public double in(double base) {
            return em ? value * base : value;
        }

        /** @return the length as Typst writes it, {@code 2em} or {@code 24pt} */
        public String typst() {
            String number = value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
            return number + (em ? "em" : "pt");
        }
    }

    /**
     * What a document asks for in its header.
     *
     * @param attribute the document's attributes by name, as they stand after its header
     * @return the spaces it asks for; a space it does not name, or names with what is no length, is left to the theme
     */
    public static TitleSpacing asked(Function<String, Optional<String>> attribute) {
        Measure authors = attribute.apply(AUTHORS).flatMap(Measure::of).orElse(null);
        Measure revision = attribute.apply(REVISION).flatMap(Measure::of).orElse(null);
        Measure above = attribute.apply(ABSTRACT).flatMap(Measure::of).orElse(null);
        return authors == null && revision == null && above == null ? NONE : new TitleSpacing(authors, revision, above);
    }

    /**
     * These spaces with a theme's wherever none is asked for.
     *
     * @param theme the theme's spacing, every space given
     * @return the spacing a page is set with
     */
    public TitleSpacing over(TitleSpacing theme) {
        return new TitleSpacing(authors == null ? theme.authors : authors, revision == null ? theme.revision : revision,
                abstractAbove == null ? theme.abstractAbove : abstractAbove);
    }
}

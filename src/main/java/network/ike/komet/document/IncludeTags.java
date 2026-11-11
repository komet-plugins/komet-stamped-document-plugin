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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The tagged regions an {@code include::} directive asks for, as AsciiDoc defines them
 * (ike-lab-documents topic {@code dev-document-front-matter}, "Repeating the abstract at the
 * start of the body"): {@code include::summary.adoc[tag=brief]} shows the lines of
 * {@code summary.adoc} that stand between its {@code tag::brief[]} and {@code end::brief[]}
 * lines, and {@code tags=a;b} shows several regions.
 *
 * <p>The rules are Asciidoctor's, and {@link #select} was checked line for line against what
 * Asciidoctor 2.0.26 includes for each form:
 *
 * <ul>
 *   <li>A tag line is any line that holds {@code tag::name[]} or {@code end::name[]} after a
 *       word boundary and before a space or the end of the line. It is usually written behind a
 *       line comment, {@code // tag::name[]}.</li>
 *   <li>When an include names tags, no tag line is ever shown, whichever region it belongs
 *       to. When an include names none, the whole file is shown, and a tag line behind a comment
 *       is an ordinary comment line.</li>
 *   <li>{@code tag=} names one tag. {@code tags=} names several, separated by semicolons, or by
 *       commas when the value is quoted. A name with {@code !} before it leaves that region
 *       out.</li>
 *   <li>{@code *} stands for every tagged region and {@code **} for every line; either may be
 *       negated. An include that names only regions to leave out starts from every line.</li>
 *   <li>A region inside a selected region is shown with it, unless it is named to be left
 *       out.</li>
 *   <li>{@code lines=} is not read here. Asciidoctor gives it precedence over tags, so an
 *       include that has it names no tags.</li>
 * </ul>
 */
public final class IncludeTags {

    /** Asciidoctor's own pattern for a tag line: the directive after a word boundary, before a space or the line's end. */
    private static final Pattern DIRECTIVE = Pattern.compile("\\b(?:tag|(e)nd)::(\\S+?)\\[\\](?=$|[ \\r])");
    /** One attribute of an include's list: a name, then a value that may be quoted. */
    private static final Pattern ATTRIBUTE = Pattern.compile("\\s*([A-Za-z0-9_-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^,]*))\\s*(?:,|$)");

    /**
     * What an include shows of a file.
     *
     * @param shown   the indexes of the file's lines it shows, from 0, in order
     * @param missing the tags it names to be shown that the file never opens
     */
    public record Region(List<Integer> shown, List<String> missing) { }

    /** Each tag named, in the order written: true to show its region, false to leave it out. */
    private final Map<String, Boolean> tags;
    private final String written;

    private IncludeTags(Map<String, Boolean> tags, String written) {
        this.tags = tags;
        this.written = written;
    }

    /**
     * The tags an include's attribute list names.
     *
     * @param parameters the list as written between the directive's brackets, {@code tag=brief} or
     *                   {@code tags=a;b,leveloffset=+1}; may be null
     * @return the tags, or empty when the include names none and shows the whole file
     */
    public static Optional<IncludeTags> of(String parameters) {
        if (parameters == null || parameters.isBlank()) {
            return Optional.empty();
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        Matcher m = ATTRIBUTE.matcher(parameters);
        while (m.find()) {
            String value = m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : m.group(4);
            attributes.putIfAbsent(m.group(1), value == null ? "" : value.strip());
        }
        if (attributes.containsKey("lines")) {
            return Optional.empty();
        }
        Map<String, Boolean> tags = new LinkedHashMap<>();
        String written;
        if (attributes.containsKey("tag")) {
            String tag = attributes.get("tag");
            written = "tag=" + tag;
            name(tag, tags);
        } else if (attributes.containsKey("tags")) {
            String list = attributes.get("tags");
            written = "tags=" + list;
            for (String tag : list.split(list.contains(",") ? "," : ";")) {
                name(tag.strip(), tags);
            }
        } else {
            return Optional.empty();
        }
        return tags.isEmpty() ? Optional.empty() : Optional.of(new IncludeTags(tags, written));
    }

    private static void name(String tag, Map<String, Boolean> tags) {
        if (tag.isEmpty() || tag.equals("!")) {
            return;
        }
        if (tag.startsWith("!")) {
            tags.put(tag.substring(1), false);
        } else {
            tags.put(tag, true);
        }
    }

    /** @return the attribute as the include wrote it, {@code tag=brief}, for a note or a label */
    public String written() {
        return written;
    }

    /**
     * Whether a line is a tag line.
     *
     * @param line the line's text
     * @return whether it holds a {@code tag::name[]} or an {@code end::name[]} directive
     */
    public static boolean directive(String line) {
        return line != null && line.contains("::") && line.contains("[]") && DIRECTIVE.matcher(line).find();
    }

    /**
     * The lines of a file these tags show.
     *
     * @param lines the file's lines, in order
     * @return the lines shown, and the tags that were asked for and not found
     */
    public Region select(List<String> lines) {
        Map<String, Boolean> named = new LinkedHashMap<>(tags);
        boolean select;
        Boolean wildcard = null;
        if (named.containsKey("**")) {
            select = named.remove("**");
            if (named.containsKey("*")) {
                wildcard = named.remove("*");
            } else if (!select && !named.isEmpty() && !named.values().iterator().next()) {
                wildcard = true;
            }
        } else if (named.containsKey("*")) {
            if (named.keySet().iterator().next().equals("*")) {
                wildcard = named.remove("*");
                select = !wildcard;
            } else {
                select = false;
                wildcard = named.remove("*");
            }
        } else {
            select = !named.containsValue(true);
        }
        boolean base = select;
        Deque<Object[]> open = new ArrayDeque<>();                  // {tag, whether its lines are shown}, innermost last
        String active = null;
        Set<String> found = new LinkedHashSet<>();
        List<Integer> shown = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher m = line.contains("::") && line.contains("[]") ? DIRECTIVE.matcher(line) : null;
            if (m != null && m.find()) {
                String tag = m.group(2);
                if (m.group(1) != null) {
                    if (tag.equals(active)) {
                        open.removeLast();
                        active = open.isEmpty() ? null : (String) open.getLast()[0];
                        select = open.isEmpty() ? base : (Boolean) open.getLast()[1];
                    } else if (named.containsKey(tag)) {
                        // an end that is not the innermost open tag's: that tag is closed where it stands, as Asciidoctor closes it
                        Object[] mismatched = null;
                        for (java.util.Iterator<Object[]> it = open.descendingIterator(); it.hasNext() && mismatched == null; ) {
                            Object[] candidate = it.next();
                            if (tag.equals(candidate[0])) {
                                mismatched = candidate;
                            }
                        }
                        if (mismatched != null) {
                            open.removeLastOccurrence(mismatched);
                        }
                    }
                } else if (named.containsKey(tag)) {
                    select = named.get(tag);
                    if (select) {
                        found.add(tag);
                    }
                    active = tag;
                    open.addLast(new Object[] {tag, select});
                } else if (wildcard != null) {
                    select = active != null && !select ? false : wildcard;
                    active = tag;
                    open.addLast(new Object[] {tag, select});
                }
            } else if (select) {
                shown.add(i);
            }
        }
        List<String> missing = named.entrySet().stream().filter(e -> e.getValue() && !found.contains(e.getKey())).map(Map.Entry::getKey).toList();
        return new Region(List.copyOf(shown), missing);
    }
}

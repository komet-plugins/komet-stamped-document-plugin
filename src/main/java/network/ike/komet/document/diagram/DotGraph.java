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
package network.ike.komet.document.diagram;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A box-and-arrow diagram read from the DOT a {@code [graphviz]} block holds: the part of the
 * language the documents use. A graph is directed or not and runs top to bottom or left to
 * right; a node has a label of one or more lines, a fill, and may be dashed; an edge joins two
 * nodes, may carry a label, and may be dashed; nodes may be held on one rank; a subgraph whose
 * name begins {@code cluster} draws a box around its members, with its label. Defaults set with
 * {@code node [...]} and {@code edge [...]} apply to what follows. Anything else in the text,
 * fonts, sizes, separations, shapes, is read and set aside: the diagram is drawn in the
 * document's own face and measures.
 *
 * @param directed   whether edges have a direction
 * @param leftToRight whether ranks run left to right rather than top to bottom
 * @param nodes      the nodes, in the order they were first named
 * @param edges      the edges, in order
 * @param sameRanks  the groups of node ids held on one rank
 * @param clusters   the clusters, in order
 */
public record DotGraph(boolean directed, boolean leftToRight, List<Node> nodes, List<Edge> edges, List<List<String>> sameRanks,
                       List<Cluster> clusters) {

    /**
     * A node.
     *
     * @param id     its name in the text
     * @param lines  its label, one or more lines; the name when it has no label
     * @param fill   its fill colour as CSS hex, or {@code null} for the default
     * @param dashed whether its outline is dashed
     */
    public record Node(String id, List<String> lines, String fill, boolean dashed) {
    }

    /**
     * An edge.
     *
     * @param from   the node it leaves
     * @param to     the node it reaches
     * @param lines  its label's lines, none for an unlabelled edge
     * @param dashed whether it is dashed
     */
    public record Edge(String from, String to, List<String> lines, boolean dashed) {
    }

    /**
     * A cluster: a box drawn around some nodes.
     *
     * @param label   its label, or empty
     * @param members the ids of the nodes in it
     */
    public record Cluster(String label, List<String> members) {
    }

    /** @return the node with an id, when the graph has one */
    public Optional<Node> node(String id) {
        return nodes.stream().filter(n -> n.id().equals(id)).findFirst();
    }

    /**
     * Reads a graph.
     *
     * @param text the DOT text
     * @return the graph
     * @throws IllegalArgumentException when the text is not a graph this reader knows
     */
    public static DotGraph parse(String text) {
        return new Reader(text).graph();
    }

    /** A reader over the text, one token at a time. */
    private static final class Reader {
        private final String text;
        private int at;
        private boolean directed = true;
        private boolean leftToRight;
        private final Map<String, Node> nodes = new LinkedHashMap<>();
        private final List<Edge> edges = new ArrayList<>();
        private final List<List<String>> sameRanks = new ArrayList<>();
        private final List<Cluster> clusters = new ArrayList<>();
        /** The attributes {@code node [...]} and {@code edge [...]} set for what follows. */
        private final Map<String, String> nodeDefaults = new LinkedHashMap<>();
        private final Map<String, String> edgeDefaults = new LinkedHashMap<>();

        Reader(String text) {
            this.text = stripComments(text == null ? "" : text);
        }

        DotGraph graph() {
            skipSpace();
            if (take("strict")) {
                skipSpace();
            }
            if (take("digraph")) {
                directed = true;
            } else if (take("graph")) {
                directed = false;
            } else {
                throw new IllegalArgumentException("The text does not begin with graph or digraph.");
            }
            skipSpace();
            if (peek() != '{') {
                identifier();                                            // the graph's name
                skipSpace();
            }
            expect('{');
            statements(null);
            expect('}');
            return new DotGraph(directed, leftToRight, List.copyOf(nodes.values()), List.copyOf(edges), List.copyOf(sameRanks),
                    List.copyOf(clusters));
        }

        /** Reads statements up to the closing brace; nodes named are added to the cluster being read, when one is. */
        private void statements(List<String> cluster) {
            while (true) {
                skipSpace();
                char c = peek();
                if (c == '}' || c == 0) {
                    return;
                }
                if (c == ';' || c == ',') {
                    at++;
                    continue;
                }
                if (c == '{') {
                    at++;
                    anonymousSubgraph(cluster);
                    expect('}');
                    continue;
                }
                String word = identifier();
                skipSpace();
                if (word.equals("subgraph")) {
                    String name = peek() == '{' ? "" : identifier();
                    skipSpace();
                    expect('{');
                    if (name.startsWith("cluster")) {
                        List<String> members = new ArrayList<>();
                        String label = clusterStatements(members);
                        clusters.add(new Cluster(label, List.copyOf(members)));
                        if (cluster != null) {
                            cluster.addAll(members);
                        }
                    } else {
                        anonymousSubgraph(cluster);
                    }
                    expect('}');
                    continue;
                }
                if (word.equals("node") && peek() == '[') {
                    nodeDefaults.putAll(attributes());
                    continue;
                }
                if (word.equals("edge") && peek() == '[') {
                    edgeDefaults.putAll(attributes());
                    continue;
                }
                if (word.equals("graph") && peek() == '[') {
                    graphAttributes(attributes());
                    continue;
                }
                if (peek() == '=') {
                    at++;
                    skipSpace();
                    String value = identifier();
                    graphAttributes(Map.of(word, value));
                    continue;
                }
                List<String> chain = new ArrayList<>();
                chain.add(word);
                while (takeEdgeOperator()) {
                    skipSpace();
                    chain.add(identifier());
                    skipSpace();
                }
                Map<String, String> attributes = peek() == '[' ? attributes() : Map.of();
                if (chain.size() == 1) {
                    nodeStatement(word, attributes, cluster);
                } else {
                    for (String id : chain) {
                        declare(id, cluster);
                    }
                    for (int i = 0; i + 1 < chain.size(); i++) {
                        edges.add(new Edge(chain.get(i), chain.get(i + 1), lines(attributes.get("label")), dashed(attributes, edgeDefaults)));
                    }
                }
            }
        }

        /** An unnamed subgraph, {@code {rank=same; a; b;}}: its nodes, held on one rank when it says so. */
        private void anonymousSubgraph(List<String> cluster) {
            List<String> named = new ArrayList<>();
            boolean same = false;
            while (true) {
                skipSpace();
                char c = peek();
                if (c == '}' || c == 0) {
                    break;
                }
                if (c == ';' || c == ',') {
                    at++;
                    continue;
                }
                String word = identifier();
                skipSpace();
                if (peek() == '=') {
                    at++;
                    skipSpace();
                    String value = identifier();
                    if (word.equals("rank") && value.equals("same")) {
                        same = true;
                    }
                    continue;
                }
                List<String> chain = new ArrayList<>();
                chain.add(word);
                while (takeEdgeOperator()) {
                    skipSpace();
                    chain.add(identifier());
                    skipSpace();
                }
                Map<String, String> attributes = peek() == '[' ? attributes() : Map.of();
                if (chain.size() == 1) {
                    nodeStatement(word, attributes, cluster);
                    named.add(word);
                } else {
                    for (String id : chain) {
                        declare(id, cluster);
                        named.add(id);
                    }
                    for (int i = 0; i + 1 < chain.size(); i++) {
                        edges.add(new Edge(chain.get(i), chain.get(i + 1), lines(attributes.get("label")), dashed(attributes, edgeDefaults)));
                    }
                }
            }
            if (same && named.size() > 1) {
                sameRanks.add(List.copyOf(new java.util.LinkedHashSet<>(named)));
            }
        }

        /** A cluster's statements: its members, and its label. */
        private String clusterStatements(List<String> members) {
            String label = "";
            while (true) {
                skipSpace();
                char c = peek();
                if (c == '}' || c == 0) {
                    return label;
                }
                if (c == ';' || c == ',') {
                    at++;
                    continue;
                }
                if (c == '{') {
                    at++;
                    anonymousSubgraph(members);
                    expect('}');
                    continue;
                }
                String word = identifier();
                skipSpace();
                if (peek() == '=') {
                    at++;
                    skipSpace();
                    String value = identifier();
                    if (word.equals("label")) {
                        label = value;
                    }
                    continue;
                }
                if ((word.equals("node") || word.equals("edge") || word.equals("graph")) && peek() == '[') {
                    Map<String, String> set = attributes();
                    if (word.equals("node")) {
                        nodeDefaults.putAll(set);
                    } else if (word.equals("edge")) {
                        edgeDefaults.putAll(set);
                    } else if (set.containsKey("label")) {
                        label = set.get("label");
                    }
                    continue;
                }
                List<String> chain = new ArrayList<>();
                chain.add(word);
                while (takeEdgeOperator()) {
                    skipSpace();
                    chain.add(identifier());
                    skipSpace();
                }
                Map<String, String> attributes = peek() == '[' ? attributes() : Map.of();
                if (chain.size() == 1) {
                    nodeStatement(word, attributes, members);
                } else {
                    for (String id : chain) {
                        declare(id, members);
                    }
                    for (int i = 0; i + 1 < chain.size(); i++) {
                        edges.add(new Edge(chain.get(i), chain.get(i + 1), lines(attributes.get("label")), dashed(attributes, edgeDefaults)));
                    }
                }
            }
        }

        private void graphAttributes(Map<String, String> set) {
            String direction = set.get("rankdir");
            if (direction != null) {
                leftToRight = direction.equalsIgnoreCase("LR") || direction.equalsIgnoreCase("RL");
            }
        }

        /**
         * A node named with attributes: its label, fill and style, over the defaults. A node named
         * again keeps what it had, but for what the new attributes say.
         */
        private void nodeStatement(String id, Map<String, String> attributes, List<String> cluster) {
            Node was = nodes.get(id);
            Map<String, String> all = new LinkedHashMap<>(nodeDefaults);
            all.putAll(attributes);
            String label = all.get("label");
            Node node;
            if (was == null) {
                node = new Node(id, lines(label == null ? id : label), fill(all), dashed(all, Map.of()));
            } else {
                boolean styled = attributes.containsKey("style") || attributes.containsKey("fillcolor") || attributes.containsKey("color");
                node = new Node(id, attributes.containsKey("label") ? lines(attributes.get("label")) : was.lines(),
                        styled ? fill(all) : was.fill(), styled ? dashed(all, Map.of()) : was.dashed());
            }
            nodes.put(id, node);
            if (cluster != null && !cluster.contains(id)) {
                cluster.add(id);
            }
        }

        /** A node named in an edge or a group: declared with the defaults when it is new. */
        private void declare(String id, List<String> cluster) {
            if (!nodes.containsKey(id)) {
                nodeStatement(id, Map.of(), cluster);
            } else if (cluster != null && !cluster.contains(id)) {
                cluster.add(id);
            }
        }

        private static String fill(Map<String, String> attributes) {
            String style = attributes.getOrDefault("style", "");
            String fill = attributes.get("fillcolor");
            if (fill == null && style.contains("filled")) {
                fill = attributes.get("color");
            }
            if (fill == null || !style.contains("filled")) {
                return null;
            }
            return fill.startsWith("#") ? fill : null;
        }

        private static boolean dashed(Map<String, String> attributes, Map<String, String> defaults) {
            String style = attributes.getOrDefault("style", defaults.getOrDefault("style", ""));
            return style.contains("dashed") || style.contains("dotted");
        }

        /** A label's lines: split at the line breaks DOT writes as {@code \n}, {@code \l} or {@code \r}. */
        static List<String> lines(String label) {
            if (label == null) {
                return List.of();
            }
            List<String> lines = new ArrayList<>();
            for (String line : label.split("\\\\[nlr]")) {
                lines.add(line.strip());
            }
            while (lines.size() > 1 && lines.getLast().isEmpty()) {
                lines.removeLast();
            }
            return List.copyOf(lines);
        }

        // ── tokens ──────────────────────────────────────────────────────

        private Map<String, String> attributes() {
            Map<String, String> set = new LinkedHashMap<>();
            expect('[');
            while (true) {
                skipSpace();
                char c = peek();
                if (c == ']') {
                    at++;
                    skipSpace();
                    return set;
                }
                if (c == 0) {
                    throw new IllegalArgumentException("An attribute list is not closed.");
                }
                if (c == ',' || c == ';') {
                    at++;
                    continue;
                }
                String name = identifier();
                skipSpace();
                String value = "true";
                if (peek() == '=') {
                    at++;
                    skipSpace();
                    value = identifier();
                }
                set.put(name, value);
            }
        }

        private boolean takeEdgeOperator() {
            skipSpace();
            if (text.startsWith("->", at) || text.startsWith("--", at)) {
                at += 2;
                return true;
            }
            return false;
        }

        /** A name, a number, or a quoted string with its escapes kept for {@link #lines}. */
        private String identifier() {
            skipSpace();
            char c = peek();
            if (c == '"') {
                StringBuilder out = new StringBuilder();
                at++;
                while (at < text.length()) {
                    char d = text.charAt(at);
                    if (d == '\\' && at + 1 < text.length()) {
                        char e = text.charAt(at + 1);
                        if (e == '"') {
                            out.append('"');
                        } else if (e == '\n') {
                            // a line continued
                        } else {
                            out.append('\\').append(e);
                        }
                        at += 2;
                        continue;
                    }
                    if (d == '"') {
                        at++;
                        // "a" + "b" joins two strings
                        int save = at;
                        skipSpace();
                        if (peek() == '+') {
                            at++;
                            skipSpace();
                            if (peek() == '"') {
                                out.append(identifier());
                                return out.toString();
                            }
                        }
                        at = save;
                        return out.toString();
                    }
                    out.append(d);
                    at++;
                }
                throw new IllegalArgumentException("A string is not closed.");
            }
            if (c == '<') {
                int depth = 0;
                int start = at;
                while (at < text.length()) {
                    char d = text.charAt(at++);
                    if (d == '<') {
                        depth++;
                    } else if (d == '>' && --depth == 0) {
                        break;
                    }
                }
                return text.substring(start + 1, at - 1).replaceAll("<[^>]*>", " ").strip();
            }
            int start = at;
            if (c == '-' && Character.isDigit(peekAt(at + 1))) {
                at++;                                                    // a negative number
            }
            while (at < text.length()) {
                char d = text.charAt(at);
                if (Character.isLetterOrDigit(d) || d == '_' || d == '.') {
                    at++;
                } else {
                    break;
                }
            }
            if (at == start) {
                throw new IllegalArgumentException("Unexpected '" + c + "' at " + at + ".");
            }
            return text.substring(start, at);
        }

        private void expect(char c) {
            skipSpace();
            if (peek() != c) {
                throw new IllegalArgumentException("Expected '" + c + "' at " + at + (peek() == 0 ? ", at the end." : ", found '" + peek() + "'."));
            }
            at++;
        }

        private boolean take(String word) {
            if (text.startsWith(word, at) && !Character.isLetterOrDigit(peekAt(at + word.length()))) {
                at += word.length();
                return true;
            }
            return false;
        }

        private void skipSpace() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
        }

        private char peek() {
            return peekAt(at);
        }

        private char peekAt(int index) {
            return index < text.length() ? text.charAt(index) : 0;
        }

        /** The text without its comments, {@code //} to the line's end and {@code /* … *}{@code /}; strings are left alone. */
        private static String stripComments(String text) {
            StringBuilder out = new StringBuilder();
            boolean inString = false;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (inString) {
                    out.append(c);
                    if (c == '\\' && i + 1 < text.length()) {
                        out.append(text.charAt(++i));
                    } else if (c == '"') {
                        inString = false;
                    }
                    continue;
                }
                if (c == '"') {
                    inString = true;
                    out.append(c);
                } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                    while (i < text.length() && text.charAt(i) != '\n') {
                        i++;
                    }
                    out.append('\n');
                } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                    int end = text.indexOf("*/", i + 2);
                    i = end < 0 ? text.length() : end + 1;
                    out.append(' ');
                } else if (c == '#' && (i == 0 || text.charAt(i - 1) == '\n')) {
                    while (i < text.length() && text.charAt(i) != '\n') {
                        i++;
                    }
                    out.append('\n');
                } else {
                    out.append(c);
                }
            }
            return out.toString();
        }
    }
}

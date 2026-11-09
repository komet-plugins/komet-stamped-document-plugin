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

import network.ike.komet.document.DecisionFigure;
import network.ike.komet.document.DecisionFigure.Mark;
import network.ike.komet.document.DecisionFigure.Polygon;
import network.ike.komet.document.DecisionFigure.Polyline;
import network.ike.komet.document.DecisionFigure.Rect;
import network.ike.komet.document.DecisionFigure.Stroke;
import network.ike.komet.document.DecisionFigure.Text;
import network.ike.komet.document.DecisionFigureText;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A {@link DotGraph} drawn as a figure: the nodes in ranks, each rank a row (or a column, left to
 * right), the edges between them, their labels beside them, and the clusters as boxes behind
 * their members. The figure is the one the pages print and the Reading view shows, so a diagram
 * reads the same in both.
 *
 * <p>The layout is the layered one a box-and-arrow diagram asks for, sized for the diagrams a
 * document holds, a dozen nodes or so: a node's rank is the longest path to it, nodes held on
 * one rank take the deepest rank among them, the order within a rank follows the neighbours'
 * order in a few sweeps, and a node then stands near the middle of its neighbours. An edge
 * forward runs straight between its nodes; one along a rank runs beside them; one back runs out
 * to the side and up.
 */
public final class DiagramFigure {

    /** What the figure is, as {@link DecisionFigure#role()} says. */
    public static final String ROLE = "diagram";

    static final double SIZE = 10;
    private static final double LABEL_SIZE = 9;
    private static final double LINE = DecisionFigureText.lineHeight(SIZE);
    private static final double LABEL_LINE = DecisionFigureText.lineHeight(LABEL_SIZE);
    /** The widest a node's label runs before it wraps. */
    private static final double MAX_LABEL = 170;
    private static final double PAD_X = 9;
    private static final double PAD_Y = 6;
    private static final double NODE_SEP = 26;
    private static final double RANK_SEP = 54;
    private static final double MARGIN = 8;
    private static final double CLUSTER_PAD = 10;
    private static final double ARROW = 7;

    private static final String NODE_FILL = "#f4f6f9";
    private static final String NODE_STROKE = "#5f6b7a";
    private static final String TEXT = "#1f2937";
    private static final String EDGE = "#5f6b7a";
    private static final String LABEL_INK = "#4b5563";
    private static final String CLUSTER_FILL = "#fbfcfd";
    private static final String CLUSTER_STROKE = "#c8ced8";

    private DiagramFigure() {
    }

    /**
     * Draws a graph.
     *
     * @param graph the graph
     * @return the figure
     */
    public static DecisionFigure of(DotGraph graph) {
        return new Layout(graph).figure();
    }

    /** A node being placed: its label as lines, its size, its rank and its place. */
    private static final class Box {
        final DotGraph.Node node;
        final List<String> lines = new ArrayList<>();
        final double w;
        final double h;
        int rank;
        /** Its centre along the order axis, and its start along the rank axis. */
        double o;
        double r;

        Box(DotGraph.Node node) {
            this.node = node;
            for (String line : node.lines()) {
                lines.addAll(DecisionFigureText.wrap(line, SIZE, MAX_LABEL));
            }
            if (lines.isEmpty()) {
                lines.add(node.id());
            }
            double widest = 0;
            for (String line : lines) {
                widest = Math.max(widest, DecisionFigureText.width(line, SIZE, false));
            }
            w = Math.ceil(widest) + 2 * PAD_X;
            h = lines.size() * LINE + 2 * PAD_Y;
        }

        /** The extent along the order axis. */
        double across(boolean leftToRight) {
            return leftToRight ? h : w;
        }

        /** The extent along the rank axis. */
        double along(boolean leftToRight) {
            return leftToRight ? w : h;
        }

        double x(boolean leftToRight) {
            return leftToRight ? r : o - w / 2;
        }

        double y(boolean leftToRight) {
            return leftToRight ? o - h / 2 : r;
        }
    }

    private static final class Layout {
        final DotGraph graph;
        final boolean lr;
        final Map<String, Box> boxes = new LinkedHashMap<>();
        final List<List<Box>> ranks = new ArrayList<>();
        /** The edges that were turned to break a cycle, by index. */
        final Set<Integer> back = new HashSet<>();
        final List<Mark> marks = new ArrayList<>();
        double width;
        double height;
        /** The right edge of the rightmost node, which a lane for an edge going back lies beyond. */
        double nodesRight;
        /** The edges' labels, drawn last. */
        final List<Mark> labels = new ArrayList<>();

        Layout(DotGraph graph) {
            this.graph = graph;
            this.lr = graph.leftToRight();
            for (DotGraph.Node node : graph.nodes()) {
                boxes.put(node.id(), new Box(node));
            }
        }

        DecisionFigure figure() {
            if (boxes.isEmpty()) {
                return new DecisionFigure(MARGIN * 2, MARGIN * 2, ROLE, List.of());
            }
            rank();
            order();
            place();
            drawClusters();
            drawEdges();
            drawNodes();
            marks.addAll(labels);                                        // over the nodes, so no box covers a label
            return new DecisionFigure(Math.ceil(width + MARGIN), Math.ceil(height + MARGIN), ROLE, marks);
        }

        // ── ranks ─────────────────────────────────────────────────────

        /** The longest path to each node, edges that close a cycle set aside; nodes held together take the deepest rank. */
        private void rank() {
            findBackEdges();
            Map<String, Integer> group = new HashMap<>();
            for (int g = 0; g < graph.sameRanks().size(); g++) {
                for (String id : graph.sameRanks().get(g)) {
                    group.put(id, g);
                }
            }
            for (Box box : boxes.values()) {
                box.rank = 0;
            }
            boolean moved = true;
            for (int pass = 0; moved && pass < 200; pass++) {
                moved = false;
                for (int i = 0; i < graph.edges().size(); i++) {
                    if (back.contains(i)) {
                        continue;
                    }
                    DotGraph.Edge edge = graph.edges().get(i);
                    Box from = boxes.get(edge.from());
                    Box to = boxes.get(edge.to());
                    if (from == null || to == null || from == to || sameGroup(group, edge.from(), edge.to())) {
                        continue;
                    }
                    if (to.rank < from.rank + 1) {
                        to.rank = from.rank + 1;
                        moved = true;
                    }
                }
                for (List<String> same : graph.sameRanks()) {
                    int deepest = same.stream().map(boxes::get).filter(b -> b != null).mapToInt(b -> b.rank).max().orElse(0);
                    for (String id : same) {
                        Box box = boxes.get(id);
                        if (box != null && box.rank != deepest) {
                            box.rank = deepest;
                            moved = true;
                        }
                    }
                }
            }
            int deepest = boxes.values().stream().mapToInt(b -> b.rank).max().orElse(0);
            for (int r = 0; r <= deepest; r++) {
                ranks.add(new ArrayList<>());
            }
            for (Box box : boxes.values()) {
                ranks.get(box.rank).add(box);
            }
        }

        private static boolean sameGroup(Map<String, Integer> group, String a, String b) {
            Integer ga = group.get(a);
            return ga != null && ga.equals(group.get(b));
        }

        /**
         * Edges that close a cycle, found by a depth-first walk in the nodes' order; they are drawn
         * back the other way. Nodes held on one rank walk as one, since an edge into any of them
         * and an edge out of another would otherwise push the group down without end.
         */
        private void findBackEdges() {
            Map<String, String> head = new HashMap<>();
            for (List<String> same : graph.sameRanks()) {
                for (String id : same) {
                    head.put(id, same.getFirst());
                }
            }
            Map<String, Integer> state = new HashMap<>();              // 1 on the path, 2 done
            for (String id : boxes.keySet()) {
                String group = head.getOrDefault(id, id);
                if (!state.containsKey(group)) {
                    walk(group, head, state);
                }
            }
        }

        private void walk(String group, Map<String, String> head, Map<String, Integer> state) {
            state.put(group, 1);
            for (int i = 0; i < graph.edges().size(); i++) {
                DotGraph.Edge edge = graph.edges().get(i);
                if (!head.getOrDefault(edge.from(), edge.from()).equals(group) || !boxes.containsKey(edge.to())) {
                    continue;
                }
                String to = head.getOrDefault(edge.to(), edge.to());
                if (to.equals(group)) {
                    continue;                                            // within the group: no rank between them
                }
                Integer seen = state.get(to);
                if (seen == null) {
                    walk(to, head, state);
                } else if (seen == 1) {
                    back.add(i);
                }
            }
            state.put(group, 2);
        }

        // ── order within a rank ───────────────────────────────────────

        /** A few sweeps down and up, each node taking the mean place of its neighbours on the rank before. */
        private void order() {
            for (int sweep = 0; sweep < 4; sweep++) {
                for (int r = 1; r < ranks.size(); r++) {
                    sortBy(ranks.get(r), r - 1);
                }
                for (int r = ranks.size() - 2; r >= 0; r--) {
                    sortBy(ranks.get(r), r + 1);
                }
            }
        }

        private void sortBy(List<Box> rank, int neighbourRank) {
            Map<Box, Double> place = new HashMap<>();
            for (int i = 0; i < rank.size(); i++) {
                place.put(rank.get(i), (double) i);
            }
            List<Box> neighbours = ranks.get(neighbourRank);
            Map<Box, Double> wanted = new HashMap<>();
            for (Box box : rank) {
                double sum = 0;
                int count = 0;
                for (DotGraph.Edge edge : graph.edges()) {
                    Box other = null;
                    if (edge.from().equals(box.node.id())) {
                        other = boxes.get(edge.to());
                    } else if (edge.to().equals(box.node.id())) {
                        other = boxes.get(edge.from());
                    }
                    if (other != null && other.rank == neighbourRank) {
                        sum += neighbours.indexOf(other);
                        count++;
                    }
                }
                wanted.put(box, count == 0 ? place.get(box) : sum / count);
            }
            rank.sort((a, b) -> {
                int byWanted = Double.compare(wanted.get(a), wanted.get(b));
                return byWanted != 0 ? byWanted : Double.compare(place.get(a), place.get(b));
            });
        }

        // ── places ────────────────────────────────────────────────────

        /** Ranks along the rank axis, each as deep as its deepest node; nodes across it near the middle of their neighbours. */
        private void place() {
            // room at the start for a cluster's box and label around the first nodes
            double start = MARGIN + (graph.clusters().isEmpty() ? 0 : CLUSTER_PAD + LABEL_LINE + 2);
            double r = start;
            for (List<Box> rank : ranks) {
                if (rank.isEmpty()) {
                    continue;                                            // a rank nothing stands on, left when nodes were held deeper
                }
                double deepest = 0;
                for (Box box : rank) {
                    box.r = r;
                    deepest = Math.max(deepest, box.along(lr));
                }
                r += deepest + RANK_SEP;
            }
            // first packed from the start, then drawn toward the neighbours, order kept and no two overlapping
            for (List<Box> rank : ranks) {
                pack(rank, start);
            }
            for (int pass = 0; pass < 6; pass++) {
                for (int i = 0; i < ranks.size(); i++) {
                    List<Box> rank = ranks.get(i);
                    if (rank.isEmpty()) {
                        continue;
                    }
                    Map<Box, Double> wanted = new HashMap<>();
                    for (Box box : rank) {
                        double sum = 0;
                        int count = 0;
                        for (DotGraph.Edge edge : graph.edges()) {
                            Box other = edge.from().equals(box.node.id()) ? boxes.get(edge.to())
                                    : edge.to().equals(box.node.id()) ? boxes.get(edge.from()) : null;
                            if (other != null && other.rank != box.rank) {
                                sum += other.o;
                                count++;
                            }
                        }
                        wanted.put(box, count == 0 ? box.o : sum / count);
                    }
                    for (int k = 0; k < rank.size(); k++) {
                        Box box = rank.get(k);
                        double least = k == 0 ? start + box.across(lr) / 2
                                : rank.get(k - 1).o + rank.get(k - 1).across(lr) / 2 + gap(rank.get(k - 1), box) + box.across(lr) / 2;
                        box.o = Math.max(least, wanted.get(box));
                    }
                    for (int k = rank.size() - 2; k >= 0; k--) {
                        Box box = rank.get(k);
                        Box next = rank.get(k + 1);
                        double most = next.o - next.across(lr) / 2 - gap(box, next) - box.across(lr) / 2;
                        if (box.o > most) {
                            box.o = most;
                        }
                    }
                    // a rank pushed before the start comes back
                    double first = rank.getFirst().o - rank.getFirst().across(lr) / 2;
                    if (first < start) {
                        for (Box box : rank) {
                            box.o += start - first;
                        }
                    }
                }
            }
            width = 0;
            height = 0;
            for (Box box : boxes.values()) {
                width = Math.max(width, box.x(lr) + box.w);
                height = Math.max(height, box.y(lr) + box.h);
            }
            nodesRight = width;
        }

        private void pack(List<Box> rank, double from) {
            double at = from;
            Box before = null;
            for (Box box : rank) {
                at += before == null ? 0 : gap(before, box);
                box.o = at + box.across(lr) / 2;
                at += box.across(lr);
                before = box;
            }
        }

        /** The room between two neighbours on a rank: more where a labelled edge runs between them, so its label has a place. */
        private double gap(Box a, Box b) {
            double gap = NODE_SEP;
            for (DotGraph.Edge edge : graph.edges()) {
                boolean between = edge.from().equals(a.node.id()) && edge.to().equals(b.node.id())
                        || edge.from().equals(b.node.id()) && edge.to().equals(a.node.id());
                if (between && !edge.lines().isEmpty()) {
                    double widest = lr ? edge.lines().size() * LABEL_LINE
                            : edge.lines().stream().mapToDouble(l -> DecisionFigureText.width(l, LABEL_SIZE, false)).max().orElse(0);
                    gap = Math.max(gap, widest + 16);
                }
            }
            return gap;
        }

        // ── marks ─────────────────────────────────────────────────────

        private void drawClusters() {
            for (DotGraph.Cluster cluster : graph.clusters()) {
                List<Box> members = cluster.members().stream().map(boxes::get).filter(b -> b != null).toList();
                if (members.isEmpty()) {
                    continue;
                }
                double x0 = Double.MAX_VALUE;
                double y0 = Double.MAX_VALUE;
                double x1 = 0;
                double y1 = 0;
                for (Box box : members) {
                    x0 = Math.min(x0, box.x(lr));
                    y0 = Math.min(y0, box.y(lr));
                    x1 = Math.max(x1, box.x(lr) + box.w);
                    y1 = Math.max(y1, box.y(lr) + box.h);
                }
                double labelHeight = cluster.label().isEmpty() ? 0 : LABEL_LINE + 2;
                x0 -= CLUSTER_PAD;
                y0 -= CLUSTER_PAD + labelHeight;
                x1 += CLUSTER_PAD;
                y1 += CLUSTER_PAD;
                marks.add(new Rect(x0, y0, x1 - x0, y1 - y0, CLUSTER_FILL, 1, Stroke.dashed(CLUSTER_STROKE, 1), ROLE + "-cluster"));
                if (!cluster.label().isEmpty()) {
                    marks.add(new Text(x0 + 6, y0 + 3, cluster.label(), LABEL_SIZE, true, false, LABEL_INK, ROLE + "-cluster-label"));
                }
                width = Math.max(width, x1);
                height = Math.max(height, y1);
            }
        }

        private void drawNodes() {
            for (Box box : boxes.values()) {
                double x = box.x(lr);
                double y = box.y(lr);
                String fill = box.node.fill() != null ? box.node.fill() : NODE_FILL;
                Stroke stroke = box.node.dashed() ? Stroke.dashed(NODE_STROKE, 1) : Stroke.solid(NODE_STROKE, 1);
                marks.add(new Rect(x, y, box.w, box.h, fill, 1, stroke, ROLE + "-node"));
                double top = y + PAD_Y;
                for (String line : box.lines) {
                    double lineWidth = DecisionFigureText.width(line, SIZE, false);
                    marks.add(new Text(x + (box.w - lineWidth) / 2, top, line, SIZE, false, false, TEXT, ROLE + "-node-label"));
                    top += LINE;
                }
            }
        }

        private void drawEdges() {
            // the edges between one pair, so parallel ones stand apart
            Map<String, Integer> seen = new HashMap<>();
            for (int i = 0; i < graph.edges().size(); i++) {
                DotGraph.Edge edge = graph.edges().get(i);
                Box from = boxes.get(edge.from());
                Box to = boxes.get(edge.to());
                if (from == null || to == null) {
                    continue;
                }
                String pair = edge.from().compareTo(edge.to()) < 0 ? edge.from() + "\t" + edge.to() : edge.to() + "\t" + edge.from();
                int k = seen.merge(pair, 1, Integer::sum) - 1;
                double offset = (k % 2 == 0 ? 1 : -1) * ((k + 1) / 2) * 10;
                double[] points;
                if (from == to) {
                    continue;                                            // a loop on one node is not drawn
                }
                int kind;                                                // 0 along the rank, 1 forward, 2 back
                if (from.rank == to.rank) {
                    points = alongRank(from, to, offset);
                    kind = 0;
                } else if (to.rank > from.rank) {
                    points = forward(from, to, offset);
                    kind = 1;
                } else {
                    points = backward(from, to, offset);
                    kind = 2;
                }
                Stroke stroke = edge.dashed() ? Stroke.dashed(EDGE, 1) : Stroke.solid(EDGE, 1);
                double[] shortened = graph.directed() ? shorten(points, ARROW * 0.6) : points;
                marks.add(new Polyline(shortened, stroke, ROLE + "-edge"));
                if (graph.directed()) {
                    marks.add(arrowhead(points));
                }
                if (!edge.lines().isEmpty()) {
                    label(points, edge.lines(), kind);
                }
            }
        }

        /** Straight from the deeper side of a node to the facing side of one on a deeper rank. */
        private double[] forward(Box from, Box to, double offset) {
            if (lr) {
                return new double[] {from.x(true) + from.w, from.o + offset, to.x(true), to.o + offset};
            }
            return new double[] {from.o + offset, from.y(false) + from.h, to.o + offset, to.y(false)};
        }

        /** Between two nodes on one rank: from the side of one to the facing side of the other. */
        private double[] alongRank(Box from, Box to, double offset) {
            boolean after = to.o > from.o;
            if (lr) {
                double fy = after ? from.y(true) + from.h : from.y(true);
                double ty = after ? to.y(true) : to.y(true) + to.h;
                return new double[] {from.x(true) + from.w / 2 + offset, fy, to.x(true) + to.w / 2 + offset, ty};
            }
            double fx = after ? from.x(false) + from.w : from.x(false);
            double tx = after ? to.x(false) : to.x(false) + to.w;
            return new double[] {fx, from.y(false) + from.h / 2 + offset, tx, to.y(false) + to.h / 2 + offset};
        }

        /** How many edges have gone back so far, each taking the next lane beside the figure. */
        private int lanes;

        /** Back against the ranks: out past every node to a lane of its own, along, and in. */
        private double[] backward(Box from, Box to, double offset) {
            double lane = 16 + 12 * lanes++;
            if (lr) {
                double y = height + lane;
                double fx = from.x(true) + from.w / 2 + offset;
                double tx = to.x(true) + to.w / 2 + offset;
                return new double[] {fx, from.y(true) + from.h, fx, y, tx, y, tx, to.y(true) + to.h};
            }
            double x = nodesRight + lane;
            double fy = from.y(false) + from.h / 2 + offset;
            double ty = to.y(false) + to.h / 2 + offset;
            width = Math.max(width, x);
            return new double[] {from.x(false) + from.w, fy, x, fy, x, ty, to.x(false) + to.w, ty};
        }

        /** The line stopped short of its end, so the arrowhead's point and not its base meets the node. */
        private static double[] shorten(double[] points, double by) {
            int n = points.length;
            double x0 = points[n - 4];
            double y0 = points[n - 3];
            double x1 = points[n - 2];
            double y1 = points[n - 1];
            double length = Math.hypot(x1 - x0, y1 - y0);
            if (length <= by) {
                return points;
            }
            double[] out = points.clone();
            out[n - 2] = x1 - (x1 - x0) / length * by;
            out[n - 1] = y1 - (y1 - y0) / length * by;
            return out;
        }

        private static Polygon arrowhead(double[] points) {
            int n = points.length;
            double x0 = points[n - 4];
            double y0 = points[n - 3];
            double x1 = points[n - 2];
            double y1 = points[n - 1];
            double length = Math.max(1e-6, Math.hypot(x1 - x0, y1 - y0));
            double ux = (x1 - x0) / length;
            double uy = (y1 - y0) / length;
            double bx = x1 - ux * ARROW;
            double by = y1 - uy * ARROW;
            double half = ARROW * 0.4;
            return new Polygon(new double[] {x1, y1, bx - uy * half, by + ux * half, bx + uy * half, by - ux * half},
                    EDGE, null, ROLE + "-arrow");
        }

        /**
         * The label beside the middle of the edge, on a pale ground so it reads over other lines:
         * an edge along a rank has it above (or beside, left to right); an edge forward has it on
         * the side it leans toward, so the labels of edges fanning out from one node fan with
         * them; an edge back has it beside its lane, in the room past the nodes.
         */
        private void label(double[] points, List<String> lines, int kind) {
            int at = kind == 2 ? 2 : 0;                                  // the lane is the second piece of an edge back
            double mx = (points[at] + points[at + 2]) / 2;
            double my = (points[at + 1] + points[at + 3]) / 2;
            double widest = 0;
            for (String line : lines) {
                widest = Math.max(widest, DecisionFigureText.width(line, LABEL_SIZE, false));
            }
            double h = lines.size() * LABEL_LINE;
            double x;
            double y;
            if (kind == 0) {
                boolean across = Math.abs(points[2] - points[0]) >= Math.abs(points[3] - points[1]);
                x = across ? mx - widest / 2 : mx + 5;
                y = across ? my - h - 3 : my - h / 2;
            } else if (kind == 2) {
                boolean vertical = Math.abs(points[at + 2] - points[at]) < Math.abs(points[at + 3] - points[at + 1]);
                x = vertical ? mx + 5 : mx - widest / 2;
                y = vertical ? my - h / 2 : my + 4;
            } else {
                boolean leansRight = points[2] >= points[0];
                boolean steep = Math.abs(points[2] - points[0]) < Math.abs(points[3] - points[1]);
                x = steep ? (leansRight ? mx + 5 : mx - widest - 5) : mx - widest / 2;
                y = steep ? my - h / 2 : (points[3] >= points[1] ? my - h - 3 : my + 4);
            }
            labels.add(new Rect(x - 2, y - 1, widest + 4, h + 2, "#ffffff", 0.85, null, ROLE + "-edge-label-ground"));
            double top = y;
            for (String line : lines) {
                labels.add(new Text(x, top, line, LABEL_SIZE, false, false, LABEL_INK, ROLE + "-edge-label"));
                top += LABEL_LINE;
            }
            width = Math.max(width, x + widest + 2);
            height = Math.max(height, y + h + 1);
        }
    }
}

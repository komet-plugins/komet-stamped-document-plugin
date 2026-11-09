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
import network.ike.komet.document.DecisionFigure.Rect;
import network.ike.komet.document.DecisionFigure.Text;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A graph drawn as a figure: every node a box with its label, no two boxes overlapping, edges running down the ranks. */
class DiagramFigureTest {

    private static List<Rect> nodes(DecisionFigure figure) {
        return figure.marks().stream().filter(m -> m.role().equals("diagram-node")).map(Rect.class::cast).toList();
    }

    private static long count(DecisionFigure figure, String role) {
        return figure.marks().stream().filter(m -> m.role().equals(role)).count();
    }

    @Test
    void everyNodeIsABoxWithItsLabelAndNoTwoOverlap() {
        DecisionFigure figure = DiagramFigure.of(DotGraph.parse(DotGraphTest.CONTROL));
        assertEquals("diagram", figure.role());
        List<Rect> boxes = nodes(figure);
        assertEquals(5, boxes.size());
        for (int i = 0; i < boxes.size(); i++) {
            for (int j = i + 1; j < boxes.size(); j++) {
                Rect a = boxes.get(i);
                Rect b = boxes.get(j);
                boolean apart = a.x() + a.w() <= b.x() || b.x() + b.w() <= a.x() || a.y() + a.h() <= b.y() || b.y() + b.h() <= a.y();
                assertTrue(apart, "boxes " + i + " and " + j + " overlap");
            }
        }
        List<String> labels = figure.marks().stream().filter(m -> m.role().equals("diagram-node-label")).map(m -> ((Text) m).text()).toList();
        assertTrue(labels.contains("Regulator"));
        assertTrue(labels.contains("failure rate"), "every line of a label is drawn");
        assertEquals(6, count(figure, "diagram-edge"));
        assertEquals(6, count(figure, "diagram-arrow"), "a directed graph's edges end in arrowheads");
        assertEquals(List.of("approve,", "deny", "pivotal evidence", "post-approval", "study result", "adverse event", "reports"),
                figure.marks().stream().filter(m -> m.role().equals("diagram-edge-label")).map(m -> ((Text) m).text()).toList());
        for (Mark mark : figure.marks()) {
            if (mark instanceof Rect r) {
                assertTrue(r.x() >= 0 && r.y() >= 0 && r.x() + r.w() <= figure.width() && r.y() + r.h() <= figure.height(),
                        "every box lies within the figure: " + mark);
            }
        }
    }

    @Test
    void ranksRunDownTheFigureAndHeldNodesShareARow() {
        DecisionFigure figure = DiagramFigure.of(DotGraph.parse(DotGraphTest.CONTROL));
        Map<String, Rect> byLabel = boxesByFirstLine(figure);
        assertEquals(byLabel.get("Regulator").y(), byLabel.get("Other devices").y(), 1e-9, "rank=same");
        assertEquals(byLabel.get("Manufacturer").y(), byLabel.get("Clinicians").y(), 1e-9);
        assertTrue(byLabel.get("Manufacturer").y() > byLabel.get("Regulator").y(), "the regulator stands above the manufacturer it approves");
    }

    @Test
    void leftToRightRunsTheRanksAcross() {
        DecisionFigure figure = DiagramFigure.of(DotGraph.parse("digraph { rankdir=LR; a -> b -> c; }"));
        Map<String, Rect> byLabel = boxesByFirstLine(figure);
        assertTrue(byLabel.get("a").x() < byLabel.get("b").x() && byLabel.get("b").x() < byLabel.get("c").x());
        assertEquals(byLabel.get("a").y(), byLabel.get("c").y(), 1e-9, "one row");
    }

    @Test
    void aClusterBoxesItsMembersAndCarriesItsLabel() {
        DecisionFigure figure = DiagramFigure.of(DotGraph.parse("digraph { subgraph cluster_x { label=\"Inside\"; a; b; } a -> b; c -> a; }"));
        Rect cluster = (Rect) figure.marks().stream().filter(m -> m.role().equals("diagram-cluster")).findFirst().orElseThrow();
        Map<String, Rect> byLabel = boxesByFirstLine(figure);
        for (String member : List.of("a", "b")) {
            Rect box = byLabel.get(member);
            assertTrue(box.x() >= cluster.x() && box.y() >= cluster.y() && box.x() + box.w() <= cluster.x() + cluster.w()
                    && box.y() + box.h() <= cluster.y() + cluster.h(), member + " is inside the cluster");
        }
        assertTrue(figure.marks().stream().anyMatch(m -> m.role().equals("diagram-cluster-label") && ((Text) m).text().equals("Inside")));
        assertEquals(0, figure.marks().indexOf(cluster), "the cluster is drawn first, behind its members");
    }

    @Test
    void anEmptyGraphIsAnEmptyFigure() {
        DecisionFigure figure = DiagramFigure.of(DotGraph.parse("digraph {}"));
        assertTrue(figure.marks().isEmpty());
    }

    private static Map<String, Rect> boxesByFirstLine(DecisionFigure figure) {
        List<Rect> boxes = nodes(figure);
        List<Text> labels = figure.marks().stream().filter(m -> m.role().equals("diagram-node-label")).map(Text.class::cast).toList();
        return boxes.stream().collect(Collectors.toMap(box -> labels.stream()
                .filter(t -> t.x() >= box.x() && t.x() <= box.x() + box.w() && t.top() >= box.y() && t.top() <= box.y() + box.h())
                .findFirst().orElseThrow().text(), box -> box));
    }
}

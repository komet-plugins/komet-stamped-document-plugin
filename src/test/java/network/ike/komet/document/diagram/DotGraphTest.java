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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The DOT a document's graphviz blocks hold, read as nodes, edges, ranks and clusters. */
class DotGraphTest {

    /** The control-structure figure of the regulatory document, as written. */
    static final String CONTROL = """
            digraph control_structure {
              rankdir=TB; nodesep=0.3; ranksep=0.6;
              node [shape=box, fontname="Helvetica", fontsize=11, style=filled, fillcolor="#f2f5f9"];
              edge [fontname="Helvetica", fontsize=10];
              reg [label="Regulator\\nprocess model: the device's\\nfailure rate"];
              man [label="Manufacturer\\nruns the studies"];
              cli [label="Clinicians"];
              oth [label="Other devices\\n(DeX)", style="filled,dashed", fillcolor="#ffffff"];
              pat [label="Patients and the device", fillcolor="#e8f0fb"];
              reg -> man [label="approve,\\ndeny"];
              man -> reg [label="pivotal evidence", style=dashed];
              man -> reg [label="post-approval\\nstudy result", style=dashed];
              reg -> cli;
              pat -> reg [label="adverse event\\nreports", style=dashed];
              oth -> reg [style=dashed];
              {rank=same; man; cli;}
              {rank=same; oth; reg;}
            }
            """;

    @Test
    void nodesEdgesAndRanksAreRead() {
        DotGraph graph = DotGraph.parse(CONTROL);
        assertTrue(graph.directed());
        assertFalse(graph.leftToRight());
        assertEquals(List.of("reg", "man", "cli", "oth", "pat"), graph.nodes().stream().map(DotGraph.Node::id).toList(), "in the order first named");
        assertEquals(List.of("Regulator", "process model: the device's", "failure rate"), graph.node("reg").orElseThrow().lines());
        assertEquals("#f2f5f9", graph.node("reg").orElseThrow().fill(), "the node default");
        assertEquals("#e8f0fb", graph.node("pat").orElseThrow().fill(), "the node's own");
        assertTrue(graph.node("oth").orElseThrow().dashed());
        assertFalse(graph.node("reg").orElseThrow().dashed());
        assertEquals(6, graph.edges().size());
        DotGraph.Edge second = graph.edges().get(1);
        assertEquals("man", second.from());
        assertEquals("reg", second.to());
        assertEquals(List.of("pivotal evidence"), second.lines());
        assertTrue(second.dashed());
        assertEquals(List.of(), graph.edges().get(3).lines(), "an edge with no label");
        assertEquals(List.of(List.of("man", "cli"), List.of("oth", "reg")), graph.sameRanks());
        assertTrue(graph.clusters().isEmpty());
    }

    @Test
    void clustersAndChainsAndComments() {
        DotGraph graph = DotGraph.parse("""
                // the levels
                digraph {
                  rankdir=LR;
                  subgraph cluster_a { label="Premarket"; a -> b -> c; }
                  subgraph cluster_b { label="Postmarket"; d [label="Registry"]; }
                  c -> d [label="approval"]; /* across */
                  e;
                }
                """);
        assertTrue(graph.leftToRight());
        assertEquals(5, graph.nodes().size());
        assertEquals(3, graph.edges().size(), "a chain of three names two edges");
        assertEquals(2, graph.clusters().size());
        assertEquals("Premarket", graph.clusters().get(0).label());
        assertEquals(List.of("a", "b", "c"), graph.clusters().get(0).members());
        assertEquals(List.of("d"), graph.clusters().get(1).members());
        assertEquals(List.of("Registry"), graph.node("d").orElseThrow().lines());
        assertEquals(List.of("e"), graph.node("e").orElseThrow().lines(), "a node with no label is named by its id");
        assertNull(graph.node("e").orElseThrow().fill());
    }

    @Test
    void anUndirectedGraphAndOtherLineBreaks() {
        DotGraph graph = DotGraph.parse("graph g { a -- b [label=\"one\\lline two\"]; }");
        assertFalse(graph.directed());
        assertEquals(List.of("one", "line two"), graph.edges().getFirst().lines());
    }

    @Test
    void whatIsNotAGraphIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> DotGraph.parse("flowchart LR\n a --> b"));
        assertThrows(IllegalArgumentException.class, () -> DotGraph.parse("digraph { a -> "));
    }
}

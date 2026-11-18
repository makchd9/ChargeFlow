package com.chargeflow.algo.flow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Correctness tests for Edmonds–Karp, Ford–Fulkerson (DFS) and min-cut extraction. */
class MaxFlowTest {

    /** CLRS Figure 26.1 network; known max flow = 23. */
    private static FlowNetwork clrs() {
        FlowNetwork g = new FlowNetwork(6); // s=0, v1..v4 = 1..4, t=5
        g.addEdge(0, 1, 16); g.addEdge(0, 2, 13);
        g.addEdge(2, 1, 4);  g.addEdge(1, 3, 12);
        g.addEdge(3, 2, 9);  g.addEdge(2, 4, 14);
        g.addEdge(4, 3, 7);  g.addEdge(3, 5, 20);
        g.addEdge(4, 5, 4);
        return g;
    }

    @Test
    @DisplayName("1. textbook network (CLRS 26.1) has max flow 23 under both solvers")
    void textbookExample() {
        assertEquals(23, new EdmondsKarp().augment(clrs(), 0, 5));
        assertEquals(23, new FordFulkersonDfs().augment(clrs(), 0, 5));
    }

    @Test
    @DisplayName("2. edge case: sink unreachable gives flow 0 and an empty min cut")
    void disconnected() {
        FlowNetwork g = new FlowNetwork(4);
        g.addEdge(0, 1, 5);
        g.addEdge(2, 3, 5);
        assertEquals(0, new EdmondsKarp().augment(g, 0, 3));
        MinCut c = MinCut.of(g, 0);
        assertEquals(0, c.capacity());
        assertTrue(c.cutArcs().isEmpty());
    }

    @Test
    @DisplayName("3. boundary: zero-capacity edge and single-edge network")
    void zeroAndSingle() {
        FlowNetwork g = new FlowNetwork(2);
        g.addEdge(0, 1, 0);
        assertEquals(0, new EdmondsKarp().augment(g, 0, 1));
        FlowNetwork h = new FlowNetwork(2);
        h.addEdge(0, 1, 7);
        assertEquals(7, new EdmondsKarp().augment(h, 0, 1));
    }

    @Test
    @DisplayName("4. parallel and anti-parallel edges are handled via residual arcs")
    void parallelEdges() {
        FlowNetwork g = new FlowNetwork(3);
        g.addEdge(0, 1, 3); g.addEdge(0, 1, 4); // parallel
        g.addEdge(1, 0, 2);                     // anti-parallel
        g.addEdge(1, 2, 10);
        assertEquals(7, new EdmondsKarp().augment(g, 0, 2));
    }

    @Test
    @DisplayName("5. the algorithm must use a reverse residual arc to reach the optimum")
    void needsCancellation() {
        // Diamond with a cross edge: a naive "first path" s-a-b-t blocks without cancellation.
        FlowNetwork g = new FlowNetwork(4); // s=0 a=1 b=2 t=3
        g.addEdge(0, 1, 1); g.addEdge(0, 2, 1);
        g.addEdge(1, 2, 1); g.addEdge(1, 3, 1); g.addEdge(2, 3, 1);
        assertEquals(2, new EdmondsKarp().augment(g, 0, 3));
    }

    @Test
    @DisplayName("6. bipartite matching: 3 workers x 3 jobs with a perfect matching")
    void bipartiteMatching() {
        // s=0, workers 1..3, jobs 4..6, t=7
        FlowNetwork g = new FlowNetwork(8);
        for (int w = 1; w <= 3; w++) g.addEdge(0, w, 1);
        for (int j = 4; j <= 6; j++) g.addEdge(j, 7, 1);
        g.addEdge(1, 4, 1); g.addEdge(1, 5, 1);
        g.addEdge(2, 4, 1);
        g.addEdge(3, 5, 1); g.addEdge(3, 6, 1);
        assertEquals(3, new EdmondsKarp().augment(g, 0, 7));
    }

    @Test
    @DisplayName("7. invalid input: source == sink, or a node out of range, is rejected")
    void invalid() {
        FlowNetwork g = new FlowNetwork(3);
        assertThrows(IllegalArgumentException.class, () -> new EdmondsKarp().augment(g, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new EdmondsKarp().augment(g, 0, 9));
        assertThrows(IllegalArgumentException.class, () -> g.addEdge(0, 1, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> g.addEdge(0, 5, 1));
    }

    @Test
    @DisplayName("8. resuming after adding edges continues from the existing flow (used for tiers)")
    void incremental() {
        FlowNetwork g = new FlowNetwork(3);
        g.addEdge(0, 1, 5); g.addEdge(1, 2, 3);
        EdmondsKarp ek = new EdmondsKarp();
        assertEquals(3, ek.augment(g, 0, 2));
        g.addEdge(0, 2, 4);
        assertEquals(4, ek.augment(g, 0, 2)); // only the new capacity is added
    }

    /** Random layered graphs: EK == FF, capacity and conservation hold, and |f| == min-cut capacity. */
    @ParameterizedTest(name = "9. stress/property seed {0}")
    @ValueSource(longs = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
    void randomPropertyCheck(long seed) {
        Random r = new Random(seed);
        int n = 30 + r.nextInt(40);
        FlowNetwork a = new FlowNetwork(n), b = new FlowNetwork(n);
        int m = n * 4;
        for (int i = 0; i < m; i++) {
            int u = r.nextInt(n), v = r.nextInt(n);
            if (u == v) continue;
            int c = r.nextInt(20);
            a.addEdge(u, v, c);
            b.addEdge(u, v, c);
        }
        long fa = new EdmondsKarp().augment(a, 0, n - 1);
        long fb = new FordFulkersonDfs().augment(b, 0, n - 1);
        assertEquals(fa, fb, "both Ford-Fulkerson variants must find the same max value");

        long[] net = new long[n];
        for (int e = 0; e < a.arcCount(); e += 2) {
            int f = a.flow(e);
            assertTrue(f >= 0 && f <= a.capacity(e), "capacity constraint");
            net[a.source(e)] -= f;
            net[a.target(e)] += f;
        }
        for (int v = 1; v < n - 1; v++) assertEquals(0, net[v], "conservation at " + v);
        assertEquals(fa, net[n - 1], "flow value equals inflow at sink");
        assertEquals(fa, MinCut.of(a, 0).capacity(), "max-flow = min-cut");
    }
}

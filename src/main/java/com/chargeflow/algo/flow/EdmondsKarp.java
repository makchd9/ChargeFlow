package com.chargeflow.algo.flow;

import java.util.Arrays;

/**
 * Edmonds–Karp maximum flow: Ford–Fulkerson where every augmenting path is a
 * <em>shortest</em> path (fewest arcs) in the residual network, found by BFS.
 *
 * <pre>
 * EDMONDS-KARP(G, s, t):
 *   while BFS finds an s-t path P in the residual graph G_f:
 *       b = min residual capacity on P        // bottleneck
 *       for each arc e on P: push b along e   // residual[e] -= b, residual[rev(e)] += b
 *       flow += b
 *   return flow
 * </pre>
 *
 * <p><b>Correctness:</b> when no augmenting path exists, the set S of nodes
 * reachable from s in G_f defines a cut (S, V\S) whose arcs are all saturated,
 * so |f| = c(S, V\S). By weak duality, |f| <= c(any cut), so f is maximum
 * (max-flow min-cut theorem).</p>
 *
 * <p><b>Complexity:</b> BFS shortest-path distances never decrease, and each arc
 * becomes critical at most V/2 times, so there are O(VE) augmentations of O(E)
 * each: O(V E^2) time in the worst case. Here every path carries an integer flow
 * of at least 1, so the number of augmentations is also bounded by |f*|, which
 * gives O(E * min(VE, |f*|)). Space is O(V) on top of the network.</p>
 */
public final class EdmondsKarp implements MaxFlowSolver {

    private long paths;
    private long scans;

    private int[] parentArc = new int[0];
    private int[] queue = new int[0];

    @Override
    public long augment(FlowNetwork g, int s, int t) {
        validate(g, s, t);
        int n = g.nodeCount();
        if (parentArc.length < n) {
            parentArc = new int[n];
            queue = new int[n];
        }
        long added = 0;
        while (true) {
            if (!bfs(g, s, t, n)) break;
            // bottleneck along the parent-arc chain from t back to s
            int bottleneck = Integer.MAX_VALUE;
            for (int v = t; v != s; ) {
                int e = parentArc[v];
                bottleneck = Math.min(bottleneck, g.residual(e));
                v = g.source(e);
            }
            for (int v = t; v != s; ) {
                int e = parentArc[v];
                g.push(e, bottleneck);
                v = g.source(e);
            }
            added += bottleneck;
            paths++;
        }
        return added;
    }

    /** BFS over arcs with positive residual capacity; stops as soon as t is labelled. */
    private boolean bfs(FlowNetwork g, int s, int t, int n) {
        Arrays.fill(parentArc, 0, n, -1);
        int qh = 0, qt = 0;
        queue[qt++] = s;
        parentArc[s] = -2; // mark visited
        while (qh < qt) {
            int u = queue[qh++];
            for (int e = g.firstArc(u); e != -1; e = g.nextArc(e)) {
                scans++;
                int v = g.target(e);
                if (parentArc[v] == -1 && g.residual(e) > 0) {
                    parentArc[v] = e;
                    if (v == t) return true;
                    queue[qt++] = v;
                }
            }
        }
        return false;
    }

    static void validate(FlowNetwork g, int s, int t) {
        if (g == null) throw new IllegalArgumentException("network is null");
        int n = g.nodeCount();
        if (s < 0 || s >= n || t < 0 || t >= n) throw new IllegalArgumentException("source/sink out of range");
        if (s == t) throw new IllegalArgumentException("source and sink must differ");
    }

    @Override public long augmentingPaths() { return paths; }
    @Override public long arcScans() { return scans; }
    @Override public String name() { return "Edmonds-Karp (BFS)"; }
}

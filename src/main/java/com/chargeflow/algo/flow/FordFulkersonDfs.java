package com.chargeflow.algo.flow;

import java.util.Arrays;

/**
 * Baseline: classic Ford–Fulkerson that takes <em>any</em> augmenting path,
 * found by iterative depth-first search. It returns the same maximum value as
 * Edmonds–Karp, but its path count is bounded only by |f*|, not by O(VE), so
 * long, winding DFS paths can make it slower on dense, contested networks.
 * Time: O(E * |f*|). Used only as a benchmark baseline.
 */
public final class FordFulkersonDfs implements MaxFlowSolver {

    private long paths;
    private long scans;

    private int[] parentArc = new int[0];
    private int[] stack = new int[0];
    private int[] iter = new int[0];

    @Override
    public long augment(FlowNetwork g, int s, int t) {
        EdmondsKarp.validate(g, s, t);
        int n = g.nodeCount();
        if (parentArc.length < n) {
            parentArc = new int[n];
            stack = new int[n];
            iter = new int[n];
        }
        long added = 0;
        while (dfs(g, s, t, n)) {
            int b = Integer.MAX_VALUE;
            for (int v = t; v != s; v = g.source(parentArc[v])) b = Math.min(b, g.residual(parentArc[v]));
            for (int v = t; v != s; v = g.source(parentArc[v])) g.push(parentArc[v], b);
            added += b;
            paths++;
        }
        return added;
    }

    /** Iterative DFS (no recursion, so large networks cannot overflow the call stack). */
    private boolean dfs(FlowNetwork g, int s, int t, int n) {
        Arrays.fill(parentArc, 0, n, -1);
        for (int i = 0; i < n; i++) iter[i] = g.firstArc(i);
        int sp = 0;
        stack[sp++] = s;
        parentArc[s] = -2;
        while (sp > 0) {
            int u = stack[sp - 1];
            int e = iter[u];
            if (e == -1) { sp--; continue; }
            iter[u] = g.nextArc(e);
            scans++;
            int v = g.target(e);
            if (parentArc[v] == -1 && g.residual(e) > 0) {
                parentArc[v] = e;
                if (v == t) return true;
                stack[sp++] = v;
            }
        }
        return false;
    }

    @Override public long augmentingPaths() { return paths; }
    @Override public long arcScans() { return scans; }
    @Override public String name() { return "Ford-Fulkerson (DFS)"; }
}

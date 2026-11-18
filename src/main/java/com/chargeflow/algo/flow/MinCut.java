package com.chargeflow.algo.flow;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts a minimum s-t cut from a network that holds a maximum flow.
 * S = nodes reachable from s through arcs with positive residual capacity.
 * The cut arcs are the forward arcs (u in S, v not in S); every one of them is
 * saturated, and their capacities sum to the max-flow value.
 * Time O(V + E).
 */
public final class MinCut {

    private final boolean[] sourceSide;
    private final List<Integer> cutArcs;
    private final long capacity;

    private MinCut(boolean[] sourceSide, List<Integer> cutArcs, long capacity) {
        this.sourceSide = sourceSide;
        this.cutArcs = cutArcs;
        this.capacity = capacity;
    }

    public static MinCut of(FlowNetwork g, int s) {
        int n = g.nodeCount();
        boolean[] seen = new boolean[n];
        int[] q = new int[n];
        int qh = 0, qt = 0;
        q[qt++] = s;
        seen[s] = true;
        while (qh < qt) {
            int u = q[qh++];
            for (int e = g.firstArc(u); e != -1; e = g.nextArc(e)) {
                int v = g.target(e);
                if (!seen[v] && g.residual(e) > 0) {
                    seen[v] = true;
                    q[qt++] = v;
                }
            }
        }
        List<Integer> arcs = new ArrayList<>();
        long cap = 0;
        for (int e = 0; e < g.arcCount(); e += 2) { // forward arcs only
            if (seen[g.source(e)] && !seen[g.target(e)]) {
                arcs.add(e);
                cap += g.capacity(e);
            }
        }
        return new MinCut(seen, arcs, cap);
    }

    public boolean onSourceSide(int v) { return sourceSide[v]; }
    public List<Integer> cutArcs() { return cutArcs; }
    public long capacity() { return capacity; }
}

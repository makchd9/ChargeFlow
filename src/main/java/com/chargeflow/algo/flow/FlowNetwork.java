package com.chargeflow.algo.flow;

import java.util.Arrays;

/**
 * Directed flow network stored as a residual graph in flat int arrays
 * (forward-star / linked adjacency lists).
 *
 * <p>Every call to {@link #addEdge(int, int, int)} creates a pair of arcs:
 * the forward arc at an even index {@code e} and its reverse (residual) arc at
 * {@code e ^ 1}. Pushing {@code x} units along {@code e} decreases
 * {@code residual[e]} and increases {@code residual[e ^ 1]}; this is the
 * standard residual-network construction used by Ford–Fulkerson methods.</p>
 *
 * <p>Space: O(V + E). Nodes are dense integers {@code 0 .. n-1}.</p>
 *
 * <p>Nodes can be added after construction ({@link #addNode()}), and edges can be
 * added between max-flow runs. Because flow already pushed is stored in the
 * residual capacities, a later run continues from the current flow, which
 * the tiered (priority) allocation relies on.</p>
 */
public final class FlowNetwork {

    private int nodeCount;
    private int[] head;      // head[v] = first arc index out of v, or -1

    private int arcCount;
    private int[] next;      // next arc in the same adjacency list
    private int[] to;        // arc target
    private int[] capacity;  // original capacity (0 for reverse arcs)
    private int[] residual;  // current residual capacity

    public FlowNetwork(int initialNodes) {
        if (initialNodes < 0) throw new IllegalArgumentException("node count must be >= 0");
        this.nodeCount = initialNodes;
        this.head = new int[Math.max(4, initialNodes)];
        Arrays.fill(head, -1);
        int initArcs = 16;
        next = new int[initArcs];
        to = new int[initArcs];
        capacity = new int[initArcs];
        residual = new int[initArcs];
    }

    /** Adds a node and returns its id. */
    public int addNode() {
        if (nodeCount == head.length) {
            int old = head.length;
            head = Arrays.copyOf(head, old * 2);
            Arrays.fill(head, old, head.length, -1);
        }
        return nodeCount++;
    }

    /**
     * Adds a directed edge u -> v with the given capacity (and its residual
     * reverse arc). Returns the index of the forward arc.
     */
    public int addEdge(int u, int v, int cap) {
        checkNode(u);
        checkNode(v);
        if (cap < 0) throw new IllegalArgumentException("capacity must be >= 0, got " + cap);
        ensureArcCapacity(arcCount + 2);
        int e = arcCount;
        link(e, u, v, cap);
        link(e + 1, v, u, 0);
        arcCount += 2;
        return e;
    }

    private void link(int e, int from, int target, int cap) {
        to[e] = target;
        capacity[e] = cap;
        residual[e] = cap;
        next[e] = head[from];
        head[from] = e;
    }

    private void ensureArcCapacity(int needed) {
        if (needed <= to.length) return;
        int n = Math.max(needed, to.length * 2);
        next = Arrays.copyOf(next, n);
        to = Arrays.copyOf(to, n);
        capacity = Arrays.copyOf(capacity, n);
        residual = Arrays.copyOf(residual, n);
    }

    private void checkNode(int v) {
        if (v < 0 || v >= nodeCount) throw new IndexOutOfBoundsException("node " + v + " not in [0," + nodeCount + ")");
    }

    // ---- accessors used by solvers -------------------------------------------------

    public int nodeCount() { return nodeCount; }
    public int arcCount() { return arcCount; }
    /** Number of user-added (forward) edges. */
    public int edgeCount() { return arcCount / 2; }
    public int firstArc(int v) { return head[v]; }
    public int nextArc(int e) { return next[e]; }
    public int target(int e) { return to[e]; }
    public int residual(int e) { return residual[e]; }
    public int capacity(int e) { return capacity[e]; }

    /** Flow currently on forward arc e (e must be even). */
    public int flow(int e) {
        if ((e & 1) != 0) throw new IllegalArgumentException("flow() expects a forward arc index");
        return capacity[e] - residual[e];
    }

    /** Pushes {@code amount} units along arc e (updates the paired reverse arc). */
    void push(int e, int amount) {
        residual[e] -= amount;
        residual[e ^ 1] += amount;
    }

    /** Source node of arc e (the target of its paired arc). */
    public int source(int e) { return to[e ^ 1]; }

    /** Resets all flow to zero, keeping the topology. */
    public void resetFlow() {
        System.arraycopy(capacity, 0, residual, 0, arcCount);
    }
}

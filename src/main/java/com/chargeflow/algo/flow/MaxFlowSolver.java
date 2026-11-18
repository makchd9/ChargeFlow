package com.chargeflow.algo.flow;

/**
 * A Ford–Fulkerson style max-flow solver. Implementations augment the flow
 * already present in the network, so calling {@link #augment} again after
 * adding edges continues from the existing flow instead of starting over.
 */
public interface MaxFlowSolver {

    /**
     * Repeatedly finds augmenting paths from s to t and pushes flow along them
     * until none remain.
     *
     * @return the amount of flow added by this call
     */
    long augment(FlowNetwork g, int s, int t);

    /** Number of augmenting paths found over the solver's lifetime. */
    long augmentingPaths();

    /** Number of arcs inspected while searching (a machine-independent work measure). */
    long arcScans();

    String name();
}

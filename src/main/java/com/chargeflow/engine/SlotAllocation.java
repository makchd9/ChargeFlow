package com.chargeflow.engine;

import java.util.List;

/**
 * Raw output of an allocator: for every vehicle (by request position), the
 * sorted list of slots in which it charges, plus work counters.
 *
 * @param slotsByVehicle   slotsByVehicle.get(i) = slots granted to request i
 * @param tierServed       slots served per tier, measured after the final phase
 * @param tierServedAfterOwnPhase slots served per tier at the end of that tier's own phase
 * @param minCut           min-cut decomposition (null for the greedy baseline)
 * @param nodes            network nodes (0 for greedy)
 * @param edges            network edges (0 for greedy)
 * @param augmentingPaths  augmenting paths found (0 for greedy)
 * @param arcScans         arcs inspected during the search (greedy: slot probes)
 * @param nanos            wall time of the allocation step
 */
public record SlotAllocation(
        List<int[]> slotsByVehicle,
        long[] tierServed,
        long[] tierServedAfterOwnPhase,
        CutInfo minCut,
        int nodes,
        int edges,
        long augmentingPaths,
        long arcScans,
        long nanos) {

    public long totalServed() {
        long s = 0;
        for (int[] a : slotsByVehicle) s += a.length;
        return s;
    }

    /**
     * Min-cut view in domain terms. Max-flow = cutDemandSlots + cutCapacitySlots, where
     * cutDemandSlots is the demand of vehicles whose source arc is in the cut (fully served
     * vehicles that are no longer reachable) and cutCapacitySlots is the capacity of the
     * (connector, slot) arcs into the sink that are in the cut (the binding capacity).
     *
     * @param capacity          total cut capacity (equals max-flow value)
     * @param cutDemandSlots    sum over cut source-arcs
     * @param cutCapacitySlots  sum over cut capacity arcs (station-slot -> sink, and the few vehicle -> station-slot unit arcs)
     * @param bindingStationSlots  keys "CONNECTOR@slot" of station-slot arcs in the cut
     */
    public record CutInfo(long capacity, long cutDemandSlots, long cutCapacitySlots, List<String> bindingStationSlots) { }
}

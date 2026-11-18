package com.chargeflow.engine;

import com.chargeflow.algo.flow.*;
import com.chargeflow.domain.ConnectorType;
import com.chargeflow.domain.HubConfig;
import com.chargeflow.domain.VehicleRequest;

import java.util.*;

/**
 * Turns a validated charging problem into slot assignments.
 *
 * <h2>Flow model</h2>
 * <pre>
 *   source --(demand_v)--> vehicle v --(1)--> (connector(v), t) --(free modules at t)--> sink
 *                                     for every slot t in v's parked window
 * </pre>
 * <ul>
 *   <li>source -> v has capacity = slots v needs (capped by its window length),</li>
 *   <li>v -> (c, t) has capacity 1: a vehicle charges on at most one module per slot,</li>
 *   <li>(c, t) -> sink has capacity = modules of type c not under maintenance at t.</li>
 * </ul>
 * An integral flow of value F is exactly a schedule delivering F charging slots
 * that respects every capacity: integrality theorem plus flow conservation.
 * A maximum flow therefore maximises charging slots delivered (energy throughput).
 *
 * <h2>Priority tiers</h2>
 * Source arcs are added tier by tier, and the solver is resumed after each tier.
 * An augmenting path is a simple s-t path, so it leaves s through exactly one arc
 * and never sends flow back out of s. Flow already on tier-1 source arcs is
 * therefore never reduced by later phases: tier 1 gets the maximum it could get
 * if it were alone, and lower tiers use whatever capacity remains, to the overall maximum.
 */
public final class AllocationEngine {

    private AllocationEngine() { }

    /** Index of connector type in the station-slot node block. */
    private static int ci(ConnectorType c) { return c.ordinal(); }

    /** capacity[c][t] = modules of connector c available in slot t. */
    public static int[][] capacities(HubConfig hub) {
        int T = hub.slotCount();
        int[][] cap = new int[ConnectorType.values().length][T];
        for (HubConfig.Charger ch : hub.chargers())
            for (int t = 0; t < T; t++)
                if (ch.availableAt(t)) cap[ci(ch.connector())][t]++;
        return cap;
    }

    /**
     * Max-flow allocation with priority tiers.
     *
     * @param tiers  tiers.get(0) = request indices in the highest-priority tier, and so on
     */
    public static SlotAllocation maxFlow(HubConfig hub, List<VehicleRequest> reqs, List<List<Integer>> tiers, MaxFlowSolver solver) {
        long t0 = System.nanoTime();
        int T = hub.slotCount();
        int C = ConnectorType.values().length;
        int V = reqs.size();
        int[][] cap = capacities(hub);

        final int S = 0, SINK = 1, VBASE = 2, SBASE = 2 + V;
        FlowNetwork g = new FlowNetwork(SBASE + C * T);
        // station-slot -> sink
        int[] stationArc = new int[C * T];
        Arrays.fill(stationArc, -1);
        for (int c = 0; c < C; c++)
            for (int t = 0; t < T; t++)
                if (cap[c][t] > 0) stationArc[c * T + t] = g.addEdge(SBASE + c * T + t, SINK, cap[c][t]);

        // vehicle -> station-slot (unit arcs)
        int[][] vehicleArcs = new int[V][];
        int[][] vehicleArcSlot = new int[V][];
        for (int i = 0; i < V; i++) {
            VehicleRequest r = reqs.get(i);
            if (!r.feasible()) { vehicleArcs[i] = new int[0]; vehicleArcSlot[i] = new int[0]; continue; }
            int c = ci(r.vehicle().connector());
            int[] arcs = new int[r.windowSlots()];
            int[] slots = new int[r.windowSlots()];
            int n = 0;
            for (int t = r.arrivalSlot(); t < r.departSlot(); t++) {
                if (cap[c][t] == 0) continue;
                arcs[n] = g.addEdge(VBASE + i, SBASE + c * T + t, 1);
                slots[n++] = t;
            }
            vehicleArcs[i] = Arrays.copyOf(arcs, n);
            vehicleArcSlot[i] = Arrays.copyOf(slots, n);
        }

        // tiered source arcs + resumed augmentation
        int[] sourceArc = new int[V];
        Arrays.fill(sourceArc, -1);
        long[] afterOwn = new long[tiers.size()];
        for (int k = 0; k < tiers.size(); k++) {
            for (int i : tiers.get(k)) {
                int d = reqs.get(i).cappedDemand();
                if (d > 0 && vehicleArcs[i].length > 0) sourceArc[i] = g.addEdge(S, VBASE + i, d);
            }
            solver.augment(g, S, SINK);
            afterOwn[k] = served(g, tiers.get(k), sourceArc);
        }
        long[] tierServed = new long[tiers.size()];
        for (int k = 0; k < tiers.size(); k++) tierServed[k] = served(g, tiers.get(k), sourceArc);

        // decode: unit arcs carrying flow = granted slots (already in ascending slot order)
        List<int[]> slotsByVehicle = new ArrayList<>(V);
        for (int i = 0; i < V; i++) {
            int[] arcs = vehicleArcs[i];
            int[] tmp = new int[arcs.length];
            int n = 0;
            for (int j = 0; j < arcs.length; j++) if (g.flow(arcs[j]) == 1) tmp[n++] = vehicleArcSlot[i][j];
            slotsByVehicle.add(Arrays.copyOf(tmp, n));
        }

        // min cut in domain terms
        MinCut cut = MinCut.of(g, S);
        long cutDemand = 0, cutCap = 0;
        List<String> binding = new ArrayList<>();
        ConnectorType[] types = ConnectorType.values();
        for (int e : cut.cutArcs()) {
            int u = g.source(e);
            if (u == S) cutDemand += g.capacity(e);
            else if (u >= SBASE) {
                cutCap += g.capacity(e);
                int idx = u - SBASE;
                binding.add(types[idx / T].name() + "@" + (idx % T));
            } else {
                // vehicle -> station arc in the cut (capacity 1)
                cutCap += g.capacity(e);
            }
        }
        long nanos = System.nanoTime() - t0;
        return new SlotAllocation(slotsByVehicle, tierServed, afterOwn,
                new SlotAllocation.CutInfo(cut.capacity(), cutDemand, cutCap, binding),
                g.nodeCount(), g.edgeCount(), solver.augmentingPaths(), solver.arcScans(), nanos);
    }

    private static long served(FlowNetwork g, List<Integer> tier, int[] sourceArc) {
        long s = 0;
        for (int i : tier) if (sourceArc[i] >= 0) s += g.flow(sourceArc[i]);
        return s;
    }

    /**
     * Baseline: greedy first-come-first-served. Tiers are processed in order;
     * within a tier, vehicles are taken by arrival time and each one grabs the
     * earliest free slots in its window until its demand is met. O(V * W) for
     * window length W. This is what a simple "book the next free charger" app does.
     */
    public static SlotAllocation greedy(HubConfig hub, List<VehicleRequest> reqs, List<List<Integer>> tiers) {
        long t0 = System.nanoTime();
        int[][] cap = capacities(hub);
        int[][] used = new int[cap.length][hub.slotCount()];
        int[][] granted = new int[reqs.size()][];
        long probes = 0;
        long[] tierServed = new long[tiers.size()];
        for (int k = 0; k < tiers.size(); k++) {
            List<Integer> order = new ArrayList<>(tiers.get(k));
            order.sort(Comparator.comparingInt((Integer i) -> reqs.get(i).arrivalSlot()).thenComparingInt(i -> i));
            for (int i : order) {
                VehicleRequest r = reqs.get(i);
                int need = r.cappedDemand();
                int[] got = new int[need];
                int n = 0;
                int c = ci(r.vehicle().connector());
                for (int t = r.arrivalSlot(); t < r.departSlot() && n < need; t++) {
                    probes++;
                    if (used[c][t] < cap[c][t]) { used[c][t]++; got[n++] = t; }
                }
                granted[i] = Arrays.copyOf(got, n);
                tierServed[k] += n;
            }
        }
        List<int[]> slots = new ArrayList<>(reqs.size());
        for (int i = 0; i < reqs.size(); i++) slots.add(granted[i] == null ? new int[0] : granted[i]);
        return new SlotAllocation(slots, tierServed, tierServed.clone(), null, 0, 0, 0, probes, System.nanoTime() - t0);
    }
}

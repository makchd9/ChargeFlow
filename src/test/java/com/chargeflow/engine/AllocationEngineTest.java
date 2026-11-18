package com.chargeflow.engine;

import com.chargeflow.algo.flow.EdmondsKarp;
import com.chargeflow.algo.flow.FordFulkersonDfs;
import com.chargeflow.data.SyntheticData;
import com.chargeflow.domain.*;
import com.chargeflow.service.ValidationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Domain-level correctness of the tiered max-flow allocator and the greedy baseline. */
class AllocationEngineTest {

    private final ValidationService vs = new ValidationService();

    private static HubConfig hub(int ccs, int slots) {
        List<HubConfig.Charger> ch = new ArrayList<>();
        for (int i = 0; i < ccs; i++) ch.add(new HubConfig.Charger("C" + i, ConnectorType.CCS2, List.of()));
        return new HubConfig("test", "08:00", 60, slots, Map.of(ConnectorType.CCS2, 10.0), ch);
    }

    /** 10 kW for 1 h = 10 kWh per slot, so energy 10*k means k slots. */
    private static Vehicle v(String plate, String arr, String dep, int slots, VehicleCategory cat) {
        return new Vehicle(plate, "m", ConnectorType.CCS2, arr, dep, 10.0 * slots, 50, null, cat);
    }

    private List<VehicleRequest> reqs(HubConfig h, List<Vehicle> vs0) {
        return vs.validate(h, vs0, AllocationOptions.defaults()).requests();
    }

    private static List<List<Integer>> oneTier(int n) {
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < n; i++) all.add(i);
        return List.of(all);
    }

    @Test
    @DisplayName("1. hand-checked instance where greedy FCFS is sub-optimal and max-flow is optimal")
    void greedyTrap() {
        // 1 charger, slots 08-09, 09-10. A (08-10) needs 1 slot, B (08-09) needs 1 slot.
        // Greedy by arrival gives A the 08 slot, and B (gone at 09) gets nothing: 1 slot served.
        // Max flow moves A to 09 and serves both: 2 slots.
        HubConfig h = hub(1, 2);
        List<VehicleRequest> r = reqs(h, List.of(
                v("MH12AA0001", "08:00", "10:00", 1, VehicleCategory.PRIVATE),
                v("MH12AA0002", "08:00", "09:00", 1, VehicleCategory.PRIVATE)));
        assertEquals(1, AllocationEngine.greedy(h, r, oneTier(2)).totalServed());
        SlotAllocation mf = AllocationEngine.maxFlow(h, r, oneTier(2), new EdmondsKarp());
        assertEquals(2, mf.totalServed());
        assertArrayEquals(new int[]{1}, mf.slotsByVehicle().get(0));
        assertArrayEquals(new int[]{0}, mf.slotsByVehicle().get(1));
        assertEquals(2, mf.minCut().capacity());
    }

    @Test
    @DisplayName("2. edge case: demand larger than the window is capped, never over-served")
    void demandExceedsWindow() {
        HubConfig h = hub(2, 4);
        List<VehicleRequest> r = reqs(h, List.of(v("MH12AA0003", "08:00", "10:00", 5, VehicleCategory.PRIVATE)));
        assertEquals(2, r.get(0).cappedDemand());
        SlotAllocation mf = AllocationEngine.maxFlow(h, r, oneTier(1), new EdmondsKarp());
        assertEquals(2, mf.totalServed());
    }

    @Test
    @DisplayName("3. infeasible vehicle (no compatible connector) gets nothing and is flagged")
    void infeasibleConnector() {
        HubConfig h = hub(1, 4);
        Vehicle ac = new Vehicle("MH12AA0004", "m", ConnectorType.TYPE2_AC, "08:00", "12:00", 10, 50, null, VehicleCategory.PRIVATE);
        List<VehicleRequest> r = reqs(h, List.of(ac));
        assertFalse(r.get(0).feasible());
        assertEquals(0, AllocationEngine.maxFlow(h, r, oneTier(1), new EdmondsKarp()).totalServed());
    }

    @Test
    @DisplayName("4. maintenance windows remove capacity from the network")
    void maintenance() {
        HubConfig h = new HubConfig("t", "08:00", 60, 3, Map.of(ConnectorType.CCS2, 10.0),
                List.of(new HubConfig.Charger("C0", ConnectorType.CCS2, List.of(new HubConfig.SlotRange(0, 2)))));
        List<VehicleRequest> r = reqs(h, List.of(v("MH12AA0005", "08:00", "11:00", 3, VehicleCategory.PRIVATE)));
        SlotAllocation mf = AllocationEngine.maxFlow(h, r, oneTier(1), new EdmondsKarp());
        assertArrayEquals(new int[]{2}, mf.slotsByVehicle().get(0));
    }

    @Test
    @DisplayName("5. priority tier keeps its standalone optimum after lower tiers are added")
    void tierPreservation() {
        // 1 charger, 1 slot. Tier 1 = emergency vehicle, tier 2 = private car arriving at the same time.
        HubConfig h = hub(1, 1);
        List<VehicleRequest> r = reqs(h, List.of(
                v("MH12AA0006", "08:00", "09:00", 1, VehicleCategory.PRIVATE),
                v("MH12AA0007", "08:00", "09:00", 1, VehicleCategory.EMERGENCY)));
        SlotAllocation mf = AllocationEngine.maxFlow(h, r, List.of(List.of(1), List.of(0)), new EdmondsKarp());
        assertEquals(1, mf.slotsByVehicle().get(1).length);
        assertEquals(0, mf.slotsByVehicle().get(0).length);
        assertEquals(mf.tierServedAfterOwnPhase()[0], mf.tierServed()[0]);
    }

    @ParameterizedTest(name = "6. stress seed {0}: capacity, windows, no double-booking, flow >= greedy")
    @ValueSource(longs = {11, 22, 33, 44, 55})
    void randomInvariants(long seed) {
        HubConfig h = SyntheticData.defaultHub();
        List<Vehicle> vehicles = SyntheticData.vehicles(260, seed, 0.3, List.of(), h);
        List<VehicleRequest> r = reqs(h, vehicles);
        PriorityTiering.Result pt = PriorityTiering.tier(r, i -> false, 0.2, seed);

        SlotAllocation ek = AllocationEngine.maxFlow(h, r, pt.tiers(), new EdmondsKarp());
        SlotAllocation ff = AllocationEngine.maxFlow(h, r, pt.tiers(), new FordFulkersonDfs());
        SlotAllocation gr = AllocationEngine.greedy(h, r, pt.tiers());
        assertEquals(ek.totalServed(), ff.totalServed(), "EK and FF agree on the optimum");
        assertTrue(ek.totalServed() >= gr.totalServed(), "max-flow never worse than greedy");
        assertEquals(ek.minCut().capacity(), ek.totalServed(), "max-flow = min-cut");
        assertEquals(ek.tierServedAfterOwnPhase()[0], ek.tierServed()[0], "tier-1 preserved");

        int[][] cap = AllocationEngine.capacities(h);
        int[][] used = new int[cap.length][h.slotCount()];
        for (int i = 0; i < r.size(); i++) {
            VehicleRequest q = r.get(i);
            int[] s = ek.slotsByVehicle().get(i);
            assertTrue(s.length <= q.cappedDemand(), "never above demand");
            for (int j = 0; j < s.length; j++) {
                assertTrue(s[j] >= q.arrivalSlot() && s[j] < q.departSlot(), "inside window");
                if (j > 0) assertTrue(s[j] > s[j - 1], "one module per slot per vehicle");
                used[q.vehicle().connector().ordinal()][s[j]]++;
            }
        }
        for (int c = 0; c < cap.length; c++)
            for (int t = 0; t < h.slotCount(); t++)
                assertTrue(used[c][t] <= cap[c][t], "capacity at " + c + "@" + t);

        // module assignment is always realisable
        ChargerAssigner.Assignment a = ChargerAssigner.assign(h, r, ek.slotsByVehicle());
        assertNotNull(a);
    }

    @Test
    @DisplayName("7. priority tiering via Quickselect selects exactly k vehicles, all with score >= threshold")
    void tieringExactK() {
        HubConfig h = SyntheticData.defaultHub();
        List<VehicleRequest> r = reqs(h, SyntheticData.vehicles(200, 7, 0, List.of(), h));
        PriorityTiering.Result pt = PriorityTiering.tier(r, i -> false, 0.25, 1);
        long feasible = r.stream().filter(VehicleRequest::feasible).count();
        assertEquals((int) Math.ceil(0.25 * feasible), pt.tiers().get(0).size());
        double minT1 = pt.tiers().get(0).stream().mapToDouble(i -> pt.scores()[i]).min().orElseThrow();
        double maxT2 = pt.tiers().get(1).stream().filter(i -> r.get(i).feasible()).mapToDouble(i -> pt.scores()[i]).max().orElseThrow();
        assertEquals(pt.threshold(), minT1, 1e-9);
        assertTrue(maxT2 <= minT1, "no tier-2 vehicle outranks tier 1");
    }
}

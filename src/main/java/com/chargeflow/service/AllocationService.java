package com.chargeflow.service;

import com.chargeflow.algo.flow.EdmondsKarp;
import com.chargeflow.algo.flow.FordFulkersonDfs;
import com.chargeflow.algo.flow.MaxFlowSolver;
import com.chargeflow.algo.select.RandomizedQuickselect;
import com.chargeflow.domain.*;
import com.chargeflow.domain.AllocationResult.*;
import com.chargeflow.engine.*;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Application service for one allocation run:
 * validate -> registry screening (Bloom + exact) -> priority tiers (Quickselect)
 * -> tiered max-flow (or baseline) -> module assignment -> interpretable result.
 */
@Service
public class AllocationService {

    private final ValidationService validation;
    private final RegistryService registry;

    public AllocationService(ValidationService validation, RegistryService registry) {
        this.validation = validation;
        this.registry = registry;
    }

    public AllocationResult allocate(HubConfig hub, List<Vehicle> vehicles, AllocationOptions options) {
        long t0 = System.nanoTime();
        AllocationOptions opts = options == null ? AllocationOptions.defaults() : options;
        ValidationService.Problem p = validation.validate(hub, vehicles, opts);
        List<VehicleRequest> reqs = p.requests();
        List<String> warnings = new ArrayList<>(p.warnings());

        // 1. registry screening: Bloom pre-check, exact confirmation only on hits
        RegistryService.Status[] reg = new RegistryService.Status[reqs.size()];
        long absent = 0, verified = 0, fps = 0;
        for (int i = 0; i < reqs.size(); i++) {
            reg[i] = registry.check(reqs.get(i).vehicle().plate());
            switch (reg[i]) {
                case DEFINITELY_ABSENT -> absent++;
                case VERIFIED -> verified++;
                case FALSE_POSITIVE -> fps++;
            }
        }
        RegistryService.Stats after = registry.stats();
        long checked = reqs.size();
        long lookups = verified + fps;   // exact store is touched only on Bloom hits
        if (fps > 0) warnings.add(fps + " Bloom filter hit(s) were false positives, rejected by the exact registry lookup");

        // 2. priority tiers via randomized Quickselect
        double fraction = opts.priorityFractionOrDefault();
        PriorityTiering.Result tiering = PriorityTiering.tier(reqs, i -> reg[i] == RegistryService.Status.VERIFIED,
                fraction, opts.seedOrDefault());

        // 3. allocate
        AllocationOptions.Algorithm algo = opts.algorithmOrDefault();
        SlotAllocation main = run(algo, p.hub(), reqs, tiering.tiers());
        SlotAllocation base = null;
        if (opts.compareBaselineOrDefault() && algo != AllocationOptions.Algorithm.GREEDY) {
            base = AllocationEngine.greedy(p.hub(), reqs, tiering.tiers());
        }

        // 4. modules + result
        ChargerAssigner.Assignment asg = ChargerAssigner.assign(p.hub(), reqs, main.slotsByVehicle());
        Set<Integer> tier1 = tiering.tiers().size() > 1 ? new HashSet<>(tiering.tiers().get(0)) : Set.of();
        Summary summary = summarise(label(algo), p.hub(), reqs, main, tier1);
        Summary baseSummary = base == null ? null : summarise("Greedy FCFS (baseline)", p.hub(), reqs, base, tier1);
        Comparison cmp = baseSummary == null ? null : new Comparison(
                summary.servedSlots() - baseSummary.servedSlots(),
                round1(summary.energyDeliveredKwh() - baseSummary.energyDeliveredKwh()),
                summary.fullyServed() - baseSummary.fullyServed(),
                summary.tier1Served() - baseSummary.tier1Served(),
                baseSummary.servedSlots() == 0 ? 0 : round1(100.0 * (summary.servedSlots() - baseSummary.servedSlots()) / baseSummary.servedSlots()));

        List<VehicleOutcome> outcomes = outcomes(p.hub(), reqs, main, asg, tiering, tier1, reg);
        List<ChargerRow> schedule = new ArrayList<>();
        for (int m = 0; m < p.hub().chargers().size(); m++) {
            HubConfig.Charger c = p.hub().chargers().get(m);
            schedule.add(new ChargerRow(c.id(), c.connector(), asg.grid()[m]));
        }
        List<SlotLoad> loads = loads(p.hub(), reqs, main);

        // shortfall percentiles (randomized Quickselect again)
        Double sp50 = null, sp90 = null;
        List<Double> shortfalls = new ArrayList<>();
        for (int i = 0; i < reqs.size(); i++) if (reqs.get(i).feasible())
            shortfalls.add((double) (reqs.get(i).slotsNeeded() - main.slotsByVehicle().get(i).length));
        if (!shortfalls.isEmpty()) {
            double[] a = shortfalls.stream().mapToDouble(Double::doubleValue).toArray();
            RandomizedQuickselect qs = new RandomizedQuickselect(opts.seedOrDefault() + 1);
            sp50 = qs.percentile(a, 50);
            sp90 = qs.percentile(a, 90);
        }

        CutSummary cut = null;
        if (main.minCut() != null) {
            SlotAllocation.CutInfo ci = main.minCut();
            cut = new CutSummary(ci.capacity(), main.totalServed(), ci.cutDemandSlots(), ci.cutCapacitySlots(),
                    ci.bindingStationSlots().size(),
                    "Max-flow = min-cut = " + ci.capacity() + " slots. " + ci.cutDemandSlots()
                            + " come from vehicles whose whole demand is met, and " + ci.cutCapacitySlots()
                            + " from saturated charger capacity in " + ci.bindingStationSlots().size()
                            + " (connector, slot) pairs. No schedule can deliver more with the current capacity: extra modules would be needed in those binding slots.");
        }
        if (tiering.tiers().size() > 1 && main.tierServedAfterOwnPhase()[0] != main.tierServed()[0]) {
            warnings.add("internal: tier-1 service changed after later phases");  // never expected; surfaced, not hidden
        }

        Registry regOut = new Registry(checked, absent, verified, fps, lookups, checked - lookups,
                after.registeredPlates(), after.bitCount(), after.hashCount(), after.theoreticalFpr());
        Priority pr = new Priority(fraction, tiering.tiers().size() > 1 ? tiering.tiers().get(0).size() : 0,
                nanToNull(tiering.threshold()), nanToNull(tiering.p50()), nanToNull(tiering.p90()),
                tiering.comparisons(), "randomized Quickselect, 3-way partition, seed " + opts.seedOrDefault());
        Stats stats = new Stats(main.nodes(), main.edges(), main.augmentingPaths(), main.arcScans(),
                round3(main.nanos() / 1e6), round3((System.nanoTime() - t0) / 1e6), sp50, sp90);

        List<String> labels = new ArrayList<>();
        for (int t = 0; t < p.hub().slotCount(); t++) labels.add(p.hub().slotLabel(t));
        return new AllocationResult(label(algo), p.hub().name(), labels, summary, baseSummary, cmp, pr, regOut, cut,
                stats, outcomes, schedule, loads, warnings);
    }

    public static SlotAllocation run(AllocationOptions.Algorithm algo, HubConfig hub, List<VehicleRequest> reqs, List<List<Integer>> tiers) {
        return switch (algo) {
            case EDMONDS_KARP -> AllocationEngine.maxFlow(hub, reqs, tiers, new EdmondsKarp());
            case FORD_FULKERSON_DFS -> AllocationEngine.maxFlow(hub, reqs, tiers, new FordFulkersonDfs());
            case GREEDY -> AllocationEngine.greedy(hub, reqs, tiers);
        };
    }

    private static String label(AllocationOptions.Algorithm a) {
        return switch (a) {
            case EDMONDS_KARP -> "Edmonds-Karp max-flow (tiered)";
            case FORD_FULKERSON_DFS -> "Ford-Fulkerson DFS max-flow (tiered)";
            case GREEDY -> "Greedy FCFS (baseline)";
        };
    }

    static Summary summarise(String name, HubConfig hub, List<VehicleRequest> reqs, SlotAllocation a, Set<Integer> tier1) {
        long requested = 0, servable = 0, served = 0, t1d = 0, t1s = 0;
        int feasible = 0, full = 0, partial = 0, none = 0;
        double eReq = 0, eDel = 0;
        for (int i = 0; i < reqs.size(); i++) {
            VehicleRequest r = reqs.get(i);
            int got = a.slotsByVehicle().get(i).length;
            requested += r.slotsNeeded();
            servable += r.cappedDemand();
            served += got;
            eReq += r.vehicle().energyKwh();
            eDel += delivered(r, got, hub);
            if (r.feasible()) feasible++;
            if (got >= r.slotsNeeded()) full++;
            else if (got > 0) partial++;
            else none++;
            if (tier1.contains(i)) { t1d += r.cappedDemand(); t1s += got; }
        }
        long capacity = 0;
        for (int[] row : AllocationEngine.capacities(hub)) for (int c : row) capacity += c;
        return new Summary(name, reqs.size(), feasible, requested, servable, served, full, partial, none,
                round1(eReq), round1(eDel), capacity == 0 ? 0 : round1(100.0 * served / capacity),
                t1d, t1s, round3(a.nanos() / 1e6));
    }

    private static double delivered(VehicleRequest r, int slots, HubConfig hub) {
        return Math.min(r.vehicle().energyKwh(), slots * r.effectiveKw() * hub.slotHours());
    }

    private List<VehicleOutcome> outcomes(HubConfig hub, List<VehicleRequest> reqs, SlotAllocation a,
                                          ChargerAssigner.Assignment asg, PriorityTiering.Result tiering,
                                          Set<Integer> tier1, RegistryService.Status[] reg) {
        List<VehicleOutcome> out = new ArrayList<>(reqs.size());
        for (int i = 0; i < reqs.size(); i++) {
            VehicleRequest r = reqs.get(i);
            int[] slots = a.slotsByVehicle().get(i);
            List<Assignment> as = new ArrayList<>(slots.length);
            int sessions = 0, switches = 0;
            for (int j = 0; j < slots.length; j++) {
                int m = asg.chargerOf()[i][j];
                as.add(new Assignment(slots[j], hub.slotLabel(slots[j]), hub.chargers().get(m).id()));
                if (j == 0 || slots[j] != slots[j - 1] + 1) sessions++;
                if (j > 0 && slots[j] == slots[j - 1] + 1 && asg.chargerOf()[i][j - 1] != m) switches++;
            }
            String status = !r.feasible() ? "INFEASIBLE"
                    : slots.length >= r.slotsNeeded() ? "FULL"
                    : slots.length > 0 ? "PARTIAL" : "UNSERVED";
            out.add(new VehicleOutcome(i, r.vehicle().plate(), r.vehicle().model(), r.vehicle().category(),
                    r.vehicle().connector(), r.vehicle().arrival(), r.vehicle().departure(), r.windowSlots(),
                    r.slotsNeeded(), slots.length, r.vehicle().energyKwh(), round1(delivered(r, slots.length, hub)),
                    status, tier1.isEmpty() ? 1 : (tier1.contains(i) ? 1 : 2), tiering.scores()[i], reg[i].name(),
                    sessions, switches, as, r.note()));
        }
        return out;
    }

    private static List<SlotLoad> loads(HubConfig hub, List<VehicleRequest> reqs, SlotAllocation a) {
        int T = hub.slotCount();
        ConnectorType[] types = ConnectorType.values();
        int[][] cap = AllocationEngine.capacities(hub);
        int[][] used = new int[types.length][T];
        int[][] pressure = new int[types.length][T];
        for (int i = 0; i < reqs.size(); i++) {
            VehicleRequest r = reqs.get(i);
            int c = r.vehicle().connector().ordinal();
            for (int t : a.slotsByVehicle().get(i)) used[c][t]++;
            if (r.feasible()) for (int t = r.arrivalSlot(); t < r.departSlot(); t++) pressure[c][t]++;
        }
        Set<String> binding = new HashSet<>(a.minCut() == null ? List.of() : a.minCut().bindingStationSlots());
        boolean present[] = new boolean[types.length];
        for (HubConfig.Charger ch : hub.chargers()) present[ch.connector().ordinal()] = true;
        List<SlotLoad> out = new ArrayList<>();
        for (int c = 0; c < types.length; c++) {
            if (!present[c]) continue;
            for (int t = 0; t < T; t++)
                out.add(new SlotLoad(types[c], t, hub.slotLabel(t), cap[c][t], used[c][t], pressure[c][t],
                        binding.contains(types[c].name() + "@" + t)));
        }
        return out;
    }

    private static Double nanToNull(double d) { return Double.isNaN(d) ? null : d; }
    private static double round1(double d) { return Math.round(d * 10) / 10.0; }
    private static double round3(double d) { return Math.round(d * 1000) / 1000.0; }
}

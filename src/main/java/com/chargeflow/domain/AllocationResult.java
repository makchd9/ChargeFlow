package com.chargeflow.domain;

import java.util.List;

/** Everything the UI needs to explain one allocation run. */
public record AllocationResult(
        String algorithm,
        String hubName,
        List<String> slotLabels,
        Summary summary,
        Summary baseline,
        Comparison comparison,
        Priority priority,
        Registry registry,
        CutSummary minCut,
        Stats stats,
        List<VehicleOutcome> vehicles,
        List<ChargerRow> schedule,
        List<SlotLoad> loads,
        List<String> warnings) {

    public record Summary(String algorithm, int vehicles, int feasibleVehicles, long requestedSlots, long servableDemandSlots,
                          long servedSlots, int fullyServed, int partiallyServed, int unserved,
                          double energyRequestedKwh, double energyDeliveredKwh, double utilisationPct,
                          long tier1Demand, long tier1Served, double runtimeMs) { }

    public record Comparison(long extraSlots, double extraEnergyKwh, int extraFullyServed,
                             long extraTier1Slots, double servedGainPct) { }

    public record Priority(double fraction, int tierSize, Double threshold, Double p50Score, Double p90Score,
                           long quickselectComparisons, String method) { }

    public record Registry(long checked, long definitelyAbsent, long verified, long falsePositivesCaught,
                           long exactLookups, long exactLookupsAvoided, int registeredPlates,
                           long bloomBits, int bloomHashes, double theoreticalFpr) { }

    public record CutSummary(long capacity, long servedSlots, long cutDemandSlots, long cutCapacitySlots,
                             int bindingStationSlots, String explanation) { }

    public record Stats(int nodes, int edges, long augmentingPaths, long arcScans, double allocationMs,
                        double totalMs, Double shortfallP50, Double shortfallP90) { }

    public record Assignment(int slot, String time, String chargerId) { }

    public record VehicleOutcome(int index, String plate, String model, VehicleCategory category, ConnectorType connector,
                                 String arrival, String departure, int windowSlots, int slotsNeeded, int slotsServed,
                                 double energyRequestedKwh, double energyDeliveredKwh, String status, int tier,
                                 double priorityScore, String registry, int sessions, int moduleSwitches,
                                 List<Assignment> assignments, String note) { }

    /** cells[t] = vehicle index, -1 idle, -2 maintenance. */
    public record ChargerRow(String chargerId, ConnectorType connector, int[] cells) { }

    public record SlotLoad(ConnectorType connector, int slot, String time, int capacity, int used,
                           int demandPressure, boolean binding) { }
}

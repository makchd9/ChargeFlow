package com.chargeflow.domain;

/**
 * A validated vehicle converted to slot units, the form the allocators work on.
 *
 * @param index        position in the input list
 * @param vehicle      the original (normalised) input
 * @param arrivalSlot  first slot the vehicle can charge in (inclusive)
 * @param departSlot   first slot the vehicle is gone (exclusive)
 * @param effectiveKw  min(charger rating, vehicle max acceptance)
 * @param slotsNeeded  ceil(energyKwh / (effectiveKw * slotHours))
 * @param feasible     false when no charger of the vehicle's type exists or its window is empty
 * @param note         human-readable reason when not feasible or clipped
 */
public record VehicleRequest(
        int index,
        Vehicle vehicle,
        int arrivalSlot,
        int departSlot,
        double effectiveKw,
        int slotsNeeded,
        boolean feasible,
        String note) {

    public int windowSlots() { return Math.max(0, departSlot - arrivalSlot); }

    /** Demand the network can possibly satisfy: you cannot charge more slots than you are parked. */
    public int cappedDemand() { return feasible ? Math.min(slotsNeeded, windowSlots()) : 0; }
}

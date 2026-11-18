package com.chargeflow.domain;

/**
 * A vehicle requesting charge at the hub (input record).
 *
 * @param plate         registration number, e.g. "MH12AB1234" (normalised: upper case, no spaces)
 * @param model         free-text model name (display only)
 * @param connector     the connector the vehicle uses at this hub
 * @param arrival       arrival time "HH:mm"
 * @param departure     departure time "HH:mm" (must be after arrival)
 * @param energyKwh     energy requested in kWh (> 0)
 * @param batteryPct    state of charge on arrival, 0..100
 * @param maxChargeKw   maximum power the vehicle accepts; null or 0 means the charger's rating
 * @param category      EMERGENCY, FLEET or PRIVATE
 */
public record Vehicle(
        String plate,
        String model,
        ConnectorType connector,
        String arrival,
        String departure,
        double energyKwh,
        double batteryPct,
        Double maxChargeKw,
        VehicleCategory category) {
}

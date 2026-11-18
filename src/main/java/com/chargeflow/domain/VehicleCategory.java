package com.chargeflow.domain;

/** Operational category of an arriving vehicle; feeds the priority score. */
public enum VehicleCategory {
    EMERGENCY(100),   // ambulances, police, utility response
    FLEET(40),        // ride-hailing / logistics fleets with SLAs
    PRIVATE(0);       // walk-in private cars

    private final int weight;

    VehicleCategory(int weight) { this.weight = weight; }

    public int weight() { return weight; }

    public static VehicleCategory parse(String s) {
        if (s == null || s.isBlank()) return PRIVATE;
        try {
            return valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown category '" + s + "' (expected EMERGENCY, FLEET or PRIVATE)");
        }
    }
}

package com.chargeflow.domain;

/** Charging connector standards common at Indian public hubs. */
public enum ConnectorType {
    /** CCS2 DC fast charging (Tata Nexon EV, MG ZS EV, Mahindra XUV400, Hyundai Kona ...). */
    CCS2(60.0),
    /** Type 2 AC (IEC 62196-2) destination charging. */
    TYPE2_AC(22.0),
    /** Bharat DC-001 (GB/T-based low-voltage DC, common for older fleet cars). */
    BHARAT_DC001(15.0);

    private final double defaultPowerKw;

    ConnectorType(double defaultPowerKw) { this.defaultPowerKw = defaultPowerKw; }

    public double defaultPowerKw() { return defaultPowerKw; }

    public static ConnectorType parse(String s) {
        if (s == null) throw new IllegalArgumentException("connector is missing");
        String n = s.trim().toUpperCase().replace('-', '_').replace(' ', '_');
        switch (n) {
            case "CCS", "CCS2": return CCS2;
            case "TYPE2", "TYPE_2", "TYPE2_AC", "AC": return TYPE2_AC;
            case "BHARAT", "BHARAT_DC", "BHARAT_DC001", "BHARAT_DC_001", "GBT", "GB_T": return BHARAT_DC001;
            default: throw new IllegalArgumentException("unknown connector '" + s + "' (expected CCS2, TYPE2_AC or BHARAT_DC001)");
        }
    }
}

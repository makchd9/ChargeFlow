package com.chargeflow.data;

import com.chargeflow.domain.*;

import java.util.*;

/**
 * Seeded generator of realistic synthetic data: Indian registration plates and a
 * day of arrivals at an urban charging hub. The arrival profile is bimodal
 * (morning and evening commuter peaks plus a midday fleet plateau), and the
 * vehicle mix reflects common Indian EVs. Every run with the same seed gives the
 * same data, so experiments are reproducible.
 */
public final class SyntheticData {

    private SyntheticData() { }

    private static final String[] STATES = {"MH", "MH", "MH", "KA", "DL", "GJ", "TN", "TS", "HR", "UP", "RJ", "KL"};
    private static final String LETTERS = "ABCDEFGHJKLMNPRSTUVWXYZ";

    /** One random plate such as MH12AB1234. */
    public static String plate(Random r) {
        String st = STATES[r.nextInt(STATES.length)];
        int rto = 1 + r.nextInt(50);
        int series = 1 + r.nextInt(2);
        StringBuilder sb = new StringBuilder(st).append(String.format("%02d", rto));
        for (int i = 0; i < series; i++) sb.append(LETTERS.charAt(r.nextInt(LETTERS.length())));
        sb.append(String.format("%04d", 1 + r.nextInt(9999)));
        return sb.toString();
    }

    /** n distinct plates, none of which appear in {@code exclude}. */
    public static List<String> plates(int n, Random r, Set<String> exclude) {
        LinkedHashSet<String> out = new LinkedHashSet<>(n * 2);
        while (out.size() < n) {
            String p = plate(r);
            if (!exclude.contains(p)) out.add(p);
        }
        return new ArrayList<>(out);
    }

    private record Model(String name, ConnectorType connector, double batteryKwh, double maxKw) { }

    private static final Model[] MODELS = {
            new Model("Tata Nexon EV", ConnectorType.CCS2, 40.5, 50),
            new Model("Tata Punch EV", ConnectorType.CCS2, 35.0, 50),
            new Model("MG ZS EV", ConnectorType.CCS2, 50.3, 76),
            new Model("Mahindra XUV400", ConnectorType.CCS2, 39.4, 50),
            new Model("Hyundai Kona Electric", ConnectorType.CCS2, 39.2, 50),
            new Model("BYD Atto 3", ConnectorType.CCS2, 60.5, 80),
            new Model("Tata Tiago EV", ConnectorType.TYPE2_AC, 24.0, 7.2),
            new Model("MG Comet EV", ConnectorType.TYPE2_AC, 17.3, 7.4),
            new Model("Citroen eC3", ConnectorType.TYPE2_AC, 29.2, 7.4),
            new Model("Tata Tigor EV (fleet)", ConnectorType.BHARAT_DC001, 21.5, 15),
            new Model("Mahindra eVerito (fleet)", ConnectorType.BHARAT_DC001, 21.2, 15),
    };
    private static final double[] MODEL_WEIGHTS = {14, 9, 6, 7, 4, 3, 12, 6, 5, 9, 5};

    /**
     * A day of charging requests.
     *
     * @param count            number of vehicles
     * @param seed             RNG seed
     * @param registeredShare  fraction of vehicles whose plate is taken from {@code registry}
     * @param registry         subscriber plates (may be empty)
     * @param hub              hub, used for the opening hours
     */
    public static List<Vehicle> vehicles(int count, long seed, double registeredShare, List<String> registry, HubConfig hub) {
        if (count <= 0) throw new IllegalArgumentException("count must be > 0");
        Random r = new Random(seed);
        Set<String> used = new HashSet<>();
        Set<String> regSet = new HashSet<>(registry);
        int open = hub.open().toSecondOfDay() / 60;
        int close = open + hub.slotMinutes() * hub.slotCount();
        double totalW = Arrays.stream(MODEL_WEIGHTS).sum();
        List<Vehicle> out = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            // plate: registered subscriber or walk-in
            String plate;
            if (!registry.isEmpty() && r.nextDouble() < registeredShare) {
                do { plate = registry.get(r.nextInt(registry.size())); } while (used.contains(plate) && used.size() < registry.size());
                if (used.contains(plate)) plate = freshPlate(r, used, regSet);
            } else {
                plate = freshPlate(r, used, regSet);
            }
            used.add(plate);

            Model m = pick(r, totalW);
            VehicleCategory cat;
            double u = r.nextDouble();
            if (u < 0.04) cat = VehicleCategory.EMERGENCY;
            else if (u < 0.30 || m.connector == ConnectorType.BHARAT_DC001) cat = VehicleCategory.FLEET;
            else cat = VehicleCategory.PRIVATE;

            // arrival: mixture of morning peak, midday plateau and evening peak (minutes since midnight)
            double which = r.nextDouble();
            double mean, sd;
            if (which < 0.38) { mean = 9 * 60; sd = 55; }
            else if (which < 0.62) { mean = 13 * 60; sd = 90; }
            else { mean = 18 * 60 + 15; sd = 60; }
            int arr = clamp((int) Math.round(mean + sd * r.nextGaussian()), open, close - 45);
            arr = arr / 5 * 5;
            // dwell: fleets are short, private cars longer (lognormal-ish)
            double dwellMean = cat == VehicleCategory.PRIVATE ? 140 : (cat == VehicleCategory.FLEET ? 85 : 60);
            int dwell = (int) Math.round(dwellMean * Math.exp(0.45 * r.nextGaussian()));
            dwell = clamp(dwell, 35, 360) / 5 * 5;
            int dep = Math.min(close, arr + dwell);

            double soc = clamp((int) Math.round(12 + 35 * r.nextDouble() + (cat == VehicleCategory.EMERGENCY ? -5 : 0)), 3, 70);
            double target = 80 + (r.nextBoolean() ? 0 : 10);
            double energy = Math.round(m.batteryKwh * (target - soc) / 100.0 * 10) / 10.0;

            out.add(new Vehicle(plate, m.name, m.connector, hhmm(arr), hhmm(dep), Math.max(1.0, energy),
                    soc, m.maxKw, cat));
        }
        return out;
    }

    private static String freshPlate(Random r, Set<String> used, Set<String> reg) {
        String p;
        do { p = plate(r); } while (used.contains(p) || reg.contains(p));
        return p;
    }

    private static Model pick(Random r, double totalW) {
        double x = r.nextDouble() * totalW;
        for (int i = 0; i < MODELS.length; i++) {
            x -= MODEL_WEIGHTS[i];
            if (x <= 0) return MODELS[i];
        }
        return MODELS[MODELS.length - 1];
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    public static String hhmm(int minutes) { return String.format("%02d:%02d", minutes / 60, minutes % 60); }

    /** Default hub: 06:00-22:00, 30-minute slots, 6x CCS2, 5x Type 2 AC, 3x Bharat DC-001, one CCS2 under midday maintenance. */
    public static HubConfig defaultHub() {
        List<HubConfig.Charger> ch = new ArrayList<>();
        for (int i = 1; i <= 6; i++) ch.add(new HubConfig.Charger(String.format("CCS2-%02d", i), ConnectorType.CCS2,
                i == 3 ? List.of(new HubConfig.SlotRange(12, 16)) : List.of()));
        for (int i = 1; i <= 5; i++) ch.add(new HubConfig.Charger(String.format("AC-%02d", i), ConnectorType.TYPE2_AC, List.of()));
        for (int i = 1; i <= 3; i++) ch.add(new HubConfig.Charger(String.format("BDC-%02d", i), ConnectorType.BHARAT_DC001, List.of()));
        Map<ConnectorType, Double> power = new EnumMap<>(ConnectorType.class);
        power.put(ConnectorType.CCS2, 60.0);
        power.put(ConnectorType.TYPE2_AC, 7.4);
        power.put(ConnectorType.BHARAT_DC001, 15.0);
        return new HubConfig("Baner EV Hub, Pune", "06:00", 30, 32, power, ch);
    }

    /** A hub scaled up for benchmarks: {@code factor} times the default charger count. */
    public static HubConfig scaledHub(int factor) {
        HubConfig base = defaultHub();
        List<HubConfig.Charger> ch = new ArrayList<>();
        for (int f = 0; f < factor; f++)
            for (HubConfig.Charger c : base.chargers())
                ch.add(new HubConfig.Charger(c.id() + "-" + f, c.connector(), c.downSlots()));
        return new HubConfig(base.name() + " x" + factor, base.openTime(), base.slotMinutes(), base.slotCount(), base.powerKw(), ch);
    }
}

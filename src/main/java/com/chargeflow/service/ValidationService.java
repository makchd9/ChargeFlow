package com.chargeflow.service;

import com.chargeflow.domain.*;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Validates and normalises hub + vehicle input before any algorithm runs.
 * Hard errors (bad formats, duplicates, impossible values) reject the request
 * with the full list of problems. Soft issues (a vehicle with no compatible
 * charger, a window clipped to opening hours, more energy requested than the
 * window allows) are kept and reported as warnings, so the result never
 * silently disagrees with the input.
 */
@Service
public class ValidationService {

    public static final int MAX_VEHICLES = 20_000;
    public static final int MAX_CHARGERS = 500;
    public static final int MAX_SLOTS = 288;

    /** Standard Indian plate (e.g. MH12AB1234, DL3CAF0001, KA051234) or Bharat series (22BH1234AA). */
    private static final Pattern PLATE = Pattern.compile("^([A-Z]{2}\\d{1,2}[A-Z]{0,3}\\d{4}|\\d{2}BH\\d{4}[A-Z]{1,2})$");

    public static String normalisePlate(String raw) {
        if (raw == null) return "";
        return raw.toUpperCase(Locale.ROOT).replaceAll("[\\s\\-.]", "");
    }

    public static boolean isValidPlate(String normalised) {
        return PLATE.matcher(normalised).matches();
    }

    public record Problem(HubConfig hub, List<VehicleRequest> requests, List<String> warnings) { }

    public void validateHub(HubConfig hub) {
        List<String> errs = new ArrayList<>();
        if (hub == null) throw new ValidationException("Hub configuration is missing");
        LocalTime open = null;
        try {
            open = LocalTime.parse(hub.openTime());
        } catch (DateTimeParseException | NullPointerException e) {
            errs.add("hub.openTime must be HH:mm, got '" + hub.openTime() + "'");
        }
        if (hub.slotMinutes() < 5 || hub.slotMinutes() > 240) errs.add("hub.slotMinutes must be between 5 and 240");
        if (hub.slotCount() < 1 || hub.slotCount() > MAX_SLOTS) errs.add("hub.slotCount must be between 1 and " + MAX_SLOTS);
        if (open != null && hub.slotMinutes() > 0
                && open.toSecondOfDay() / 60 + (long) hub.slotMinutes() * hub.slotCount() > 24 * 60) {
            errs.add("operating day runs past midnight: openTime + slotMinutes x slotCount must be <= 24:00");
        }
        if (hub.powerKw() != null) {
            hub.powerKw().forEach((k, v) -> {
                if (v == null || v <= 0 || v > 500) errs.add("power for " + k + " must be in (0, 500] kW");
            });
        }
        if (hub.chargers() == null || hub.chargers().isEmpty()) {
            errs.add("hub has no chargers, so no vehicle can be served (infeasible network)");
        } else {
            if (hub.chargers().size() > MAX_CHARGERS) errs.add("at most " + MAX_CHARGERS + " chargers supported");
            Set<String> ids = new HashSet<>();
            for (HubConfig.Charger c : hub.chargers()) {
                if (c.id() == null || c.id().isBlank()) { errs.add("every charger needs an id"); continue; }
                if (!ids.add(c.id())) errs.add("duplicate charger id '" + c.id() + "'");
                if (c.connector() == null) errs.add("charger " + c.id() + " has no connector type");
                if (c.downSlots() != null) for (HubConfig.SlotRange r : c.downSlots()) {
                    if (r.from() < 0 || r.to() > hub.slotCount() || r.from() >= r.to())
                        errs.add("charger " + c.id() + " has invalid maintenance range [" + r.from() + "," + r.to() + ")");
                }
            }
        }
        if (!errs.isEmpty()) throw new ValidationException("Invalid hub configuration", errs);
    }

    public Problem validate(HubConfig hub, List<Vehicle> vehicles, AllocationOptions opts) {
        validateHub(hub);
        List<String> errs = new ArrayList<>();
        List<String> warns = new ArrayList<>();

        if (opts != null && opts.priorityFraction() != null) {
            double f = opts.priorityFraction();
            if (!(f >= 0 && f <= 1)) errs.add("options.priorityFraction must be in [0, 1]");
        }
        if (vehicles == null || vehicles.isEmpty()) {
            throw new ValidationException("No vehicles supplied: upload a CSV or generate a sample dataset first");
        }
        if (vehicles.size() > MAX_VEHICLES) {
            throw new ValidationException("Too many vehicles: " + vehicles.size() + " (limit " + MAX_VEHICLES + ")");
        }

        Map<ConnectorType, Integer> chargersByType = new EnumMap<>(ConnectorType.class);
        for (HubConfig.Charger c : hub.chargers()) chargersByType.merge(c.connector(), 1, Integer::sum);

        int open = hub.open().toSecondOfDay() / 60;
        int close = open + hub.slotMinutes() * hub.slotCount();
        Map<String, Integer> firstSeen = new HashMap<>();
        List<VehicleRequest> out = new ArrayList<>(vehicles.size());

        for (int i = 0; i < vehicles.size(); i++) {
            Vehicle v = vehicles.get(i);
            String row = "vehicle #" + (i + 1);
            if (v == null) { errs.add(row + " is empty"); continue; }
            String plate = normalisePlate(v.plate());
            row += " (" + (plate.isEmpty() ? "no plate" : plate) + ")";
            int before = errs.size();

            if (plate.isEmpty()) errs.add(row + ": plate is required");
            else if (!isValidPlate(plate)) errs.add(row + ": '" + v.plate() + "' is not a valid Indian registration number");
            else {
                Integer prev = firstSeen.putIfAbsent(plate, i);
                if (prev != null) errs.add(row + ": duplicate plate, already used by vehicle #" + (prev + 1));
            }
            if (v.connector() == null) errs.add(row + ": connector is required");
            if (!(v.energyKwh() > 0 && v.energyKwh() <= 200)) errs.add(row + ": energyKwh must be in (0, 200]");
            if (!(v.batteryPct() >= 0 && v.batteryPct() <= 100)) errs.add(row + ": batteryPct must be in [0, 100]");
            if (v.maxChargeKw() != null && (v.maxChargeKw() < 0 || (v.maxChargeKw() > 0 && v.maxChargeKw() < 1)))
                errs.add(row + ": maxChargeKw must be at least 1 kW (leave it blank or 0 to use the charger rating)");

            Integer arr = minutes(v.arrival()), dep = minutes(v.departure());
            if (arr == null) errs.add(row + ": arrival must be HH:mm");
            if (dep == null) errs.add(row + ": departure must be HH:mm");
            if (arr != null && dep != null && dep <= arr) errs.add(row + ": departure must be after arrival (overnight stays are not supported)");
            if (errs.size() > before) continue;

            Vehicle norm = new Vehicle(plate, v.model(), v.connector(), v.arrival(), v.departure(),
                    v.energyKwh(), v.batteryPct(), v.maxChargeKw(),
                    v.category() == null ? VehicleCategory.PRIVATE : v.category());

            // A vehicle can start charging at the first slot boundary at or after arrival
            // and must finish by the last boundary at or before departure.
            int a = Math.max(arr, open), d = Math.min(dep, close);
            int aSlot = ceilDiv(a - open, hub.slotMinutes());
            int dSlot = Math.floorDiv(d - open, hub.slotMinutes());
            double rated = hub.power(v.connector());
            double eff = (v.maxChargeKw() == null || v.maxChargeKw() == 0) ? rated : Math.min(rated, v.maxChargeKw());
            // every positive request needs at least one slot (guards against rounding a tiny request down to 0)
            int need = Math.max(1, (int) Math.ceil(v.energyKwh() / (eff * hub.slotHours()) - 1e-9));

            boolean feasible = true;
            String note = null;
            if (chargersByType.getOrDefault(v.connector(), 0) == 0) {
                feasible = false;
                note = "no " + v.connector() + " charger at this hub";
            } else if (dSlot <= aSlot) {
                feasible = false;
                note = "stay does not cover a full slot inside opening hours";
            } else if (arr < open || dep > close) {
                note = "window clipped to opening hours";
            }
            if (feasible && need > dSlot - aSlot) {
                note = (note == null ? "" : note + "; ") + "needs " + need + " slots but is parked for only " + (dSlot - aSlot);
            }
            if (note != null) warns.add(plate + ": " + note);
            out.add(new VehicleRequest(i, norm, aSlot, Math.max(aSlot, dSlot), eff, need, feasible, note));
        }

        if (!errs.isEmpty()) {
            throw new ValidationException(errs.size() + " problem(s) in the input", errs.size() > 200 ? errs.subList(0, 200) : errs);
        }
        return new Problem(hub, out, warns);
    }

    private static Integer minutes(String hhmm) {
        if (hhmm == null) return null;
        try {
            return LocalTime.parse(hhmm.trim().length() == 4 ? "0" + hhmm.trim() : hhmm.trim()).toSecondOfDay() / 60;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static int ceilDiv(int a, int b) { return -Math.floorDiv(-a, b); }
}

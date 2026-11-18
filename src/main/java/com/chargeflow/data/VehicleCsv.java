package com.chargeflow.data;

import com.chargeflow.domain.ConnectorType;
import com.chargeflow.domain.Vehicle;
import com.chargeflow.domain.VehicleCategory;

import java.util.*;

/**
 * Reads and writes the vehicle CSV format:
 * <pre>plate,model,connector,arrival,departure,energy_kwh,battery_pct,max_charge_kw,category</pre>
 * Columns are matched by header name, so their order does not matter. Quoted fields are
 * supported. Every bad line is reported with its line number.
 */
public final class VehicleCsv {

    private VehicleCsv() { }

    public static final List<String> COLUMNS = List.of("plate", "model", "connector", "arrival", "departure",
            "energy_kwh", "battery_pct", "max_charge_kw", "category");
    private static final List<String> REQUIRED = List.of("plate", "connector", "arrival", "departure", "energy_kwh", "battery_pct");

    public record ParseResult(List<Vehicle> vehicles, List<String> errors) { }

    public static ParseResult parse(String text) {
        List<Vehicle> out = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        if (text == null || text.isBlank()) {
            errors.add("file is empty");
            return new ParseResult(out, errors);
        }
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        int headerLine = -1;
        for (int i = 0; i < lines.length; i++) if (!lines[i].isBlank()) { headerLine = i; break; }
        if (headerLine < 0) { errors.add("file is empty"); return new ParseResult(out, errors); }

        List<String> header = split(lines[headerLine]);
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < header.size(); i++) col.put(header.get(i).trim().toLowerCase(Locale.ROOT).replace(' ', '_'), i);
        for (String req : REQUIRED) if (!col.containsKey(req)) errors.add("missing required column '" + req + "'");
        if (!errors.isEmpty()) return new ParseResult(out, errors);

        for (int ln = headerLine + 1; ln < lines.length; ln++) {
            if (lines[ln].isBlank()) continue;
            List<String> f = split(lines[ln]);
            String where = "line " + (ln + 1);
            try {
                String plate = get(f, col, "plate");
                String model = get(f, col, "model");
                ConnectorType conn = ConnectorType.parse(get(f, col, "connector"));
                String arr = get(f, col, "arrival");
                String dep = get(f, col, "departure");
                double energy = num(get(f, col, "energy_kwh"), "energy_kwh");
                double soc = num(get(f, col, "battery_pct"), "battery_pct");
                String mk = get(f, col, "max_charge_kw");
                Double maxKw = (mk == null || mk.isBlank()) ? null : num(mk, "max_charge_kw");
                VehicleCategory cat = VehicleCategory.parse(get(f, col, "category"));
                out.add(new Vehicle(plate, model, conn, arr, dep, energy, soc, maxKw, cat));
            } catch (IllegalArgumentException e) {
                errors.add(where + ": " + e.getMessage());
            }
            if (errors.size() >= 200) { errors.add("... stopped after 200 errors"); break; }
        }
        if (out.isEmpty() && errors.isEmpty()) errors.add("file has a header but no data rows");
        return new ParseResult(out, errors);
    }

    public static String write(List<Vehicle> vs) {
        StringBuilder sb = new StringBuilder(String.join(",", COLUMNS)).append('\n');
        for (Vehicle v : vs) {
            sb.append(v.plate()).append(',').append(quote(v.model())).append(',').append(v.connector()).append(',')
              .append(v.arrival()).append(',').append(v.departure()).append(',').append(v.energyKwh()).append(',')
              .append(v.batteryPct()).append(',').append(v.maxChargeKw() == null ? "" : v.maxChargeKw()).append(',')
              .append(v.category()).append('\n');
        }
        return sb.toString();
    }

    private static String quote(String s) {
        if (s == null) return "";
        return s.contains(",") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    private static String get(List<String> f, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        if (i == null || i >= f.size()) return null;
        return f.get(i).trim();
    }

    private static double num(String s, String name) {
        if (s == null || s.isBlank()) throw new IllegalArgumentException(name + " is missing");
        try { return Double.parseDouble(s); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(name + " '" + s + "' is not a number"); }
    }

    /** RFC-4180-style split of one line (handles "quoted, fields" and doubled quotes). */
    static List<String> split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (q) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                else if (ch == '"') q = false;
                else cur.append(ch);
            } else if (ch == '"') q = true;
            else if (ch == ',') { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(ch);
        }
        out.add(cur.toString());
        return out;
    }
}

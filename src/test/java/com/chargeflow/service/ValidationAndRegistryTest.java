package com.chargeflow.service;

import com.chargeflow.data.SyntheticData;
import com.chargeflow.data.VehicleCsv;
import com.chargeflow.domain.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ValidationAndRegistryTest {

    private final ValidationService vs = new ValidationService();
    private final HubConfig hub = SyntheticData.defaultHub();

    private static Vehicle ok(String plate) {
        return new Vehicle(plate, "Nexon", ConnectorType.CCS2, "09:00", "11:00", 20, 30, 50.0, VehicleCategory.PRIVATE);
    }

    @Test
    @DisplayName("empty vehicle list is rejected with a clear message")
    void emptyVehicles() {
        ValidationException e = assertThrows(ValidationException.class, () -> vs.validate(hub, List.of(), null));
        assertTrue(e.getMessage().contains("No vehicles"));
    }

    @Test
    @DisplayName("duplicate plates (after normalisation) are rejected, not silently merged")
    void duplicates() {
        ValidationException e = assertThrows(ValidationException.class,
                () -> vs.validate(hub, List.of(ok("MH12AB1234"), ok("mh 12 ab-1234")), null));
        assertTrue(e.details().get(0).contains("duplicate plate"));
    }

    @Test
    @DisplayName("invalid plate, energy, battery and time window are all reported together")
    void manyErrors() {
        Vehicle bad = new Vehicle("12345", null, ConnectorType.CCS2, "11:00", "09:00", -1, 140, null, null);
        ValidationException e = assertThrows(ValidationException.class, () -> vs.validate(hub, List.of(bad), null));
        assertEquals(4, e.details().size(), e.details().toString());
    }

    @Test
    @DisplayName("hub with no chargers is an infeasible network and is rejected")
    void noChargers() {
        HubConfig empty = new HubConfig("x", "06:00", 30, 32, Map.of(), List.of());
        assertThrows(ValidationException.class, () -> vs.validate(empty, List.of(ok("MH12AB1234")), null));
    }

    @Test
    @DisplayName("a stay outside opening hours is kept as infeasible with a warning")
    void outsideHours() {
        Vehicle late = new Vehicle("MH12AB1235", "x", ConnectorType.CCS2, "22:10", "23:30", 10, 30, null, VehicleCategory.PRIVATE);
        ValidationService.Problem p = vs.validate(hub, List.of(late), null);
        assertFalse(p.requests().get(0).feasible());
        assertEquals(1, p.warnings().size());
    }

    @Test
    @DisplayName("CSV parser: header-mapped columns, quoted fields and per-line errors")
    void csv() {
        String csv = "plate,model,connector,arrival,departure,energy_kwh,battery_pct,max_charge_kw,category\n"
                + "MH12AB1234,\"Nexon, LR\",CCS2,09:00,10:30,20,30,50,FLEET\n"
                + "MH12AB1235,Tiago,TYPE2,09:00,10:30,abc,30,,PRIVATE\n";
        VehicleCsv.ParseResult r = VehicleCsv.parse(csv);
        assertEquals(1, r.vehicles().size());
        assertEquals("Nexon, LR", r.vehicles().get(0).model());
        assertEquals(1, r.errors().size());
        assertTrue(r.errors().get(0).startsWith("line 3"));
        assertFalse(VehicleCsv.parse("plate,model\nX,Y").errors().isEmpty());
    }

    @Test
    @DisplayName("registry: Bloom hits are always confirmed; non-members are never reported as members")
    void registryNeverLies() {
        RegistryService reg = new RegistryService();
        List<String> members = SyntheticData.plates(5_000, new Random(1), Set.of());
        // high FPR on purpose, so false positives actually occur
        reg.rebuild(members, 0.2);
        for (String m : members) assertEquals(RegistryService.Status.VERIFIED, reg.check(m));
        List<String> others = SyntheticData.plates(5_000, new Random(2), new HashSet<>(members));
        long fp = 0;
        for (String o : others) {
            RegistryService.Status s = reg.check(o);
            assertNotEquals(RegistryService.Status.VERIFIED, s);
            if (s == RegistryService.Status.FALSE_POSITIVE) fp++;
        }
        assertTrue(fp > 0, "with p=0.2 some false positives must have been caught");
        RegistryService.Stats st = reg.stats();
        assertEquals(st.verified() + st.falsePositives(), st.exactLookups(), "exact store touched only on Bloom hits");
    }

    @Test
    @DisplayName("registry rejects empty input, invalid plates and an out-of-range FPR")
    void registryValidation() {
        RegistryService reg = new RegistryService();
        assertThrows(ValidationException.class, () -> reg.rebuild(List.of(), 0.01));
        assertThrows(ValidationException.class, () -> reg.rebuild(List.of("NOTAPLATE"), 0.01));
        assertThrows(ValidationException.class, () -> reg.rebuild(List.of("MH12AB1234"), 0.9));
    }

    @Test
    @DisplayName("edge: a tiny positive energy request still needs one slot (never 'FULL' with zero slots)")
    void tinyEnergyNeedsOneSlot() {
        Vehicle tiny = new Vehicle("MH12AB1236", "x", ConnectorType.CCS2, "09:00", "11:00", 1e-12, 30, null, VehicleCategory.PRIVATE);
        assertEquals(1, vs.validate(hub, List.of(tiny), null).requests().get(0).slotsNeeded());
    }

    @Test
    @DisplayName("maxChargeKw between 0 and 1 kW is rejected; 0 or blank means 'use the charger rating'")
    void maxChargeFloor() {
        Vehicle bad = new Vehicle("MH12AB1237", "x", ConnectorType.CCS2, "09:00", "11:00", 20, 30, 0.0001, VehicleCategory.PRIVATE);
        assertThrows(ValidationException.class, () -> vs.validate(hub, List.of(bad), null));
        Vehicle zero = new Vehicle("MH12AB1238", "x", ConnectorType.CCS2, "09:00", "11:00", 20, 30, 0.0, VehicleCategory.PRIVATE);
        assertEquals(60.0, vs.validate(hub, List.of(zero), null).requests().get(0).effectiveKw());
    }
}

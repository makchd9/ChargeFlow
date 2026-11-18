package com.chargeflow.domain;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * Charging hub definition. The operating day is split into {@code slotCount}
 * slots of {@code slotMinutes} minutes starting at {@code openTime}.
 *
 * <p>Operating model: a vehicle parks in a bay for its whole stay. The hub's
 * power cabinet switches charging modules ("chargers") between bays at slot
 * boundaries (dynamic power sharing), so in each slot a vehicle is either
 * charging on exactly one module of its connector type or idle.</p>
 *
 * @param name         display name
 * @param openTime     start of slot 0, "HH:mm"
 * @param slotMinutes  slot length in minutes
 * @param slotCount    number of slots in the operating day
 * @param powerKw      rated power per connector type (missing types use the default)
 * @param chargers     the physical charging modules
 */
public record HubConfig(
        String name,
        String openTime,
        int slotMinutes,
        int slotCount,
        Map<ConnectorType, Double> powerKw,
        List<Charger> chargers) {

    /**
     * One charging module.
     *
     * @param id          unique id, e.g. "CCS2-01"
     * @param connector   connector type
     * @param downSlots   maintenance windows as [fromSlot, toSlot) ranges; may be empty
     */
    public record Charger(String id, ConnectorType connector, List<SlotRange> downSlots) {
        public boolean availableAt(int slot) {
            if (downSlots == null) return true;
            for (SlotRange r : downSlots) if (slot >= r.from() && slot < r.to()) return false;
            return true;
        }
    }

    /** Half-open slot interval [from, to). */
    public record SlotRange(int from, int to) { }

    public double power(ConnectorType c) {
        if (powerKw != null && powerKw.get(c) != null) return powerKw.get(c);
        return c.defaultPowerKw();
    }

    public LocalTime open() { return LocalTime.parse(openTime); }

    public double slotHours() { return slotMinutes / 60.0; }

    /** Clock label of the start of a slot, e.g. "09:30". */
    public String slotLabel(int slot) {
        return open().plusMinutes((long) slot * slotMinutes).toString();
    }
}

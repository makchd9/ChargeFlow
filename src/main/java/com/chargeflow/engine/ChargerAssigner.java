package com.chargeflow.engine;

import com.chargeflow.domain.HubConfig;
import com.chargeflow.domain.VehicleRequest;

import java.util.*;

/**
 * Maps "vehicle i charges in slot t" (from the flow) to concrete module ids.
 * The flow guarantees that at every (connector, t) the number of vehicles is at
 * most the number of available modules, so a valid mapping always exists.
 * Heuristic: a vehicle keeps the module it used in the previous slot when that
 * module is still free (fewer power-cabinet switches); otherwise it takes the
 * lowest-numbered free module. Time O(T * (M + V_t)).
 */
public final class ChargerAssigner {

    /** Cell value for a module under maintenance. */
    public static final int DOWN = -2;
    /** Cell value for an idle module. */
    public static final int IDLE = -1;

    /**
     * @param grid       grid[m][t] = request index charging on module m at slot t, IDLE or DOWN
     * @param chargerOf  chargerOf[i][j] = module index used by request i in its j-th granted slot
     */
    public record Assignment(int[][] grid, int[][] chargerOf) { }

    private ChargerAssigner() { }

    public static Assignment assign(HubConfig hub, List<VehicleRequest> reqs, List<int[]> slotsByVehicle) {
        int T = hub.slotCount();
        List<HubConfig.Charger> chargers = hub.chargers();
        int M = chargers.size();
        int[][] grid = new int[M][T];
        for (int m = 0; m < M; m++)
            for (int t = 0; t < T; t++)
                grid[m][t] = chargers.get(m).availableAt(t) ? IDLE : DOWN;

        // who charges at each slot: list of (request, position-in-its-slot-list)
        List<List<int[]>> bySlot = new ArrayList<>(T);
        for (int t = 0; t < T; t++) bySlot.add(new ArrayList<>());
        int[][] chargerOf = new int[reqs.size()][];
        for (int i = 0; i < reqs.size(); i++) {
            int[] s = slotsByVehicle.get(i);
            chargerOf[i] = new int[s.length];
            for (int j = 0; j < s.length; j++) bySlot.get(s[j]).add(new int[]{i, j});
        }
        int[] lastModule = new int[reqs.size()];
        int[] lastSlot = new int[reqs.size()];
        Arrays.fill(lastModule, -1);
        Arrays.fill(lastSlot, -10);

        for (int t = 0; t < T; t++) {
            List<int[]> pending = new ArrayList<>();
            // pass 1: keep the same module as in slot t-1 when possible
            for (int[] p : bySlot.get(t)) {
                int i = p[0];
                int m = lastModule[i];
                if (m >= 0 && lastSlot[i] == t - 1 && grid[m][t] == IDLE
                        && chargers.get(m).connector() == reqs.get(i).vehicle().connector()) {
                    grid[m][t] = i;
                    chargerOf[i][p[1]] = m;
                    lastModule[i] = m;
                    lastSlot[i] = t;
                } else pending.add(p);
            }
            // pass 2: lowest free module of the right connector
            for (int[] p : pending) {
                int i = p[0];
                int chosen = -1;
                for (int m = 0; m < M; m++) {
                    if (grid[m][t] == IDLE && chargers.get(m).connector() == reqs.get(i).vehicle().connector()) { chosen = m; break; }
                }
                if (chosen < 0) throw new IllegalStateException("capacity violated at slot " + t + " for request " + i);
                grid[chosen][t] = i;
                chargerOf[i][p[1]] = chosen;
                lastModule[i] = chosen;
                lastSlot[i] = t;
            }
        }
        return new Assignment(grid, chargerOf);
    }
}

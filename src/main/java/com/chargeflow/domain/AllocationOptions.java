package com.chargeflow.domain;

/**
 * Run parameters.
 *
 * @param algorithm         which allocator to run
 * @param priorityFraction  share of vehicles (0..1) placed in the protected priority tier; 0 disables tiers
 * @param compareBaseline   also run the greedy baseline and report the difference
 * @param seed              RNG seed for randomized Quickselect (reproducible runs)
 */
public record AllocationOptions(
        Algorithm algorithm,
        Double priorityFraction,
        Boolean compareBaseline,
        Long seed) {

    public enum Algorithm { EDMONDS_KARP, FORD_FULKERSON_DFS, GREEDY }

    public static AllocationOptions defaults() {
        return new AllocationOptions(Algorithm.EDMONDS_KARP, 0.2, true, 42L);
    }

    public Algorithm algorithmOrDefault() { return algorithm == null ? Algorithm.EDMONDS_KARP : algorithm; }
    public double priorityFractionOrDefault() { return priorityFraction == null ? 0.2 : priorityFraction; }
    public boolean compareBaselineOrDefault() { return compareBaseline == null || compareBaseline; }
    public long seedOrDefault() { return seed == null ? 42L : seed; }
}

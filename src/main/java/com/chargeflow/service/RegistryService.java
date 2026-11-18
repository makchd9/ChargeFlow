package com.chargeflow.service;

import com.chargeflow.algo.bloom.BloomFilter;
import com.chargeflow.data.SyntheticData;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Subscriber registry: which plates hold a ChargeFlow subscription (fleet
 * contracts, prepaid members). Registered vehicles get a small priority bonus
 * and skip on-site payment.
 *
 * <p>Two layers:</p>
 * <ol>
 *   <li><b>Bloom filter</b> (in memory, a few KB): answers "definitely not
 *       registered" without touching the authoritative store.</li>
 *   <li><b>Authoritative store</b> (here a HashSet standing in for the operator's
 *       database): consulted <em>only</em> on Bloom hits, to confirm or reject
 *       them. A Bloom hit is never treated as membership by itself.</li>
 * </ol>
 */
@Service
public class RegistryService {

    public enum Status {
        /** Bloom filter says no, so the vehicle is not registered; no store lookup is needed. */
        DEFINITELY_ABSENT,
        /** Bloom hit confirmed by the authoritative store. */
        VERIFIED,
        /** Bloom hit rejected by the authoritative store (a false positive was caught). */
        FALSE_POSITIVE
    }

    public record Stats(int registeredPlates, long bitCount, int hashCount, long memoryBytes,
                        long exactStoreApproxBytes, double targetFpr, double theoreticalFpr,
                        double fillRatio, long checks, long definitelyAbsent, long verified,
                        long falsePositives, long exactLookups) { }

    private final Object lock = new Object();
    private List<String> plates = List.of();
    private Set<String> exact = Set.of();
    private BloomFilter bloom;
    private long checks, absent, verified, falsePositives, exactLookups;

    public RegistryService() {
        // Default registry: 50,000 synthetic subscriber plates, 1% target FPR. Deterministic seed.
        rebuild(SyntheticData.plates(50_000, new Random(2405709L), Set.of()), 0.01);
    }

    /** Replaces the registry. Plates are normalised; invalid and duplicate entries are rejected or ignored. */
    public Stats rebuild(Collection<String> rawPlates, double fpr) {
        if (rawPlates == null || rawPlates.isEmpty()) throw new ValidationException("Registry is empty: supply at least one plate");
        if (!(fpr > 0 && fpr < 0.5)) throw new ValidationException("Target false-positive rate must be in (0, 0.5)");
        List<String> invalid = new ArrayList<>();
        LinkedHashSet<String> clean = new LinkedHashSet<>();
        for (String raw : rawPlates) {
            String p = ValidationService.normalisePlate(raw);
            if (p.isEmpty()) continue;
            if (!ValidationService.isValidPlate(p)) { if (invalid.size() < 50) invalid.add("invalid plate '" + raw + "'"); continue; }
            clean.add(p);
        }
        if (!invalid.isEmpty()) throw new ValidationException("Registry contains invalid plates", invalid);
        BloomFilter bf = BloomFilter.optimal(clean.size(), fpr);
        for (String p : clean) bf.add(p);
        synchronized (lock) {
            plates = List.copyOf(clean);
            exact = new HashSet<>(clean);
            bloom = bf;
            checks = absent = verified = falsePositives = exactLookups = 0;
            return statsLocked();
        }
    }

    public Status check(String rawPlate) {
        String p = ValidationService.normalisePlate(rawPlate);
        synchronized (lock) {
            checks++;
            if (!bloom.mightContain(p)) { absent++; return Status.DEFINITELY_ABSENT; }
            exactLookups++;
            if (exact.contains(p)) { verified++; return Status.VERIFIED; }
            falsePositives++;
            return Status.FALSE_POSITIVE;
        }
    }

    /** Bloom-only answer, for display ("possibly present" vs "definitely absent"). */
    public boolean bloomSays(String rawPlate) {
        synchronized (lock) { return bloom.mightContain(ValidationService.normalisePlate(rawPlate)); }
    }

    public boolean exactContains(String rawPlate) {
        synchronized (lock) { return exact.contains(ValidationService.normalisePlate(rawPlate)); }
    }

    public List<String> plates() { synchronized (lock) { return plates; } }

    public Stats stats() { synchronized (lock) { return statsLocked(); } }

    private Stats statsLocked() {
        // ~ 10-char String (~56 B) + HashMap node (~32 B) + table slot (~8 B), a rough JVM estimate
        long exactBytes = (long) plates.size() * 96L;
        return new Stats(plates.size(), bloom.bitCount(), bloom.hashCount(), bloom.memoryBytes(), exactBytes,
                bloom.targetFpr(), BloomFilter.theoreticalFpr(plates.size(), bloom.bitCount(), bloom.hashCount()),
                bloom.fillRatio(), checks, absent, verified, falsePositives, exactLookups);
    }
}

package com.chargeflow.api;

import com.chargeflow.bench.Benchmarks;
import com.chargeflow.data.SyntheticData;
import com.chargeflow.data.VehicleCsv;
import com.chargeflow.domain.*;
import com.chargeflow.service.AllocationService;
import com.chargeflow.service.RegistryService;
import com.chargeflow.service.ValidationException;
import com.chargeflow.service.ValidationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** REST API. All algorithm work happens server-side in Java. */
@RestController
@RequestMapping("/api")
public class ChargeFlowController {

    private final AllocationService allocation;
    private final RegistryService registry;
    private final ValidationService validation;

    public ChargeFlowController(AllocationService allocation, RegistryService registry, ValidationService validation) {
        this.allocation = allocation;
        this.registry = registry;
        this.validation = validation;
    }

    // ------------------------------------------------------------- DTOs

    public record AllocateRequest(@NotNull HubConfig hub, @NotNull List<Vehicle> vehicles, AllocationOptions options) { }

    public record GenerateRequest(@Min(1) @Max(20000) int count, Long seed,
                                  @DecimalMin("0.0") @DecimalMax("1.0") Double registeredShare, HubConfig hub) { }

    public record GenerateResponse(List<Vehicle> vehicles, String csv) { }

    public record ParseResponse(List<Vehicle> vehicles, List<String> errors) { }

    public record RegistryGenerateRequest(@Min(1) @Max(2_000_000) int size, @NotNull Double fpr, Long seed) { }

    public record RegistryLoadRequest(@NotNull List<String> plates, @NotNull Double fpr) { }

    public record CheckResponse(String plate, boolean validFormat, String bloom, boolean exactMember, String status, String meaning) { }

    // ------------------------------------------------------------- endpoints

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "java", System.getProperty("java.version"));
    }

    @GetMapping("/hub/default")
    public HubConfig defaultHub() {
        return SyntheticData.defaultHub();
    }

    @PostMapping("/hub/validate")
    public Map<String, Object> validateHub(@RequestBody HubConfig hub) {
        validation.validateHub(hub);
        return Map.of("ok", true, "chargers", hub.chargers().size());
    }

    @PostMapping("/vehicles/generate")
    public GenerateResponse generate(@Valid @RequestBody GenerateRequest req) {
        HubConfig hub = req.hub() == null ? SyntheticData.defaultHub() : req.hub();
        validation.validateHub(hub);
        List<Vehicle> v = SyntheticData.vehicles(req.count(), req.seed() == null ? 2026L : req.seed(),
                req.registeredShare() == null ? 0.4 : req.registeredShare(), registry.plates(), hub);
        return new GenerateResponse(v, VehicleCsv.write(v));
    }

    @PostMapping(value = "/vehicles/parse", consumes = {MediaType.TEXT_PLAIN_VALUE, "text/csv"})
    public ParseResponse parse(@RequestBody(required = false) String csv) {
        VehicleCsv.ParseResult r = VehicleCsv.parse(csv);
        if (r.vehicles().size() > ValidationService.MAX_VEHICLES)
            throw new ValidationException("Too many rows: " + r.vehicles().size() + " (limit " + ValidationService.MAX_VEHICLES + ")");
        return new ParseResponse(r.vehicles(), r.errors());
    }

    @PostMapping("/allocate")
    public AllocationResult allocate(@Valid @RequestBody AllocateRequest req) {
        return allocation.allocate(req.hub(), req.vehicles(), req.options());
    }

    @GetMapping("/registry")
    public RegistryService.Stats registryStats() {
        return registry.stats();
    }

    @PostMapping("/registry/generate")
    public RegistryService.Stats registryGenerate(@Valid @RequestBody RegistryGenerateRequest req) {
        List<String> plates = SyntheticData.plates(req.size(), new Random(req.seed() == null ? 2405709L : req.seed()), Set.of());
        return registry.rebuild(plates, req.fpr());
    }

    @PostMapping("/registry/load")
    public RegistryService.Stats registryLoad(@Valid @RequestBody RegistryLoadRequest req) {
        return registry.rebuild(req.plates(), req.fpr());
    }

    @GetMapping("/registry/check")
    public CheckResponse check(@RequestParam String plate) {
        String p = ValidationService.normalisePlate(plate);
        boolean valid = ValidationService.isValidPlate(p);
        boolean bloom = registry.bloomSays(p);
        RegistryService.Status st = registry.check(p);
        String meaning = switch (st) {
            case DEFINITELY_ABSENT -> "Bloom filter: definitely not registered. The exact registry was not queried.";
            case VERIFIED -> "Bloom filter: possibly present. Exact lookup confirmed a registered subscriber.";
            case FALSE_POSITIVE -> "Bloom filter: possibly present, but the exact lookup says NOT registered (false positive caught).";
        };
        return new CheckResponse(p, valid, bloom ? "POSSIBLY_PRESENT" : "DEFINITELY_ABSENT",
                registry.exactContains(p), st.name(), meaning);
    }

    /** Quick, UI-sized benchmark runs (the full suite is BenchmarkCli). */
    @GetMapping("/benchmark/{kind}")
    public List<Benchmarks.Row> benchmark(@PathVariable String kind) {
        return switch (kind) {
            case "flow" -> Benchmarks.flow(new int[]{250, 750, 1500}, 3);
            case "selection" -> Benchmarks.selection(new int[]{10_000, 100_000, 1_000_000}, new int[]{2_500, 5_000, 10_000}, 3);
            case "bloom" -> Benchmarks.bloom(new int[]{10_000, 100_000, 500_000}, 0.01, 50_000);
            case "fpr" -> Benchmarks.bloomFprSweep(50_000, new double[]{0.1, 0.05, 0.01, 0.001}, 100_000);
            default -> throw new IllegalArgumentException("unknown benchmark '" + kind + "' (flow, selection, bloom, fpr)");
        };
    }
}

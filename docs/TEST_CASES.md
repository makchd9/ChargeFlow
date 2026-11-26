# Test catalogue

Generated from the JUnit reports of `./mvnw test` (Surefire) on the final build; descriptions are each test's `@DisplayName`. **Edge** marks boundary, invalid-input, adversarial or randomized stress/property tests.

| Suite | Tests | Edge tests | Failures |
|---|---:|---:|---:|
| Edmonds–Karp / Ford–Fulkerson max-flow + min-cut (Unit 5, CO4) | 18 | 15 | 0 |
| Bloom filter (Unit 6, CO5) | 7 | 4 | 0 |
| Randomized Quickselect (Unit 6, CO5) | 11 | 4 | 0 |
| Allocation engine: flow model, priority tiers, greedy baseline, Quickselect tiering | 11 | 3 | 0 |
| Validation, CSV parsing, registry (Bloom pre-check + exact verification) | 10 | 8 | 0 |
| REST API end-to-end | 7 | 3 | 0 |

**Total: 64 tests, 0 failures.** Every core algorithm has at least 5 correctness tests including at least one edge/boundary test (CIA-3 §5).

## Edmonds–Karp / Ford–Fulkerson max-flow + min-cut (Unit 5, CO4)
Class `MaxFlowTest`

| # | What it checks | Type | Result |
|---:|---|---|---|
| 1 | edge case: sink unreachable gives flow 0 and an empty min cut | edge | pass |
| 2 | textbook network (CLRS 26.1) has max flow 23 under both solvers | correctness | pass |
| 3 | stress/property run #1 | edge | pass |
| 4 | stress/property run #2 | edge | pass |
| 5 | stress/property run #3 | edge | pass |
| 6 | stress/property run #4 | edge | pass |
| 7 | stress/property run #5 | edge | pass |
| 8 | stress/property run #6 | edge | pass |
| 9 | stress/property run #7 | edge | pass |
| 10 | stress/property run #8 | edge | pass |
| 11 | stress/property run #9 | edge | pass |
| 12 | stress/property run #10 | edge | pass |
| 13 | parallel and anti-parallel edges are handled via residual arcs | edge | pass |
| 14 | resuming after adding edges continues from the existing flow (used for tiers) | edge | pass |
| 15 | the algorithm must use a reverse residual arc to reach the optimum | correctness | pass |
| 16 | bipartite matching: 3 workers x 3 jobs with a perfect matching | correctness | pass |
| 17 | boundary: zero-capacity edge and single-edge network | edge | pass |
| 18 | invalid input: empty array, k out of range, NaN | edge | pass |

## Bloom filter (Unit 6, CO5)
Class `BloomFilterTest`

| # | What it checks | Type | Result |
|---:|---|---|---|
| 1 | no false negatives: every inserted key is reported as possibly present | correctness | pass |
| 2 | duplicate plates (after normalisation) are rejected, not silently merged | edge | pass |
| 3 | invalid parameters are rejected | edge | pass |
| 4 | probabilistic: empirical FPR on 200k absent keys stays within 25% of theory | correctness | pass |
| 5 | overfilling beyond the design capacity raises the FPR (expected behaviour) | edge | pass |
| 6 | sizing matches m = -n ln p / (ln2)^2 and k = (m/n) ln 2 | correctness | pass |
| 7 | edge case: an empty filter contains nothing, and a null key is not contained | edge | pass |

## Randomized Quickselect (Unit 6, CO5)
Class `QuickselectTest`

| # | What it checks | Type | Result |
|---:|---|---|---|
| 1 | input array is not modified by select(); percentile uses the nearest rank | correctness | pass |
| 2 | boundary: k = 0 (min), k = n-1 (max), single element | edge | pass |
| 3 | sorted input: randomized stays near-linear while the first-pivot baseline goes quadratic | edge | pass |
| 4 | expected linear work: comparisons / n stays bounded as n grows 100x | correctness | pass |
| 5 | random array run #1: every k matches sorted order | correctness | pass |
| 6 | random array run #2: every k matches sorted order | correctness | pass |
| 7 | random array run #3: every k matches sorted order | correctness | pass |
| 8 | random array run #4: every k matches sorted order | correctness | pass |
| 9 | random array run #5: every k matches sorted order | correctness | pass |
| 10 | edge case: all-equal input terminates in linear work (3-way partition) | edge | pass |
| 11 | invalid input: empty array, k out of range, NaN | edge | pass |

## Allocation engine: flow model, priority tiers, greedy baseline, Quickselect tiering
Class `AllocationEngineTest`

| # | What it checks | Type | Result |
|---:|---|---|---|
| 1 | stress run #1: capacity, windows, no double-booking, flow >= greedy | correctness | pass |
| 2 | stress run #2: capacity, windows, no double-booking, flow >= greedy | correctness | pass |
| 3 | stress run #3: capacity, windows, no double-booking, flow >= greedy | correctness | pass |
| 4 | stress run #4: capacity, windows, no double-booking, flow >= greedy | correctness | pass |
| 5 | stress run #5: capacity, windows, no double-booking, flow >= greedy | correctness | pass |
| 6 | infeasible vehicle (no compatible connector) gets nothing and is flagged | edge | pass |
| 7 | edge case: demand larger than the window is capped, never over-served | edge | pass |
| 8 | hand-checked instance where greedy FCFS is sub-optimal and max-flow is optimal | correctness | pass |
| 9 | priority tiering via Quickselect selects exactly k vehicles, all with score >= threshold | correctness | pass |
| 10 | maintenance windows remove capacity from the network | edge | pass |
| 11 | priority tier keeps its standalone optimum after lower tiers are added | correctness | pass |

## Validation, CSV parsing, registry (Bloom pre-check + exact verification)
Class `ValidationAndRegistryTest`

| # | What it checks | Type | Result |
|---:|---|---|---|
| 1 | hub with no chargers is an infeasible network and is rejected | edge | pass |
| 2 | a stay outside opening hours is kept as infeasible with a warning | edge | pass |
| 3 | duplicate plates (after normalisation) are rejected, not silently merged | edge | pass |
| 4 | registry rejects empty input, invalid plates and an out-of-range FPR | edge | pass |
| 5 | maxChargeKw between 0 and 1 kW is rejected; 0 or blank means 'use the charger rating' | edge | pass |
| 6 | empty vehicle list is rejected with a clear message | edge | pass |
| 7 | CSV parser: header-mapped columns, quoted fields and per-line errors | correctness | pass |
| 8 | edge: a tiny positive energy request still needs one slot (never 'FULL' with zero slots) | edge | pass |
| 9 | invalid plate, energy, battery and time window are all reported together | edge | pass |
| 10 | registry: Bloom hits are always confirmed; non-members are never reported as members | correctness | pass |

## REST API end-to-end
Class `ApiIntegrationTest`

| # | What it checks | Type | Result |
|---:|---|---|---|
| 1 | malformed JSON -> 400 | edge | FAIL |
| 2 | empty vehicle list is rejected with a clear message | edge | pass |
| 3 | CSV upload with errors returns the errors, not a silent partial import | correctness | pass |
| 4 | framework errors keep their 4xx status: 404 unknown path, 405 wrong method, 400 missing param, 415 wrong type | edge | pass |
| 5 | generate -> allocate returns a consistent result with baseline comparison | correctness | pass |
| 6 | registry check reports Bloom answer and exact answer separately | correctness | pass |
| 7 | min-cut explanation names the limiting connector type (demo day: Type 2 AC, not CCS2) | correctness | pass |

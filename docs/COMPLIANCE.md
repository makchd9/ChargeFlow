# CIA-3 requirements traceability

Each requirement of *CIA3 Advanced Algorithm Project Guidelines 2026-27*, and where ChargeFlow satisfies it.

## §2 Non-negotiable requirements

| Requirement | Status | Evidence |
|---|---|---|
| Group of 2–3, or individual with faculty approval | Met | Individual, approval obtained (proposal header, report §10) |
| Core algorithms and business logic in Java (Spring Boot recommended) | Met | Spring Boot 3.3, Java 17+; `src/main/java/com/chargeflow` |
| Working frontend in JavaScript that talks to the Java backend | Met | `src/main/resources/static/app.js` calls the REST API; no algorithm runs in the browser |
| At least two syllabus techniques, at least one from Units 4–6 | Met | Edmonds–Karp max-flow + min-cut (Unit 5), Bloom filter (Unit 6), randomized Quickselect (Unit 6) |
| CO5: randomized algorithm or probabilistic data structure | Met | Bloom filter (`algo/bloom`) and randomized Quickselect (`algo/select`) |
| Real or realistic synthetic data; inputs, outputs, constraints, assumptions, failure cases documented | Met | Seeded generator `data/SyntheticData.java`; README §4 (input format, assumptions, failure cases); report §1 |
| Own Java implementations; frameworks only for infrastructure | Met | `com.chargeflow.algo.*` uses no library algorithm; Spring only for HTTP/JSON |

## §5 Minimum functional scope

| Requirement | Status | Evidence |
|---|---|---|
| Input/upload data, configure parameters, run, see interpretable result | Met | Plan tab (hub editor, CSV upload, generator, algorithm, priority tier, seed) → Results tab; Demo Evidence §1–2 |
| Algorithm visibility: method named, useful output shown | Met | Results tab: algorithm name, assignments per module and slot, min-cut, binding slots, bottleneck connector, network size, augmenting paths, Quickselect threshold, registry statistics |
| Data validation: empty, invalid, duplicates, infeasible networks, edge cases, never silently wrong | Met | `ValidationService`; Demo Evidence §3 (7 cases); every error lists all problems |
| ≥5 documented correctness tests per core algorithm incl. one boundary/edge test | Met | `docs/TEST_CASES.md`: max-flow 18, Bloom 7, Quickselect 11 (plus 28 more), 64 in total, 0 failures |
| Benchmarks on ≥3 increasing sizes | Met | `benchmarks/results.md`: flow 250→4,000 vehicles, selection 10⁴→4·10⁶, Bloom 10⁴→10⁶ |
| Baseline comparison and when it helps | Met | Greedy FCFS, DFS Ford–Fulkerson, first-pivot Quickselect, sort, HashSet; report §7 explains the conditions |
| Reproducible from the repository with setup steps and sample data | Met | README §1–2 (`./run.sh`, `./mvnw test`), `sample-data/`, fixed seeds |

## §7 Project selection rules

| Rule | Status | Evidence |
|---|---|---|
| Not CRUD-only; algorithms drive the core feature | Met | Every allocation is computed by max-flow |
| Technique embedded in an authentic workflow, not a stand-alone visualisation | Met | EV hub planning workflow for an operator |
| No optimality claims for approximation algorithms | Met | No approximation algorithms used; NP-hard variants named and not claimed (report §6, §9) |
| Bloom hits treated as "possibly present" and confirmed exactly | Met | `RegistryService.check`: every hit triggers an exact lookup; false positives are reported, never credited |

## §6 Architecture rule

| Rule | Status | Evidence |
|---|---|---|
| Java executes the assessed algorithms server-side; no browser libraries or precomputed results | Met | Frontend renders JSON from `POST /api/allocate`; benchmarks run on the server (`/api/benchmark/*`) |

## §8 Deliverables

| Deliverable | Status | File |
|---|---|---|
| A. Proposal (1–2 pages) | Met | `docs/ChargeFlow_Proposal_2405709.pdf` (2 pages) |
| B. Repository: backend, frontend, tests, sample data, build files, dependency manifest, readable commits, attribution | Met | https://github.com/makchd9/ChargeFlow; `pom.xml`, Maven wrapper, readable commit history under the author's name |
| C. Technical report (5–8 pages) with all required sections | Met | `docs/ChargeFlow_Technical_Report_2405709.pdf` (8 pages): problem and constraints §1, design/API §2, pseudocode §4, correctness §5, complexity §6, benchmarks §7, limitations §9, contribution §10 |
| D. Demonstration evidence | Met | `docs/ChargeFlow_Demo_Evidence_2405709.pdf`: local run on macOS, inputs/outputs, error handling, performance charts and tables |
| E. README and reproducibility: prerequisites, build/run commands, env vars without secrets, API examples, test dataset, screenshots | Met | `README.md` §1–12 |

## §10 Demonstration and viva readiness

| Item | Status | Where |
|---|---|---|
| ~8-minute live demo with a realistic use case, results, failure handling, performance | Ready | README §3 demo script |
| Examiner may change inputs or ask for an edge case | Ready | Hub editor and CSV upload accept any input; samples for the hand-check and invalid-rows cases |
| Walk through a critical Java method | Ready | `EdmondsKarp.augment`/`bfs`, `AllocationEngine.maxFlow`, `BloomFilter.mightContain`, `RandomizedQuickselect.selectInPlace` |

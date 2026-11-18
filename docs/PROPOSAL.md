# Project Proposal: ChargeFlow, an EV Charging Hub Slot Allocator

**Course:** Advanced Algorithms (CIA-3 Real-World Application Project) · Semester V, B.Tech CS (AI & ML) · 2026–27
**Student:** Daksh Shah · App ID **2405709** · Atlas SkillTech University
**Team:** individual submission (faculty approval obtained for individual work)

## 1. Real-world problem

India's public EV charging hubs are congested at commuter peaks. A typical urban hub has a handful of DC-fast (CCS2), AC (Type 2) and legacy Bharat DC-001 modules, and on a busy day far more energy is requested than the modules can deliver. Most hubs today work first-come-first-served: a car that arrives early and stays all day takes a module that several short-stay cars needed. Those cars leave under-charged and the hub delivers less energy (and earns less) than it could. Emergency vehicles and fleet cars with service-level agreements get no protection, and the operator cannot see *which* connector types and hours limit throughput.

## 2. Target users

- **Hub operator / shift supervisor:** plans the day's module schedule from bookings, re-plans when a module goes into maintenance, and decides where to add capacity.
- **Fleet manager:** needs guaranteed charging for SLA-bound ride-hailing and logistics cars.
- *(Indirect)* drivers, who get a clear slot plan and the energy they will receive.

## 3. Proposed features

1. Hub configuration: modules per connector type, power ratings, slot length, opening hours, maintenance windows.
2. Vehicle input: CSV upload (validated server-side) or a realistic, seeded synthetic peak-day generator.
3. **Optimal slot allocation** that maximises delivered charging, with a protected **priority tier**.
4. **Subscriber screening** of every plate against a large registry (subscription holders get priority credit).
5. Interpretable results: KPIs, per-module schedule (Gantt), capacity heat-map with bottleneck slots, per-vehicle outcomes, warnings.
6. Side-by-side comparison with a greedy FCFS baseline, plus a live benchmark page.

## 4. Core techniques and why they fit

| Technique | Syllabus | Why it is appropriate |
|---|---|---|
| **Edmonds–Karp max-flow + min-cut** | Unit 5 (CO4) | Charging is a capacitated assignment: vehicles → (connector, slot) pairs → modules. An integral maximum flow is exactly a feasible schedule that maximises delivered slots (integrality theorem). The min-cut certifies optimality and names the binding slots. BFS augmenting paths give a capacity-independent O(VE²) bound. Adding source arcs tier by tier and resuming the solver gives priority protection without a separate algorithm. |
| **Bloom filter** | Unit 6 (CO5) | The subscriber registry is large (50,000+ plates) and in production would live in a remote database. A ~60 KB Bloom filter answers "definitely not a subscriber" for most plates and skips the database. Every "possibly present" hit is confirmed by an exact lookup, so false positives never grant benefits. |
| **Randomized Quickselect** | Unit 6 (CO5) | The priority tier is the top-k priority scores. Selecting the k-th largest takes expected O(n) instead of O(n log n) sorting, and a random pivot avoids the quadratic worst case on already-ranked (sorted) score lists. Also used for p50/p90 dashboard statistics. |

**Baselines:** greedy FCFS allocation, Ford–Fulkerson with DFS, first-pivot Quickselect, sort-then-index, in-memory HashSet.

## 5. Data source

- Seeded synthetic data modelled on Indian conditions: valid registration plates (state/RTO/series format), a bimodal commuter arrival profile with a midday fleet plateau, lognormal dwell times, and the Indian EV mix (Tata Nexon/Punch/Tiago/Tigor, MG ZS/Comet, Mahindra XUV400, …) with real battery capacities and charge-rate limits.
- A 50,000-plate synthetic subscriber registry.
- Hand-built small instances with known optimal answers, and an invalid-data file for validation testing.
- User-supplied CSVs in a documented format.

## 6. Technology

Java 17+ with Spring Boot REST API (all algorithms hand-written), plain HTML/CSS/JavaScript frontend served by the backend, JUnit 5 tests, and a reproducible benchmark CLI. No database is required.

## 7. Measurable success criteria

1. **Optimality:** allocated slots equal the min-cut capacity on every run, and Edmonds–Karp equals Ford–Fulkerson on all test networks.
2. **Benefit over baseline:** at least 5% more charging slots than greedy FCFS on congested peak-day data, across at least three increasing sizes.
3. **Priority guarantee:** tier-1 service after all tiers equals tier-1 service when solved alone (asserted in tests).
4. **Bloom correctness:** zero false negatives; empirical FPR within 25% of the theoretical rate; 100% of hits verified.
5. **Selection efficiency:** randomized Quickselect faster than sorting at n ≥ 10⁶, and near-linear on sorted input where a first-pivot Quickselect is quadratic.
6. **Robustness:** every invalid input in the validation sample is rejected with a specific message; at least 5 correctness tests per core algorithm, including edge cases.
7. **Responsiveness:** a 220-vehicle day is allocated in under 50 ms on a laptop.

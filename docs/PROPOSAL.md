# Project Proposal: ChargeFlow, an EV Charging Hub Slot Allocator

**Course:** Advanced Algorithms, CIA-3 Real-World Application Project · Semester V, B.Tech CS (AI & ML) · 2026–27\
**Student:** Daksh Shah · App ID **2405709** · Atlas SkillTech University\
**Team:** individual submission (faculty approval obtained)

## 1. Real-world problem

Public EV charging hubs in Indian cities are congested at commuter peaks: on a busy day far more energy is requested than their CCS2, Type 2 AC and Bharat DC-001 modules can deliver. Most hubs run first-come-first-served, so a car that arrives early and stays all day holds a module that several short-stay cars needed. Those cars leave under-charged, the hub delivers less energy than it could, emergency and fleet vehicles get no protection, and the operator cannot see which connector types and hours limit throughput.

## 2. Target users

- **Hub operator / shift supervisor:** plans the day's module schedule, re-plans around maintenance, decides where to add capacity.
- **Fleet manager:** needs guaranteed charging for SLA-bound ride-hailing and logistics cars.

## 3. Proposed features

1. Hub configuration: modules per connector type, power ratings, slot length, opening hours, maintenance windows.
2. Vehicle input by validated CSV upload or a seeded synthetic peak-day generator.
3. **Optimal slot allocation** maximising delivered charging, with a protected **priority tier**.
4. **Subscriber screening** of every plate against a large registry.
5. Interpretable results: KPIs, per-module schedule, bottleneck heat-map, per-vehicle outcomes, and a side-by-side comparison with a greedy baseline.

## 4. Core techniques and why they fit

| Technique | Unit | Why it is appropriate |
|---|---|---|
| **Edmonds–Karp max-flow + min-cut** | 5 (CO4) | Charging is a capacitated assignment of vehicles to (connector, slot) pairs. An integral maximum flow is exactly an optimal feasible schedule; the min-cut certifies it and names the binding slots. Adding source arcs tier by tier protects the priority tier. |
| **Bloom filter** | 6 (CO5) | A ~60 KB filter answers "definitely not a subscriber" for most plates without touching the 50,000-plate registry; every "possibly present" hit is confirmed by an exact lookup. |
| **Randomized Quickselect** | 6 (CO5) | The priority tier is the top-k scores: expected O(n) selection instead of sorting, and a random pivot avoids the quadratic case on already-ranked lists. |

**Baselines:** greedy FCFS, DFS Ford–Fulkerson, first-pivot Quickselect, sort-then-index, HashSet.

## 5. Data and technology

Seeded synthetic data modelled on Indian conditions (valid registration plates, bimodal commuter arrivals, real EV models with their battery sizes and charge limits), a 50,000-plate registry, hand-checkable instances and an invalid-data file. Java 17+ with a Spring Boot REST API (all algorithms hand-written), a plain JavaScript frontend, JUnit 5 tests and a reproducible benchmark CLI.

## 6. Measurable success criteria

1. Allocated slots equal the min-cut capacity on every run; Edmonds–Karp equals Ford–Fulkerson on all test networks.
2. At least 5% more charging slots than greedy FCFS on congested days, at three or more sizes.
3. Tier-1 service after all tiers equals its service when solved alone.
4. Bloom filter: zero false negatives, empirical FPR within 25% of theory, every hit verified.
5. Quickselect faster than sorting at n ≥ 10⁶ and near-linear on sorted input.
6. Every invalid input is rejected with a specific message; at least 5 tests per core algorithm.

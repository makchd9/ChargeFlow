"""Regenerates docs/img/*.png from benchmarks/results.csv (python3 docs/make_charts.py)."""
import csv, collections, matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
rows = list(csv.DictReader(open("benchmarks/results.csv")))
C = ["#00a37a", "#2f6fd6", "#d9483b"]
plt.rcParams.update({"font.size": 10, "axes.spines.top": False, "axes.spines.right": False})

def line(exp, fname, title, ylabel="median time (ms)", key="median_ms"):
    rs = [r for r in rows if r["experiment"] == exp]
    series = collections.OrderedDict()
    for r in rs: series.setdefault(r["variant"], []).append((int(r["n"]), float(r[key])))
    fig, ax = plt.subplots(figsize=(6.2, 3.6))
    for i, (k, pts) in enumerate(series.items()):
        x, y = zip(*sorted(pts)); ax.plot(x, y, "o-", color=C[i % 3], label=k, lw=2)
    ax.set_xscale("log"); ax.set_yscale("log"); ax.set_xlabel("n"); ax.set_ylabel(ylabel); ax.set_title(title, loc="left", fontweight="bold")
    ax.grid(alpha=.3, which="both"); ax.legend(frameon=False, fontsize=8.5); fig.tight_layout(); fig.savefig("docs/img/" + fname, dpi=160); plt.close(fig)

line("max-flow", "flow_time.png", "Allocation network: time vs vehicles")
line("max-flow (deep random)", "flow_deep.png", "Deep random networks: Edmonds-Karp vs DFS")
line("selection (random input)", "select_random.png", "Median selection, random input")
line("selection (sorted input)", "select_sorted.png", "Median selection, sorted input (adversarial)")

# quality: slots served, max-flow vs greedy
rs = [r for r in rows if r["experiment"] == "max-flow" and r["variant"] != "Ford-Fulkerson (DFS)"]
ns = sorted({int(r["n"]) for r in rs})
mf = [float(next(r["quality"] for r in rs if int(r["n"]) == n and r["variant"].startswith("Edmonds"))) for n in ns]
gr = [float(next(r["quality"] for r in rs if int(r["n"]) == n and r["variant"].startswith("Greedy"))) for n in ns]
fig, ax = plt.subplots(figsize=(6.2, 3.4)); w = .38; xs = range(len(ns))
ax.bar([x - w/2 for x in xs], mf, w, color=C[0], label="Edmonds-Karp max-flow")
ax.bar([x + w/2 for x in xs], gr, w, color="#b8c4bf", label="Greedy FCFS")
for x, a, b in zip(xs, mf, gr): ax.text(x, a * 1.01, f"+{100*(a-b)/b:.1f}%", ha="center", fontsize=9, fontweight="bold")
ax.set_xticks(list(xs)); ax.set_xticklabels([f"{n:,}" for n in ns]); ax.set_xlabel("vehicles (hub scaled n/250)"); ax.set_ylabel("charging slots served")
ax.set_title("Solution quality: slots served", loc="left", fontweight="bold"); ax.legend(frameon=False, fontsize=8.5); fig.tight_layout(); fig.savefig("docs/img/flow_quality.png", dpi=160); plt.close(fig)

# bloom fpr
rs = [r for r in rows if r["experiment"] == "bloom-fpr"]
fig, ax = plt.subplots(figsize=(6.2, 3.4)); xs = range(len(rs))
ax.bar([x - w/2 for x in xs], [float(r["work"]) for r in rs], w, color="#b8c4bf", label="theoretical")
ax.bar([x + w/2 for x in xs], [float(r["quality"]) for r in rs], w, color=C[0], label="empirical (200k absent probes)")
ax.set_yscale("log"); ax.set_xticks(list(xs)); ax.set_xticklabels([r["variant"] for r in rs]); ax.set_ylabel("false-positive rate")
ax.set_title("Bloom filter FPR, n = 100,000", loc="left", fontweight="bold"); ax.legend(frameon=False, fontsize=8.5); fig.tight_layout(); fig.savefig("docs/img/bloom_fpr.png", dpi=160); plt.close(fig)
print("charts written")

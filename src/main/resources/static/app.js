/* ChargeFlow frontend: plain JavaScript, no build step.
 * It only collects input, calls the Java REST API and visualises the response.
 * Every algorithm (max-flow, Bloom filter, Quickselect) runs on the server. */
'use strict';

const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const fmt = (n, d = 0) => n == null || Number.isNaN(n) ? '–' : Number(n).toLocaleString('en-IN', { maximumFractionDigits: d, minimumFractionDigits: d });
const TYPES = ['CCS2', 'TYPE2_AC', 'BHARAT_DC001'];
const TYPE_LABEL = { CCS2: 'CCS2 (DC fast)', TYPE2_AC: 'Type 2 AC', BHARAT_DC001: 'Bharat DC-001' };
const ID_PREFIX = { CCS2: 'CCS2', TYPE2_AC: 'AC', BHARAT_DC001: 'BDC' };
const CAT_COLOR = { EMERGENCY: '#d9483b', FLEET: '#2f6fd6', PRIVATE: '#7d8a85' };

const state = { hub: null, vehicles: [], result: null, sort: { key: null, dir: 1 } };

// ------------------------------------------------------------------ API helper
async function api(path, opts = {}) {
  const res = await fetch(path, opts);
  const text = await res.text();
  let body = null;
  try { body = text ? JSON.parse(text) : null; } catch { body = { error: text }; }
  if (!res.ok) {
    const err = new Error(body?.error || `HTTP ${res.status}`);
    err.details = body?.details || [];
    throw err;
  }
  return body;
}
const postJson = (path, obj) => api(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(obj) });

async function busy(text, fn) {
  $('#busyText').textContent = text;
  $('#busy').classList.remove('hidden');
  try { return await fn(); }
  catch (e) { showError(e.message, e.details); throw e; }
  finally { $('#busy').classList.add('hidden'); }
}

function showError(msg, details = []) { banner(msg, details, false); }
function showInfo(msg, details = []) { banner(msg, details, true); }
function banner(msg, details, info) {
  const b = $('#banner');
  b.className = 'banner' + (info ? ' info' : '');
  b.innerHTML = `<span class="x" title="dismiss">✕</span><b>${esc(msg)}</b>` +
    (details?.length ? `<ul>${details.map(d => `<li>${esc(d)}</li>`).join('')}</ul>` : '');
  $('.x', b).onclick = () => b.classList.add('hidden');
  window.scrollTo({ top: 0, behavior: 'smooth' });
}
const clearBanner = () => $('#banner').classList.add('hidden');

// ------------------------------------------------------------------ tabs
$$('.tab').forEach(t => t.onclick = () => openTab(t.dataset.tab));
function openTab(name) {
  $$('.tab').forEach(t => t.classList.toggle('active', t.dataset.tab === name));
  $$('.tabpane').forEach(p => p.classList.toggle('active', p.id === 'tab-' + name));
  if (name === 'registry') loadRegistry();
}

// ------------------------------------------------------------------ hub editor
function slotLabel(hub, t) {
  const [h, m] = hub.openTime.split(':').map(Number);
  const mins = h * 60 + m + t * hub.slotMinutes;
  return `${String(Math.floor(mins / 60) % 24).padStart(2, '0')}:${String(mins % 60).padStart(2, '0')}`;
}

function renderHub() {
  const h = state.hub;
  $('#hubName').value = h.name;
  $('#hubOpen').value = h.openTime;
  $('#hubSlotMin').value = h.slotMinutes;
  $('#hubSlots').value = h.slotCount;
  $('#hubHours').textContent = `Operating ${h.openTime}–${slotLabel(h, h.slotCount)} · ${h.chargers.length} modules · ${h.slotCount} slots of ${h.slotMinutes} min`;
  $('#typeRows').innerHTML = TYPES.map(t => {
    const n = h.chargers.filter(c => c.connector === t).length;
    const p = h.powerKw?.[t] ?? '';
    return `<tr><td>${TYPE_LABEL[t]}</td><td><input type="number" min="0" max="200" value="${n}" data-count="${t}"></td>
      <td><input type="number" min="1" max="500" step="0.1" value="${p}" data-power="${t}"></td></tr>`;
  }).join('');
  $$('[data-count]').forEach(i => i.onchange = () => setCount(i.dataset.count, +i.value));
  $$('[data-power]').forEach(i => i.onchange = () => { state.hub.powerKw = { ...state.hub.powerKw, [i.dataset.power]: +i.value }; });
  renderMaint();
}

function setCount(type, n) {
  const h = state.hub;
  const others = h.chargers.filter(c => c.connector !== type);
  let mine = h.chargers.filter(c => c.connector === type).slice(0, Math.max(0, n));
  const ids = new Set(h.chargers.map(c => c.id));
  let k = 1;
  while (mine.length < n) {
    let id;
    do { id = `${ID_PREFIX[type]}-${String(k++).padStart(2, '0')}`; } while (ids.has(id));
    ids.add(id);
    mine.push({ id, connector: type, downSlots: [] });
  }
  h.chargers = [...others, ...mine].sort((a, b) => TYPES.indexOf(a.connector) - TYPES.indexOf(b.connector) || a.id.localeCompare(b.id));
  renderHub();
}

function renderMaint() {
  const h = state.hub;
  const rows = [];
  h.chargers.forEach(c => (c.downSlots || []).forEach((r, j) => rows.push({ c, r, j })));
  const opts = t => Array.from({ length: h.slotCount + 1 }, (_, i) => `<option value="${i}" ${i === t ? 'selected' : ''}>${slotLabel(h, i)}</option>`).join('');
  $('#maintRows').innerHTML = rows.length ? rows.map(({ c, r, j }, idx) => `<tr data-i="${idx}">
      <td>${esc(c.id)}</td><td><select data-f="from">${opts(r.from)}</select></td><td><select data-f="to">${opts(r.to)}</select></td>
      <td><button class="btn ghost small" data-del>✕</button></td></tr>`).join('')
    : `<tr><td colspan="4" class="muted">none</td></tr>`;
  $$('#maintRows tr[data-i]').forEach(tr => {
    const { r, c, j } = rows[+tr.dataset.i];
    $$('select', tr).forEach(s => s.onchange = () => { r[s.dataset.f] = +s.value; });
    $('[data-del]', tr).onclick = () => { c.downSlots.splice(j, 1); renderMaint(); };
  });
}

$('#addMaint').onclick = () => {
  const h = state.hub;
  if (!h.chargers.length) return showError('Add a charger first');
  const id = prompt('Charger id for the maintenance window:', h.chargers[0].id);
  const c = h.chargers.find(x => x.id === id);
  if (!c) return showError(`No charger with id "${id}"`);
  c.downSlots = c.downSlots || [];
  c.downSlots.push({ from: 0, to: Math.min(2, h.slotCount) });
  renderMaint();
};
['hubName', 'hubOpen', 'hubSlotMin', 'hubSlots'].forEach(id => $('#' + id).onchange = () => {
  const h = state.hub;
  h.name = $('#hubName').value;
  h.openTime = $('#hubOpen').value || '06:00';
  h.slotMinutes = +$('#hubSlotMin').value;
  h.slotCount = +$('#hubSlots').value;
  renderHub();
});
$('#hubReset').onclick = async () => { state.hub = await busy('Loading default hub…', () => api('/api/hub/default')); renderHub(); };
$('#hubFile').onchange = async e => {
  const f = e.target.files[0]; if (!f) return;
  try {
    const hub = JSON.parse(await f.text());
    await busy('Validating hub…', () => postJson('/api/hub/validate', hub));
    state.hub = hub; renderHub(); showInfo(`Loaded hub "${hub.name}"`);
  } catch (err) { if (!err.details) showError('Hub file is not valid JSON: ' + err.message); }
  e.target.value = '';
};

// ------------------------------------------------------------------ vehicles
function setVehicles(vs, source) {
  state.vehicles = vs;
  $('#vehCount').textContent = fmt(vs.length);
  $('#csvDownload').disabled = !vs.length;
  const mix = {};
  vs.forEach(v => { mix[v.connector] = (mix[v.connector] || 0) + 1; mix[v.category] = (mix[v.category] || 0) + 1; });
  $('#vehMix').innerHTML = (source ? `<span><b>${esc(source)}</b></span>` : '') +
    Object.entries(mix).map(([k, n]) => `<span>${esc(k)} <b>${n}</b></span>`).join('');
  const cols = ['plate', 'model', 'connector', 'arrival', 'departure', 'energyKwh', 'batteryPct', 'maxChargeKw', 'category'];
  $('#vehTable thead').innerHTML = `<tr>${cols.map(c => `<th>${c}</th>`).join('')}</tr>`;
  $('#vehTable tbody').innerHTML = vs.slice(0, 150).map(v => `<tr>${cols.map(c => `<td>${esc(v[c])}</td>`).join('')}</tr>`).join('') +
    (vs.length > 150 ? `<tr><td colspan="9" class="muted">… ${vs.length - 150} more</td></tr>` : '');
}

$('#genBtn').onclick = async () => {
  clearBanner();
  const r = await busy('Generating synthetic arrivals…', () => postJson('/api/vehicles/generate',
    { count: +$('#genCount').value, seed: +$('#genSeed').value, registeredShare: +$('#genReg').value, hub: state.hub }));
  setVehicles(r.vehicles, `synthetic · seed ${$('#genSeed').value}`);
};

async function parseCsv(text, name) {
  const r = await busy('Parsing CSV on the server…', () => api('/api/vehicles/parse', { method: 'POST', headers: { 'Content-Type': 'text/plain' }, body: text }));
  if (r.errors.length) {
    showError(`${r.errors.length} row(s) in ${name} could not be read. Nothing was imported: fix the file and upload it again.`, r.errors);
    return;
  }
  clearBanner();
  setVehicles(r.vehicles, name);
}
$('#csvFile').onchange = async e => { const f = e.target.files[0]; if (f) await parseCsv(await f.text(), f.name).catch(() => {}); e.target.value = ''; };
$('#csvDownload').onclick = () => {
  const cols = ['plate', 'model', 'connector', 'arrival', 'departure', 'energy_kwh', 'battery_pct', 'max_charge_kw', 'category'];
  const keys = ['plate', 'model', 'connector', 'arrival', 'departure', 'energyKwh', 'batteryPct', 'maxChargeKw', 'category'];
  const q = s => { s = String(s ?? ''); return /[",]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s; };
  const csv = [cols.join(','), ...state.vehicles.map(v => keys.map(k => q(v[k])).join(','))].join('\n');
  const a = Object.assign(document.createElement('a'), { href: URL.createObjectURL(new Blob([csv], { type: 'text/csv' })), download: 'vehicles.csv' });
  a.click();
};
$('#sampleSel').onchange = async e => {
  const v = e.target.value; e.target.value = '';
  if (!v) return;
  try {
    if (v === 'small') {
      state.hub = await (await fetch('samples/hub_small.json')).json();
      renderHub();
      await parseCsv(await (await fetch('samples/vehicles_small.csv')).text(), 'vehicles_small.csv');
      showInfo('Small hub loaded: 1 CCS2 module, 1 AC module, 4 one-hour slots. Run it with Greedy, then with Edmonds–Karp, and compare.');
    } else {
      if (v === 'vehicles_demo_220.csv') { state.hub = await api('/api/hub/default'); renderHub(); }
      await parseCsv(await (await fetch('samples/' + v)).text(), v);
      if (v === 'vehicles_invalid.csv') showInfo('Invalid sample loaded. Press "Run allocation" to see server-side validation reject it with every problem listed.');
    }
  } catch (err) { /* banner already shown */ }
};

// ------------------------------------------------------------------ run
$('#prio').oninput = () => { $('#prioVal').textContent = Math.round(+$('#prio').value * 100) + '%'; };
$('#runBtn').onclick = async () => {
  clearBanner();
  const body = {
    hub: state.hub, vehicles: state.vehicles,
    options: { algorithm: $('#algo').value, priorityFraction: +$('#prio').value, compareBaseline: $('#cmp').checked, seed: +$('#seed').value }
  };
  try {
    const r = await busy('Running ' + $('#algo').selectedOptions[0].text + '…', () => postJson('/api/allocate', body));
    state.result = r;
    renderResult(r);
    openTab('results');
  } catch { /* banner shown */ }
};

// ------------------------------------------------------------------ results
function kpi(label, value, sub, cls = '', subCls = '') {
  return `<div class="kpi ${cls}"><div class="l">${label}</div><div class="v">${value}</div>${sub ? `<div class="d ${subCls}">${sub}</div>` : ''}</div>`;
}
const signed = (n, d = 0) => (n > 0 ? '+' : '') + fmt(n, d);

function renderResult(r) {
  $('#noResult').classList.add('hidden');
  $('#result').classList.remove('hidden');
  const s = r.summary, b = r.baseline, c = r.comparison;
  const pct = s.servableDemandSlots ? 100 * s.servedSlots / s.servableDemandSlots : 0;
  $('#kpis').innerHTML = [
    kpi('Charging slots delivered', `${fmt(s.servedSlots)} <span class="small muted">/ ${fmt(s.servableDemandSlots)}</span>`, `${fmt(pct, 1)}% of servable demand`, 'accent'),
    kpi('Energy delivered', `${fmt(s.energyDeliveredKwh)} kWh`, `of ${fmt(s.energyRequestedKwh)} kWh requested`),
    kpi('Vehicles fully charged', `${fmt(s.fullyServed)} <span class="small muted">/ ${fmt(s.vehicles)}</span>`, `${fmt(s.partiallyServed)} partial · ${fmt(s.unserved)} none`),
    kpi('Hub utilisation', `${fmt(s.utilisationPct, 1)}%`, 'of all module-slots'),
    kpi('Priority tier served', s.tier1Demand ? `${fmt(s.tier1Served)} / ${fmt(s.tier1Demand)}` : '–',
      r.priority.tierSize ? `${r.priority.tierSize} vehicles · score ≥ ${fmt(r.priority.threshold, 1)}` : 'tiers disabled'),
    c ? kpi('vs greedy baseline', `${signed(c.extraSlots)} slots`, `${signed(c.extraEnergyKwh, 1)} kWh · ${signed(c.extraFullyServed)} fully charged · ${signed(c.servedGainPct, 1)}%`, '', c.extraSlots >= 0 ? 'up' : 'down')
      : kpi('Algorithm', esc(r.algorithm), ''),
    kpi('Runtime', `${fmt(r.stats.allocationMs, 2)} ms`, `allocation step · ${fmt(r.stats.totalMs, 1)} ms total`),
  ].join('');

  // comparison
  if (b) {
    const metrics = [
      ['Slots delivered', s.servedSlots, b.servedSlots],
      ['Energy (kWh)', s.energyDeliveredKwh, b.energyDeliveredKwh],
      ['Fully charged', s.fullyServed, b.fullyServed],
      ['Tier-1 slots', s.tier1Served, b.tier1Served],
    ];
    $('#cmpChart').innerHTML = barPairs(metrics, ['Max-flow', 'Greedy FCFS']);
    $('#cmpNote').textContent = 'Same tiers, same input. Greedy books each vehicle the earliest free slots in arrival order, so early long-stay cars can take slots that short-stay cars needed. Max-flow re-routes those slots through residual paths.';
  } else {
    $('#cmpChart').innerHTML = '<p class="muted">Baseline comparison is off, or the baseline itself was run.</p>';
    $('#cmpNote').textContent = '';
  }

  // min cut + stats
  $('#cutText').innerHTML = r.minCut ? esc(r.minCut.explanation) : 'Greedy has no optimality certificate. Run Edmonds–Karp to get the min cut.';
  const reg = r.registry, pr = r.priority, st = r.stats;
  $('#algoStats').innerHTML = [
    ['Algorithm', r.algorithm],
    ['Flow network', st.nodes ? `${fmt(st.nodes)} nodes · ${fmt(st.edges)} edges` : '–'],
    ['Augmenting paths', st.augmentingPaths ? fmt(st.augmentingPaths) : '–'],
    ['Arc scans (work)', fmt(st.arcScans)],
    ['Priority threshold', pr.threshold == null ? '–' : `${fmt(pr.threshold, 2)} (k = ${pr.tierSize}, ${pr.method})`],
    ['Score p50 / p90', `${fmt(pr.p50Score, 1)} / ${fmt(pr.p90Score, 1)} (Quickselect)`],
    ['Shortfall p50 / p90', `${fmt(st.shortfallP50)} / ${fmt(st.shortfallP90)} slots`],
    ['Registry screening', `${fmt(reg.checked)} plates · ${fmt(reg.definitelyAbsent)} definitely absent · ${fmt(reg.verified)} verified · ${fmt(reg.falsePositivesCaught)} false positives caught`],
    ['Exact lookups avoided', `${fmt(reg.exactLookupsAvoided)} of ${fmt(reg.checked)} (Bloom m=${fmt(reg.bloomBits)} bits, k=${reg.bloomHashes})`],
  ].map(([k, v]) => `<dt>${k}</dt><dd>${esc(v)}</dd>`).join('');

  renderHeat(r);
  renderGantt(r);
  renderOutcomes();
  $('#warnCount').textContent = r.warnings.length;
  $('#warnCard').classList.toggle('hidden', !r.warnings.length);
  $('#warnings').innerHTML = r.warnings.slice(0, 300).map(w => `<li>${esc(w)}</li>`).join('');
}

function barPairs(metrics, names) {
  const W = 560, rowH = 46, H = metrics.length * rowH + 28, L = 120, R = 70;
  let svg = `<svg viewBox="0 0 ${W} ${H}" width="100%" role="img">`;
  metrics.forEach(([label, a, b], i) => {
    const max = Math.max(a, b, 1), y = i * rowH + 6, w = W - L - R;
    svg += `<text x="0" y="${y + 20}" font-size="12" fill="#5d6b66">${label}</text>
      <rect x="${L}" y="${y}" width="${w * a / max}" height="16" rx="3" fill="#00a37a"/><text x="${L + w * a / max + 6}" y="${y + 12}" font-size="11.5" font-weight="700">${fmt(a, 0)}</text>
      <rect x="${L}" y="${y + 19}" width="${w * b / max}" height="16" rx="3" fill="#b8c4bf"/><text x="${L + w * b / max + 6}" y="${y + 31}" font-size="11.5">${fmt(b, 0)}</text>`;
  });
  svg += `<rect x="${L}" y="${H - 14}" width="10" height="10" fill="#00a37a"/><text x="${L + 14}" y="${H - 5}" font-size="11">${names[0]}</text>
    <rect x="${L + 100}" y="${H - 14}" width="10" height="10" fill="#b8c4bf"/><text x="${L + 114}" y="${H - 5}" font-size="11">${names[1]}</text></svg>`;
  return svg;
}

const tip = $('#tooltip');
function bindTips(root) {
  root.addEventListener('mousemove', e => {
    const t = e.target.closest('[data-tip]');
    if (!t) { tip.classList.add('hidden'); return; }
    tip.innerHTML = t.dataset.tip;
    tip.classList.remove('hidden');
    tip.style.left = Math.min(e.clientX + 14, innerWidth - 300) + 'px';
    tip.style.top = (e.clientY + 14) + 'px';
  });
  root.addEventListener('mouseleave', () => tip.classList.add('hidden'));
}

function renderHeat(r) {
  const T = r.slotLabels.length, cw = Math.max(22, Math.min(40, 1100 / T)), ch = 30, L = 120;
  const types = [...new Set(r.loads.map(l => l.connector))];
  const W = L + T * cw + 10, H = types.length * ch + 36;
  let svg = `<svg viewBox="0 0 ${W} ${H}" width="${W}" height="${H}">`;
  r.slotLabels.forEach((lab, t) => { if (t % 2 === 0) svg += `<text x="${L + t * cw + 2}" y="12" font-size="10" fill="#5d6b66">${lab}</text>`; });
  types.forEach((ty, i) => {
    const y = 18 + i * ch;
    svg += `<text x="0" y="${y + 19}" font-size="12" font-weight="600">${TYPE_LABEL[ty] || ty}</text>`;
    r.loads.filter(l => l.connector === ty).forEach(l => {
      const x = L + l.slot * cw;
      const u = l.capacity ? l.used / l.capacity : 0;
      const fill = l.capacity === 0 ? '#e4e8e6' : `rgba(0,163,122,${0.08 + 0.85 * u})`;
      const tipHtml = `<b>${ty} · ${l.time}</b><br>${l.used}/${l.capacity} modules used<br>${l.demandPressure} vehicles parked could use it${l.binding ? '<br><b>binding: on the min cut</b>' : ''}`;
      svg += `<g data-tip="${esc(tipHtml)}"><rect x="${x + 1}" y="${y}" width="${cw - 2}" height="${ch - 4}" rx="3" fill="${fill}" ${l.binding ? 'stroke="#15201c" stroke-width="1.6"' : ''}/>
        <text x="${x + cw / 2}" y="${y + 17}" font-size="10" text-anchor="middle" fill="${u > .55 ? '#fff' : '#15201c'}">${l.demandPressure || ''}</text></g>`;
    });
  });
  $('#heat').innerHTML = svg + '</svg>';
  bindTips($('#heat'));
}

function renderGantt(r) {
  const T = r.slotLabels.length, cw = Math.max(22, Math.min(40, 1100 / T)), ch = 20, L = 90;
  const W = L + T * cw + 10, H = r.schedule.length * ch + 24;
  const vs = r.vehicles;
  let svg = `<svg viewBox="0 0 ${W} ${H}" width="${W}" height="${H}"><defs><pattern id="hatch" width="5" height="5" patternUnits="userSpaceOnUse" patternTransform="rotate(45)"><rect width="5" height="5" fill="#fff"/><line x1="0" y1="0" x2="0" y2="5" stroke="#bbb" stroke-width="2"/></pattern></defs>`;
  r.slotLabels.forEach((lab, t) => { if (t % 2 === 0) svg += `<text x="${L + t * cw + 2}" y="12" font-size="10" fill="#5d6b66">${lab}</text>`; });
  r.schedule.forEach((row, m) => {
    const y = 16 + m * ch;
    svg += `<text x="0" y="${y + 13}" font-size="11" font-family="ui-monospace,monospace">${esc(row.chargerId)}</text>`;
    row.cells.forEach((v, t) => {
      const x = L + t * cw;
      if (v === -2) svg += `<rect x="${x + 1}" y="${y}" width="${cw - 2}" height="${ch - 3}" fill="url(#hatch)" stroke="#ccc" data-tip="<b>${esc(row.chargerId)}</b> · ${r.slotLabels[t]}<br>maintenance"/>`;
      else if (v === -1) svg += `<rect x="${x + 1}" y="${y}" width="${cw - 2}" height="${ch - 3}" fill="#f1f4f3" rx="2"/>`;
      else {
        const o = vs[v];
        const tipHtml = `<b>${esc(o.plate)}</b> · ${esc(o.model || '')}<br>${o.category} · tier ${o.tier} · score ${fmt(o.priorityScore, 1)}<br>${esc(row.chargerId)} @ ${r.slotLabels[t]}<br>window ${o.arrival}–${o.departure} · ${o.slotsServed}/${o.slotsNeeded} slots`;
        svg += `<rect class="gc" data-v="${v}" x="${x + 1}" y="${y}" width="${cw - 2}" height="${ch - 3}" rx="2" fill="${CAT_COLOR[o.category]}" ${o.tier === 1 && r.priority.tierSize ? 'stroke="#15201c" stroke-width="1.4"' : ''} data-tip="${esc(tipHtml)}" style="cursor:pointer"/>`;
      }
    });
  });
  $('#gantt').innerHTML = svg + '</svg>';
  bindTips($('#gantt'));
  $$('#gantt .gc').forEach(el => el.onclick = () => {
    const o = vs[+el.dataset.v];
    $('#vehSearch').value = o.plate; $('#vehFilter').value = ''; $('#tierFilter').value = '';
    renderOutcomes(o.plate);
    $('#outTable').scrollIntoView({ behavior: 'smooth', block: 'center' });
  });
}

const OUT_COLS = [
  ['plate', 'Plate'], ['model', 'Model'], ['category', 'Category'], ['connector', 'Connector'], ['arrival', 'Arrive'], ['departure', 'Leave'],
  ['slotsNeeded', 'Need', 1], ['slotsServed', 'Got', 1], ['energyDeliveredKwh', 'kWh', 1], ['status', 'Status'], ['tier', 'Tier', 1],
  ['priorityScore', 'Score', 1], ['registry', 'Registry'], ['sessions', 'Sessions', 1], ['moduleSwitches', 'Switches', 1], ['assignments', 'Modules'], ['note', 'Note']];

function renderOutcomes(highlight) {
  const r = state.result; if (!r) return;
  const q = $('#vehSearch').value.trim().toUpperCase(), f = $('#vehFilter').value, tf = $('#tierFilter').value;
  let rows = r.vehicles.filter(v => (!q || v.plate.includes(q) || (v.model || '').toUpperCase().includes(q)) && (!f || v.status === f) && (!tf || String(v.tier) === tf));
  const { key, dir } = state.sort;
  if (key) rows = [...rows].sort((a, b) => (a[key] > b[key] ? 1 : a[key] < b[key] ? -1 : 0) * dir);
  $('#outTable thead').innerHTML = `<tr>${OUT_COLS.map(([k, l, n]) => `<th data-k="${k}" class="${n ? 'num' : ''}">${l}${key === k ? (dir > 0 ? ' ▲' : ' ▼') : ''}</th>`).join('')}</tr>`;
  $$('#outTable th').forEach(th => th.onclick = () => {
    const k = th.dataset.k; if (k === 'assignments') return;
    state.sort = { key: k, dir: state.sort.key === k ? -state.sort.dir : 1 };
    renderOutcomes();
  });
  const shown = rows.slice(0, 1000);
  $('#outTable tbody').innerHTML = shown.map(v => {
    const mods = [...new Set(v.assignments.map(a => a.chargerId))].join(', ');
    const times = v.assignments.length ? `${v.assignments[0].time}…` : '';
    return `<tr class="${v.plate === highlight ? 'hl' : ''}">
      <td><code>${esc(v.plate)}</code></td><td>${esc(v.model)}</td><td><span class="cat ${v.category}">${v.category}</span></td><td>${v.connector}</td>
      <td>${v.arrival}</td><td>${v.departure}</td><td class="num">${v.slotsNeeded}</td><td class="num">${v.slotsServed}</td>
      <td class="num">${fmt(v.energyDeliveredKwh, 1)} / ${fmt(v.energyRequestedKwh, 1)}</td><td><span class="st ${v.status}">${v.status}</span></td>
      <td class="num">${v.tier}</td><td class="num">${fmt(v.priorityScore, 1)}</td><td><span class="reg ${v.registry}">${v.registry.replace('_', ' ').toLowerCase()}</span></td>
      <td class="num">${v.sessions}</td><td class="num">${v.moduleSwitches}</td><td title="${esc(v.assignments.map(a => a.time + ' ' + a.chargerId).join('\n'))}">${esc(mods)} <span class="muted">${times}</span></td>
      <td class="muted">${esc(v.note || '')}</td></tr>`;
  }).join('') + (rows.length > 1000 ? `<tr><td colspan="17" class="muted">showing 1000 of ${rows.length}, so narrow the filter</td></tr>` : '');
}
['vehSearch', 'vehFilter', 'tierFilter'].forEach(id => $('#' + id).oninput = () => renderOutcomes());

// ------------------------------------------------------------------ registry
async function loadRegistry() {
  try { renderRegStats(await api('/api/registry')); } catch (e) { showError(e.message, e.details); }
}
function renderRegStats(s) {
  $('#regStats').innerHTML = [
    ['Registered plates (n)', fmt(s.registeredPlates)],
    ['Bloom bits (m)', `${fmt(s.bitCount)} (${fmt(s.bitCount / Math.max(1, s.registeredPlates), 2)} bits per plate)`],
    ['Hash functions (k)', s.hashCount],
    ['Bloom memory', `${fmt(s.memoryBytes / 1024, 1)} KiB vs ~${fmt(s.exactStoreApproxBytes / 1048576, 1)} MiB for an in-memory HashSet`],
    ['Target / theoretical FPR', `${s.targetFpr} / ${fmt(100 * s.theoreticalFpr, 3)}%`],
    ['Fill ratio', `${fmt(100 * s.fillRatio, 1)}% of bits set`],
    ['Checks since rebuild', `${fmt(s.checks)} · ${fmt(s.definitelyAbsent)} definitely absent · ${fmt(s.verified)} verified · ${fmt(s.falsePositives)} false positives caught`],
    ['Exact-store lookups', `${fmt(s.exactLookups)} (only on Bloom hits)`],
  ].map(([k, v]) => `<dt>${k}</dt><dd>${esc(v)}</dd>`).join('');
}
$('#regGen').onclick = async () => {
  const s = await busy('Building Bloom filter…', () => postJson('/api/registry/generate', { size: +$('#regSize').value, fpr: +$('#regFpr').value })).catch(() => null);
  if (s) { renderRegStats(s); showInfo('Registry rebuilt. Regenerate the vehicles so some plates match the new subscribers.'); }
};
$('#regFile').onchange = async e => {
  const f = e.target.files[0]; if (!f) return;
  const plates = (await f.text()).split(/\r?\n/).map(l => l.split(',')[0].trim()).filter(p => p && !/^plate$/i.test(p));
  const s = await busy('Building Bloom filter…', () => postJson('/api/registry/load', { plates, fpr: +$('#regFpr').value })).catch(() => null);
  if (s) { renderRegStats(s); showInfo(`Registry loaded from ${f.name}`); }
  e.target.value = '';
};
$('#plateBtn').onclick = async () => {
  const p = $('#plateIn').value; if (!p.trim()) return;
  const r = await busy('Checking…', () => api('/api/registry/check?plate=' + encodeURIComponent(p))).catch(() => null);
  if (!r) return;
  const cls = r.status === 'VERIFIED' ? 'ok' : r.status === 'FALSE_POSITIVE' ? 'fp' : 'no';
  $('#plateOut').innerHTML = `<div><code>${esc(r.plate)}</code> ${r.validFormat ? '' : '<span class="st UNSERVED">not a valid plate format</span>'}</div>
    <div class="ans ${cls}"><b>Bloom filter:</b> ${r.bloom.replace('_', ' ').toLowerCase()} → <b>${r.status.replace('_', ' ')}</b><br>${esc(r.meaning)}</div>`;
  loadRegistry();
};
$('#plateIn').onkeydown = e => { if (e.key === 'Enter') $('#plateBtn').click(); };
$('#fprBtn').onclick = async () => {
  const rows = await busy('Running FPR experiment (50k plates, 100k absent probes)…', () => api('/api/benchmark/fpr')).catch(() => null);
  if (!rows) return;
  $('#fprChart').innerHTML = fprChart(rows);
};
function fprChart(rows) {
  const W = 520, H = 230, L = 60, B = 40, T = 14;
  const vals = rows.flatMap(r => [r.work, r.quality]).filter(v => v > 0);
  const lo = Math.log10(Math.min(...vals) / 2), hi = Math.log10(Math.max(...vals) * 2);
  const y = v => T + (H - T - B) * (1 - (Math.log10(v) - lo) / (hi - lo));
  const bw = (W - L - 10) / rows.length;
  let s = `<svg viewBox="0 0 ${W} ${H}" width="100%">`;
  for (let e = Math.ceil(lo); e <= Math.floor(hi); e++) s += `<line x1="${L}" x2="${W}" y1="${y(10 ** e)}" y2="${y(10 ** e)}" stroke="#eee"/><text x="${L - 6}" y="${y(10 ** e) + 4}" font-size="10" text-anchor="end">${10 ** e >= 0.01 ? (10 ** e * 100) + '%' : (10 ** e * 100).toPrecision(1) + '%'}</text>`;
  rows.forEach((r, i) => {
    const x = L + i * bw + 8;
    s += `<rect x="${x}" y="${y(r.work)}" width="${bw / 2 - 10}" height="${H - B - y(r.work)}" fill="#b8c4bf"/>
      <rect x="${x + bw / 2 - 8}" y="${y(Math.max(r.quality, 1e-6))}" width="${bw / 2 - 10}" height="${H - B - y(Math.max(r.quality, 1e-6))}" fill="#00a37a"/>
      <text x="${x + bw / 2 - 10}" y="${H - B + 14}" font-size="11" text-anchor="middle">${esc(r.variant)}</text>
      <text x="${x + bw / 2 - 10}" y="${H - B + 27}" font-size="10" fill="#5d6b66" text-anchor="middle">${fmt(r.quality * 100, 3)}% measured</text>`;
  });
  s += `<rect x="${L}" y="2" width="10" height="10" fill="#b8c4bf"/><text x="${L + 14}" y="11" font-size="11">theoretical (1−e^(−kn/m))^k</text>
    <rect x="${L + 200}" y="2" width="10" height="10" fill="#00a37a"/><text x="${L + 214}" y="11" font-size="11">empirical (absent probes)</text></svg>`;
  return s;
}

// ------------------------------------------------------------------ benchmarks
const PALETTE = ['#00a37a', '#2f6fd6', '#d9483b', '#8a5cd6', '#b26b00'];
$$('[data-bench]').forEach(b => b.onclick = async () => {
  const kind = b.dataset.bench;
  const rows = await busy(`Running ${kind} benchmark on the server… (up to ~20 s)`, () => api('/api/benchmark/' + kind)).catch(() => null);
  if (!rows) return;
  const groups = [...new Set(rows.map(r => r.experiment))];
  let html = `<div class="bench-block"><h3>${esc(b.textContent)}</h3><div class="bench-charts">`;
  for (const g of groups) {
    const gr = rows.filter(r => r.experiment === g && r.medianMs > 0);
    if (gr.length) html += `<div>${lineChart(gr, g)}</div>`;
  }
  html += `</div><div class="tablewrap short"><table class="data"><thead><tr><th>experiment</th><th>variant</th><th class="num">n</th><th class="num">median ms</th><th class="num">work</th><th class="num">quality</th><th>note</th></tr></thead><tbody>` +
    rows.map(r => `<tr><td>${esc(r.experiment)}</td><td>${esc(r.variant)}</td><td class="num">${fmt(r.n)}</td><td class="num">${fmt(r.medianMs, 3)}</td><td class="num">${fmt(r.work)}</td><td class="num">${r.quality % 1 ? fmt(r.quality, 5) : fmt(r.quality)}</td><td class="muted">${esc(r.note)}</td></tr>`).join('') +
    `</tbody></table></div></div>`;
  $('#benchOut').insertAdjacentHTML('afterbegin', html);
});

function lineChart(rows, title) {
  const W = 480, H = 260, L = 56, B = 34, T = 26, R = 12;
  const series = [...new Set(rows.map(r => r.variant))];
  const xs = rows.map(r => r.n), ys = rows.map(r => r.medianMs);
  const lx0 = Math.log10(Math.min(...xs)), lx1 = Math.log10(Math.max(...xs)) + 1e-9;
  const ly0 = Math.log10(Math.min(...ys) / 1.5), ly1 = Math.log10(Math.max(...ys) * 1.5);
  const X = v => L + (W - L - R) * (Math.log10(v) - lx0) / (lx1 - lx0 || 1);
  const Y = v => T + (H - T - B) * (1 - (Math.log10(v) - ly0) / (ly1 - ly0 || 1));
  let s = `<svg viewBox="0 0 ${W} ${H + 18 * Math.ceil(series.length / 2)}" width="100%"><text x="${L}" y="14" font-size="12" font-weight="700">${esc(title)} · time vs n (log–log)</text>`;
  for (let e = Math.floor(ly0); e <= Math.ceil(ly1); e++) {
    const v = 10 ** e; if (Math.log10(v) < ly0 || Math.log10(v) > ly1) continue;
    s += `<line x1="${L}" x2="${W - R}" y1="${Y(v)}" y2="${Y(v)}" stroke="#eee"/><text x="${L - 5}" y="${Y(v) + 4}" font-size="10" text-anchor="end">${v >= 1 ? v : v.toPrecision(1)} ms</text>`;
  }
  [...new Set(xs)].forEach(n => s += `<text x="${X(n)}" y="${H - B + 14}" font-size="10" text-anchor="middle">${n >= 1e6 ? n / 1e6 + 'M' : n >= 1e3 ? n / 1e3 + 'k' : n}</text>`);
  series.forEach((name, i) => {
    const pts = rows.filter(r => r.variant === name).sort((a, b) => a.n - b.n);
    const c = PALETTE[i % PALETTE.length];
    s += `<polyline fill="none" stroke="${c}" stroke-width="2" points="${pts.map(p => `${X(p.n)},${Y(p.medianMs)}`).join(' ')}"/>`;
    pts.forEach(p => s += `<circle cx="${X(p.n)}" cy="${Y(p.medianMs)}" r="3" fill="${c}"><title>${esc(name)} n=${p.n}: ${p.medianMs} ms</title></circle>`);
    const lx = L + (i % 2) * 220, ly = H + 4 + Math.floor(i / 2) * 18;
    s += `<rect x="${lx}" y="${ly}" width="10" height="10" fill="${c}"/><text x="${lx + 14}" y="${ly + 9}" font-size="11">${esc(name)}</text>`;
  });
  return s + '</svg>';
}

// ------------------------------------------------------------------ boot
(async function init() {
  try {
    state.hub = await api('/api/hub/default');
    renderHub();
    const r = await postJson('/api/vehicles/generate', { count: 220, seed: 2026, registeredShare: 0.4, hub: state.hub });
    setVehicles(r.vehicles, 'synthetic · seed 2026');
  } catch (e) {
    showError('Cannot reach the ChargeFlow backend. Is it running on this port?', [e.message]);
  }
})();

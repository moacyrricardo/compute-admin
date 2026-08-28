"use strict";
// spec-057 headless render check: the client fold of the four footprint probes.
// Loads the REAL app.js in a minimal DOM stub, exposes its internals, and asserts the
// per-axis math (PSS-not-RSS, CPU as a RATE with a PID-churn guard, native disk ÷ the
// root-FS denominator), the degrade-and-label posture, and — critically — that a native
// disk numerator subtracts from the spec-041 OTHER segment (computeOther is untouched).
const fs = require("fs");
const vm = require("vm");
const path = process.argv[2] || __dirname + "/../../main/resources/static/app.js";
let src = fs.readFileSync(path, "utf8");

src = src.replace(
  "  window.addEventListener(\"hashchange\", route);\n  route();\n})();",
  "  window.addEventListener(\"hashchange\", route);\n" +
  "  globalThis.__ca = { buildConsumers, applyConsumerReading, computeOther, checkKind," +
  " parsePss, parseStatTicks, parseDuBytes, ramConfidenceLow, clampPct };\n})();"
);
if (src.indexOf("globalThis.__ca") < 0) { console.error("FAIL: could not inject test hook"); process.exit(1); }

// ---- minimal DOM stub (app.js touches document/window at load) ----
function makeNode(tag) {
  return {
    tagName: tag, children: [], _text: null, style: {}, className: "", attrs: {}, value: "",
    classList: { _s: {}, add(c){this._s[c]=1;}, remove(c){delete this._s[c];}, contains(c){return !!this._s[c];}, toggle(c){this._s[c]?delete this._s[c]:this._s[c]=1;} },
    set textContent(v){ this._text = String(v); this.children = []; },
    get textContent(){ if (this._text != null) return this._text; return this.children.map(c => c.textContent).join(""); },
    appendChild(k){ k._parent = this; this.children.push(k); return k; },
    setAttribute(k,v){ this.attrs[k]=v; },
    addEventListener(){}, closest(){ return null; }
  };
}
const document = {
  createElement: t => makeNode(t),
  createTextNode: t => { const n = makeNode("#text"); n._text = String(t); return n; },
  getElementById: () => makeNode("div")
};
const window = { addEventListener(){}, location:{ hash:"" } };
const ctx = {
  document, window, location: window.location, console,
  localStorage: { getItem:()=>null, setItem(){}, removeItem(){} },
  setTimeout: ()=>0, clearTimeout: ()=>{}, fetch: ()=>Promise.resolve({}),
  TextDecoder: function(){ this.decode=()=>""; }, AbortController: function(){ this.abort=()=>{}; this.signal={}; },
  globalThis: null
};
ctx.globalThis = ctx;
vm.createContext(ctx);
vm.runInContext(src, ctx);
const ca = ctx.__ca;
if (!ca) { console.error("FAIL: __ca not exposed"); process.exit(1); }

let failed = 0;
function assert(cond, msg) { if (!cond) { console.error("FAIL: " + msg); failed++; } }

// ---- 1. PSS is summed, NOT RSS-in-a-sum ----
// Two workers each Pss 500 MB but RSS 900 MB (shared libraries): PSS sums to 1000 MB (honest),
// whereas naively summing RSS would overstate at 1800 MB. parsePss proves we sum PSS.
const pssOut = "## pid 100\nPss: 512000 kB\n## pid 101\nPss: 512000 kB\n";
assert(ca.parsePss(pssOut) === 1000, "PSS must sum to 1000 MB, got " + ca.parsePss(pssOut));

// ---- 2. CPU is a RATE with a starttime (PID-churn) guard ----
// pid 100 keeps starttime 9 across both samples → Δticks counted (400). pid 101 is REUSED
// (starttime 9 → 77) between samples → its Δticks are DISCARDED (guard), not counted as a
// huge negative/positive spike. Σ=400 ticks / 100 CLK_TCK / 2s = 2.0 cores = 200%.
const churn = "clk_tck=100\nt0=10.0\n## s0\npid=100 ticks=100 starttime=9\npid=101 ticks=900 starttime=9\n"
  + "t1=12.0\n## s1\npid=100 ticks=500 starttime=9\npid=101 ticks=50 starttime=77\n";
assert(Math.round(ca.parseStatTicks(churn)) === 200,
  "CPU-rate must guard PID churn (only pid 100's 400 ticks count → 200%), got " + ca.parseStatTicks(churn));
// A zero / non-positive measured Δt cannot yield a rate → null (— honesty).
assert(ca.parseStatTicks("clk_tck=100\nt0=5.0\n## s0\npid=1 ticks=1 starttime=1\nt1=5.0\n## s1\npid=1 ticks=9 starttime=1\n") === null,
  "a non-positive Δt must yield null");

// ---- 2b. CPU rate: the plain (no-churn) multi-PID sum honours clk_tck and the MEASURED Δt ----
// Two stable PIDs (starttime unchanged): Δticks 300 + 100 = 400 / 100 CLK_TCK / 4s measured
// = 1.0 core = 100%. Proves the ordinary rate math independent of the churn-guard branch.
const rate = "clk_tck=100\nt0=100.0\n## s0\npid=1 ticks=200 starttime=5\npid=2 ticks=50 starttime=6\n"
  + "t1=104.0\n## s1\npid=1 ticks=500 starttime=5\npid=2 ticks=150 starttime=6\n";
assert(Math.round(ca.parseStatTicks(rate)) === 100,
  "CPU-rate plain sum = 400 ticks / 100 / 4s = 100%, got " + ca.parseStatTicks(rate));

// ---- 3. du bytes parse (already bytes, -b) ----
assert(ca.parseDuBytes("app_folder=/opt/x\ndu_bytes=1073741824\n") === 1073741824, "parseDuBytes");
assert(ca.parseDuBytes("no dir /opt/x") === null, "no-dir sentinel → null");
// A du-timeout degrade still folds a value: the fallback emits disk_confidence=low PLUS a
// --max-depth=1 du_bytes sum (a labelled LOWER bound), so the disk axis fills, never null.
assert(ca.parseDuBytes("disk_confidence=low reason=du-timeout\ndu_bytes=500\n") === 500,
  "a du-timeout lower bound still yields du_bytes, got " + ca.parseDuBytes("disk_confidence=low reason=du-timeout\ndu_bytes=500\n"));

// ---- 4. degrade-and-label: procfs denied → RSS upper-bound, flagged low ----
const denied = "## pid 100\nVmRSS: 4096000 kB\nram_confidence=low reason=procfs-denied\n";
assert(ca.parsePss(denied) === null, "a denied read has no PSS");
assert(ca.ramConfidenceLow(denied) === true, "ram_confidence=low must be detected");
const machine = { machineId:"m", apps:[ { appName:"orders", framework:"spring", port:8080, checks:[
  { id:"ram1", name:"ram", approvalState:"APPROVED", changedSinceApproval:false }
] } ], consumers:[ { id:"orders", name:"orders", role:"APP", source:"HOST", ram:null, cpu:null, disk:null, services:[] } ] };
const nc = ca.buildConsumers(machine)[0];
ca.applyConsumerReading(nc, { ram1: { orders: { stdout: denied, exit: 0 } } }, 8000, 4, 100);
assert(nc.ram === 50, "degraded RAM still fills from RSS upper-bound (4000 MB / 8000 = 50%), got " + nc.ram);
assert(nc._ramLow === true, "a degraded reading must be flagged _ramLow for the 059 badge");

// ---- 5. the native disk axis SUBTRACTS from spec-041 OTHER (computeOther untouched) ----
// A native app fills disk=20% via the same root-FS denominator; computeOther's attr() then
// subtracts it so OTHER shrinks — the load-bearing spec-041 integration.
const diskMachine = { machineId:"m2", apps:[ { appName:"orders", framework:"spring", port:8080, checks:[
  { id:"disk1", name:"disk", approvalState:"APPROVED", changedSinceApproval:false }
] } ], consumers:[ { id:"orders", name:"orders", role:"APP", source:"HOST", ram:null, cpu:null, disk:null, services:[] } ] };
const dc = ca.buildConsumers(diskMachine)[0];
const DENOM = 100 * 1024 * 1024 * 1024; // 100 GiB root FS
ca.applyConsumerReading(dc, { disk1: { orders: { stdout: "du_bytes=" + (20 * 1024 * 1024 * 1024) + "\n", exit: 0 } } }, 8000, 4, DENOM);
assert(dc.disk === 20, "native disk = 20 GiB / 100 GiB = 20%, got " + dc.disk);
assert(dc._diskLow === false, "a full du reading is not flagged low");
// A du-timeout degrade fills the axis from the lower bound AND flags _diskLow (parity with the
// RAM RSS-fallback's _ramLow) so 059 can badge it — Decision 6 degrade-and-label for disk.
const dcLow = ca.buildConsumers(diskMachine)[0];
ca.applyConsumerReading(dcLow, { disk1: { orders: { stdout: "disk_confidence=low reason=du-timeout\ndu_bytes=" + (10 * 1024 * 1024 * 1024) + "\n", exit: 0 } } }, 8000, 4, DENOM);
assert(dcLow.disk === 10, "du-timeout lower bound still fills disk (10 GiB / 100 GiB = 10%), got " + dcLow.disk);
assert(dcLow._diskLow === true, "a du-timeout lower bound must be flagged _diskLow for the 059 badge");
const other = ca.computeOther("m2", { ram: null, cpu: null, disk: 70 }, [dc]);
assert(other.disk === 50, "OTHER disk must be host 70% − native 20% = 50% (auto-subtract), got " + other.disk);

// ---- 6. the sudo re-probe classifies as its axis (so pollConsumers can exclude it by .sudo) ----
assert(ca.checkKind({ name: "ram (sudo re-probe)" }) === "ram", "sudo ram re-probe is a ram-kind check");
assert(ca.checkKind({ name: "disk (sudo re-probe)" }) === "disk", "sudo disk re-probe is a disk-kind check");

if (failed) { console.error("FAILED: " + failed + " assertion(s)"); process.exit(1); }
console.log("PASS: spec-057 — PSS summed (not RSS), CPU-rate (plain sum + PID-churn guard), du bytes (incl. du-timeout lower bound), degrade-and-label (RSS upper-bound, _ramLow), native disk subtracts from spec-041 OTHER, sudo re-probes classify by axis");

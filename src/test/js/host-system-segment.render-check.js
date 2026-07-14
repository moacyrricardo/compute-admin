"use strict";
// spec-041 headless render check: an app-less host with its `monitor machine` vitals
// present must render a NON-EMPTY "other / system" segment carrying real usage (not
// "No discovered consumers on this host"), and must degrade an absent host vital to —
// (never a bogus 0). Loads the REAL app.js in a minimal DOM stub, exposes its internals,
// and asserts on the parsers, the computed OTHER consumer, and its rendered textContent.
const fs = require("fs");
const vm = require("vm");
const path = process.argv[2] || __dirname + "/../../main/resources/static/app.js";
let src = fs.readFileSync(path, "utf8");

// Expose the closure internals + skip the boot route() (no login/router in this stub).
src = src.replace(
  "  window.addEventListener(\"hashchange\", route);\n  route();\n})();",
  "  window.addEventListener(\"hashchange\", route);\n" +
  "  globalThis.__ca = { computeOther, parseHostCpu, parseDfUsedPct, parseMem," +
  " parseDfTotal, consumerLegend, axisMeter, clampPct };\n})();"
);
if (src.indexOf("globalThis.__ca") < 0) { console.error("FAIL: could not inject test hook"); process.exit(1); }

// ---- minimal DOM stub (shared shape with docker-consumer-metrics.render-check.js) ----
function makeNode(tag) {
  return {
    tagName: tag, children: [], _text: null, style: {}, className: "", attrs: {}, value: "",
    classList: { _s: {}, add(c){this._s[c]=1;}, remove(c){delete this._s[c];}, contains(c){return !!this._s[c];}, toggle(c){this._s[c]?delete this._s[c]:this._s[c]=1;} },
    set textContent(v){ this._text = String(v); this.children = []; },
    get textContent(){ if (this._text != null) return this._text; return this.children.map(c => c.textContent).join(""); },
    get firstChild(){ return this.children[0] || null; },
    get parentNode(){ return this._parent || null; },
    appendChild(k){ k._parent = this; this.children.push(k); return k; },
    removeChild(k){ const i = this.children.indexOf(k); if (i>=0) this.children.splice(i,1); return k; },
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
function assert(cond, msg){ if (!cond){ console.error("FAIL:", msg); process.exit(1); } }

// ---- host-vital parsers (the used values spec-041 stops discarding) ----
// free -m already parses `used`; assert it is surfaced.
const mem = ca.parseMem("              total        used        free\nMem:           16000        12000        4000\nSwap:           2000          0        2000\n");
assert(mem && mem.mem.total === 16000 && mem.mem.used === 12000, "parseMem must surface used: " + JSON.stringify(mem));

// top -bn1: host CPU used% = 100 − idle.
assert(ca.parseHostCpu("%Cpu(s):  1.0 us,  0.5 sy,  0.0 ni, 98.0 id,  0.5 wa") === 2,
  "parseHostCpu 100-98=2, got " + ca.parseHostCpu("%Cpu(s):  1.0 us,  0.5 sy,  0.0 ni, 98.0 id,  0.5 wa"));
assert(ca.parseHostCpu("no cpu line here") === null, "unparseable cpu → null");
assert(ca.parseHostCpu("") === null && ca.parseHostCpu(null) === null, "empty/absent cpu → null");

// df -h: Use% of the `/` (data-root) row.
const df = "Filesystem Size Used Avail Use% Mounted on\n/dev/sda1 50G 35G 13G 70% /\n";
assert(ca.parseDfUsedPct(df) === 70, "parseDfUsedPct root Use% = 70, got " + ca.parseDfUsedPct(df));
assert(ca.parseDfUsedPct("") === null, "empty df → null used%");

// ---- computeOther: a NO-APP host with all three vitals present ----
// RAM used 12000/16000 = 75%, CPU 2%, disk 70%; no attributed consumers ⇒ OTHER = host used.
const hostUsed = { ram: mem.mem.used / mem.mem.total * 100, cpu: 2, disk: 70 };
const bare = ca.computeOther("m1", hostUsed, []);
assert(bare, "an app-less host with host vitals must yield an OTHER segment, got null");
assert(bare.role === "OTHER" && bare.bucket === "SYSTEM" && bare._hue === "--c-system",
  "OTHER identity: " + JSON.stringify({ role: bare.role, bucket: bare.bucket, hue: bare._hue }));
assert(bare.ram === 75 && bare.cpu === 2 && bare.disk === 70,
  "bare-host OTHER axes = host used, got " + JSON.stringify({ ram: bare.ram, cpu: bare.cpu, disk: bare.disk }));

// Renders NON-EMPTY: axisMeter shows "used 75%", legend chip shows the name + numeric axes.
const ramMeter = ca.axisMeter("RAM", [bare], "ram", ()=>{}).textContent;
assert(/used 75%/.test(ramMeter), "RAM axis meter must read used 75% for the bare host: " + ramMeter);
const legend = ca.consumerLegend([bare], ()=>{}).textContent;
assert(/other \/ system/.test(legend), "legend must name the other/system segment: " + legend);
assert(/75%/.test(legend) && /2%/.test(legend) && /70%/.test(legend),
  "legend must show numeric OTHER axes (75/2/70), not —: " + legend);

// ---- computeOther with attributed apps: OTHER = host used − Σ attributed, clamped ----
const named = [ { ram: 20, cpu: 1, disk: 10 }, { ram: 15, cpu: null, disk: 5 } ];
const withApps = ca.computeOther("m2", { ram: 90, cpu: 30, disk: 70 }, named);
assert(withApps.ram === 55, "OTHER ram = 90 − (20+15) = 55, got " + withApps.ram);   // 35 attributed
assert(withApps.cpu === 29, "OTHER cpu = 30 − 1 = 29 (null attribution ignored), got " + withApps.cpu);
assert(withApps.disk === 55, "OTHER disk = 70 − (10+5) = 55, got " + withApps.disk);

// Clamp at zero: attributed exceeds host used ⇒ 0, never negative.
const clamped = ca.computeOther("m3", { ram: 10, cpu: null, disk: null }, [ { ram: 40, cpu: 0, disk: 0 } ]);
assert(clamped.ram === 0, "OTHER ram clamps to 0 when attributed > host used, got " + clamped.ram);

// ---- honesty: an absent host vital ⇒ that axis is null (renders —), not a bogus 0 ----
const partial = ca.computeOther("m4", { ram: 88, cpu: null, disk: null }, []);
assert(partial.ram === 88 && partial.cpu === null && partial.disk === null,
  "absent CPU/disk vitals ⇒ null axes: " + JSON.stringify({ ram: partial.ram, cpu: partial.cpu, disk: partial.disk }));
const partLegend = ca.consumerLegend([partial], ()=>{}).textContent;
assert(/88%/.test(partLegend) && /—/.test(partLegend),
  "partial OTHER must show 88% for RAM and — for the absent axes: " + partLegend);

// No host vital at all ⇒ no phantom OTHER segment.
assert(ca.computeOther("m5", { ram: null, cpu: null, disk: null }, []) === null,
  "a host with no vitals must yield NO OTHER segment (null)");

console.log("PASS: spec-041 — host used RAM/CPU/disk parsed (free used, top 100−id, df Use%); an app-less host with vitals renders a non-empty other/system segment (75/2/70), attributed apps subtract + clamp at 0, and an absent host vital degrades that axis to — (no OTHER when no vital at all)");

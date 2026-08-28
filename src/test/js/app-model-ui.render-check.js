"use strict";
// spec-059 headless render check (Surface 2 — footprint dashboard).
// Loads the REAL app.js in a minimal DOM stub and asserts the degrade-and-label
// confidence rendering: a spec-057 bounded footprint reading (`_ramLow`/`_diskLow`)
// surfaces a TEXT "low confidence" caveat on the consumer axis meter, the drawer, and
// the legend chip — so the confidence signal is never carried by colour alone (WCAG AA).
// A precise (un-flagged) reading renders the bare meter; a null axis renders — with no
// badge. CPU has no fallback kind, so it never flags.
const fs = require("fs");
const vm = require("vm");
const path = process.argv[2] || __dirname + "/../../main/resources/static/app.js";
let src = fs.readFileSync(path, "utf8");

src = src.replace(
  "  window.addEventListener(\"hashchange\", route);\n  route();\n})();",
  "  window.addEventListener(\"hashchange\", route);\n" +
  "  globalThis.__ca = { buildConsumers, consumerAxis, consumerLegend, confMeter," +
  " axisLow, meter };\n})();"
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
// The stub node aggregates all descendant text through its textContent getter.
function txt(node) { return node ? node.textContent : ""; }
// Recursively collect className tokens across the node tree (badge detection).
function classes(node, acc) {
  acc = acc || [];
  if (!node) return acc;
  if (node.className) acc.push(node.className);
  (node.children || []).forEach(c => classes(c, acc));
  return acc;
}
function hasClass(node, cls) { return classes(node).some(cn => (" " + cn + " ").indexOf(" " + cls + " ") >= 0); }

// ---- 1. axisLow: only a KNOWN, bounded axis flags; CPU never; a precise axis never ----
const low = { ram: 40, cpu: 30, disk: 10, _ramLow: true, _diskLow: true };
const hi  = { ram: 40, cpu: 30, disk: 10, _ramLow: false, _diskLow: false };
assert(ca.axisLow(low, "ram") === true, "ram bounded reading flags low");
assert(ca.axisLow(low, "disk") === true, "disk bounded reading flags low");
assert(ca.axisLow(low, "cpu") === false, "cpu has no fallback kind → never low");
assert(ca.axisLow(hi, "ram") === false && ca.axisLow(hi, "disk") === false, "a precise reading is not low");

// ---- 2. confMeter appends the TEXT caveat for a bounded axis, and a badge tag ----
const ramMeter = ca.confMeter("RAM", { ram: 40, _ramLow: true, _rssMb: 512 }, "512 MiB RSS");
assert(txt(ramMeter).indexOf("low confidence") >= 0, "a _ramLow axis renders the 'low confidence' text");
assert(txt(ramMeter).indexOf("RSS upper bound") >= 0, "the RAM caveat explains the RSS upper bound");
assert(hasClass(ramMeter, "tag--low"), "the low-confidence caveat uses the .tag--low badge");
assert(txt(ramMeter).indexOf("512 MiB RSS") >= 0, "the RSS sub-line is preserved");

const diskMeter = ca.confMeter("Disk", { disk: 10, _diskLow: true });
assert(txt(diskMeter).indexOf("low confidence") >= 0, "a _diskLow axis renders the 'low confidence' text");
assert(txt(diskMeter).indexOf("du lower bound") >= 0, "the Disk caveat explains the du lower bound");

// ---- 3. a precise reading renders the bare meter, no caveat/badge ----
const precise = ca.confMeter("RAM", { ram: 40, _ramLow: false, _rssMb: 512 }, "512 MiB RSS");
assert(txt(precise).indexOf("low confidence") < 0, "a precise reading has NO confidence caveat");
assert(!hasClass(precise, "tag--low"), "a precise reading has NO low badge");

// ---- 4. consumerAxis: a null axis renders — with no confidence badge (honesty) ----
const nullAxis = ca.consumerAxis("Disk", { disk: null, _anyApproved: true });
assert(txt(nullAxis).indexOf("—") >= 0, "a null axis renders an em dash");
assert(!hasClass(nullAxis, "tag--low"), "a null axis carries no low badge (nothing to caveat)");

// ---- 5. consumerLegend: a bounded consumer carries the 'est.' chip; a precise one does not ----
const legendLow = ca.consumerLegend(
  [{ id: "orders", name: "orders", ram: 40, cpu: 30, disk: 10, _ramLow: true, _diskLow: false }],
  function () {});
assert(txt(legendLow).indexOf("est.") >= 0, "a bounded consumer's legend chip carries the 'est.' marker");
assert(hasClass(legendLow, "lg-low"), "the legend estimate marker uses the .lg-low class");
const legendHi = ca.consumerLegend(
  [{ id: "orders", name: "orders", ram: 40, cpu: 30, disk: 10, _ramLow: false, _diskLow: false }],
  function () {});
assert(txt(legendHi).indexOf("est.") < 0, "a precise consumer's legend chip has no 'est.' marker");

// ---- 6. the flags flow from the real poll fold (buildConsumers copies axis state) ----
// buildConsumers copies ram/cpu/disk off the wire consumer; the _ramLow/_diskLow flags are
// set later by applyConsumerReading (proven in the spec-057 check). Here we assert the render
// path reads whatever flags are present on the model object it is handed.
const model = ca.buildConsumers({ consumers: [{ id: "x", name: "x", role: "APP", source: "HOST",
  ram: 50, cpu: null, disk: 20, services: [] }] })[0];
model._diskLow = true;
const axis = ca.consumerAxis("Disk", model);
assert(txt(axis).indexOf("low confidence") >= 0, "consumerAxis renders the low caveat off the model flag");

if (failed) { console.error("FAILED: " + failed + " assertion(s)"); process.exit(1); }
console.log("PASS: spec-059 Surface 2 — degrade-and-label confidence renders as TEXT on the axis meter, drawer, and legend (never colour alone); precise & null axes carry no caveat; CPU never flags");

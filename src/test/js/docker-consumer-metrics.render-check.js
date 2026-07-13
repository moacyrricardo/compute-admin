"use strict";
// spec-037 headless render check: a docker consumer with a parsed `docker stats`
// reading must show NUMERIC axes (not —) in the legend/card. Loads the REAL app.js
// in a minimal DOM stub, exposes its internals, and asserts on rendered textContent.
const fs = require("fs");
const vm = require("vm");
// Defaults to the real app.js relative to this script; override with argv[2].
const path = process.argv[2] || __dirname + "/../../main/resources/static/app.js";
let src = fs.readFileSync(path, "utf8");

// Expose the closure internals + skip the boot route() (no login/router in this stub).
src = src.replace(
  "  window.addEventListener(\"hashchange\", route);\n  route();\n})();",
  "  window.addEventListener(\"hashchange\", route);\n" +
  "  globalThis.__ca = { buildConsumers, applyDockerReading, consumerLegend, consumerCard," +
  " parseDockerStats, parseDockerPs, parseDockerVolumes, dockerBytes, parseDfTotal, pctText };\n})();"
);
if (src.indexOf("globalThis.__ca") < 0) { console.error("FAIL: could not inject test hook"); process.exit(1); }

// ---- minimal DOM stub ----
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

// ---- synthetic docker consumer + reading ----
const machine = { machineId:"m1", apps:[], consumers:[
  { id:"shop", name:"shop", role:"APP", source:"DOCKER", ram:null, cpu:null, disk:null,
    services:[ { name:"shop-web-1", image:"nginx", role:"APP" } ] }
]};
const models = ca.buildConsumers(machine);
const c = models[0];

// Before the poll every axis is — (this is the bug 037 fixes).
const before = ca.consumerLegend(models, ()=>{}).textContent;
if (before.indexOf("—") < 0) { console.error("FAIL: expected pre-poll — placeholder, got:", before); process.exit(1); }

const stats = ca.parseDockerStats(JSON.stringify(
  { Name:"shop-web-1", CPUPerc:"200.00%", MemPerc:"10.00%", MemUsage:"2GiB / 4GiB" }));
const ps = ca.parseDockerPs(JSON.stringify({ Names:"shop-web-1", Size:"1.5GB (virtual 200MB)" }));
const denom = { ramMb: 4000, cores: 4, diskBytes: 50 * 1024 * 1024 * 1024 };
ca.applyDockerReading(c, stats, ps, null, denom);

// RAM: 2GiB=2048MB /4000 = 51%; CPU: 200/4 = 50%; disk: 1.5GB /50GiB ≈ 3%.
function assert(cond, msg){ if (!cond){ console.error("FAIL:", msg); process.exit(1); } }
assert(c.ram === 51, "ram expected 51, got " + c.ram);
assert(c.cpu === 50, "cpu expected 50, got " + c.cpu);
assert(c.disk === 3, "disk expected 3, got " + c.disk);
assert(c.services[0].ram === 51 && c.services[0].cpu === 50, "service axes not filled: " + JSON.stringify(c.services[0]));

const legend = ca.consumerLegend(models, ()=>{}).textContent;
assert(/51%/.test(legend) && /50%/.test(legend), "legend missing numeric axes: " + legend);

const card = ca.consumerCard(c, ()=>{}, ()=>{}).textContent;
assert(/51%/.test(card), "card missing numeric RAM axis: " + card);
assert(/UP/.test(card), "card should show UP for a running docker consumer: " + card);

// dockerBytes unit dialects
assert(ca.dockerBytes("120MB") === 120000000, "SI MB: " + ca.dockerBytes("120MB"));
assert(ca.dockerBytes("2GiB") === 2147483648, "binary GiB: " + ca.dockerBytes("2GiB"));
assert(ca.dockerBytes("50G") === 50*1024*1024*1024, "df bare G: " + ca.dockerBytes("50G"));

// df total + volume parsing
const dfTotal = ca.parseDfTotal("Filesystem Size Used Avail Use% Mounted on\n/dev/sda1 50G 20G 28G 42% /\n");
assert(dfTotal === 50*1024*1024*1024, "df total: " + dfTotal);
const vols = ca.parseDockerVolumes("Local Volumes space usage:\n\nVOLUME NAME    LINKS   SIZE\nshop_pgdata    1       120MB\n");
assert(vols && vols[0].name === "shop_pgdata" && vols[0].bytes === 120000000, "volumes: " + JSON.stringify(vols));

console.log("PASS: docker consumer axes render numeric (ram=51% cpu=50% disk=3%), UP, service axes + unit/df/volume parsers verified");

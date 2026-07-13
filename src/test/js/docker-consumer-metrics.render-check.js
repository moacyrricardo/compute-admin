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
  " datastoresOf, serviceRow, parseDockerStats, parseDockerPs, parseDockerVolumes," +
  " dockerBytes, parseDfTotal, pctText };\n})();"
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

// ---- spec-038: a compose project is ONE card whose services include its datastore ----
// The dedicated split in the Databases lens derives from the project's role=DATABASE
// service (owner = the project), preserving the per-datastore axis; the project card's
// axes sum ALL its services (app + datastore).
const project = { machineId:"m2", apps:[], consumers:[
  { id:"lia-consulta", name:"lia-consulta", role:"APP", source:"DOCKER", ram:null, cpu:null, disk:null,
    services:[ { name:"lia-consulta-api-1", image:"lia/api", role:"APP" },
               { name:"lia-consulta-worker-1", image:"lia/worker", role:"APP" },
               { name:"lia-consulta-postgres-1", image:"postgres:16", role:"DATABASE" } ] }
]};
const pmodels = ca.buildConsumers(project);
assert(pmodels.length === 1, "a compose project must render as ONE card/consumer, got " + pmodels.length);
const pc = pmodels[0];
assert(pc.services.length === 3, "the project card keeps ALL services incl. its datastore, got " + pc.services.length);
// The datastore service renders inside the project drawer tagged "database".
const dsRow = ca.serviceRow(pc.services[2]).textContent;
assert(/database/.test(dsRow), "datastore service row should be tagged 'database': " + dsRow);

const pstats = ca.parseDockerStats([
  JSON.stringify({ Name:"lia-consulta-api-1", CPUPerc:"100.00%", MemPerc:"5%", MemUsage:"1GiB / 4GiB" }),
  JSON.stringify({ Name:"lia-consulta-worker-1", CPUPerc:"40.00%", MemPerc:"5%", MemUsage:"512MiB / 4GiB" }),
  JSON.stringify({ Name:"lia-consulta-postgres-1", CPUPerc:"20.00%", MemPerc:"5%", MemUsage:"512MiB / 4GiB" })
].join("\n"));
ca.applyDockerReading(pc, pstats, null, null, { ramMb: 4000, cores: 4, diskBytes: 50*1024*1024*1024 });
// Card RAM sums all three services: (1024+512+512)MB / 4000 = 51%.
assert(pc.ram === 51, "project card RAM must sum ALL its services, got " + pc.ram);

// Databases lens: the Dedicated band derives from the project's role=DATABASE service.
const dbs = ca.datastoresOf(pmodels);
assert(dbs.ded.length === 1, "dedicated band must derive one datastore from the project's DB service, got " + dbs.ded.length);
assert(dbs.shared.length === 0, "an app project must NOT surface as a shared datastore consumer: " + JSON.stringify(dbs.shared.map(c=>c.name)));
assert(dbs.ded[0].owner === "lia-consulta" && dbs.ded[0].name === "lia-consulta-postgres-1",
  "dedicated datastore owner/name: " + JSON.stringify(dbs.ded[0]));
// It carries its OWN per-service axis (512MiB / 4000 ≈ 13%), not the project's sum.
assert(dbs.ded[0].ram === 13, "dedicated datastore per-service RAM axis, got " + dbs.ded[0].ram);

// A standalone/shared datastore consumer still goes to the Shared band (unchanged).
const shModels = ca.buildConsumers({ machineId:"m3", apps:[], consumers:[
  { id:"cache", name:"cache", role:"DATABASE", source:"DOCKER", dedication:"SHARED", ram:null, cpu:null, disk:null,
    services:[ { name:"cache", image:"redis:7", role:"DATABASE" } ] }
]});
const shDbs = ca.datastoresOf(shModels);
assert(shDbs.shared.length === 1 && shDbs.ded.length === 0,
  "a standalone DATABASE consumer must stay in the Shared band: " + JSON.stringify({ded:shDbs.ded.length,shared:shDbs.shared.length}));

console.log("PASS: docker consumer axes render numeric (ram=51% cpu=50% disk=3%), UP, service axes + unit/df/volume parsers verified");
console.log("PASS: spec-038 — compose project renders as ONE card (services incl. datastore, axes sum all); Databases lens Dedicated band derives from the project's DB service; standalone datastore stays Shared");

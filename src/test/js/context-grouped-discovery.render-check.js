"use strict";
// spec-066 headless render check (run: `node src/test/js/context-grouped-discovery.render-check.js`;
// not wired into mvn, matching the existing render-check idiom). Loads the REAL app.js in a
// minimal DOM stub and asserts the two surfaces spec-066 builds:
//   Surface 1 — the machine-detail Discovery panel re-groups the recipe channel (data.groups,
//     now carrying a populated appPortList per BLOCKER 1) into one context card per contextDisplay:
//     multi-recipe contexts collapse to one card, context-less/empty recipes fall into the
//     "other / none" ungrouped remainder, a port==0 item renders "no listening port", a
//     split-management-port item renders ":T · mgmt :M", and each proposed action row carries the
//     existing spec-044 approvalSplit control (no new endpoint).
//   Surface 2 — a per-context native consumer (id = name = contextDisplay, carrying its member
//     (appName,port) pairs) polls its member apps: applyConsumerReading null-aware-sums PSS across
//     the members into the context's RAM axis (a number, not —), and disk stays — with no member du.
const fs = require("fs");
const vm = require("vm");
const path = process.argv[2] || __dirname + "/../../main/resources/static/app.js";
let src = fs.readFileSync(path, "utf8");

src = src.replace(
  "  window.addEventListener(\"hashchange\", route);\n  route();\n})();",
  "  window.addEventListener(\"hashchange\", route);\n" +
  "  globalThis.__ca = { groupByContext, contextCard, contextPortLine, basename," +
  " contextDiscovery, buildConsumers, applyConsumerReading, consumerLegend };\n})();"
);
if (src.indexOf("globalThis.__ca") < 0) { console.error("FAIL: could not inject test hook"); process.exit(1); }

// ---- minimal DOM stub (shared shape with the other render-checks) ----
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
  setTimeout: ()=>0, clearTimeout: ()=>{}, setInterval: ()=>0, clearInterval: ()=>{},
  fetch: ()=>Promise.resolve({}),
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
function txt(node) { return node ? node.textContent : ""; }

const machine = { id: "m1", name: "host", loginUser: "admin", host: "h", port: 22 };

// ---- fixtures: two recipes sharing one context, one context-less, one empty ----
function item(appName, port, over) {
  return Object.assign({ appName: appName, port: port, runtime: "systemd", contextDisplay: "/opt/orders",
    contextScripts: ["/opt/orders/scripts/start.sh", "/opt/orders/scripts/stop.sh"],
    sourceNote: "app folder · discovered via port :8080 + systemd unit", confidence: "high",
    scriptFolder: "/opt/orders/scripts", managementPort: null }, over || {});
}
const ordersProbe = { recipe: { id: "r1", name: "orders monitor", type: "MONITOR",
  appPortList: [ item("orders", 8080), item("orders-worker", 0, { managementPort: null }) ] },
  actions: [ { id: "a1", name: "process probe", approvalState: "DRAFT", sudo: false } ] };
const ordersOps = { recipe: { id: "r2", name: "orders ops", type: "OPS",
  appPortList: [ item("orders", 8080, { managementPort: 9090 }) ] },
  actions: [ { id: "a2", name: "restart", approvalState: "PENDING_APPROVAL", sudo: false, approvedAt: null } ] };
const contextless = { recipe: { id: "r3", name: "legacy monitor", type: "MONITOR",
  appPortList: [ { appName: "legacy", port: 9000, runtime: "process" } ] }, actions: [] };
const blueprint = { recipe: { id: "r4", name: "nginx blueprint", type: "CUSTOM", appPortList: [] }, actions: [] };

// ======================================================================= 1 ==
// groupByContext: two recipes on the same contextDisplay collapse into ONE context
// carrying both recipe groups; context-less and empty recipes fall into ungrouped.
const grouped = ca.groupByContext([ordersProbe, ordersOps, contextless, blueprint]);
assert(grouped.contexts.length === 1, "two recipes sharing /opt/orders collapse into ONE context, got " + grouped.contexts.length);
const octx = grouped.contexts[0];
assert(octx.display === "/opt/orders", "the context is keyed by contextDisplay, got " + octx.display);
assert(octx.groups.length === 2, "both recipes (probe + ops) attach to the context, got " + octx.groups.length);
assert(octx.confidence === "high", "the context collapses the member confidence label, got " + octx.confidence);
assert(octx.scripts.indexOf("start.sh") >= 0 && octx.scripts.indexOf("stop.sh") >= 0,
  "sibling scripts collapse to basenames, got " + octx.scripts.join(","));
assert(grouped.ungrouped.length === 2, "context-less + empty-appPortList recipes fall into ungrouped, got " + grouped.ungrouped.length);

// ======================================================================= 2 ==
// contextPortLine: declared-only (port 0) → "no listening port"; split mgmt port → ":T · mgmt :M";
// single port → ":T".
assert(txt(ca.contextPortLine({ appName: "w", port: 0 })).indexOf("no listening port") >= 0,
  "a port==0 declared-only item renders 'no listening port'");
assert(txt(ca.contextPortLine({ appName: "o", port: 8080, managementPort: 9090 })) === ":8080 · mgmt :9090",
  "a split-management-port item renders ':T · mgmt :M', got " + txt(ca.contextPortLine({ appName: "o", port: 8080, managementPort: 9090 })));
assert(txt(ca.contextPortLine({ appName: "o", port: 8080, managementPort: null })) === ":8080",
  "a single-port item renders ':T'");

// ======================================================================= 3 ==
// contextCard: renders the path header + source note, lists member items (incl. the declared-only
// worker), and each proposed action row carries the spec-044 approvalSplit control (DRAFT → Submit).
const card = ca.contextCard(machine, octx);
const cardText = txt(card);
assert(cardText.indexOf("/opt/orders") >= 0, "the card shows the contextDisplay path");
assert(cardText.indexOf("app folder") >= 0, "the card shows the sourceNote sub-line");
assert(cardText.indexOf("high confidence") >= 0, "confidence renders as a neutral text label, not colour alone");
assert(cardText.indexOf("no listening port") >= 0, "the declared-only member renders its 'no listening port' label");
assert(cardText.indexOf("Submit") >= 0, "a DRAFT action row carries the spec-044 approvalSplit primary (Submit)");
assert(cardText.indexOf("Review & approve") >= 0, "a first-time PENDING action row routes through Review & approve (approvalSplit)");

// ==================================================================== 3b ==
// contextDiscovery scopes the discovery panel to pre-filled proposals ONLY: an
// empty-appPortList blueprint/custom recipe belongs to the recipe registry, not the discovery
// panel, so it must NOT leak into the "other / none" remainder here (avoids duplicating the whole
// registry). A context-less pre-filled recipe DOES render under "other / none".
const panel = ca.contextDiscovery(machine, [ordersProbe, contextless, blueprint]);
const panelText = txt(panel);
assert(panelText.indexOf("/opt/orders") >= 0, "the discovery panel shows the resolved context card");
assert(panelText.indexOf("legacy monitor") >= 0, "a context-less pre-filled recipe lists under other/none");
assert(panelText.indexOf("nginx blueprint") < 0,
  "an empty-appPortList blueprint recipe must NOT appear in the discovery panel (registry-only)");
assert(panelText.indexOf("Other / none") >= 0, "the ungrouped remainder carries the 'other / none' heading");

// ======================================================================= 4 ==
// Surface 2: a per-context native consumer polls its member apps. buildConsumers binds the members
// to their per-app checks; applyConsumerReading null-aware-sums PSS across BOTH members into RAM
// (500 + 500 = 1000 MB / 2000 = 50% — a single member would read 25%). Disk stays — with no du.
const ramCheck = { id: "ram1", name: "ram", approvalState: "APPROVED", changedSinceApproval: false };
const diskCheck = { id: "disk1", name: "disk", approvalState: "APPROVED", changedSinceApproval: false };
const mon = { machineId: "m1",
  apps: [
    { appName: "orders", framework: "spring", port: 8080, checks: [ramCheck, diskCheck] },
    { appName: "orders-worker", framework: "spring", port: 7000, checks: [ramCheck, diskCheck] }
  ],
  consumers: [ { id: "/opt/orders", name: "/opt/orders", role: "APP", source: "NATIVE",
    ram: null, cpu: null, disk: null, services: [],
    members: [ { appName: "orders", port: 8080 }, { appName: "orders-worker", port: 7000 } ] } ]
};
const consumer = ca.buildConsumers(mon)[0];
assert(consumer.members.length === 2, "buildConsumers exposes the context's member apps, got " + consumer.members.length);
assert((consumer.checks || []).length === 2, "the context binds its members' shared recipe checks, got " + (consumer.checks || []).length);
const pss = "## pid 1\nPss: 512000 kB\n";               // 500 MB per member
const noDu = "no dir /opt/orders";                      // parseDuBytes → null (no attributable disk)
ca.applyConsumerReading(consumer,
  { ram1: { orders: { stdout: pss, exit: 0 }, "orders-worker": { stdout: pss, exit: 0 } },
    disk1: { orders: { stdout: noDu, exit: 0 }, "orders-worker": { stdout: noDu, exit: 0 } } },
  2000, 4, 100 * 1024 * 1024 * 1024);
assert(consumer.ram === 50, "RAM sums PSS across BOTH members (1000 MB / 2000 = 50%), got " + consumer.ram);
assert(consumer.disk == null, "an all-native context with no member du leaves the disk axis — (F3), got " + consumer.disk);

// ======================================================================= 5 ==
// consumerLegend renders the per-context consumer under its contextDisplay name.
const legend = ca.consumerLegend([consumer], function () {});
assert(txt(legend).indexOf("/opt/orders") >= 0, "the legend chip is named by contextDisplay, got " + txt(legend));

// ======================================================================= 6 ==
// spec-075 A1: nginx :80 and :443, port-fingerprinted at low confidence, share one contextDisplay
// (/var/www), so the two ports collapse into ONE context card — not two anonymous app-<port> cards.
function nginxPort(port) {
  return { appName: "nginx", port: port, runtime: "process", contextDisplay: "/var/www",
    contextScripts: [],
    sourceNote: "unattributed listener · discovered via port :" + port + " · fingerprinted nginx by port",
    confidence: "low", scriptFolder: null, managementPort: null };
}
const nginxRecipe = { recipe: { id: "n1", name: "nginx", type: "NGINX",
  appPortList: [ nginxPort(80), nginxPort(443) ] }, actions: [] };
const nginxGrouped = ca.groupByContext([nginxRecipe]);
assert(nginxGrouped.contexts.length === 1,
  "spec-075: nginx :80 + :443 collapse into ONE context card, got " + nginxGrouped.contexts.length);
assert(nginxGrouped.contexts[0].display === "/var/www",
  "spec-075: the nginx context is keyed by its catalog data dir /var/www, got " + nginxGrouped.contexts[0].display);
assert(nginxGrouped.contexts[0].items.length === 2,
  "spec-075: both nginx ports are members of the one context, got " + nginxGrouped.contexts[0].items.length);
assert(nginxGrouped.contexts[0].confidence === "low",
  "spec-075: a port-fingerprinted context renders low confidence (a port guess), got " + nginxGrouped.contexts[0].confidence);
const nginxCardText = txt(ca.contextCard(machine, nginxGrouped.contexts[0]));
assert(nginxCardText.indexOf("/var/www") >= 0, "spec-075: the nginx card shows the /var/www context path");
assert(nginxCardText.indexOf(":80") >= 0 && nginxCardText.indexOf(":443") >= 0,
  "spec-075: the single nginx card lists both port lines :80 and :443");

if (failed) { console.error("FAILED: " + failed + " assertion(s)"); process.exit(1); }
console.log("PASS: spec-066 — discovery re-groups the recipe channel into one context card per "
  + "contextDisplay (multi-recipe collapse, context-less/empty → other/none, declared-only + "
  + "':T · mgmt :M' port lines, spec-044 approvalSplit rows); a per-context native consumer polls "
  + "its member apps (RAM sums PSS across members, disk stays — with no du) and legends by contextDisplay");

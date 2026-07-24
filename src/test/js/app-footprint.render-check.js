"use strict";
// spec-049 headless render check: a parsed app-folder/footprint NDJSON line drives the
// native consumer's folder + sizes onto the card AND the drawer, feeds the native disk-%
// axis for a DEPLOYED root (so computeOther subtracts it — no double count), and degrades
// honestly for build-tree / cwd-only / unresolved / malformed lines. Loads the REAL app.js
// in a minimal DOM stub, exposes its internals, and asserts on rendered textContent.
const fs = require("fs");
const vm = require("vm");
const path = process.argv[2] || __dirname + "/../../main/resources/static/app.js";
let src = fs.readFileSync(path, "utf8");

src = src.replace(
  "  window.addEventListener(\"hashchange\", route);\n  route();\n})();",
  "  window.addEventListener(\"hashchange\", route);\n" +
  "  globalThis.__ca = { buildConsumers, applyConsumerReading, consumerCard, openConsumerDrawer," +
  " computeOther, checkKind, parseFootprint, bytesText, midTruncate };\n})();"
);
if (src.indexOf("globalThis.__ca") < 0) { console.error("FAIL: could not inject test hook"); process.exit(1); }

// ---- minimal DOM stub (getElementById caches by id so the drawer is retrievable) ----
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
const _byId = {};
const document = {
  createElement: t => makeNode(t),
  createTextNode: t => { const n = makeNode("#text"); n._text = String(t); return n; },
  getElementById: id => _byId[id] || (_byId[id] = makeNode("div"))
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

const machine = { machineId:"m1", loginUser:"deploy", host:"host", port:22 };
const HOST_DISK = 100 * 1e9; // 100 GB data-root

function nativeApp(fpStdout) {
  const m = { machineId:"m1", loginUser:"deploy", host:"host", port:22, apps:[
    { appName:"orders", framework:"spring", port:8080, checks:[
      { id:"fp1", name:"footprint", approvalState:"APPROVED", changedSinceApproval:false }
    ] }
  ], consumers:[
    { id:"orders", name:"orders", role:"APP", source:"HOST", ram:null, cpu:null, disk:null, services:[] }
  ]};
  const nc = ca.buildConsumers(m)[0];
  const outputs = fpStdout == null ? null : { fp1: { orders: { stdout: fpStdout, exit: 0 } } };
  ca.applyConsumerReading(nc, outputs, 4000, 4, HOST_DISK);
  return nc;
}

// ---- 0. checkKind + parseFootprint --------------------------------------------------
assert(ca.checkKind({ name:"footprint" }) === "footprint", "checkKind must classify footprint");
assert(ca.parseFootprint("") === null && ca.parseFootprint(null) === null, "empty/absent line → null");
assert(ca.parseFootprint("{ not json") === null, "malformed line → null (never throws)");

// ---- 1. DEPLOYED java: folder + 3 sizes on card & drawer, disk-% axis FED -----------
const deployLine = JSON.stringify({
  v:1, port:8080, pid:4242, user:"deploy", runtime:"java",
  cmdline:"java -jar /opt/orders/current/orders.jar",
  cwd:"/opt/orders/releases/123", exe:"/usr/bin/java",
  artifact:"/opt/orders/releases/123/orders.jar", artifactBytes:48234511,
  appRoot:"/opt/orders/releases/123", link:"/opt/orders/current",
  rootKind:"deploy", buildTool:null, markers:[],
  dataDir:"/opt/orders/releases/123/data", dataBytes:104857600,
  footprintBytes:5000000000, footprintSkipped:null, notes:[]
});
const dc = nativeApp(deployLine);
// Symlink IS the identity; the resolved release is secondary (spec-049 decision 4).
assert(dc.folder === "/opt/orders/current", "deploy folder = symlink identity, got " + dc.folder);
assert(dc.folderResolved === "/opt/orders/releases/123", "resolved target kept, got " + dc.folderResolved);
assert(dc.rootKind === "deploy", "rootKind deploy, got " + dc.rootKind);
// Disk axis fed: 5e9 / 100e9 = 5%.
assert(dc.disk === 5, "deploy footprint must FEED the native disk-% axis (5%), got " + dc.disk);

const dCard = ca.consumerCard(dc, ()=>{}, ()=>{}).textContent;
assert(dCard.indexOf("/opt/orders/current") >= 0, "card shows the symlink folder identity: " + dCard);
assert(/footprint 5\.0 GB/.test(dCard), "card size chip shows the footprint size: " + dCard);

ca.openConsumerDrawer(machine, dc);
const dDrawer = _byId["modal-root"].textContent;
assert(dDrawer.indexOf("Folder") >= 0, "drawer has a Folder block: " + dDrawer);
assert(dDrawer.indexOf("/opt/orders/current") >= 0 && dDrawer.indexOf("/opt/orders/releases/123") >= 0,
  "drawer shows symlink identity + resolved target: " + dDrawer);
assert(/48\.2 MB/.test(dDrawer) && /104\.9 MB/.test(dDrawer) && /5\.0 GB \(approximate\)/.test(dDrawer),
  "drawer shows the three distinct sizes (artifact/data/footprint approx): " + dDrawer);

// ---- 2. computeOther (spec-041 joint edit): deployed disk is NOT double-counted -----
// hostUsed.disk 70; the deploy app (disk=5) is attributed → OTHER disk = 70 − 5 = 65.
const other = ca.computeOther("m1", { ram:null, cpu:null, disk:70 }, [dc]);
assert(other && other.disk === 65, "OTHER disk must subtract the app's newly-attributed footprint (70−5=65), got " + (other && other.disk));

// ---- 3. BUILD tree: folder + artifact only, footprint suppressed, disk stays — ------
const buildLine = JSON.stringify({
  v:1, port:8080, pid:4242, user:"dev", runtime:"java",
  cmdline:"java -jar /home/dev/orders/target/orders.jar",
  cwd:"/home/dev/orders", exe:"/usr/bin/java",
  artifact:"/home/dev/orders/target/orders.jar", artifactBytes:48234511,
  appRoot:"/home/dev/orders", link:null,
  rootKind:"build", buildTool:"maven", markers:["pom.xml"],
  dataDir:null, dataBytes:null, footprintBytes:null, footprintSkipped:"build-tree", notes:[]
});
const bc = nativeApp(buildLine);
assert(bc.folder === "/home/dev/orders", "build folder = appRoot, got " + bc.folder);
assert(bc.disk == null, "a build tree must NOT feed the disk axis (stays —), got " + bc.disk);
const bCard = ca.consumerCard(bc, ()=>{}, ()=>{}).textContent;
assert(/artifact 48\.2 MB/.test(bCard), "build card falls back to the artifact size chip: " + bCard);
_byId["modal-root"] = null; delete _byId["modal-root"];
ca.openConsumerDrawer(machine, bc);
const bDrawer = _byId["modal-root"].textContent;
assert(bDrawer.indexOf("build tree — footprint suppressed") >= 0,
  "build-tree drawer says the footprint is suppressed: " + bDrawer);

// ---- 4. cwd-only python straggler: folder shown, tagged, no sizes, no disk ----------
const cwdLine = JSON.stringify({
  v:1, port:8000, pid:9, user:"deploy", runtime:"python",
  cmdline:"python -c import app", cwd:"/srv/scratch", exe:"/usr/bin/python3.12",
  artifact:null, artifactBytes:null, appRoot:"/srv/scratch", link:null,
  rootKind:"cwd-only", buildTool:null, markers:[],
  dataDir:null, dataBytes:null, footprintBytes:null, footprintSkipped:null, notes:["no-jar-cp-or-exploded"]
});
const wc = nativeApp(cwdLine);
assert(wc.folder === "/srv/scratch" && wc.disk == null, "cwd-only shows the cwd, no disk feed: " + JSON.stringify({f:wc.folder,d:wc.disk}));
const wCard = ca.consumerCard(wc, ()=>{}, ()=>{}).textContent;
assert(wCard.indexOf("cwd-only") >= 0, "cwd-only card tags the folder honestly: " + wCard);

// ---- 5. unresolved (no folder at all): rolls up down, card unchanged -----------------
const unresolvedLine = JSON.stringify({ v:1, port:8000, rootKind:"unresolved", footprintSkipped:"permission", notes:["no-listener-or-not-owner"] });
const uc = nativeApp(unresolvedLine);
assert(!uc.folder, "unresolved with no appRoot/cwd → no folder line: " + uc.folder);
const uCard = ca.consumerCard(uc, ()=>{}, ()=>{}).textContent;
assert(uCard.indexOf("orders") >= 0, "unresolved card still renders the app: " + uCard);

// ---- 6. malformed line: no throw, folder stays absent, check still rolls up ----------
const mc = nativeApp("garbage not json");
assert(!mc.folder && mc.disk == null, "malformed footprint line leaves folder/disk absent (honest —)");

// ---- 7. SAMPLED: when footprint is NOT re-polled, folder/sizes/disk RETAIN ----------
// (the slow-tier decision — fast cycles carry no footprint output for the check).
ca.applyConsumerReading(dc, { }, 4000, 4, HOST_DISK); // no fp1 output this cycle
assert(dc.folder === "/opt/orders/current" && dc.disk === 5,
  "a cycle without a footprint reading must RETAIN the last sampled folder/disk, got " + JSON.stringify({f:dc.folder,d:dc.disk}));

console.log("PASS: spec-049 — deployed app shows folder (symlink identity) + artifact/data/footprint sizes on card & drawer, feeds the native disk-% axis (5%), and computeOther subtracts it (no double count)");
console.log("PASS: spec-049 — build-tree suppresses the footprint (artifact-only, disk —); cwd-only/unresolved tagged honestly; malformed line degrades to — without throwing; sampled probe retains its last value between fast cycles");

"use strict";
// spec-067 headless render check (run: `node src/test/js/machine-dashboard-composition.render-check.js`;
// not wired into mvn, matching the existing render-check idiom). Loads the REAL app.js in a
// minimal DOM stub and asserts three hardened spec-067 decisions:
//   1. The shared makeFootprint factory (extracted from the fleet Monitor) renders the
//      identity head ONLY when showHead is true — so the fleet keeps its per-machine head
//      while the single-machine dashboard (whose pageHead already carries it) does NOT get a
//      duplicate identity head, yet still composes the footprint body.
//   2. consumerCard renders the consumer name as PLAIN text when no fleet toggle is passed
//      (Decision 1: "toggleApp is dead here"), and as a filter button when one is.
//   3. The recipe source filter (Decision 4): source derives from RecipeView.appPortList
//      runtimes (docker ⇒ docker, else native); an EMPTY appPortList is "other / none" and is
//      NEVER hidden by a native/docker chip; a mixed-runtime recipe matches every source.
const fs = require("fs");
const vm = require("vm");
const path = process.argv[2] || __dirname + "/../../main/resources/static/app.js";
let src = fs.readFileSync(path, "utf8");

src = src.replace(
  "  window.addEventListener(\"hashchange\", route);\n  route();\n})();",
  "  window.addEventListener(\"hashchange\", route);\n" +
  "  globalThis.__ca = { makeFootprint, consumerCard," +
  " recipeSourceSet, recipeIsOtherNone, recipeMatchesSources };\n})();"
);
if (src.indexOf("globalThis.__ca") < 0) { console.error("FAIL: could not inject test hook"); process.exit(1); }

// ---- minimal DOM stub (shared shape with host-system-segment.render-check.js) ----
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
function assert(cond, msg){ if (!cond){ console.error("FAIL:", msg); process.exit(1); } }
function hasClass(node, cls){ return String(node.className).split(/\s+/).indexOf(cls) >= 0; }
function findByClass(node, cls){
  if (node && node.className != null && hasClass(node, cls)) return node;
  for (const c of (node.children || [])) { const f = findByClass(c, cls); if (f) return f; }
  return null;
}

// ======================================================================= 1 ==
// makeFootprint: showHead true ⇒ identity head present; false ⇒ body-only (no dup head).
const machine = { machineId: "m1", host: "h1.example", loginUser: "admin", port: 22,
  status: "ONLINE", consumers: [], apps: [], hostActions: [] };
function fp(showHead) {
  return ca.makeFootprint({
    models: { m1: [] },
    selectedNamed: () => [],
    noAppsOn: () => false,
    onToggleApp: showHead ? () => {} : null,
    showHead: showHead
  }).buildSection(machine);
}
const headed = fp(true).node.textContent;
assert(/admin@h1\.example:22/.test(headed),
  "fleet section (showHead:true) must carry the identity head loginUser@host:port, got: " + headed);

const bodyOnly = fp(false).node.textContent;
assert(!/admin@h1\.example:22/.test(bodyOnly),
  "machine-dashboard section (showHead:false) must NOT duplicate the identity head (pageHead owns it), got: " + bodyOnly);
assert(/No discovered consumers on this host\./.test(bodyOnly),
  "body-only section must still compose the footprint body (bare host ⇒ the honest empty note), got: " + bodyOnly);

// ======================================================================= 2 ==
// consumerCard: the name is a fleet-filter BUTTON with a toggle, PLAIN text without one.
const consumer = { id: "c1", name: "web", framework: "generic", role: "APP", source: "NATIVE",
  runtime: "process", port: 8080, ram: null, cpu: null, disk: null, _up: null,
  _checkStates: [], ops: [] };

const withToggle = ca.consumerCard(consumer, () => {}, () => {});
assert(findByClass(withToggle, "app-name-toggle"),
  "fleet consumerCard (onToggle given) must render the app-name-toggle filter button");
assert(!findByClass(withToggle, "app-name-static"),
  "fleet consumerCard must NOT render the static name variant");

const noToggle = ca.consumerCard(consumer, null, () => {});
assert(!findByClass(noToggle, "app-name-toggle"),
  "single-machine consumerCard (onToggle null) must NOT render the fleet filter button (toggleApp is dead here)");
const staticName = findByClass(noToggle, "app-name-static");
assert(staticName && staticName.textContent === "web",
  "single-machine consumerCard must render the consumer name as plain text 'web', got: " + (staticName && staticName.textContent));

// ======================================================================= 3 ==
// Source classification + the source filter's "other / none is never hidden" rule.
const emptyR   = { appPortList: [] };                                    // blueprint/custom majority
const dockerR  = { appPortList: [{ runtime: "docker" }] };
const nativeR  = { appPortList: [{ runtime: "systemd" }] };              // non-docker ⇒ native
const mixedR   = { appPortList: [{ runtime: "docker" }, { runtime: "process" }] };

assert(Object.keys(ca.recipeSourceSet(emptyR)).length === 0, "empty appPortList ⇒ no source");
assert(ca.recipeIsOtherNone(emptyR) === true, "empty appPortList ⇒ other/none");
assert(ca.recipeSourceSet(dockerR).docker === true && !ca.recipeSourceSet(dockerR).native,
  "docker runtime ⇒ {docker}");
assert(ca.recipeSourceSet(nativeR).native === true && !ca.recipeSourceSet(nativeR).docker,
  "non-docker runtime ⇒ {native}");
assert(ca.recipeSourceSet(mixedR).docker === true && ca.recipeSourceSet(mixedR).native === true,
  "mixed runtimes ⇒ {docker, native}");
assert(ca.recipeIsOtherNone(mixedR) === false, "a sourced recipe is not other/none");

// No chip selected ⇒ everything passes.
assert(ca.recipeMatchesSources(dockerR, []) === true, "no source chip ⇒ all recipes pass");
// The key rule: an empty-appPortList recipe is NEVER hidden by a source chip.
assert(ca.recipeMatchesSources(emptyR, ["native"]) === true,
  "other/none must NOT be hidden by the native chip (un-pre-filled majority stays visible)");
assert(ca.recipeMatchesSources(emptyR, ["docker"]) === true,
  "other/none must NOT be hidden by the docker chip");
// A sourced recipe IS filtered by the chip.
assert(ca.recipeMatchesSources(dockerR, ["native"]) === false,
  "a docker-only recipe must be hidden when only the native chip is on");
assert(ca.recipeMatchesSources(dockerR, ["docker"]) === true, "docker recipe passes the docker chip");
assert(ca.recipeMatchesSources(nativeR, ["native"]) === true, "native recipe passes the native chip");
// A mixed recipe matches every source it carries.
assert(ca.recipeMatchesSources(mixedR, ["native"]) === true, "mixed recipe matches the native chip");
assert(ca.recipeMatchesSources(mixedR, ["docker"]) === true, "mixed recipe matches the docker chip");

console.log("PASS: spec-067 — makeFootprint renders the identity head only when showHead (fleet keeps it, "
  + "the single-machine dashboard omits the duplicate yet composes the body); consumerCard renders the name "
  + "as plain text without a fleet toggle; and the recipe source filter classifies docker/native/mixed from "
  + "appPortList while never hiding the empty-appPortList 'other / none' majority behind a source chip");

"use strict";
// spec-050 headless render check: approved app-ops named start/stop/restart/deploy render
// as a lifecycle control row on the app card. Asserts the run semantics: Start runs INLINE
// (opens a run-output drawer that POSTs /runs, no confirm); Stop/Restart/Deploy route
// through the spec-044 review/confirm drawer (renderActionReview body + an explicit confirm
// that does NOT POST until pressed); Deploy adds a type-the-app-name gate (confirm disabled
// until the name is typed); Restart with only start+stop COMPOSES (a warning drawer, no
// auto-run); and the systemd dedup prefers a SYSTEMD control over a script one per verb.
// Loads the REAL app.js in a minimal DOM stub, exposes its internals, drives clicks.
const fs = require("fs");
const vm = require("vm");
const path = process.argv[2] || __dirname + "/../../main/resources/static/app.js";
let src = fs.readFileSync(path, "utf8");

src = src.replace(
  "  window.addEventListener(\"hashchange\", route);\n  route();\n})();",
  "  window.addEventListener(\"hashchange\", route);\n" +
  "  globalThis.__ca = { lifecycleControls, isLifecycleOp, lifecycleOpForVerb, consumerCard };\n})();"
);
if (src.indexOf("globalThis.__ca") < 0) { console.error("FAIL: could not inject test hook"); process.exit(1); }

// ---- minimal DOM stub with real event dispatch + a stable modal-root by id ----
function makeNode(tag) {
  return {
    tagName: tag, children: [], _text: null, style: {}, _class: "", attrs: {}, value: "",
    disabled: false, _ev: {},
    classList: { _s: {}, add(c){this._s[c]=1;}, remove(c){delete this._s[c];}, contains(c){return !!this._s[c];}, toggle(c){this._s[c]?delete this._s[c]:this._s[c]=1;} },
    get className(){ return this._class; },
    set className(v){ this._class = v; this.classList._s = {}; String(v).split(/\s+/).forEach(t => { if (t) this.classList._s[t] = 1; }); },
    set textContent(v){ this._text = String(v); this.children = []; },
    get textContent(){ if (this._text != null) return this._text; return this.children.map(c => c.textContent).join(""); },
    get firstChild(){ return this.children[0] || null; },
    get parentNode(){ return this._parent || null; },
    appendChild(k){ k._parent = this; this.children.push(k); return k; },
    removeChild(k){ const i = this.children.indexOf(k); if (i>=0) this.children.splice(i,1); return k; },
    setAttribute(k,v){ this.attrs[k]=v; },
    getAttribute(k){ return this.attrs[k]; },
    addEventListener(ev, fn){ (this._ev[ev] = this._ev[ev] || []).push(fn); },
    removeEventListener(ev, fn){ const a=this._ev[ev]; if (a){ const i=a.indexOf(fn); if(i>=0)a.splice(i,1);} },
    focus(){},
    contains(n){ if (n===this) return true; return this.children.some(c => c.contains && c.contains(n)); },
    querySelector(sel){ for (const c of this.children) { if (c.tagName === sel) return c; const f = c.querySelector && c.querySelector(sel); if (f) return f; } return null; },
    closest(){ return null; }
  };
}
function fire(node, ev) {
  const e = { target: node, stopPropagation(){}, preventDefault(){} };
  (node._ev[ev] || []).forEach(fn => fn(e));
}
function click(node) { fire(node, "click"); }
function findByText(node, text) {
  if (node._text === text) return node;
  for (const c of (node.children || [])) { const r = findByText(c, text); if (r) return r; }
  return null;
}
function findByTextStartsWith(node, prefix) {
  if (node._text != null && node._text.indexOf(prefix) === 0) return node;
  for (const c of (node.children || [])) { const r = findByTextStartsWith(c, prefix); if (r) return r; }
  return null;
}
function hasClass(node, cls) { return (" " + (node.className || "") + " ").indexOf(" " + cls + " ") >= 0; }
function findByClass(node, cls) {
  if (node.className && hasClass(node, cls)) return node;
  for (const c of node.children) { const r = c.children ? findByClass(c, cls) : null; if (r) return r; }
  return null;
}
function findByTag(node, tag) {
  if (node.tagName === tag) return node;
  for (const c of (node.children || [])) { const r = findByTag(c, tag); if (r) return r; }
  return null;
}
function textIncludes(node, needle) { return (node.textContent || "").indexOf(needle) >= 0; }
function findButton(node, label) {
  if (node.tagName === "button" && node.textContent === label) return node;
  for (const c of (node.children || [])) { const r = findButton(c, label); if (r) return r; }
  return null;
}
function findButtonStartsWith(node, prefix) {
  if (node.tagName === "button" && (node.textContent || "").indexOf(prefix) === 0) return node;
  for (const c of (node.children || [])) { const r = findButtonStartsWith(c, prefix); if (r) return r; }
  return null;
}

const modalRoot = makeNode("div");
const toastRoot = makeNode("div");
const document = {
  createElement: t => makeNode(t),
  createTextNode: t => { const n = makeNode("#text"); n._text = String(t); return n; },
  getElementById: id => id === "modal-root" ? modalRoot : (id === "toast-root" ? toastRoot : makeNode("div")),
  addEventListener(){}, removeEventListener(){}, activeElement: null
};
const fetchCalls = [];
const fakeRes = { ok:true, status:200, headers:{ get:()=>"application/json" }, json:()=>Promise.resolve({ id:"run1" }), text:()=>Promise.resolve("{}") };
const window = { addEventListener(){}, location:{ hash:"" }, matchMedia:()=>({ matches:false }) };
const ctx = {
  document, window, location: window.location, console,
  localStorage: { getItem:()=>null, setItem(){}, removeItem(){} },
  setTimeout: ()=>0, clearTimeout: ()=>{},
  fetch: (url, opts)=>{ fetchCalls.push({ url, method: opts && opts.method }); return Promise.resolve(fakeRes); },
  TextDecoder: function(){ this.decode=()=>""; }, AbortController: function(){ this.abort=()=>{}; this.signal={}; },
  navigator: {}, globalThis: null
};
ctx.globalThis = ctx;
vm.createContext(ctx);
vm.runInContext(src, ctx);

const ca = ctx.__ca;
if (!ca) { console.error("FAIL: __ca not exposed"); process.exit(1); }
function assert(cond, msg){ if (!cond){ console.error("FAIL:", msg); process.exit(1); } }

const machine = { id: "m1", name: "web-prod-1", host: "10.0.0.5", port: 22, loginUser: "admin" };
function mkOp(over){
  return Object.assign({
    id: "op", machineId: "m1", recipeId: "r1", recipeName: "lifecycle orders", recipeType: "CUSTOM",
    name: "start", description: "Runs /opt/orders/run.sh verbatim (content-pinned).",
    sudo: false, approvalState: "APPROVED", changedSinceApproval: false,
    appParamName: "app-name", targetApps: ["orders"],
    argTokens: [{ position:0, kind:"LITERAL", value:"/opt/orders/run.sh" }],
    paramDefs: [{ name:"app-name", kind:"ALLOWED_SET", allowedValues:["orders"] }]
  }, over);
}
const cctx = { machine: machine, onRan: function(){} };

// ---- 1. lifecycle-named ops render as a control row; others do not ----
assert(ca.isLifecycleOp(mkOp({ name:"start" })), "start is a lifecycle verb");
assert(ca.isLifecycleOp(mkOp({ name:"deploy" })), "deploy is a lifecycle verb");
assert(!ca.isLifecycleOp(mkOp({ name:"status" })), "status is NOT a lifecycle verb (stays a run chip)");

const full = { name:"orders", ops:[
  mkOp({ id:"start", name:"start", argTokens:[{position:0,kind:"LITERAL",value:"/opt/orders/start.sh"}] }),
  mkOp({ id:"stop", name:"stop", argTokens:[{position:0,kind:"LITERAL",value:"/opt/orders/stop.sh"}] }),
  mkOp({ id:"restart", name:"restart", argTokens:[{position:0,kind:"LITERAL",value:"/opt/orders/restart.sh"}] }),
  mkOp({ id:"deploy", name:"deploy", argTokens:[{position:0,kind:"LITERAL",value:"/opt/orders/deploy.sh"}] })
]};
const row = ca.lifecycleControls(full, cctx);
assert(row && hasClass(row, "lifecycle-controls"), "approved lifecycle ops render a .lifecycle-controls row");
assert(findButton(row, "Start") && findButton(row, "Stop") && findButton(row, "Restart") && findButton(row, "Deploy"),
  "the control row carries Start / Stop / Restart / Deploy buttons");

// ---- 2. Start runs INLINE (POST /runs, run-output drawer with Cancel) — no confirm ----
fetchCalls.length = 0; modalRoot.children = [];
click(findButton(row, "Start"));
assert(findByClass(modalRoot, "drawer"), "Start opens a drawer");
assert(findByText(modalRoot, "Cancel run"), "Start opens the run-output drawer (Cancel run), not a confirm drawer");
assert(fetchCalls.some(c => c.method === "POST" && /\/runs$/.test(c.url)), "Start POSTs /runs inline: " + JSON.stringify(fetchCalls));

// ---- 3. Stop routes through the review/confirm drawer; no run until confirmed ----
fetchCalls.length = 0; modalRoot.children = [];
click(findButton(row, "Stop"));
const stopDrawer = findByClass(modalRoot, "drawer");
assert(stopDrawer, "Stop opens the confirm drawer");
assert(findByText(stopDrawer, "Command"), "the confirm drawer shows the spec-044 renderActionReview 'Command' body");
assert(!fetchCalls.some(c => c.method === "POST" && /\/runs$/.test(c.url)), "Stop must NOT run until confirmed: " + JSON.stringify(fetchCalls));
const stopConfirm = findButtonStartsWith(stopDrawer, "Stop orders");
assert(stopConfirm, "the confirm drawer has an explicit 'Stop orders' confirm button");
click(stopConfirm);
assert(fetchCalls.some(c => c.method === "POST" && /\/runs$/.test(c.url)), "confirming Stop POSTs /runs");

// ---- 4. Deploy: confirm drawer + long-running warning + type-the-app-name gate ----
fetchCalls.length = 0; modalRoot.children = [];
click(findButton(row, "Deploy"));
const depDrawer = findByClass(modalRoot, "drawer");
assert(depDrawer && textIncludes(depDrawer, "Long-running"), "Deploy drawer carries the long-running warning");
const depConfirm = findButtonStartsWith(depDrawer, "Deploy orders");
assert(depConfirm && depConfirm.disabled === true, "Deploy confirm is DISABLED until the app name is typed");
const field = findByTag(depDrawer, "input");
assert(field, "Deploy drawer has a type-to-confirm text field");
field.value = "wrong"; fire(field, "input");
assert(depConfirm.disabled === true, "a wrong app name keeps Deploy disabled");
field.value = "orders"; fire(field, "input");
assert(depConfirm.disabled === false, "typing the exact app name enables Deploy");

// ---- 5. Restart composes when only start+stop exist (warning, no auto-run) ----
fetchCalls.length = 0; modalRoot.children = [];
const composable = { name:"orders", ops:[
  mkOp({ id:"start", name:"start", argTokens:[{position:0,kind:"LITERAL",value:"/opt/orders/run.sh"}] }),
  mkOp({ id:"stop", name:"stop", argTokens:[{position:0,kind:"LITERAL",value:"/opt/orders/kill.sh"}] })
]};
const row2 = ca.lifecycleControls(composable, cctx);
const restartBtn = findButton(row2, "Restart");
assert(restartBtn, "Restart renders when both start+stop exist (composed)");
click(restartBtn);
const composeDrawer = findByClass(modalRoot, "drawer");
assert(composeDrawer && textIncludes(composeDrawer, "Composed restart"),
  "the composed-restart drawer warns it runs Stop then Start (half-failure leaves the app down)");
assert(!fetchCalls.some(c => c.method === "POST" && /\/runs$/.test(c.url)),
  "composed restart does NOT auto-run before the user confirms: " + JSON.stringify(fetchCalls));

// no start+stop, no restart → no lifecycle row at all
assert(ca.lifecycleControls({ name:"x", ops:[mkOp({ name:"status" })] }, cctx) === null,
  "an app with only non-lifecycle ops renders no control row");

// ---- 6. systemd dedup: a SYSTEMD restart control wins over a script one for the verb ----
const both = [
  mkOp({ id:"scriptRestart", name:"restart", recipeType:"CUSTOM" }),
  mkOp({ id:"systemdRestart", name:"restart", recipeType:"SYSTEMD" })
];
assert(ca.lifecycleOpForVerb(both, "restart").id === "systemdRestart",
  "dedup prefers the SYSTEMD control per verb so a card never shows two Restart buttons");

console.log("PASS: spec-050 — approved start/stop/restart/deploy ops render as card controls; Start runs inline (POST /runs, run-output drawer) while Stop/Restart/Deploy route through the spec-044 confirm drawer (no run until confirmed); Deploy adds the long-running warning + type-the-app-name gate; Restart composes stop→start (warned, no auto-run) when no real restart exists; systemd dedup wins per verb");

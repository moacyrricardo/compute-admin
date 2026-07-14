"use strict";
// spec-044 headless render check: recipe actions render as a GRID of cards, each
// carrying a split-approve control (a .btn-group whose primary is the valid primary
// transition for the state + a caret .menu of the other valid verbs). Asserts the
// review-safety guard: a FIRST-TIME approval (no approvedAt) or any changedSinceApproval
// action makes the primary "Review & approve", which OPENS THE DRAWER (does not POST
// /approve); only a routine re-approval of an unchanged action approves in one click.
// Loads the REAL app.js in a minimal DOM stub, exposes its internals, drives clicks.
const fs = require("fs");
const vm = require("vm");
const path = process.argv[2] || __dirname + "/../../main/resources/static/app.js";
let src = fs.readFileSync(path, "utf8");

src = src.replace(
  "  window.addEventListener(\"hashchange\", route);\n  route();\n})();",
  "  window.addEventListener(\"hashchange\", route);\n" +
  "  globalThis.__ca = { actionsList, actionCard, approvalSplit, splitButton, openActionDrawer };\n})();"
);
if (src.indexOf("globalThis.__ca") < 0) { console.error("FAIL: could not inject test hook"); process.exit(1); }

// ---- minimal DOM stub with real event dispatch + a stable modal-root by id ----
function makeNode(tag) {
  return {
    tagName: tag, children: [], _text: null, style: {}, _class: "", attrs: {}, value: "",
    _ev: {},
    classList: { _s: {}, add(c){this._s[c]=1;}, remove(c){delete this._s[c];}, contains(c){return !!this._s[c];}, toggle(c){this._s[c]?delete this._s[c]:this._s[c]=1;} },
    // Keep className and classList in sync (the browser does; the code sets the
    // initial "menu hidden" via className then toggles "hidden" via classList).
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
    querySelector(sel){ // only "button" is used by the code under test
      for (const c of this.children) {
        if (c.tagName === sel) return c;
        const found = c.querySelector && c.querySelector(sel);
        if (found) return found;
      }
      return null;
    },
    closest(){ return null; }
  };
}
// Fire a click on a node's registered handlers with a minimal event.
function click(node) {
  const ev = { target: node, stopPropagation(){}, preventDefault(){} };
  (node._ev.click || []).forEach(fn => fn(ev));
}
function findByText(node, text) {
  if (node._text === text) return node;
  for (const c of (node.children || [])) { const r = findByText(c, text); if (r) return r; }
  return null;
}
// className "contains" a class token
function hasClass(node, cls) {
  return (" " + (node.className || "") + " ").indexOf(" " + cls + " ") >= 0;
}
function findByClass(node, cls) {
  if (node.className && hasClass(node, cls)) return node;
  for (const c of node.children) { const r = c.children ? findByClass(c, cls) : null; if (r) return r; }
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
const fakeRes = { ok:true, status:200, headers:{ get:()=>"application/json" }, json:()=>Promise.resolve([]), text:()=>Promise.resolve("[]") };
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
const recipe = { id: "r1", name: "nginx" };
function mkAction(over){ return Object.assign({ id: "a1", name: "reload nginx", approvalState: "PENDING_APPROVAL", sudo: false, paramDefs: [], argTokens: [{ position:0, kind:"LITERAL", value:"nginx" }], approvedAt: null, changedSinceApproval: false }, over); }

// ---- 1. grid of cards, each with a split-button ----
const grid = ca.actionsList(machine, recipe, [
  mkAction({ id:"draft", name:"a draft", approvalState:"DRAFT" }),
  mkAction({ id:"pend", name:"first-time pending" }),
  mkAction({ id:"appr", name:"an approved", approvalState:"APPROVED" })
]);
assert(hasClass(grid, "action-cards"), "actionsList must render an .action-cards grid, got className=" + grid.className);
assert(grid.children.length === 3, "grid must hold one card per action, got " + grid.children.length);
assert(grid.children.every(c => hasClass(c, "action-card")), "each grid child is an .action-card");
assert(grid.children.every(c => findByClass(c, "btn-group")), "each card carries a .btn-group split control");

// ---- 2. review-safety guard: FIRST-TIME pending → primary "Review & approve", opens drawer, no approve POST ----
fetchCalls.length = 0;
modalRoot.children = [];
const firstTime = mkAction({ id:"ft", approvedAt: null, changedSinceApproval: false });
const splitFT = ca.approvalSplit(machine, recipe, firstTime);
const primaryFT = splitFT.children[0];
assert(primaryFT.textContent === "Review & approve",
  "first-time pending primary must be 'Review & approve', got '" + primaryFT.textContent + "'");
click(primaryFT);
assert(modalRoot.children.length === 1 && findByClass(modalRoot, "drawer"),
  "clicking 'Review & approve' must OPEN THE DRAWER (a .drawer in modal-root)");
assert(!fetchCalls.some(c => c.method === "POST" && /\/approve$/.test(c.url)),
  "clicking 'Review & approve' must NOT POST /approve directly: " + JSON.stringify(fetchCalls));

// ---- 3. changedSinceApproval (even with a prior approval) → also "Review & approve" ----
const changed = mkAction({ id:"ch", approvedAt: "2026-01-01T00:00:00Z", changedSinceApproval: true });
const primaryCh = ca.approvalSplit(machine, recipe, changed).children[0];
assert(primaryCh.textContent === "Review & approve",
  "a changedSinceApproval action must require review, got '" + primaryCh.textContent + "'");

// ---- 4. routine re-approval of an UNCHANGED, previously-approved action → one-click Approve (POSTs /approve) ----
fetchCalls.length = 0;
modalRoot.children = [];
const routine = mkAction({ id:"rt", approvedAt: "2026-01-01T00:00:00Z", changedSinceApproval: false });
const primaryRt = ca.approvalSplit(machine, recipe, routine).children[0];
assert(primaryRt.textContent === "Approve",
  "a routine unchanged re-approval primary must be one-click 'Approve', got '" + primaryRt.textContent + "'");
click(primaryRt);
assert(fetchCalls.some(c => c.method === "POST" && /\/actions\/rt\/approve$/.test(c.url)),
  "one-click Approve must POST /actions/rt/approve: " + JSON.stringify(fetchCalls));
assert(!findByClass(modalRoot, "drawer"), "one-click Approve must not open the drawer");

// ---- 5. primary transition per state; caret menu carries valid verbs + See more… ----
const draftPrimary = ca.approvalSplit(machine, recipe, mkAction({ approvalState:"DRAFT" })).children[0];
assert(draftPrimary.textContent === "Submit", "DRAFT primary is Submit, got '" + draftPrimary.textContent + "'");
const apprSplit = ca.approvalSplit(machine, recipe, mkAction({ approvalState:"APPROVED" }));
assert(apprSplit.children[0].textContent === "Run", "APPROVED primary is Run, got '" + apprSplit.children[0].textContent + "'");
const apprMenu = findByClass(apprSplit, "menu");
assert(apprMenu && findByText(apprMenu, "Revoke"), "APPROVED caret menu offers Revoke");
assert(apprMenu && findByText(apprMenu, "See more…"), "the caret menu offers 'See more…' (the review drawer)");

// ---- 6. caret is keyboard/aria wired ----
const caret = findByClass(apprSplit, "btn--caret");
assert(caret && caret.getAttribute("aria-haspopup") === "true" && caret.getAttribute("aria-expanded") === "false",
  "caret exposes aria-haspopup + aria-expanded=false when closed");
click(caret);
assert(caret.getAttribute("aria-expanded") === "true", "clicking the caret opens the menu (aria-expanded=true)");

console.log("PASS: spec-044 — actions render as an .action-cards grid of split-button cards; the review-safety guard routes a first-time OR changedSinceApproval approval through 'Review & approve' (opens the drawer, no direct /approve POST) while a routine unchanged re-approval approves in one click; primaries follow the state (DRAFT→Submit, APPROVED→Run) and the caret menu (aria-wired) carries the other valid verbs + 'See more…'");

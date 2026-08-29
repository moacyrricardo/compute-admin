"use strict";
// spec-065 headless check — selectable visual identity.
//
// A visual-identity spec is mostly CSS + a little wiring, so this check has two
// halves:
//   Part A — static-asset invariants a DOM stub cannot see: the enabling refactor
//     (the five hard-coded colour spots are now tokens), the OS-dark gating, the
//     three identity token sets, and the index.html pre-paint-stamp ordering.
//     These are regression guards: a literal colour bypass creeping back, or the
//     dark block losing its gate, fails here.
//   Part B — behavioural: load the REAL app.js in a DOM stub and drive the topbar
//     switcher (reflect stored / whitelist / persist ca.identity / re-stamp
//     <html data-identity>) plus the title-block route + date cell fills.
const fs = require("fs");
const vm = require("vm");
const dir = __dirname + "/../../main/resources/static/";

let failed = 0;
function assert(cond, msg) { if (!cond) { console.error("FAIL: " + msg); failed++; } }

// ======================================================================= A ===
const tokens = fs.readFileSync(dir + "tokens.css", "utf8");
const appcss = fs.readFileSync(dir + "app.css", "utf8");
const html   = fs.readFileSync(dir + "index.html", "utf8");

// A1 — three identities: current is base :root; iskeru/blueprint are scoped blocks.
assert(/:root\[data-identity="iskeru"\]/.test(tokens), "tokens.css declares the iskeru identity block");
assert(/:root\[data-identity="blueprint"\]/.test(tokens), "tokens.css declares the blueprint identity block");
assert(/:root\[data-identity="iskeru"\][\s\S]*color-scheme:\s*dark/.test(tokens), "iskeru pins color-scheme:dark (committed dark)");
assert(/:root\[data-identity="blueprint"\][\s\S]*color-scheme:\s*light/.test(tokens), "blueprint pins color-scheme:light (committed light)");

// A2 — the OS light/dark pair is gated OFF the two committed identities.
assert(/@media \(prefers-color-scheme: dark\)\s*\{\s*:root:not\(\[data-identity="iskeru"\]\):not\(\[data-identity="blueprint"\]\)/.test(tokens),
  "the prefers-color-scheme:dark block is re-scoped to exclude iskeru + blueprint");

// A3 — the contract tokens all exist on base :root (so `current` needs no override).
["--accent-2", "--accent-ink", "--accent-grad", "--accent-soft", "--font-display",
 "--btn-primary-shadow", "--hair", "--terminal-bg", "--terminal-ink",
 "--backdrop-modal", "--backdrop-drawer"].forEach(function (t) {
  assert(tokens.indexOf(t) >= 0, "tokens.css declares the contract token " + t);
});

// A4 — the FIVE hard-coded colour spots are tokenized (regression: no literal returns).
assert(/\.btn--primary\s*\{[^}]*color:\s*var\(--accent-ink\)/.test(appcss), ".btn--primary ink uses --accent-ink");
assert(/\.btn--primary\s*\{[^}]*background:\s*var\(--accent-grad\)/.test(appcss), ".btn--primary fill uses --accent-grad");
assert(!/\.btn--primary\s*\{[^}]*#ffffff/.test(appcss), ".btn--primary no longer hard-codes #ffffff");
assert(!/@media \(prefers-color-scheme: dark\)\s*\{\s*\.btn--primary/.test(appcss), "the .btn--primary dark @media override is deleted (moved into the gated token block)");
assert(/\.terminal\s*\{[\s\S]*?var\(--terminal-bg\)[\s\S]*?var\(--terminal-ink\)/.test(appcss), ".terminal uses --terminal-bg/--terminal-ink");
assert(appcss.indexOf("#06121a") < 0 && appcss.indexOf("#d5e3ea") < 0, "app.css no longer hard-codes the terminal literals");
assert(/\.modal-backdrop\s*\{[\s\S]*?var\(--backdrop-modal\)/.test(appcss), ".modal-backdrop uses --backdrop-modal");
assert(/\.drawer-backdrop\s*\{[\s\S]*?var\(--backdrop-drawer\)/.test(appcss), ".drawer-backdrop uses --backdrop-drawer");
assert(appcss.indexOf("rgba(15, 23, 42, 0.55)") < 0 && appcss.indexOf("rgba(15, 23, 42, 0.45)") < 0, "app.css no longer hard-codes the slate backdrop scrims");
assert(/\.tag--filter\.tag--on\s*\{[\s\S]*?var\(--accent-ink\)/.test(appcss), ".tag--filter.tag--on (the fifth bypass) uses --accent-ink");

// A5 — identity layout remaps are gated to the desktop breakpoint; blueprint extends the grid.
assert(/@media \(min-width: 721px\)[\s\S]*?:root\[data-identity="iskeru"\] \.shell/.test(appcss), "iskeru's shell grid remap is gated to min-width:721px (mobile falls back)");
assert(/grid-area:\s*titleblock/.test(appcss), "blueprint gives .pf-titleblock its own grid-area (titleblock)");

// A6 — index.html: pre-paint stamp BEFORE the stylesheets; switcher; title-block after #view.
const stampIdx = html.indexOf("document.documentElement.dataset.identity");
const tokensLinkIdx = html.indexOf('href="tokens.css"');
assert(stampIdx >= 0 && stampIdx < tokensLinkIdx, "the pre-paint identity stamp runs before the tokens.css link (no flash-of-wrong-identity)");
assert(/id="identity-switch"[\s\S]*data-identity="current"[\s\S]*data-identity="iskeru"[\s\S]*data-identity="blueprint"/.test(html), "the topbar switcher offers all three identities");
const viewIdx = html.indexOf('id="view"');
const tbIdx = html.indexOf('class="pf-titleblock"');
assert(tbIdx >= 0, "the net-new .pf-titleblock element is present");
assert(viewIdx >= 0 && tbIdx > viewIdx, "the title-block is a shell child placed after #view (survives the per-route re-render)");

// ======================================================================= B ===
// A DOM stub complete enough to run app.js's boot + one route() pass.
function makeNode(tag) {
  return {
    tagName: tag, children: [], attrs: {}, dataset: {}, style: {}, value: "",
    _text: null, _listeners: {},
    classList: {
      _s: {},
      add(c) { this._s[c] = 1; }, remove(c) { delete this._s[c]; },
      contains(c) { return !!this._s[c]; },
      toggle(c, force) { var on = force === undefined ? !this._s[c] : !!force; on ? this._s[c] = 1 : delete this._s[c]; return on; }
    },
    get firstChild() { return this.children[0] || null; },
    removeChild(k) { var i = this.children.indexOf(k); if (i >= 0) this.children.splice(i, 1); return k; },
    appendChild(k) { this.children.push(k); return k; },
    set textContent(v) { this._text = String(v); this.children = []; },
    get textContent() { if (this._text != null) return this._text; return this.children.map(function (c) { return c.textContent; }).join(""); },
    setAttribute(k, v) { this.attrs[k] = v; if (k.indexOf("data-") === 0) this.dataset[k.slice(5).replace(/-([a-z])/g, function (_, c) { return c.toUpperCase(); })] = v; },
    getAttribute(k) { return this.attrs[k] !== undefined ? this.attrs[k] : null; },
    addEventListener(type, fn) { (this._listeners[type] = this._listeners[type] || []).push(fn); },
    dispatch(type, evt) { (this._listeners[type] || []).forEach(function (fn) { fn(evt); }); },
    focus() {}, closest() { return null; }, querySelectorAll() { return []; }
  };
}

// Build a switch group whose querySelectorAll returns three button nodes, each of
// which .closest("button[data-identity]") resolves to itself (as a real one would).
function makeSwitchGroup() {
  var group = makeNode("div");
  var buttons = ["current", "iskeru", "blueprint"].map(function (id) {
    var b = makeNode("button");
    b.setAttribute("data-identity", id);
    b.closest = function () { return b; };
    return b;
  });
  group.querySelectorAll = function () { return buttons; };
  group._buttons = buttons;
  return group;
}
function btn(group, id) { return group._buttons.filter(function (b) { return b.getAttribute("data-identity") === id; })[0]; }

function load(opts) {
  var registry = {};
  var group = makeSwitchGroup();
  registry["identity-switch"] = group;
  var document = {
    documentElement: { dataset: Object.assign({}, opts.stampedIdentity ? { identity: opts.stampedIdentity } : {}) },
    createElement: function (t) { return makeNode(t); },
    createTextNode: function (t) { var n = makeNode("#text"); n._text = String(t); return n; },
    getElementById: function (id) { return registry[id] || (registry[id] = makeNode("div")); },
    querySelectorAll: function () { return []; }
  };
  var store = Object.assign({}, opts.storage || {});
  var window = { addEventListener: function () {}, location: { hash: opts.hash || "" } };
  var ctx = {
    document: document, window: window, location: window.location, console: console,
    localStorage: {
      getItem: function (k) { return k in store ? store[k] : null; },
      setItem: function (k, v) { store[k] = String(v); },
      removeItem: function (k) { delete store[k]; }
    },
    setTimeout: function () { return 0; }, clearTimeout: function () {},
    fetch: function () { return Promise.resolve({ ok: true, json: function () { return Promise.resolve({}); }, text: function () { return Promise.resolve("{}"); } }); },
    TextDecoder: function () { this.decode = function () { return ""; }; },
    AbortController: function () { this.abort = function () {}; this.signal = {}; },
    globalThis: null
  };
  ctx.globalThis = ctx;
  ctx._store = store;
  ctx._doc = document;
  ctx._group = group;
  ctx._registry = registry;
  vm.createContext(ctx);
  var src = fs.readFileSync(dir + "app.js", "utf8");
  vm.runInContext(src, ctx);
  return ctx;
}

// B1 — a stored identity is reflected on the switcher; the title-block cells fill.
var c1 = load({ storage: { "ca.identity": "iskeru", "ca.jwt": "x", "ca.user": JSON.stringify({ name: "T", email: "t@e" }) }, stampedIdentity: "iskeru", hash: "#/zzz" });
assert(btn(c1._group, "iskeru").classList.contains("on"), "the stored identity's switcher button is marked active on load");
assert(!btn(c1._group, "current").classList.contains("on"), "a non-stored identity's button is not active");
assert(/^\d{4}-\d{2}-\d{2}$/.test(c1._registry["titleblock-date"]._text || ""), "the title-block Date cell is stamped with an ISO date at boot");
assert(c1._registry["titleblock-route"]._text === "zzz", "the title-block View cell tracks the current route on load");

// B2 — clicking a button persists ca.identity + re-stamps <html data-identity> live.
c1._group.dispatch("click", { target: btn(c1._group, "blueprint") });
assert(c1._store["ca.identity"] === "blueprint", "clicking a switcher button persists ca.identity");
assert(c1._doc.documentElement.dataset.identity === "blueprint", "clicking re-stamps <html data-identity> (tokens flip live, no reload)");
assert(btn(c1._group, "blueprint").classList.contains("on") && !btn(c1._group, "iskeru").classList.contains("on"), "the active button follows the click");

// B3 — an out-of-whitelist value is rejected (no persist, no re-stamp).
var bogus = makeNode("button"); bogus.setAttribute("data-identity", "hacker"); bogus.closest = function () { return bogus; };
c1._group.dispatch("click", { target: bogus });
assert(c1._store["ca.identity"] === "blueprint", "a non-whitelisted identity is not persisted");
assert(c1._doc.documentElement.dataset.identity === "blueprint", "a non-whitelisted identity does not re-stamp <html>");

// B4 — a corrupt stored value falls back to `current` (whitelist on read).
var c2 = load({ storage: { "ca.identity": "garbage" }, hash: "" });
assert(btn(c2._group, "current").classList.contains("on"), "a corrupt stored ca.identity falls back to the current button");
assert(!btn(c2._group, "iskeru").classList.contains("on") && !btn(c2._group, "blueprint").classList.contains("on"), "no committed identity is active on a corrupt stored value");

if (failed) { console.error("FAILED: " + failed + " assertion(s)"); process.exit(1); }
console.log("PASS: spec-065 — the five hard-coded colour spots are tokenized, the OS-dark block is gated off the two committed identities, the three identity token sets ship, the pre-paint stamp precedes the stylesheets; the switcher reflects/persists/re-stamps a whitelisted identity live and the title-block route+date cells fill");

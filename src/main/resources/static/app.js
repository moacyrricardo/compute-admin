/*
 * spec-012 — compute-admin web UI.
 *
 * A framework-free, JSON-driven vanilla-JS shell. A hash router picks a render
 * function; each render function `fetch`es the relevant /api JSON and builds the
 * DOM. The approval screen is the centrepiece: it renders the exact command a
 * human is signing off on.
 *
 * XSS discipline (spec-012 "Known Gaps"): every user- or cloud-derived string
 * (tags, hosts, names, command output, param values) reaches the DOM only through
 * `textContent` / text nodes — this file never assigns innerHTML. The `h()` helper
 * enforces that: string children become text nodes; there is no `html` prop.
 */
(function () {
  "use strict";

  // ------------------------------------------------------------------ dom ---

  /**
   * Create an element. `props.text` sets textContent (safe). String children are
   * appended as text nodes (safe). There is deliberately no innerHTML path.
   */
  function h(tag, props) {
    var el = document.createElement(tag);
    if (props) {
      Object.keys(props).forEach(function (k) {
        var v = props[k];
        if (v == null || v === false) return;
        if (k === "class") el.className = v;
        else if (k === "text") el.textContent = v;
        else if (k === "html") throw new Error("innerHTML is forbidden (spec-012)");
        else if (k.slice(0, 2) === "on" && typeof v === "function") {
          el.addEventListener(k.slice(2), v);
        } else if (v === true) el.setAttribute(k, "");
        else el.setAttribute(k, v);
      });
    }
    for (var i = 2; i < arguments.length; i++) {
      appendChild(el, arguments[i]);
    }
    return el;
  }

  function appendChild(el, kid) {
    if (kid == null || kid === false) return;
    if (Array.isArray(kid)) {
      kid.forEach(function (k) { appendChild(el, k); });
    } else if (typeof kid === "string" || typeof kid === "number") {
      el.appendChild(document.createTextNode(String(kid)));
    } else {
      el.appendChild(kid);
    }
  }

  function clear(el) { while (el.firstChild) el.removeChild(el.firstChild); }

  function byId(id) { return document.getElementById(id); }

  function toast(message) {
    var root = byId("toast-root");
    clear(root);
    var t = h("div", { class: "toast", role: "status" }, message);
    root.appendChild(t);
    setTimeout(function () { if (t.parentNode === root) root.removeChild(t); }, 2600);
  }

  // -------------------------------------------------------------- session ---

  var Session = {
    token: function () { return localStorage.getItem("ca.jwt"); },
    user: function () {
      try { return JSON.parse(localStorage.getItem("ca.user") || "null"); }
      catch (e) { return null; }
    },
    set: function (token, user) {
      localStorage.setItem("ca.jwt", token);
      localStorage.setItem("ca.user", JSON.stringify(user));
    },
    clear: function () {
      localStorage.removeItem("ca.jwt");
      localStorage.removeItem("ca.user");
    }
  };

  // ------------------------------------------------------ recent-run cache ---
  // The run engine (spec-005) exposes no "list runs" endpoint and RunView carries
  // no params or command. To render the Runs index and the "command that ran" /
  // "parameters used" panels, we remember what we launched this session locally.

  var Runs = {
    all: function () {
      try { return JSON.parse(localStorage.getItem("ca.runs") || "[]"); }
      catch (e) { return []; }
    },
    remember: function (entry) {
      var list = Runs.all().filter(function (r) { return r.id !== entry.id; });
      list.unshift(entry);
      localStorage.setItem("ca.runs", JSON.stringify(list.slice(0, 50)));
    },
    get: function (id) {
      return Runs.all().filter(function (r) { return r.id === id; })[0] || null;
    }
  };

  // ------------------------------------------------------------- api client ---

  function authHeaders(extra) {
    var hdr = { "Authorization": "Bearer " + (Session.token() || "") };
    if (extra) Object.keys(extra).forEach(function (k) { hdr[k] = extra[k]; });
    return hdr;
  }

  /** JSON API call scoped to /api with the bearer token. 401 → back to login. */
  function api(method, path, body) {
    var opts = { method: method, headers: authHeaders() };
    if (body !== undefined) {
      opts.headers["Content-Type"] = "application/json";
      opts.body = JSON.stringify(body);
    }
    return fetch("/api" + path, opts).then(function (res) {
      if (res.status === 401) {
        Session.clear();
        showLogin();
        throw new Error("unauthorized");
      }
      if (!res.ok) {
        return res.text().then(function (t) {
          var msg = t;
          try { msg = JSON.parse(t).error || t; } catch (e) { /* keep text */ }
          throw new Error(msg || ("HTTP " + res.status));
        });
      }
      if (res.status === 204) return null;
      var ct = res.headers.get("content-type") || "";
      return ct.indexOf("application/json") >= 0 ? res.json() : res.text();
    });
  }

  /**
   * Stream a run's SSE output through `fetch` (not EventSource) so the Bearer
   * token can be sent — the /api/runs/{id}/output endpoint is @Secured and
   * EventSource cannot set headers. Replays the buffered prefix then the live
   * tail and resolves when the server closes the stream.
   */
  function streamRunOutput(runId, handlers) {
    var controller = new AbortController();
    fetch("/api/runs/" + encodeURIComponent(runId) + "/output", {
      headers: authHeaders({ "Accept": "text/event-stream" }),
      signal: controller.signal
    }).then(function (res) {
      if (!res.ok || !res.body) { handlers.onDone && handlers.onDone(); return; }
      var reader = res.body.getReader();
      var decoder = new TextDecoder();
      var buf = "", evName = "message", dataLines = [];
      function dispatch() {
        if (dataLines.length && handlers.onChunk) {
          handlers.onChunk(evName, dataLines.join("\n"));
        }
        evName = "message"; dataLines = [];
      }
      function pump() {
        return reader.read().then(function (r) {
          if (r.done) { dispatch(); handlers.onDone && handlers.onDone(); return; }
          buf += decoder.decode(r.value, { stream: true });
          var idx;
          while ((idx = buf.indexOf("\n")) >= 0) {
            var line = buf.slice(0, idx);
            buf = buf.slice(idx + 1);
            if (line.charAt(line.length - 1) === "\r") line = line.slice(0, -1);
            if (line === "") { dispatch(); continue; }
            if (line.indexOf("event:") === 0) evName = line.slice(6).trim();
            else if (line.indexOf("data:") === 0) {
              var d = line.slice(5);
              if (d.charAt(0) === " ") d = d.slice(1);
              dataLines.push(d);
            }
          }
          return pump();
        });
      }
      return pump();
    }).catch(function () { handlers.onDone && handlers.onDone(); });
    return controller;
  }

  // --------------------------------------------------------------- helpers ---

  var CHIP = {
    APPROVED: "ok", DONE: "ok", ONLINE: "ok",
    PENDING_APPROVAL: "warn",
    REVOKED: "bad", FAILED: "bad", UNREACHABLE: "bad", INTERRUPTED: "bad",
    RUNNING: "info", QUEUED: "info",
    DRAFT: "neutral", UNKNOWN: "neutral", OFFLINE: "neutral", STOPPED: "neutral"
  };

  /** The single state-chip component: colour PLUS label, never colour alone. */
  function chip(state) {
    var kind = CHIP[state] || "neutral";
    return h("span", { class: "chip chip--" + kind, title: state }, humanize(state));
  }

  function humanize(s) {
    return String(s || "").toLowerCase().replace(/_/g, " ");
  }

  function fmtTime(iso) {
    if (!iso) return "—";
    var d = new Date(iso);
    return isNaN(d.getTime()) ? String(iso) : d.toLocaleString();
  }

  /** Human description of one typed param rule (spec-004 ParamKind). */
  function paramRuleText(def) {
    if (def.kind === "ALLOWED_SET") return "ALLOWED_SET { " + (def.allowedValues || []).join(", ") + " }";
    if (def.kind === "REGEX") return "REGEX /" + (def.pattern || "") + "/";
    if (def.kind === "INT_RANGE") {
      return "INT_RANGE [" + (def.intMin != null ? def.intMin : "−∞") + ", "
        + (def.intMax != null ? def.intMax : "+∞") + "]";
    }
    return def.kind;
  }

  /** Client-side mirror of the server ParamBinder rule (spec-004). */
  function validateParam(def, value) {
    if (value == null || value === "") return false;
    if (def.kind === "ALLOWED_SET") return (def.allowedValues || []).indexOf(value) >= 0;
    if (def.kind === "REGEX") {
      try { return new RegExp("^(?:" + (def.pattern || "") + ")$").test(value); }
      catch (e) { return false; }
    }
    if (def.kind === "INT_RANGE") {
      if (!/^-?\d+$/.test(value.trim())) return false;
      var n = parseInt(value.trim(), 10);
      if (def.intMin != null && n < def.intMin) return false;
      if (def.intMax != null && n > def.intMax) return false;
      return true;
    }
    return false;
  }

  /**
   * Render the command in monospace: LITERAL tokens plain, PARAM tokens visually
   * distinct (accent underline). When `values` is supplied, each PARAM slot shows
   * its chosen value (the live resolved-command preview); otherwise it shows its
   * `{name}` placeholder.
   */
  function renderCommand(action, values) {
    var box = h("code", { class: "command" });
    if (action.sudo) {
      box.appendChild(h("span", { class: "sudo-prefix" }, "sudo -n"));
      box.appendChild(document.createTextNode(" "));
    }
    var tokens = (action.argTokens || []).slice().sort(function (a, b) { return a.position - b.position; });
    tokens.forEach(function (tok, i) {
      if (i > 0) box.appendChild(document.createTextNode(" "));
      if (tok.kind === "PARAM") {
        var chosen = values ? values[tok.value] : "";
        var filled = chosen != null && chosen !== "";
        box.appendChild(h("span", {
          class: "tok-param" + (filled ? " filled" : ""),
          title: "parameter: " + tok.value
        }, filled ? chosen : "{" + tok.value + "}"));
      } else {
        box.appendChild(h("span", { class: "tok-literal" }, tok.value));
      }
    });
    return box;
  }

  function pageHead(title, sub, actions) {
    return h("div", { class: "page-head" },
      h("div", null, h("h1", { text: title }), sub ? h("p", { class: "sub", text: sub }) : null),
      actions ? h("div", { class: "row" }, actions) : null);
  }

  function crumbs() {
    var wrap = h("nav", { class: "crumbs", "aria-label": "Breadcrumb" });
    for (var i = 0; i < arguments.length; i++) {
      if (i > 0) wrap.appendChild(document.createTextNode(" / "));
      wrap.appendChild(arguments[i]);
    }
    return wrap;
  }

  function link(hash, text, cls) { return h("a", { href: hash, class: cls }, text); }

  function empty(text) { return h("div", { class: "empty", text: text }); }

  function loading() { return h("div", { class: "empty", text: "Loading…" }); }

  function errorCard(err) {
    return h("div", { class: "banner banner--bad" },
      h("div", { class: "banner-body" }, h("strong", { text: "Error: " }), String(err && err.message || err)));
  }

  // ---------------------------------------------------------------- modal ---

  function openModal(node) {
    var root = byId("modal-root");
    clear(root);
    var backdrop = h("div", { class: "modal-backdrop", onclick: function (e) {
      if (e.target === backdrop) closeModal();
    } });
    backdrop.appendChild(node);
    root.appendChild(backdrop);
  }
  function closeModal() { clear(byId("modal-root")); }

  function revealOnceModal(title, value) {
    openModal(h("div", { class: "modal", role: "dialog", "aria-modal": "true" },
      h("h2", { text: title }),
      h("p", { class: "small dim", text: "Copy it now — it is shown only once and cannot be retrieved again." }),
      h("div", { class: "reveal-value", text: value }),
      h("div", { class: "row" },
        h("button", { class: "btn btn--primary", onclick: function () {
          copy(value);
        } }, "Copy"),
        h("button", { class: "btn", onclick: closeModal }, "Done"))));
  }

  function copy(text) {
    // navigator.clipboard exists only in a secure context (HTTPS or localhost). Over
    // plain HTTP on a LAN IP it is undefined, so fall back to a temporary textarea +
    // execCommand("copy"), which works in non-secure contexts.
    function fallback() {
      try {
        var ta = document.createElement("textarea");
        ta.value = text;
        ta.setAttribute("readonly", "");
        ta.style.position = "fixed";
        ta.style.top = "-1000px";
        ta.style.opacity = "0";
        document.body.appendChild(ta);
        ta.select();
        var ok = document.execCommand("copy");
        document.body.removeChild(ta);
        toast(ok ? "Copied to clipboard" : "Copy failed — select manually");
      } catch (e) {
        toast("Copy failed — select manually");
      }
    }
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(
        function () { toast("Copied to clipboard"); },
        fallback);
    } else {
      fallback();
    }
  }

  // --------------------------------------------------------- split button ---
  // spec-044: a .btn-group of a primary .btn plus a caret .btn that toggles a
  // .menu of secondary actions. Keyboard-accessible (aria-expanded on the caret,
  // Escape closes), closes on outside-click, and — because only one menu may be
  // open at a time and the router calls closeAllMenus() — never leaks an open
  // menu across a route change. textContent-only, like everything else here.

  var openMenuCloser = null;
  function closeAllMenus() {
    var c = openMenuCloser;
    openMenuCloser = null;
    if (c) c();
  }

  /**
   * splitButton({ primary, items }) — `primary` is a prebuilt .btn (button OR
   * link); `items` is [{ label, onClick, cls }]. With no items the caret/menu is
   * omitted and the bare primary is returned.
   */
  function splitButton(opts) {
    var group = h("div", { class: "btn-group" });
    group.appendChild(opts.primary);
    var items = opts.items || [];
    if (!items.length) return group;

    var menu = h("div", { class: "menu hidden", role: "menu" });
    var caret = h("button", {
      type: "button", class: "btn btn--caret",
      "aria-haspopup": "true", "aria-expanded": "false", "aria-label": "More actions"
    }, "▾");

    function closeMenu() {
      menu.classList.add("hidden");
      caret.setAttribute("aria-expanded", "false");
      document.removeEventListener("click", onDoc, true);
      if (openMenuCloser === closeMenu) openMenuCloser = null;
    }
    function onDoc(e) { if (!group.contains(e.target)) closeMenu(); }
    function openMenu() {
      closeAllMenus();
      menu.classList.remove("hidden");
      caret.setAttribute("aria-expanded", "true");
      document.addEventListener("click", onDoc, true);
      openMenuCloser = closeMenu;
      var first = menu.querySelector("button");
      if (first) first.focus();
    }
    caret.addEventListener("click", function (e) {
      e.stopPropagation();
      if (menu.classList.contains("hidden")) openMenu(); else closeMenu();
    });
    caret.addEventListener("keydown", function (e) {
      if (e.key === "Escape") closeMenu();
    });

    items.forEach(function (it) {
      var mi = h("button", {
        type: "button", role: "menuitem",
        class: "menu-item" + (it.cls ? " menu-item--" + it.cls : "")
      }, it.label);
      mi.addEventListener("click", function (e) {
        e.stopPropagation();
        closeMenu();
        it.onClick();
      });
      menu.appendChild(mi);
    });
    menu.addEventListener("keydown", function (e) {
      if (e.key === "Escape") { closeMenu(); caret.focus(); }
    });

    group.appendChild(caret);
    group.appendChild(menu);
    return group;
  }

  function sudoBadge() { return h("span", { class: "badge-sudo", text: "sudo" }); }

  /**
   * spec-044: a small copy-host control shown beside a machine's name. Machines
   * read name-first; this puts the connection target one click away. Copies the
   * raw host (the S9 UI view already exposes it; MCP views stay name/id-only).
   */
  function copyHostButton(machine) {
    var target = machine.loginUser + "@" + machine.host + ":" + machine.port;
    return h("button", {
      type: "button", class: "btn btn--sm", title: "Copy " + target,
      onclick: function (e) { if (e && e.stopPropagation) e.stopPropagation(); copy(machine.host); }
    }, "Copy host");
  }

  // --------------------------------------------------------------- mount ----

  function mount(node) {
    var view = byId("view");
    clear(view);
    view.appendChild(node);
    view.focus();
  }

  function mountAsync(builder) {
    mount(loading());
    builder().then(mount).catch(function (err) {
      if (err && err.message === "unauthorized") return;
      mount(errorCard(err));
    });
  }

  // =========================================================== SCREENS ======

  // ----- Machines list -----------------------------------------------------

  function screenMachines() {
    mountAsync(function () {
      return api("GET", "/machines").then(function (machines) {
        var head = pageHead("Machines", "SSH-reachable hosts you own.",
          link("#/machines/register", "Register machine", "btn btn--primary"));
        if (!machines.length) {
          return h("div", null, head,
            empty("No machines yet. Register one to install the app SSH key and probe connectivity."));
        }

        // Filter chips derived from the loaded set; selection narrows the list
        // entirely client-side (single-user scale), OR semantics across chips.
        var allTags = [];
        var seen = {};
        machines.forEach(function (m) {
          (m.tags || []).forEach(function (t) {
            if (!seen[t]) { seen[t] = true; allTags.push(t); }
          });
        });
        allTags.sort();
        var selected = {};

        var chipsWrap = allTags.length ? h("div", { class: "filter-chips" }) : null;
        var listWrap = h("div", null);

        function selectedTags() {
          return allTags.filter(function (t) { return selected[t]; });
        }
        function matches(m) {
          var sel = selectedTags();
          if (!sel.length) return true;
          var mine = m.tags || [];
          return sel.some(function (t) { return mine.indexOf(t) !== -1; });
        }
        function renderList() {
          clear(listWrap);
          var visible = machines.filter(matches);
          if (!visible.length) {
            listWrap.appendChild(empty("No machines match the selected tags."));
            return;
          }
          listWrap.appendChild(h("ul", { class: "list" }, visible.map(function (m) {
            return h("li", null, h("div", { class: "row-between" },
              h("div", { class: "grow" },
                h("div", { class: "row" }, link("#/machines/" + m.id, m.name), copyHostButton(m)),
                h("div", { class: "small dim mono mt-2", text: m.loginUser + "@" + m.host + ":" + m.port }),
                h("div", { class: "row mt-2" }, (m.tags || []).map(function (t) {
                  return h("span", { class: "tag", text: t });
                }))),
              chip(m.status)));
          })));
        }
        function renderChips() {
          if (!chipsWrap) return;
          clear(chipsWrap);
          allTags.forEach(function (t) {
            var on = !!selected[t];
            chipsWrap.appendChild(h("button", {
              type: "button",
              class: "tag tag--filter" + (on ? " tag--on" : ""),
              "aria-pressed": on ? "true" : "false",
              text: t,
              onclick: function () {
                selected[t] = !selected[t];
                renderChips();
                renderList();
              }
            }));
          });
        }

        renderChips();
        renderList();
        return h("div", null, head, chipsWrap, listWrap);
      });
    });
  }

  // ----- Register machine + onboarding -------------------------------------

  function screenRegisterMachine() {
    mountAsync(function () {
      return api("GET", "/ssh/public-key").then(function (key) {
        var name = h("input", { placeholder: "web-prod-1" });
        var host = h("input", { class: "mono", placeholder: "10.0.0.5 or db.internal" });
        var port = h("input", { type: "number", value: "22", min: "1", max: "65535" });
        var user = h("input", { class: "mono", placeholder: "admin" });
        var status = h("div", { class: "mt-3" });
        var snippet = 'echo "' + key.publicKey + '" >> ~/.ssh/authorized_keys';

        function submit() {
          status.textContent = "";
          if (!name.value.trim() || !host.value.trim() || !user.value.trim()) {
            status.appendChild(h("div", { class: "field-error", text: "name, host and login user are required" }));
            return;
          }
          mount(loading());
          api("POST", "/machines", {
            name: name.value.trim(),
            host: host.value.trim(),
            port: parseInt(port.value, 10) || 22,
            loginUser: user.value.trim()
          }).then(function (m) {
            // Fire the promised "test connection" probe right after registering, then
            // land on the machine (whose status pill refreshes to ONLINE once the
            // reachability event is processed). A failed probe still lands there.
            return api("POST", "/machines/" + m.id + "/test").then(function (fresh) {
              toast("Registered — connection " + humanize(fresh.status));
              location.hash = "#/machines/" + m.id;
            }, function () {
              toast("Registered — probing connectivity");
              location.hash = "#/machines/" + m.id;
            });
          }).catch(function (err) { mount(errorCard(err)); });
        }

        return h("div", null,
          crumbs(link("#/machines", "Machines"), h("span", { text: "Register" })),
          pageHead("Register machine", "Install the app's public key on the target, then register and test the connection."),
          h("div", { class: "card" },
            h("h2", { text: "1 · Install the app SSH key" }),
            h("p", { class: "small dim mt-2", text: "One app-owned keypair serves the whole fleet; the private key never leaves this box. Add the public key to the target's authorized_keys:" }),
            h("div", { class: "field mt-3" },
              h("label", { text: "App public key" }),
              h("code", { class: "command command--scroll", text: key.publicKey }),
              h("div", { class: "hint mono", text: "fingerprint " + key.fingerprint })),
            h("div", { class: "field" },
              h("label", { text: "Install snippet" }),
              h("code", { class: "command command--scroll", text: snippet }),
              h("button", { class: "btn btn--sm mt-2", onclick: function () { copy(snippet); } }, "Copy snippet"))),
          h("div", { class: "card" },
            h("h2", { text: "2 · Register & test connection" }),
            h("div", { class: "field mt-3" }, h("label", { text: "Name" }), name),
            h("div", { class: "field" }, h("label", { text: "Host" }), host),
            h("div", { class: "field" }, h("label", { text: "Port" }), port),
            h("div", { class: "field" }, h("label", { text: "Login user" }), user),
            h("button", { class: "btn btn--primary", onclick: submit }, "Register & test connection"),
            status));
      });
    });
  }

  // ----- Machine detail -----------------------------------------------------

  // spec-067 Decision 4: a recipe's source set, derived from its RecipeView.appPortList
  // runtimes (docker ⇒ docker, else native — the server's own sourceOf). An empty
  // appPortList (blueprint/custom, and the plain /recipes list path — the majority) yields
  // {} = "other / none".
  function recipeSourceSet(recipe) {
    var set = {};
    ((recipe && recipe.appPortList) || []).forEach(function (item) {
      set[(item.runtime && item.runtime.toLowerCase() === "docker") ? "docker" : "native"] = true;
    });
    return set;
  }
  function recipeIsOtherNone(recipe) {
    var s = recipeSourceSet(recipe);
    return !s.docker && !s.native;
  }
  // A recipe passes the source filter when: no source chip is selected (all pass); OR it is
  // "other / none" (empty appPortList — NEVER hidden by a source chip, so turning on
  // native/docker cannot silently drop the un-pre-filled majority); OR at least one of its
  // sources is selected. A mixed-runtime recipe matches every source its items carry.
  function recipeMatchesSources(recipe, selected) {
    if (!selected.length) return true;
    if (recipeIsOtherNone(recipe)) return true;
    var src = recipeSourceSet(recipe);
    return selected.some(function (s) { return src[s]; });
  }

  /**
   * spec-067: the composed per-machine dashboard (Screen C). Stacks the identity head,
   * the tri-axis consumer footprint (the SAME makeFootprint the fleet Monitor uses, one
   * machine, body-only), a searchable/filterable recipes-with-state list, and a two-column
   * "Recent runs + SSH / connectivity" grid — all from existing reads, no server change.
   *
   * Leak fix (spec-067, a blocker): this screen is re-invoked IN PLACE — bypassing the
   * router — from approvalSplit.onDone, the post-discovery re-render, toggleFamily and the
   * review drawer's done(). The router's runViewCleanup only fires on router dispatch, so
   * once the footprint panel wires a heartbeat ticker, an in-place re-mount would orphan the
   * previous interval permanently. We therefore run any pending currentViewCleanup at entry,
   * BEFORE the new render wires its own timer (spec-067 Implementation option (a)).
   */
  function screenMachineDetail(p) {
    var mid = p.mid;
    runViewCleanup();
    mountAsync(function () {
      return Promise.all([
        api("GET", "/machines/" + mid),
        api("GET", "/recipes?machineId=" + encodeURIComponent(mid)),
        api("GET", "/machines/" + mid + "/discovery"),
        api("GET", "/monitor?machineId=" + encodeURIComponent(mid)),
        api("GET", "/ssh/public-key").catch(function () { return null; })
      ]).then(function (res) {
        var machine = res[0], recipes = res[1], discovery = res[2], mon = res[3], pubkey = res[4];
        return Promise.all(recipes.map(function (r) {
          return api("GET", "/recipes/" + r.id + "/actions").then(function (actions) {
            return { recipe: r, actions: actions };
          });
        })).then(function (groups) {
          return { machine: machine, groups: groups, discovery: discovery, mon: mon, pubkey: pubkey };
        });
      }).then(function (data) {
        var machine = data.machine;

        // Two status chips (identity head + SSH card) kept in sync: the Test button — moved
        // into the SSH card (spec-067 Decision 2) — swaps BOTH from the /test result.
        var chips = { head: chip(machine.status), card: chip(machine.status) };
        function replaceChip(key, state) {
          var fresh = chip(state);
          if (chips[key].parentNode) chips[key].parentNode.replaceChild(fresh, chips[key]);
          chips[key] = fresh;
        }
        var testBtn = h("button", { class: "btn" }, "Test connection");
        testBtn.addEventListener("click", function () {
          testBtn.disabled = true;
          testBtn.textContent = "Testing…";
          api("POST", "/machines/" + mid + "/test").then(function (fresh) {
            replaceChip("head", fresh.status);
            replaceChip("card", fresh.status);
            toast("Connection " + humanize(fresh.status));
          }).catch(function (err) {
            toast(err.message);
          }).then(function () {
            testBtn.disabled = false;
            testBtn.textContent = "Test connection";
          });
        });

        // ---- recipe filter bar (spec-067 Decision 4) --------------------
        // A name/description/action substring query + type chips (distinct recipe types) +
        // the two unconditional source chips (native / docker). Filtering is a client-side
        // re-render of the groups container — no re-fetch (same posture as the Monitor lens).
        var query = "";
        var selType = {};
        var selSource = {};
        var typeValues = uniqSorted(data.groups.map(function (g) { return g.recipe.type; }));
        var groupsBox = h("div");
        var chipsRow = h("div", { class: "filter-chips mt-2" });
        var searchInput = h("input", { class: "mono",
          placeholder: "Filter recipes by name, description, or action…", "aria-label": "Filter recipes" });
        searchInput.addEventListener("input", function () {
          query = searchInput.value.trim().toLowerCase(); renderGroups();
        });

        function filterChip(label, on, title, onClick) {
          return h("button", { type: "button",
            class: "tag tag--filter" + (on ? " tag--on" : ""),
            "aria-pressed": on ? "true" : "false", title: title || label, text: label, onclick: onClick });
        }
        function renderChipsRow() {
          clear(chipsRow);
          if (typeValues.length) {
            chipsRow.appendChild(h("span", { class: "small dim", text: "Type" }));
            typeValues.forEach(function (t) {
              chipsRow.appendChild(filterChip(t, !!selType[t], "Show only " + t + " recipes",
                function () { selType[t] = !selType[t]; renderChipsRow(); renderGroups(); }));
            });
          }
          chipsRow.appendChild(h("span", { class: "small dim", style: "margin-left:12px", text: "Source" }));
          ["native", "docker"].forEach(function (s) {
            chipsRow.appendChild(filterChip(s, !!selSource[s],
              "Show recipes with a " + s + " discovered consumer",
              function () { selSource[s] = !selSource[s]; renderChipsRow(); renderGroups(); }));
          });
        }

        function selectedTypes() { return typeValues.filter(function (t) { return selType[t]; }); }
        function selectedSources() { return ["native", "docker"].filter(function (s) { return selSource[s]; }); }
        function matchesQuery(g) {
          if (!query) return true;
          var hay = (g.recipe.name || "") + " " + (g.recipe.description || "");
          (g.actions || []).forEach(function (a) { hay += " " + (a.name || ""); });
          return hay.toLowerCase().indexOf(query) >= 0;
        }
        function matchesType(g) {
          var s = selectedTypes();
          return !s.length || s.indexOf(g.recipe.type) >= 0;
        }
        function matchesSource(g) { return recipeMatchesSources(g.recipe, selectedSources()); }
        function groupNode(g) {
          return h("div", { class: "section" },
            h("div", { class: "row-between" },
              h("h2", { text: g.recipe.name }),
              h("span", { class: "tag", text: g.recipe.type })),
            g.recipe.description ? h("p", { class: "small dim mt-2", text: g.recipe.description }) : null,
            g.recipe.sourceBlueprintId ? h("p", { class: "xs faint mt-2",
              text: "from blueprint " + g.recipe.sourceBlueprintId + " v" + g.recipe.sourceBlueprintVersion }) : null,
            actionsList(machine, g.recipe, g.actions));
        }
        function renderGroups() {
          clear(groupsBox);
          if (!data.groups.length) {
            groupsBox.appendChild(empty("No recipes yet. Run discovery to propose recipes for the services on this host."));
            return;
          }
          var visible = data.groups.filter(function (g) {
            return matchesQuery(g) && matchesType(g) && matchesSource(g);
          });
          if (!visible.length) {
            groupsBox.appendChild(empty("No recipes match the filter."));
            return;
          }
          // With a source chip on, split the un-pre-filled majority under an "other / none"
          // heading so turning on native/docker re-labels rather than hides them.
          if (selectedSources().length) {
            var sourced = visible.filter(function (g) { return !recipeIsOtherNone(g.recipe); });
            var other = visible.filter(function (g) { return recipeIsOtherNone(g.recipe); });
            sourced.forEach(function (g) { groupsBox.appendChild(groupNode(g)); });
            if (other.length) {
              groupsBox.appendChild(h("h3", { class: "mt-4 dim",
                text: "Other / none — no discovered source" }));
              other.forEach(function (g) { groupsBox.appendChild(groupNode(g)); });
            }
          } else {
            visible.forEach(function (g) { groupsBox.appendChild(groupNode(g)); });
          }
        }
        renderChipsRow();
        renderGroups();

        var recipesSection = h("div", { class: "section" },
          h("h2", { text: "Recipes & actions" }),
          data.groups.length ? h("div", { class: "mt-2" }, searchInput) : null,
          data.groups.length ? chipsRow : null,
          groupsBox);

        // ---- footprint (spec-067 Decision 1): the SAME makeFootprint the fleet Monitor
        // uses, scoped to this one machine (?machineId=). Owner-scoped ⇒ absent machines[0]
        // omits the section entirely.
        var monMachine = (data.mon && data.mon.machines || [])[0] || null;

        return h("div", null,
          crumbs(link("#/machines", "Machines"),
            h("span", { text: machine.name })),
          pageHead(machine.name, machine.loginUser + "@" + machine.host + ":" + machine.port,
            [chips.head, copyHostButton(machine)]),
          h("div", { class: "row" }, (machine.tags || []).map(function (t) {
            return h("span", { class: "tag", text: t });
          })),
          monMachine ? footprintPanel(monMachine) : null,
          discoverySection(p, mid, (data.discovery && data.discovery.families) || []),
          recipesSection,
          h("div", { class: "detail-split" },
            recentRunsSection(mid),
            sshCard(machine, data.pubkey, chips.card, testBtn)));
      });
    });
  }

  /**
   * spec-067 Decision 1: the per-machine footprint panel. Builds one makeFootprint section
   * (body-only — the pageHead already carries the identity + status chip) and drives it
   * ONE-SHOT on mount, plus a "Run now" control that re-polls. There is NO standing poll
   * interval (matching the fleet Monitor's default Single cadence); the only timer is a 1 s
   * heartbeat that refreshes the relative "updated Ns ago" read-out. That ticker is
   * registered with the router's runViewCleanup so navigating away — and the in-place
   * re-mounts (which call runViewCleanup at screenMachineDetail entry) — never orphan it.
   */
  function footprintPanel(monMachine) {
    var models = {};
    models[monMachine.machineId] = buildConsumers(monMachine);
    var fp = makeFootprint({
      models: models,
      // One machine: no app-name filter, so every named consumer is always shown and the
      // card name renders as plain text (onToggleApp null). Lens/bucket stay at defaults —
      // this page shows strictly less than the fleet Monitor for the machine (no DB lens,
      // no bucket reveal); the full breakdown stays on #/monitor.
      selectedNamed: function (m) { return models[m.machineId].filter(function (c) { return !c.bucket; }); },
      noAppsOn: function () { return false; },
      onToggleApp: null,
      showHead: false
    });
    var section = fp.buildSection(monMachine);

    var lastUpdated = null, heartbeatTimer = null, inFlight = false;
    var updatedLabel = h("span", { class: "small dim", text: "not yet updated" });
    var runNowBtn = h("button", { class: "btn btn--sm btn--primary" }, "Run now");
    function tick() {
      updatedLabel.textContent = lastUpdated
        ? "updated " + Math.round((Date.now() - lastUpdated) / 1000) + "s ago"
        : "not yet updated";
    }
    function cycle() {
      if (inFlight) return;
      inFlight = true; runNowBtn.disabled = true;
      section.refresh()
        .then(function () { lastUpdated = Date.now(); tick(); })
        .catch(function () { /* per-section errors are shown in-section */ })
        .then(function () { inFlight = false; runNowBtn.disabled = false; });
    }
    runNowBtn.addEventListener("click", cycle);
    heartbeatTimer = setInterval(tick, 1000);
    currentViewCleanup = function () {
      if (heartbeatTimer) { clearInterval(heartbeatTimer); heartbeatTimer = null; }
    };
    cycle();   // one-shot on mount

    return h("div", { class: "section" },
      h("div", { class: "row-between" },
        h("h2", { text: "Footprint" }),
        h("div", { class: "row" }, updatedLabel, runNowBtn)),
      h("p", { class: "small dim",
        text: "RAM / CPU / disk this host's consumers occupy, computed in your browser from the "
          + "APPROVED monitor actions (spec-029) — one-shot on load, Run now re-polls. Approve the "
          + "host-vital monitor actions to fill the axes." }),
      section.node);
  }

  /**
   * spec-067 Decision 2: the SSH / connectivity card. A .card with a kv list from the
   * already-fetched MachineView (status chip + loginUser@host:port) plus the app-key
   * identity from GET /ssh/public-key (key type parsed client-side + fingerprint). The
   * Test-connection button lives in the footer. Handshake latency / last-probed rows need
   * data that does not exist yet — spec-068 fills them into this same card.
   */
  function sshCard(machine, pubkey, statusChip, testBtn) {
    var keyType = null;
    if (pubkey && pubkey.publicKey) {
      keyType = pubkey.publicKey.trim().split(/\s+/)[0] || null;   // e.g. "ssh-ed25519"
    }
    return h("div", { class: "card" },
      h("h2", { text: "SSH / connectivity" }),
      h("dl", { class: "kv mt-3" },
        h("dt", { text: "Status" }), h("dd", null, statusChip),
        h("dt", { text: "Target" }),
        h("dd", { class: "mono", text: machine.loginUser + "@" + machine.host + ":" + machine.port }),
        pubkey ? h("dt", { text: "App key" }) : null,
        pubkey ? h("dd", { class: "mono",
          text: (keyType ? keyType + " · " : "") + (pubkey.fingerprint || "") }) : null),
      h("div", { class: "action-card-foot mt-3" }, copyHostButton(machine), testBtn));
  }

  /**
   * spec-067 Decision 5: the this-browser Recent-runs slice. Runs.all() carries a
   * machineId per entry (spec-005 has no server-side run list), so we filter to this
   * machine and render the #/runs row idiom with the honest "launched from this browser"
   * caveat. Server-backed history that survives the browser is spec-069, which swaps in
   * behind this same section when/if it lands.
   */
  function recentRunsSection(mid) {
    var mine = Runs.all().filter(function (r) { return r.machineId === mid; }).slice(0, 10);
    var body = mine.length
      ? h("ul", { class: "list" }, mine.map(function (r) {
          return h("li", null, h("div", { class: "row-between" },
            h("div", { class: "grow" },
              link("#/runs/" + r.id, r.actionName || r.id, null),
              h("p", { class: "small dim mt-2 mono", text: (r.host || "") + " · " + fmtTime(r.createdAt) })),
            link("#/runs/" + r.id, "Open", "btn btn--sm")));
        }))
      : empty("No runs launched from this browser for this machine yet.");
    return h("div", { class: "card" },
      h("h2", { text: "Recent runs" }),
      h("p", { class: "small dim",
        text: "Launched from this browser (spec-005 has no server-side run list; spec-069 adds durable history)." }),
      body);
  }

  /**
   * spec-044: recipe actions render as a responsive grid of cards (2–3 across on
   * desktop, 1 column on phones) rather than full-width rows. Each card carries
   * the identity (name, state chip, sudo badge, description) and the split-approve
   * control; clicking through opens the review drawer in place — no navigation.
   */
  function actionsList(machine, recipe, actions) {
    if (!actions.length) return empty("No actions in this recipe.");
    return h("div", { class: "action-cards mt-3" }, actions.map(function (a) {
      return actionCard(machine, recipe, a);
    }));
  }

  function actionCard(machine, recipe, a) {
    return h("div", { class: "action-card" },
      h("div", { class: "row-between" },
        h("strong", { class: "grow", text: a.name }),
        h("div", { class: "row" }, chip(a.approvalState), a.sudo ? sudoBadge() : null)),
      a.description ? h("p", { class: "small dim mt-2", text: a.description }) : null,
      h("div", { class: "action-card-foot mt-3" }, approvalSplit(machine, recipe, a)));
  }

  /**
   * The per-action split-approve control. The PRIMARY button is the one valid
   * primary transition for the current state (DRAFT→Submit, PENDING→Approve,
   * APPROVED→Run); the caret menu lists the other valid verbs plus "See more…"
   * (the review drawer). Only transitions the backend actually supports appear.
   *
   * Review-safety guard: a one-click Approve is offered only for a routine
   * re-approval of an UNCHANGED action (previously approved, `changedSinceApproval`
   * false). A first-time approval (no `approvedAt`) or any `changedSinceApproval`
   * action makes the primary "Review & approve", which OPENS THE DRAWER first so a
   * human sees the exact command before it is armed — it does not approve directly.
   */
  function approvalSplit(machine, recipe, action) {
    var mid = machine.id, rid = recipe.id;
    var onDone = function () { screenMachineDetail({ mid: mid }); };
    var openReview = function () { openActionDrawer(machine, recipe, action); };
    var seeMore = { label: "See more…", onClick: openReview };
    var s = action.approvalState;

    if (s === "DRAFT") {
      return splitButton({
        primary: h("button", { class: "btn btn--primary",
          onclick: function () { actVerb(action, "submit", onDone); } }, "Submit"),
        items: [seeMore]
      });
    }
    if (s === "PENDING_APPROVAL") {
      var mustReview = !action.approvedAt || action.changedSinceApproval;
      var primary = mustReview
        ? h("button", { class: "btn btn--primary", onclick: openReview }, "Review & approve")
        : h("button", { class: "btn btn--ok",
            onclick: function () { actVerb(action, "approve", onDone); } }, "Approve");
      return splitButton({ primary: primary, items: [seeMore] });
    }
    if (s === "APPROVED") {
      var runHash = "#/machines/" + mid + "/recipes/" + rid + "/actions/" + action.id + "/run";
      return splitButton({
        primary: h("a", { href: runHash, class: "btn btn--primary" }, "Run"),
        items: [
          { label: "Revoke", cls: "danger", onClick: function () { actVerb(action, "revoke", onDone); } },
          seeMore
        ]
      });
    }
    // REVOKED (terminal — no re-approval): only a review link.
    return splitButton({ primary: h("button", { class: "btn", onclick: openReview }, "Review"), items: [] });
  }

  /** POST an approval transition, toast, then run the caller's refresh. */
  function actVerb(action, verb, onDone) {
    api("POST", "/actions/" + action.id + "/" + verb).then(function () {
      toast("Action " + verb + "d");
      if (onDone) onDone();
    }).catch(function (err) { toast(err.message); });
  }

  /**
   * The per-machine Discovery panel (spec-035): a family toggle list plus, for any
   * guarded family, its one-line capability note (docker: root-equivalent). Enabling a
   * family only lets its discoverers probe and propose — every proposed action still
   * needs approval to run, so the toggles sit above the recipe/proposal groups below.
   */
  function discoverySection(p, mid, families) {
    var chips = families.map(function (f) {
      return h("button", {
        type: "button",
        class: "tag tag--filter" + (f.enabled ? " tag--on" : ""),
        "aria-pressed": f.enabled ? "true" : "false",
        text: f.label,
        onclick: function () { toggleFamily(p, mid, f); }
      });
    });
    var notes = families.filter(function (f) { return f.note; }).map(function (f) {
      return h("p", { class: "small faint mt-2", text: f.label + " — " + f.note });
    });
    var discoverBtn = h("button", { class: "btn btn--sm" }, "Discover recipes");
    discoverBtn.addEventListener("click", function () {
      discoverBtn.disabled = true;
      discoverBtn.textContent = "Discovering…";
      api("POST", "/machines/" + mid + "/discover").then(function (result) {
        // spec-070: a transport failure can skip one or more discoverer families and
        // still return the rest — say so rather than claim a clean run.
        if (result && result.partial) {
          var fams = (result.failedFamilies || []).join(", ");
          if (result.connectionLost) {
            // The shared session dropped (or never opened) — the run is truncated, not
            // a set of individually-empty families (070 follow-up).
            toast(fams
              ? "Discovery incomplete — lost the connection to the machine mid-probe (unprobed: " + fams + ")"
              : "Discovery incomplete — could not reach the machine");
          } else {
            toast(fams
              ? "Discovery partial — some families could not be probed: " + fams
              : "Discovery partial — some families could not be probed");
          }
        } else {
          toast("Discovery complete");
        }
        screenMachineDetail(p);
      }).catch(function (err) { toast(err.message); discoverBtn.disabled = false; discoverBtn.textContent = "Discover recipes"; });
    });
    return h("div", { class: "section" },
      h("div", { class: "row-between" },
        h("h2", { text: "Discovery" }),
        discoverBtn),
      h("p", { class: "small dim",
        text: "Choose which discoverer families may probe this machine, then run discovery. "
          + "Enabling a family only lets it propose recipes; every proposed action still needs "
          + "approval to run." }),
      chips.length ? h("div", { class: "filter-chips mt-2" }, chips) : empty("No discoverer families."),
      notes);
  }

  /** PUT the flipped family enablement, then re-render the detail to reflect the new state. */
  function toggleFamily(p, mid, f) {
    var next = !f.enabled;
    api("PUT", "/machines/" + mid + "/discovery/" + f.key.toLowerCase(), { enabled: next })
      .then(function () {
        toast(f.label + " discovery " + (next ? "enabled" : "disabled"));
        screenMachineDetail(p);
      })
      .catch(function (err) { toast(err.message); });
  }

  // ----- helper: load one action with its machine + recipe context ----------

  function loadActionContext(p) {
    return Promise.all([
      api("GET", "/machines/" + p.mid),
      api("GET", "/recipes?machineId=" + encodeURIComponent(p.mid)),
      api("GET", "/recipes/" + p.rid + "/actions")
    ]).then(function (res) {
      var recipe = res[1].filter(function (r) { return r.id === p.rid; })[0] || null;
      var action = res[2].filter(function (a) { return a.id === p.aid; })[0];
      if (!action) throw new Error("action not found");
      return { machine: res[0], recipe: recipe, action: action };
    });
  }

  // ----- Action review (drawer body + standalone page) ----------------------

  /**
   * spec-044: the review body — the changed/awaiting/revoked banners, the exact
   * command a human signs off on, the parameter rules and the provenance card.
   * Shared verbatim by the review DRAWER (openActionDrawer) and the standalone
   * approval PAGE (screenApproval), so both show identical facts. Returns an
   * array of nodes (no crumbs/page-head — those differ between the two hosts).
   */
  function renderActionReview(action, ctx) {
    var recipe = ctx.recipe;
    var params = (action.paramDefs || []);
    var paramSection = params.length
      ? h("div", { class: "card" },
          h("h2", { text: "Parameters" }),
          h("p", { class: "small dim mt-2", text: "Typed, validated inputs bound into the PARAM slots at run time." }),
          h("div", { class: "param-rule mt-3" }, params.reduce(function (acc, def) {
            acc.push(h("span", { class: "name", text: def.name }));
            acc.push(h("span", { class: "rule", text: paramRuleText(def) }));
            return acc;
          }, [])))
      : null;

    // "changed since approval" guard, backed by the spec-004 content hash.
    // The API exposes it as `changedSinceApproval` when the approved snapshot
    // no longer matches the current content (edits otherwise reset to DRAFT).
    var changedBanner = action.changedSinceApproval
      ? h("div", { class: "banner banner--bad", role: "alert" },
          h("div", { class: "banner-body" },
            h("strong", { text: "Changed since approval — re-review. " }),
            "This action's command or parameters differ from the approved snapshot. Approve again to allow runs."))
      : null;

    var pending = action.approvalState === "PENDING_APPROVAL"
      ? h("div", { class: "banner banner--warn", role: "note" },
          h("div", { class: "banner-body" },
            h("strong", { text: "Awaiting approval. " }),
            "MCP can see this action but cannot run it until you approve here."))
      : null;

    var revoked = action.approvalState === "REVOKED"
      ? h("div", { class: "banner banner--warn", role: "note" },
          h("div", { class: "banner-body" },
            h("strong", { text: "Revoked. " }),
            "This action can no longer run and cannot be re-approved directly. "
              + "Re-enabling it means editing the action, which returns it to draft to be submitted and approved afresh."))
      : null;

    return [
      changedBanner,
      pending,
      revoked,
      action.description ? h("p", { class: "dim", text: action.description }) : null,
      h("div", { class: "card mt-4" },
        h("h2", { text: "Command" }),
        h("p", { class: "small dim mt-2", text: "Exactly what will run. LITERAL tokens are plain; PARAM slots are underlined and bound from validated input." }),
        h("div", { class: "mt-3" }, renderCommand(action, null)),
        action.sudo
          ? h("p", { class: "small mt-3" }, sudoBadge(),
              h("span", { class: "dim", text: " runs with passwordless sudo on the target (spec risk S5)." }))
          : null),
      paramSection,
      h("div", { class: "card" },
        h("h2", { text: "Provenance" }),
        h("dl", { class: "kv mt-3" },
          h("dt", { text: "Approval state" }), h("dd", null, chip(action.approvalState)),
          h("dt", { text: "Approved by" }), h("dd", { text: action.approvedByUserId || "—" }),
          h("dt", { text: "Approved at" }), h("dd", { text: fmtTime(action.approvedAt) }),
          recipe && recipe.sourceBlueprintId ? h("dt", { text: "Blueprint source" }) : null,
          recipe && recipe.sourceBlueprintId
            ? h("dd", { class: "mono", text: recipe.sourceBlueprintId + " v" + recipe.sourceBlueprintVersion })
            : null))
    ];
  }

  /**
   * The full transition buttons for the review surfaces (drawer + standalone
   * page). Unlike the card's split button there is no review-safety guard here:
   * both surfaces already show the exact command, so direct Approve is honest.
   * REVOKED is terminal — the backend permits no REVOKED→APPROVED, so no control
   * is offered rather than a button that always 409s (the revoked banner explains).
   */
  function transitionButtons(mid, rid, action, onDone) {
    var controls = [];
    var s = action.approvalState;
    if (s === "DRAFT") {
      controls.push(h("button", { class: "btn btn--primary",
        onclick: function () { actVerb(action, "submit", onDone); } }, "Submit for approval"));
    }
    if (s === "PENDING_APPROVAL") {
      controls.push(h("button", { class: "btn btn--ok",
        onclick: function () { actVerb(action, "approve", onDone); } }, "Approve"));
    }
    if (s === "APPROVED") {
      controls.push(link("#/machines/" + mid + "/recipes/" + rid + "/actions/" + action.id + "/run", "Run action", "btn btn--primary"));
      controls.push(h("button", { class: "btn btn--danger",
        onclick: function () { actVerb(action, "revoke", onDone); } }, "Revoke"));
    }
    return controls;
  }

  /**
   * spec-044: the review DRAWER — mirrors openConsumerDrawer (a right-side sheet
   * ≥720px, a bottom sheet ≤480px per spec-043). Opens in place from a machine
   * page; a transition re-renders the machine detail (cards reflect the new
   * state) and closing restores focus to the triggering control.
   */
  function openActionDrawer(machine, recipe, action) {
    var mid = machine.id, rid = recipe.id;
    var ctx = { machine: machine, recipe: recipe, action: action };
    var restoreFocus = document.activeElement;

    function closeActionDrawer() {
      document.removeEventListener("keydown", onKey);
      closeDrawer();
      if (restoreFocus && restoreFocus.focus) restoreFocus.focus();
    }
    function onKey(e) { if (e.key === "Escape") closeActionDrawer(); }
    function done() {
      document.removeEventListener("keydown", onKey);
      closeDrawer();
      screenMachineDetail({ mid: mid });
    }

    var controls = transitionButtons(mid, rid, action, done);
    var drawer = h("div", { class: "drawer", role: "dialog", "aria-modal": "true", "aria-label": action.name },
      h("div", { class: "row-between" },
        h("h2", { text: action.name }),
        h("button", { class: "btn btn--sm", onclick: closeActionDrawer }, "Close")),
      h("div", { class: "row mt-2" }, chip(action.approvalState), action.sudo ? sudoBadge() : null),
      h("p", { class: "small dim mt-2", text: machine.loginUser + "@" + machine.host + ":" + machine.port }),
      renderActionReview(action, ctx),
      controls.length ? h("div", { class: "row mt-4" }, controls) : null);
    var backdrop = h("div", { class: "drawer-backdrop", onclick: function (e) {
      if (e.target === backdrop) closeActionDrawer();
    } }, drawer);
    var root = byId("modal-root");
    clear(root);
    root.appendChild(backdrop);
    document.addEventListener("keydown", onKey);
  }

  // ----- Approval screen (standalone URL fallback / deep link) ---------------

  function screenApproval(p) {
    mountAsync(function () {
      return loadActionContext(p).then(function (ctx) {
        var action = ctx.action, machine = ctx.machine, recipe = ctx.recipe;
        var controls = transitionButtons(p.mid, p.rid, action, function () { screenApproval(p); });
        return h("div", null,
          crumbs(link("#/machines", "Machines"),
            link("#/machines/" + p.mid, machine.name),
            h("span", { text: action.name })),
          pageHead(action.name, recipe ? recipe.name : null, [chip(action.approvalState),
            action.sudo ? sudoBadge() : null]),
          renderActionReview(action, ctx),
          h("div", { class: "row mt-4" }, controls));
      });
    });
  }

  // ----- Run parameter entry ------------------------------------------------

  function screenRunForm(p, query) {
    query = query || {};
    // spec-026: an ops action launched from an app card arrives with ?app-name=<app>.
    // That value pre-fills and LOCKS the reserved `app-name` param; the remaining params
    // are entered normally. The gate is still the server's — this only seeds the form.
    var prefillApp = query["app-name"] || null;
    mountAsync(function () {
      return loadActionContext(p).then(function (ctx) {
        var action = ctx.action, machine = ctx.machine;
        if (action.approvalState !== "APPROVED") {
          return h("div", null,
            crumbs(link("#/machines", "Machines"), link("#/machines/" + p.mid, machine.name),
              h("span", { text: action.name })),
            pageHead("Run " + action.name),
            h("div", { class: "banner banner--warn" }, h("div", { class: "banner-body" },
              h("strong", { text: "Not approved. " }),
              "Only APPROVED actions can run. Approve it first.")),
            link("#/machines/" + p.mid + "/recipes/" + p.rid + "/actions/" + p.aid, "Back to review", "btn"));
        }

        var params = action.paramDefs || [];
        var values = {};
        // Seed the locked app-name before the first preview so it renders filled.
        if (prefillApp) {
          params.forEach(function (def) { if (def.name === "app-name") values[def.name] = prefillApp; });
        }
        var preview = renderCommand(action, values);
        var runBtn = h("button", { class: "btn btn--primary", disabled: true }, "Run");

        function refresh() {
          var newPreview = renderCommand(action, values);
          preview.parentNode.replaceChild(newPreview, preview);
          preview = newPreview;
          var allValid = params.every(function (def) { return validateParam(def, values[def.name]); });
          runBtn.disabled = !allValid;
        }

        var fields = params.map(function (def) {
          var control, errEl = h("div", { class: "field-error hidden" });
          // A pre-filled app-name is locked: shown read-only, its value already seeded
          // into `values`, so it validates without user input (spec-026).
          if (prefillApp && def.name === "app-name") {
            control = h("input", { type: "text", class: "mono", value: prefillApp, disabled: true, readonly: true });
            return h("div", { class: "field" },
              h("label", { text: def.name }),
              control,
              h("div", { class: "hint", text: "locked to app " + prefillApp }));
          }
          function onInput(v) {
            values[def.name] = v;
            var ok = validateParam(def, v);
            if (v && !ok) {
              errEl.textContent = "Does not satisfy " + paramRuleText(def);
              errEl.className = "field-error";
              control.classList.add("invalid");
            } else {
              errEl.className = "field-error hidden";
              control.classList.remove("invalid");
            }
            refresh();
          }
          if (def.kind === "ALLOWED_SET") {
            control = h("select", { class: "mono", onchange: function (e) { onInput(e.target.value); } },
              h("option", { value: "", text: "— select —" }),
              (def.allowedValues || []).map(function (v) { return h("option", { value: v, text: v }); }));
          } else if (def.kind === "INT_RANGE") {
            control = h("input", { type: "number", class: "mono",
              min: def.intMin != null ? def.intMin : null,
              max: def.intMax != null ? def.intMax : null,
              oninput: function (e) { onInput(e.target.value); } });
          } else {
            control = h("input", { type: "text", class: "mono", placeholder: def.pattern || "",
              oninput: function (e) { onInput(e.target.value); } });
          }
          return h("div", { class: "field" },
            h("label", { text: def.name }),
            control,
            h("div", { class: "hint", text: paramRuleText(def) }),
            errEl);
        });

        // Initial validity: a fully pre-filled op (e.g. restart, whose only param is the
        // locked app-name) is runnable immediately; otherwise disabled until every param
        // validates. Computed from `values`, so no DOM/refresh dependency (spec-026).
        runBtn.disabled = !params.every(function (def) { return validateParam(def, values[def.name]); });

        runBtn.addEventListener("click", function () {
          runBtn.disabled = true;
          runBtn.textContent = "Starting…";
          var suppliedParams = {};
          params.forEach(function (def) { suppliedParams[def.name] = values[def.name]; });
          api("POST", "/runs", {
            machineId: p.mid, actionId: action.id, params: suppliedParams
          }).then(function (run) {
            Runs.remember({
              id: run.id, machineId: p.mid, actionId: action.id, actionName: action.name,
              host: machine.host, command: commandText(action, values), params: suppliedParams,
              createdAt: run.createdAt || new Date().toISOString()
            });
            location.hash = "#/runs/" + run.id;
          }).catch(function (err) {
            toast(err.message); runBtn.disabled = false; runBtn.textContent = "Run";
          });
        });

        // Set the initial button state once: refresh() only runs on param input, so a
        // no-param action (params.every(...) === true, vacuously) would otherwise stay
        // disabled forever. A parameterised action still starts disabled (empty values
        // fail validateParam) until the user fills it.
        runBtn.disabled = !params.every(function (def) { return validateParam(def, values[def.name]); });

        return h("div", null,
          crumbs(link("#/machines", "Machines"), link("#/machines/" + p.mid, machine.name),
            link("#/machines/" + p.mid + "/recipes/" + p.rid + "/actions/" + p.aid, action.name),
            h("span", { text: "Run" })),
          pageHead("Run " + action.name, "Enter parameters. Each is validated against its rule before the run is allowed.",
            action.sudo ? h("span", { class: "badge-sudo", text: "sudo" }) : null),
          h("div", { class: "card" },
            h("h2", { text: "Resolved command" }),
            h("p", { class: "small dim mt-2", text: "Updates live as you fill parameters." }),
            h("div", { class: "mt-3" }, preview)),
          params.length
            ? h("div", { class: "card" }, h("h2", { text: "Parameters" }), fields)
            : h("div", { class: "card" }, h("p", { class: "small dim", text: "This action takes no parameters." })),
          h("div", { class: "row mt-4" }, runBtn,
            h("span", { class: "small dim", text: params.length ? "Disabled until every parameter is valid." : "No parameters — ready to run." })));
      });
    });
  }

  /** Plain-text resolved command (for caching / display), sudo prefix included. */
  function commandText(action, values) {
    var parts = [];
    if (action.sudo) parts.push("sudo", "-n");
    (action.argTokens || []).slice().sort(function (a, b) { return a.position - b.position; })
      .forEach(function (tok) {
        if (tok.kind === "PARAM") parts.push(values && values[tok.value] != null && values[tok.value] !== "" ? values[tok.value] : "{" + tok.value + "}");
        else parts.push(tok.value);
      });
    return parts.join(" ");
  }

  // ----- Run view (resolved command + params-used + streaming terminal) -----

  function screenRunView(p) {
    var cached = Runs.get(p.id);
    mount(loading());
    api("GET", "/runs/" + p.id).then(function (run) {
      var term = h("div", { class: "terminal", "aria-live": "polite" });
      var statusChip = chip(run.status);
      var paused = false;
      var pauseBtn = h("button", { class: "btn btn--sm" }, "Pause auto-scroll");
      pauseBtn.addEventListener("click", function () {
        paused = !paused;
        pauseBtn.textContent = paused ? "Resume auto-scroll" : "Pause auto-scroll";
      });
      var reduceMotion = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
      var exitValue = h("dd", { class: "mono", text: run.exitCode != null ? String(run.exitCode) : "—" });

      // spec-026: a Stop control cancels a live run (the follow-mode -f log case). Shown
      // only while the run is non-terminal; POSTs /runs/{id}/cancel, which closes the SSH
      // channel and marks the run STOPPED. Cancelling an already-terminal run is a no-op.
      var TERMINAL = { DONE: 1, FAILED: 1, INTERRUPTED: 1, STOPPED: 1 };
      var stopBtn = TERMINAL[run.status] ? null : h("button", { class: "btn btn--sm btn--danger" }, "Stop");
      if (stopBtn) {
        stopBtn.addEventListener("click", function () {
          stopBtn.disabled = true;
          stopBtn.textContent = "Stopping…";
          api("POST", "/runs/" + encodeURIComponent(p.id) + "/cancel").then(function (fresh) {
            toast("Run stopped");
            var freshChip = chip(fresh.status);
            statusChip.parentNode.replaceChild(freshChip, statusChip);
            statusChip = freshChip;
          }).catch(function (err) {
            toast(err.message);
            stopBtn.disabled = false;
            stopBtn.textContent = "Stop";
          });
        });
      }

      /**
       * spec-012: Render live run output. Only the {@code stdout}/{@code stderr}
       * SSE streams are terminal lines; the terminal {@code exit} event carries the
       * exit-code string and updates the header field instead of being echoed as a
       * spurious output line.
       */
      function appendOutput(stream, data) {
        if (stream === "exit") { exitValue.textContent = data; return; }
        if (stream !== "stdout" && stream !== "stderr") return;
        var span = h("span", { class: stream === "stderr" ? "stderr" : "" }, data + "\n");
        term.appendChild(span);
        if (!paused) term.scrollTop = term.scrollHeight; // instant jump, no animation
      }

      var paramsUsed = (cached && cached.params) || null;
      var commandView = cached && cached.command
        ? h("code", { class: "command", text: cached.command })
        : h("p", { class: "small dim", text: "The resolved command is not available in this session (this run was started elsewhere)." });

      var paramsPanel = paramsUsed && Object.keys(paramsUsed).length
        ? h("dl", { class: "kv mt-3" }, Object.keys(paramsUsed).reduce(function (acc, k) {
            acc.push(h("dt", { class: "mono", text: k }));
            acc.push(h("dd", { class: "mono", text: paramsUsed[k] }));
            return acc;
          }, []))
        : h("p", { class: "small dim", text: paramsUsed ? "No parameters." : "Not recorded in this session." });

        var node = h("div", null,
          crumbs(link("#/runs", "Runs"), h("span", { class: "mono", text: run.id })),
          pageHead("Run", cached ? cached.actionName + " · " + cached.host : run.actionId, statusChip),
          h("div", { class: "card" },
            h("h2", { text: "Command that ran" }),
            h("div", { class: "mt-3" }, commandView)),
          h("div", { class: "card" },
            h("h2", { text: "Parameters used" }),
            paramsPanel),
          h("div", { class: "card" },
            h("div", { class: "row-between" },
              h("h2", { text: "Output" }),
              h("div", { class: "row" },
                h("dl", { class: "kv" },
                  h("dt", { text: "exit" }), exitValue),
                stopBtn,
                pauseBtn)),
            h("p", { class: "small dim mt-2 mono" },
              "queued " + fmtTime(run.createdAt) + " · started " + fmtTime(run.startedAt) + " · finished " + fmtTime(run.finishedAt)),
            h("div", { class: "mt-3" }, term)));

      mount(node);

      // Stream output. For a finished run this replays the buffered log and closes;
      // for a live run it tails until the server completes, then we refresh status.
      streamRunOutput(p.id, {
        onChunk: appendOutput,
        onDone: function () {
          api("GET", "/runs/" + p.id).then(function (fresh) {
            var freshChip = chip(fresh.status);
            statusChip.parentNode.replaceChild(freshChip, statusChip);
            statusChip = freshChip;
          }).catch(function () { /* leave last-known status */ });
        }
      });
    }).catch(function (err) {
      if (err && err.message === "unauthorized") return;
      mount(errorCard(err));
    });
  }

  // ----- Runs index ---------------------------------------------------------

  function screenRuns() {
    var recent = Runs.all();
    var lookup = h("input", { class: "mono", placeholder: "run id" });
    var body = recent.length
      ? h("ul", { class: "list" }, recent.map(function (r) {
          return h("li", null, h("div", { class: "row-between" },
            h("div", { class: "grow" },
              link("#/runs/" + r.id, r.actionName || r.id, null),
              h("p", { class: "small dim mt-2 mono", text: (r.host || "") + " · " + fmtTime(r.createdAt) })),
            link("#/runs/" + r.id, "Open", "btn btn--sm")));
        }))
      : empty("No runs launched from this browser yet. Approve an action and run it, or look one up by id.");

    mount(h("div", null,
      pageHead("Runs", "Execution log. The engine (spec-005) has no list endpoint, so this shows runs launched from this browser plus a by-id lookup."),
      h("div", { class: "card" },
        h("h2", { text: "Look up a run" }),
        h("div", { class: "row mt-3" }, h("div", { class: "grow" }, lookup),
          h("button", { class: "btn btn--primary", onclick: function () {
            if (lookup.value.trim()) location.hash = "#/runs/" + lookup.value.trim();
          } }, "Open"))),
      h("div", { class: "section" }, h("h2", { text: "Recent (this browser)" }), body)));
  }

  // ----- Blueprints ---------------------------------------------------------

  function screenBlueprints() {
    mountAsync(function () {
      return api("GET", "/blueprints").then(function (blueprints) {
        var nameInput = h("input", { placeholder: "e.g. nginx-restart" });
        var descInput = h("input", { placeholder: "what this blueprint does" });
        function create() {
          if (!nameInput.value.trim()) { toast("name is required"); return; }
          api("POST", "/blueprints", { name: nameInput.value.trim(), description: descInput.value.trim() || null })
            .then(function () { toast("Blueprint created"); screenBlueprints(); })
            .catch(function (err) { toast(err.message); });
        }
        var list = blueprints.length
          ? h("ul", { class: "list" }, blueprints.map(function (b) {
              return h("li", null, h("div", { class: "row-between" },
                h("div", { class: "grow" },
                  link("#/blueprints/" + b.id, b.name, null),
                  b.description ? h("p", { class: "small dim mt-2", text: b.description }) : null),
                h("div", { class: "row" }, h("span", { class: "tag", text: b.type }),
                  h("span", { class: "tag", text: "v" + b.version }))));
            }))
          : empty("No blueprints yet. Author one, then instantiate it onto machines or a tag.");
        return h("div", null,
          pageHead("Blueprints", "Reusable recipe templates. Instantiating creates PENDING_APPROVAL actions per machine — it never approves."),
          h("div", { class: "card" }, h("h2", { text: "New blueprint" }),
            h("div", { class: "field mt-3" }, h("label", { text: "Name" }), nameInput),
            h("div", { class: "field" }, h("label", { text: "Description" }), descInput),
            h("button", { class: "btn btn--primary", onclick: create }, "Create blueprint")),
          h("div", { class: "section" }, h("h2", { text: "All blueprints" }), list));
      });
    });
  }

  function screenBlueprintDetail(p) {
    mountAsync(function () {
      return Promise.all([
        api("GET", "/blueprints/" + p.bid),
        api("GET", "/blueprints/" + p.bid + "/actions"),
        api("GET", "/machines")
      ]).then(function (res) {
        var bp = res[0], actions = res[1], machines = res[2];
        var result = h("div", { class: "section" });

        var tagInput = h("input", { class: "mono", placeholder: "tag (optional)" });
        var checks = machines.map(function (m) {
          return { m: m, cb: h("input", { type: "checkbox", value: m.id }) };
        });
        function instantiate() {
          var ids = checks.filter(function (c) { return c.cb.checked; }).map(function (c) { return c.m.id; });
          var body;
          if (tagInput.value.trim()) body = { tag: tagInput.value.trim() };
          else if (ids.length) body = { machineIds: ids };
          else { toast("Pick machines or a tag"); return; }
          api("POST", "/blueprints/" + p.bid + "/instantiate", body).then(function (recipes) {
            clear(result);
            result.appendChild(h("h2", { text: "Instantiated" }));
            if (!recipes.length) { result.appendChild(empty("No machines matched.")); return; }
            result.appendChild(h("ul", { class: "list mt-3" }, recipes.map(function (r) {
              return h("li", null,
                h("div", { class: "row-between" },
                  link("#/machines/" + r.machineId, r.name, "mono"),
                  h("span", { class: "tag", text: r.actions.length + " actions" })),
                h("div", { class: "row mt-2" }, r.actions.map(function (a) {
                  return h("span", { class: "row" }, h("span", { class: "small mono", text: a.name + " " }), chip(a.approvalState));
                })));
            })));
            toast("Instantiated — approve the pending actions per machine");
          }).catch(function (err) { toast(err.message); });
        }

        return h("div", null,
          crumbs(link("#/blueprints", "Blueprints"), h("span", { text: bp.name })),
          pageHead(bp.name, bp.description, [h("span", { class: "tag", text: bp.type }), h("span", { class: "tag", text: "v" + bp.version })]),
          h("div", { class: "card" }, h("h2", { text: "Actions" }),
            actions.length
              ? h("ul", { class: "list mt-3" }, actions.map(function (a) {
                  return h("li", null,
                    h("div", { class: "row-between" },
                      h("strong", { text: a.name }),
                      a.sudo ? h("span", { class: "badge-sudo", text: "sudo" }) : null),
                    a.description ? h("p", { class: "small dim mt-2", text: a.description }) : null,
                    h("div", { class: "mt-3" }, renderCommand(a, null)));
                }))
              : empty("This blueprint has no actions yet.")),
          h("div", { class: "card" }, h("h2", { text: "Instantiate" }),
            h("p", { class: "small dim mt-2", text: "Choose machines or a tag. Creates per-machine PENDING_APPROVAL actions." }),
            h("div", { class: "stack mt-3" }, checks.map(function (c) {
              return h("label", { class: "row" }, c.cb,
                h("span", { class: "mono small", text: c.m.loginUser + "@" + c.m.host }));
            })),
            h("div", { class: "field mt-3" }, h("label", { text: "…or by tag" }), tagInput),
            h("button", { class: "btn btn--primary", onclick: instantiate }, "Instantiate")),
          result);
      });
    });
  }

  // ----- MCP surface --------------------------------------------------------
  // The catalogue is a live read from GET /api/mcp/tools (McpCatalogRS) rather than
  // a hardcoded list — it stays in sync with the tools spec-008 actually registers.
  // The point of the screen is to make the trust model legible: what an agent on
  // /mcp can do, grouped by kind, and that there is no approve tool.

  function screenMcp() {
    mountAsync(function () {
      return api("GET", "/mcp/tools").then(function (catalog) {
        var groups = (catalog.groups || []).map(function (g) {
          return h("div", { class: "section" }, h("h2", { text: g.group }),
            h("ul", { class: "list mt-3" }, (g.tools || []).map(function (t) {
              return h("li", null,
                h("code", { class: "mono", text: t.signature }),
                h("p", { class: "small dim mt-2", text: t.description }));
            })));
        });
        var resources = (catalog.resources || []).join("; ") || "none";
        return h("div", null,
          pageHead("MCP surface", "What an agent connected to /mcp can do — as you, over a personal token."),
          catalog.approveTool ? null : h("div", { class: "banner banner--warn", role: "note" },
            h("div", { class: "banner-body" },
              h("strong", { text: "There is no approve tool. " }),
              "Registration and authoring are open to MCP, but approval is UI-only: an agent can propose and ask, and can only run actions you have approved here.")),
          h("div", { class: "card" }, h("h2", { text: "Connection" }),
            h("dl", { class: "kv mt-3" },
              h("dt", { text: "Endpoint" }), h("dd", { class: "mono", text: location.origin + "/mcp" }),
              h("dt", { text: "Auth" }), h("dd", { class: "mono", text: "Authorization: Bearer <personal token>" }),
              h("dt", { text: "Identity" }), h("dd", { text: "The token acts as you; every tool scopes to your data." }),
              h("dt", { text: "Scope" }), h("dd", { text: "Your machines, recipes, and runs only — not-owned rows read as 404." }),
              h("dt", { text: "Resources" }), h("dd", { text: resources })),
            h("p", { class: "small dim mt-3" }, "Create a token under ",
              link("#/tokens", "Tokens", null), " or pair a client at ", h("code", { class: "mono", text: "/#/setup" }), ".")),
          groups);
      });
    });
  }

  // ----- Tokens & pairing ---------------------------------------------------

  function screenTokens() {
    mountAsync(function () {
      return api("GET", "/tokens").then(function (tokens) {
        var label = h("input", { placeholder: "e.g. laptop-cli" });
        function create() {
          api("POST", "/tokens", { label: label.value.trim() || null }).then(function (created) {
            revealOnceModal("Personal token created", created.token);
            screenTokens();
          }).catch(function (err) { toast(err.message); });
        }
        var active = tokens.filter(function (t) { return !t.revokedAt; });
        var list = active.length
          ? h("ul", { class: "list" }, active.map(function (t) {
              var revoke = h("button", { class: "btn btn--sm btn--danger" }, "Revoke");
              revoke.addEventListener("click", function () {
                api("DELETE", "/tokens/" + t.id).then(function () { toast("Revoked"); screenTokens(); })
                  .catch(function (err) { toast(err.message); });
              });
              return h("li", null, h("div", { class: "row-between" },
                h("div", { class: "grow" },
                  h("strong", { text: t.label || "(unlabelled)" }),
                  h("p", { class: "small dim mt-2 mono", text: "created " + fmtTime(t.createdAt) + " · last used " + fmtTime(t.lastUsedAt) })),
                revoke));
            }))
          : empty("No active tokens. Create one to connect an MCP client.");
        return h("div", null,
          pageHead("Tokens", "Personal MCP tokens. The plaintext is shown once, at creation."),
          h("div", { class: "card" }, h("h2", { text: "New token" }),
            h("div", { class: "field mt-3" }, h("label", { text: "Label" }), label),
            h("button", { class: "btn btn--primary", onclick: create }, "Create token")),
          h("div", { class: "section" }, h("h2", { text: "Active tokens" }), list));
      });
    });
  }

  function screenSetup(p, query) {
    var userCode = p.code || query.user_code || query.userCode || "";
    if (!userCode) {
      var input = h("input", { class: "mono", placeholder: "ABCD-1234" });
      mount(h("div", null,
        pageHead("Pair an MCP client", "Enter the code your agent is showing to link it to your account."),
        h("div", { class: "card" },
          h("div", { class: "field" }, h("label", { text: "User code" }), input),
          h("button", { class: "btn btn--primary", onclick: function () {
            if (input.value.trim()) location.hash = "#/setup/" + encodeURIComponent(input.value.trim());
          } }, "Continue"))));
      return;
    }
    mountAsync(function () {
      return api("GET", "/mcp-pairing/" + encodeURIComponent(userCode)).then(function (pairing) {
        function decide(verb) {
          api("POST", "/mcp-pairing/" + encodeURIComponent(userCode) + "/" + verb).then(function (updated) {
            toast("Pairing " + verb + (verb === "deny" ? "ied" : "d"));
            screenSetup({ code: userCode }, {});
          }).catch(function (err) { toast(err.message); });
        }
        var isPending = pairing.status === "PENDING";
        return h("div", null,
          pageHead("Pair an MCP client", "A client is requesting access as you."),
          h("div", { class: "card" },
            h("dl", { class: "kv" },
              h("dt", { text: "User code" }), h("dd", { class: "mono", text: pairing.userCode }),
              h("dt", { text: "Status" }), h("dd", null, chip(pairing.status))),
            isPending
              ? h("div", { class: "row mt-4" },
                  h("button", { class: "btn btn--ok", onclick: function () { decide("approve"); } }, "Approve"),
                  h("button", { class: "btn btn--danger", onclick: function () { decide("deny"); } }, "Deny"))
              : h("p", { class: "small dim mt-4", text: "This request is no longer pending." })),
          h("div", { class: "card" }, h("h2", { text: "How an agent connects" }),
            h("p", { class: "small dim mt-2", text: "The client began a device-authorization request and showed you this code. When you approve, it finishes the exchange and receives its own personal token — which then acts as you against /mcp. Approval happens only here, in your signed-in UI; the client never authenticates itself." })));
      });
    });
  }

  // ----- App SSH key --------------------------------------------------------

  function screenAppKey() {
    mountAsync(function () {
      return api("GET", "/ssh/public-key").then(function (key) {
        var snippet = 'echo "' + key.publicKey + '" >> ~/.ssh/authorized_keys';
        return h("div", null,
          pageHead("App SSH key", "One app-owned keypair serves the whole fleet. The private key never leaves this box (spec risk S2)."),
          h("div", { class: "card" },
            h("div", { class: "field" }, h("label", { text: "Public key" }),
              h("code", { class: "command command--scroll", text: key.publicKey }),
              h("button", { class: "btn btn--sm mt-2", onclick: function () { copy(key.publicKey); } }, "Copy key")),
            h("div", { class: "field" }, h("label", { text: "Fingerprint" }),
              h("code", { class: "command command--scroll", text: key.fingerprint })),
            h("div", { class: "field" }, h("label", { text: "Install on a target" }),
              h("code", { class: "command command--scroll", text: snippet }),
              h("button", { class: "btn btn--sm mt-2", onclick: function () { copy(snippet); } }, "Copy snippet"))));
      });
    });
  }

  // =========================================================== MONITOR ======
  //
  // spec-024 — the monitor dashboard. Enumerates the user's MONITOR-classified
  // actions (GET /api/monitor, grouped host vs per-app by the 022 convention),
  // then POLLS them client-side: the browser re-runs the already-APPROVED monitor
  // actions on a chosen cadence through the ordinary run path (POST /runs +
  // streamRunOutput) and renders the CURRENT reading — no server sampler, no
  // stored time-series. Approval stays UI-only; a not-approved action is never run
  // (rendered disabled with a link to the approval screen). All remote stdout
  // reaches the DOM only via textContent / the h() helper (spec-012 XSS).

  var MONITOR_CADENCES = [
    { key: "single", label: "Single", ms: 0 },
    { key: "5s", label: "5s", ms: 5000 },
    { key: "30s", label: "30s", ms: 30000 },
    { key: "1m", label: "1m", ms: 60000 },
    { key: "5m", label: "5m", ms: 300000 }
  ];

  /** Threshold band for a 0..100 metre: red ≥ 90, amber ≥ 75, else ok (spec-024). */
  function meterBand(pct) {
    if (pct == null || isNaN(pct)) return "none";
    if (pct >= 90) return "red";
    if (pct >= 75) return "amber";
    return "ok";
  }

  /** A labelled horizontal bar. `pct` is a number 0..100 (or null → “no data”). */
  function meter(label, pct, sub) {
    var known = pct != null && !isNaN(pct);
    var clamped = known ? Math.max(0, Math.min(100, pct)) : 0;
    var fill = h("div", { class: "meter-fill meter-fill--" + meterBand(pct) });
    fill.style.width = clamped + "%";
    return h("div", { class: "meter" },
      h("div", { class: "meter-head" },
        h("span", { class: "meter-label", text: label }),
        h("span", { class: "meter-val mono", text: known ? (Math.round(clamped) + "%") : "—" })),
      h("div", { class: "meter-track", role: "img",
        "aria-label": label + " " + (known ? Math.round(clamped) + " percent" : "no data") }, fill),
      sub ? h("div", { class: "meter-sub mono", text: sub }) : null);
  }

  // ---- client-side stdout parsers (spec-023) --------------------------------
  // The consumer redesign (spec-034) replaced the host-vitals meters with the
  // per-consumer segmented bars, so the host CPU/disk parsers are gone; the host
  // memory parser stays because its total is the RAM-%-of-host denominator, and
  // the per-app RSS parser (parseRssMb, below) is the numerator.

  function parseMem(text) {
    // free -m: "Mem:  <total> <used> ..." / "Swap: <total> <used> ..." (MiB).
    var out = {};
    text.split(/\r?\n/).forEach(function (ln) {
      var mem = ln.match(/^\s*Mem:\s+(\d+)\s+(\d+)/i);
      if (mem) {
        var t = parseInt(mem[1], 10), u = parseInt(mem[2], 10);
        out.mem = { total: t, used: u, pct: t ? (u / t) * 100 : 0 };
      }
      var sw = ln.match(/^\s*Swap:\s+(\d+)\s+(\d+)/i);
      if (sw) {
        var t2 = parseInt(sw[1], 10), u2 = parseInt(sw[2], 10);
        out.swap = { total: t2, used: u2, pct: t2 ? (u2 / t2) * 100 : 0 };
      }
    });
    return out.mem ? out : null;
  }

  function mibText(mib) {
    if (mib == null) return "";
    if (mib >= 1024) return (mib / 1024).toFixed(1) + " GiB";
    return mib + " MiB";
  }

  /** Percent-ish "1.20%" → 1.2 (number), null when unparseable. */
  function pctNum(s) {
    if (s == null) return null;
    var m = String(s).match(/([\d.]+)/);
    return m ? parseFloat(m[1]) : null;
  }

  /**
   * Parse `docker stats --no-stream --format '{{json .}}'` (spec-033): one JSON object
   * per line → [{ name, cpu, mem, memUsage }] with cpu/mem as numeric percents. The
   * cgroup-sourced RAM/CPU per container; degrade-to-raw (null) when no line parses.
   */
  function parseDockerStats(text) {
    if (!text) return null;
    var rows = [];
    text.split(/\r?\n/).forEach(function (ln) {
      ln = ln.trim();
      if (!ln) return;
      try {
        var o = JSON.parse(ln);
        var name = o.Name || o.Container;
        if (name) rows.push({ name: name, cpu: pctNum(o.CPUPerc), mem: pctNum(o.MemPerc), memUsage: o.MemUsage || null });
      } catch (e) { /* not a JSON line → skip (degrade-to-raw) */ }
    });
    return rows.length ? rows : null;
  }

  /**
   * Parse `docker ps -s --format '{{json .}}'` (spec-033): one JSON object per line →
   * [{ name, size }], the writable-layer + image size per container for the disk axis.
   * Named-volume sizes come from `docker system df -v` (kept raw). null → degrade-to-raw.
   */
  function parseDockerPs(text) {
    if (!text) return null;
    var rows = [];
    text.split(/\r?\n/).forEach(function (ln) {
      ln = ln.trim();
      if (!ln) return;
      try {
        var o = JSON.parse(ln);
        if (o.Names) rows.push({ name: (o.Names || "").split(",")[0].trim(), size: o.Size || null });
      } catch (e) { /* skip */ }
    });
    return rows.length ? rows : null;
  }

  /**
   * A compact per-container summary of a docker metric check's stdout (spec-033),
   * routed by the check name: `docker stats` → CPU%/mem% per container, `docker
   * disk`/`docker volumes` → size per container. null when nothing parses (the caller
   * then shows the raw output — the spec-023/025 degrade-to-raw contract). The parsed
   * values feed the 032 consumer axes once the fleet redesign (034) renders them.
   */
  function dockerSummary(action, text) {
    var n = (action.name || "").toLowerCase();
    if (n.indexOf("stat") >= 0) {
      var s = parseDockerStats(text);
      if (s) return s.map(function (r) {
        return r.name + " — cpu " + (r.cpu == null ? "?" : r.cpu + "%") + ", mem " + (r.mem == null ? "?" : r.mem + "%");
      }).join("\n");
    } else {
      var p = parseDockerPs(text);
      if (p) return p.map(function (r) { return r.name + " — " + (r.size || "?"); }).join("\n");
    }
    return null;
  }

  /**
   * Parse a docker/`df` size token into whole BYTES, null when unparseable (spec-037).
   * Handles the three unit dialects the docker/host reads use: binary `KiB/MiB/GiB/TiB`
   * (1024, `docker stats` MemUsage), SI `kB/MB/GB/TB` (1000, `docker ps -s` Size), and
   * the bare `K/M/G/T` of `df -h` (1024). A leading `"1.5GiB / 3.8GiB"` or `"1.09kB
   * (virtual 7.05MB)"` yields its FIRST value — the container's own share, not the pair.
   */
  function dockerBytes(s) {
    if (s == null) return null;
    var m = String(s).match(/([\d.]+)\s*([KMGTP])?(i)?B?/i);
    if (!m || m[1] === "") return null;
    var n = parseFloat(m[1]);
    if (isNaN(n)) return null;
    var unit = (m[2] || "").toUpperCase();
    if (!unit) return Math.round(n);
    var raw = String(s);
    // Binary when an explicit "i" (GiB) or a bare df-style letter with no trailing "B"
    // (df -h "50G"); SI only for the letter+B docker size form ("120MB").
    var hasI = !!m[3];
    var trailingB = new RegExp(unit + "B", "i").test(raw);
    var base = (hasI || !trailingB) ? 1024 : 1000;
    var pow = { K: 1, M: 2, G: 3, T: 4, P: 5 }[unit] || 0;
    return Math.round(n * Math.pow(base, pow));
  }

  /**
   * Parse the "Local Volumes space usage:" table of `docker system df -v` (spec-037):
   * [{ name, bytes }] per named volume, from the VOLUME NAME / LINKS / SIZE columns.
   * Named volumes are attributed to a compose project by the `<project>_…` name
   * convention at aggregation time. null → nothing parsed (degrade-to-raw).
   */
  function parseDockerVolumes(text) {
    if (!text) return null;
    var lines = text.split(/\r?\n/);
    var out = [], inSection = false;
    for (var i = 0; i < lines.length; i++) {
      var ln = lines[i];
      var head = ln.trim().toUpperCase();
      if (head.indexOf("VOLUME NAME") >= 0 && head.indexOf("SIZE") >= 0) { inSection = true; continue; }
      if (!inSection) continue;
      if (!ln.trim()) break;                         // blank line ends the section
      if (/space usage:/i.test(ln)) break;           // next section header
      var cols = ln.trim().split(/\s{2,}|\t+/);
      if (cols.length < 2) cols = ln.trim().split(/\s+/);
      var name = cols[0];
      var size = cols[cols.length - 1];
      var bytes = dockerBytes(size);
      if (name && bytes != null) out.push({ name: name, bytes: bytes });
    }
    return out.length ? out : null;
  }

  /**
   * The total capacity in BYTES of the filesystem mounted at `/` from `df -h` stdout
   * (spec-037): the docker data-root (`/var/lib/docker`) proxy, the disk-axis
   * denominator. Falls back to the first data row when no `/` row is found; null when
   * nothing parses (disk then degrades to —).
   */
  function parseDfTotal(text) {
    if (!text) return null;
    var lines = text.split(/\r?\n/), root = null, firstData = null;
    for (var i = 0; i < lines.length; i++) {
      var cols = lines[i].trim().split(/\s+/);
      if (cols.length < 6) continue;
      if (/^filesystem$/i.test(cols[0])) continue;   // header
      if (firstData == null) firstData = cols;
      if (cols[cols.length - 1] === "/") { root = cols; break; }
    }
    var row = root || firstData;
    return row ? dockerBytes(row[1]) : null;          // the Size column
  }

  /**
   * The USED percentage of the root/data-root filesystem from `df -h` stdout (spec-041):
   * the `Use%` column of the same `/` row parseDfTotal keys on (the /var/lib/docker
   * proxy). This feeds the disk OTHER/system computation — the host's actual disk-in-use,
   * distinct from the summed per-consumer attribution. null when nothing parses (→ —).
   */
  function parseDfUsedPct(text) {
    if (!text) return null;
    var lines = text.split(/\r?\n/), root = null, firstData = null;
    for (var i = 0; i < lines.length; i++) {
      var cols = lines[i].trim().split(/\s+/);
      if (cols.length < 6) continue;
      if (/^filesystem$/i.test(cols[0])) continue;   // header
      if (firstData == null) firstData = cols;
      if (cols[cols.length - 1] === "/") { root = cols; break; }
    }
    var row = root || firstData;
    if (!row) return null;
    // Use% is the column ending in "%" (df -h: Filesystem Size Used Avail Use% Mounted on).
    for (var j = 0; j < row.length; j++) {
      var m = String(row[j]).match(/^(\d+(?:\.\d+)?)%$/);
      if (m) return parseFloat(m[1]);
    }
    return null;
  }

  /**
   * Host CPU-in-use percent from `top -bn1` stdout (spec-041; the host CPU parser
   * removed in spec-034 is re-added here for the OTHER/system computation only — the
   * per-consumer CPU denominator stays `nproc`). Reads the `%Cpu(s): … <n> id` idle
   * field and returns `100 − idle`, clamped ≥ 0. null when the line is unparseable (→ —).
   */
  function parseHostCpu(text) {
    if (!text) return null;
    var lines = text.split(/\r?\n/);
    for (var i = 0; i < lines.length; i++) {
      if (!/%?Cpu/i.test(lines[i])) continue;
      var idle = lines[i].match(/([\d.]+)\s*id\b/i);
      if (idle) return Math.max(0, 100 - parseFloat(idle[1]));
    }
    return null;
  }

  function metricKind(action) {
    var n = (action.name || "").toLowerCase();
    // Docker consumer metrics (spec-033) route first — a "docker disk" check must not be
    // mistaken for the host `df` disk vital below.
    if (n.indexOf("docker") >= 0) return "docker";
    if (n.indexOf("cpu") >= 0 || n.indexOf("load") >= 0) return "cpu";
    if (n.indexOf("mem") >= 0 || n.indexOf("ram") >= 0) return "memory";
    if (n.indexOf("disk") >= 0 || n.indexOf("df") >= 0 || n.indexOf("filesystem") >= 0) return "disk";
    return "other";
  }

  /**
   * Run one APPROVED action through the ordinary run path and collect its stdout.
   * Gate-safe: the server re-checks approval + live-hash + params (this only POSTs
   * the run and reads the stream). Resolves with the accumulated stdout string.
   */
  function runAndCollect(machineId, actionId, params) {
    return api("POST", "/runs", { machineId: machineId, actionId: actionId, params: params || {} })
      .then(function (run) {
        return new Promise(function (resolve) {
          var out = "";
          streamRunOutput(run.id, {
            onChunk: function (stream, data) {
              if (stream === "stdout" || stream === "stderr") out += data + "\n";
            },
            onDone: function () { resolve({ runId: run.id, stdout: out }); }
          });
        });
      });
  }

  function actionApprovalHref(a) {
    return "#/machines/" + a.machineId + "/recipes/" + a.recipeId + "/actions/" + a.id;
  }
  function actionRunHref(a) {
    return actionApprovalHref(a) + "/run";
  }

  /**
   * spec-067: the shared per-machine footprint section factory. Extracted from the
   * fleet Monitor (spec-034) so BOTH the Monitor route and the single-machine dashboard
   * (Screen C) render the SAME axisMeter ×3 + consumerLegend + computeOther + Consumers
   * `consumerCard` grid over one paint() path — no fork of the paint/poll code. It owns
   * the six host denominator/usage maps and the four approved-host-vital polls
   * (pollHost*). The caller injects `models`/`named` state, the `selectedNamed`/`noAppsOn`
   * filter predicates and the app-name `onToggleApp` (a no-op on the one-machine page,
   * where filtering the fleet to an app is meaningless — the name then renders as plain
   * text), and drives the lens/bucket View through the returned `view` object. When
   * `showHead` is false the section renders body-only — the machine dashboard's pageHead
   * already carries the host@ident + status chip, so buildSection must not duplicate it.
   *
   * Returns { view, buildSection }.
   */
  function makeFootprint(cfg) {
    var models = cfg.models;
    var selectedNamed = cfg.selectedNamed;
    var noAppsOn = cfg.noAppsOn || function () { return false; };
    var onToggleApp = cfg.onToggleApp || null;
    var showHead = cfg.showHead !== false;
    // The two pure RE-RENDER toggles (spec-034): the View lens (apps|db) and the docker/
    // system bucket reveals. The caller mutates these through this object and repaints.
    var view = { lens: "apps", showDocker: false, showSystem: false };

    var hostMemTotal = {};    // machineId → host total MB (the RAM-% denominator)
    var hostCores = {};       // machineId → logical CPU count (docker CPU-% denominator, spec-037)
    var hostDiskTotal = {};   // machineId → data-root FS total bytes (docker disk-% denominator, spec-037)
    // Host USED readings (spec-041), the numerators of the OTHER/system segment: real
    // RAM used MB, CPU-in-use %, and disk Use% — kept per machine alongside the totals.
    var hostMemUsed = {};     // machineId → host used MB (free -m)
    var hostCpuUsed = {};     // machineId → host CPU-in-use % (top -bn1: 100 − idle)
    var hostDiskUsedPct = {}; // machineId → data-root Use% (df -h)

    // ---- one machine's section (spec-034) ----------------------------
    // The host panel is three segmented axisMeters + a legend; the body is either
    // the consumer-card grid (Apps lens) or the datastore bands (Databases lens).
    // paint() rebuilds the body from the consumers' CURRENT state, so a re-poll
    // (which mutates those consumer objects in place) and a lens/bucket toggle
    // both go through the same paint() — no duplicate render paths.
    function buildSection(m) {
      var all = models[m.machineId];
      var synthOther = null;   // the client-synthesized OTHER/system consumer (spec-041)
      var bodyWrap = h("div");
      var node = showHead
        ? h("section", { class: "section monitor-machine" },
            h("div", { class: "row-between" },
              h("div", { class: "grow" },
                h("h2", { text: m.host }),
                h("p", { class: "small dim mono", text: m.loginUser + "@" + m.host + ":" + m.port })),
              chip(m.status)),
            bodyWrap)
        : h("section", { class: "section monitor-machine" }, bodyWrap);

      function openDrawer(cid) {
        if (synthOther && synthOther.id === cid) { openConsumerDrawer(m, synthOther); return; }
        var c = all.filter(function (x) { return x.id === cid; })[0];
        if (c) openConsumerDrawer(m, c);
      }
      function revealedBuckets() {
        return all.filter(function (c) {
          if (c.bucket === "DOCKER") return view.showDocker;
          if (c.bucket === "SYSTEM") return view.showSystem;
          return false;
        });
      }

      function paint() {
        clear(bodyWrap);
        if (view.lens === "db") {
          renderDbInto(bodyWrap, all.filter(function (c) { return !c.bucket; }), openDrawer);
          return;
        }
        // Bars = the polled named consumers, then any revealed bucket, then the
        // client-synthesized OTHER/system segment (spec-041) that carries the host's
        // real unattributed usage; the genuinely-free tail stays hatched. The OTHER
        // segment is shown BY DEFAULT so an app-less host reads as used, not idle.
        var named = selectedNamed(m);
        var mid = m.machineId;
        var mt = hostMemTotal[mid], mu = hostMemUsed[mid];
        var hostUsed = {
          ram: (mt != null && mu != null && mt > 0) ? (mu / mt * 100) : null,
          cpu: hostCpuUsed[mid] != null ? hostCpuUsed[mid] : null,
          disk: hostDiskUsedPct[mid] != null ? hostDiskUsedPct[mid] : null
        };
        synthOther = computeOther(mid, hostUsed, named);
        var buckets = revealedBuckets();
        // One OTHER segment only: when we synthesize it, drop any server-provided
        // SYSTEM bucket so system usage is never double-counted (spec-041 reconcile).
        if (synthOther) buckets = buckets.filter(function (c) { return c.bucket !== "SYSTEM"; });
        var bars = named.concat(buckets).concat(synthOther ? [synthOther] : []);
        bodyWrap.appendChild(h("div", { class: "host-panel" },
          axisMeter("RAM", bars, "ram", openDrawer),
          axisMeter("CPU", bars, "cpu", openDrawer),
          axisMeter("Disk", bars, "disk", openDrawer)));
        bodyWrap.appendChild(consumerLegend(bars, openDrawer));
        var cards = noAppsOn() ? [] : selectedNamed(m);
        if (cards.length) {
          bodyWrap.appendChild(h("h3", { class: "mt-4", text: "Consumers" }));
          bodyWrap.appendChild(h("div", { class: "app-cards" },
            cards.map(function (c) { return consumerCard(c, onToggleApp, openDrawer); })));
        } else if (!noAppsOn() && !bars.length) {
          bodyWrap.appendChild(h("p", { class: "small dim mt-3",
            text: "No discovered consumers on this host." }));
        }
      }

      // Host denominators first — RAM total (free -m), core count (nproc, spec-037),
      // data-root FS total (df -h, spec-037) — then the two polls that fill the
      // consumer axes: the native APP_PORT_LIST fan-out (RAM) and the param-free
      // docker reads (RAM/CPU/disk, spec-037). All mutate the shared consumer
      // objects in place, then paint() rebuilds from their current state.
      function refresh() {
        return Promise.all([pollHostTotal(m), pollHostCores(m), pollHostDiskTotal(m), pollHostCpuUsed(m)])
          .then(function (res) {
            var mem = res[0], disk = res[2];
            if (mem != null) {
              hostMemTotal[m.machineId] = mem.total;
              if (mem.used != null) hostMemUsed[m.machineId] = mem.used;
            }
            if (res[1] != null) hostCores[m.machineId] = res[1];
            if (disk != null) {
              hostDiskTotal[m.machineId] = disk.total;
              if (disk.usedPct != null) hostDiskUsedPct[m.machineId] = disk.usedPct;
            }
            if (res[3] != null) hostCpuUsed[m.machineId] = res[3];
            var denom = {
              ramMb: hostMemTotal[m.machineId],
              cores: hostCores[m.machineId],
              diskBytes: hostDiskTotal[m.machineId]
            };
            return Promise.all([
              // spec-057: thread the root-FS denominator so the native disk axis divides
              // by the SAME denom.diskBytes applyDockerReading uses (OTHER subtracts cleanly).
              pollConsumers(m.machineId, selectedNamed(m), denom.ramMb, denom.cores, denom.diskBytes),
              pollDockerConsumers(m, selectedNamed(m), denom)
            ]);
          }).then(paint);
      }

      paint();
      return { node: node, refresh: refresh, paint: paint };
    }

    // Poll the machine's approved host-memory probe → { total, used } MB (spec-041:
    // the used value, already computed by parseMem, is no longer dropped). The total
    // is the RAM-% denominator; the used feeds the OTHER/system numerator.
    function pollHostTotal(m) {
      var mem = (m.hostActions || []).filter(function (a) {
        return metricKind(a) === "memory" && a.approvalState === "APPROVED"
          && !a.changedSinceApproval && (a.paramDefs || []).length === 0;
      })[0];
      if (!mem) return Promise.resolve(null);
      return runAndCollect(m.machineId, mem.id, {}).then(function (r) {
        var parsed = parseMem(r.stdout);
        if (!parsed || !parsed.mem || !parsed.mem.total) return null;
        return { total: parsed.mem.total, used: parsed.mem.used != null ? parsed.mem.used : null };
      }).catch(function () { return null; });
    }

    // Poll the approved host CPU vital (top -bn1, metricKind "cpu"; re-added in
    // spec-041) → host CPU-in-use %. Distinct from the `cores`/nproc denominator poll
    // (found by name). null → the CPU OTHER axis degrades to — (honesty).
    function pollHostCpuUsed(m) {
      var cpu = (m.hostActions || []).filter(function (a) {
        return metricKind(a) === "cpu" && a.approvalState === "APPROVED"
          && !a.changedSinceApproval && (a.paramDefs || []).length === 0;
      })[0];
      if (!cpu) return Promise.resolve(null);
      return runAndCollect(m.machineId, cpu.id, {}).then(function (r) {
        return parseHostCpu(r.stdout);
      }).catch(function () { return null; });
    }

    // Poll the approved `cores` host vital (nproc, spec-037) → logical CPU count,
    // the denominator for the docker CPU axis. null → CPU degrades to — (honesty).
    // Found by name (nproc/core) so it is never confused with the top -bn1 host CPU.
    function pollHostCores(m) {
      var cores = (m.hostActions || []).filter(function (a) {
        var n = (a.name || "").toLowerCase();
        return (n.indexOf("core") >= 0 || n.indexOf("nproc") >= 0)
          && a.approvalState === "APPROVED" && !a.changedSinceApproval
          && (a.paramDefs || []).length === 0;
      })[0];
      if (!cores) return Promise.resolve(null);
      return runAndCollect(m.machineId, cores.id, {}).then(function (r) {
        var m2 = (r.stdout || "").match(/\d+/);
        var n = m2 ? parseInt(m2[0], 10) : null;
        return (n && n > 0) ? n : null;
      }).catch(function () { return null; });
    }

    // Poll the approved host `disk` vital (df -h, spec-037) → { total, usedPct }: the
    // data-root filesystem total bytes (the `/` row as the /var/lib/docker proxy, the
    // docker disk-axis denominator) plus its Use% (spec-041, the OTHER/system disk
    // numerator). null → disk degrades to —.
    function pollHostDiskTotal(m) {
      var disk = (m.hostActions || []).filter(function (a) {
        return metricKind(a) === "disk" && a.approvalState === "APPROVED"
          && !a.changedSinceApproval && (a.paramDefs || []).length === 0;
      })[0];
      if (!disk) return Promise.resolve(null);
      return runAndCollect(m.machineId, disk.id, {}).then(function (r) {
        var total = parseDfTotal(r.stdout);
        if (total == null) return null;
        return { total: total, usedPct: parseDfUsedPct(r.stdout) };
      }).catch(function () { return null; });
    }

    return { view: view, buildSection: buildSection };
  }

  function screenMonitor() {
    mountAsync(function () {
      return api("GET", "/monitor").then(function (dash) {
        var machines = (dash && dash.machines) || [];

        // ---- filter + view state (client-side; spec-029/034) -------------
        // Two POLL-SET filters (spec-029): machine tags (018) and app-names (022) —
        // both define what is polled, so a filtered-out machine/app is never polled.
        // Two pure RE-RENDER toggles (spec-034): the View lens (Apps | Databases) and
        // the Show chips (docker / system buckets); they re-slice already-polled data
        // and never touch the poll set.
        var selectedTags = {};
        var selectedApps = {};   // app-name → true; plus the synthetic NO_APPS token
        var cadence = "single";
        // spec-067: the lens/bucket View state and the six host denominator/usage maps
        // now live in the shared makeFootprint factory (fp.view + its own closure).

        // The consumer spine (spec-032/034): every machine's apps re-expressed as
        // MonitorConsumerViews, joined to the 029 per-app rollup for the probe
        // metadata (framework/port/checks/ops) the 032 contract does not carry yet.
        // Built ONCE per machine so a consumer keeps its last poll reading across the
        // client re-renders that lens/bucket/filter toggles trigger.
        var models = {};
        machines.forEach(function (m) { models[m.machineId] = buildConsumers(m); });
        function named(mid) { return models[mid].filter(function (c) { return !c.bucket; }); }

        // spec-067: the footprint sections (host axes + legend + Consumers grid) are built
        // by the shared makeFootprint factory — the SAME one the single-machine dashboard
        // uses. It owns the host denominator/usage maps + the four approved-host-vital polls;
        // this fleet route injects its models, app-filter predicate and app-name toggle, and
        // drives the lens/bucket View chips through fp.view.
        var fp = makeFootprint({
          models: models,
          selectedNamed: function (m) { return selectedNamed(m); },
          noAppsOn: function () { return noAppsOn(); },
          onToggleApp: function (name) { toggleApp(name); },
          showHead: true
        });

        var allTags = uniqSorted(flatMap(machines, function (m) { return m.tags || []; }));
        var allApps = uniqSorted(flatMap(machines, function (m) {
          return named(m.machineId).map(function (c) { return c.name; });
        }));
        var anyHostOnly = machines.some(function (m) { return named(m.machineId).length === 0; });

        function selTags() { return allTags.filter(function (t) { return selectedTags[t]; }); }
        function selApps() { return allApps.filter(function (a) { return selectedApps[a]; }); }
        function noAppsOn() { return !!selectedApps[NO_APPS]; }
        function appFilterActive() { return selApps().length > 0; }
        function tagMatch(m) {
          var s = selTags(); if (!s.length) return true;
          var mine = m.tags || [];
          return s.some(function (t) { return mine.indexOf(t) >= 0; });
        }
        // The poll set of named consumers for one machine: all its apps, or — when an
        // app-name is pinned — only the pinned ones (unpinned apps are never polled).
        // Buckets are server-side aggregates, never polled, so they are excluded here.
        function selectedNamed(m) {
          var list = named(m.machineId);
          if (appFilterActive() && !noAppsOn()) {
            var s = selApps();
            return list.filter(function (c) { return s.indexOf(c.name) >= 0; });
          }
          return list;
        }
        // A machine is visible when it matches the tag filter AND either no app-name is
        // pinned (⇒ all machines), the host-only view is on, or it runs a pinned app.
        function machineVisible(m) {
          if (!tagMatch(m)) return false;
          if (noAppsOn() || !appFilterActive()) return true;
          return selectedNamed(m).length > 0;
        }

        // ---- poll state --------------------------------------------------
        var pollTimer = null, heartbeatTimer = null, lastUpdated = null, cycleInFlight = false;
        var sections = [];

        var updatedLabel = h("span", { class: "small dim", text: "not yet updated" });
        var counterLabel = h("span", { class: "small dim mono" });
        var runNowBtn = h("button", { class: "btn btn--sm btn--primary" }, "Run now");
        var cadenceSel = h("select", { class: "mono", "aria-label": "Poll cadence" },
          MONITOR_CADENCES.map(function (c) { return h("option", { value: c.key, text: c.label }); }));

        function stopTimers() {
          if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
          if (heartbeatTimer) { clearInterval(heartbeatTimer); heartbeatTimer = null; }
        }
        // Registered with the router so navigating away kills every timer (no leak).
        currentViewCleanup = stopTimers;

        function tickHeartbeat() {
          if (!lastUpdated) { updatedLabel.textContent = "not yet updated"; return; }
          updatedLabel.textContent = "updated " + Math.round((Date.now() - lastUpdated) / 1000) + "s ago";
        }
        function applyCadence() {
          stopTimers();
          heartbeatTimer = setInterval(tickHeartbeat, 1000);
          var chosen = MONITOR_CADENCES.filter(function (c) { return c.key === cadence; })[0];
          if (chosen && chosen.ms > 0) pollTimer = setInterval(cycle, chosen.ms);
        }
        cadenceSel.addEventListener("change", function (e) { cadence = e.target.value; applyCadence(); });
        runNowBtn.addEventListener("click", function () { cycle(); });

        var tagBar = h("div", { class: "filter-chips" });
        var appBar = h("div", { class: "filter-chips" });
        var viewBar = h("div", { class: "filter-chips" });
        var body = h("div", { class: "monitor-machines" });

        // Clicking a card's app-name is the second entry point to the app filter.
        function toggleApp(name) {
          var on = !selectedApps[name];
          selectedApps[name] = on;
          // no-apps (host-only view) and app-name pins are mutually exclusive.
          if (on && name === NO_APPS) { allApps.forEach(function (a) { selectedApps[a] = false; }); }
          else if (on) { selectedApps[NO_APPS] = false; }
          renderChips(); rebuild(); cycle();
        }
        function toggleTag(name) { selectedTags[name] = !selectedTags[name]; renderChips(); rebuild(); cycle(); }

        function chipBtn(label, on, title, onClick) {
          return h("button", { type: "button",
            class: "tag tag--filter" + (on ? " tag--on" : ""),
            "aria-pressed": on ? "true" : "false", title: title || label, text: label, onclick: onClick });
        }

        // The lens + bucket toggles re-slice already-polled data, so they only
        // repaint the existing sections — no re-poll (spec-034 §7).
        function repaint() { sections.forEach(function (s) { s.paint(); }); updateCounter(); }
        function setLens(x) { fp.view.lens = x; renderChips(); repaint(); }
        function toggleBucket(which) {
          if (which === "docker") fp.view.showDocker = !fp.view.showDocker;
          else fp.view.showSystem = !fp.view.showSystem;
          renderChips(); repaint();
        }

        function renderChips() {
          clear(tagBar); clear(appBar); clear(viewBar);
          if (allTags.length) {
            tagBar.appendChild(h("span", { class: "small dim", text: "Tags" }));
            allTags.forEach(function (t) {
              tagBar.appendChild(chipBtn(t, !!selectedTags[t], "Filter to machines tagged " + t,
                function () { toggleTag(t); }));
            });
          }
          if (allApps.length || anyHostOnly) {
            appBar.appendChild(h("span", { class: "small dim", text: "Apps" }));
            allApps.forEach(function (a) {
              appBar.appendChild(chipBtn(a, !!selectedApps[a], "Compare " + a + " across the fleet",
                function () { toggleApp(a); }));
            });
            if (anyHostOnly) {
              appBar.appendChild(chipBtn("no-apps", !!selectedApps[NO_APPS],
                "Host-only view: machine rollup bars, no consumer cards", function () { toggleApp(NO_APPS); }));
            }
          }
          // The View lens and the bucket Show chips (spec-034): pure re-render toggles.
          viewBar.appendChild(h("span", { class: "small dim", text: "View" }));
          viewBar.appendChild(chipBtn("Apps", fp.view.lens === "apps",
            "Per-consumer cards on three axes", function () { setLens("apps"); }));
          viewBar.appendChild(chipBtn("Databases", fp.view.lens === "db",
            "Re-slice the same consumers by datastore role", function () { setLens("db"); }));
          viewBar.appendChild(h("span", { class: "small dim", style: "margin-left:12px", text: "Show" }));
          viewBar.appendChild(chipBtn("docker bucket", fp.view.showDocker,
            "Reveal the unclassified docker bucket in the bars", function () { toggleBucket("docker"); }));
          viewBar.appendChild(chipBtn("system / free", fp.view.showSystem,
            "Reveal the system + free remainder in the bars", function () { toggleBucket("system"); }));
        }

        function updateCounter() {
          var vm = machines.filter(machineVisible);
          var apps = 0;
          vm.forEach(function (mch) { apps += selectedNamed(mch).length; });
          counterLabel.textContent = "polling " + vm.length + (vm.length === 1 ? " machine · " : " machines · ")
            + apps + (apps === 1 ? " app" : " apps");
        }

        function rebuild() {
          clear(body);
          sections = [];
          var vm = machines.filter(machineVisible);
          if (!vm.length) {
            body.appendChild(empty(machines.length
              ? "No machines match the current filters."
              : "No machines yet. Register one and run discovery to propose the monitor recipes."));
            updateCounter();
            return;
          }
          vm.forEach(function (mch) {
            var sec = fp.buildSection(mch);
            sections.push(sec);
            body.appendChild(sec.node);
          });
          updateCounter();
        }

        // The poll loop touches ONLY the currently-rendered (visible) sections.
        function cycle() {
          if (cycleInFlight) return;
          cycleInFlight = true; runNowBtn.disabled = true;
          Promise.all(sections.map(function (s) { return s.refresh(); }))
            .then(function () { lastUpdated = Date.now(); tickHeartbeat(); })
            .catch(function () { /* per-section errors are shown in-section */ })
            .then(function () { cycleInFlight = false; runNowBtn.disabled = false; });
        }

        var head = h("div", { class: "page-head" },
          h("div", null,
            h("h1", { text: "Monitor" }),
            h("p", { class: "sub", text: "Fleet health, polled from your browser. Every consumer — app, datastore, bucket — sits on the same RAM/CPU/disk axes as its host. Filter by tag or app-name; whatever is filtered out is not polled. Approval stays UI-only." })),
          h("div", { class: "row monitor-controls" },
            h("label", { class: "small dim", text: "Cadence" }),
            cadenceSel, runNowBtn, counterLabel, updatedLabel));

        renderChips();
        rebuild();
        // Default: all machines, poll = Single → one cheap one-shot, no standing load.
        applyCadence();
        cycle();

        return h("div", null, head, tagBar, appBar, viewBar, body);
      });
    });
  }

  // ---- app-ops (spec-026) --------------------------------------------------
  // App-ops surfaces approved mutating actions (restart / tail-logs / redeploy) on the
  // card of every app their reserved `app-name` param can target. The correlation key is
  // the param, not a machine tag or recipe label; targetApps is that param's ALLOWED_SET.

  /** The machine's ops actions that can target `appName` (targetApps contains it). */
  function opsForApp(mch, appName) {
    return (mch.appOps || []).filter(function (op) {
      return (op.targetApps || []).indexOf(appName) >= 0;
    });
  }

  /**
   * A run chip for one ops action on a given app: links to the run form with the app
   * pre-filled and LOCKED (?app-name=<app>). The remaining params are entered there and
   * the unchanged run path enforces the gate — this chip only navigates.
   */
  function opsRunChip(op, appName) {
    var href = actionRunHref(op) + "?app-name=" + encodeURIComponent(appName);
    return h("a", { class: "run-chip", href: href,
      title: "Run " + op.name + " for " + appName },
      op.name, h("span", { class: "run-chip-go", text: "run…" }));
  }

  // ---- fleet poll helpers (spec-029) ---------------------------------------
  // Per-app metrics (up / rss / mem%) are client-computed from live probe runs: a
  // fan-out probe is POSTed for the visible apps, its children are listed
  // (GET /runs/{id}/children), and each child's output is streamed and attributed to
  // its app by appLabel. No server sampler — closing the tab stops all probing (024).

  // Synthetic app-filter token selecting host-only machines. A NUL prefix keeps it
  // disjoint from any real app-name (which the fixed charset forbids NUL in).
  var NO_APPS = " no-apps";

  function uniqSorted(list) {
    var seen = {}, out = [];
    list.forEach(function (x) { if (x != null && !seen[x]) { seen[x] = true; out.push(x); } });
    out.sort();
    return out;
  }
  function flatMap(list, fn) {
    var out = [];
    list.forEach(function (x) { fn(x).forEach(function (y) { out.push(y); }); });
    return out;
  }

  /** The name of an action's APP_PORT_LIST (fan-out) param, or null if it is scalar. */
  function appPortListParamName(action) {
    var defs = action.paramDefs || [];
    for (var i = 0; i < defs.length; i++) {
      if (defs[i].kind === "APP_PORT_LIST") return defs[i].name;
    }
    return null;
  }

  /** Subscribe to one run's stream, resolving with its accumulated stdout + exit code. */
  function collectRun(runId) {
    return new Promise(function (resolve) {
      var out = "", exit = null;
      streamRunOutput(runId, {
        onChunk: function (stream, data) {
          if (stream === "exit") { exit = parseInt(data, 10); return; }
          if (stream === "stdout" || stream === "stderr") out += data + "\n";
        },
        onDone: function () { resolve({ stdout: out, exit: exit }); }
      });
    });
  }

  /**
   * Run one fan-out probe over `apps` ([{appName, port}]) and collect each app's output.
   * POSTs the run (gate-safe: the server re-checks approval + live-hash + params), lists
   * the fan-out children, then streams each child keyed by its appLabel. Resolves with a
   * map appName → { stdout, exit }.
   */
  function runProbeForApps(machineId, checkAction, apps) {
    var paramName = appPortListParamName(checkAction);
    if (!paramName || !apps.length) return Promise.resolve({});
    var value = JSON.stringify(apps.map(function (a) { return { appName: a.appName, port: a.port }; }));
    var params = {}; params[paramName] = value;
    return api("POST", "/runs", { machineId: machineId, actionId: checkAction.id, params: params })
      .then(function (parent) {
        return api("GET", "/runs/" + encodeURIComponent(parent.id) + "/children")
          .then(function (children) {
            var byApp = {};
            return Promise.all((children || []).map(function (c) {
              return collectRun(c.id).then(function (r) { byApp[c.appLabel] = r; });
            })).then(function () { return byApp; });
          });
      }).catch(function () { return {}; });
  }

  /**
   * Classify a check by name: liveness/health, process (VmRSS), cpu, or other. The
   * app-level cpu probe (spec-032) is a first-class metric-kind here, the client-side
   * mirror of the server classification — matching metricKind()'s host-CPU rule so an
   * app's process-tree CPU probe is recognised, not lumped into "other".
   */
  function checkKind(action) {
    var n = (action.name || "").toLowerCase();
    if (n.indexOf("process") >= 0 || n.indexOf("proc") >= 0) return "process";
    // spec-057 footprint axes: ram (PSS) and disk (du) are first-class metric-kinds
    // alongside the cpu-rate probe, each folded onto its own host denominator below.
    if (n.indexOf("ram") >= 0 || n.indexOf("mem") >= 0) return "ram";
    if (n.indexOf("disk") >= 0) return "disk";
    if (n.indexOf("cpu") >= 0 || n.indexOf("load") >= 0) return "cpu";
    if (n.indexOf("health") >= 0 || n.indexOf("live") >= 0 || n.indexOf("ping") >= 0
        || n.indexOf("readiness") >= 0) return "liveness";
    return "other";
  }

  /** Sum VmRSS (kB) from a process-probe stdout into whole MB, or null if absent. */
  function parseRssMb(text) {
    if (!text) return null;
    var total = 0, seen = false;
    text.split(/\r?\n/).forEach(function (ln) {
      var m = ln.match(/VmRSS:\s+(\d+)\s*kB/i);
      if (m) { total += parseInt(m[1], 10); seen = true; }
    });
    return seen ? Math.round(total / 1024) : null;
  }

  /**
   * Sum PSS (kB) from a spec-057 ram-probe stdout into whole MB, or null if absent. PSS
   * (proportional set size) is the honest per-context RAM figure — summing it across N
   * workers that share libraries does NOT overcount, unlike summing RSS. Each `## pid N`
   * block emits `Pss: <kB> kB`; on a procfs denial the probe emits VmRSS + a
   * `ram_confidence=low` marker instead (parsed by parseRssMb / ramConfidenceLow).
   */
  function parsePss(text) {
    if (!text) return null;
    var total = 0, seen = false;
    text.split(/\r?\n/).forEach(function (ln) {
      var m = ln.match(/Pss:\s+(\d+)\s*kB/i);
      if (m) { total += parseInt(m[1], 10); seen = true; }
    });
    return seen ? Math.round(total / 1024) : null;
  }

  /** Whether a ram-probe degraded to the RSS upper-bound (procfs denied) — a label, spec-057. */
  function ramConfidenceLow(text) {
    return !!(text && /ram_confidence=low/.test(text));
  }

  /**
   * Compute a cross-core %CPU from a spec-057 cpu-RATE probe stdout: two /proc/<pid>/stat
   * samples (`## s0` / `## s1`) a MEASURED Δt apart (`t0=`/`t1=` epoch seconds), each line
   * `pid=P ticks=(utime+stime) starttime=S`. Per pid present in both samples with an
   * UNCHANGED starttime (guarding PID reuse), Δticks = s1−s0; the sum ÷ CLK_TCK ÷ Δt × 100 is
   * the cross-core percent (Σ per-process core-fractions × 100; may exceed 100, matching
   * denom.cores). null when the interval or samples are missing — the axis then degrades to —.
   */
  function parseStatTicks(text) {
    if (!text) return null;
    var clk = 100, t0 = null, t1 = null, seg = null, s0 = {}, s1 = {};
    text.split(/\r?\n/).forEach(function (ln) {
      var t = ln.trim(), m;
      if ((m = t.match(/^clk_tck=(\d+)/))) { clk = parseInt(m[1], 10) || 100; return; }
      if ((m = t.match(/^t0=([\d.]+)/))) { t0 = parseFloat(m[1]); return; }
      if ((m = t.match(/^t1=([\d.]+)/))) { t1 = parseFloat(m[1]); return; }
      if (t === "## s0") { seg = s0; return; }
      if (t === "## s1") { seg = s1; return; }
      if (seg && (m = t.match(/^pid=(\d+)\s+ticks=(\d+)\s+starttime=(\d+)/))) {
        seg[m[1]] = { ticks: parseInt(m[2], 10), st: m[3] };
      }
    });
    if (t0 == null || t1 == null) return null;
    var dt = t1 - t0;
    if (!(dt > 0)) return null;
    var sum = 0, seen = false;
    Object.keys(s1).forEach(function (pid) {
      var a = s0[pid], b = s1[pid];
      if (a && b && a.st === b.st) {
        var d = b.ticks - a.ticks;
        if (d >= 0) { sum += d; seen = true; }
      }
    });
    if (!seen) return null;
    return (sum / clk) / dt * 100;
  }

  /** Bytes from a spec-057 disk-probe stdout (`du_bytes=<n>`, already bytes), or null. */
  function parseDuBytes(text) {
    if (!text) return null;
    var m = text && text.match(/du_bytes=(\d+)/);
    return m ? parseInt(m[1], 10) : null;
  }

  /**
   * round(rssMb / hostTotalMb * 100), null-safe. The client is the SOLE owner of the
   * mem-%-of-host axis: spec-032 dropped the dead server-side MonitorDtos.memPctOfHost
   * twin (catalog H8), so this is the single source of truth for the axis.
   */
  function clientMemPct(rssMb, hostTotalMb) {
    if (rssMb == null || hostTotalMb == null || hostTotalMb <= 0) return null;
    return Math.round(rssMb / hostTotalMb * 100);
  }

  // ---- consumer render helpers (spec-034) ----------------------------------
  // The redesigned fleet UI is CONSUMER-centric: it renders MonitorConsumerView
  // (spec-032) instead of the 029 MonitorAppView. Every consumer sits on the same
  // three host-relative axes as its host (RAM/CPU/disk, % of the machine), one
  // categorical colour held across all three. Server-side assembly leaves the axes
  // null (no server sampler); the browser fills RAM from its poll. Built against
  // 032 alone, native consumers have no attributable disk (— on that axis) and the
  // docker/system buckets are not populated yet (spec-033) — rendered honestly:
  // absent is —, never a silent 0. All text reaches the DOM via textContent (h()).

  // The categorical palette (spec-034 / tokens.css): ~5 hues cycled across a
  // machine's named consumers, a neutral for the docker bucket, transparent for
  // system/free. Colour is ALWAYS backed by the labelled legend (WCAG AA).
  var CONSUMER_HUES = ["--c-1", "--c-2", "--c-3", "--c-4", "--c-5"];
  function consumerColorVar(c) { return "var(" + (c._hue || "--c-docker") + ")"; }

  /** A 0..100 axis value as text; null/NaN → an em dash (never a silent 0). */
  function pctText(n) { return (n == null || isNaN(n)) ? "—" : Math.round(n) + "%"; }
  function clampPct(n) { return Math.max(0, Math.min(100, n)); }

  /**
   * Build one machine's consumer models (spec-034). Each MonitorConsumerView (032)
   * is joined by name to the 029 per-app rollup so the client still has the probe
   * metadata the 032 contract does not carry — framework, port, fan-out checks, and
   * app-ops — while role/source/dedication/owner/usedBy/bucket/services come from
   * the consumer. A stable categorical colour is assigned per named consumer here so
   * it holds across all three axes and across re-renders. The axis fields are copied
   * so the poll can mutate them in place without touching the wire objects.
   */
  function buildConsumers(m) {
    var appsByName = {};
    (m.apps || []).forEach(function (a) { appsByName[a.appName] = a; });
    var hueIdx = 0;
    return (m.consumers || []).map(function (c) {
      var app = appsByName[c.id] || appsByName[c.name] || null;
      var model = {
        id: c.id,
        name: c.name,
        role: c.role,
        source: c.source,
        dedication: c.dedication || null,
        owner: c.owner || null,
        usedBy: c.usedBy || [],
        bucket: c.bucket || null,
        ram: c.ram,
        cpu: c.cpu,
        disk: c.disk,
        services: c.services || [],
        framework: app ? (app.framework || "generic") : (c.role === "DATABASE" ? "datastore" : "generic"),
        runtime: app ? app.runtime : null,
        port: app ? app.port : null,
        managementPort: app ? app.managementPort : null,
        checks: app ? (app.checks || []) : [],
        ops: app ? (app.ops || []) : []
      };
      if (model.bucket === "SYSTEM") model._hue = "--c-system";
      else if (model.bucket === "DOCKER") model._hue = "--c-docker";
      else { model._hue = CONSUMER_HUES[hueIdx % CONSUMER_HUES.length]; hueIdx++; }
      return model;
    });
  }

  /**
   * Synthesize the client-side "other / system" segment (spec-041). Per axis:
   * `other = clamp(host_used% − Σ attributed_consumer_pct, 0, 100)`, where `hostUsed`
   * carries the machine's real RAM/CPU/disk-in-use percentages (RAM used/total*100, CPU
   * `100 − idle`, disk Use%) and `named` are the currently-rendered attributed consumers.
   * An absent host vital ⇒ that axis is null (renders — not a bogus 0). Returns null when
   * every axis is absent (no host vital at all) so a bare host with no vitals shows no
   * phantom segment. The result fills the SYSTEM bucket (ConsumerRole.OTHER, `--c-system`)
   * and is drawn by default — it is what makes an app-less host read as used, not idle.
   */
  function computeOther(machineId, hostUsed, named) {
    function attr(axis) {
      var s = 0;
      (named || []).forEach(function (c) { if (c[axis] != null) s += c[axis]; });
      return s;
    }
    function seg(host, axis) { return host == null ? null : clampPct(host - attr(axis)); }
    var ram = seg(hostUsed.ram, "ram"), cpu = seg(hostUsed.cpu, "cpu"), disk = seg(hostUsed.disk, "disk");
    if (ram == null && cpu == null && disk == null) return null;
    return {
      id: "__other:" + machineId, name: "other / system", role: "OTHER", source: "HOST",
      bucket: "SYSTEM", _hue: "--c-system", _synthetic: true,
      ram: ram, cpu: cpu, disk: disk, services: [], framework: "system", checks: []
    };
  }

  /**
   * A segmented axis meter (spec-034): the .meter-track sliced one coloured segment
   * per consumer for the given axis, then a hatched free remainder to 100 %. The
   * aggregate "used %" adopts the meterBand thresholds (amber >=75, red >=90). A
   * segment (and every legend chip) opens the consumer drawer.
   */
  function axisMeter(label, consumers, axis, onOpen) {
    var total = 0;
    consumers.forEach(function (c) { if (c[axis] != null) total += c[axis]; });
    var track = h("div", { class: "meter-track axis-track", role: "img",
      "aria-label": label + " — used " + pctText(total) + " of host" });
    consumers.forEach(function (c) {
      var v = c[axis];
      if (v == null || v <= 0) return;
      var seg = h("div", { class: "axis-seg", "data-cid": c.id,
        title: c.name + " · " + pctText(v) + " of host " + label });
      seg.style.width = clampPct(v) + "%";
      seg.style.background = consumerColorVar(c);
      if (onOpen) seg.addEventListener("click", function () { onOpen(c.id); });
      track.appendChild(seg);
    });
    var free = Math.max(0, 100 - total);
    if (free > 0.3) {
      var f = h("div", { class: "axis-seg axis-seg--free", title: "unshown / free · " + pctText(free) });
      f.style.width = free + "%";
      track.appendChild(f);
    }
    var band = meterBand(total);
    return h("div", { class: "meter" },
      h("div", { class: "meter-head" },
        h("span", { class: "meter-label", text: label }),
        h("span", { class: "meter-val mono" + (band === "amber" ? " meter-val--amber" : band === "red" ? " meter-val--red" : ""),
          text: "used " + pctText(total) })),
      track);
  }

  /**
   * The mandatory legend (spec-034): one clickable chip per consumer — colour swatch
   * PLUS name PLUS its three axis values — so the segment colours are never the sole
   * signal (WCAG AA, the house rule). A chip opens the consumer drawer.
   */
  function consumerLegend(consumers, onOpen) {
    return h("div", { class: "legend" }, consumers.map(function (c) {
      var dot = h("span", { class: "legend-dot" });
      dot.style.background = consumerColorVar(c);
      // spec-059: a legend chip whose RAM or Disk axis is a bounded estimate carries a
      // small "est." tag, so the confidence caveat rides the labelled legend too (WCAG AA).
      var lowMark = (axisLow(c, "ram") || axisLow(c, "disk"))
        ? h("span", { class: "tag tag--low lg-low", title: "One or more axes are a bounded estimate (spec-057).", text: "est." })
        : null;
      return h("button", { type: "button", class: "legend-chip", "data-cid": c.id,
        title: "Open " + c.name, onclick: function () { onOpen(c.id); } },
        dot,
        h("span", { text: c.name }),
        h("span", { class: "lg-pct", text: pctText(c.ram) + " · " + pctText(c.cpu) + " · " + pctText(c.disk) }),
        lowMark);
    }));
  }

  /**
   * spec-059 degrade-and-label: a footprint axis whose spec-057 probe fell back to a
   * bounded estimate carries a low-confidence flag on the consumer — RAM's `_ramLow`
   * (VmRSS upper bound when procfs denied a PSS read) and Disk's `_diskLow` (a
   * `du --max-depth=1` lower bound when the full walk timed out). CPU has no fallback
   * kind, so no low flag. Returns true only for a KNOWN axis value that is bounded.
   */
  function axisLow(consumer, key) {
    return (key === "ram" && consumer._ramLow === true)
      || (key === "disk" && consumer._diskLow === true);
  }

  /** The human note for a bounded axis — a label, never colour alone (WCAG AA). */
  function axisConfText(key) {
    return key === "ram"
      ? "≈ RSS upper bound — procfs denied a PSS read"
      : "≈ du lower bound — the disk walk timed out";
  }

  /**
   * spec-059: a consumer axis rendered as the reused .meter, with the spec-057
   * degrade-and-label confidence note appended when the axis value is a bounded
   * estimate (`_ramLow`/`_diskLow`). The note is text — so the caveat is never
   * signalled by colour alone. A precise (un-flagged) reading renders the bare meter.
   */
  function confMeter(label, consumer, sub) {
    var key = label.toLowerCase();
    var el = meter(label, consumer[key], sub);
    if (consumer[key] != null && axisLow(consumer, key)) {
      el.appendChild(h("div", { class: "meter-conf",
        title: "Degrade-and-label (spec-057): a bounded fallback reading, not an exact figure." },
        h("span", { class: "tag tag--low", text: "low confidence" }),
        h("span", { class: "meter-conf-note", text: axisConfText(key) })));
    }
    return el;
  }

  /**
   * One axis meter on a consumer card. A known value renders the reused .meter
   * (RAM carries its RSS as the sub-line); a null value renders — with an honest
   * note: an axis with no approved monitor says so, everything else is simply
   * "no data" yet. (spec-057 fills the native disk axis via the du probe, so it is
   * no longer permanently "n/a"; spec-059 renders its degrade-and-label confidence.)
   */
  function consumerAxis(label, consumer) {
    var key = label.toLowerCase();
    var pct = consumer[key];
    if (pct == null) {
      var note = (consumer._anyApproved === false) ? "approve to see" : "no data";
      return h("div", { class: "meter" },
        h("div", { class: "meter-head" },
          h("span", { class: "meter-label", text: label }),
          h("span", { class: "meter-val mono", text: "—" })),
        h("div", { class: "meter-sub mono", text: note }));
    }
    var sub = (key === "ram" && consumer._rssMb != null) ? (mibText(consumer._rssMb) + " RSS") : null;
    return confMeter(label, consumer, sub);
  }

  /**
   * One consumer card (spec-034): framework badge (+ "actuator-less" for the http
   * family), UP/DOWN pill rolled up from the probes, all THREE axes, the responded
   * checks as chips, and the matched app-ops. The name is a fleet filter toggle; the
   * card body opens the drawer. Rebuilt from the consumer's current state on paint().
   */
  function consumerCard(consumer, onToggle, onOpen) {
    var fw = consumer.framework || "generic";
    var badge = h("span", { class: "fw-badge fw-badge--" + fw, text: fw });
    var actuatorless = fw === "http"
      ? h("span", { class: "tag", title: "No actuator responded; liveness via GET /", text: "actuator-less" })
      : null;
    var up = consumer._up;
    var pill = up == null
      ? h("span", { class: "pill pill--unknown", text: "no data" })
      : h("span", { class: "pill pill--" + (up ? "up" : "down"), text: up ? "UP" : "DOWN" });
    // spec-067: on the single-machine dashboard there is no fleet to filter, so the caller
    // passes no onToggle and the name renders as plain text rather than a filter button.
    var name = onToggle
      ? h("button", { type: "button", class: "app-name-toggle",
          title: "Filter the fleet to " + consumer.name, text: consumer.name,
          onclick: function (e) { e.stopPropagation(); onToggle(consumer.name); } })
      : h("span", { class: "app-name-static", text: consumer.name });
    var runtimeTag = h("span", { class: "tag mono",
      text: (consumer.runtime || (consumer.source === "DOCKER" ? "docker" : "process"))
        + (consumer.port != null ? " :" + consumer.port : "")
        // spec-073: a Spring Boot app whose actuator lives on a separate management.server.port
        // shows both — ":8080 · mgmt :8081" — so the operator sees one app, two sockets.
        + (consumer.managementPort != null && consumer.managementPort !== consumer.port
            ? " · mgmt :" + consumer.managementPort : "") });
    var axes = h("div", { class: "d-axes mt-2" },
      consumerAxis("RAM", consumer), consumerAxis("CPU", consumer), consumerAxis("Disk", consumer));
    var states = consumer._checkStates || [];
    var checks = states.length
      ? h("div", { class: "run-chip-row mt-3" }, states.map(function (r) {
          var cls = r.state === "up" ? "chip chip--ok" : r.state === "down" ? "chip chip--bad" : "chip chip--neutral";
          return h("span", { class: cls, title: r.name + " (" + humanize(r.state) + ")", text: r.name });
        }))
      : null;
    var ops = (consumer.ops || []).length
      ? h("div", { class: "run-chip-row mt-2" }, consumer.ops.map(function (op) { return opsRunChip(op, consumer.name); }))
      : null;
    var card = h("div", { class: "app-card" },
      h("div", { class: "row-between" },
        h("div", { class: "grow" }, name, h("div", { class: "row mt-2" }, badge, actuatorless, pill)),
        runtimeTag),
      axes, checks, ops);
    card.addEventListener("click", function (e) {
      if (e.target.closest(".run-chip") || e.target.closest(".app-name-toggle")) return;
      onOpen(consumer.id);
    });
    return card;
  }

  // ---- databases lens (spec-034 §5) ----------------------------------------
  // One lens, two bands. Dedicated datastores (one owner → attributable) show the
  // owner split per axis; shared datastores (many users, no owner) show "used by"
  // chips and NO per-app split. It is a re-slice of the SAME consumers, not a move.

  // spec-038: a compose project is ONE consumer whose services carry its datastores, so
  // the Dedicated band is derived from each app project's role=DATABASE SERVICES (owner =
  // the project), one slice per datastore — preserving the per-datastore axis split. The
  // Shared band stays the top-level DATABASE consumers (standalone datastores + datastore-
  // only projects). It is a re-slice of the SAME containers the Apps view shows.
  function datastoresOf(consumers) {
    var ded = [], shared = [];
    consumers.forEach(function (c) {
      if (c.role === "DATABASE") { shared.push(c); return; }
      (c.services || []).forEach(function (s) {
        if (s.role !== "DATABASE") return;
        ded.push({ id: c.id, owner: c.name, name: s.name, image: s.image,
          ram: s.ram, cpu: s.cpu, disk: s.disk, _hue: c._hue });
      });
    });
    return { ded: ded, shared: shared };
  }

  // A dedicated-datastore label: its own service name, prefixed with the owning project
  // when the name doesn't already carry it — so two datastores of one project (e.g. a
  // postgres + a redis) are told apart in the split (spec-038).
  function dedLabel(i) {
    if (i.owner && i.name && i.name.indexOf(i.owner) < 0) return i.owner + " / " + i.name;
    return i.name || i.owner || "";
  }

  /** A segmented split of the dedicated datastores on one axis, coloured per owner. */
  function splitMeter(axis, items) {
    var total = 0;
    items.forEach(function (i) { if (i[axis] != null) total += i[axis]; });
    var track = h("div", { class: "meter-track axis-track" });
    items.forEach(function (i) {
      var v = i[axis];
      if (v == null || v <= 0) return;
      var seg = h("div", { class: "axis-seg", title: dedLabel(i) + " · " + pctText(v) + " of host" });
      seg.style.width = (total > 0 ? (v / total * 100) : 0) + "%";
      seg.style.background = consumerColorVar(i);
      track.appendChild(seg);
    });
    var rows = items.map(function (i) {
      var share = (total > 0 && i[axis] != null) ? Math.round(i[axis] / total * 100) : null;
      var dot = h("span", { class: "legend-dot" });
      dot.style.background = consumerColorVar(i);
      return h("span", { class: "row", style: "gap:6px" }, dot,
        dedLabel(i) + " " + (share == null ? "—" : share + "%"));
    });
    return h("div", { class: "meter" },
      h("div", { class: "meter-head" },
        h("span", { class: "meter-label", text: axis.toUpperCase() + " — " + pctText(total) + " of host" })),
      track,
      h("div", { class: "split-legend" }, rows));
  }

  /** One shared-datastore row: no per-app split, just its "used by" chips + axes. */
  function sharedRow(c, onOpen) {
    var usedBy = (c.usedBy || []).map(function (a) { return h("span", { class: "tag", text: a }); });
    function ax(key, label) {
      var na = c[key] == null;
      return h("div", { class: "dax" },
        h("div", { class: "k", text: label }),
        h("div", { class: "v" + (na ? " na" : ""), text: pctText(c[key]) }));
    }
    var dot = h("span", { class: "legend-dot" });
    dot.style.background = consumerColorVar(c);
    var row = h("div", { class: "drow drow--click" }, dot,
      h("div", { class: "grow" },
        h("div", { style: "font-weight:600", text: c.name }),
        usedBy.length
          ? h("div", { class: "small dim row mt-2" }, h("span", { text: "used by" }), usedBy)
          : h("div", { class: "small dim", text: "no dependents recorded" })),
      h("div", { class: "daxes" }, ax("ram", "RAM"), ax("cpu", "CPU"), ax("disk", "DISK")));
    row.addEventListener("click", function () { onOpen(c.id); });
    return row;
  }

  function renderDbInto(wrap, datastores, onOpen) {
    var d = datastoresOf(datastores);
    if (!d.ded.length && !d.shared.length) {
      wrap.appendChild(empty("No datastores detected on this host."));
      return;
    }
    wrap.appendChild(h("p", { class: "small dim mt-2",
      text: "The same consumers as the Apps view, re-sliced by datastore role — a re-slice, not a move." }));
    if (d.ded.length) {
      wrap.appendChild(h("div", { class: "card mt-3" },
        h("div", { class: "row" },
          h("span", { class: "band-title", text: "Dedicated" }),
          h("span", { class: "tag", text: "one owner → resource is attributable, so we show the split" })),
        h("div", { class: "d-axes mt-3" },
          splitMeter("ram", d.ded), splitMeter("cpu", d.ded), splitMeter("disk", d.ded))));
    }
    if (d.shared.length) {
      wrap.appendChild(h("div", { class: "card mt-3" },
        h("div", { class: "row" },
          h("span", { class: "band-title", text: "Shared" }),
          h("span", { class: "tag", text: "many users, no owner → NO per-app split; we show who uses it" })),
        d.shared.map(function (c) { return sharedRow(c, onOpen); })));
    }
  }

  // ---- consumer poll (spec-034) --------------------------------------------
  // Group the visible consumers by their (shared) fan-out check actions so each
  // check runs ONCE over all apps that declare it, then distribute the results and
  // fill each consumer's RAM axis (RSS / host total) and CPU axis (process-tree
  // %cpu ÷ host cores, spec-039) + UP/DOWN + probe states.

  function pollConsumers(machineId, consumers, hostTotal, cores, diskBytes) {
    var pollable = consumers.filter(function (c) { return (c.checks || []).length && c.port != null; });
    if (!pollable.length) return Promise.resolve();
    var groups = {};
    pollable.forEach(function (c) {
      (c.checks || []).forEach(function (chk) {
        if (chk.approvalState !== "APPROVED" || chk.changedSinceApproval) return;
        // The sudo re-probe variants (spec-057 Decision 6) are on-demand controls, never
        // auto-run in the poll loop — they run only when the operator triggers them (spec-059).
        if (chk.sudo) return;
        if (!appPortListParamName(chk)) return;
        var g = groups[chk.id] || (groups[chk.id] = { action: chk, apps: [], seen: {} });
        if (!g.seen[c.name]) { g.seen[c.name] = true; g.apps.push({ appName: c.name, port: c.port }); }
      });
    });
    var ids = Object.keys(groups);
    if (!ids.length) {
      pollable.forEach(function (c) { applyConsumerReading(c, null, hostTotal, cores, diskBytes); });
      return Promise.resolve();
    }
    var outputs = {}; // checkId → (appName → { stdout, exit })
    return Promise.all(ids.map(function (id) {
      return runProbeForApps(machineId, groups[id].action, groups[id].apps)
        .then(function (byApp) { outputs[id] = byApp; });
    })).then(function () {
      pollable.forEach(function (c) { applyConsumerReading(c, outputs, hostTotal, cores, diskBytes); });
    });
  }

  /**
   * Fold one consumer's probe outputs into its live state: the process probe's VmRSS
   * (summed → MB) over the host total gives the RAM axis; the cpu probe's process-tree
   * %CPU (summed → ÷ the host core count, spec-039) gives the CPU axis, mirroring the
   * docker path's `sumCpu / denom.cores`; liveness/process rolls up to UP/DOWN; each
   * check keeps its responded state (na = did not respond, so it is omitted from the
   * drawer's probe list — probe honesty, spec-034 §6). Disk is the native `du` footprint
   * (spec-057) over the root-FS denominator — a du-timeout lower bound still fills it but
   * flags `_diskLow`, mirroring the RAM RSS-fallback's `_ramLow` (059's confidence badge).
   * An absent cpu reading or an unknown `cores` leaves CPU null → — (honesty rule, never a
   * silent 0).
   */
  function applyConsumerReading(c, outputs, hostTotal, cores, diskBytes) {
    var livenessUp = null, processUp = null, rssMb = null, pssMb = null, ramLow = false;
    var cpuRaw = null, duBytes = null, diskLow = false, rows = [];
    (c.checks || []).forEach(function (chk) {
      var res = outputs && outputs[chk.id] && outputs[chk.id][c.name];
      var kind = checkKind(chk);
      var state = "na";
      if (res) {
        if (kind === "process") {
          var rss = parseRssMb(res.stdout);
          if (rss != null) rssMb = (rssMb == null ? 0 : rssMb) + rss;
          var listener = !!(res.stdout && res.stdout.indexOf("no listener") < 0 && /VmRSS/i.test(res.stdout));
          processUp = listener;
          state = listener ? "up" : "down";
        } else if (kind === "ram") {
          // Honest RAM: PSS summed to the context (spec-057). On procfs denial the probe
          // falls back to VmRSS + a low-confidence label — a ≤ upper bound, never the same
          // metric — so parsePss is null and we take the RSS value, flagged.
          var pss = parsePss(res.stdout);
          if (pss != null) { pssMb = (pssMb == null ? 0 : pssMb) + pss; }
          else {
            var rrss = parseRssMb(res.stdout);
            if (rrss != null) { pssMb = (pssMb == null ? 0 : pssMb) + rrss; ramLow = true; }
          }
          if (ramConfidenceLow(res.stdout)) ramLow = true;
          state = res.stdout && res.stdout.indexOf("no listener") < 0 ? "up" : "down";
        } else if (kind === "cpu") {
          // A RATE now (spec-057), not the lifetime average: Δ jiffies over the measured Δt.
          var pct = parseStatTicks(res.stdout);
          if (pct != null) cpuRaw = (cpuRaw == null ? 0 : cpuRaw) + pct;
          state = res.stdout && res.stdout.indexOf("no listener") < 0 ? "up" : "down";
        } else if (kind === "disk") {
          // Native disk footprint (spec-057), finally attributed: root-FS bytes / the same
          // root-FS denominator docker uses, so it subtracts cleanly from OTHER (spec-041).
          var b = parseDuBytes(res.stdout);
          if (b != null) duBytes = (duBytes == null ? 0 : duBytes) + b;
          // du-timeout degrade (spec-057 Decision 6): the value is a labelled LOWER bound, so
          // flag it — the axis still fills, but 059 badges it low, as RAM's RSS fallback is.
          if (res.stdout && res.stdout.indexOf("disk_confidence=low") >= 0) diskLow = true;
          state = b != null ? "up" : "na";
        } else if (kind === "liveness") {
          livenessUp = res.exit === 0;
          state = livenessUp ? "up" : "down";
        } else {
          state = res.exit === 0 ? "up" : "down";
        }
      }
      rows.push({ name: chk.name, state: state });
    });
    c._checkStates = rows;
    c._up = livenessUp != null ? livenessUp : processUp;
    c._anyApproved = (c.checks || []).some(function (x) {
      return x.approvalState === "APPROVED" && !x.changedSinceApproval;
    });
    // RAM axis prefers the honest PSS figure; falls back to the process probe's RSS when no
    // ram probe is present. _rssMb still carries RSS for the card's sub-line; _ramLow marks a
    // degraded (RSS upper-bound) reading for 059's confidence badge.
    c._rssMb = rssMb;
    c._ramLow = ramLow;
    var ramMb = pssMb != null ? pssMb : rssMb;
    c.ram = clientMemPct(ramMb, hostTotal);
    // % of host: cross-core cpu-rate ÷ logical cores (the same denom.cores docker uses);
    // native disk: du bytes ÷ the root-FS denominator. Absent reading / unknown denominator →
    // leave the axis null (—), never a silent 0 that would wrongly shrink OTHER.
    c.cpu = (cpuRaw != null && cores) ? clampPct(Math.round(cpuRaw / cores)) : c.cpu;
    c.disk = (duBytes != null && diskBytes) ? clampPct(Math.round(duBytes / diskBytes * 100)) : c.disk;
    c._diskLow = diskLow;
  }

  // ---- docker consumer poll (spec-037) -------------------------------------
  // 034's pollConsumers only drives the native (app-name,port) APP_PORT_LIST
  // fan-out, so a docker consumer (no port, no fan-out check) never gets metrics.
  // This is the missing path: run each APPROVED, un-drifted, PARAM-FREE docker
  // MONITOR check (docker stats / ps -s / system df -v) ONCE per machine — they
  // enumerate every container in one read — parse with the 033 parsers, then
  // aggregate per container up to the consumer via its services[] identity and
  // normalize each axis to % of host (RAM ÷ host total, CPU ÷ nproc, disk ÷
  // data-root FS). Absent parse or denominator → the axis stays — (honesty rule).

  function pollDockerConsumers(m, consumers, denom) {
    var dockerConsumers = consumers.filter(function (c) {
      return c.source === "DOCKER" && (c.services || []).length;
    });
    if (!dockerConsumers.length) return Promise.resolve();
    var checks = (m.hostActions || []).filter(function (a) {
      return metricKind(a) === "docker" && a.approvalState === "APPROVED"
        && !a.changedSinceApproval && (a.paramDefs || []).length === 0;
    });
    if (!checks.length) return Promise.resolve();
    return Promise.all(checks.map(function (a) {
      return runAndCollect(m.machineId, a.id, {})
        .then(function (r) { return { action: a, stdout: r.stdout }; })
        .catch(function () { return { action: a, stdout: "" }; });
    })).then(function (results) {
      var stats = null, ps = null, vol = null;
      results.forEach(function (res) {
        var n = (res.action.name || "").toLowerCase();
        if (n.indexOf("stat") >= 0) stats = parseDockerStats(res.stdout);
        else if (n.indexOf("vol") >= 0 || n.indexOf("system") >= 0 || n.indexOf("df") >= 0)
          vol = parseDockerVolumes(res.stdout);
        else ps = parseDockerPs(res.stdout);   // "docker disk" → docker ps -s writable layer
      });
      dockerConsumers.forEach(function (c) { applyDockerReading(c, stats, ps, vol, denom); });
    });
  }

  /** Index parsed docker rows by container name for the per-consumer join. */
  function indexByName(rows) {
    var by = {};
    (rows || []).forEach(function (r) { if (r.name) by[r.name] = r; });
    return by;
  }

  /**
   * Fold one docker consumer's container readings into its axes (spec-037). Sums the
   * consumer's services[] (a compose project sums its members; a standalone datastore
   * is 1:1): RAM from `docker stats` MemUsage ABSOLUTE bytes (not MemPerc, which is
   * container-limit-relative), CPU from `docker stats` CPUPerc (sums cores ⇒ ÷ nproc),
   * disk from `docker ps -s` writable-layer bytes plus this project's named volumes
   * (`<project>_…`). Each axis is a % of host, clamped 0..100, and set ONLY when both
   * numerator and denominator are present — otherwise it stays — (never a silent 0).
   * Per-service axes are filled too so the drawer's service rows read numeric.
   */
  function applyDockerReading(c, stats, ps, vol, denom) {
    var statBy = indexByName(stats), psBy = indexByName(ps);
    var sumMemBytes = 0, memSeen = false;
    var sumCpu = 0, cpuSeen = false;
    var sumDiskBytes = 0, diskSeen = false;
    var anyContainer = false, statsRan = !!stats, diskRan = !!ps;
    (c.services || []).forEach(function (s) {
      var st = statBy[s.name], p = psBy[s.name];
      if (st || p) anyContainer = true;
      var memBytes = st ? dockerBytes(st.memUsage) : null;
      var cpuRaw = st ? st.cpu : null;               // percent, already summed over cores
      var diskBytes = p ? dockerBytes(p.size) : null;
      if (memBytes != null) { sumMemBytes += memBytes; memSeen = true; }
      if (cpuRaw != null) { sumCpu += cpuRaw; cpuSeen = true; }
      if (diskBytes != null) { sumDiskBytes += diskBytes; diskSeen = true; }
      s.ram = (memBytes != null && denom.ramMb) ? clampPct(Math.round(memBytes / 1048576 / denom.ramMb * 100)) : s.ram;
      s.cpu = (cpuRaw != null && denom.cores) ? clampPct(Math.round(cpuRaw / denom.cores)) : s.cpu;
      s.disk = (diskBytes != null && denom.diskBytes) ? clampPct(Math.round(diskBytes / denom.diskBytes * 100)) : s.disk;
    });
    // Named volumes attributed to this compose project by the <project>_… convention
    // (best-effort — docker system df -v gives a link count, not which container; a
    // shared volume across projects is deliberately not split, spec-032 §4 / Known Gaps).
    if (vol && c.id) {
      var prefix = c.id + "_";
      vol.forEach(function (v) {
        if (v.name && v.name.indexOf(prefix) === 0 && v.bytes != null) { sumDiskBytes += v.bytes; diskSeen = true; }
      });
    }
    if (memSeen && denom.ramMb) c.ram = clampPct(Math.round(sumMemBytes / 1048576 / denom.ramMb * 100));
    if (cpuSeen && denom.cores) c.cpu = clampPct(Math.round(sumCpu / denom.cores));
    if (diskSeen && denom.diskBytes) c.disk = clampPct(Math.round(sumDiskBytes / denom.diskBytes * 100));
    // A docker consumer with a running container reads UP; the two docker reads become
    // the responded probe chips (only those that produced data show — probe honesty).
    if (anyContainer) c._up = true;
    else if (statsRan || diskRan) c._up = false;
    c._anyApproved = statsRan || diskRan;
    c._checkStates = [
      { name: "docker stats", state: statsRan ? (anyContainer && memSeen ? "up" : "na") : "na" },
      { name: "docker disk", state: diskRan ? (diskSeen ? "up" : "na") : "na" }
    ].filter(function (r) { return r.state !== "na"; });
  }

  /**
   * The per-consumer detail drawer (spec-034, replacing openAppDrawer): the tri-axis
   * readout, the owner / used-by line for a datastore, the services breakdown (docker
   * containers, spec-033), the responded-only probe list, and the compose block. All
   * facts come from the consumer; native consumers have no services and no compose.
   */
  function openConsumerDrawer(machine, c) {
    var badges = [
      h("span", { class: "fw-badge fw-badge--" + (c.framework || "generic"), text: c.framework || "generic" }),
      h("span", { class: "tag", text: (c.source || "").toLowerCase() }),
      c.role === "DATABASE" ? h("span", { class: "tag", text: c.dedication === "SHARED" ? "shared" : "dedicated" }) : null,
      (c.bucket && c.role !== "OTHER") ? h("span", { class: "tag", text: "hidden bucket" }) : null
    ];
    // The synthesized OTHER/system segment (spec-041) is an estimate, not a monitored
    // consumer — say so honestly in its drawer rather than implying a precise figure.
    var otherNote = c.role === "OTHER"
      ? h("p", { class: "small dim mt-2",
          text: "unattributed system usage (approximate) — host used minus the sum of attributed consumers, clamped at zero. Not an exact accounting; it absorbs OS overhead and RSS-vs-free slop." })
      : null;
    var diskMeter = c.disk == null
      ? h("div", { class: "meter" },
          h("div", { class: "meter-head" },
            h("span", { class: "meter-label", text: "Disk" }),
            h("span", { class: "meter-val mono", text: "—" })),
          h("div", { class: "meter-sub mono",
            text: c.source === "DOCKER" ? "n/a" : "native process — no attributable disk footprint" }))
      : confMeter("Disk", c);
    var owner = c.role === "DATABASE"
      ? (c.dedication === "SHARED"
          ? h("dl", { class: "kv mt-2" }, h("dt", { text: "used by" }),
              h("dd", { text: (c.usedBy || []).join(", ") + " — shared engine, resource not split per app" }))
          : (c.owner ? h("dl", { class: "kv mt-2" }, h("dt", { text: "owned by" }), h("dd", { text: c.owner })) : null))
      : null;
    var services = (c.services || []).length
      ? [h("h3", { class: "mt-4", text: "Services" }), h("div", { class: "mt-2" }, c.services.map(serviceRow))]
      : null;
    var responded = (c._checkStates || []).filter(function (r) { return r.state !== "na"; });
    var probes = responded.length
      ? [h("h3", { class: "mt-4", text: "Probes" }),
         h("div", { class: "run-chip-row mt-2" }, responded.map(function (r) {
           return h("span", { class: "chip " + (r.state === "up" ? "chip--ok" : "chip--bad"), text: r.name });
         })),
         h("p", { class: "small faint mt-2",
           text: "Only probes that responded are shown — a springboot exposing just /actuator/health shows only health." })]
      : [h("h3", { class: "mt-4", text: "Probes" }),
         (c.checks || []).length
           ? h("p", { class: "small dim", text: "No probe has responded yet — run the monitor to populate." })
           : h("p", { class: "small dim", text: "Aggregate bucket — not a monitored app." })];
    var compose = c.source === "DOCKER"
      ? [h("h3", { class: "mt-4", text: "Compose" }),
         h("p", { class: "small dim mt-2" }, "Grouped by ",
           h("span", { class: "mono", text: "com.docker.compose.project" }),
           " label; project file not reachable from this host (best-effort).")]
      : null;

    var drawer = h("div", { class: "drawer", role: "dialog", "aria-modal": "true", "aria-label": c.name },
      h("div", { class: "row-between" },
        h("h2", { text: c.name }),
        h("button", { class: "btn btn--sm", onclick: closeDrawer }, "Close")),
      h("div", { class: "row mt-2" }, badges),
      h("p", { class: "small dim mt-2", text: machine.loginUser + "@" + machine.host + ":" + machine.port }),
      h("div", { class: "d-axes mt-4" },
        confMeter("RAM", c, (c._rssMb != null ? mibText(c._rssMb) + " RSS" : null)),
        meter("CPU", c.cpu), diskMeter),
      otherNote, owner, services, probes, compose);
    var backdrop = h("div", { class: "drawer-backdrop", onclick: function (e) {
      if (e.target === backdrop) closeDrawer();
    } }, drawer);
    var root = byId("modal-root");
    clear(root);
    root.appendChild(backdrop);
  }

  /** One service (docker container, spec-033) inside a consumer, with its own axes. */
  function serviceRow(s) {
    function ax(key, label) {
      var na = s[key] == null;
      return h("div", { class: "dax" },
        h("div", { class: "k", text: label }),
        h("div", { class: "v" + (na ? " na" : ""), text: pctText(s[key]) }));
    }
    // A role=DATABASE service is a datastore dedicated to this project (spec-038) — tag it
    // so the drawer reads the datastore as a member of the project, not a stray container.
    var roleTag = s.role === "DATABASE"
      ? h("span", { class: "tag", style: "margin-left:6px", text: "database" })
      : null;
    return h("div", { class: "drow" },
      h("div", { class: "grow" },
        h("div", { style: "font-weight:600" }, s.name, roleTag),
        h("div", { class: "small dim mono", text: s.image || "" })),
      h("div", { class: "daxes" }, ax("ram", "RAM"), ax("cpu", "CPU"), ax("disk", "DISK")));
  }

  function closeDrawer() { clear(byId("modal-root")); }

  // =========================================================== ROUTER =======

  var ROUTES = [
    { re: /^\/?$/, fn: function () { location.hash = "#/machines"; } },
    { re: /^\/machines$/, fn: screenMachines, nav: "machines" },
    { re: /^\/machines\/register$/, fn: screenRegisterMachine, nav: "machines" },
    { re: /^\/machines\/([^/]+)$/, fn: function (m) { screenMachineDetail({ mid: m[1] }); }, nav: "machines" },
    { re: /^\/monitor$/, fn: screenMonitor, nav: "monitor" },
    { re: /^\/machines\/([^/]+)\/recipes\/([^/]+)\/actions\/([^/]+)\/run$/,
      fn: function (m, q) { screenRunForm({ mid: m[1], rid: m[2], aid: m[3] }, q); }, nav: "machines" },
    { re: /^\/machines\/([^/]+)\/recipes\/([^/]+)\/actions\/([^/]+)$/,
      fn: function (m) { screenApproval({ mid: m[1], rid: m[2], aid: m[3] }); }, nav: "machines" },
    { re: /^\/runs$/, fn: screenRuns, nav: "runs" },
    { re: /^\/runs\/([^/]+)$/, fn: function (m) { screenRunView({ id: m[1] }); }, nav: "runs" },
    { re: /^\/blueprints$/, fn: screenBlueprints, nav: "blueprints" },
    { re: /^\/blueprints\/([^/]+)$/, fn: function (m) { screenBlueprintDetail({ bid: m[1] }); }, nav: "blueprints" },
    { re: /^\/mcp$/, fn: screenMcp, nav: "mcp" },
    { re: /^\/tokens$/, fn: screenTokens, nav: "tokens" },
    { re: /^\/appkey$/, fn: screenAppKey, nav: "appkey" },
    { re: /^\/setup\/([^/?]+)$/, fn: function (m, q) { screenSetup({ code: decodeURIComponent(m[1]) }, q); } },
    { re: /^\/setup$/, fn: function (m, q) { screenSetup({}, q); } }
  ];

  function parseHash() {
    var raw = location.hash.replace(/^#/, "");
    var qIndex = raw.indexOf("?");
    var path = qIndex >= 0 ? raw.slice(0, qIndex) : raw;
    var query = {};
    if (qIndex >= 0) {
      raw.slice(qIndex + 1).split("&").forEach(function (kv) {
        var pair = kv.split("=");
        if (pair[0]) query[decodeURIComponent(pair[0])] = decodeURIComponent(pair[1] || "");
      });
    }
    return { path: path, query: query };
  }

  function setActiveNav(nav) {
    var links = document.querySelectorAll(".nav a[data-nav]");
    for (var i = 0; i < links.length; i++) {
      links[i].classList.toggle("active", links[i].getAttribute("data-nav") === nav);
    }
  }

  // A view may register a teardown (e.g. the Monitor screen's poll timers). The
  // router runs it before dispatching the next route so no interval leaks across
  // navigations (spec-024: intervals cleared on route-away).
  var currentViewCleanup = null;
  function runViewCleanup() {
    if (currentViewCleanup) {
      try { currentViewCleanup(); } catch (e) { /* never let teardown break routing */ }
      currentViewCleanup = null;
    }
  }

  // spec-043: on phones the primary nav is collapsed behind the "Menu" toggle.
  // Collapse it again on every route change so tapping a link closes the menu.
  function closeNav() {
    var toggle = byId("nav-toggle");
    var nav = byId("nav");
    if (nav) nav.classList.remove("nav--open");
    if (toggle) toggle.setAttribute("aria-expanded", "false");
  }

  function route() {
    runViewCleanup();
    closeNav();
    closeAllMenus();   // spec-044: no split-button menu leaks across a navigation
    closeDrawer();     // spec-044: a review drawer left open closes on route change
    if (!Session.token()) { showLogin(); return; }
    showShell();
    var parsed = parseHash();
    // spec-065: keep the blueprint title-block's "View" cell in sync with the
    // route. It lives outside #view (which is re-rendered per navigation), so it
    // is updated here rather than by any screen renderer. Hidden identities never
    // show it; the write is cheap and harmless.
    var routeCell = byId("titleblock-route");
    if (routeCell) routeCell.textContent = parsed.path.replace(/^\//, "") || "machines";
    for (var i = 0; i < ROUTES.length; i++) {
      var m = parsed.path.match(ROUTES[i].re);
      if (m) {
        setActiveNav(ROUTES[i].nav || null);
        ROUTES[i].fn(m, parsed.query);
        return;
      }
    }
    mount(empty("Not found: " + parsed.path));
  }

  // =========================================================== LOGIN ========

  function showLogin() {
    byId("shell-root").classList.add("hidden");
    var root = byId("login-root");
    root.classList.remove("hidden");
    clear(root);

    var emailField = h("input", { type: "email", placeholder: "you@example.com", "aria-label": "Email", autocomplete: "username" });
    var passwordField = h("input", { type: "password", placeholder: "Password", "aria-label": "Password", autocomplete: "current-password" });
    var nameField = h("input", { type: "text", placeholder: "Display name (optional)", "aria-label": "Display name" });
    var nameRow = h("div", { class: "hidden mt-2" }, h("label", { text: "Name" }), nameField);

    // Two actions on one form: Log in (default) and Register. The name field only
    // matters for registration, so it stays hidden until the user reveals it.
    var loginBtn = h("button", { class: "btn btn--primary", type: "submit" }, "Log in");
    var registerBtn = h("button", { class: "btn mt-2", type: "button" }, "Register");

    function authenticate(path) {
      var email = emailField.value.trim();
      var password = passwordField.value;
      if (!email || !password) { toast("Email and password are required"); return; }
      // Only registration carries a display name; /auth/login accepts email+password
      // only, so never send `name` on the login path.
      var payload = { email: email, password: password };
      if (path === "register") { payload.name = nameField.value.trim() || null; }
      fetch("/api/auth/" + path, {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload)
      }).then(function (res) {
        return res.text().then(function (t) {
          if (!res.ok) {
            var code = "";
            try { code = JSON.parse(t).error || ""; } catch (e) { /* keep empty */ }
            if (res.status === 409 || code === "email_taken") throw new Error("That email is already registered — log in instead.");
            if (res.status === 401) throw new Error("Invalid email or password.");
            throw new Error(code || ("sign-in failed (" + res.status + ")"));
          }
          return JSON.parse(t);
        });
      }).then(function (session) {
        Session.set(session.token, session.user);
        if (!location.hash || location.hash === "#/") location.hash = "#/machines";
        route();
      }).catch(function (err) { toast(err.message); });
    }

    var form = h("form", { class: "login-form", onsubmit: function (e) { e.preventDefault(); authenticate("login"); } },
      h("label", { text: "Email" }),
      emailField,
      h("label", { class: "mt-2", text: "Password" }),
      passwordField,
      nameRow,
      loginBtn,
      registerBtn);

    registerBtn.addEventListener("click", function () {
      if (nameRow.classList.contains("hidden")) {
        // First click reveals the optional name field so the intent is a fresh
        // registration; a second click submits it.
        nameRow.classList.remove("hidden");
        registerBtn.textContent = "Create account";
        return;
      }
      authenticate("register");
    });

    root.appendChild(h("div", { class: "login-screen" },
      h("div", { class: "login-card" },
        h("div", { class: "row" }, h("span", { class: "dot", "aria-hidden": "true" }), h("h1", { text: "compute-admin" })),
        h("p", { class: "lede", text: "Sign in to review and approve operations on your machines. Approval is UI-only — this session is what authorises a run." }),
        form,
        h("p", { class: "xs faint mt-3", text: "New here? Register creates a local account (min 8-character password). No email verification — this is a single local instance." }))));
  }

  function showShell() {
    byId("login-root").classList.add("hidden");
    byId("shell-root").classList.remove("hidden");
    var user = Session.user() || {};
    byId("user-name").textContent = user.name || "";
    byId("user-email").textContent = user.email || "";
  }

  // =========================================================== BOOT =========

  byId("sign-out").addEventListener("click", function () {
    Session.clear();
    location.hash = "";
    showLogin();
  });

  // spec-043: mobile nav toggle. Plain DOM wiring (no innerHTML) — flips the
  // .nav--open class and the button's aria-expanded. Closing on navigation is
  // handled by closeNav() in route().
  (function wireNavToggle() {
    var toggle = byId("nav-toggle");
    var nav = byId("nav");
    if (!toggle || !nav) return;
    toggle.addEventListener("click", function () {
      var open = nav.classList.toggle("nav--open");
      toggle.setAttribute("aria-expanded", open ? "true" : "false");
    });
  })();

  // spec-065: per-viewer visual-identity switcher. The stored identity is already
  // stamped on <html> pre-paint by the inline <head> script; here we reflect the
  // active button and, on click, persist ca.identity + re-stamp so tokens flip
  // live (no reload). All values are whitelisted; ca.identity follows the ca.*
  // localStorage idiom (like ca.jwt / ca.user / ca.runs).
  (function wireIdentitySwitch() {
    var IDENTITIES = ["current", "iskeru", "blueprint"];
    var group = byId("identity-switch");
    // Requires a real element (querySelectorAll/addEventListener); degrade to a
    // no-op otherwise. The pre-paint <head> stamp has already applied the stored
    // identity, so a missing switcher never leaves the page unskinned.
    if (!group || typeof group.querySelectorAll !== "function") return;

    function current() {
      var stored = null;
      try { stored = localStorage.getItem("ca.identity"); } catch (e) { /* ignore */ }
      return IDENTITIES.indexOf(stored) >= 0 ? stored : "current";
    }

    function reflect(id) {
      var buttons = group.querySelectorAll("button[data-identity]");
      for (var i = 0; i < buttons.length; i++) {
        var on = buttons[i].getAttribute("data-identity") === id;
        buttons[i].classList.toggle("on", on);
        buttons[i].setAttribute("aria-pressed", on ? "true" : "false");
      }
    }

    function apply(id) {
      if (IDENTITIES.indexOf(id) < 0) return;
      try { localStorage.setItem("ca.identity", id); } catch (e) { /* non-fatal */ }
      document.documentElement.dataset.identity = id;
      reflect(id);
    }

    group.addEventListener("click", function (e) {
      var btn = e.target.closest ? e.target.closest("button[data-identity]") : null;
      if (!btn) return;
      apply(btn.getAttribute("data-identity"));
    });

    reflect(current());
  })();

  // spec-065: fill the blueprint title-block's static "Date" cell once at boot
  // (the only non-route cell that would otherwise ship stale). A drafting stamp.
  (function stampTitleblockDate() {
    var cell = byId("titleblock-date");
    if (cell) cell.textContent = new Date().toISOString().slice(0, 10);
  })();

  window.addEventListener("hashchange", route);
  route();
})();

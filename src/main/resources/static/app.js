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
    DRAFT: "neutral", UNKNOWN: "neutral", OFFLINE: "neutral"
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
                link("#/machines/" + m.id, m.loginUser + "@" + m.host + ":" + m.port, "mono"),
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
        var host = h("input", { class: "mono", placeholder: "10.0.0.5 or db.internal" });
        var port = h("input", { type: "number", value: "22", min: "1", max: "65535" });
        var user = h("input", { class: "mono", placeholder: "admin" });
        var status = h("div", { class: "mt-3" });
        var snippet = 'echo "' + key.publicKey + '" >> ~/.ssh/authorized_keys';

        function submit() {
          status.textContent = "";
          if (!host.value.trim() || !user.value.trim()) {
            status.appendChild(h("div", { class: "field-error", text: "host and login user are required" }));
            return;
          }
          mount(loading());
          api("POST", "/machines", {
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
            h("div", { class: "field mt-3" }, h("label", { text: "Host" }), host),
            h("div", { class: "field" }, h("label", { text: "Port" }), port),
            h("div", { class: "field" }, h("label", { text: "Login user" }), user),
            h("button", { class: "btn btn--primary", onclick: submit }, "Register & test connection"),
            status));
      });
    });
  }

  // ----- Machine detail -----------------------------------------------------

  function screenMachineDetail(p) {
    var mid = p.mid;
    mountAsync(function () {
      return Promise.all([
        api("GET", "/machines/" + mid),
        api("GET", "/recipes?machineId=" + encodeURIComponent(mid))
      ]).then(function (res) {
        var machine = res[0], recipes = res[1];
        return Promise.all(recipes.map(function (r) {
          return api("GET", "/recipes/" + r.id + "/actions").then(function (actions) {
            return { recipe: r, actions: actions };
          });
        })).then(function (groups) {
          return { machine: machine, groups: groups };
        });
      }).then(function (data) {
        var machine = data.machine;
        var statusChip = chip(machine.status);
        var testBtn = h("button", { class: "btn" }, "Test connection");
        testBtn.addEventListener("click", function () {
          testBtn.disabled = true;
          testBtn.textContent = "Testing…";
          api("POST", "/machines/" + mid + "/test").then(function (fresh) {
            var freshChip = chip(fresh.status);
            if (statusChip.parentNode) statusChip.parentNode.replaceChild(freshChip, statusChip);
            statusChip = freshChip;
            toast("Connection " + humanize(fresh.status));
          }).catch(function (err) {
            toast(err.message);
          }).then(function () {
            testBtn.disabled = false;
            testBtn.textContent = "Test connection";
          });
        });
        var discoverBtn = h("button", { class: "btn" }, "Discover recipes");
        discoverBtn.addEventListener("click", function () {
          discoverBtn.disabled = true;
          discoverBtn.textContent = "Discovering…";
          api("POST", "/machines/" + mid + "/discover").then(function () {
            toast("Discovery complete");
            screenMachineDetail(p);
          }).catch(function (err) { toast(err.message); discoverBtn.disabled = false; discoverBtn.textContent = "Discover recipes"; });
        });

        var groups = data.groups.length
          ? data.groups.map(function (g) {
              return h("div", { class: "section" },
                h("div", { class: "row-between" },
                  h("h2", { text: g.recipe.name }),
                  h("span", { class: "tag", text: g.recipe.type })),
                g.recipe.description ? h("p", { class: "small dim mt-2", text: g.recipe.description }) : null,
                g.recipe.sourceBlueprintId ? h("p", { class: "xs faint mt-2",
                  text: "from blueprint " + g.recipe.sourceBlueprintId + " v" + g.recipe.sourceBlueprintVersion }) : null,
                actionsList(mid, g.recipe.id, g.actions));
            })
          : empty("No recipes yet. Run discovery to propose recipes for the services on this host.");

        return h("div", null,
          crumbs(link("#/machines", "Machines"),
            h("span", { class: "mono", text: machine.loginUser + "@" + machine.host })),
          pageHead(machine.host, machine.loginUser + "@" + machine.host + ":" + machine.port,
            [statusChip, testBtn, discoverBtn]),
          h("div", { class: "row" }, (machine.tags || []).map(function (t) {
            return h("span", { class: "tag", text: t });
          })),
          groups);
      });
    });
  }

  function actionsList(mid, rid, actions) {
    if (!actions.length) return empty("No actions in this recipe.");
    return h("ul", { class: "list mt-3" }, actions.map(function (a) {
      var right = h("div", { class: "row" }, chip(a.approvalState),
        a.sudo ? h("span", { class: "badge-sudo", text: "sudo" }) : null);
      var links = h("div", { class: "row mt-2" },
        link("#/machines/" + mid + "/recipes/" + rid + "/actions/" + a.id, "Review / approve", "btn btn--sm"),
        a.approvalState === "APPROVED"
          ? link("#/machines/" + mid + "/recipes/" + rid + "/actions/" + a.id + "/run", "Run", "btn btn--sm btn--ok")
          : null);
      return h("li", null,
        h("div", { class: "row-between" },
          h("div", { class: "grow" },
            h("strong", { text: a.name }),
            a.description ? h("p", { class: "small dim mt-2", text: a.description }) : null),
          right),
        links);
    }));
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

  // ----- Approval screen (centrepiece) --------------------------------------

  function screenApproval(p) {
    mountAsync(function () {
      return loadActionContext(p).then(function (ctx) {
        var action = ctx.action, machine = ctx.machine, recipe = ctx.recipe;

        function act(verb) {
          api("POST", "/actions/" + action.id + "/" + verb).then(function () {
            toast("Action " + verb + "d");
            screenApproval(p);
          }).catch(function (err) { toast(err.message); });
        }

        var controls = [];
        if (action.approvalState === "DRAFT") {
          controls.push(h("button", { class: "btn btn--primary", onclick: function () { act("submit"); } }, "Submit for approval"));
        }
        if (action.approvalState === "PENDING_APPROVAL") {
          controls.push(h("button", { class: "btn btn--ok", onclick: function () { act("approve"); } }, "Approve"));
        }
        if (action.approvalState === "APPROVED") {
          controls.push(link("#/machines/" + p.mid + "/recipes/" + p.rid + "/actions/" + p.aid + "/run", "Run action", "btn btn--primary"));
          controls.push(h("button", { class: "btn btn--danger", onclick: function () { act("revoke"); } }, "Revoke"));
        }
        // REVOKED is terminal: the backend permits no REVOKED -> APPROVED transition
        // (re-enabling means editing the action, which resets it to DRAFT). We offer no
        // control here rather than a button that always 409s. The revoked banner below
        // explains the dead-end.

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

        return h("div", null,
          crumbs(link("#/machines", "Machines"),
            link("#/machines/" + p.mid, machine.host),
            h("span", { text: action.name })),
          pageHead(action.name, recipe ? recipe.name : null, [chip(action.approvalState),
            action.sudo ? h("span", { class: "badge-sudo", text: "sudo" }) : null]),
          changedBanner,
          pending,
          revoked,
          action.description ? h("p", { class: "dim", text: action.description }) : null,
          h("div", { class: "card mt-4" },
            h("h2", { text: "Command" }),
            h("p", { class: "small dim mt-2", text: "Exactly what will run. LITERAL tokens are plain; PARAM slots are underlined and bound from validated input." }),
            h("div", { class: "mt-3" }, renderCommand(action, null)),
            action.sudo
              ? h("p", { class: "small mt-3" }, h("span", { class: "badge-sudo", text: "sudo" }),
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
                : null)),
          h("div", { class: "row mt-4" }, controls));
      });
    });
  }

  // ----- Run parameter entry ------------------------------------------------

  function screenRunForm(p) {
    mountAsync(function () {
      return loadActionContext(p).then(function (ctx) {
        var action = ctx.action, machine = ctx.machine;
        if (action.approvalState !== "APPROVED") {
          return h("div", null,
            crumbs(link("#/machines", "Machines"), link("#/machines/" + p.mid, machine.host),
              h("span", { text: action.name })),
            pageHead("Run " + action.name),
            h("div", { class: "banner banner--warn" }, h("div", { class: "banner-body" },
              h("strong", { text: "Not approved. " }),
              "Only APPROVED actions can run. Approve it first.")),
            link("#/machines/" + p.mid + "/recipes/" + p.rid + "/actions/" + p.aid, "Back to review", "btn"));
        }

        var params = action.paramDefs || [];
        var values = {};
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

        return h("div", null,
          crumbs(link("#/machines", "Machines"), link("#/machines/" + p.mid, machine.host),
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
            h("span", { class: "small dim", text: "Disabled until every parameter is valid." })));
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

  // ---- client-side parsers (spec-023 raw stdout → bars; degrade to raw text) ----

  function parseCpu(text) {
    // top -bn1: "%Cpu(s):  3.4 us,  1.2 sy,  0.0 ni, 95.0 id, ..." → used = 100 − idle.
    var m = text.match(/Cpu\(s\)[^\n]*?([\d.]+)\s*(?:%\s*)?id/i);
    if (!m) m = text.match(/([\d.]+)\s*(?:%\s*)?id\b/i);
    if (!m) return null;
    var idle = parseFloat(m[1]);
    if (isNaN(idle)) return null;
    return Math.max(0, Math.min(100, 100 - idle));
  }

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

  function parseDisk(text) {
    // df -h: rows ending "... <use>% <mountpoint>". Prefer "/", else the busiest.
    var rows = [];
    text.split(/\r?\n/).forEach(function (ln) {
      var m = ln.match(/(\d+)%\s+(\/\S*)\s*$/);
      if (m) rows.push({ pct: parseInt(m[1], 10), mount: m[2] });
    });
    if (!rows.length) return null;
    var root = rows.filter(function (r) { return r.mount === "/"; })[0];
    var busiest = rows.slice().sort(function (a, b) { return b.pct - a.pct; })[0];
    return { primary: root || busiest, all: rows };
  }

  function mibText(mib) {
    if (mib == null) return "";
    if (mib >= 1024) return (mib / 1024).toFixed(1) + " GiB";
    return mib + " MiB";
  }

  /** Classify a MONITOR action's framework from its recipe/action name (025 badges). */
  function frameworkOf(action) {
    var s = ((action.recipeName || "") + " " + (action.name || "")).toLowerCase();
    if (s.indexOf("springboot") >= 0 || s.indexOf("spring boot") >= 0 || s.indexOf("actuator") >= 0) return "springboot";
    if (s.indexOf("fastapi") >= 0 || s.indexOf("uvicorn") >= 0 || s.indexOf("gunicorn") >= 0) return "fastapi";
    return "generic";
  }

  function metricKind(action) {
    var n = (action.name || "").toLowerCase();
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
   * A gate-safe run chip for one related action. APPROVED + param-free → runs inline
   * (toast feedback); APPROVED + parameterised → links to the run form (params are
   * entered there); not APPROVED → a disabled chip linking to the approval screen.
   */
  function runChip(action) {
    var approved = action.approvalState === "APPROVED" && !action.changedSinceApproval;
    var parameterised = (action.paramDefs || []).length > 0;
    if (!approved) {
      return h("a", { class: "run-chip run-chip--disabled", href: actionApprovalHref(action),
        title: "Not approved — review and approve before it can run" },
        action.name, h("span", { class: "run-chip-state", text: humanize(action.approvalState) }));
    }
    if (parameterised) {
      return h("a", { class: "run-chip", href: actionRunHref(action),
        title: "Enter parameters and run" }, action.name, h("span", { class: "run-chip-go", text: "run…" }));
    }
    var chip = h("button", { type: "button", class: "run-chip", title: "Run now (approved)" },
      action.name, h("span", { class: "run-chip-go", text: "run" }));
    chip.addEventListener("click", function () {
      chip.disabled = true;
      runAndCollect(action.machineId, action.id, {}).then(function (r) {
        toast(action.name + " ran");
      }).catch(function (err) { toast(err.message); }).then(function () { chip.disabled = false; });
    });
    return chip;
  }

  function screenMonitor() {
    mountAsync(function () {
      return api("GET", "/monitor").then(function (dash) {
        var machines = (dash && dash.machines) || [];

        // ---- poll state --------------------------------------------------
        var cadence = "single";
        var pollTimer = null;
        var heartbeatTimer = null;
        var lastUpdated = null;
        var cycleInFlight = false;

        var updatedLabel = h("span", { class: "small dim", text: "not yet updated" });
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
          var secs = Math.round((Date.now() - lastUpdated) / 1000);
          updatedLabel.textContent = "updated " + secs + "s ago";
        }

        function applyCadence() {
          stopTimers();
          heartbeatTimer = setInterval(tickHeartbeat, 1000);
          var chosen = MONITOR_CADENCES.filter(function (c) { return c.key === cadence; })[0];
          if (chosen && chosen.ms > 0) {
            pollTimer = setInterval(cycle, chosen.ms);
          }
        }

        cadenceSel.addEventListener("change", function (e) {
          cadence = e.target.value;
          applyCadence();
        });
        runNowBtn.addEventListener("click", function () { cycle(); });

        // ---- per-machine host panels + app cards -------------------------
        var panels = machines.map(function (mch) { return buildMachineBlock(mch); });

        function cycle() {
          if (cycleInFlight) return;
          cycleInFlight = true;
          runNowBtn.disabled = true;
          var jobs = panels.map(function (p) { return p.refresh(); });
          Promise.all(jobs).then(function () {
            lastUpdated = Date.now();
            tickHeartbeat();
          }).catch(function () { /* per-panel errors are shown in-panel */ })
            .then(function () { cycleInFlight = false; runNowBtn.disabled = false; });
        }

        var head = h("div", { class: "page-head" },
          h("div", null,
            h("h1", { text: "Monitor" }),
            h("p", { class: "sub", text: "Live host vitals and per-app health, polled from your browser. Approval stays UI-only — polling re-runs already-approved actions." })),
          h("div", { class: "row monitor-controls" },
            h("label", { class: "small dim", text: "Cadence" }),
            cadenceSel,
            runNowBtn,
            updatedLabel));

        var body = machines.length
          ? h("div", { class: "monitor-machines" }, panels.map(function (p) { return p.node; }))
          : empty("No machines yet. Register one and run discovery to propose the monitor recipes.");

        // First reading + heartbeat (default cadence = single → one cycle, no interval).
        applyCadence();
        cycle();

        return h("div", null, head, body);
      });
    });
  }

  /**
   * Build one machine's block: a host panel (bars filled from the host monitor
   * actions each cycle) and the per-app cards. Returns { node, refresh } where
   * refresh() re-runs this machine's approved monitor actions and updates the DOM.
   */
  function buildMachineBlock(mch) {
    var hostActions = mch.hostActions || [];
    var appActions = mch.appActions || [];

    var hostPanel = h("div", { class: "host-panel" });
    var rawWrap = h("div", { class: "host-raw" });

    function renderHost(readings) {
      clear(hostPanel);
      clear(rawWrap);
      if (!hostActions.length) {
        hostPanel.appendChild(h("p", { class: "small dim" },
          "No host monitor yet. ",
          link("#/machines/" + mch.machineId, "Discover recipes", null),
          " to add the ", h("span", { class: "mono", text: "monitor machine" }), " recipe."));
        return;
      }
      var anyBar = false;
      var rawBlocks = [];
      hostActions.forEach(function (a) {
        var reading = readings[a.id];
        var approved = a.approvalState === "APPROVED" && !a.changedSinceApproval;
        if (!approved) {
          hostPanel.appendChild(h("div", { class: "meter" },
            h("div", { class: "meter-head" },
              h("span", { class: "meter-label", text: a.name }),
              h("a", { class: "small", href: actionApprovalHref(a), text: "approve to poll" }))));
          return;
        }
        var text = reading && reading.stdout ? reading.stdout : "";
        var kind = metricKind(a);
        if (kind === "cpu") {
          var cpu = parseCpu(text);
          if (cpu != null) { hostPanel.appendChild(meter("CPU", cpu)); anyBar = true; return; }
        } else if (kind === "memory") {
          var mem = parseMem(text);
          if (mem) {
            hostPanel.appendChild(meter("Memory", mem.mem.pct,
              mibText(mem.mem.used) + " / " + mibText(mem.mem.total)));
            if (mem.swap && mem.swap.total > 0) {
              hostPanel.appendChild(meter("Swap", mem.swap.pct,
                mibText(mem.swap.used) + " / " + mibText(mem.swap.total)));
            }
            anyBar = true; return;
          }
        } else if (kind === "disk") {
          var disk = parseDisk(text);
          if (disk) {
            hostPanel.appendChild(meter("Disk " + disk.primary.mount, disk.primary.pct));
            anyBar = true; return;
          }
        }
        // Unknown metric or parse failure → degrade to raw text (spec-024 known gap).
        if (text) {
          rawBlocks.push(h("details", { class: "host-raw-item" },
            h("summary", { text: a.name + " (raw output)" }),
            h("pre", { class: "mono", text: text })));
        } else {
          hostPanel.appendChild(h("div", { class: "meter" },
            h("div", { class: "meter-head" },
              h("span", { class: "meter-label", text: a.name }),
              h("span", { class: "meter-val mono", text: "…" }))));
        }
      });
      rawBlocks.forEach(function (b) { rawWrap.appendChild(b); });
      if (!anyBar && !rawBlocks.length && hostActions.length) {
        hostPanel.appendChild(h("p", { class: "small dim", text: "Polling…" }));
      }
    }

    // App cards: grouped by app-probe action (per-app instances arrive once the
    // app-monitor recipes + fan-out labelling land, 025). Each card is gate-safe.
    var appGrid = appActions.length
      ? h("div", { class: "app-cards" }, appActions.map(function (a) { return appCard(a); }))
      : null;

    renderHost({});

    var statusChip = chip(mch.status);
    var node = h("section", { class: "section monitor-machine" },
      h("div", { class: "row-between" },
        h("div", { class: "grow" },
          h("h2", { text: mch.host }),
          h("p", { class: "small dim mono", text: mch.loginUser + "@" + mch.host + ":" + mch.port })),
        statusChip),
      hostPanel,
      rawWrap,
      appGrid ? h("h3", { class: "mt-4", text: "Apps" }) : null,
      appGrid);

    function refresh() {
      // Only APPROVED, param-free host actions are poll-runnable (app probes need a
      // runtime app list; they light up via 025). Collect each reading, then render.
      var runnable = hostActions.filter(function (a) {
        return a.approvalState === "APPROVED" && !a.changedSinceApproval && (a.paramDefs || []).length === 0;
      });
      if (!runnable.length) { renderHost({}); return Promise.resolve(); }
      var readings = {};
      return Promise.all(runnable.map(function (a) {
        return runAndCollect(mch.machineId, a.id, {}).then(function (r) {
          readings[a.id] = r;
        }).catch(function () { readings[a.id] = { stdout: "" }; });
      })).then(function () { renderHost(readings); });
    }

    return { node: node, refresh: refresh };
  }

  /** One per-app card: framework badge, UP/DOWN pill, KPIs, run-chip row, drawer. */
  function appCard(action) {
    var framework = frameworkOf(action);
    var pill = h("span", { class: "pill pill--unknown", text: "no data" });
    var badge = h("span", { class: "fw-badge fw-badge--" + framework, text: framework });

    var card = h("div", { class: "app-card" },
      h("div", { class: "row-between" },
        h("div", { class: "grow" },
          h("strong", { text: action.name }),
          h("div", { class: "row mt-2" }, badge, pill)),
        chip(action.approvalState)),
      h("div", { class: "app-kpis small dim mt-2", text: "Probe " + (action.recipeName || "") }),
      h("div", { class: "run-chip-row mt-3" }, runChip(action)));
    card.addEventListener("click", function (e) {
      if (e.target.closest(".run-chip")) return; // let chips act on their own
      openAppDrawer(action);
    });
    return card;
  }

  /** The per-app detail drawer: runtime (placeholder), probed template, related run. */
  function openAppDrawer(action) {
    var framework = frameworkOf(action);
    var tokens = (action.argTokens || []).slice().sort(function (a, b) { return a.position - b.position; });
    var cmd = h("code", { class: "command command--scroll" },
      tokens.map(function (t, i) {
        var sep = i > 0 ? " " : "";
        return t.kind === "PARAM"
          ? [sep, h("span", { class: "tok-param", text: "{" + t.value + "}" })]
          : sep + t.value;
      }));

    var drawer = h("div", { class: "drawer", role: "dialog", "aria-modal": "true", "aria-label": action.name },
      h("div", { class: "row-between" },
        h("h2", { text: action.name }),
        h("button", { class: "btn btn--sm", onclick: closeDrawer }, "Close")),
      h("div", { class: "row mt-2" },
        h("span", { class: "fw-badge fw-badge--" + framework, text: framework }),
        chip(action.approvalState)),
      h("h3", { class: "mt-4", text: "Runtime" }),
      h("p", { class: "small dim", text: "Runtime facts (version, server, base image) populate from the app monitor probes once they run (spec-025)." }),
      h("h3", { class: "mt-4", text: "Probed command" }),
      h("div", { class: "mt-2" }, cmd),
      h("h3", { class: "mt-4", text: "Related actions" }),
      h("div", { class: "run-chip-row mt-2" }, runChip(action)));

    var backdrop = h("div", { class: "drawer-backdrop", onclick: function (e) {
      if (e.target === backdrop) closeDrawer();
    } }, drawer);
    var root = byId("modal-root");
    clear(root);
    root.appendChild(backdrop);
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
      fn: function (m) { screenRunForm({ mid: m[1], rid: m[2], aid: m[3] }); }, nav: "machines" },
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

  function route() {
    runViewCleanup();
    if (!Session.token()) { showLogin(); return; }
    showShell();
    var parsed = parseHash();
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

  window.addEventListener("hashchange", route);
  route();
})();

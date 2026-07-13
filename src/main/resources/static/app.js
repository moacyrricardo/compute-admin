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
                link("#/machines/" + m.id, m.name),
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
            h("span", { text: machine.name })),
          pageHead(machine.name, machine.loginUser + "@" + machine.host + ":" + machine.port,
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
        var lens = "apps";       // "apps" | "db"
        var showDocker = false, showSystem = false;
        var cadence = "single";
        var hostMemTotal = {};    // machineId → host total MB (the RAM-% denominator)

        // The consumer spine (spec-032/034): every machine's apps re-expressed as
        // MonitorConsumerViews, joined to the 029 per-app rollup for the probe
        // metadata (framework/port/checks/ops) the 032 contract does not carry yet.
        // Built ONCE per machine so a consumer keeps its last poll reading across the
        // client re-renders that lens/bucket/filter toggles trigger.
        var models = {};
        machines.forEach(function (m) { models[m.machineId] = buildConsumers(m); });
        function named(mid) { return models[mid].filter(function (c) { return !c.bucket; }); }

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
        function setLens(x) { lens = x; renderChips(); repaint(); }
        function toggleBucket(which) {
          if (which === "docker") showDocker = !showDocker; else showSystem = !showSystem;
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
          viewBar.appendChild(chipBtn("Apps", lens === "apps",
            "Per-consumer cards on three axes", function () { setLens("apps"); }));
          viewBar.appendChild(chipBtn("Databases", lens === "db",
            "Re-slice the same consumers by datastore role", function () { setLens("db"); }));
          viewBar.appendChild(h("span", { class: "small dim", style: "margin-left:12px", text: "Show" }));
          viewBar.appendChild(chipBtn("docker bucket", showDocker,
            "Reveal the unclassified docker bucket in the bars", function () { toggleBucket("docker"); }));
          viewBar.appendChild(chipBtn("system / free", showSystem,
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
            var sec = buildSection(mch);
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

        // ---- one machine's section (spec-034) ----------------------------
        // The host panel is three segmented axisMeters + a legend; the body is either
        // the consumer-card grid (Apps lens) or the datastore bands (Databases lens).
        // paint() rebuilds the body from the consumers' CURRENT state, so a re-poll
        // (which mutates those consumer objects in place) and a lens/bucket toggle
        // both go through the same paint() — no duplicate render paths.
        function buildSection(m) {
          var all = models[m.machineId];
          var bodyWrap = h("div");
          var node = h("section", { class: "section monitor-machine" },
            h("div", { class: "row-between" },
              h("div", { class: "grow" },
                h("h2", { text: m.host }),
                h("p", { class: "small dim mono", text: m.loginUser + "@" + m.host + ":" + m.port })),
              chip(m.status)),
            bodyWrap);

          function openDrawer(cid) {
            var c = all.filter(function (x) { return x.id === cid; })[0];
            if (c) openConsumerDrawer(m, c);
          }
          function revealedBuckets() {
            return all.filter(function (c) {
              if (c.bucket === "DOCKER") return showDocker;
              if (c.bucket === "SYSTEM") return showSystem;
              return false;
            });
          }

          function paint() {
            clear(bodyWrap);
            if (lens === "db") {
              renderDbInto(bodyWrap, all.filter(function (c) { return !c.bucket; }), openDrawer);
              return;
            }
            // Bars = the polled named consumers plus any revealed bucket; the free
            // remainder is hatched, so the default (buckets hidden) sits short of 100 %.
            var bars = selectedNamed(m).concat(revealedBuckets());
            bodyWrap.appendChild(h("div", { class: "host-panel" },
              axisMeter("RAM", bars, "ram", openDrawer),
              axisMeter("CPU", bars, "cpu", openDrawer),
              axisMeter("Disk", bars, "disk", openDrawer)));
            bodyWrap.appendChild(consumerLegend(bars, openDrawer));
            var cards = noAppsOn() ? [] : selectedNamed(m);
            if (cards.length) {
              bodyWrap.appendChild(h("h3", { class: "mt-4", text: "Consumers" }));
              bodyWrap.appendChild(h("div", { class: "app-cards" },
                cards.map(function (c) { return consumerCard(c, toggleApp, openDrawer); })));
            } else if (!noAppsOn() && !bars.length) {
              bodyWrap.appendChild(h("p", { class: "small dim mt-3",
                text: "No discovered consumers on this host." }));
            }
          }

          // Host memory first (the RAM-% denominator), then the visible consumers'
          // fan-out probes; both mutate the shared consumer objects, then paint().
          function refresh() {
            return pollHostTotal(m).then(function (total) {
              if (total != null) hostMemTotal[m.machineId] = total;
              return pollConsumers(m.machineId, selectedNamed(m), hostMemTotal[m.machineId]);
            }).then(paint);
          }

          paint();
          return { node: node, refresh: refresh, paint: paint };
        }

        // Poll the machine's approved host-memory probe → host total MB. No host
        // vitals are rendered any more (spec-034 replaced the host panel with the
        // per-consumer segmented bars); this run only feeds the RAM-% denominator.
        function pollHostTotal(m) {
          var mem = (m.hostActions || []).filter(function (a) {
            return metricKind(a) === "memory" && a.approvalState === "APPROVED"
              && !a.changedSinceApproval && (a.paramDefs || []).length === 0;
          })[0];
          if (!mem) return Promise.resolve(null);
          return runAndCollect(m.machineId, mem.id, {}).then(function (r) {
            var parsed = parseMem(r.stdout);
            return parsed && parsed.mem && parsed.mem.total ? parsed.mem.total : null;
          }).catch(function () { return null; });
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
      return h("button", { type: "button", class: "legend-chip", "data-cid": c.id,
        title: "Open " + c.name, onclick: function () { onOpen(c.id); } },
        dot,
        h("span", { text: c.name }),
        h("span", { class: "lg-pct", text: pctText(c.ram) + " · " + pctText(c.cpu) + " · " + pctText(c.disk) }));
    }));
  }

  /**
   * One axis meter on a consumer card. A known value renders the reused .meter
   * (RAM carries its RSS as the sub-line); a null value renders — with an honest
   * note: a native process has no attributable disk, an axis with no approved
   * monitor says so, everything else is simply "no data" yet.
   */
  function consumerAxis(label, consumer) {
    var key = label.toLowerCase();
    var pct = consumer[key];
    if (pct == null) {
      var note = (key === "disk")
        ? (consumer.source === "DOCKER" ? "n/a" : "native — n/a")
        : (consumer._anyApproved === false ? "approve to see" : "no data");
      return h("div", { class: "meter" },
        h("div", { class: "meter-head" },
          h("span", { class: "meter-label", text: label }),
          h("span", { class: "meter-val mono", text: "—" })),
        h("div", { class: "meter-sub mono", text: note }));
    }
    var sub = (key === "ram" && consumer._rssMb != null) ? (mibText(consumer._rssMb) + " RSS") : null;
    return meter(label, pct, sub);
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
    var name = h("button", { type: "button", class: "app-name-toggle",
      title: "Filter the fleet to " + consumer.name, text: consumer.name,
      onclick: function (e) { e.stopPropagation(); onToggle(consumer.name); } });
    var runtimeTag = h("span", { class: "tag mono",
      text: (consumer.runtime || (consumer.source === "DOCKER" ? "docker" : "process"))
        + (consumer.port != null ? " :" + consumer.port : "") });
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

  function datastoresOf(consumers) {
    var ded = [], shared = [];
    consumers.forEach(function (c) {
      if (c.role !== "DATABASE") return;
      (c.dedication === "SHARED" ? shared : ded).push(c);
    });
    return { ded: ded, shared: shared };
  }

  /** A segmented split of the dedicated datastores on one axis, coloured per owner. */
  function splitMeter(axis, items) {
    var total = 0;
    items.forEach(function (i) { if (i[axis] != null) total += i[axis]; });
    var track = h("div", { class: "meter-track axis-track" });
    items.forEach(function (i) {
      var v = i[axis];
      if (v == null || v <= 0) return;
      var seg = h("div", { class: "axis-seg", title: (i.owner || i.name) + " · " + pctText(v) + " of host" });
      seg.style.width = (total > 0 ? (v / total * 100) : 0) + "%";
      seg.style.background = consumerColorVar(i);
      track.appendChild(seg);
    });
    var rows = items.map(function (i) {
      var share = (total > 0 && i[axis] != null) ? Math.round(i[axis] / total * 100) : null;
      var dot = h("span", { class: "legend-dot" });
      dot.style.background = consumerColorVar(i);
      return h("span", { class: "row", style: "gap:6px" }, dot,
        (i.owner || i.name) + " " + (share == null ? "—" : share + "%"));
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
  // fill each consumer's RAM axis (RSS / host total) + UP/DOWN + probe states.

  function pollConsumers(machineId, consumers, hostTotal) {
    var pollable = consumers.filter(function (c) { return (c.checks || []).length && c.port != null; });
    if (!pollable.length) return Promise.resolve();
    var groups = {};
    pollable.forEach(function (c) {
      (c.checks || []).forEach(function (chk) {
        if (chk.approvalState !== "APPROVED" || chk.changedSinceApproval) return;
        if (!appPortListParamName(chk)) return;
        var g = groups[chk.id] || (groups[chk.id] = { action: chk, apps: [], seen: {} });
        if (!g.seen[c.name]) { g.seen[c.name] = true; g.apps.push({ appName: c.name, port: c.port }); }
      });
    });
    var ids = Object.keys(groups);
    if (!ids.length) {
      pollable.forEach(function (c) { applyConsumerReading(c, null, hostTotal); });
      return Promise.resolve();
    }
    var outputs = {}; // checkId → (appName → { stdout, exit })
    return Promise.all(ids.map(function (id) {
      return runProbeForApps(machineId, groups[id].action, groups[id].apps)
        .then(function (byApp) { outputs[id] = byApp; });
    })).then(function () {
      pollable.forEach(function (c) { applyConsumerReading(c, outputs, hostTotal); });
    });
  }

  /**
   * Fold one consumer's probe outputs into its live state: the process probe's VmRSS
   * (summed → MB) over the host total gives the RAM axis; liveness/process rolls up
   * to UP/DOWN; each check keeps its responded state (na = did not respond, so it is
   * omitted from the drawer's probe list — probe honesty, spec-034 §6). Only the RAM
   * axis is filled client-side; CPU/disk stay at their server value (null against 032).
   */
  function applyConsumerReading(c, outputs, hostTotal) {
    var livenessUp = null, processUp = null, rssMb = null, rows = [];
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
        } else if (kind === "cpu") {
          state = res.stdout && res.stdout.indexOf("no listener") < 0 ? "up" : "down";
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
    c._rssMb = rssMb;
    c.ram = clientMemPct(rssMb, hostTotal);
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
      c.bucket ? h("span", { class: "tag", text: "hidden bucket" }) : null
    ];
    var diskMeter = c.disk == null
      ? h("div", { class: "meter" },
          h("div", { class: "meter-head" },
            h("span", { class: "meter-label", text: "Disk" }),
            h("span", { class: "meter-val mono", text: "—" })),
          h("div", { class: "meter-sub mono",
            text: c.source === "DOCKER" ? "n/a" : "native process — no attributable disk footprint" }))
      : meter("Disk", c.disk);
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
      h("div", { class: "d-axes mt-4" }, meter("RAM", c.ram), meter("CPU", c.cpu), diskMeter),
      owner, services, probes, compose);
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
    return h("div", { class: "drow" },
      h("div", { class: "grow" },
        h("div", { style: "font-weight:600", text: s.name }),
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

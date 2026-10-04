// Reader UI. SMS bodies are attacker-controlled (anyone can text the phone): render with
// textContent only, never innerHTML.

import * as C from "./crypto.js";
import { Vault } from "./store.js";
import * as R from "./rules.js";

const HELLO_MS = 60_000; // while the page is visible
const HELLO_HIDDEN_MS = 5 * 60_000; // tab in the background: every hello wakes the phone, so go slow
/** The phone counts as online if it answered within 2.5 hello intervals (longer while hidden). */
const reachableMs = () => 2.5 * (document.hidden ? HELLO_HIDDEN_MS : HELLO_MS);
const HELLO_MIN_GAP_MS = 15_000; // extra hellos (tab shown again, phone re-announcing) at most this often
const HELLO_PROOF_MS = 180_000; // a status counts as proof of life only if it answers a hello this recent
const PAGE = 500;
const CMD_EXPIRE_MS = 6.5 * 24 * 3600 * 1000; // phone rejects commands older than 7 days
const MAX_IDS = 100;

let vault = null;
let cfg = null;
let keys = null; // { sigKey, encKey, sigPub, encPub }
let client = null;
let helloTimer = null;
let ackTimer = null;
let ackFirstAt = null;
const ackQueue = new Map(); // id -> delivery generation being acknowledged
const goneAcks = new Set(); // ids the phone reported deleted there, to acknowledge
const recentHellos = new Map(); // hello cmd -> sent at
const saving = new Set(); // sms ids whose first copy is being written
let shown = PAGE;
let view = { addr: null }; // null: conversation list; otherwise the open conversation

const KINDS = ["in", "sent", "draft", "failed"];
/** Sender or recipient (older records stored it as `from`). */
const addrOf = (m) => m.addr ?? m.from ?? "";

/**
 * Message-state changes run one at a time: each reads the in-memory record, writes the vault, then
 * updates memory, and two of them interleaving across an await could undo each other (e.g. a delete
 * result restoring a message the user had just removed from the archive).
 */
let writeChain = Promise.resolve();
function serially(fn) {
  const run = writeChain.then(fn);
  writeChain = run.catch(() => {});
  return run;
}
const msgs = new Map();
const pending = new Map();
const selected = new Set();
let phoneStatus = null;
let lastSeen = 0; // when a status newer than any before it arrived (replays do not count)
let heardSinceConnect = false; // a fresh status arrived over the current connection
let candidate = null; // pair announcement awaiting the user's fingerprint check
let warning = null;
let filter = "";
/** The phone's forwarding rule set ({version, rules}) and allowlist ([{n, a}]), as it last reported them. */
let fwd = { rules: null, allow: [] };

const app = document.getElementById("app");

// ---------- DOM helpers ----------

function el(tag, props = {}, ...children) {
  const n = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (k === "on") for (const [ev, fn] of Object.entries(v)) n.addEventListener(ev, fn);
    else if (k === "class") n.className = v;
    else if (k in n) n[k] = v;
    else n.setAttribute(k, v);
  }
  for (const c of children.flat()) if (c != null && c !== false) n.append(c instanceof Node ? c : String(c));
  return n;
}

function screen(...children) {
  app.replaceChildren(...children);
}

function field(label, input) {
  return el("label", { class: "field" }, el("span", {}, label), input);
}

/** Time only for today, day and month this year, full date otherwise. */
function fmtTime(ms) {
  if (!ms) return "";
  const d = new Date(ms);
  const now = new Date();
  if (d.toDateString() === now.toDateString()) return d.toLocaleTimeString(undefined, { timeStyle: "short" });
  if (d.getFullYear() === now.getFullYear()) {
    return d.toLocaleString(undefined, { day: "numeric", month: "short", hour: "numeric", minute: "2-digit" });
  }
  return d.toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
}

function ago(ms) {
  if (!ms) return "never";
  const s = Math.round((Date.now() - ms) / 1000);
  if (s < 60) return `${s}s ago`;
  if (s < 3600) return `${Math.round(s / 60)} min ago`;
  if (s < 86400) return `${Math.round(s / 3600)} h ago`;
  return `${Math.round(s / 86400)} days ago`;
}

// ---------- start / unlock ----------

async function start() {
  if (window.top !== window.self) {
    // Never run inside a frame: another page could read the unlocked archive.
    screen(el("p", { class: "error" }, "This page refuses to run inside a frame. Open it directly."));
    return;
  }
  if (!globalThis.crypto?.subtle) {
    screen(el("p", { class: "error" }, "This page needs a secure context (https:// or http://localhost)."));
    return;
  }
  // One reader tab at a time: two would keep separate in-memory copies of the archive and undo each
  // other's writes (and fight over the broker connection).
  if (!(await holdTabLock())) {
    screen(el("p", { class: "error" }, "The reader is already open in another tab or window. Use that one, or close it and reload this page."));
    return;
  }
  if (await Vault.exists()) showUnlock();
  else showCreate();
}

function showCreate() {
  const p1 = el("input", { type: "password", autocomplete: "new-password" });
  const p2 = el("input", { type: "password", autocomplete: "new-password" });
  const err = el("p", { class: "error" });
  screen(
    el("h1", {}, "SMS Relay reader"),
    el("p", {}, "Choose a passphrase. It encrypts everything this page stores. If you forget it, the archive is lost and you will need to pair again."),
    field("Passphrase", p1),
    field("Repeat", p2),
    err,
    el("button", {
      on: {
        click: async () => {
          if (p1.value.length < 10) return (err.textContent = "Use at least 10 characters.");
          if (p1.value !== p2.value) return (err.textContent = "Passphrases differ.");
          err.textContent = "Deriving key…";
          vault = await Vault.create(p1.value);
          showSetup();
        },
      },
    }, "Create"),
    el("h2", {}, "Or restore a backup"),
    el("p", { class: "muted" }, "Use this on a new laptop or browser. The backup passphrase becomes this reader's passphrase."),
    restoreForm(),
  );
}

function restoreForm() {
  const file = el("input", { type: "file", accept: ".json,application/json" });
  const pass = el("input", { type: "password", autocomplete: "off" });
  const err = el("p", { class: "error" });
  return el("div", {},
    field("Backup file", file),
    field("Backup passphrase", pass),
    err,
    el("button", {
      class: "secondary",
      on: {
        click: async () => {
          if (!file.files[0]) return (err.textContent = "Choose a file.");
          err.textContent = "Decrypting…";
          let data;
          try {
            data = await decryptBackup(JSON.parse(await file.files[0].text()), pass.value);
          } catch {
            return (err.textContent = "Wrong passphrase or not a backup file.");
          }
          vault = await Vault.create(pass.value);
          cfg = data.cfg;
          await vault.put("kv", "config", cfg);
          for (const m of data.msgs) {
            msgs.set(m.id, m);
            await vault.put("msgs", m.id, m);
          }
          for (const c of data.cmds) {
            pending.set(c.payload.cmd, c);
            await vault.put("cmds", c.payload.cmd, c);
          }
          await loadKeys();
          connect();
          cfg.phone ? showMain() : showPairing();
        },
      },
    }, "Restore"),
  );
}

const BACKUP_ITER = 600000;

async function backupKey(pass, salt, iterations = BACKUP_ITER) {
  const base = await crypto.subtle.importKey("raw", new TextEncoder().encode(pass), "PBKDF2", false, ["deriveKey"]);
  return crypto.subtle.deriveKey({ name: "PBKDF2", hash: "SHA-256", salt, iterations }, base, { name: "AES-GCM", length: 256 }, false, ["encrypt", "decrypt"]);
}

async function encryptBackup(pass) {
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const body = { cfg, msgs: [...msgs.values()], cmds: [...pending.values()] };
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv }, await backupKey(pass, salt), new TextEncoder().encode(JSON.stringify(body))));
  return { format: "sms-relay-backup", v: 1, iter: BACKUP_ITER, salt: C.b64e(salt), iv: C.b64e(iv), ct: C.b64e(ct) };
}

async function decryptBackup(file, pass) {
  if (file.format !== "sms-relay-backup" || file.v !== 1) throw new Error("not a backup");
  const iter = Number(file.iter);
  if (!(iter >= 100000 && iter <= 10000000)) throw new Error("bad iteration count");
  const key = await backupKey(pass, C.b64d(file.salt), iter);
  const pt = await crypto.subtle.decrypt({ name: "AES-GCM", iv: C.b64d(file.iv) }, key, C.b64d(file.ct));
  const data = JSON.parse(new TextDecoder().decode(pt));
  if (!data.cfg?.id?.sigPriv || !Array.isArray(data.msgs) || !Array.isArray(data.cmds)) throw new Error("bad backup");
  return data;
}

function backupForm() {
  const p1 = el("input", { type: "password", autocomplete: "new-password" });
  const p2 = el("input", { type: "password", autocomplete: "new-password" });
  const err = el("p", { class: "error" });
  return el("div", {},
    el("p", {}, "Without this file, losing this browser means pairing again, which needs the phone in hand. Download one now and after big changes; keep it somewhere safe (password manager, encrypted drive)."),
    field("Backup passphrase", p1),
    field("Repeat", p2),
    err,
    el("button", {
      class: "secondary",
      on: {
        click: async () => {
          if (p1.value.length < 12) return (err.textContent = "Use at least 12 characters.");
          if (p1.value !== p2.value) return (err.textContent = "Passphrases differ.");
          err.textContent = "Encrypting…";
          const blob = new Blob([JSON.stringify(await encryptBackup(p1.value))], { type: "application/json" });
          const a = el("a", { href: URL.createObjectURL(blob), download: `sms-relay-backup-${new Date().toISOString().slice(0, 10)}.json` });
          document.body.append(a);
          a.click();
          a.remove();
          setTimeout(() => URL.revokeObjectURL(a.href), 10_000);
          p1.value = p2.value = "";
          err.textContent = "Backup downloaded.";
        },
      },
    }, "Download encrypted backup"),
  );
}

let tabLock = null;

/** Take (and keep, for this page's life) the single-tab lock; false if another tab holds it. */
function holdTabLock() {
  if (tabLock) return Promise.resolve(true);
  if (!navigator.locks) return Promise.resolve(true); // very old browser: no coordination available
  return new Promise((resolve) => {
    navigator.locks.request("sms-relay-reader", { ifAvailable: true }, (lock) => {
      if (!lock) return resolve(false);
      resolve(true);
      return new Promise((release) => { tabLock = release; }); // held until the page closes
    }).catch(() => resolve(true)); // locks unavailable here: run uncoordinated rather than not at all
  });
}

function showUnlock() {
  const p = el("input", { type: "password", autocomplete: "current-password" });
  const err = el("p", { class: "error" });
  const go = async () => {
    err.textContent = "Unlocking…";
    const v = await Vault.unlock(p.value);
    if (!v) return (err.textContent = "Wrong passphrase.");
    vault = v;
    cfg = await vault.get("kv", "config");
    if (!cfg) return showSetup();
    await loadKeys();
    for (const m of await vault.all("msgs")) msgs.set(m.id, m);
    for (const c of await vault.all("cmds")) pending.set(c.payload.cmd, c);
    fwd = (await vault.get("kv", "forwarding")) ?? fwd;
    connect();
    cfg.phone ? showMain() : showPairing();
  };
  p.addEventListener("keydown", (e) => e.key === "Enter" && go());
  screen(
    el("h1", {}, "SMS Relay reader"),
    field("Passphrase", p),
    err,
    el("button", { on: { click: go } }, "Unlock"),
    el("p", { class: "muted" }, el("button", { class: "link", on: { click: resetAll } }, "Forgot passphrase: erase this reader")),
  );
  p.focus();
}

async function resetAll() {
  if (!confirm("Erase all stored messages, keys and settings in this browser? You will need to pair the phone again.")) return;
  client?.end(true);
  vault?.close();
  try {
    await Vault.destroy();
  } catch (e) {
    alert(`Could not erase: ${e.message}`);
    return;
  }
  location.reload();
}

// ---------- setup and pairing ----------

const BROKER_KEYS = ["host", "wsPort", "tcpPort", "prefix", "readerUser", "readerPass", "phoneUser", "phonePass"];

/** Broker settings inputs; submit(settings) is called with validated values. */
function brokerForm(initial, label, submit) {
  const v = { host: "", wsPort: 8084, tcpPort: 8883, prefix: "smsrelay", readerUser: "reader", readerPass: "", phoneUser: "phone", phonePass: "", ...initial };
  const host = el("input", { placeholder: "your-deployment.example.com", value: v.host });
  const wsPort = el("input", { value: String(v.wsPort), inputMode: "numeric" });
  const tcpPort = el("input", { value: String(v.tcpPort), inputMode: "numeric" });
  const prefix = el("input", { value: v.prefix });
  const ru = el("input", { value: v.readerUser });
  const rp = el("input", { type: "password", value: v.readerPass });
  const pu = el("input", { value: v.phoneUser });
  const pp = el("input", { type: "password", value: v.phonePass });
  const err = el("p", { class: "error" });
  const port = (x) => Number.isInteger(Number(x)) && Number(x) >= 1 && Number(x) <= 65535;
  return el("div", {},
    field("Broker host", host),
    field("WebSocket TLS port (reader)", wsPort),
    field("MQTT TLS port (phone)", tcpPort),
    field("Topic prefix", prefix),
    field("Reader username", ru),
    field("Reader password", rp),
    field("Phone username", pu),
    field("Phone password", pp),
    err,
    el("button", {
      on: {
        click: async () => {
          const h = host.value.trim();
          if (!/^[A-Za-z0-9.-]{1,253}$/.test(h)) return (err.textContent = "Bad host.");
          if (!port(wsPort.value) || !port(tcpPort.value)) return (err.textContent = "Bad port.");
          if (!/^[A-Za-z0-9_-]{1,64}(\/[A-Za-z0-9_-]{1,64}){0,3}$/.test(prefix.value.trim())) return (err.textContent = "Bad topic prefix.");
          if (!rp.value || !pp.value || !ru.value || !pu.value) return (err.textContent = "All credentials are required.");
          err.textContent = "";
          await submit({
            host: h,
            wsPort: Number(wsPort.value),
            tcpPort: Number(tcpPort.value),
            prefix: prefix.value.trim(),
            readerUser: ru.value,
            readerPass: rp.value,
            phoneUser: pu.value,
            phonePass: pp.value,
          });
        },
      },
    }, label),
  );
}

function showSetup() {
  screen(
    el("h1", {}, "Broker settings"),
    el("p", {}, "From your EMQX Serverless deployment. See the README for the exact steps and access rules."),
    brokerForm({}, "Save and show pairing code", async (b) => {
      const id = await C.generateIdentity();
      cfg = {
        ...b,
        id: { sigPriv: C.b64e(id.sigPriv), sigPub: C.b64e(id.sigPub), encPriv: C.b64e(id.encPriv), encPub: C.b64e(id.encPub) },
        phone: null,
      };
      await vault.put("kv", "config", cfg);
      await loadKeys();
      connect();
      showPairing();
    }),
  );
}

function pick(obj) {
  return Object.fromEntries(BROKER_KEYS.map((k) => [k, obj[k]]));
}

/** Ask the phone to move; it confirms on the old broker, then both switch. */
async function requestMove(b) {
  const payload = cmd("rebroker", { host: b.host, port: b.tcpPort, topic: b.prefix, user: b.phoneUser, pass: b.phonePass });
  cfg.moveTo = { cmd: payload.cmd, broker: b };
  await vault.put("kv", "config", cfg);
  await queueCommand(payload);
  warning = "Move requested. Waiting for the phone to confirm on the current broker…";
  renderMain();
}

async function switchBroker(to, note, movedCmd = null) {
  cfg.prevBroker = pick(cfg);
  Object.assign(cfg, to);
  delete cfg.moveTo;
  if (movedCmd) cfg.movedCmd = movedCmd;
  else delete cfg.movedCmd;
  await vault.put("kv", "config", cfg);
  lastSeen = 0;
  connect();
  warning = note;
  showMain(); // rebuilds the More section so "Switch back" appears
}

async function loadKeys() {
  keys = {
    sigKey: await C.importSigPriv(C.b64d(cfg.id.sigPriv)),
    encKey: await C.importEncPriv(C.b64d(cfg.id.encPriv)),
    sigPub: C.b64d(cfg.id.sigPub),
    encPub: C.b64d(cfg.id.encPub),
  };
}

function pairingCode() {
  const j = { h: cfg.host, p: cfg.tcpPort, t: cfg.prefix, pu: cfg.phoneUser, pp: cfg.phonePass, rs: cfg.id.sigPub, re: cfg.id.encPub };
  return "SR1." + C.b64e(new TextEncoder().encode(JSON.stringify(j)));
}

function showPairing() {
  const code = pairingCode();
  const qr = globalThis.qrcode(0, "M");
  qr.addData(code);
  qr.make();
  const box = el("div", { class: "pairbox" });
  screen(
    el("h1", {}, "Pair the phone"),
    el("ol", {},
      el("li", {}, "On the phone, open Messages → Settings → Advanced (it asks for the screen lock) and finish steps 1 to 3."),
      el("li", {}, "Scan this QR with the phone camera (or any QR scanner app), copy the text, paste it in step 4 and tap Pair."),
      el("li", {}, "Compare the fingerprint below with the one on the phone."),
    ),
    el("img", { class: "qr", src: qr.createDataURL(6, 4), alt: "pairing QR code" }),
    el("details", {}, el("summary", {}, "Show code as text"), el("code", { class: "code" }, code)),
    el("p", { class: "muted" }, "This code contains the phone's broker password. Do not share it or screenshot it."),
    box,
    el("p", {}, el("button", { class: "link", on: { click: () => { cfg.phone = null; showSetup(); } } }, "Change broker settings")),
  );
  renderCandidate(box);
}

async function renderCandidate(box = document.querySelector(".pairbox")) {
  if (!box) return;
  if (!candidate) {
    box.replaceChildren(el("p", {}, client?.connected ? "Connected to broker. Waiting for the phone…" : "Connecting to broker…"));
    return;
  }
  const shown = candidate; // pin exactly the identity whose fingerprint is on screen
  const fp = await C.fingerprint(shown.sigPub, shown.encPub, keys.sigPub, keys.encPub);
  if (candidate !== shown) return; // a newer announcement replaced it; its own render follows
  box.replaceChildren(
    el("p", {}, "Phone found. Fingerprint:"),
    el("p", { class: "fingerprint" }, fp),
    el("button", {
      on: {
        click: async () => {
          cfg.phone = { sigPub: C.b64e(shown.sigPub), encPub: C.b64e(shown.encPub) };
          cfg.pairedAt = Date.now();
          await vault.put("kv", "config", cfg);
          candidate = null;
          sendHello();
          showMain();
        },
      },
    }, "It matches the phone"),
    " ",
    el("button", { class: "secondary", on: { click: () => { candidate = null; renderCandidate(box); } } }, "It does not match"),
  );
}

// ---------- MQTT ----------

function wsUrl() {
  // Plain ws:// only for a broker on this machine (the Docker test broker); everything else is wss://.
  const local = cfg.host === "localhost" || cfg.host === "127.0.0.1";
  return `${local ? "ws" : "wss"}://${cfg.host}:${cfg.wsPort}/mqtt`;
}

function connect() {
  client?.end(true);
  // Hello proofs belong to one connection: a status answering a hello sent over an earlier
  // connection (e.g. the old broker during a move) must not count as proof on this one.
  recentHellos.clear();
  const cl = globalThis.mqtt.connect(wsUrl(), {
    username: cfg.readerUser,
    password: cfg.readerPass,
    clientId: "srr-" + C.randomHex(6),
    clean: true,
    keepalive: 60,
    reconnectPeriod: 5000,
    connectTimeout: 30_000,
    protocolVersion: 4,
    timerVariant: "native", // the default worker timer needs a blob: worker, which the CSP blocks
  });
  client = cl;
  cl.on("connect", () => {
    if (cl !== client) return;
    heardSinceConnect = false;
    recentHellos.clear(); // reconnects too: proofs never span connections
    cl.subscribe(`${cfg.prefix}/up`, { qos: 1 }, (err) => {
      if (!err && cl === client) sendHello();
    });
    refresh();
  });
  cl.on("close", refresh);
  cl.on("error", (e) => {
    if (cl !== client) return;
    warning = `Broker: ${e.message}`;
    refresh();
  });
  cl.on("message", (_topic, payload) => {
    if (cl === client) onUp(new Uint8Array(payload)).catch(() => {});
  });
  scheduleHello();
}

/** Hello every minute while visible, every 5 minutes in the background; one right away on return. */
function scheduleHello() {
  clearTimeout(helloTimer);
  helloTimer = setTimeout(async () => {
    await sendHello();
    scheduleHello();
  }, document.hidden ? HELLO_HIDDEN_MS : HELLO_MS);
}

document.addEventListener("visibilitychange", () => {
  if (!client) return;
  if (!document.hidden) sendHello({ extra: true });
  scheduleHello();
});

async function publish(payload) {
  if (!client?.connected || !cfg.phone) return false;
  const env = await C.seal(C.KIND_DOWN, keys.sigKey, keys.sigPub, C.b64d(cfg.phone.encPub), payload);
  client.publish(`${cfg.prefix}/down`, env, { qos: 1, retain: false });
  return true;
}

function cmd(t, extra = {}) {
  return { t, cmd: C.randomHex(16), ts: Date.now(), ...extra };
}

let lastHelloSent = 0;

/** Each hello wakes the phone; [extra] hellos outside the schedule are throttled. */
async function sendHello({ extra = false } = {}) {
  if (!cfg?.phone || !client?.connected) return renderCandidate();
  if (extra && Date.now() - lastHelloSent < HELLO_MIN_GAP_MS) return;
  lastHelloSent = Date.now();
  // After a broker move, quote it once we have heard the phone on this broker: that is the
  // phone's signal that both directions work and the move can be made permanent.
  // rv: the rule-set version we hold; the phone attaches its rules only when ours is out of date.
  const hello = cmd("hello", { rv: fwd.rules?.version ?? 0, ...(cfg.movedCmd && heardSinceConnect ? { moved: cfg.movedCmd } : {}) });
  recentHellos.set(hello.cmd, Date.now());
  for (const [c, at] of recentHellos) if (Date.now() - at > HELLO_PROOF_MS) recentHellos.delete(c);
  await publish(hello);
  for (const [id, c] of pending) {
    if (Date.now() - c.payload.ts > CMD_EXPIRE_MS) {
      await expire(id, c);
      continue;
    }
    await publish(c.payload); // same cmd id: the phone answers repeats from its log
  }
  refresh();
}

function expire(id, c) {
  return serially(async () => {
    const reset = (c.payload.ids || [])
      .map((mid) => msgs.get(mid))
      .filter((m) => m && m.phone === "pending")
      .map((m) => ({ ...m, phone: "present" }));
    // Command removal and message resets together, so nothing can stay "pending" without a command.
    await vault.batch([{ store: "cmds", key: id, del: true }, ...reset.map((m) => ({ store: "msgs", key: m.id, value: m }))]);
    pending.delete(id);
    for (const m of reset) msgs.set(m.id, m);
    warning = "A command expired before the phone came online. Try again.";
  });
}

/** Persist the command together with any message updates (atomically), then send it. */
async function queueCommand(payload, msgUpdates = []) {
  const c = { payload, created: Date.now() };
  await vault.batch([{ store: "cmds", key: payload.cmd, value: c }, ...msgUpdates.map((m) => ({ store: "msgs", key: m.id, value: m }))]);
  pending.set(payload.cmd, c);
  for (const m of msgUpdates) msgs.set(m.id, m);
  await publish(payload);
}

/** Acknowledge exactly the copy we stored (id + delivery generation). */
function scheduleAck(id, gen) {
  if (gen != null) ackQueue.set(id, gen);
  else goneAcks.add(id);
  // Batch for half a second, but never hold acks more than 2 s during a steady stream.
  ackFirstAt ??= Date.now();
  clearTimeout(ackTimer);
  ackTimer = setTimeout(sendAcks, Date.now() - ackFirstAt > 1500 ? 0 : 500);
}

async function sendAcks() {
  ackFirstAt = null;
  const items = [...ackQueue];
  const gone = [...goneAcks];
  ackQueue.clear();
  goneAcks.clear();
  const rounds = Math.max(Math.ceil(items.length / MAX_IDS), Math.ceil(gone.length / MAX_IDS));
  for (let i = 0; i < rounds; i++) {
    const part = items.slice(i * MAX_IDS, (i + 1) * MAX_IDS);
    await publish(cmd("ack", {
      ids: part.map((x) => x[0]),
      gens: part.map((x) => x[1]),
      gone: gone.slice(i * MAX_IDS, (i + 1) * MAX_IDS),
    }));
  }
}

/** The phone deleted these itself (its own UI, or a draft that was edited or sent). */
function onGone(m) {
  const ids = Array.isArray(m.ids) ? m.ids.map(String).filter((id) => /^[0-9a-f]{32}$/.test(id)) : [];
  return serially(async () => {
    const updated = [];
    for (const id of ids) {
      const rec = msgs.get(id);
      if (rec?.removed) continue;
      // Never received here: keep a tombstone so a late or replayed copy is not shown as present.
      if (!rec) {
        updated.push({ id, removed: true, addr: "", body: "", phone: "gone" });
        continue;
      }
      // A replaced draft just disappears; anything else stays, marked as deleted on the phone.
      updated.push(rec.kind === "draft" ? { id, removed: true, addr: addrOf(rec), body: "", phone: "gone" } : { ...rec, phone: "gone" });
    }
    if (updated.length) await vault.batch(updated.map((u) => ({ store: "msgs", key: u.id, value: u })));
    for (const u of updated) msgs.set(u.id, u);
    for (const id of ids) scheduleAck(id, null);
  });
}

async function onUp(env) {
  if (env[1] === C.KIND_PAIR) return onPair(env);
  if (!cfg.phone) return;
  let m;
  try {
    m = await C.open(env, C.KIND_UP, C.b64d(cfg.phone.sigPub), keys.encKey, keys.encPub);
  } catch {
    return; // not from our phone
  }
  if (m.t === "sms") await onSms(m);
  else if (m.t === "status") {
    // Only an answer to one of our own recent hellos proves the phone is alive now: the broker
    // can replay any older status.
    const asked = recentHellos.get(String(m.re || ""));
    if (!asked || Date.now() - asked > HELLO_PROOF_MS) return;
    phoneStatus = m;
    lastSeen = Date.now();
    await onForwardingState(m);
    if (cfg.movedCmd && !heardSinceConnect) {
      heardSinceConnect = true;
      sendHello(); // confirm the move right away instead of at the next tick
    }
    heardSinceConnect = true;
    // (Pending commands already went out with the hello this status answers.)
  }
  else if (m.t === "result") await onResult(m);
  else if (m.t === "gone") await onGone(m);
  refresh();
}

async function onPair(env) {
  let m;
  try {
    m = await C.open(env, C.KIND_PAIR, null, keys.encKey, keys.encPub);
  } catch {
    return;
  }
  const sigPub = C.b64d(String(m.sigPub || ""));
  const encPub = C.b64d(String(m.encPub || ""));
  if (sigPub.length !== 32 || encPub.length !== 32) return;
  if (cfg.phone) {
    if (C.b64e(sigPub) !== cfg.phone.sigPub || C.b64e(encPub) !== cfg.phone.encPub) {
      warning = "A device announced a different phone identity. It was ignored. If you did not re-pair the phone, someone may have its broker password.";
      refresh();
    } else {
      sendHello({ extra: true }); // phone has not heard from us yet (throttled: announcements can be replayed)
    }
    return;
  }
  candidate = { sigPub, encPub };
  renderCandidate();
}

async function onSms(m) {
  const id = String(m.id || "");
  const gen = String(m.gen || "");
  if (!/^[0-9a-f]{32}$/.test(id) || !/^[0-9a-f]{16}$/.test(gen)) return;
  if (saving.has(id)) return; // first copy still being written; the phone will send it again
  if (!msgs.has(id)) { // removed messages stay as tombstones, so replays do not bring them back
    const rec = {
      id,
      kind: KINDS.includes(m.kind) ? m.kind : "in",
      addr: String(m.addr ?? m.from ?? ""),
      body: String(m.body ?? ""),
      sent: Number(m.sent) || 0,
      rcvd: Number(m.rcvd) || 0,
      phone: "present",
      got: Date.now(),
      cut: Number(m.cut) || 0,
    };
    // A copy the phone sent because of a forwarding rule: the rule's name and the original message.
    if (typeof m.rule === "string" && m.rule) rec.rule = m.rule.slice(0, R.LIMITS.name);
    if (/^[0-9a-f]{32}$/.test(String(m.fwdOf || ""))) rec.fwdOf = m.fwdOf;
    saving.add(id);
    try {
      await serially(async () => {
        if (msgs.has(id)) return; // a tombstone (e.g. from a "gone" notice) was written meanwhile: keep it
        await vault.put("msgs", id, rec); // stored before acknowledged, or the phone would drop it
        msgs.set(id, rec);
      });
    } catch {
      warning = "Could not save a message in this browser (storage full or blocked). It stays on the phone.";
      return;
    } finally {
      saving.delete(id);
    }
  }
  scheduleAck(id, gen); // also for a duplicate: our previous ack was lost
}

async function onResult(m) {
  const c = pending.get(String(m.cmd));
  if (!c) return;
  if (m.op === "delete" && m.res && typeof m.res === "object") {
    // Every id we asked about gets a final state, so nothing stays "pending" forever; states and
    // the command's removal are written together. Records are read inside the serial section, so a
    // tombstone written meanwhile is kept (only its phone state changes).
    return serially(async () => {
      if (!pending.has(c.payload.cmd)) return; // a duplicate result already handled it
      const updated = [];
      for (const id of c.payload.ids || []) {
        const rec = msgs.get(id);
        if (!rec) continue;
        const r = m.res[id];
        updated.push({ ...rec, phone: ["deleted", "absent", "mismatch", "error"].includes(r) ? r : "error" });
      }
      await vault.batch([{ store: "cmds", key: c.payload.cmd, del: true }, ...updated.map((u) => ({ store: "msgs", key: u.id, value: u }))]);
      for (const u of updated) msgs.set(u.id, u);
      pending.delete(c.payload.cmd);
    });
  }
  pending.delete(c.payload.cmd);
  await vault.del("cmds", c.payload.cmd);
  if (m.op === "backfill") {
    warning = `Phone queued ${Number(m.queued) || 0} messages.`;
  } else if (m.op === "rules") {
    if (m.ok === true) {
      if (c.payload.set.version > (fwd.rules?.version ?? 0)) await saveFwd({ ...fwd, rules: c.payload.set });
      warning = "Forwarding rules are now in effect on the phone.";
    } else {
      warning = `The phone refused the rule change: ${String(m.error || "invalid rules").slice(0, 200)}. Nothing changed there.`;
    }
  } else if (m.op === "approve" || m.op === "revoke") {
    // The list itself only comes from statuses (a replayed result could be stale): ask for one.
    setTimeout(() => sendHello({ extra: true }), 0);
    warning = m.op === "approve" ? "Number approved on the phone." : "Number removed on the phone. Nothing is forwarded to it any more.";
  } else if (m.op === "rebroker") {
    if (m.ok === true && cfg.moveTo?.cmd === c.payload.cmd) {
      await switchBroker(cfg.moveTo.broker, "Moved to the new broker. If the phone does not appear within 10 minutes it returns to the old one; then use 'Switch back' below.", c.payload.cmd);
    } else {
      delete cfg.moveTo;
      await vault.put("kv", "config", cfg);
      warning = m.stale
        ? "That move is no longer in effect on the phone (it rolled back or was replaced). Nothing changed."
        : "The phone rejected the new broker settings.";
    }
  }
}

// ---------- main view ----------

let renderQueued = false;

/** Coalesce redraws: a burst of 200 incoming messages causes one render, not 200. */
function refresh() {
  if (document.querySelector(".pairbox")) return renderCandidate();
  if (document.getElementById("fwd")) return renderFwd();
  if (renderQueued || !document.getElementById("list")) return;
  renderQueued = true;
  setTimeout(() => {
    renderQueued = false;
    renderMain();
  }, 100);
}

function showMain() {
  const search = el("input", { type: "search", placeholder: "Search sender or text", value: filter });
  search.addEventListener("input", () => {
    filter = search.value.toLowerCase();
    renderMain();
  });
  const days = el("input", { value: "30", inputMode: "numeric", class: "small" });
  const resend = el("input", { type: "checkbox" });
  screen(
    el("header", {},
      el("h1", {}, "SMS Relay"),
      el("span", { class: "actions" },
        el("button", { class: "secondary", on: { click: showForwarding } }, "Forwarding"),
        el("button", { class: "secondary", on: { click: () => { client?.end(true); vault.close(); location.reload(); } } }, "Lock"),
      ),
    ),
    el("div", { id: "status" }),
    el("div", { id: "warning" }),
    el("div", { class: "toolbar" }, search),
    el("div", { id: "list" }),
    // Appears at the bottom of the screen while messages are selected (thumb-reachable on phones).
    el("div", { id: "selbar", class: "selbar", hidden: true },
      el("span", { id: "selcount" }),
      el("button", { id: "del", on: { click: deleteSelected } }, "Delete on phone"),
      el("button", { class: "secondary", on: { click: removeSelected } }, "Remove from archive"),
      el("button", { class: "link", on: { click: () => { selected.clear(); renderMain(); } } }, "Cancel"),
    ),
    el("details", { class: "more" },
      el("summary", {}, "More"),
      el("p", {},
        "Fetch SMS already on the phone from the last ",
        days,
        " days ",
        el("button", {
          class: "secondary",
          on: {
            click: async () => {
              const n = Math.trunc(Number(days.value));
              if (!(n >= 1 && n <= 3650)) return;
              await queueCommand(cmd("backfill", { days: n, resend: resend.checked }));
              warning = "Fetch requested.";
              renderMain();
            },
          },
        }, "Fetch"),
      ),
      el("label", { class: "check" }, resend, " Also re-send messages this or another reader already received (to rebuild a lost archive)"),
      el("h2", {}, "Backup"),
      backupForm(),
      el("h2", {}, "Move to a different broker"),
      el("p", { class: "muted" }, "For when the broker shuts down or a password leaks. The phone tries the new broker for 10 minutes and goes back to the old one if this reader never reaches it there."),
      el("details", {}, el("summary", {}, "New broker settings"), brokerForm({ ...pick(cfg), readerPass: "", phonePass: "" }, "Ask the phone to move", requestMove)),
      cfg.prevBroker
        ? el("p", {}, el("button", {
            class: "secondary",
            on: { click: () => confirm("Reconnect this reader to the previous broker?") && switchBroker(cfg.prevBroker, "Switched back to the previous broker.") },
          }, `Switch back to previous broker (${cfg.prevBroker.host})`))
        : null,
      el("h2", {}, "Danger"),
      el("p", {}, el("button", { class: "link", on: { click: resetAll } }, "Erase this reader (unpair)")),
    ),
  );
  renderMain();
}

const PHONE_STATE = {
  present: null,
  pending: "delete pending",
  deleted: "deleted on phone",
  gone: "deleted on the phone",
  absent: "already gone from phone",
  mismatch: "not deleted: changed on phone",
  error: "delete failed",
};

function renderMain() {
  if (!document.getElementById("status")) return;
  renderStatus();
  renderList();
  renderSelbar();
}

function renderSelbar() {
  const bar = document.getElementById("selbar");
  if (!bar) return;
  bar.hidden = selected.size === 0;
  document.getElementById("selcount").textContent = `${selected.size} selected`;
}

function renderStatus() {
  const status = document.getElementById("status");
  if (!status) return;
  const reachable = Date.now() - lastSeen < reachableMs();
  const s = phoneStatus;
  status.replaceChildren(...[
    el("span", { class: client?.connected ? "ok" : "bad" }, client?.connected ? "Broker connected" : "Broker offline"),
    el("span", { class: reachable ? "ok" : "bad" }, reachable ? "Phone online" : `Phone unreachable (last seen ${ago(lastSeen)})`),
    s ? el("span", {}, `Battery ${s.bat}%${s.chg ? " charging" : " NOT charging"}`) : null,
    s && !s.role ? el("span", { class: "bad" }, "Phone app is NOT the default SMS app") : null,
    s && s.doze === false ? el("span", { class: "bad" }, "Battery optimisation is ON for the app: it may stop during power cuts") : null,
    s && s.net === "cell" ? el("span", {}, "on mobile data") : null,
    s && s.queue ? el("span", {}, `${s.queue} waiting on phone`) : null,
    pending.size ? el("span", {}, `${pending.size} command(s) pending`) : null,
    s && s.fwdBlocked ? el("span", { class: "bad" }, `${s.fwdBlocked} forward(s) held back by the hourly/daily limit`) : null,
  ].filter(Boolean));
  document.getElementById("warning").replaceChildren(
    warning ? el("p", { class: "warn" }, warning, " ", el("button", { class: "link", on: { click: () => { warning = null; renderStatus(); } } }, "dismiss")) : "",
  );
}

const when = (m) => m.rcvd || m.got;
let fwdTargets = new Map(); // original message id -> numbers a rule forwarded it to
const KIND_LABEL = { sent: "Sent", draft: "Draft", failed: "Not sent" };

/** Conversation list, one conversation, or (while searching) matching messages from all of them. */
function renderList() {
  const list = document.getElementById("list");
  const live = [...msgs.values()].filter((m) => !m.removed);
  if (filter) {
    const hits = live
      .filter((m) => addrOf(m).toLowerCase().includes(filter) || m.body.toLowerCase().includes(filter))
      .sort((a, b) => when(b) - when(a));
    return list.replaceChildren(...paged(hits, (m) => messageCard(m, true)), ...(hits.length ? [] : [el("p", { class: "muted" }, "No matches.")]));
  }
  fwdTargets = new Map();
  for (const m of live) if (m.fwdOf) fwdTargets.set(m.fwdOf, [...(fwdTargets.get(m.fwdOf) ?? []), addrOf(m)]);
  if (view.addr == null) return renderConversations(list, live);
  renderThread(list, live.filter((m) => addrOf(m) === view.addr));
}

/** The newest [shown] items, with a "Show more" line when there are more. */
function paged(items, render) {
  const more = items.length > shown
    ? el("p", {}, `Showing ${shown} of ${items.length}. `, el("button", { class: "secondary", on: { click: () => { shown += PAGE; renderMain(); } } }, "Show more"))
    : null;
  return [...items.slice(0, shown).map(render), ...(more ? [more] : [])];
}

function renderConversations(list, live) {
  const latest = new Map();
  for (const m of live) {
    const a = addrOf(m);
    const cur = latest.get(a);
    if (!cur || when(m) > when(cur.last)) latest.set(a, { last: m, count: (cur?.count ?? 0) + 1 });
    else cur.count++;
  }
  const convs = [...latest.entries()].sort((a, b) => when(b[1].last) - when(a[1].last));
  list.replaceChildren(...(convs.length
    ? paged(convs, ([addr, { last, count }]) => el("div", {
        class: "conv",
        on: { click: () => { view = { addr }; selected.clear(); shown = PAGE; renderMain(); } },
      },
        el("div", { class: "avatar" }, (addr.match(/[A-Za-z0-9]/)?.[0] ?? "#").toUpperCase()),
        el("div", { class: "conv-text" },
          el("div", { class: "conv-top" }, el("strong", {}, addr), el("span", { class: "muted" }, fmtTime(when(last)))),
          el("div", { class: "muted conv-snippet" },
            last.kind === "draft" ? "Draft: " : last.kind === "sent" || last.kind === "failed" ? "You: " : "",
            last.body),
        ),
        el("span", { class: "count muted" }, String(count)),
      ))
    : [el("p", { class: "muted" }, "No messages yet.")]));
}

function renderThread(list, items) {
  items.sort((a, b) => when(b) - when(a)); // newest first for paging, shown oldest first
  const page = paged(items, (m) => messageCard(m, false));
  const more = page.length > Math.min(items.length, shown) ? [page.pop()] : [];
  list.replaceChildren(
    el("div", { class: "thread-head" },
      el("button", { class: "link", on: { click: () => { view = { addr: null }; selected.clear(); renderMain(); } } }, "← Conversations"),
      el("strong", {}, view.addr),
    ),
    ...more,
    ...page.reverse(),
  );
}

/** A message bubble (incoming left, sent and drafts right) with its selection box and phone state. */
function messageCard(m, showAddr) {
  const box = el("input", { type: "checkbox", checked: selected.has(m.id) });
  box.addEventListener("change", () => {
    if (box.checked) selected.add(m.id);
    else selected.delete(m.id);
    renderSelbar();
  });
  const kind = m.kind || "in";
  const state = PHONE_STATE[m.phone];
  return el("div", { class: `msg ${kind === "in" ? "in" : "out"}` + (m.phone === "deleted" || m.phone === "gone" ? " gone" : "") },
    el("label", { class: "sel" }, box),
    el("div", { class: `bubble ${kind}` },
      showAddr ? el("div", { class: "meta" }, el("strong", {}, addrOf(m))) : null,
      el("div", { class: "body" }, m.body),
      m.cut ? el("div", { class: "muted" }, `(${m.cut} characters cut: message too long to relay)`) : null,
      el("div", { class: "meta" },
        KIND_LABEL[kind] && !m.rule ? el("span", { class: "badge" }, KIND_LABEL[kind]) : null,
        m.rule ? el("span", { class: "badge fwd", title: "Sent automatically by a forwarding rule" }, `Forwarded · rule "${m.rule}"`) : null,
        fwdTargets.has(m.id) ? el("span", { class: "badge fwd" }, `Forwarded to ${fwdTargets.get(m.id).join(", ")}`) : null,
        el("span", { class: "muted" }, fmtTime(when(m))),
        state ? el("span", { class: "badge " + m.phone }, state) : null,
      ),
    ),
  );
}

async function deleteSelected() {
  const ids = [...selected].filter((id) => {
    const p = msgs.get(id)?.phone;
    return p === "present" || p === "error" || p === "mismatch";
  });
  if (!ids.length) return;
  if (!confirm(`Delete ${ids.length} message(s) from the phone? This cannot be undone.`)) return;
  for (let i = 0; i < ids.length; i += MAX_IDS) {
    const chunk = ids.slice(i, i + MAX_IDS);
    await serially(() => queueCommand(cmd("delete", { ids: chunk }), chunk.map((id) => ({ ...msgs.get(id), phone: "pending" }))));
  }
  selected.clear();
  renderMain();
}

async function removeSelected() {
  const ids = [...selected];
  if (!ids.length) return;
  if (!confirm(`Remove ${ids.length} message(s) from this archive only? They stay on the phone unless deleted there.`)) return;
  await serially(async () => {
    // Keep a tombstone without content so a replayed or re-sent copy is not shown again.
    const tombs = ids.map((id) => ({ id, removed: true, addr: "", body: "", phone: msgs.get(id)?.phone }));
    await vault.batch(tombs.map((t) => ({ store: "msgs", key: t.id, value: t })));
    for (const t of tombs) msgs.set(t.id, t);
  });
  selected.clear();
  renderMain();
}

// ---------- forwarding ----------
//
// Rules are written here and applied by the phone. Destinations need both sides: a number is
// proposed on the phone (behind its screen lock) and approved here, so neither someone holding this
// reader alone nor someone holding the phone alone can start sending messages to a new number.

let editing = null; // the rule being edited (editor model), or null

async function saveFwd(next) {
  fwd = next;
  await vault.put("kv", "forwarding", fwd);
}

/** Allowlist and rules from a fresh status (the phone attaches the rules only when ours are stale). */
async function onForwardingState(m) {
  const allow = R.cleanAllow(m.allow) ?? fwd.allow;
  const rv = Number(m.rv);
  let rules = fwd.rules;
  if (rv === 0) rules = null;
  else if (m.rules && Number(m.rules.version) === rv && Array.isArray(m.rules.rules)) rules = { version: rv, rules: m.rules.rules };
  if (JSON.stringify(allow) !== JSON.stringify(fwd.allow) || rules !== fwd.rules) await saveFwd({ allow, rules });
}

const approved = () => fwd.allow.filter((e) => e.a).map((e) => e.n);

/** Rule-set commands not yet answered, newest version last. */
const pendingRuleSets = () => [...pending.values()].filter((c) => c.payload.t === "rules").sort((a, b) => a.payload.set.version - b.payload.set.version);

/** What the rules will be once the phone applies everything sent: edits build on this. */
function workingRules() {
  const p = pendingRuleSets();
  return p.length ? p[p.length - 1].payload.set.rules : fwd.rules?.rules ?? [];
}

let ruleEdits = Promise.resolve();

/**
 * Apply [change] (current rules -> new rules) and send the result. Edits run one at a time, each
 * reading the rules and versions only after the previous one is stored, so two quick edits (a
 * toggle while a save is still being written) never share a version or undo each other.
 */
function sendRules(change) {
  const run = ruleEdits.then(async () => {
    const version = Math.max(fwd.rules?.version ?? 0, Number(phoneStatus?.rv) || 0, ...pendingRuleSets().map((c) => c.payload.set.version));
    const set = { version: version + 1, rules: change(workingRules()) };
    const problem = R.setProblem(set);
    if (problem) {
      alert(problem);
      return false;
    }
    await queueCommand(cmd("rules", { set }));
    warning = "Rule change sent. It takes effect when the phone confirms it.";
    return true;
  });
  ruleEdits = run.catch(() => {});
  return run;
}

function showForwarding() {
  editing = null;
  screen(
    el("header", {},
      el("h1", {}, "Forwarding"),
      el("button", { class: "secondary", on: { click: () => { editing = null; showMain(); } } }, "← Messages"),
    ),
    el("div", { id: "fwd" },
      el("div", { id: "status" }),
      el("div", { id: "warning" }),
      el("p", { class: "muted" },
        "When an incoming SMS matches a rule, the phone sends a copy as a new SMS that starts with \"From <sender>: \". ",
        "Copies show up under the destination's conversation, marked with the rule's name. ",
        `At most ${R.LIMITS.perHour} copies an hour and ${R.LIMITS.perDay} a day are sent, and long messages are cut. Only messages that arrive while forwarding is set up are forwarded, never older ones.`),
      el("h2", {}, "Approved numbers"),
      el("div", { id: "fwd-allow" }),
      el("h2", {}, "Rules"),
      el("div", { id: "fwd-rules" }),
    ),
  );
  renderFwd();
}

function renderFwd() {
  renderStatus();
  renderAllow();
  if (!editing) renderRules(); // never rebuild the editor under the user's fingers
}

function renderAllow() {
  const box = document.getElementById("fwd-allow");
  if (!box) return;
  const rows = fwd.allow.map((e) => el("div", { class: "row" },
    el("span", { class: "grow" }, el("strong", {}, e.n), " ", el("span", { class: e.a ? "badge deleted" : "badge mismatch" }, e.a ? "approved" : "waiting for your approval")),
    e.a
      ? el("button", { class: "secondary", on: { click: () => confirm(`Stop forwarding to ${e.n}? Rules that name it stop sending to it as soon as the phone receives this.`) && queueCommand(cmd("revoke", { nums: [e.n] })).then(refresh) } }, "Remove")
      : el("span", { class: "actions" },
          el("button", { on: { click: () => confirm(`Approve ${e.n}?\n\nOnly approve a number you or someone you trust proposed on the phone just now. If you did not expect it, choose Cancel and press Reject.`) && queueCommand(cmd("approve", { nums: [e.n], p: [e.p] })).then(refresh) } }, "Approve"),
          el("button", { class: "secondary", on: { click: () => queueCommand(cmd("revoke", { nums: [e.n] })).then(refresh) } }, "Reject"),
        ),
  ));
  box.replaceChildren(
    ...(rows.length ? rows : [el("p", { class: "muted" }, phoneStatus ? "No numbers yet." : "Waiting for the phone to report its list…")]),
    el("p", { class: "hint" },
      "To add a number: on the phone open Messages → Settings → Forwarding (it asks for the screen lock) and propose it. ",
      "It then shows here as waiting; approve it here. A number cannot be added from this page alone, and removing it on either side stops forwarding to it."),
  );
}

function renderRules() {
  const box = document.getElementById("fwd-rules");
  if (!box) return;
  const rules = workingRules();
  const waiting = pendingRuleSets().length;
  const ok = approved();
  box.replaceChildren(...[
    waiting ? el("p", { class: "warn" }, "Changes waiting for the phone to come online. You can keep editing; the newest version wins.") : null,
    ...rules.map((r) => {
      const model = R.fromWire(r);
      const dead = r.forwardTo.filter((n) => !ok.includes(n));
      return el("div", { class: "rule" + (r.enabled === false ? " off" : "") },
        el("div", { class: "row" },
          el("strong", { class: "grow" }, r.name || r.id),
          el("label", { class: "check" },
            el("input", { type: "checkbox", checked: r.enabled !== false, on: { change: (e) => sendRules((cur) => cur.map((x) => (x.id === r.id ? { ...x, enabled: e.target.checked } : x))).then(renderRules) } }),
            "On"),
        ),
        el("p", {}, model ? `Forward when ${R.describe(model)}` : "(made in a format this editor cannot show)"),
        el("p", { class: "muted" }, "To: ", r.forwardTo.join(", ")),
        dead.length ? el("p", { class: "bad" }, `Not approved, so nothing is sent to: ${dead.join(", ")}`) : null,
        el("div", { class: "actions" },
          model ? el("button", { class: "secondary", on: { click: () => openEditor(structuredClone(model)) } }, "Edit") : null,
          el("button", { class: "link", on: { click: () => confirm(`Delete the rule "${r.name || r.id}"?`) && sendRules((cur) => cur.filter((x) => x.id !== r.id)).then(renderRules) } }, "Delete"),
        ),
      );
    }),
    rules.length ? null : el("p", { class: "muted" }, "No rules yet."),
    rules.length >= R.LIMITS.rules ? null : el("p", {}, el("button", { on: { click: () => openEditor(R.blankRule()) } }, "Add a rule")),
  ].filter(Boolean));
}

function select(options, value, onChange) {
  const s = el("select", { on: { change: () => onChange(s.value) } }, ...options.map((o) => el("option", { value: o.key }, o.label)));
  s.value = value;
  return s;
}

/** The rule editor: groups of conditions, destinations, a live preview and a try-it box. */
function openEditor(model) {
  editing = model;
  const box = document.getElementById("fwd-rules");
  const preview = el("p", { class: "preview" });
  const issues = el("ul", { class: "error" });
  const trySender = el("input", { placeholder: "Sender, e.g. EXBANK" });
  const tryBody = el("input", { placeholder: "Message text" });
  const tryOut = el("p", {});
  const save = el("button", {}, "Save rule");
  const isNew = !workingRules().some((r) => r.id === model.id);

  const update = () => {
    const others = workingRules();
    const list = R.problems(model, approved(), others);
    preview.textContent = `Forward when ${R.describe(model)}${model.forwardTo.length ? `, to ${model.forwardTo.join(", ")}` : ""}.`;
    issues.replaceChildren(...list.map((p) => el("li", {}, p)));
    save.disabled = list.length > 0;
    tryOut.textContent = trySender.value || tryBody.value
      ? R.matches(model, trySender.value, tryBody.value) ? "✓ This message would be forwarded." : "✗ This message would not be forwarded."
      : "Type a sample sender and text to check the rule.";
  };
  trySender.addEventListener("input", update);
  tryBody.addEventListener("input", update);

  const groupsBox = el("div", {});
  const MATCH = [{ key: "all", label: "ALL" }, { key: "any", label: "ANY" }];
  const word = (m) => (m === "all" ? "and" : "or");
  const renderGroups = () => {
    const multi = model.groups.length > 1;
    groupsBox.replaceChildren(...[
      multi
        ? el("div", { class: "match" }, "Forward when ", select(MATCH, model.match, (v) => { model.match = v; renderGroups(); }), " of these groups match")
        : null,
      ...model.groups.flatMap((g, gi) => [
        gi > 0 ? el("div", { class: "or" }, word(model.match).toUpperCase()) : null,
        el("div", { class: "group" },
          g.conds.length > 1
            ? el("div", { class: "match small" }, multi ? `Group ${gi + 1}: ` : "", "match ", select(MATCH, g.match, (v) => { g.match = v; renderGroups(); }), " of these conditions")
            : multi ? el("div", { class: "muted small" }, `Group ${gi + 1}`) : null,
          ...g.conds.map((c, ci) => el("div", { class: "cond" },
            ci > 0 ? el("span", { class: "and muted" }, word(g.match)) : null,
            select(R.FIELDS, c.field, (v) => { c.field = v; update(); }),
            select(R.OPS, c.op, (v) => { c.op = v; update(); }),
            (() => {
              const v = el("input", { value: c.value, maxLength: R.LIMITS.value, placeholder: c.field === "sender" ? "e.g. EXBANK" : "e.g. OTP" });
              v.addEventListener("input", () => { c.value = v.value; update(); });
              return v;
            })(),
            g.conds.length > 1 || multi
              ? el("button", { class: "link", title: "Remove this condition", on: { click: () => {
                  g.conds.splice(ci, 1);
                  if (!g.conds.length) model.groups.splice(gi, 1);
                  renderGroups();
                } } }, "Remove")
              : null,
          )),
          el("button", { class: "secondary small", on: { click: () => { g.conds.push({ field: "body", op: "contains", value: "" }); renderGroups(); } } }, "+ Add a condition"),
        ),
      ]),
    ].filter(Boolean));
    update();
  };

  const ok = approved();
  const dests = [...new Set([...ok, ...model.forwardTo])].map((n) => el("label", { class: "check" },
    el("input", { type: "checkbox", checked: model.forwardTo.includes(n), on: { change: (e) => {
      model.forwardTo = e.target.checked ? [...model.forwardTo, n] : model.forwardTo.filter((x) => x !== n);
      update();
    } } }),
    n, ok.includes(n) ? "" : " (no longer approved)"));

  const name = el("input", { value: model.name, maxLength: R.LIMITS.name, placeholder: "e.g. Bank codes" });
  name.addEventListener("input", () => { model.name = name.value; update(); });
  const enabled = el("input", { type: "checkbox", checked: model.enabled, on: { change: () => { model.enabled = enabled.checked; } } });

  save.addEventListener("click", async () => {
    if (R.problems(model, approved(), workingRules()).length) return update();
    const wire = R.toWire(model);
    const ok = await sendRules((cur) => (cur.some((r) => r.id === wire.id) ? cur.map((r) => (r.id === wire.id ? wire : r)) : [...cur, wire]));
    if (ok) {
      editing = null;
      renderRules();
    }
  });

  box.replaceChildren(el("div", { class: "editor" },
    el("h2", {}, isNew ? "New rule" : "Edit rule"),
    field("Name (shown on forwarded copies)", name),
    el("label", { class: "check" }, enabled, "Rule is on"),
    el("h3", {}, "Forward a message when"),
    groupsBox,
    el("button", { class: "secondary small", on: { click: () => { model.groups.push(R.blankGroup()); renderGroups(); } } }, "+ Add a group"),
    el("p", { class: "hint" },
      "Choose ALL (and) or ANY (or) for the conditions in a group, and for how the groups combine. ",
      "Examples: \"sender contains EXBANK and text contains OTP\" is one group set to ALL; ",
      "\"sender contains EXBANK and (text contains OTP or text contains code)\" is two groups combined with ALL, the second set to ANY. ",
      "Upper and lower case are ignored. \"Sender\" is the number or name the phone shows, e.g. EXBANK or +15555550100."),
    el("h3", {}, "Send the copy to"),
    dests.length ? el("div", { class: "dests" }, ...dests) : el("p", { class: "bad" }, "No approved numbers yet. Propose one on the phone first (see Approved numbers above)."),
    el("p", { class: "hint" }, `Up to ${R.LIMITS.destinations} numbers. Only approved numbers can be picked.`),
    el("h3", {}, "Preview"),
    preview,
    el("h3", {}, "Try it with a sample message"),
    el("div", { class: "try" }, trySender, tryBody, tryOut),
    issues,
    el("div", { class: "actions" },
      save,
      el("button", { class: "secondary", on: { click: () => { editing = null; renderRules(); } } }, "Cancel"),
    ),
  ));
  renderGroups();
}

setInterval(renderStatus, 15_000); // "last seen" ages; the list only changes on events
start();

// Encrypted local storage for the reader. Everything (config, keys, messages, pending commands)
// is AES-GCM encrypted with a key derived from the passphrase; IndexedDB only sees ciphertext.

const DB_NAME = "smsrelay";
const ITERATIONS = 600000;
const te = new TextEncoder();
const td = new TextDecoder();

function idb() {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, 1);
    req.onupgradeneeded = () => {
      const db = req.result;
      db.createObjectStore("kv");
      db.createObjectStore("msgs");
      db.createObjectStore("cmds");
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

function tx(db, store, mode, fn) {
  return new Promise((resolve, reject) => {
    const t = db.transaction(store, mode);
    const s = t.objectStore(store);
    let result;
    Promise.resolve(fn(s)).then((r) => (result = r));
    t.oncomplete = () => resolve(result);
    t.onerror = () => reject(t.error);
    t.onabort = () => reject(t.error);
  });
}

function reqP(req) {
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

async function deriveKey(pass, salt) {
  const base = await crypto.subtle.importKey("raw", te.encode(pass), "PBKDF2", false, ["deriveKey"]);
  return crypto.subtle.deriveKey(
    { name: "PBKDF2", hash: "SHA-256", salt, iterations: ITERATIONS },
    base,
    { name: "AES-GCM", length: 256 },
    false,
    ["encrypt", "decrypt"],
  );
}

export class Vault {
  constructor(db, key) {
    this.db = db;
    this.key = key;
  }

  static async exists() {
    const db = await idb();
    const meta = await tx(db, "kv", "readonly", (s) => reqP(s.get("vault")));
    db.close();
    return !!meta;
  }

  static async create(pass) {
    const db = await idb();
    const salt = crypto.getRandomValues(new Uint8Array(16));
    const key = await deriveKey(pass, salt);
    const v = new Vault(db, key);
    const check = await v.#seal("vault-ok");
    await tx(db, "kv", "readwrite", (s) => s.put({ salt, check }, "vault"));
    return v;
  }

  /** Returns null on a wrong passphrase. */
  static async unlock(pass) {
    const db = await idb();
    const meta = await tx(db, "kv", "readonly", (s) => reqP(s.get("vault")));
    if (!meta) throw new Error("no vault");
    const v = new Vault(db, await deriveKey(pass, meta.salt));
    try {
      if ((await v.#open(meta.check)) === "vault-ok") return v;
    } catch {
      // wrong passphrase
    }
    db.close(); // an open connection would block "erase this reader"
    return null;
  }

  /** Resolves only once the database is really gone; blocked means another tab still has it open. */
  static async destroy() {
    await new Promise((resolve, reject) => {
      const req = indexedDB.deleteDatabase(DB_NAME);
      req.onsuccess = resolve;
      req.onerror = () => reject(req.error);
      req.onblocked = () => reject(new Error("blocked: close other tabs of this page"));
    });
  }

  // Records are padded to power-of-two sizes (min 256 B) so stored sizes do not reveal SMS lengths.
  async #seal(value) {
    const data = te.encode(JSON.stringify(value));
    let size = 256;
    while (size < data.length + 4) size *= 2;
    const padded = new Uint8Array(size);
    new DataView(padded.buffer).setUint32(0, data.length);
    padded.set(data, 4);
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const ct = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv }, this.key, padded));
    return { iv, ct };
  }

  async #open(rec) {
    const pt = new Uint8Array(await crypto.subtle.decrypt({ name: "AES-GCM", iv: rec.iv }, this.key, rec.ct));
    const len = pt.length >= 4 ? new DataView(pt.buffer).getUint32(0) : -1;
    // Records written before padding existed are plain JSON (the "length" would be absurd).
    if (len < 0 || len > pt.length - 4) return JSON.parse(td.decode(pt));
    return JSON.parse(td.decode(pt.subarray(4, 4 + len)));
  }

  async get(store, key) {
    const rec = await tx(this.db, store, "readonly", (s) => reqP(s.get(key)));
    return rec ? this.#open(rec) : undefined;
  }

  async put(store, key, value) {
    const rec = await this.#seal(value);
    await tx(this.db, store, "readwrite", (s) => s.put(rec, key));
  }

  /**
   * Apply several writes atomically: ops are {store, key, value} or {store, key, del: true}.
   * Everything is encrypted first, because an IndexedDB transaction commits as soon as it is idle.
   */
  async batch(ops) {
    const sealed = await Promise.all(ops.map(async (o) => (o.del ? o : { ...o, rec: await this.#seal(o.value) })));
    const stores = [...new Set(ops.map((o) => o.store))];
    await new Promise((resolve, reject) => {
      const t = this.db.transaction(stores, "readwrite");
      for (const o of sealed) {
        const s = t.objectStore(o.store);
        if (o.del) s.delete(o.key);
        else s.put(o.rec, o.key);
      }
      t.oncomplete = resolve;
      t.onerror = () => reject(t.error);
      t.onabort = () => reject(t.error);
    });
  }

  async del(store, key) {
    await tx(this.db, store, "readwrite", (s) => s.delete(key));
  }

  async all(store) {
    const recs = await tx(this.db, store, "readonly", (s) => reqP(s.getAll()));
    return Promise.all(recs.map((r) => this.#open(r)));
  }

  close() {
    this.db.close();
    this.key = null;
  }
}

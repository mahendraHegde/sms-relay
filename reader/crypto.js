// Envelope format and key derivation. See docs/PROTOCOL.md; must match
// android/.../core/Envelope.kt byte for byte. Runs in browsers and Node (WebCrypto).

export const KIND_UP = 1;
export const KIND_DOWN = 2;
export const KIND_PAIR = 3;

const subtle = globalThis.crypto.subtle;
const te = new TextEncoder();
const td = new TextDecoder("utf-8", { fatal: true });
const INFO_PREFIX = te.encode("sms-relay/v1");
const HEADER = 46;
const SIG = 64;
const TAG = 16;
const MIN_BUCKET = 512;
const MAX_BUCKET = 65536;

// PKCS#8 wrappers so raw 32-byte private keys can be imported.
const PKCS8_X25519 = hex("302e020100300506032b656e04220420");
const PKCS8_ED25519 = hex("302e020100300506032b657004220420");

export class Invalid extends Error {}

export function hex(s) {
  return Uint8Array.from(s.match(/../g).map((b) => parseInt(b, 16)));
}

export function toHex(b) {
  return Array.from(b, (x) => x.toString(16).padStart(2, "0")).join("");
}

export function concat(...parts) {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let o = 0;
  for (const p of parts) {
    out.set(p, o);
    o += p.length;
  }
  return out;
}

export function b64e(bytes) {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function b64d(str) {
  try {
    const s = atob(str.replace(/-/g, "+").replace(/_/g, "/"));
    return Uint8Array.from(s, (c) => c.charCodeAt(0));
  } catch {
    return new Uint8Array(0);
  }
}

export function randomHex(n) {
  return toHex(crypto.getRandomValues(new Uint8Array(n)));
}

/** New long-term identity: Ed25519 for signing, X25519 for encryption. */
export async function generateIdentity() {
  const sig = await subtle.generateKey({ name: "Ed25519" }, true, ["sign", "verify"]);
  const enc = await subtle.generateKey({ name: "X25519" }, true, ["deriveBits"]);
  return {
    sigPriv: new Uint8Array(await subtle.exportKey("pkcs8", sig.privateKey)),
    sigPub: new Uint8Array(await subtle.exportKey("raw", sig.publicKey)),
    encPriv: new Uint8Array(await subtle.exportKey("pkcs8", enc.privateKey)),
    encPub: new Uint8Array(await subtle.exportKey("raw", enc.publicKey)),
  };
}

export function importSigPriv(pkcs8) {
  return subtle.importKey("pkcs8", pkcs8, { name: "Ed25519" }, false, ["sign"]);
}

export function importEncPriv(pkcs8) {
  return subtle.importKey("pkcs8", pkcs8, { name: "X25519" }, false, ["deriveBits"]);
}

export function rawToPkcs8X25519(raw) {
  return concat(PKCS8_X25519, raw);
}

export function rawToPkcs8Ed25519(raw) {
  return concat(PKCS8_ED25519, raw);
}

async function x25519(priv, pubRaw) {
  if (pubRaw.length !== 32) throw new Invalid("bad public key");
  let shared;
  try {
    const pub = await subtle.importKey("raw", pubRaw, { name: "X25519" }, false, []);
    shared = new Uint8Array(await subtle.deriveBits({ name: "X25519", public: pub }, priv, 256));
  } catch {
    throw new Invalid("low-order key");
  }
  if (shared.every((b) => b === 0)) throw new Invalid("low-order key");
  return shared;
}

async function deriveKey(kind, shared, epk, recipientEncPub, senderSigPub) {
  const info = concat(INFO_PREFIX, Uint8Array.of(kind), kind === KIND_PAIR ? new Uint8Array(0) : senderSigPub);
  const ikm = await subtle.importKey("raw", shared, "HKDF", false, ["deriveKey"]);
  return subtle.deriveKey(
    { name: "HKDF", hash: "SHA-256", salt: concat(epk, recipientEncPub), info },
    ikm,
    { name: "AES-GCM", length: 256 },
    false,
    ["encrypt", "decrypt"],
  );
}

function aad(header, kind, senderSigPub) {
  return concat(header.subarray(0, 34), kind === KIND_PAIR ? new Uint8Array(0) : senderSigPub);
}

export function pad(data) {
  const need = data.length + 4;
  if (need > MAX_BUCKET) throw new Error("payload too large");
  let bucket = MIN_BUCKET;
  while (bucket < need) bucket *= 2;
  const out = new Uint8Array(bucket);
  new DataView(out.buffer).setUint32(0, data.length);
  out.set(data, 4);
  return out;
}

export function unpad(p) {
  if (p.length < 4) throw new Invalid("short plaintext");
  const len = new DataView(p.buffer, p.byteOffset, p.byteLength).getUint32(0);
  if (len > p.length - 4) throw new Invalid("bad length");
  return p.subarray(4, 4 + len);
}

/**
 * @param senderSigPriv CryptoKey (Ed25519, sign)
 * @param senderSigPub  Uint8Array(32)
 * @param recipientEncPub Uint8Array(32)
 */
export async function seal(kind, senderSigPriv, senderSigPub, recipientEncPub, payload) {
  const eph = await subtle.generateKey({ name: "X25519" }, true, ["deriveBits"]);
  const epk = new Uint8Array(await subtle.exportKey("raw", eph.publicKey));
  const nonce = crypto.getRandomValues(new Uint8Array(12));
  const header = concat(Uint8Array.of(1, kind), epk, nonce);
  const shared = await x25519(eph.privateKey, recipientEncPub);
  const key = await deriveKey(kind, shared, epk, recipientEncPub, senderSigPub);
  const ct = new Uint8Array(
    await subtle.encrypt(
      { name: "AES-GCM", iv: nonce, additionalData: aad(header, kind, senderSigPub), tagLength: 128 },
      key,
      pad(te.encode(JSON.stringify(payload))),
    ),
  );
  const signed = concat(header, ct);
  const sig = new Uint8Array(await subtle.sign({ name: "Ed25519" }, senderSigPriv, signed));
  return concat(signed, sig);
}

async function verify(pubRaw, data, sig) {
  try {
    const pub = await subtle.importKey("raw", pubRaw, { name: "Ed25519" }, false, ["verify"]);
    return await subtle.verify({ name: "Ed25519" }, pub, sig, data);
  } catch {
    return false;
  }
}

/**
 * Verify and decrypt. For KIND_PAIR pass senderSigPub = null; the key inside the plaintext is
 * checked against the signature and the caller decides whether to trust it.
 */
export async function open(env, expectedKind, senderSigPub, recipientEncPriv, recipientEncPub) {
  env = new Uint8Array(env);
  if (env.length < HEADER + TAG + SIG) throw new Invalid("short envelope");
  if (env[0] !== 1) throw new Invalid("bad version");
  const kind = env[1];
  if (kind !== expectedKind) throw new Invalid("unexpected kind");
  if ((kind === KIND_PAIR) !== (senderSigPub == null)) throw new Invalid("sender key mismatch for kind");

  const signedLen = env.length - SIG;
  const signed = env.subarray(0, signedLen);
  const sig = env.subarray(signedLen);
  if (senderSigPub != null && !(await verify(senderSigPub, signed, sig))) throw new Invalid("bad signature");

  const header = env.subarray(0, HEADER);
  const epk = env.subarray(2, 34);
  const nonce = env.subarray(34, 46);
  const shared = await x25519(recipientEncPriv, epk);
  const key = await deriveKey(kind, shared, epk, recipientEncPub, senderSigPub);
  let plain;
  try {
    plain = new Uint8Array(
      await subtle.decrypt(
        { name: "AES-GCM", iv: nonce, additionalData: aad(header, kind, senderSigPub), tagLength: 128 },
        key,
        env.subarray(HEADER, signedLen),
      ),
    );
  } catch {
    throw new Invalid("decrypt failed");
  }
  let msg;
  try {
    msg = JSON.parse(td.decode(unpad(plain)));
  } catch {
    throw new Invalid("bad payload");
  }
  if (msg === null || typeof msg !== "object" || Array.isArray(msg)) throw new Invalid("bad payload");

  if (kind === KIND_PAIR) {
    const claimed = b64d(String(msg.sigPub || ""));
    if (claimed.length !== 32 || !(await verify(claimed, signed, sig))) throw new Invalid("bad pair signature");
  }
  return msg;
}

export async function fingerprint(phoneSig, phoneEnc, readerSig, readerEnc) {
  const h = new Uint8Array(await subtle.digest("SHA-256", concat(phoneSig, phoneEnc, readerSig, readerEnc)));
  return toHex(h.subarray(0, 10)).match(/.{4}/g).join("-");
}

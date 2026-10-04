// Run: node --test reader/test/*.mjs   (after the Gradle tests, which write the Kotlin vectors)
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import * as C from "../crypto.js";

const vecDir = fileURLToPath(new URL("../../test-vectors/", import.meta.url));

async function identity() {
  const id = await C.generateIdentity();
  return { ...id, sigKey: await C.importSigPriv(id.sigPriv), encKey: await C.importEncPriv(id.encPriv) };
}

test("round trip, unicode, buckets", async () => {
  const phone = await identity();
  const reader = await identity();
  const msg = { t: "sms", body: "OTP 123456 ünïcødé 🙂" };
  const env = await C.seal(C.KIND_UP, phone.sigKey, phone.sigPub, reader.encPub, msg);
  assert.deepEqual(await C.open(env, C.KIND_UP, phone.sigPub, reader.encKey, reader.encPub), msg);
  const small = await C.seal(C.KIND_UP, phone.sigKey, phone.sigPub, reader.encPub, { b: "x" });
  const mid = await C.seal(C.KIND_UP, phone.sigKey, phone.sigPub, reader.encPub, { b: "x".repeat(400) });
  assert.equal(small.length, mid.length);
});

test("envelope sizes are pinned (same numbers as EnvelopeTest)", async () => {
  const phone = await identity();
  const reader = await identity();
  const small = await C.seal(C.KIND_UP, phone.sigKey, phone.sigPub, reader.encPub, { b: "x" });
  assert.equal(small.length, 2 + 32 + 12 + 512 + 16 + 64);
  const big = await C.seal(C.KIND_UP, phone.sigKey, phone.sigPub, reader.encPub, { b: "x".repeat(600) });
  assert.equal(big.length, 2 + 32 + 12 + 1024 + 16 + 64);
  assert.equal(C.pad(new Uint8Array(65532)).length, 65536);
  assert.throws(() => C.pad(new Uint8Array(65533)));
});

test("tampering, wrong sender, reflection are rejected", async () => {
  const phone = await identity();
  const reader = await identity();
  const env = await C.seal(C.KIND_UP, phone.sigKey, phone.sigPub, reader.encPub, { t: "sms" });
  for (let i = 0; i < env.length; i += 11) {
    const bad = env.slice();
    bad[i] ^= 1;
    await assert.rejects(C.open(bad, C.KIND_UP, phone.sigPub, reader.encKey, reader.encPub), C.Invalid);
  }
  await assert.rejects(C.open(env, C.KIND_UP, reader.sigPub, reader.encKey, reader.encPub), C.Invalid);
  await assert.rejects(C.open(env, C.KIND_DOWN, phone.sigPub, reader.encKey, reader.encPub), C.Invalid);
});

test("pair claiming a foreign key is rejected", async () => {
  const phone = await identity();
  const reader = await identity();
  const lie = { t: "pair", sigPub: C.b64e(reader.sigPub) };
  const env = await C.seal(C.KIND_PAIR, phone.sigKey, phone.sigPub, reader.encPub, lie);
  await assert.rejects(C.open(env, C.KIND_PAIR, null, reader.encKey, reader.encPub), C.Invalid);
});

test("opens envelopes produced by Kotlin", { skip: !existsSync(vecDir + "kotlin.json") }, async () => {
  const k = JSON.parse(readFileSync(vecDir + "kotlin.json", "utf8"));
  const readerEncKey = await C.importEncPriv(C.rawToPkcs8X25519(C.b64d(k.readerEncPriv)));
  const readerEncPub = await (async () => {
    // Derive the public key by exporting from a re-imported extractable copy.
    const key = await crypto.subtle.importKey("pkcs8", C.rawToPkcs8X25519(C.b64d(k.readerEncPriv)), { name: "X25519" }, true, ["deriveBits"]);
    const jwk = await crypto.subtle.exportKey("jwk", key);
    return C.b64d(jwk.x);
  })();
  for (const v of k.vectors) {
    const sender = v.kind === C.KIND_PAIR ? null : C.b64d(k.phoneSigPub);
    const got = await C.open(C.b64d(v.env), v.kind, sender, readerEncKey, readerEncPub);
    assert.deepEqual(got, v.expect);
  }
  assert.equal(
    await C.fingerprint(C.b64d(k.phoneSigPub), C.b64d(k.phoneEncPub), C.b64d(k.readerSigPub), readerEncPub),
    k.fingerprint,
  );
});

test("writes vectors for Kotlin", async () => {
  const phone = await identity();
  const reader = await identity();
  const payloads = [
    { t: "delete", cmd: C.randomHex(16), ts: 1800000000000, ids: [C.randomHex(16)] },
    { t: "hello", cmd: C.randomHex(16), ts: 1800000000000, note: "ünïcødé 🙂" },
  ];
  const vectors = [];
  for (const p of payloads) {
    const env = await C.seal(C.KIND_DOWN, reader.sigKey, reader.sigPub, phone.encPub, p);
    vectors.push({ env: C.b64e(env), expect: p });
  }
  writeFileSync(
    vecDir + "js.json",
    JSON.stringify(
      {
        phoneEncPriv: C.b64e(phone.encPriv.subarray(16)), // strip the PKCS#8 prefix
        phoneSigPub: C.b64e(phone.sigPub),
        phoneEncPub: C.b64e(phone.encPub),
        readerSigPub: C.b64e(reader.sigPub),
        readerEncPub: C.b64e(reader.encPub),
        fingerprint: await C.fingerprint(phone.sigPub, phone.encPub, reader.sigPub, reader.encPub),
        vectors,
      },
      null,
      2,
    ),
  );
});

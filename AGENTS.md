# SMS Relay: guidance for AI coding agents (AGENTS.md)

This file tells coding agents (Claude Code and similar) how to work on this repository. Read it
before changing anything. Human contributors are welcome to follow it too.

## Priorities, in this order

1. **Security.** This app reads every SMS on a phone, including one-time passwords. A change that
   weakens confidentiality, authenticity, replay protection, delete safety or the forwarding
   allowlist is never acceptable, whatever it gains elsewhere.
2. **Efficiency.** The phone runs unattended for months, mostly on battery and Wi-Fi. Every wake
   lock, alarm, reconnect, query and byte on the radio costs battery. Prefer doing nothing over
   doing something speculatively.
3. **Clean, maintainable code.** Small, readable, consistent with the surrounding code. Clever code
   that is hard to review is a security risk in itself.

When two of these conflict, the higher one wins. Say so explicitly when you make that trade.

## Principles

### Security
- **The broker is untrusted.** Everything crossing it is an envelope: per-message X25519 + HKDF-SHA256
  + AES-256-GCM, signed with Ed25519 (`core/Envelope.kt`, `reader/crypto.js`). Never send plaintext,
  metadata that reveals content, or keys through it. Treat anything received from it as hostile:
  validate sizes and shapes before doing work (see `core/MqttLimitStream.kt`).
- **Kotlin and JavaScript crypto must stay byte-identical.** Change both sides together and keep the
  cross-language vectors in `test-vectors/` passing. Do not swap primitives or libraries casually.
- **Commands are authenticated, fresh and idempotent.** Signed by the paired reader, timestamp
  window, command-id log so a replay returns the stored result instead of acting again. New
  commands must follow the same pattern (`PhoneCore.onDown`, `idempotent`).
- **The reader can never make the phone send an SMS of its choosing.** There is no send command,
  and there must not be one. Forwarding only copies incoming SMS, with fixed text, to numbers
  approved on both sides (proposed on the phone behind the screen lock, approved in the reader),
  rate limited. Do not weaken any part of that dual control.
- **Deletes are identity-checked.** A delete only removes a row whose provider id and identity hash
  still match what was relayed.
- **Keys and secrets stay local.** Phone keys are wrapped by the Android Keystore (`Secrets.kt`);
  reader keys live in the passphrase-encrypted vault (`reader/store.js`). Never log, export or commit
  keys, pairing codes, broker passwords or SMS content. No analytics, no crash reporting, no
  third-party services.
- **Reader hygiene.** SMS text is attacker-controlled: render with `textContent` only, never
  `innerHTML`. Keep the CSP strict, the frame guard, and SRI on vendored scripts. No CDNs at runtime.
- **Android surface.** Export only what the default-SMS role requires, protect exported components
  with the right permissions, validate every intent extra, use `FLAG_IMMUTABLE` PendingIntents, keep
  backups disabled and Settings behind the device credential.
- **No crash paths.** An exception on a worker thread kills the process and the relay with it. Code
  that touches the SMS provider must not throw (the app can lose the default-SMS role at any time).

### Efficiency
- **Nothing is published unless a reader is listening** (the one exception is the phone announcing
  its keys during setup). The broker keeps nothing for absent subscribers; publishing to nobody is
  wasted radio time.
- **Alarms and wake locks are bounded.** Every wake lock has a timeout and is released when the work
  completes. Use the existing alarm slots and backoff (`Relay`, `Heartbeat`); never add a polling
  loop, a periodic job or an unbounded retry.
- **Reconnects are rare and deliberate.** Respect `retryNotBefore`, the backoff ladder and the
  adaptive keep-alive. A change that can cause a reconnect storm is a bug.
- **Cheap hot paths.** Incoming SMS and command handling run on battery: avoid repeated parsing,
  large allocations and provider queries in loops. Cache what is stable (see `PhoneCore.rules()`).
- **Reader too.** Coalesce renders, page long lists, and keep hello traffic slow while the tab is
  hidden: every hello wakes the phone.

### Clean code
- Match the style of the file you are in: naming, comment density, plain Views (no AndroidX), no new
  dependencies without a strong reason (each one is attack surface and a supply-chain risk).
- Keep logic that must agree in one place, or pin the copies together with shared test vectors
  (limits and matching in `test-vectors/rules.json`).
- Comments explain *why* (the constraint, the attack, the battery cost), not what the code says.
- No personal data in the repository: no real phone numbers, names, carriers, banks or locations in
  code, tests, fixtures or docs. Use neutral samples such as `+15555550100` and `EXBANK`.

## Before you finish a change

Run the full gate and report the actual results:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # or any JDK 17+
android/gradlew -p android testDebugUnitTest assembleRelease lintRelease
node --test reader/test/*.mjs
```

- Add or update tests for every behaviour you change, especially security guards: a guard you can
  delete without a test failing is not protected.
- If the protocol changes, update `docs/PROTOCOL.md` in the same change; user-visible behaviour goes
  in `README.md`.
- Prefer Docker for test infrastructure (`test/broker/` runs a local Mosquitto) over installing
  software on the host.
- Never commit release keystores, `keystore.properties`, generated vectors (`test-vectors/js.json`,
  `test-vectors/kotlin.json`) or build output.

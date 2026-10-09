# SMS Relay

Read and manage the SMS of an Android phone from anywhere, end-to-end encrypted, without
running a server.

- **Phone app** (Android 10+, Kotlin): an ordinary-looking messaging app ("Messages") that
  becomes the default SMS app. Conversations, sending, drafts, notifications, delete. In the
  background it encrypts every message (incoming, sent, drafts) for your reader only and
  sends it through an MQTT broker. It never accepts incoming connections; it only connects out.
- **Reader** (`reader/`, a static web page that works on phones and desktops): your messages
  as conversations, remote delete on the phone, forwarding rules, an encrypted archive in the
  browser. It cannot send SMS of its own; the protocol has no such command. Forwarding rules
  can only make the phone copy incoming SMS to numbers approved on both the phone and the
  reader (see [Forwarding](#forwarding)).
- **Broker** (any MQTT broker with TLS and per-user access rules, e.g. EMQX Serverless, which
  has a free tier): passes encrypted envelopes between the two and stores nothing. It cannot
  read, forge or usefully replay anything. See [docs/PROTOCOL.md](docs/PROTOCOL.md).

> **Security note.** This is an unaudited hobby project; read [SECURITY.md](SECURITY.md) and the
> threat model below before trusting it with one-time passwords.

## Threat model: who can see what

| Who | Sees |
|---|---|
| The broker operator | Ciphertext padded to fixed sizes, timing, the IP addresses of the phone and reader, topic names |
| Anyone with the broker passwords or topic names | The same; replays do nothing, and nothing can be forged without the reader's private key |
| Other apps on the phone, the phone vendor, Google | Nothing from this app (backups disabled, no exported data). Turn off the vendor's own SMS backup/sync (below) |
| Someone with the unlocked phone in hand | Everything on it. Keep a screen lock |
| Someone with your browser storage or reader backup file | Nothing without the reader passphrase |
| Someone with the unlocked reader (or its keys) | Every relayed message; can delete messages and change forwarding rules, but can only forward to numbers that were also approved on the phone behind its screen lock |

## Setup

A detailed step-by-step walkthrough, with troubleshooting, is in [docs/SETUP.md](docs/SETUP.md).

Everything below needs the phone in hand once; afterwards it runs unattended.

### 1. Broker

Example with EMQX Serverless (free tier; any MQTT broker with TLS, logins and per-user topic
rules works the same way):

1. Create a **Serverless** deployment. With the spending limit at **0** it can never charge you,
   but if the free quota were ever used up (normal use needs a small fraction of it; only someone
   abusing a leaked broker password could exhaust it) the deployment stops until next month. A
   small spending limit removes that failure mode.
2. **Authentication:** add two users, `phone` and `reader`, each with a long random password.
3. **Authorization** (topic prefix `smsrelay`):

   | User | Topic | Action | Permission |
   |---|---|---|---|
   | phone | `smsrelay/up` | publish | allow |
   | phone | `smsrelay/down` | subscribe | allow |
   | reader | `smsrelay/down` | publish | allow |
   | reader | `smsrelay/up` | subscribe | allow |
   | all users | `#` | publish and subscribe | **deny** |

   The last rule matters: some brokers default to allowing any authenticated user everything.
4. Note the broker address and ports: MQTT over TLS (EMQX: `8883`) and WebSocket over TLS
   (EMQX: `8084`).

### 2. Reader

The reader is static files. Host it on **GitHub Pages** (recommended) or run it locally.

- **GitHub Pages:** `.github/workflows/pages.yml` publishes `reader/` on every push to `main`
  (Settings → Pages → Source: GitHub Actions) after checking the vendored scripts against their
  recorded hashes.
- **Give it an origin of its own.** Browsers isolate storage by origin, and every project page of
  one GitHub account shares `<user>.github.io`: any other page there could read the encrypted
  archive and attack the passphrase offline. The free option is a **separate GitHub
  organization** used only for this (its pages live on `<org>.github.io`, a different site);
  a custom domain or subdomain also works.
- **Protect the repository.** Whoever can push to it can change the reader you load and capture
  your keys: use two-factor authentication and keep write access to yourself.
- **Locally:** `python3 -m http.server 8765 --bind 127.0.0.1 -d reader`, then open
  <http://127.0.0.1:8765>.

Open it, choose a passphrase (at least 10 characters) and enter the broker settings. The page
creates the reader's keys and shows a pairing QR code. It refuses to run inside a frame.

### 3. Phone

1. Install the APK (see Building), allowing "install unknown apps" for the file manager.
2. Open **Messages**, make it the default SMS app when asked (or Settings → Default SMS app).
3. Open the hidden relay settings: Settings → tap **About / Version** five times quickly → confirm
   the phone's screen lock. Nothing in the app shows that these settings exist, and every visit
   asks again. There, **Relay setup**: allow unrestricted battery use, and turn on autostart if the
   vendor has such a setting.
4. Scan the reader's QR code with the camera or any QR scanner, copy the text, paste it into the
   pairing field and tap **Pair**. The app clears the clipboard afterwards; clear the scanner's
   history too (the code contains the phone's broker password).
5. **Compare the fingerprint** shown on the phone with the reader's, character by character. Only
   if they match: tap **Fingerprint matches the reader** on the phone and **It matches the
   phone** in the reader. Until you confirm on the phone it forwards nothing and obeys nothing,
   so a pairing code swapped on its way in gains nothing.
6. In the reader, open **More** and fetch the last 30 days to check that everything flows.
7. Download an **encrypted backup** of the reader (More → Backup) and keep it with its
   passphrase. Without it, a lost browser means pairing again with the phone in hand.

### 4. Phone settings for long unattended running

- Turn off cloud backup/sync of SMS (Google backup, and the vendor's own message sync if any);
  otherwise the phone itself uploads your messages.
- Lock screen: hide notification content.
- Grant the battery exemption (step 3). Vendor battery managers kill background apps
  aggressively; see <https://dontkillmyapp.com> for your phone's settings (typically: allow
  autostart, set the battery saver to "No restrictions", lock the app in recents).
- Developer options: **Mobile data always active** keeps a fallback when Wi-Fi is connected but
  has no internet. Leave USB debugging off.
- Remove apps you don't need, especially if the phone no longer gets security updates.
- Keep the SIM plan active (prepaid plans can expire and stop incoming SMS).

**Battery and data.** The app never keeps the phone awake: it wakes for a fraction of a second
per keep-alive ping (starting every 4 minutes, stretching to every 55 minutes on Wi-Fi and 28 on
mobile data when the network allows) and briefly per message or command. While no reader is
open nothing is sent at all; messages wait on the phone and arrive when you open the reader.
After network trouble they can be a few minutes late; that is the trade for battery. Data use
is a few hundred bytes per message or ping.

**About the screen lock.** With a PIN, after a reboot Android keeps the app and incoming SMS
locked until someone unlocks the phone once; the reader shows "Phone unreachable" meanwhile.

### 5. Test before relying on it

1. Reboot the phone, unlock it once, and check a test SMS still reaches the reader.
2. Turn Wi-Fi off and check messages still arrive over mobile data.
3. Delete a test message from the reader and check it is gone on the phone.
4. Restore the reader backup in a second browser and check it connects.

## Daily use

**On the phone** it is a normal messaging app: conversations, replies, drafts (kept when you
leave a conversation), notifications for new messages, long-press to delete a message or a
conversation. Apps that open the SMS composer (e.g. one that verifies your number by sending an SMS) work too.

**In the reader:**

- Conversations with incoming messages on the left and sent messages and drafts on the right.
  Messages that arrived while the reader was closed come within a minute of opening it.
- Tick messages, or whole conversations in the conversation list, and **Delete**: they are deleted
  on the phone (any kind: incoming, sent, drafts) and removed from the reader once the phone
  confirms. A delete is refused (`changed on phone`) if the message on the phone no longer matches
  what was relayed; it then stays in the reader, marked, so you can retry.
- Messages deleted on the phone itself stay in the reader as a record, marked "deleted on the
  phone"; replaced drafts disappear. Select them and **Delete** to remove them from the reader.
- **Remove from reader only** removes messages from this browser and leaves the phone untouched.
- The status line warns if the phone is unreachable, not the default SMS app, not charging, or
  missing the battery exemption.

## Forwarding

The phone can forward chosen incoming SMS to other numbers, e.g. bank codes to a second phone.

1. **Propose the number on the phone:** Messages → Settings → tap the version five times →
   screen lock → Forwarding → enter the number → Propose.
2. **Approve it in the reader:** Forwarding → Approved numbers → Approve. Approve only numbers
   you expect: the two steps mean neither the phone alone nor the reader alone can add one.
3. **Write a rule in the reader:** Forwarding → Add a rule. Pick conditions on the sender or the
   text (contains, is exactly, starts with, ends with, and their "not" forms), combine them with
   ALL (and) or ANY (or), in up to two levels, e.g. *sender contains EXBANK* AND (*text contains
   OTP* OR *text contains code*). A preview says the rule in plain words and a try-it box checks
   it against a sample message. Pick the destination numbers and save; the phone confirms.

The phone sends `From <sender>: <text>` to every approved destination of every matching rule
(each number at most once per message, the text cut to 300 characters), only for messages that
arrive live (never history), and at most 20 an hour and 100 a day. The copies appear in the reader as sent
messages marked *Forwarded · rule "name"*, and the original shows *Forwarded to …*. Delete them
like any other message. Removing a number on either side stops forwarding to it immediately,
even if rules still name it. Rules are visible read-only on the phone under Settings →
Forwarding. Forwarded copies are ordinary SMS: the carrier charges for them and can read them.

## Recovery and maintenance

- **Lost or cleared browser:** restore the backup file, then More → Fetch with "also re-send"
  ticked to refill messages received since the backup.
- **Broker shutting down or a password leaked:** create the new broker (or new users), then More
  → Move to a different broker. The phone confirms on the old broker, switches, and keeps the
  new one only once the reader has reached it there in both directions; otherwise it returns to
  the old broker by itself within about 10 minutes (use "Switch back" in the reader). Make a
  fresh backup afterwards.

## Building

Requirements: JDK 17+ and the Android SDK (Android Studio bundles both). Point `JAVA_HOME` at
Android Studio's bundled JBR (macOS: `/Applications/Android Studio.app/Contents/jbr/Contents/Home`)
and create `android/local.properties` with `sdk.dir=<path to the Android SDK>`.

```bash
cd android
./gradlew testDebugUnitTest      # unit tests incl. cross-language crypto vectors
./gradlew assembleRelease        # app/build/outputs/apk/release/
```

Release signing: create a keystore once (`keytool -genkeypair -v -keystore relay.jks -keyalg RSA
-keysize 4096 -validity 10000 -alias relay`), then `android/keystore.properties`:

```
storeFile=relay.jks
storePassword=...
keyAlias=relay
keyPassword=...
```

Both files are gitignored. Keep the keystore: updates must be signed with the same key.

Releases: bump `versionName` and `versionCode` in `android/app/build.gradle.kts`, then run
`scripts/release.sh`. It runs all tests (including the cross-language crypto vectors) and lint,
builds the signed APK, verifies it (package, version, not debuggable, exactly one signer) and writes
it with its SHA-256 to `dist/`. The first run pins the signing certificate in
`scripts/release-cert.sha256` (commit it); later builds signed with any other key are refused.
`scripts/release.sh --publish` also creates a draft GitHub release; it needs the GitHub CLI (`gh`),
a clean tree whose commit is pushed to origin, and a token in `git config github.token`.

Reader tests: `node --test reader/test/*.mjs` (run the Gradle tests first so the
Kotlin vectors exist; a second Gradle run then verifies the JS vectors).

### End-to-end test (local, needs Docker)

```bash
docker compose -f test/broker/compose.yaml up -d          # Mosquitto with the ACLs above
python3 -m http.server 8765 --bind 127.0.0.1 -d reader     # reader at http://127.0.0.1:8765
SMSRELAY_E2E=/tmp/e2e ./android/gradlew -p android testDebugUnitTest --tests 'dev.smsrelay.core.E2EHarness'
```

In the reader use host `localhost`, WebSocket port `9001`, MQTT port `1883`, users
`reader`/`readerpass` and `phone`/`phonepass` (test-only credentials), and write the pairing code
to `/tmp/e2e/pairing.txt`. The harness runs the real phone core: it prints its fingerprint to
`/tmp/e2e/fingerprint.txt`, injects a message whenever `/tmp/e2e/inject` is created, simulates a
deletion on the phone for `/tmp/e2e/localdelete`, and stops on `/tmp/e2e/stop`. Afterwards:
`docker compose -f test/broker/compose.yaml down -v`.

## Limitations

- MMS are not supported (they are dropped). One-time passwords and most service messages are
  plain SMS.
- Contacts are shown as phone numbers.
- The reader cannot send SMS (by design); forwarding only copies incoming SMS to approved numbers.
- If the broker deployment is stopped (e.g. quota exhausted by someone with a leaked password),
  the broker-move feature cannot reach the phone until it runs again.
- Updating the app needs the phone in hand.
- A phone compromised by malware exposes everything; no transport fixes that.

## License

MIT, see [LICENSE](LICENSE). Third-party components: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

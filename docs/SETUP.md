# Setup guide

A step-by-step walkthrough from nothing to a working relay. It takes about an hour, and you need
the phone in hand once. For what each part does and why, see the [README](../README.md) and
[PROTOCOL.md](PROTOCOL.md).

You need:

- an Android phone (Android 10 or newer) with the SIM whose messages you want to relay;
- a computer to run or open the reader;
- an MQTT broker with TLS, logins and per-user topic rules (this guide uses EMQX Serverless, which
  has a free tier; any broker with the same features works);
- a password manager for the passwords and backups created below.

## 1. Broker

1. Create a broker deployment. On EMQX: create a **Serverless** deployment in the region closest
   to the phone. Set a spending limit: `0` means it can never charge you (it stops if the free
   quota is ever used up), a small amount removes that failure mode.
2. Note the broker's **address** and two ports: **MQTT over TLS** (EMQX: `8883`, used by the phone)
   and **WebSocket over TLS** (EMQX: `8084`, used by the reader).
3. **Authentication:** create two users, `phone` and `reader`, each with a long random password.
4. **Authorization**, with the deny rule last:

   | User | Topic | Action | Permission |
   |---|---|---|---|
   | phone | `smsrelay/up` | publish | allow |
   | phone | `smsrelay/down` | subscribe | allow |
   | reader | `smsrelay/down` | publish | allow |
   | reader | `smsrelay/up` | subscribe | allow |
   | all users | `#` | publish and subscribe | **deny** |

   The final rule matters: some brokers otherwise allow any logged-in user everything.

## 2. Reader

The reader is a static web page. Its crypto only runs in a secure context: `https://`, or
`http://localhost` / `http://127.0.0.1`.

**Option A, locally** (quickest to start):

```bash
python3 -m http.server 8766 --bind 127.0.0.1 -d reader
```

Open <http://127.0.0.1:8766>. Only this computer can open it.

**Option B, GitHub Pages** (reachable from any device): see README, "2. Reader". Give it an
origin of its own (a separate GitHub organization or a custom domain), because every page on one
`<user>.github.io` shares browser storage.

A reader set up locally can move to Pages later without pairing again: take a backup (section 5)
and restore it on the new address.

Then, in the reader:

1. Choose a passphrase (at least 10 characters) and store it in your password manager.
2. Enter the broker settings:
   - **Broker host:** the broker address
   - **WebSocket TLS port (reader):** e.g. `8084`
   - **MQTT TLS port (phone):** e.g. `8883`
   - **Topic prefix:** `smsrelay` (must match the authorization rules)
   - **Reader** and **Phone** usernames and passwords from step 1.3
3. The reader shows **Broker connected** and a **pairing QR code**. Keep the page open.

## 3. Install the app

1. Get the APK: build it yourself (README, "Building"), or download a release and compare its
   SHA-256 and signing-certificate SHA-256 with the ones in the release notes.
2. Copy it to the phone (download it in the phone's browser, or any file transfer you trust) and
   open it. Allow "install unknown apps" for that app when Android asks, and turn it off again
   afterwards.
3. If Google Play Protect blocks the install (it can refuse apps from outside a store that request
   SMS permissions), turn off **Play Store → Play Protect → settings → Scan apps with Play Protect**
   for the install, then turn it back on. Updates may need the same.

## 4. Phone

1. Open **Messages** and accept **Set as default SMS app**. The app now receives all SMS and shows
   notifications like any messaging app.
2. Open the hidden relay settings: **menu → Settings**, tap **About / Version** five times quickly,
   then confirm the phone's screen lock. Nothing in the app shows that these settings exist, and
   every visit asks again.
3. Open **Relay setup** and go through the steps:
   1. **Make this the default SMS app** (if not done already).
   2. **Battery: allow unrestricted.**
   3. **Autostart / background settings:** allow autostart if the phone has such a setting. Many
      vendors stop background apps aggressively; see <https://dontkillmyapp.com> for your model
      (typically: allow autostart, battery saver "No restrictions", lock the app in recents).
4. **Pair:** scan the reader's QR code with the camera or a QR scanner, copy the text (it starts
   with `SR1.`), paste it into the pairing field and tap **Pair with reader**. The app clears the
   clipboard; delete the scan from the scanner's history too, because the code contains the phone's
   broker password.
5. **Compare the fingerprint.** Within a minute the reader shows **Phone found** with a
   fingerprint, and the phone shows one as well. Compare them character by character. Only if they
   match: tap **Fingerprint matches the reader** on the phone and **It matches the phone** in the
   reader. Until you confirm on the phone, it relays nothing and obeys no command.

## 5. Check and secure

1. In the reader, **More → fetch the last 30 days**: existing messages appear.
2. Send the phone a test SMS: it shows a notification on the phone and appears in the reader
   within a minute.
3. **More → Backup** in the reader: save the encrypted backup file together with the passphrase.
   Without it, a lost or cleared browser means pairing again with the phone in hand.
4. On the phone:
   - turn off cloud backup or sync of messages (Google's and the vendor's own);
   - set the lock screen to hide notification content;
   - check **Settings → Apps → Permissions → SMS** and remove apps that do not need it;
   - keep a screen lock and leave USB debugging off.
5. Test before relying on it: reboot the phone, unlock it once, and check that a test SMS still
   reaches the reader; delete a test message from the reader and check it is gone on the phone.

## Optional: forwarding

To have the phone forward chosen incoming SMS to another number, see README, "Forwarding":
propose the number in the hidden settings (**Forwarding**), approve it in the reader, then write a
rule in the reader.

## Troubleshooting

- **Reader stays on "Connecting to broker":** check host, the WebSocket TLS port and the reader
  user's password; the browser console shows the error.
- **No "Phone found" after pairing:** check the phone's network and the MQTT TLS port; open
  **Relay setup** and tap **Restart relay**; confirm the phone user's password and the authorization
  rules.
- **Reader says "Phone unreachable":** the phone is offline, was rebooted and not yet unlocked, or a
  battery manager stopped the app (revisit section 4.3).
- **Messages arrive late:** expected after network trouble or while the reader is closed; they
  wait on the phone and arrive when the reader is open.

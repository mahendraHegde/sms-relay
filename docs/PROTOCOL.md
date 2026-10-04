# Wire protocol (v1)

Two parties: the **phone** (Android app) and the **reader** (web page, anywhere).
They never talk directly. Both connect outbound to an MQTT broker (e.g. EMQX Serverless)
and the broker only ever carries the envelopes below. The broker is untrusted: it
must not be able to read, forge, replay into a harmful effect, or correlate SMS content.

## Topics

| Topic            | Publisher | Subscriber | Carries                          |
|------------------|-----------|------------|----------------------------------|
| `<prefix>/up`    | phone     | reader     | sms, gone, status, result, pair  |
| `<prefix>/down`  | reader    | phone      | hello, ack, delete, backfill, rebroker, rules, approve, revoke |

QoS 1, never retained. The broker keeps no durable sessions, so a message published
while the other side is offline is dropped. Reliability comes from the phone's outbox
(re-sent until acked) and the reader's pending-command list (re-sent until a result
arrives).

## Keys

Each side has an Ed25519 signing key and an X25519 encryption key.
The phone stores only the reader's **public** keys, and vice versa.
The reader's keys are generated in the browser and handed to the phone in the pairing
code. The phone's public keys reach the reader in a `pair` envelope; the user confirms
by comparing the fingerprint shown on both screens **and confirming on both devices**. Until
the user confirms on the phone, it forwards nothing and accepts no command (it only repeats its
`pair` announcement), so a pairing code swapped on its way to the phone gains nothing.

Fingerprint: first 10 bytes of
`SHA-256(phoneSig || phoneEnc || readerSig || readerEnc)`, hex, grouped by 4.

## Envelope

```
offset  len  field
0       1    version   = 0x01
1       1    kind      0x01 phone->reader, 0x02 reader->phone, 0x03 pair (phone->reader)
2       32   epk       sender's ephemeral X25519 public key (fresh per envelope)
34      12   nonce     random
46      n    ct        AES-256-GCM ciphertext || 16-byte tag
46+n    64   sig       Ed25519 over bytes [0, 46+n) by the sender's long-term signing key
```

Key derivation, per envelope:

```
shared = X25519(eph_priv, recipient_enc_pub)        reject if all-zero
salt   = epk || recipient_enc_pub
info   = "sms-relay/v1" || kind || sender_sig_pub   (sender_sig_pub omitted for kind 0x03)
key    = HKDF-SHA256(shared, salt, info, 32)
aad    = bytes[0, 34) || sender_sig_pub             (sender_sig_pub omitted for kind 0x03)
```

Because the ephemeral key is discarded after sealing, the phone cannot decrypt its own
past envelopes. Someone who records broker traffic and later seizes the phone still
cannot read SMS that were deleted from it.

For `pair` (0x03) the reader does not know the sender yet: it decrypts, reads
`sigPub` from the plaintext, then verifies the signature with that key.

## Plaintext

`u32 big-endian length || UTF-8 JSON || zero padding`, padded to the smallest power of
two in [512, 65536] that fits. Lengths are hidden up to the bucket.

### Phone -> reader

| `t`      | fields                                                                 |
|----------|------------------------------------------------------------------------|
| `pair`   | `sigPub`, `encPub` (base64url), `ts`                                   |
| `sms`    | `id`, `gen` (delivery generation, new whenever the envelope is re-sealed, e.g. a backfill `resend`), `kind` (`in`, `sent`, `draft`, `failed`), `addr` (sender, or recipient for outgoing kinds), `body`, `sent` (ms), `rcvd` (ms, the provider date), `ts`, `cut` (chars dropped) when the body was too long for an envelope, and for a copy sent by a forwarding rule `rule` (the rule's name) and `fwdOf` (the `id` of the original message) |
| `gone`   | `ids` (<=100), `ts`: messages deleted on the phone itself (its own UI) or replaced (an edited draft). Re-sent with every hello until the reader acknowledges them |
| `status` | `ts`, `re` (the `cmd` of the hello it answers; statuses are only sent as answers), `bat`, `chg`, `queue`, `role` (default SMS app?), `doze` (battery exemption granted?), `net` (`wifi`/`cell`/`other`), `up`, `ver`, `rej`, `rv` (forwarding rule-set version, 0 = none), `allow` (`[{n, a, p}]`: allowlisted numbers, `a` = approved, `p` = proposal id), `fwdBlocked` (copies dropped by the rate limit since start), and `rules` (the full rule set) only when the hello's `rv` differs from the phone's |
| `result` | `cmd`, `op`, `ts`, and `res` (`{id: deleted\|absent\|mismatch\|error}`) for delete, `queued` for backfill, `ok` for rebroker, `ok` + `version` or `error` for rules, `ok` for approve/revoke (the list follows in the next status) |

The reader treats a `status` as proof of life only if `re` names one of its own hellos from the
last 3 minutes (the broker could replay any older status), and keeps a content-free tombstone
for messages removed from its archive so replays do not bring them back.

### Reader -> phone

Every command has `cmd` (random 128-bit hex id) and `ts` (ms).

| `t`        | fields       | phone action                                              |
|------------|--------------|-----------------------------------------------------------|
| `hello`    | `rv` (rule-set version the reader holds), `moved` (optional) | reply `status` with `re`; re-send un-acked `sms` (see bandwidth below); `moved` confirms a broker move (below) |
| `ack`      | `ids` (<=100), `gens` (same length), `gone` (<=100) | drop stored ciphertext for each id whose current `gen` matches, so an ack for an older copy cannot cancel a newer re-send; forget the listed `gone` notices |
| `delete`   | `ids` (<=100)| delete the matching SMS from the phone's SMS store        |
| `backfill` | `days` (1-3650), `resend` | queue SMS of every relayed kind from the last N days not yet relayed; with `resend`, also re-send already acked ones under their original ids (rebuilding a lost archive). Scans at most the newest 5000 rows |
| `rebroker` | `host`, `port`, `topic`, `user`, `pass` | move to another broker (below) |
| `rules`    | `set` (`{version, rules}`, below) | replace the forwarding rules if `version` is higher than the current one and every destination is either approved or already named by the current rules |
| `approve`  | `nums`, `p` (same order) | approve the phone's proposals named by number and proposal id; anything else is ignored |
| `revoke`   | `nums` | remove numbers from the allowlist (proposed or approved) |

Phone-side checks on every command: signature by the paired reader key, `ts` not
older than 7 days and not more than 1 day ahead. `delete`, `backfill`, `rebroker`, `rules`, `approve` and `revoke` are
idempotent by `cmd`: a repeat returns the stored result instead of acting again. Results
are kept 9 days, longer than any command can stay acceptable (7 days + 1 day skew).
A delete only removes a row whose provider id and identity hash (sender, text and receive
time) still match what was recorded when the SMS was forwarded; otherwise it reports `mismatch`.

## Bandwidth and battery rules (phone)

- A `hello` is acted on only if its `ts` is within 1 hour of the phone's clock and its `cmd`
  has not been seen in this process (the seen-list survives reconnects); replays do nothing.
- Nothing is published while no reader listens (no hello in the last 3 minutes): new SMS wait
  in the outbox and the next hello pulls them. A message counts as sent only once the broker
  acknowledges it. The reader says hello every minute while visible, every 5 minutes when its
  tab is in the background.
- Every accepted hello runs an outbox pass, but each un-acked envelope is sent at most once per
  10 minutes. Reconnects and the 6-hourly watchdog send only envelopes never
  sent before. A new SMS is sent once immediately.
- The command log is pruned against the newest command `ts` seen, not only the phone's clock,
  so a clock pushed forward cannot make still-valid commands replayable.
- Keep-alive is adaptive (4 up to 55 minutes on Wi-Fi, up to 28 on mobile data, 15 on battery),
  learned per network, and pings are alarm-driven; see Heartbeat.kt.
- Reconnect attempts back off (1, 2, 5, 10, 15, 30 minutes) after failures or drops that never
  carried traffic. A network change, or the network regaining internet access (validated), tries
  at once. An incoming SMS may also cut the backoff short, but at most once per max(2 minutes,
  half the current backoff), so SMS senders cannot drive frequent reconnects.

## Moving to another broker

1. Reader sends `rebroker` on the current broker A. The reader keys are not part of it: the
   phone builds the new pairing from the new broker fields plus the reader keys it already has.
2. Phone validates, replies `result {op: rebroker, ok}` on A, and 3 s later connects to B as a
   *candidate* for 10 minutes.
3. On `ok: true` the reader switches itself to B and sends `hello`. The phone answers with a
   `status` on B.
4. Once the reader has received a fresh `status` over its B connection, its `hello` carries
   `moved: <rebroker cmd id>`. The phone commits B only on such a hello: it proves both
   directions work on B, and a command replayed from A cannot carry it. If none arrives within
   10 minutes the phone drops the candidate, forgets the move (so a replay of it cannot push
   it back to B), and returns to A; the reader's "Switch back" does the same on its side.
5. A replay of the most recent `rebroker` re-offers the switch (the first may have been lost to
   a process death); the phone ignores a move to where it already is. A replay of an older or
   rolled-back move is never re-applied and is answered `ok: false, stale: true`, so a reader
   that missed the first answer stays where the phone is.

## Which messages are relayed

Incoming, sent, failed and draft messages are relayed (outbox/queued rows wait until they are
sent or failed). The phone's own UI can send SMS locally; **no command lets the reader send an
SMS**, so even someone holding the reader's keys cannot send from the phone's number. A delete
from the reader works for every kind. Deletions made on the phone, and drafts that were edited
(a new row replaces the old), reach the reader as `gone` notices.

The one exception is forwarding (below): the phone itself sends copies of incoming SMS, but only
to numbers approved on both sides, with the text fixed as `From <sender>: <original text>` (cut to 300 characters).

## Forwarding

A rule set is `{version, rules: [{id, name, enabled, when, forwardTo}]}`. `when` is a condition:
`{field: sender|body, op: contains|equals|startsWith|endsWith, value}`, or `{all: [...]}`,
`{any: [...]}`, `{not: {...}}`. Matching is plain, case-insensitive text (no regular expressions,
so a crafted SMS cannot make matching slow). Limits, enforced by the phone: 50 rules, 20 nodes and
depth 4 per rule, exactly one of `all`/`any`/`not`/`field` per node, values 1-200 characters and
not only spaces, 1-5 destinations, ids `[a-z0-9-]{1,32}`, `version` up to 2^53-1, 32 KiB for the
whole set as the phone serialises it (it rides in `status`, which must fit one envelope).

Destinations need dual control. A number is proposed on the phone (Settings, behind the screen
lock), which gives it a random proposal id, and becomes usable only after the reader sends
`approve` naming that number and id; the reader cannot add a number, someone holding only the
phone cannot activate one, and a held-back old approval cannot activate a later proposal of the
same number. Removing it on either side stops forwarding to it at once: the phone checks the
approved list on every forward, not only when the rules are set. A number added again needs a new
approval. A rule may keep naming a removed number (so other edits are not blocked) and nothing is
sent to it; a destination can only be added to a rule (by rule id) while it is approved.

When an incoming SMS is first recorded by the phone and is no more than 1 hour old (nor dated
more than 5 minutes ahead) (normally on
delivery; the reconcile scan or a `backfill` can record one the delivery path missed, never an
older one), the phone sends `From <sender>: <text>` (text cut to 300 characters) to each approved
destination of every enabled matching rule, once per destination. It never forwards a message whose sender is an
allowlisted number (loop guard), sends nothing before the pairing is confirmed on the phone, and
sends at most 20 forwards an hour and 100 a day (persisted across restarts; excess forwards are
dropped and counted in `fwdBlocked`). Each copy is an ordinary sent SMS and is relayed as such,
tagged with `rule` and `fwdOf`.

# Mac ↔ phone sync protocol (v1)

- **Status:** Implemented on both sides — phone `tool/…/data/sync/`, Mac `mac/` (see `mac/README.md`)
- **Related:** `docs/mac-loader.prd.md` (product), `docs/ipod.adr.md` D2 (storage layout)

The phone pulls; the Mac serves (PRD §3). The Mac runs a small HTTPS server on the
home Wi-Fi while the app is open. The phone, when the music tool is opened (or the
user taps Sync), asks for the queue, downloads each file into its own `music/`
folder, acknowledges each one, then rescans its library.

## 1. Pairing code

The Mac shows a QR code containing one line:

```
lp3music://pair?v=1&h=<host>&p=<port>&t=<token>&f=<cert-sha256-hex>&n=<mac-name>
```

| Key | Meaning |
| --- | --- |
| `v` | Protocol version. Always `1`. Phone rejects anything else. |
| `h` | Host the phone should connect to. The Mac's LAN IPv4 address (no scheme, no slashes). |
| `p` | TCP port of the Mac's HTTPS server. |
| `t` | Bearer token. Random, ≥ 32 bytes of entropy, generated once per Mac install and persisted. The Mac accepts only this token. |
| `f` | Hex SHA-256 of the DER encoding of the Mac's self-signed TLS certificate (64 hex chars, any case). |
| `n` | Human name for the Mac ("Justin's MacBook"). Shown in every phone message. Optional; phone falls back to "your Mac". |

Values are `application/x-www-form-urlencoded` (`+` is a space). Parser:
`PairingCode.kt`. The phone stores the whole record and reuses it on every sync
until the user taps **Forget This Mac** or scans a new code.

**Why TLS + pinning, not plain HTTP:** LightOS tools cannot opt into cleartext
traffic (the SDK generates the manifest), so plain `http://` is refused by the
OS. A self-signed certificate whose fingerprint rides in the QR code gives the
phone something to trust without a CA, and it is what makes "the two devices
know each other and only each other" true: the phone trusts only that
certificate, the Mac trusts only that token.

**Mac requirements:** generate a self-signed cert + key once and persist them in
the Keychain or app support; keep the fingerprint stable across launches
(regenerating it silently invalidates every paired phone, which then shows
"This doesn't look like *Mac* … scan the code again"). If the Mac's IP changes,
the phone's message is "Can't find *Mac* …" and the fix is re-scanning; that is
acceptable for v1 (PRD §11 item 5 covers discovery).

## 2. HTTP API

All requests carry `Authorization: Bearer <token>`. Missing or wrong token →
`401`. The phone treats `401`/`403` as "the Mac doesn't recognize this phone;
scan again".

### `GET /v1/queue`

Everything currently dropped on the Mac and not yet acknowledged.

```json
{
  "items": [
    { "id": "6f1c…", "path": "Radiohead/In Rainbows/01 15 Step.mp3", "sizeBytes": 9812345 }
  ]
}
```

- `id`: opaque, stable for the life of the item, URL-safe.
- `path`: where the file lands, **relative to the phone's `music/` folder**,
  forward slashes. The phone refuses (and reports to the user) any path that is
  absolute, contains `..`, empty segments, `:`, control characters, or ends in
  `.part`; and any extension outside `mp3 m4a aac wav ogg flac`. The Mac should
  build paths as `<Artist>/<Album>/<track> <Title>.<ext>` from the file's tags,
  falling back to the original filename, and sanitize accordingly. Two drops
  that produce the same path are the same song to the phone (a same-size file
  already present is acknowledged without re-download); different paths are
  duplicates (PRD §10).
- `sizeBytes`: exact byte length. The phone discards a download whose length
  differs and reports "The copy was cut off before it finished".

Order is the order the phone copies in. Unknown fields are ignored.

### `GET /v1/files/{id}`

Raw bytes of the file, `Content-Length` set. `404` if the item is gone (user
removed it from the Mac queue); the phone reports "Your Mac no longer has this
file" and moves on.

### `POST /v1/ack/{id}`

The file is on the phone. The Mac removes it from the queue and shows it as
done. Idempotent: acking an unknown id returns `2xx`. Empty body is fine.

The phone acks after the file is fully written and renamed into place. If the
ack itself fails (Mac unreachable) the file stays; next sync the item is still
queued, the phone sees the same-size file already there, and acks again.

## 3. Phone behavior the Mac can rely on

- Connect timeout 4 s, read timeout 30 s per request. Files stream to
  `<path>.part` then rename, so the library never sees a half file.
- Sync runs automatically when the tool is opened (throttled to once a minute)
  and on demand from **Sync → Sync Now**. One sync at a time.
- After ≥ 1 file lands, the phone rescans and the songs appear in Artists /
  Albums / Songs with no further action.
- Interrupted transfer policy (PRD §14): the cut-off file is discarded and
  retried next sync; files already copied stay. Resume is at queue granularity.
- The phone never deletes music and never talks to anything but the paired
  host:port. There is no discovery in v1.

## 4. What the phone shows for each failure

| Condition | Sentence |
| --- | --- |
| Not on Wi-Fi | Your phone isn't on Wi-Fi. Join the same Wi-Fi as *Mac* and try again. |
| Connect refused / timeout | Can't find *Mac*. Make sure the music app is open there and both devices are on the same Wi-Fi. |
| Dropped mid-sync | Lost the connection to *Mac* after N songs. Open Sync again to pick up where it left off. |
| 401 / 403 | *Mac* doesn't recognize this phone anymore. Scan the code on your Mac again. |
| TLS fingerprint mismatch | This doesn't look like *Mac*. Scan the code on your Mac again to reconnect. |
| Disk full | Your phone is out of space. No more music can be added right now. |
| Bad path | This file has a name your phone can't store |
| Unsupported extension | Your phone can't play this kind of file |
| Length mismatch | The copy was cut off before it finished |
| 404 on file | Your Mac no longer has this file |

The Mac should do its own preflight (PRD §7) so the last four almost never
happen; the phone's checks are the backstop.

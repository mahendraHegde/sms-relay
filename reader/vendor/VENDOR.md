# Vendored third-party files

Loaded by index.html from this folder (never from a CDN at runtime). Update only deliberately, and re-check the hashes.

| File | Source | SHA-256 |
|---|---|---|
| mqtt.min.js | https://cdn.jsdelivr.net/npm/mqtt@5.10.4/dist/mqtt.min.js (MIT) | `0d5d035815d2a462e6edf0f86ab91f7a5d563a36b40e9760e4ee452618f7c45e` |
| qrcode.js | https://cdn.jsdelivr.net/npm/qrcode-generator@1.4.4/qrcode.js (MIT) | `18ae399f81182bc9de916e9c77b195df20cc58d6f2d55a62b085a299f1bf1780` |

Check: `shasum -a 256 reader/vendor/*.js`

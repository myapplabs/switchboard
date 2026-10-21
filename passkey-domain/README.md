# switchboard.applabs.llc — the website and passkey domain

This is for maintainers (and for forks that need their own domain); running a hub needs none of it.

Switchboard passkeys are bound to `switchboard.applabs.llc`, which every hub uses as its relying-party
ID (`SWITCHBOARD_RP_ID` in the hub). Phones only let the Switchboard app create passkeys for that
domain if the domain vouches for the app. These two files do that:

| File | For | Fill in |
|---|---|---|
| `public/.well-known/assetlinks.json` | Android (Credential Manager, invite links) | Signing-cert SHA-256 fingerprints. Now: the development debug cert. Later: add the Play app-signing key (Play Console → App integrity) |
| `templates/apple-app-site-association` (moves to `public/.well-known/` once there is an Apple developer account) | iOS: passkeys (`webcredentials`) and invite links (`applinks`, path `/join`) | Apple Team ID (developer.apple.com → Membership). The app's entitlement lists `webcredentials:switchboard.applabs.llc` |

`assetlinks.json` also covers invite links on Android (`handle_all_urls`; the app declares an intent filter for `https://switchboard.applabs.llc/join`). Both must be served over HTTPS with `Content-Type: application/json`, at exactly these paths, with no
redirects. The domain holds no accounts and no data. Passkeys and challenges stay in each group's own hub.

When the Android app's signing key exists, also put its origin
(`android:apk-key-hash:<base64url of the cert SHA-256>`) into the hub's `SWITCHBOARD_APP_ORIGINS`.

## The website

The same domain is Switchboard's landing page: `public/index.html` with `style.css` and `favicon.svg` (the
mark from `app/design/`), `join.html` for invite links opened without the app (served at `/join` by a
rewrite; the code stays in the link's `#…` part, which browsers never send), and `404.html`. No external
fonts, scripts, cookies or trackers. `/install.sh` is the repository's `install.sh`, copied in when the site
is deployed (`predeploy` in `firebase.json`). Don't add an `ignore` list to `firebase.json`: Firebase's usual one
skips dot-folders, which would drop `.well-known/` and break passkeys.

## Hosting (Firebase project "Switchboard", `applabs-switchboard`)

The site has its own Firebase project, kept apart from AppLabs' other apps. Its default site is
`applabs-switchboard` (https://applabs-switchboard.web.app).

1. Deploy from this folder: `firebase deploy --only hosting:switchboard`. `.firebaserc` maps the target.
2. Connect the domain: Firebase console → project Switchboard → Hosting → Add custom domain
   `switchboard.applabs.llc`. In GoDaddy's DNS for applabs.llc, add the records Firebase shows:
   - TXT `switchboard` = `hosting-site=applabs-switchboard`
   - A `switchboard` = `199.36.158.100`
   The certificate follows automatically.
3. Check: `curl -i https://switchboard.applabs.llc/.well-known/assetlinks.json` returns 200 with `application/json`.

Any static HTTPS host works the same way (GitHub Pages needs `.nojekyll` so `.well-known` is published).

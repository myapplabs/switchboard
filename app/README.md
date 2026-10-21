# Switchboard app (Android and iOS)

The phone app for Switchboard hubs, built with Kotlin Multiplatform and Compose Multiplatform. It talks
only to a hub ([`../hub`](../hub)), through the API in [`../api`](../api).

| Module | What |
|---|---|
| `shared/domain` | API models, repository and platform interfaces (`Passkeys`, `SecureStore`, `PushTokens`) |
| `shared/data` | `HubClient` (Ktor): token refresh, problem+json errors, SSE streams that resume with `Last-Event-ID`; the repositories, live events and `AppContainer` (manual DI) |
| `shared/design` | The Switchboard theme: a dark "control room" (near-black, one amber accent, mono status labels); light follows the system |
| `shared/ui` | Screens and ViewModels: connect and passkeys, agents, chats, the board, jobs, members, settings |
| `androidApp` | Credential Manager passkeys, Keystore-encrypted tokens, FCM push, invite links, the adaptive icon (from `design/app-icon-dark.svg`) |
| `iosApp` | SwiftUI host: AuthenticationServices passkeys and the Keychain (not yet built or released) |

## Build and run (Android)

Requires JDK 21 and the Android SDK (`local.properties`).

```sh
./gradlew :androidApp:assembleDebug :shared:data:testAndroidHostTest :shared:ui:testAndroidHostTest
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

In the app, enter your hub's address (or open an invite link) and sign in with a passkey.

**Debug builds** also accept a hub on plain HTTP and offer **Use a test token** (from
`switchboard-hub dev-token`), for working on the app before passkeys are set up. Release builds need the
hub's HTTPS address.

**Passkeys** work with the official signing key, which `switchboard.applabs.llc` vouches for. A build
signed with another key (including your own debug key) can't create passkeys for that domain: use the
test token, or set up your own domain (see [`../passkey-domain`](../passkey-domain)).

**Push notifications** need `androidApp/google-services.json` for the Firebase project the push relay
uses ([`../push-relay`](../push-relay)).

Dependencies are locked (`gradle.lockfile` per module); after changing one, run
`./gradlew resolveAndLockAll --write-locks`.

## Not built yet

- **iOS:** the code is written (`iosApp/`, `Platform.swift`) but must be built on a Mac with an Apple
  developer account.
- Scanning invite QR codes with the camera (opening or pasting an invite link works).
- Bundled Inter and JetBrains Mono fonts (the platform's sans and mono are used).

Conventions for contributors are in [`docs/CONVENTIONS.md`](docs/CONVENTIONS.md).

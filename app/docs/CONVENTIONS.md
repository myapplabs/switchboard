# App conventions

How the Switchboard app is organised and what a change needs.

## Modules and dependencies

```
androidApp ─┐
iosApp     ─┼─▶ shared:ui ─▶ shared:design
            │      │
            │      ▼
            └─▶ shared:data ─▶ shared:domain
```

- **`shared:domain`**: plain Kotlin. API models (`@Serializable`, names as in the contract), the
  repository interfaces (`AgentsRepository`, `ChatRepository`, `TasksRepository`, …) and the platform
  interfaces (`Passkeys`, `SecureStore`, `PushTokens`). No Ktor, no Compose.
- **`shared:data`**: implementations. `HubClient` is the only place that talks HTTP. `AppContainer`
  creates everything once (manual DI) and is passed to the UI as `AppDependencies`.
- **`shared:design`**: theme and tokens (`AppThemeTokens.kt`: colours, mono labels, status colours).
  Screens use the theme and tokens, never hard-coded colours.
- **`shared:ui`**: screens and ViewModels. ViewModels depend on repository interfaces only and expose
  one immutable `StateFlow<…UiState>`. Navigation: a route in `Routes` and a `composable(...)` in `App()`.
- **Platform code** lives in `androidMain` / `iosMain`, or the `androidApp` / `iosApp` hosts.

## The API contract

The app builds to [`../../api`](../../api). Fields are `snake_case` on the wire (`@SerialName`). Clients
ignore unknown fields, event types and enum values, so additive hub changes never break the app. The app
branches on an agent's **capabilities**, never on its type.

## Adding a feature

1. Domain: models and the repository method.
2. Data: the implementation and its tests (Ktor `MockEngine`).
3. UI: the ViewModel (and tests where it has logic), then the screen.
4. Accessibility: content descriptions for anything visual, headings, and live regions for changing
   status. Touch targets at least 48 dp.
5. `./gradlew :androidApp:assembleDebug :shared:data:testAndroidHostTest :shared:ui:testAndroidHostTest` passes.

## Testing

- Tests live in `commonTest` and run on the JVM (`withHostTest {}`), on Linux and in CI.
- Coroutines: `runTest`; fakes rather than mocks.
- Test names are sentences in backticks: `` `a refused refresh signs this device out` ``.

## Dependencies

- Versions live in `gradle/libs.versions.toml`; modules use `libs.*` aliases only.
- Every module has a `gradle.lockfile`. After adding, removing or upgrading a dependency, run
  `./gradlew resolveAndLockAll --write-locks` and commit the lockfiles.

## Secrets

Nothing secret goes in the app or the repository: hub tokens live in the Android Keystore or iOS
Keychain, and agent keys never leave the hub. `google-services.json` is Firebase client configuration,
public by design.

# UniEat Android — Sprint 2 walkthrough

Supporting document for the deliverable and the viva voce. Everything here comes from the repository's code, its git history, and real test runs. Where something is missing, the document says so.

Team members on this client: Camilo Molina, Juan José Murillo and Samuel David Rozen Mogollon.

---

## 1. Summary of the work

The Android client consumes the same Supabase API v1 as the iOS app. What is working, with tests, after this sprint:

**By Camilo Molina** (commits "Base android" and "Fase 1–5" of menu-detail-location, plus follow-ups):

- Project base: tab navigation, visual theme, reusable components (cards, stickers, buttons), HTTP client, and the contract models.
- Menu detail with **BQ-05** (location references): location card with an OpenStreetMap view, address, entrance description, and an explicit warning when references are missing or disputed by pending reports.
- The **GPS sensor** in the detail: live walking distance to the restaurant, a "Tú" marker on the map, and the "¿Ya llegaste?" prompt within 50 meters. Context-aware for real: a different UI for each permission and GPS state.
- Community reports (`POST /reports`) from the detail.
- The **analytics pipeline**: event queue persisted on disk, WorkManager to drain it when online, batched upload to `POST /events/batch`.
- Backend connectivity: `ApiClient` with the API error envelope and authenticated access tokens supplied by `SessionManager`.

**By Juan José Murillo** (commits "Fase 1–3" of the feed, Strategy, and the dashboard, on the feed-today branch):

- The **"Hoy" feed**: the real menu list in the backend's rank-v1 order, built with MVVM and StateFlow.
- **BQ-06** (waiting time): the backend's queue estimate shown with its evidence level.
- The **filters** `/feed` accepts (budget, time, diet, area, payment method), shared between tabs.
- The smart feature **"Elige por mí"**: a recommendation from the backend ranking with its explanation, plus the **Strategy** pattern with two interchangeable criteria.
- The **expired-session redirect** to the login (`AUTH_REQUIRED`).
- The **`feed_impression`** events (deduplicated per session) on top of the existing pipeline.
- The **metrics dashboard** in the "Rendimiento" tab (`GET /performance`).

**By Samuel David Rozen Mogollon** (branch feat/samuel-auth-bq01):

- **Login** with Supabase Auth (email and password) and sign-out, replacing the debug test account (`DevTokenProvider` was removed).
- **Persisted session**: it survives closing the app, and the access token is refreshed without the rest of the app noticing. This is the **Proxy** pattern (`SessionManager`).
- **Profile** screen backed by `GET /me`: name, role and establishments, with loading, error and retry states.
- **Tabs by role** (context-aware): "Publicar" only for restaurants, "Rendimiento" for restaurants and admins.
- **Shake-to-refresh** with the accelerometer on the "Hoy" feed (sensor).
- **"Menor fila"**, a third criterion in "Elige por mí" (smart feature).
- **BQ-01** end to end: every feed load is recorded with its outcome, connection, device, OS and timing, uploaded to the backend (`POST /telemetry/feed-loads`), aggregated across all devices in Postgres and shown in a card in "Rendimiento" (section 12).

---

## 2. Architecture

```mermaid
flowchart TB
    U([User]) --> UI
    subgraph UI["Compose UI"]
        FS[FeedScreen]
        RS[RecommendScreen]
        PS[PerformanceScreen]
        DS[MenuDetailScreen]
        LS[LoginScreen]
        PRS[ProfileScreen]
    end
    subgraph VMS["ViewModels · StateFlow"]
        FVM[FeedViewModel]
        PVM[PerformanceViewModel]
        DVM[MenuDetailViewModel]
        LVM[LoginViewModel]
        PRVM[ProfileViewModel]
        SHVM[AppShellViewModel]
    end
    subgraph DATA["Data layer"]
        MR[MenuRepository]
        AR[AnalyticsRepository]
        RR[ReportRepository]
        LR[LocationRepository]
        PR[ProfileRepository]
        FLT[FeedLoadTelemetry]
        AC[ApiClient]
        SM[SessionManager]
    end
    UI --> VMS --> DATA
    MR & AR & RR & PR --> AC
    AC -- access token --> SM
    SM --> AUTH[("Supabase Auth")]
    SM --> DSTORE[("DataStore<br/>persisted session")]
    AC --> SB[("Supabase API v1<br/>shared with iOS")]
    LR --> GPS[("Fused location<br/>Google Play services")]
    FLT --> FILE[("Local JSON file<br/>BQ-01 records")]
    FILE -- WorkManager --> AC
```

One sentence per block. The UI only draws what the state says and never touches the network. The ViewModel is each screen's brain: it fetches, decides the state, and publishes it. A Repository is a contract: in debug without a backend it answers with a copy of the seed data, with a backend it calls the real API, and screens cannot tell the difference. `ApiClient` is the only piece that knows the UniEat API, and it gets its token from `SessionManager`, the only piece that knows Supabase Auth.

Dependency injection is manual: `AppContainer` builds one instance of each piece and ViewModel factories read from it. No Hilt, because with this size a hand-made container is easier to explain and to test.

**Why this shape**: unit tests run on the JVM without an emulator because business decisions live in ViewModels and plain Kotlin rather than composables. Heavy computation (ranking, wait aggregation, report moderation) stays on the backend; the client contributes only what the phone alone knows: GPS, permission state, and the local event queue.

---

## 3. The "Hoy" feed — Juan José

**Problem it solves**: a student needs to see, fast, which lunches are available nearby, ranked by relevance, without guessing.

```mermaid
sequenceDiagram
    actor U as User
    participant S as FeedScreen
    participant VM as FeedViewModel
    participant R as MenuRepository
    participant B as Backend

    U->>S: opens the "Hoy" tab
    S->>VM: collects the StateFlow
    VM->>R: feed with the shared filters
    R->>B: GET /feed
    B-->>R: menus ranked by rank-v1 plus serverNow
    R-->>VM: FeedResponse
    VM->>VM: hide expired and closed menus using serverNow
    VM-->>S: publishes Content and the screen redraws
```

**What each card shows**: restaurant, dish, price, area, the BQ-06 wait sticker, "Menú por vencer" within 30 minutes of expiry, "Reporte pendiente" when there are any, and the `explanation` string with which the backend justifies the ranking position.

**Screen states**, each with its own visual answer: loading, content, no menus matching the filters, no connection with a retry button, server error with its message, and expired session which navigates to the login.

**A detail worth points**: freshness is judged with the server clock, not the phone's. Each response carries `serverNow`; we keep the offset against the device clock and a shared minute ticker (`rememberServerNow`) re-evaluates, so a menu that expires while on screen disappears on its own.

**Files**: `ui/feed/FeedViewModel.kt` (states and logic), `ui/feed/FeedScreen.kt` (the screen), `ui/common/ServerClock.kt` (the minute ticker, shared with the detail).

---

## 4. BQ-06 — Waiting time — Juan José

**The question**: how long would a student wait in line here, and how reliable is that estimate?

**Who does what**: the backend aggregates the community's wait reports and sends three fields per menu: `waitMinutes` (the estimate), `waitSampleCount` (how many reports), `waitNewestReportAt` (the newest one). Android **computes nothing**: it only decides whether the evidence is enough to show the number.

```mermaid
flowchart TD
    A[Menu arrives from the backend] --> B{waitMinutes present?}
    B -- no --> N["Card shows Fila sin datos"]
    B -- yes --> C{at least 3 reports?}
    C -- no --> N
    C -- yes --> D{newest report within 30 min?}
    D -- no --> N
    D -- yes --> Y["Card shows ~N min de fila"]
```

The rule as it exists in `core/model/Models.kt`:

```kotlin
/** queue-v1 freshness: >= 3 samples and newest report at most 30 minutes old. */
fun hasWaitEvidence(at: Instant = Instant.now()): Boolean {
    val newest = waitNewestReportAt ?: return false
    if (waitSampleCount < 3 || waitMinutes == null) return false
    return newest <= at && Duration.between(newest, at) <= Duration.ofMinutes(30)
}
```

**What the user sees**: with evidence, a green "~9 min de fila" sticker on the feed card, and in the detail the big number with how many reports back it and the time of the newest one. Without evidence, "Fila sin datos". A number is never made up, and a stale estimate is never presented as reliable.

**Where it lives**: the rule in `DailyMenu.hasWaitEvidence`, the sticker in `WaitSticker` inside `FeedScreen.kt`, the detail card existed already (Camilo). Tests in `WaitEvidenceTest`, five cases including the exact 30-minute edge.

---

## 5. Filters — Juan José

The five parameters `/feed` really accepts, none invented: budget (5,000–40,000), available time (15/30/45/60), diet (all/vegetarian/vegan), area (Centro/Norte/Sur/Fuera del campus), payment method. Null values are omitted from the query.

```mermaid
flowchart LR
    FS[Filters sheet<br/>local draft] -- "Aplicar filtros" --> AF[("Shared filters<br/>MutableStateFlow in AppContainer")]
    AF -- observed by --> V1[FeedViewModel · Hoy]
    AF -- observed by --> V2[FeedViewModel · Elige por mí]
    V1 -- reload --> B[GET /feed]
    V2 -- reload --> B
```

**Race protection**: the flow is observed with `collectLatest`, so if the user changes filters while a request is in flight, that request is cancelled and a stale response can never overwrite the new one. iOS does this guard by hand; in Kotlin it is one word.

**Files**: `ui/feed/FiltersSheet.kt` (the sheet), `AppContainer.kt` (the shared flow, one line), `FeedViewModel.kt` (observation and reload). Key test: `applyingFiltersInOneTabReloadsTheOtherTab`.

---

## 6. "Elige por mí" — smart feature — Juan José

**What it does**: picks today's lunch for the student, using the ranking the backend already computed.

```mermaid
flowchart TD
    B["GET /feed<br/>filtered and ranked by the backend,<br/>each menu with its explanation"] --> S{Active strategy}
    S -- "Mejor puntuada" --> R1[Current position of the<br/>rank-v1 order, wrapping around]
    S -- "Más económica" --> R2[Lowest price among<br/>the same options]
    S -- "Menor fila" --> R3[Shortest wait backed by<br/>BQ-06 evidence · Samuel]
    R1 --> UIcard[Card with the menu<br/>and the backend explanation]
    R2 --> UIcard
    R3 --> UIcard
    UIcard --> N["Elegir otra opción → next one"]
    UIcard --> D["Ver publicación completa → detail"]
```

**Where the "why" comes from**: the `explanation` field the backend sends per menu, shown as-is. **There is no local ranking**: the backend filters, orders, and explains; a strategy only chooses among results already delivered, using fields that already arrive (position, `lowestPriceCop`, and the BQ-06 wait fields).

**Edge cases**: with no compatible menus, the empty state suggests changing filters; with no connection, the clear error with retry; with an expired session, the login. Switching criterion or reloading goes back to that criterion's best option.

**Files**: `ui/feed/RecommendScreen.kt`, `ui/feed/RecommendationStrategy.kt`, and the part of `FeedViewModel` that holds the active strategy and the index.

---

## 7. GPS — Camilo, in the detail

The sensor lives in the menu detail screen.

```mermaid
flowchart TD
    P{Location permission} -- not asked --> E1[Explanation before the system dialog]
    P -- denied --> E2[Message with a path to settings]
    P -- granted --> G{GPS on?}
    G -- off --> E3["Ubicación desactivada" state]
    G -- on --> F[Fused location fixes<br/>while the screen is visible]
    F --> H[Haversine + detour factor →<br/>distance and walking minutes]
    H --> A{Within 50 m with a precise fix?}
    A -- yes --> Y["¿Ya llegaste? prompt"]
    A -- no --> K[Distance line keeps updating]
```

Context-aware for real: each permission and GPS situation has its own visual state, and the sensor stops by itself when the screen goes to the background (`WhileSubscribed`). The location never leaves the phone.

Scope note: during the sprint, sending the location as `origin` to `/feed` was also implemented and then reverted when responsibilities were split (the sensor belongs to BQ-05 in the detail). It remains in git history as evidence of work, but **it is not in the current code**.

**Files**: `data/location/FusedLocationRepository.kt`, `core/decision/Proximity.kt`, and the distance section in `ui/detail/`.

---

## 8. EventTracker and the pipeline — Camilo the infrastructure, Juan José the feed events

```mermaid
flowchart LR
    VM[ViewModels<br/>track events] --> ET[EventTracker]
    ET --> Q[EventQueue<br/>JSON file on disk]
    Q --> WM[WorkManager<br/>runs when online]
    WM --> UP[EventUploader<br/>batches of 100]
    UP --> API[POST /events/batch]
    API --> AGG[Backend aggregation<br/>rank-v1 and queue-v1]
    AGG --> PERF[GET /performance]
    PERF --> DASH[Rendimiento tab<br/>BQ dashboard in the app]
```

Events the app records, none invented:

- **`feed_impression`** (Juan José): fires the first time a menu card is composed in the feed list, meaning it actually entered the screen. A `Set` in the ViewModel deduplicates it: one impression per menu per session, even scrolling back and forth. Test: `impressionIsTrackedOncePerMenu`.
- **`selection`** (already existed, in the detail): fires on "Elegir este menú", the student's final decision. We verified it covers selections that start in the feed, so nothing was duplicated.
- `detail_open` and `arrival` also exist in the detail (Camilo).

Why a disk queue: a student without signal is the normal case on campus. Events survive the app being closed, and since each event is born with its id, a retried batch can never count twice (the server deduplicates by id).

---

## 9. Metrics dashboard — Juan José

The "Rendimiento" tab is the other end of the pipeline: one screen with the metrics the backend computes via `GET /performance?days=7|28` — impressions, detail opens, selections, reported arrivals — and a period selector. The client computes no metric. If the backend flags `insufficientData`, the screen says so instead of presenting numbers as reliable; if the account has no role for the endpoint (it belongs to restaurants and admins), it shows "Acceso restringido" with the backend message instead of breaking.

**Files**: `ui/performance/PerformanceViewModel.kt`, `ui/performance/PerformanceScreen.kt`, and the `performance(days)` method added to the existing `AnalyticsRepository` (no new repository). Each member adds their BQ's card here; the BQ-01 card is already in place (section 12).

---

## 10. Login and session — Samuel

**Problem it solves**: until this sprint every request used a debug test account. The app needed real users, and a session that survives closing the app without asking for the password again every hour.

```mermaid
sequenceDiagram
    actor U as User
    participant S as LoginScreen
    participant VM as LoginViewModel
    participant SM as SessionManager
    participant A as Supabase Auth
    participant D as DataStore

    U->>S: types email and password, taps "Ingresar"
    S->>VM: signIn(email, password)
    VM->>VM: validates the email format and a non-empty password
    VM-->>S: Loading (the button is disabled)
    VM->>SM: signIn
    SM->>A: POST /auth/v1/token?grant_type=password
    A-->>SM: access_token, refresh_token, expires_in, user
    SM->>D: saves the session
    SM-->>VM: state SignedIn
    VM-->>S: Success, navigates to "Hoy"
```

**How the token stays valid**: every API request asks `SessionManager` for a token. It answers from memory or from disk; if the token expires in less than a minute, it first refreshes it with `grant_type=refresh_token`. A `Mutex` makes concurrent requests wait for one single refresh.

```mermaid
flowchart TD
    R[ApiClient needs a token] --> M{Session in memory or on disk?}
    M -- no --> X[AUTH_REQUIRED → login]
    M -- yes --> V{Expires in more than 1 minute?}
    V -- yes --> T[Return the current token]
    V -- no --> F[POST /token refresh_token]
    F -- ok --> P[Save the new session, return the token]
    F -- 400 / 401 --> C[Clear the session, SignedOut → login]
    F -- 5xx / offline --> K[Keep the session, report the error]
```

**Error handling**: 400 and 401 mean wrong credentials or a dead session; 429 is "too many attempts"; 5xx and no connection keep the session, because a server hiccup is not a reason to log the user out. Sign-out is best-effort: the local session is always deleted, even offline or with an already expired token.

**Start of the app**: while the stored session loads, the app shows a spinner, then starts in "Hoy" if there is a session and in the login if not. In demo mode (no backend configured) it always starts in "Hoy".

**Files**: `data/auth/SessionManager.kt`, `data/auth/SupabaseAuthRepository.kt`, `data/auth/SessionStore.kt`, `ui/auth/LoginViewModel.kt`, `ui/auth/AuthScreens.kt`.

---

## 11. Profile and tabs by role — Samuel

**Profile**: `ProfileScreen` calls `GET /me` and shows the name, the role and, for restaurant accounts, their establishments. It has loading, error with retry, and the "Cerrar sesión" button. In demo mode a fake repository answers with a demo admin profile.

**Tabs by role (context-aware)**: the bottom bar adapts to who is using the app.

| Role | Hoy | Elige por mí | Publicar | Rendimiento | Perfil |
| --- | --- | --- | --- | --- | --- |
| student | ✓ | ✓ | | | ✓ |
| restaurant | ✓ | ✓ | ✓ | ✓ | ✓ |
| admin | ✓ | ✓ | | ✓ | ✓ |

The role comes from `GET /me` through `AppShellViewModel`, never from a composable. It is requested again only when the user changes, not on every token refresh, so the tabs do not flicker. If the request fails, a bar says "No se pudo cargar tu rol" with a retry button.

Hiding a tab is **UX, not security**: the backend still answers 403 to an account without access, and "Rendimiento" already shows its restricted state for that case.

**Files**: `ui/auth/ProfileViewModel.kt`, `data/repository/ProfileRepository.kt`, `ui/navigation/AppShellViewModel.kt`, `ui/navigation/UniEatNavHost.kt`.

---

## 12. BQ-01 — Feed loading performance — Samuel

**The question** (Type 1, from Sprint 1): over the last seven days, which combinations of connection type, device model and OS version had the highest feed-loading failure rates and 95th-percentile request-to-render times, broken down by hour?

**Who uses it**: the development team, to know where the feed fails or is slow and what to optimize first.

```mermaid
flowchart LR
    A[FeedViewModel starts a load<br/>loadId + start time] --> B[GET /feed]
    B -- response --> C[render_pending]
    B -- ApiException --> D[failure + error code]
    C --> E[FeedScreen draws the list<br/>render time]
    C -- no render after 60 s --> AB[abandoned]
    D & E & AB --> F[("feed-load-telemetry.json<br/>on the phone")]
    F --> W[WorkManager<br/>runs when online]
    W --> U[FeedLoadUploader<br/>batches of 100]
    U --> P[POST /telemetry/feed-loads]
    P --> T[("feed_load_telemetry<br/>Postgres")]
    T --> S[feed_load_summary<br/>7 days, grouped,<br/>failure rate and p95]
    S --> G[GET /telemetry/feed-loads/summary]
    G --> H[BQ-01 card in Rendimiento]
    F -. server unreachable .-> L[Local report<br/>this device only] -.-> H
```

**How it is computed**: failure rate = failures ÷ (rendered + failures) per group. The p95 is the nearest-rank percentile of the time from the start of the request to the moment the list is drawn. A load the user abandons (left the screen before the feed was drawn) is counted apart, because leaving the screen is not a technical failure. An empty feed that loads fine counts as a success, not a failure.

**The pipeline**: each settled record (rendered, failure, or abandoned after 60 s) waits on disk until WorkManager finds network, then `FeedLoadUploader` sends it in batches of up to 100 to `POST /telemetry/feed-loads`. The server ignores a repeated `loadId`, so a batch resent after a timeout is never counted twice. Postgres groups the last seven days by connection, device, OS version and hour (Bogotá time) in `feed_load_summary`, with `percentile_disc(0.95)`: the same nearest-rank definition the phone uses. The card reads `GET /telemetry/feed-loads/summary` and says "Todos los dispositivos"; if the server cannot answer, it shows this phone's own records and says so.

**Why a separate endpoint**: `POST /events/batch` only accepts events tied to a menu publication, and a feed load is not about one menu. Mixing them would have meant loosening a contract iOS also depends on.

**Privacy**: only technical data travels: no location, no menu, no text typed by the user. The summary is admin-only, like the other team dashboards.

**Files**: Android: `data/telemetry/FeedLoadTelemetry.kt`, `data/telemetry/FeedLoadUpload.kt` (repository, uploader, worker), the load and render hooks in `FeedViewModel` and `FeedScreen`, and `FeedLoadingBqCard` in `PerformanceScreen.kt`. Backend: migration `20261003000000_bq01_feed_load_telemetry.sql`, `handlers/telemetry.ts`, `parseFeedLoadBatch` in `domain/validation.ts`.

---

## 13. Shake-to-refresh and "Menor fila" — Samuel

**Shake-to-refresh (sensor)**: shaking the phone on "Hoy" reloads the feed. The accelerometer reading is turned into g-force; above 2.7 g counts as a shake, and only one shake every 1.5 seconds is accepted. The listener is registered only while the feed is in the foreground (`LifecycleResumeEffect`), so it does not drain battery in the background, and a shake during a load is ignored. The threshold logic is a pure function with its own tests.

**"Menor fila" (smart feature)**: a third `RecommendationStrategy` in "Elige por mí". It puts first the menus whose wait estimate passes the BQ-06 evidence rule, ordered from shortest to longest wait, and keeps the backend order for ties and for menus without evidence. It uses the server clock, like the rest of the feed, so a wrong phone clock cannot discard valid reports.

**Files**: `data/sensor/ShakeRefreshController.kt`, `ui/feed/RecommendationStrategy.kt`.

---

## 14. Design patterns

### Observer — Juan José

```mermaid
flowchart LR
    AF[("Shared filters<br/>MutableStateFlow")]
    FVM1[FeedViewModel · Hoy] -- observes --> AF
    FVM2[FeedViewModel · Elige por mí] -- observes --> AF
    FVM1 -- publishes --> S1[StateFlow FeedUiState]
    FVM2 -- publishes --> S2[StateFlow FeedUiState]
    S1 -- collected by --> FS[FeedScreen]
    S2 -- collected by --> RS[RecommendScreen]
```

`StateFlow` is a box that holds a value and notifies whoever watches it when it changes. What we designed, beyond the library: the sealed state contract (only one state can exist at a time), the ViewModel as the single writer, and the non-trivial case of two ViewModels observing the same filters flow, proven by a unit test.

### Strategy — Juan José

```mermaid
classDiagram
    class RecommendationStrategy {
        <<interface>>
        +label: String
        +pick(menus, index, now) DailyMenu?
    }
    class BestRankedStrategy {
        follows the rank-v1 order
    }
    class CheapestStrategy {
        lowest price first
    }
    class FastestWaitStrategy {
        shortest supported wait · Samuel
    }
    RecommendationStrategy <|.. BestRankedStrategy
    RecommendationStrategy <|.. CheapestStrategy
    RecommendationStrategy <|.. FastestWaitStrategy
    FeedViewModel --> RecommendationStrategy : holds the active one
    RecommendScreen --> RecommendationStrategy : pick()
```

Three implementations a few lines each, interchangeable at runtime with the chips in "Elige por mí". The third one, "Menor fila", was added by Samuel without touching the screen or the other two: that is the point of the pattern. A hand-implemented pattern, not an annotation or a library piece: our interface, our implementations, each with its own test.

### Repository — Camilo

```mermaid
classDiagram
    class MenuRepository {
        <<interface>>
        +feed(filters) FeedResponse
        +menu(id) MenuDetailResponse
    }
    class RemoteMenuRepository {
        Supabase API v1
    }
    class FakeMenuRepository {
        debug builds, seed copy
    }
    MenuRepository <|.. RemoteMenuRepository
    MenuRepository <|.. FakeMenuRepository
    FeedViewModel --> MenuRepository
    MenuDetailViewModel --> MenuRepository
```

ViewModels depend on the contract, never on HTTP. The remote implementation adapts the API; the debug fake answers with seeded data and the same error codes (404, 410, 403). This is what makes stub-based tests and the demo mode possible.

### Proxy — Samuel David Rozen Mogollon

```mermaid
classDiagram
    class AccessTokenProvider {
        <<interface>>
        +accessToken() String
    }
    class SessionManager {
        +state StateFlow~AuthState~
        +signIn(email, password)
        +signOut()
        +accessToken() String
    }
    class AuthRepository {
        <<interface>>
        +signIn() Session
        +refresh() Session
        +signOut()
    }
    AccessTokenProvider <|.. SessionManager
    ApiClient --> AccessTokenProvider : asks for a token
    SessionManager --> AuthRepository
    SessionManager --> SessionStore
    AuthRepository <|.. SupabaseAuthRepository
```

The problem is token lifecycle coupling. Without this proxy, every repository would need to know whether the access token is valid, when it expires, and how to refresh it. `SessionManager` centralizes that decision behind the existing `AccessTokenProvider` interface. It restores the persisted session, refreshes within a one-minute margin, and uses a `Mutex` so concurrent API requests do not trigger duplicate refreshes. A failed authentication refresh clears the local session and moves the app to `SignedOut`. `ApiClient` did not change at all: it already depended on the interface, and `SessionManager` replaced the debug implementation behind it.

Known limitation: DataStore preferences persist the refresh token but do not encrypt it at rest. This must be addressed before production use.

---

## 15. Tests and validation

Run with `./gradlew :app:testDebugUnitTest` on October 2, 2026. Real result: **112 tests, 0 failures, 0 skipped**, on the JVM without an emulator: the 88 from the feed and detail slices plus 24 from Samuel's slice. The BQ-01 upload added 7 more (`FeedLoadSyncTest`), for **119**.

```mermaid
pie title 119 unit tests by area
    "Feed (ViewModel)" : 14
    "Dashboard" : 6
    "BQ-06 wait rule" : 5
    "HTTP contract" : 8
    "Menu detail" : 28
    "BQ-05 location" : 11
    "Analytics pipeline" : 9
    "Proximity / GPS math" : 4
    "Auth, session and profile" : 16
    "BQ-01 telemetry and upload" : 10
    "Shake and Menor fila" : 5
    "Formatting and fakes" : 3
```

| Suite | What it proves | Tests | Result |
| --- | --- | --- | --- |
| FeedViewModelTest | Backend order kept, expired/closed hidden, empty, error+retry, clear offline, expired session, shared filters across tabs, the two original strategies, single impression | 14 | PASS |
| PerformanceViewModelTest | Summary, period switch without extra loads, restricted access, expired session, error+retry, insufficient data passed through | 6 | PASS |
| WaitEvidenceTest | The 3-reports / 30-minutes rule, including the exact edge | 5 | PASS |
| ApiContractTest | Headers, query parameters, feed and performance decoding, error envelope | 8 | PASS |
| MenuDetailViewModelTest | Detail states, 410, events | 9 | PASS |
| MenuDetailDistanceTest | Permissions, distances, arrival | 9 | PASS |
| MenuDetailReportTest | Community reports | 10 | PASS |
| LocationGuidanceTest | BQ-05 references and warnings | 11 | PASS |
| EventPipelineTest | Queue, uploader, deduplication, bounds | 9 | PASS |
| ProximityTest | Haversine and walking minutes | 4 | PASS |
| SpanishPresentationTest, FakeReportLoopTest | Formats and fakes | 3 | PASS |
| SessionManagerTest | Token reuse, single refresh for concurrent callers, auth failure clears the session, server failure keeps it, logout offline | 5 | PASS |
| SupabaseAuthRepositoryTest | Password grant contract, bad credentials, rate limit, server error | 4 | PASS |
| LoginViewModelTest | Validation before calling auth, loading to success, double tap ignored | 2 | PASS |
| ProfileViewModelTest | Profile content, error and retry | 2 | PASS |
| AppShellViewModelTest | Role error and retry, demo profile, no reload on token refresh | 3 | PASS |
| ShakeRefreshControllerTest | Shake threshold and throttle | 3 | PASS |
| RecommendationStrategySamuelTest | "Menor fila" order and BQ-06 evidence rule | 2 | PASS |
| FeedLoadTelemetryTest | p95, grouping, failure rate with abandoned loads apart | 3 | PASS |
| FeedLoadSyncTest | When a load is ready to send, batches, offline retry, invalid batch dropped, backend summary decoding and fallback | 7 | |

There are no instrumented UI tests (Compose/emulator); visual validation is done by hand on the emulator with the demo data.

**Real bugs the tests caught during development** (and their fixes):

1. A test stub fell into infinite recursion (`StackOverflowError`) when a field and a function ended up with the same signature; the suite caught it immediately and the field was renamed.
2. The filters draft used `rememberSaveable` with a class that is not Parcelable, which would have crashed at runtime; it was changed to `remember`, accepting that a rotation closes the draft.

---

## 16. Implementation decisions

- **No client-side ranking or metrics**: the backend already orders, explains and aggregates; duplicating that would mean inventing data and drifting away from iOS.
- **Server clock for freshness**: the phone clock can be wrong; `serverNow` comes in every response, the same decision the detail and iOS made.
- **Shared filters through one flow in the container**: the alternative (passing filters through navigation or duplicating them per tab) desynchronizes the screens; one `MutableStateFlow` keeps them identical and is trivial to test.
- **`collectLatest` to cancel stale loads**: the race guard in one word.
- **Strategy on the recommendation and nowhere else**: it is the only spot in the feed with genuinely interchangeable criteria; anywhere else it would be decorative.
- **Offline as a clear error, not a cache**: a team scope decision; the context-aware behavior of this deliverable lives in the detail with the GPS.
- **Extending `AnalyticsRepository` for the dashboard** instead of creating another repository: same data family, zero duplication.
- **Session behind the existing `AccessTokenProvider`** (Samuel): no repository or `ApiClient` change was needed to go from the debug account to real users.
- **Refresh one minute early, with a `Mutex`** (Samuel): a token never expires mid-request, and ten parallel requests cause one refresh, not ten.
- **Only 400/401 log the user out** (Samuel): a 500 or a dropped connection keeps the session; logging out on any error would punish the user for a server problem.
- **Best-effort sign-out** (Samuel): the local session is always deleted, even if Supabase cannot be reached.
- **DataStore without encryption** (Samuel): enough for the course, documented as a known limitation for production.
- **Tabs by role as UX, the backend as the security boundary** (Samuel): the 403 still protects the data.
- **Abandoned loads apart from failures in BQ-01** (Samuel): a user leaving the screen says nothing about the network or the app.
- **BQ-01 with its own endpoint** (Samuel): `POST /events/batch` only accepts events tied to a menu publication; a separate `POST /telemetry/feed-loads` keeps that contract intact for iOS.
- **Same p95 on the phone and in Postgres** (Samuel): nearest rank in Kotlin and `percentile_disc` in SQL, so the local fallback and the server answer mean the same thing.

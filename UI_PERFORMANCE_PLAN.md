# Sratim UI Performance Optimization Plan

## Root-Cause Diagnosis

1. **Per-Request `OkHttpClient` & `Retrofit` Instantiation (`600–900ms` penalty per screen)**
   - `NetworkClient.createApi()` previously built a new `OkHttpClient`, `Retrofit`, and `Gson` instance on the Main Thread for every API call, discarding the TCP/TLS/HTTP2 connection pool and forcing a fresh TLS handshake on every screen transition.
   - `NetworkClient.getBaseUrl()` recompiled a `Regex` on every call and invoked `NetworkUtils.isInSameSubnet()` synchronously on the Main Thread (`InetAddress.getByName` + `ConnectivityManager` Binder IPC).
   - `HttpLoggingInterceptor` (`Level.HEADERS`) and per-item `Log.e("SratimNet", ...)` ran inside UI-thread mapping loops.

2. **4K `original` Backdrop Downloads & Unconfigured Coil Pipeline (`41MB` LOS GC stalls / `Skipped 74 frames`)**
   - `ShowDetailViewModel` requested `/images/backdrops/original/...` (up to `3840x2160`) for every `160x90dp` episode thumbnail.
   - `MovieDetailViewModel`, `ShowDetailViewModel`, `MainViewModel`, and `TvHomeChannelPublisher` also requested `original` backdrops instead of display-matched TMDB sizes (`w300`, `w780`, `w1280`), while detail hero posters used blurry `w185` instead of server-cached `w500`.
   - Neither `:app-tv` nor `:app-mobile` registered a custom Coil `ImageLoaderFactory`, leaving Coil on a separate HTTP client without tuned disk/memory caches or `RGB_565` support.

3. **Sequential Network Waterfalls, Missing In-Memory Cache & Duplicate TV Launcher Work**
   - `MainViewModel.fetchLibraries()` awaited `getLibraries()` sequentially before starting `getRecentlyWatched()`, doubling cold-start network latency.
   - Navigating back and forth between Home, Libraries, Details, and Playback re-fetched identical JSON with a full-screen loading spinner every time (`MovieDetail -> Playback` fetched `/api/v1/movie` twice in a row).
   - `MainActivity.onResume()` called `TvHomeChannelPublisher.fetchAndPublish()` while `AppNavigation` simultaneously called `TvHomeChannelPublisher.publishRecentlyWatched()`, duplicating network and `TvProvider` SQLite work on every launch/resume.
   - DTO-to-UI model mapping (`map`, `groupBy`, string formatting) executed on `Dispatchers.Main`.

4. **Jetpack Compose Lazy Layout, Overdraw & Cross-Module Stability Bottlenecks**
   - All `LazyRow`, `LazyColumn`, and `LazyVerticalGrid` containers across `:app-tv` and `:app-mobile` (`MainScreen`, `LibraryScreen`, `ShowDetailScreen`) lack `key` and `contentType`, preventing slot reuse during fast D-pad/touch scrolling.
   - `ShowDetailScreen` sorts `show.seasons.keys.toList().sorted()` inside composition on every recomposition.
   - `MovieDetailScreen` and `ShowDetailScreen` (TV) stack extra full-screen `Box`es allocating `Brush` gradients every recomposition (`3x` full-screen 1080p overdraw) instead of `Modifier.drawWithCache`.
   - `:core` does not apply the Compose compiler, making flat leaf item models (`LibraryItemUiModel`, `RecentlyWatchedUiModel`, `EpisodeUiModel`, `Library`) unstable across modules.
   - Default `NavHost` crossfade transitions (~700ms) compose and draw outgoing and incoming screens concurrently on TV hardware.

5. **Build Configuration & Server-Side Episode Probing**
   - `app-tv/build.gradle.kts` disables R8 (`optimization { enable = false }`) even in `release`, and `debug` runs Compose with debuggable ART overhead on Cortex-A55 TV cores.
   - On the Zig server (`src/web/handlers/api_v1/media.zig`), `handleGetShow` synchronously opens every episode file on disk (`stat` + `streamer.getMediaInfo`) on every `GET /api/v1/show` request.

---

## Phased Implementation Roadmap

### Phase 1 — Network, Caching & Image Pipeline Overhaul (Implemented)
1. **Singleton `OkHttpClient` + Cached `Retrofit` (`NetworkClient.kt`, `NetworkUtils.kt`)**
   - Reuse one shared `OkHttpClient` (HTTP/2 connection pool + 50 MB HTTP disk cache) and cache `SratimApi` instances keyed by `(baseUrl, token)`.
   - Provide `NetworkClient.getImageOkHttpClient()` sharing the same `ConnectionPool` and `Dispatcher`, with an automatic `404 -> original` fallback interceptor in case an on-demand TMDB size cannot be fetched by the server.
   - Parse IPv4 literals without DNS lookups, pre-compile regexes, cache resolved `baseUrl`s, and restrict HTTP logging to debug builds without per-item `Log.e` spam.
2. **Right-Sized TMDB Artwork + Custom Coil `ImageLoader` (`SratimApplication.kt`)**
   - Episode stills (`160x90dp`): `backdrops/w300` (~20x smaller than `original`).
   - Full-screen darkened detail backdrops: `backdrops/w1280`.
   - TV Launcher channel backdrops: `backdrops/w780`.
   - Detail hero posters: `posters/w500` (pre-cached on server); grid cards: `posters/w185` (pre-cached on server).
   - Register `SratimApplication : Application(), ImageLoaderFactory` in `:app-tv` and `:app-mobile` with shared HTTP/2 pool, 25% RAM cache, 150 MB disk cache, `allowHardware(true)`, `allowRgb565(true)`, and `crossfade(150)`.
3. **Parallel Home Fetch, `SratimDataCache` & Deduplicated TV Sync**
   - Run `getLibraries()` and `getRecentlyWatched()` concurrently via `async` on `Dispatchers.IO` and map UI models on `Dispatchers.Default`.
   - Introduce `SratimDataCache` for stale-while-revalidate instant screen loads (`0ms` spinner on re-entry and `Detail -> Playback` transitions) and immediate watch-progress propagation.
   - Deduplicate `TvHomeChannelPublisher` updates with signature fingerprinting, one-time channel logo storage, and lifecycle-driven refresh after playback.

### Phase 2 — Jetpack Compose UI & Navigation Smoothness (Implemented)
1. **Lazy Layout Keys & Content Types**
   - Add primitive `key` and `contentType` to all `LazyRow`, `LazyColumn`, and `LazyVerticalGrid` instances across TV and Mobile `MainScreen`, `LibraryScreen`, and `ShowDetailScreen`.
2. **Eliminate Redundant Composition Work & Overdraw**
   - Pre-sort seasons in `ShowDetailViewModel` (`LinkedHashMap`) so `ShowDetailScreen` never sorts during recomposition.
   - Replace full-screen gradient overlay `Box`es in TV `MovieDetailScreen` and `ShowDetailScreen` with `Modifier.drawWithCache` on the backdrop `AsyncImage`, and remove redundant `.background(Color.Black)` layers.
   - Add `compose_stability.conf` for `:core`'s flat primitive leaf item models (`LibraryItemUiModel`, `RecentlyWatchedUiModel`, `EpisodeUiModel`, `Library`), keeping collection-heavy `UiState` classes on fast `===` Strong Skipping checks.
   - Configure crisp `150ms` `NavHost` transitions and `collectAsStateWithLifecycle()` on DataStore flows.

### Phase 3 — Build Optimization, Single-Episode Client Fast Path & Backend API Spec (Implemented)
1. **R8 Full Mode + `profileinstaller` for TV & Mobile (`app-tv/build.gradle.kts`, `app-mobile/build.gradle.kts`, `SratimApi.kt`)**
   - Enable AGP 9 `optimization { enable = true }` (R8 full-mode shrinking, obfuscation, optimization, and resource shrinking) in `release` and inherit it in a non-debuggable `staging` build (`isDebuggable = false`, signed with the `debug` key for seamless on-device installation).
   - Annotate all Retrofit/Gson DTO properties in `SratimApi.kt` with `@SerializedName` and upgrade to Gson `2.15.0` so embedded R8 rules preserve DTO deserialization without broad package-wide keep rules.
   - Add `androidx.profileinstaller` (`1.4.1`) to `:app-tv` and `:app-mobile` for AOT Baseline Profile compilation of Compose, TV Material, Navigation, Coil, and OkHttp.
2. **Client-Side Multi-Season Show Resilience (`PlaybackViewModel.kt`, `SratimApi.kt`, `NetworkClient.kt`)**
   - Decouple episode playback preparation in `PlaybackViewModel` from `GET /api/v1/show` so launching an episode (including from *Recently Watched* / *Watch Next*) queries `GET /api/v1/episode?id={id}` (falling back to single-episode `GET /player?episode_id={id}`) instead of probing all seasons of a show.
   - Raise API `readTimeout` to `120s` in `NetworkClient` so pre-upgrade servers probing 20+ season shows on cold disks do not fail prematurely with client-side socket timeouts.
3. **Backend Performance Spec (`BACKEND_PERFORMANCE_SPEC.md`)**
   - Document the complete Zig backend remediation plan (`handleGetShow` zero-disk-I/O refactor, `GET /api/v1/episode?id={id}` endpoint, `getMediaInfo` single-open & subtitle-probe skip optimizations, and optional `SratimStorage` probe caching).

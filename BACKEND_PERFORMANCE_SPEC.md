# Sratim Backend Performance Specification — Multi-Season Show Loading (`Grey's Anatomy`)

This specification diagnoses why shows with many seasons (such as *Grey's Anatomy*, with 20+ seasons / ~430+ episodes) suffer severe delays and timeouts on `GET /api/v1/show?id={id}`, and provides a complete, step-by-step Zig implementation plan for the `sratim` backend.

---

## 1. Root-Cause Diagnosis

### Why Desktop Web (`/show?id={id}`) Is Instant (`< 2 ms`) While `/api/v1/show?id={id}` Times Out (`15–90+ s`)

In `src/web/catalog/show.zig`, `renderShowPage(...)` reads **exclusively from in-memory `SratimStorage`** (`cat.getShowById` + `cat.getEpisodesByShow`) and performs **zero disk I/O**. Media container probing (`streamer.getMediaInfo`) only happens when the user clicks **Play** on a single episode (`/player?episode_id={id}` in `src/web/handlers/player/html.zig`).

By contrast, `handleGetShow(...)` in `src/web/handlers/api_v1/media.zig` (lines 204–269) executes **five compounding per-episode bottlenecks synchronously on the HTTP request thread** for every episode in `for (episodes) |ep|`:

1. **Redundant `resolveMediaPath` Lookup Inside the Episode Loop (`N` Lock + Clone Allocations)**
   - `common.resolveMediaPath(database, allocator, .{ .library_id = library_id, .file_path = file_path })` is called for **each of the ~430 episodes**, locking `SratimStorage`, cloning the `Library` struct (`name`, `path`, `metadata_language`, `ignore_patterns`), joining paths, and freeing them every iteration — even though `library_id` is constant for the entire show.

2. **Redundant Disk `openFile` + `stat` (`N` File Opens)**
   - Lines 217–222 open every episode video file on disk and call `file.stat(io)` to obtain `ep_file_size`, even though `ep.file_size` was **already** recorded by `scanner.zig` (`scanShowDirRecursive` line 77) and is already in RAM on `schema.Episode`.

3. **Synchronous `streamer.getMediaInfo` Container Probing Across Every Episode (`2N` to `3N` File Opens + Deep Seeks)**
   - `native_metadata.getMediaInfo` (`src/media/native/metadata.zig` line 65) opens the video file a **2nd time** to sniff 16 magic bytes, closes it, and then `getMp4MediaInfo` / `getMatroskaMediaInfo` opens the file a **3rd time**!
   - **MP4 episodes (`getMp4MediaInfo` -> `isobmff.parseMp4Media`)**: Allocates and parses the **entire** `stbl` sample table (`stts`, `stsc`, `stsz`, `stco`/`co64`, `stss` — tens of thousands of sample entries per episode) just to compute `runtime` and `audio_tracks`, then immediately frees them.
   - **MKV episodes with untagged subtitle streams (`metadata.zig` lines 378–387)**: Whenever a text subtitle track has `lang == "und"` and an empty `Name`, `getMatroskaMediaInfo` calls `subtitles.peekSubtitleSample(...)` (`src/media/native/subtitles/mkv.zig`), opening the MKV a **4th time** and scanning Matroska video/audio `Cluster`s across tens of megabytes of the media file to sample subtitle text — **even though `handleGetShow` completely discards `media_info.subtitle_tracks`!**

4. **Per-Episode `LogsStorage` Lock Acquisition (`N` RwLock Cycles)**
   - `logging_mod.getEpisodePlaybackProgress(database, session_info.username, ep_id)` acquires and releases `LogsStorage.rwlock` 430 separate times instead of fetching the user's episode progress in a single shared-lock pass.

5. **Per-Episode Temporary String Allocations (`4N` Heap Allocations)**
   - Each iteration allocates `ep_audio_tracks_json`, `esc_ep_title`, `esc_ep_overview`, and `ep_json` via `std.fmt.allocPrint` instead of streaming directly into a pre-sized `json` `ArrayList(u8)` using `utils.escapeJsonString`.

### Quantitative Impact for a 20-Season Show (~430 Episodes)

| Metric | Current `GET /api/v1/show` | After Backend Plan (`GET /api/v1/show`) | Single-Episode Play (`GET /api/v1/episode`) |
|---|---|---|---|
| Disk `openFile` syscalls | **1,290 – 1,720+** | **0** | **1** (cold) / **0** (cached) |
| Disk seeks / cluster reads | Hundreds of MB across 430 files | **0 bytes** | Header only (`~64 KB`) |
| RwLock acquisitions | **~862** | **3** (`show`, `episodes`, `progress`) | **3** |
| Latency on NAS / HDD / ZFS | **15 – 90+ seconds (Timeout)** | **1 – 3 ms** | **5 – 25 ms** (cold) / **< 1 ms** (cached) |

> **Client-Side Note:** Neither Android TV (`ShowDetailScreen.kt`) nor Android Mobile (`ShowDetailScreen.kt`) displays `runtime` or `audio_tracks` on the Show Details screen — those fields are only needed when starting playback of **one** chosen episode (`PlaybackViewModel.kt`).

---

## 2. Phased Backend Implementation Plan

### Step 1 (Critical / Immediate) — Zero-Disk-I/O `handleGetShow` (`< 3 ms`)

**Goal:** Make `GET /api/v1/show?id={id}` read strictly from in-memory `SratimStorage` + `LogsStorage` by default (matching `web/catalog/show.zig`), reducing *Grey's Anatomy* load time from `15–90s` to `< 3 ms`.

#### Key Contract Detail (`audio_tracks: null` vs `[]`)
When an episode's container has **not** been probed yet, emit `"audio_tracks":null` (not `[]`):
- `"audio_tracks": [...]` means container tracks were probed (even if empty `[]`).
- `"audio_tracks": null` signals to `PlaybackViewModel` that tracks were deferred so the client should call `GET /api/v1/episode?id={episode_id}` (Step 2) — or fall back to `GET /player?episode_id={episode_id}` — for **only the single episode being played**.
- Support an optional `?probe=1` query flag on `GET /api/v1/show?id={id}&probe=1` if synchronous probing is ever desired for diagnostics.

#### 1A. Add Single-Lock Progress Lookup Map Helper (`src/db/logging.zig`)
`logging_mod.getEpisodeProgressForUser(database, allocator, username)` **already exists** in `src/db/logging.zig` (line 103) and fetches all episode progress entries for `username` under **one** `LogsStorage` read lock! Build a temporary `std.AutoHashMap(i64, f64)` in `handleGetShow` from that slice:

```zig
var progress_map = std.AutoHashMap(i64, f64).init(allocator);
defer progress_map.deinit();
if (logging_mod.getEpisodeProgressForUser(database, allocator, session_info.username) catch null) |prog_list| {
    defer allocator.free(prog_list);
    try progress_map.ensureTotalCapacity(@intCast(prog_list.len));
    for (prog_list) |p| {
        progress_map.putAssumeCapacity(p.episode_id, p.position);
    }
}
```

#### 1B. Update `handleGetShow` in `src/web/handlers/api_v1/media.zig`
- Skip `common.resolveMediaPath`, `openFile`, `stat`, and `streamer.getMediaInfo` unless `utils.parseQueryInt(u8, request.head.target, "probe") == 1`.
- Use `ep.file_size` directly from `schema.Episode` (already populated by `scanner.zig`), and `ep.runtime_mins` / `ep.audio_tracks_json` if cached (Step 4).
- Pre-allocate `json` capacity (`episodes.len * 320 + 512`) and write directly with `utils.escapeJsonString(&json, allocator, ...)`:

```zig
const probe_files = (utils.parseQueryInt(u8, request.head.target, "probe") orelse 0) == 1;

// Single read-lock fetch of all episode resume positions for this user
var progress_map = std.AutoHashMap(i64, f64).init(allocator);
defer progress_map.deinit();
if (logging_mod.getEpisodeProgressForUser(database, allocator, session_info.username) catch null) |prog_list| {
    defer allocator.free(prog_list);
    try progress_map.ensureTotalCapacity(@intCast(prog_list.len));
    for (prog_list) |p| {
        progress_map.putAssumeCapacity(p.episode_id, p.position);
    }
}

var json = std.ArrayList(u8).empty;
defer json.deinit(allocator);
try json.ensureTotalCapacity(allocator, episodes.len * 320 + 512);

try json.appendSlice(allocator, "{\"success\":true,\"show\":{\"id\":");
var num_buf: [64]u8 = undefined;
try json.appendSlice(allocator, try std.fmt.bufPrint(&num_buf, "{d},\"library_id\":{d},\"title\":\"", .{ show_id, library_id }));
try utils.escapeJsonString(&json, allocator, title);
try json.appendSlice(allocator, "\",\"overview\":\"");
try utils.escapeJsonString(&json, allocator, overview);
try json.appendSlice(allocator, "\",\"poster_path\":\"");
try utils.escapeJsonString(&json, allocator, poster_path);
try json.appendSlice(allocator, "\",\"backdrop_path\":\"");
try utils.escapeJsonString(&json, allocator, backdrop_path);
try json.appendSlice(allocator, "\",\"tmdb_id\":\"");
try json.appendSlice(allocator, tmdb_id);
try json.appendSlice(allocator, "\",\"episodes\":[");

// Resolve library base path ONCE if probe=1 was explicitly requested
var lib_opt = if (probe_files) (cat.getLibraryById(allocator, library_id) catch null) else null;
defer if (lib_opt) |*l| l.deinit(allocator);

var first_ep = true;
for (episodes) |ep| {
    if (!ep.is_present) continue;
    if (!first_ep) try json.append(allocator, ',');
    first_ep = false;

    var ep_file_size: u64 = @intCast(@max(0, ep.file_size));
    var ep_runtime: u32 = 0;
    var ep_audio_tracks_json = std.ArrayList(u8).empty;
    defer ep_audio_tracks_json.deinit(allocator);

    if (probe_files and lib_opt != null) {
        if (std.fs.path.join(allocator, &.{ lib_opt.?.path, ep.file_path }) catch null) |full_path| {
            defer allocator.free(full_path);
            if (allocator.dupeSentinel(u8, full_path, 0) catch null) |cp| {
                defer allocator.free(cp);
                if (streamer.getMediaInfoFast(allocator, io, cp) catch null) |media_info| {
                    defer media_info.deinit(allocator);
                    if (media_info.duration > 0) {
                        ep_runtime = @as(u32, @intFromFloat(media_info.duration / 60.0));
                    }
                    try appendAudioTracksJson(allocator, &ep_audio_tracks_json, media_info.audio_tracks);
                }
            }
        }
        if (ep_audio_tracks_json.items.len == 0) {
            try ep_audio_tracks_json.appendSlice(allocator, "[]");
        }
    } else {
        // Unprobed fast path: zero disk I/O!
        try ep_audio_tracks_json.appendSlice(allocator, "null");
    }

    const basename = std.fs.path.basename(ep.file_path);
    const display_title = ep.title orelse basename;
    const display_overview = ep.overview orelse "";
    const still_path = ep.still_path orelse "";
    const ep_resume_pos = progress_map.get(ep.id) orelse 0.0;

    try json.appendSlice(allocator, try std.fmt.bufPrint(
        &num_buf,
        "{{\"id\":{d},\"season\":{d},\"episode\":{d},\"title\":\"",
        .{ ep.id, ep.season, ep.episode },
    ));
    try utils.escapeJsonString(&json, allocator, display_title);
    try json.appendSlice(allocator, "\",\"overview\":\"");
    try utils.escapeJsonString(&json, allocator, display_overview);
    try json.appendSlice(allocator, "\",\"still_path\":\"");
    try utils.escapeJsonString(&json, allocator, still_path);
    try json.appendSlice(allocator, try std.fmt.bufPrint(
        &num_buf,
        "\",\"file_size\":{d},\"runtime\":{d},\"resume_position\":{d:.1},\"audio_tracks\":",
        .{ ep_file_size, ep_runtime, ep_resume_pos },
    ));
    try json.appendSlice(allocator, ep_audio_tracks_json.items);
    try json.append(allocator, '}');
}

try json.appendSlice(allocator, "]}}");
```

---

### Step 2 — Add Single-Episode Endpoint `GET /api/v1/episode?id={episode_id}` (`~10 ms`)

**Goal:** Provide a dedicated v1 JSON endpoint symmetric with `GET /api/v1/movie?id={movie_id}` that probes **only the 1 requested episode** right when playback starts (including when launching directly from *Recently Watched* or Android TV *Watch Next* without ever loading the parent show).

> **Already Wired in Android Client:** `SratimApi.getEpisodeDetails(episodeId)` and `PlaybackViewModel` in this repository already call `GET /api/v1/episode?id={id}` first and automatically fall back to `GET /player?episode_id={id}` if `/api/v1/episode` returns `404`.

#### JSON Response Schema (`GET /api/v1/episode?id={id}`)
```json
{
  "success": true,
  "episode": {
    "id": 233827,
    "show_id": 10,
    "library_id": 2,
    "season": 14,
    "episode": 5,
    "title": "Danger Zone",
    "overview": "...",
    "still_path": "/...",
    "file_size": 1948251136,
    "runtime": 43,
    "duration_sec": 2584.42,
    "resume_position": 612.5,
    "audio_tracks": [
      { "id": 1, "label": "Russian", "codec": "AC-3" },
      { "id": 2, "label": "English", "codec": "EAC-3" }
    ]
  }
}
```

#### 2A. Handler Implementation (`src/web/handlers/api_v1/media.zig`)
```zig
pub fn handleGetEpisode(
    request: *std.http.Server.Request,
    allocator: std.mem.Allocator,
    database: *db_mod.Database,
    config: *const config_mod.Config,
    io: std.Io,
    session_info: session_mod.SessionInfo,
) !void {
    if (request.head.method != .GET) {
        try request.respond("{\"success\":false,\"error\":\"Method not allowed\"}", .{ .status = .method_not_allowed });
        return;
    }

    const episode_id = utils.parseQueryInt(i64, request.head.target, "id") orelse {
        try request.respond("{\"success\":false,\"error\":\"Missing episode id\"}", .{ .status = .bad_request });
        return;
    };

    const cat = database.catalog orelse {
        try request.respond("{\"success\":false,\"error\":\"Catalog not configured\"}", .{ .status = .internal_server_error });
        return;
    };

    const ep_opt = try cat.getEpisodeById(allocator, episode_id);
    if (ep_opt == null or !ep_opt.?.is_present) {
        try request.respond("{\"success\":false,\"error\":\"Episode not found\"}", .{ .status = .not_found });
        return;
    }
    var ep = ep_opt.?;
    defer ep.deinit(allocator);

    const sh_opt = try cat.getShowById(allocator, ep.show_id);
    if (sh_opt == null or !sh_opt.?.is_present) {
        try request.respond("{\"success\":false,\"error\":\"Parent show not found\"}", .{ .status = .not_found });
        return;
    }
    var sh = sh_opt.?;
    defer sh.deinit(allocator);

    var ep_file_size: u64 = @intCast(@max(0, ep.file_size));
    var ep_duration_sec: f64 = 0.0;
    var ep_runtime: u32 = 0;
    var ep_audio_tracks_json = std.ArrayList(u8).empty;
    defer ep_audio_tracks_json.deinit(allocator);

    if (common.resolveMediaPath(database, allocator, .{ .library_id = sh.library_id, .file_path = ep.file_path }) catch null) |resolved| {
        defer allocator.free(resolved.resolved_path);

        if (ep_file_size == 0) {
            if (std.Io.Dir.cwd().statFile(io, resolved.resolved_path, .{}) catch null) |st| {
                ep_file_size = st.size;
            }
        }

        if (allocator.dupeSentinel(u8, resolved.resolved_path, 0) catch null) |cp| {
            defer allocator.free(cp);
            if (streamer.getMediaInfo(allocator, io, cp, config.media_engine.metadata) catch null) |media_info| {
                defer media_info.deinit(allocator);
                if (media_info.duration > 0) {
                    ep_duration_sec = media_info.duration;
                    ep_runtime = @as(u32, @intFromFloat(media_info.duration / 60.0));
                }
                try appendAudioTracksJson(allocator, &ep_audio_tracks_json, media_info.audio_tracks);
            }
        }
    }
    if (ep_audio_tracks_json.items.len == 0) {
        try ep_audio_tracks_json.appendSlice(allocator, "[]");
    }

    const basename = std.fs.path.basename(ep.file_path);
    const display_title = ep.title orelse basename;
    const display_overview = ep.overview orelse "";
    const still_path = ep.still_path orelse "";
    const ep_resume_pos = logging_mod.getEpisodePlaybackProgress(database, session_info.username, ep.id) catch 0.0;

    var json = std.ArrayList(u8).empty;
    defer json.deinit(allocator);
    try json.ensureTotalCapacity(allocator, 512);

    var num_buf: [128]u8 = undefined;
    try json.appendSlice(allocator, try std.fmt.bufPrint(
        &num_buf,
        "{{\"success\":true,\"episode\":{{\"id\":{d},\"show_id\":{d},\"library_id\":{d},\"season\":{d},\"episode\":{d},\"title\":\"",
        .{ ep.id, ep.show_id, sh.library_id, ep.season, ep.episode },
    ));
    try utils.escapeJsonString(&json, allocator, display_title);
    try json.appendSlice(allocator, "\",\"overview\":\"");
    try utils.escapeJsonString(&json, allocator, display_overview);
    try json.appendSlice(allocator, "\",\"still_path\":\"");
    try utils.escapeJsonString(&json, allocator, still_path);
    try json.appendSlice(allocator, try std.fmt.bufPrint(
        &num_buf,
        "\",\"file_size\":{d},\"runtime\":{d},\"duration_sec\":{d:.2},\"resume_position\":{d:.1},\"audio_tracks\":",
        .{ ep_file_size, ep_runtime, ep_duration_sec, ep_resume_pos },
    ));
    try json.appendSlice(allocator, ep_audio_tracks_json.items);
    try json.appendSlice(allocator, "}}");

    try request.respond(json.items, .{
        .status = .ok,
        .extra_headers = &.{
            .{ .name = "content-type", .value = "application/json" },
        },
    });
}
```

#### 2B. Wire Route in `src/web/routers/api_v1.zig`
```diff
--- a/src/web/routers/api_v1.zig
+++ b/src/web/routers/api_v1.zig
@@ -13,6 +13,7 @@ pub const handleRenameLibrary = library.handleRenameLibrary;
 pub const handleDeleteLibrary = library.handleDeleteLibrary;
 pub const handleGetMovie = media.handleGetMovie;
 pub const handleGetShow = media.handleGetShow;
+pub const handleGetEpisode = media.handleGetEpisode;
 pub const handleGetRecentlyWatched = media.handleGetRecentlyWatched;
 
 /// Central router for /api/v1/* endpoints.
@@ -54,6 +55,9 @@ pub fn route(
     } else if (std.mem.startsWith(u8, target, "/api/v1/show?")) {
         try media.handleGetShow(request, allocator, database, config, io, session_info);
         return true;
+    } else if (std.mem.startsWith(u8, target, "/api/v1/episode?")) {
+        try media.handleGetEpisode(request, allocator, database, config, io, session_info);
+        return true;
     } else if (std.mem.eql(u8, target, "/api/v1/recently-watched") or std.mem.startsWith(u8, target, "/api/v1/recently-watched?")) {
         try media.handleGetRecentlyWatched(request, allocator, database, session_info);
         return true;
```

> **Bonus in `handleGetMovie`:** Also include `"duration_sec":{d:.2}` alongside `"runtime":{d}` in `handleGetMovie` (`src/web/handlers/api_v1/media.zig` line 136) and skip `openFile`+`stat` when `mov.file_size > 0`.

---

### Step 3 — Make Single-File `getMediaInfo` 5–10x Faster (`src/media/native/metadata.zig`)

Even when probing a **single** movie or episode (`handleGetMovie`, `handleGetEpisode`, `handlePlayer`), `native_metadata.getMediaInfo` currently does unnecessary work that stalls on NAS/HDDs:

1. **Eliminate Double `openFile` (`metadata.zig` lines 65–78)**
   - Currently `getMediaInfo` opens `file_path`, reads 16 magic bytes, closes `file`, and then `getMatroskaMediaInfo` / `getMp4MediaInfo` immediately opens `file_path` again.
   - **Fix:** Pass the already-open `std.Io.File` into `getMatroskaMediaInfoFromFile` / `getMp4MediaInfoFromFile` (seeking back to offset `0` via `file.reader(io, &file_buf)`).

2. **Skip MKV Cluster Subtitle Sniffing When Subtitles Are Unused (`metadata.zig` lines 378–387)**
   - In `getMatroskaMediaInfo`, if an MKV contains an untagged (`lang == "und"`) text subtitle track, `subtitles.peekSubtitleSample(...)` opens the file again and scans video/audio `Cluster` blocks until the first line of dialogue appears (often 30–120 MB into the file!).
   - `/api/v1/movie` and `/api/v1/episode` only serialize `duration` and `audio_tracks` — they never use `subtitle_tracks`.
   - **Fix:** Introduce `MetadataOptions`:
     ```zig
     pub const MetadataOptions = struct {
         include_subtitles: bool = true,
         detect_und_subtitles: bool = true,
     };
     ```
     Call `getMediaInfoWithOptions(allocator, io, cp, .{ .include_subtitles = false, .detect_und_subtitles = false })` from `handleGetMovie` and `handleGetEpisode` (keeping `.{}` default in `html.zig` for the web player).

3. **Header-Only MP4 Metadata Probe (`src/media/native/isobmff/`)**
   - `getMp4MediaInfo` currently calls `isobmff.parseMp4Media`, which parses and allocates full `stts`, `stsc`, `stsz`, `stco`/`co64`, and `stss` sample tables (hundreds of KB of heap allocations per file) even though `getMp4MediaInfo` only reads `vt.duration`, `vt.timescale`, `vt.width`, `vt.height`, `at.language`, and `stsd_raw`!
   - **Fix:** Add `parseMp4MetadataOnly(allocator, io, file)` (or a `parse_sample_tables: bool` flag in `isobmff/parser.zig`) that parses `mvhd` / `tkhd` / `mdhd` / `hdlr` / `stsd` and skips `stts`, `ctts`, `stsc`, `stsz`, `stco`, `co64`, and `stss` via `reader.skipBytes`.

---

### Step 4 — Optional Persistent Media Probe Cache in `SratimStorage`

To make even `GET /api/v1/movie`, `GET /api/v1/episode`, and `/player` `0 ms` on repeat views without touching disk:

1. **Extend `schema.Movie` and `schema.Episode` (`src/storage/schema.zig`)** with optional cached probe fields (defaulting to `0` / `null` so existing `snapshot.json` files load seamlessly via `.ignore_unknown_fields = true`):
   ```zig
   // On both schema.Movie and schema.Episode:
   duration_sec: f64 = 0.0,
   audio_tracks_json: ?[]const u8 = null,
   ```
   *(Remember to copy/free `audio_tracks_json` in `clone()` and `deinit()`).*

2. **Invalidate on File Change (`src/storage/media/shows.zig` & `movies.zig`)**:
   - In `addOrUpdateEpisode` / `addOrUpdateMovie`, if `existing.file_size != new.file_size`, reset `existing.duration_sec = 0.0` and free/clear `existing.audio_tracks_json = null`.

3. **Write-Through Lazy Populate (`handleGetMovie` / `handleGetEpisode`)**:
   - If `ep.duration_sec > 0.0 and ep.audio_tracks_json != null`, serialize directly from memory with **zero** disk access!
   - Otherwise probe once via `getMediaInfoWithOptions`, respond immediately, and store `(duration_sec, audio_tracks_json)` back into `SratimStorage` (`cat.cacheEpisodeMediaInfo(ep.id, duration_sec, audio_tracks_json)`).
   - Once cached, `handleGetShow` can also emit `"runtime"` and `"audio_tracks"` directly from `ep.duration_sec` / `ep.audio_tracks_json` when non-null, and `"audio_tracks":null` when not yet probed!

---

## 3. Verification Checklist on the Zig Server

1. **Unit & Integration Tests:**
   ```bash
   zig build test
   ```
2. **Latency Benchmark Against *Grey's Anatomy*:**
   ```bash
   # 1. Show details (430+ episodes): expect < 5 ms (down from 15-90s)
   curl -s -o /dev/null -w "GET /api/v1/show: %{time_total}s (HTTP %{http_code})\n" \
     -H "Cookie: session=$TOKEN" \
     "http://127.0.0.1:8000/api/v1/show?id=$GREYS_SHOW_ID"

   # 2. Single episode metadata on Play: expect < 25 ms cold, < 2 ms warm
   curl -s -o /dev/null -w "GET /api/v1/episode: %{time_total}s (HTTP %{http_code})\n" \
     -H "Cookie: session=$TOKEN" \
     "http://127.0.0.1:8000/api/v1/episode?id=$EPISODE_ID"
   ```
3. **End-to-End TV & Mobile Verification:**
   - Open *Grey's Anatomy* on Android TV -> verify instant season tabs (`Season 1` .. `Season 20+`) and episode list rendering.
   - Start playback of any episode -> verify resume position, seek bar scrubbing, and audio track switching.
   - Start playback of *Grey's Anatomy* directly from the Home screen *Recently Watched* row -> verify playback begins immediately without fetching the full show episode catalog.

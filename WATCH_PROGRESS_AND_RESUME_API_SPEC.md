# Sratim Watch Tracking & Resume Playback Specification

This document details how watch tracking events and resume playback work across the Sratim platform (Desktop Web, Mobile, and Android TV), along with the exact changes needed in the Zig backend (`sratim`) to expose resume positions in the REST API.

---

## 1. Overview & Parity with Desktop

In the desktop web version ([`src/web/templates/player.js`](file:///Users/borisk/Programming/Zig/sratim/src/web/templates/player.js)):
- **Playback Start**: When playback starts or unpauses, a `"start"` signal is dispatched to `/api/watch/event`.
- **Periodic Progress**: While playing, every 10 seconds of playback time, a `"progress"` signal is sent to update the stored position.
- **Seeking**: Whenever the user seeks on the seekbar or via arrow keys, a `"seek"` signal is sent with the new target time.
- **Pausing & Stopping**: On pause, a `"pause"` signal is sent. When exiting the player (Back button, tab close, window navigation), a `"stop"` signal is sent with the final position.
- **Resuming**: When opening a movie or episode without an explicit `start=0` query parameter, the server retrieves `logging_mod.getPlaybackProgress()` or `logging_mod.getEpisodePlaybackProgress()`. If the user is not near the end (not within the last 5% or 10 seconds), playback starts directly at the saved `resume_position`.

The Android client (`app-tv` and `app-mobile`) now implements this exact lifecycle and signal sequence.

---

## 2. Watch Event API (`POST /api/watch/event`)

This endpoint is already implemented and operational in `sratim` (`src/web/handlers/watch.zig`).

### Endpoint
`POST /api/watch/event`

### Headers
`Cookie: session=<session_token>`  
`Content-Type: application/json`

### Payload Format (Movie)
```json
{
  "movie_id": 42,
  "event": "progress",
  "position": 1250.5,
  "duration": 7200.0
}
```

### Payload Format (Episode)
```json
{
  "episode_id": 233827,
  "event": "progress",
  "position": 420.0,
  "duration": 1500.0
}
```

### Event Types
| Event | Trigger | Description |
|---|---|---|
| `"start"` | Video starts playing | Media begins playing or resumes from pause |
| `"progress"` | Playback loop | Dispatched every 10 seconds during active playback |
| `"seek"` | User seeks | Dispatched immediately when user scrubs or skips to a new position |
| `"pause"` | Playback paused | User pressed pause |
| `"stop"` | Playback ended or exited | User navigated back, finished media, or closed screen |

---

## 3. Recommended Zig Backend Update: Expose `resume_position`

Currently, `GET /api/v1/movie?id={id}` and `GET /api/v1/show?id={id}` do not include the user's saved playback position. The Android app currently falls back to `GET /api/v1/recently-watched`, but updating `media.zig` provides immediate, universal resume support for any item (even if not in the top 20 recently watched).

### Step 1: Update `src/web/routers/api_v1.zig`

Pass `session_info` to `media.handleGetMovie` and `media.handleGetShow`:

```diff
--- a/src/web/routers/api_v1.zig
+++ b/src/web/routers/api_v1.zig
@@ -52,9 +52,9 @@ pub fn route(
     } else if (std.mem.startsWith(u8, target, "/api/v1/library?")) {
         try library.handleGetLibraryItems(request, allocator, database);
         return true;
     } else if (std.mem.startsWith(u8, target, "/api/v1/movie?")) {
-        try media.handleGetMovie(request, allocator, database, config, io);
+        try media.handleGetMovie(request, allocator, database, config, io, session_info);
         return true;
     } else if (std.mem.startsWith(u8, target, "/api/v1/show?")) {
-        try media.handleGetShow(request, allocator, database, config, io);
+        try media.handleGetShow(request, allocator, database, config, io, session_info);
         return true;
     } else if (std.mem.eql(u8, target, "/api/v1/recently-watched") or std.mem.startsWith(u8, target, "/api/v1/recently-watched?")) {
```

---

### Step 2: Update `src/web/handlers/api_v1/media.zig`

#### A. Movie Details (`handleGetMovie`):
Accept `session_info: session_mod.SessionInfo`, query `logging_mod.getPlaybackProgress()`, and add `"resume_position"` to the response:

```diff
--- a/src/web/handlers/api_v1/media.zig
+++ b/src/web/handlers/api_v1/media.zig
@@ -14,6 +14,7 @@ pub fn handleGetMovie(
     allocator: std.mem.Allocator,
     database: *db_mod.Database,
     config: *const config_mod.Config,
     io: std.Io,
+    session_info: session_mod.SessionInfo,
 ) !void {
     if (request.head.method != .GET) {
@@ -73,6 +74,8 @@ pub fn handleGetMovie(
     const display_title = if (title_opt) |t| t else clean_name;
+    const resume_pos = logging_mod.getPlaybackProgress(database, session_info.username, movie_id) catch 0.0;
 
     var escaped_title = std.ArrayList(u8).empty;
@@ -95,7 +98,7 @@ pub fn handleGetMovie(
     defer escaped_overview.deinit(allocator);
 
-    const json = try std.fmt.allocPrint(allocator, "{{\"success\":true,\"movie\":{{\"id\":{d},\"library_id\":{d},\"title\":\"{s}\",\"overview\":\"{s}\",\"poster_path\":\"{s}\",\"backdrop_path\":\"{s}\",\"release_date\":\"{s}\",\"tmdb_id\":\"{s}\",\"file_size\":{d},\"file_path\":\"{s}\",\"runtime\":{d}}}}}", .{ id, library_id, escaped_title.items, escaped_overview.items, poster_path, backdrop_path, release_date, tmdb_id, file_size, file_path, runtime });
+    const json = try std.fmt.allocPrint(allocator, "{{\"success\":true,\"movie\":{{\"id\":{d},\"library_id\":{d},\"title\":\"{s}\",\"overview\":\"{s}\",\"poster_path\":\"{s}\",\"backdrop_path\":\"{s}\",\"release_date\":\"{s}\",\"tmdb_id\":\"{s}\",\"file_size\":{d},\"file_path\":\"{s}\",\"runtime\":{d},\"resume_position\":{d:.1}}}}}", .{ id, library_id, escaped_title.items, escaped_overview.items, poster_path, backdrop_path, release_date, tmdb_id, file_size, file_path, runtime, resume_pos });
     defer allocator.free(json);
```

#### B. Show Episodes (`handleGetShow`):
Accept `session_info: session_mod.SessionInfo`, query `logging_mod.getEpisodePlaybackProgress()` for each episode, and add `"resume_position"` to each episode JSON:

```diff
--- a/src/web/handlers/api_v1/media.zig
+++ b/src/web/handlers/api_v1/media.zig
@@ -107,6 +107,7 @@ pub fn handleGetShow(
     allocator: std.mem.Allocator,
     database: *db_mod.Database,
     config: *const config_mod.Config,
     io: std.Io,
+    session_info: session_mod.SessionInfo,
 ) !void {
@@ -275,7 +276,8 @@ pub fn handleGetShow(
         var esc_ep_overview = std.ArrayList(u8).empty;
         defer esc_ep_overview.deinit(allocator);
+        const ep_resume_pos = logging_mod.getEpisodePlaybackProgress(database, session_info.username, ep_id) catch 0.0;
 
-        const ep_json = try std.fmt.allocPrint(allocator, "{{\"id\":{d},\"season\":{d},\"episode\":{d},\"title\":\"{s}\",\"overview\":\"{s}\",\"still_path\":\"{s}\",\"file_size\":{d},\"runtime\":{d}}}", .{ ep_id, season, episode, esc_ep_title.items, esc_ep_overview.items, still_path, ep_file_size, ep_runtime });
+        const ep_json = try std.fmt.allocPrint(allocator, "{{\"id\":{d},\"season\":{d},\"episode\":{d},\"title\":\"{s}\",\"overview\":\"{s}\",\"still_path\":\"{s}\",\"file_size\":{d},\"runtime\":{d},\"resume_position\":{d:.1}}}", .{ ep_id, season, episode, esc_ep_title.items, esc_ep_overview.items, still_path, ep_file_size, ep_runtime, ep_resume_pos });
         defer allocator.free(ep_json);
         try json.appendSlice(allocator, ep_json);
```

---

## 4. Client Behavior & Verification

### In the Android App:
1. When playback prepares in `PlaybackViewModel`, it checks `resume_position` from the movie/episode details. If not yet present in the server's response, it automatically queries `GET /api/v1/recently-watched` as a fallback.
2. If `resume_position` is within the last 5% or 10 seconds of the total runtime (or less than 5 seconds), it is treated as completed (resets to 0.0).
3. `PlaybackScreen` seeks ExoPlayer to `resumePositionMs` before preparing.
4. `ResolvingDataSource` translates the seek into `?start=<seconds>` query parameter to the streaming engine `/stream?id=...&start=<seconds>`.
5. Playback starts immediately from the exact second without manual scrubbing.
6. The player continuously logs `"start"`, `"progress"` (every 10s), `"seek"`, `"pause"`, and `"stop"` (with `NonCancellable` execution on exit) so progress is permanently stored in the server database WAL and logs storage.

# Sratim Audio Track Selection API Specification

This document describes how audio track discovery and switching work across the Sratim platform (Desktop Web, Mobile, and Android TV), and details the exact Zig backend changes needed in `sratim` to expose `audio_tracks` directly in the v1 JSON API.

---

## 1. Architecture & Why an API Update Is Needed

### How `/stream` Multiplexes Audio (`src/web/handlers/player/stream.zig`)
- Sratim's `/stream` endpoint dynamically remuxes or transcodes **a single selected audio track** at a time into a live fragmented MP4 (`fMP4`) stream:
  - `GET /stream?id={movie_id}&start={seconds}&audio={track_id}`
  - `GET /stream?episode_id={episode_id}&start={seconds}&audio={track_id}`
- When `audio` is omitted (or `-1`), the server selects the first available audio track (`audio_tracks[0]`).
- Because only **one** audio stream is muxed into the `fMP4` container on any given HTTP response, ExoPlayer (on Android/TV) and MSE (on Web) cannot discover alternative audio streams from the `moov` box alone — the client must receive the `audio_tracks` metadata list from the server and reload `/stream` with `&audio={track_id}&start={current_time}` when switching tracks.

### Current Gap in `/api/v1/` (`src/web/handlers/api_v1/media.zig`)
- The HTML web player (`src/web/handlers/player/html.zig`) calls `streamer.getMediaInfo(...)` and embeds `const AUDIO_TRACKS = [{"id":1,"label":"...","codec":"..."}];` into `player.js`.
- Both `handleGetMovie` (`GET /api/v1/movie?id={id}`) and `handleGetShow` (`GET /api/v1/show?id={id}`) in `src/web/handlers/api_v1/media.zig` **already** call `streamer.getMediaInfo(...)` to calculate `runtime`, **but currently discard `media_info.audio_tracks`**.
- Exposing `audio_tracks` in `handleGetMovie` and `handleGetShow` requires **zero extra disk I/O or probing**, since `media_info` is already parsed in memory during those requests.

> **Backward Compatibility Note:** Until the Zig backend update below is deployed, the Android client (`PlaybackViewModel`) automatically falls back to parsing `AUDIO_TRACKS` from `GET /player` whenever `audio_tracks` is absent (`null`) in the `/api/v1/movie` or `/api/v1/show` response. Once `media.zig` is updated, the fallback is bypassed and zero extra HTTP requests are made.

---

## 2. JSON Schema Specification

### Audio Track Object
Each entry in `audio_tracks` matches the object format already used in `html.zig`:

```json
{
  "id": 1,
  "label": "Russian (Dubbed)",
  "codec": "AC-3"
}
```

| Field | Type | Description |
|---|---|---|
| `id` | `integer` | Stream/track identifier passed to `/stream?...&audio={id}` |
| `label` | `string` | Human-readable track label/language (e.g., `"English"`, `"Russian"`, `"Track 1"`) |
| `codec` | `string` | Source audio codec name (e.g., `"AAC"`, `"AC-3"`, `"E-AC-3"`, `"DTS"`, `"MP3"`) |

---

### Updated `GET /api/v1/movie?id={id}` Response
```json
{
  "success": true,
  "movie": {
    "id": 42,
    "library_id": 1,
    "title": "Inception",
    "overview": "...",
    "poster_path": "/...",
    "backdrop_path": "/...",
    "release_date": "2010-07-15",
    "tmdb_id": "27205",
    "file_size": 8453920124,
    "file_path": "Inception (2010)/Inception.mkv",
    "runtime": 148,
    "resume_position": 754.2,
    "audio_tracks": [
      { "id": 1, "label": "Russian", "codec": "AC-3" },
      { "id": 2, "label": "English", "codec": "DTS" }
    ]
  }
}
```

---

### Updated `GET /api/v1/show?id={id}` Response
Each episode object in `show.episodes` gains `audio_tracks`:
```json
{
  "success": true,
  "show": {
    "id": 10,
    "library_id": 2,
    "title": "Severance",
    "overview": "...",
    "poster_path": "/...",
    "backdrop_path": "/...",
    "tmdb_id": "95396",
    "episodes": [
      {
        "id": 233827,
        "season": 1,
        "episode": 1,
        "title": "Good News About Hell",
        "overview": "...",
        "still_path": "/...",
        "file_size": 2147483648,
        "runtime": 57,
        "resume_position": 420.0,
        "audio_tracks": [
          { "id": 1, "label": "Russian", "codec": "E-AC-3" },
          { "id": 2, "label": "English", "codec": "E-AC-3" }
        ]
      }
    ]
  }
}
```

---

## 3. Zig Backend Implementation (`src/web/handlers/api_v1/media.zig`)

No router changes are needed (`api_v1.zig` and `stream.zig` already support everything required). Only `src/web/handlers/api_v1/media.zig` needs to serialize `media_info.audio_tracks`.

### Helper Function to Add in `src/web/handlers/api_v1/media.zig`

```zig
fn appendAudioTracksJson(
    allocator: std.mem.Allocator,
    out: *std.ArrayList(u8),
    tracks: []const streamer.AudioTrack,
) !void {
    try out.appendSlice(allocator, "[");
    for (tracks, 0..) |track, i| {
        if (i > 0) try out.appendSlice(allocator, ",");
        const escaped_label = try utils.escapeJsonAlloc(allocator, track.label);
        defer allocator.free(escaped_label);
        const escaped_codec = try utils.escapeJsonAlloc(allocator, track.codec);
        defer allocator.free(escaped_codec);

        const item = try std.fmt.allocPrint(
            allocator,
            "{{\"id\":{d},\"label\":\"{s}\",\"codec\":\"{s}\"}}",
            .{ track.id, escaped_label, escaped_codec },
        );
        defer allocator.free(item);
        try out.appendSlice(allocator, item);
    }
    try out.appendSlice(allocator, "]");
}
```

### Diff for `handleGetMovie` and `handleGetShow` in `src/web/handlers/api_v1/media.zig`

```diff
--- a/src/web/handlers/api_v1/media.zig
+++ b/src/web/handlers/api_v1/media.zig
@@ -54,6 +54,9 @@ pub fn handleGetMovie(
     var runtime: u32 = 0;
     var file_size: u64 = 0;
+    var audio_tracks_json = std.ArrayList(u8).empty;
+    defer audio_tracks_json.deinit(allocator);
 
     if (common.resolveMediaPath(database, allocator, .{ .library_id = library_id, .file_path = file_path }) catch null) |resolved| {
         defer allocator.free(resolved.resolved_path);
@@ -71,9 +74,14 @@ pub fn handleGetMovie(
                 if (media_info.duration > 0) {
                     runtime = @as(u32, @intFromFloat(media_info.duration / 60.0));
                 }
+                try appendAudioTracksJson(allocator, &audio_tracks_json, media_info.audio_tracks);
             }
         }
     }
+    if (audio_tracks_json.items.len == 0) {
+        try audio_tracks_json.appendSlice(allocator, "[]");
+    }
 
     const display_title = if (title_opt) |t| t else clean_name;
     const resume_pos = logging_mod.getPlaybackProgress(database, session_info.username, movie_id) catch 0.0;
@@ -102,7 +110,7 @@ pub fn handleGetMovie(
         }
     }
 
-    const json = try std.fmt.allocPrint(allocator, "{{\"success\":true,\"movie\":{{\"id\":{d},\"library_id\":{d},\"title\":\"{s}\",\"overview\":\"{s}\",\"poster_path\":\"{s}\",\"backdrop_path\":\"{s}\",\"release_date\":\"{s}\",\"tmdb_id\":\"{s}\",\"file_size\":{d},\"file_path\":\"{s}\",\"runtime\":{d},\"resume_position\":{d:.1}}}}}", .{ id, library_id, escaped_title.items, escaped_overview.items, poster_path, backdrop_path, release_date, tmdb_id, file_size, file_path, runtime, resume_pos });
+    const json = try std.fmt.allocPrint(allocator, "{{\"success\":true,\"movie\":{{\"id\":{d},\"library_id\":{d},\"title\":\"{s}\",\"overview\":\"{s}\",\"poster_path\":\"{s}\",\"backdrop_path\":\"{s}\",\"release_date\":\"{s}\",\"tmdb_id\":\"{s}\",\"file_size\":{d},\"file_path\":\"{s}\",\"runtime\":{d},\"resume_position\":{d:.1},\"audio_tracks\":{s}}}}}", .{ id, library_id, escaped_title.items, escaped_overview.items, poster_path, backdrop_path, release_date, tmdb_id, file_size, file_path, runtime, resume_pos, audio_tracks_json.items });
     defer allocator.free(json);
 
     try request.respond(json, .{
@@ -197,6 +205,8 @@ pub fn handleGetShow(
         var ep_file_size: u64 = @intCast(@max(0, ep_db_file_size));
         var ep_runtime: u32 = 0;
+        var ep_audio_tracks_json = std.ArrayList(u8).empty;
+        defer ep_audio_tracks_json.deinit(allocator);
 
         if (common.resolveMediaPath(database, allocator, .{ .library_id = library_id, .file_path = file_path }) catch null) |resolved| {
             defer allocator.free(resolved.resolved_path);
@@ -215,9 +225,14 @@ pub fn handleGetShow(
                     if (media_info.duration > 0) {
                         ep_runtime = @as(u32, @intFromFloat(media_info.duration / 60.0));
                     }
+                    try appendAudioTracksJson(allocator, &ep_audio_tracks_json, media_info.audio_tracks);
                 }
             }
         }
+        if (ep_audio_tracks_json.items.len == 0) {
+            try ep_audio_tracks_json.appendSlice(allocator, "[]");
+        }
 
         const basename = std.fs.path.basename(file_path);
         const display_title = if (ep_title_opt) |t| t else basename;
@@ -249,7 +264,7 @@ pub fn handleGetShow(
 
         const ep_resume_pos = logging_mod.getEpisodePlaybackProgress(database, session_info.username, ep_id) catch 0.0;
 
-        const ep_json = try std.fmt.allocPrint(allocator, "{{\"id\":{d},\"season\":{d},\"episode\":{d},\"title\":\"{s}\",\"overview\":\"{s}\",\"still_path\":\"{s}\",\"file_size\":{d},\"runtime\":{d},\"resume_position\":{d:.1}}}", .{ ep_id, season, episode, esc_ep_title.items, esc_ep_overview.items, still_path, ep_file_size, ep_runtime, ep_resume_pos });
+        const ep_json = try std.fmt.allocPrint(allocator, "{{\"id\":{d},\"season\":{d},\"episode\":{d},\"title\":\"{s}\",\"overview\":\"{s}\",\"still_path\":\"{s}\",\"file_size\":{d},\"runtime\":{d},\"resume_position\":{d:.1},\"audio_tracks\":{s}}}", .{ ep_id, season, episode, esc_ep_title.items, esc_ep_overview.items, still_path, ep_file_size, ep_runtime, ep_resume_pos, ep_audio_tracks_json.items });
         defer allocator.free(ep_json);
         try json.appendSlice(allocator, ep_json);
     }
```

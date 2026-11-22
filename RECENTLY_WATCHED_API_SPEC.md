# Sratim Recently Watched API Specification & Implementation Guide

This specification defines the `GET /api/v1/recently-watched` endpoint to allow the Android client (and other API clients) to display a "Recently Watched" (or "Continue Watching") section with progress indicators.

---

## 1. API Specification

### Endpoint
`GET /api/v1/recently-watched`

### Headers Required
`Cookie: session=<session_token>`

### Query Parameters
| Parameter | Type    | Required | Default | Description |
|-----------|---------|----------|---------|-------------|
| `limit`   | Integer | Optional | `20`    | Maximum number of recently watched items to return |

---

### Success Response (200 OK)
**Content-Type:** `application/json`

```json
{
  "success": true,
  "items": [
    {
      "id": 42,
      "type": "movie",
      "title": "Inception",
      "poster_path": "/9gk7adHYeDvHkCSEqAvQQsV5ZXl.jpg",
      "backdrop_path": "/s3TBrRGB1invsyVmLjvcb4gJ0Cw.jpg",
      "position": 3420.5,
      "duration": 8880.0,
      "progress_percent": 38.5,
      "updated_at": 1700654400
    },
    {
      "id": 233827,
      "type": "episode",
      "show_id": 10,
      "show_title": "Greenhouse",
      "title": "Episode 1",
      "season": 1,
      "episode": 1,
      "poster_path": "/2NAdWQEmfZTqvDHC8YWrf0PffRy.jpg",
      "backdrop_path": "/backdrop_path.jpg",
      "position": 745.0,
      "duration": 1200.0,
      "progress_percent": 62.0,
      "updated_at": 1700651200
    }
  ]
}
```

### Field Definitions

| Field | Type | Description |
|---|---|---|
| `id` | Integer | Media item ID (`movie_id` for movies, `episode_id` for episodes) |
| `type` | String | `"movie"` or `"episode"` |
| `show_id` | Integer | (Episodes only) Parent show ID for navigation |
| `show_title` | String | (Episodes only) Parent show display name |
| `title` | String | Movie title or episode title |
| `season` | Integer | (Episodes only) Season number |
| `episode` | Integer | (Episodes only) Episode number |
| `poster_path` | String | Relative poster path (or still path for episode) |
| `backdrop_path` | String | Relative backdrop image path (optional) |
| `position` | Float | Last played position in seconds |
| `duration` | Float | Total media runtime in seconds |
| `progress_percent`| Float | Calculated percentage: `(position / duration) * 100.0` (range: 0 to 100) |
| `updated_at` | Integer | Unix epoch timestamp of the last watch event |

---

## 2. Playback Progress Reporting (`POST /api/watch/event`)

To keep "Recently Watched" up to date from Android, the client reports progress to the already existing `/api/watch/event` endpoint.

### Endpoint
`POST /api/watch/event`

### Headers Required
`Cookie: session=<session_token>`
`Content-Type: application/json`

### Request Body (Movie)
```json
{
  "movie_id": 42,
  "event": "timeupdate",
  "position": 1250.0,
  "duration": 7200.0
}
```

### Request Body (Episode)
```json
{
  "episode_id": 233827,
  "event": "timeupdate",
  "position": 420.0,
  "duration": 1500.0
}
```

*Supported `event` values: `"play"`, `"pause"`, `"timeupdate"`, `"stop"`.*

---

## 3. Implementation Guide for Zig Backend (`sratim`)

The database logic already exists in `src/db/logging.zig` via `logging_mod.getRecentlyWatched(database, allocator, username, limit)`! Here is how to wire it up:

### Step 1: Add handler in `src/web/handlers/api_v1/media.zig`

Add the following function to `media.zig`:

```zig
pub fn handleGetRecentlyWatched(
    request: *std.http.Server.Request,
    allocator: std.mem.Allocator,
    database: *db_mod.Database,
    session_info: session_mod.SessionInfo,
) !void {
    if (request.head.method != .GET) {
        try request.respond("{\"success\":false,\"error\":\"Method not allowed\"}", .{ .status = .method_not_allowed });
        return;
    }

    const limit = utils.parseQueryInt(usize, request.head.target, "limit") orelse 20;

    const cat = database.catalog orelse {
        try request.respond("{\"success\":false,\"error\":\"Catalog not configured\"}", .{ .status = .internal_server_error });
        return;
    };

    const logging_mod = @import("../../../db/logging.zig");
    const recent_items = logging_mod.getRecentlyWatched(database, allocator, session_info.username, limit) catch |err| {
        std.debug.print("Failed to get recently watched: {any}\n", .{err});
        try request.respond("{\"success\":false,\"error\":\"Failed to query recently watched\"}", .{ .status = .internal_server_error });
        return;
    };
    defer allocator.free(recent_items);

    var json_buf = std.ArrayList(u8).empty;
    defer json_buf.deinit(allocator);

    try json_buf.appendSlice(allocator, "{\"success\":true,\"items\":[");

    var first = true;
    for (recent_items) |item| {
        if (item.media_type == .movie) {
            const mov_opt = cat.getMovieById(allocator, item.item_id) catch null;
            if (mov_opt) |mov| {
                defer {
                    var mut = mov;
                    mut.deinit(allocator);
                }
                if (mov.is_present) {
                    if (!first) try json_buf.append(allocator, ',');
                    first = false;

                    const title = mov.title orelse mov.clean_name;
                    const poster = mov.poster_path orelse "";
                    const backdrop = mov.backdrop_path orelse "";
                    const progress_pct = if (item.duration > 0) (item.position / item.duration) * 100.0 else 0.0;

                    const escaped_title = try utils.escapeJsonString(allocator, title);
                    defer allocator.free(escaped_title);

                    const entry = try std.fmt.allocPrint(
                        allocator,
                        "{{\"id\":{d},\"type\":\"movie\",\"title\":\"{s}\",\"poster_path\":\"{s}\",\"backdrop_path\":\"{s}\",\"position\":{d:.1},\"duration\":{d:.1},\"progress_percent\":{d:.1},\"updated_at\":{d}}}",
                        .{ item.item_id, escaped_title, poster, backdrop, item.position, item.duration, progress_pct, item.updated_at }
                    );
                    defer allocator.free(entry);
                    try json_buf.appendSlice(allocator, entry);
                }
            }
        } else if (item.media_type == .episode) {
            const ep_opt = cat.getEpisodeById(allocator, item.item_id) catch null;
            if (ep_opt) |ep| {
                defer {
                    var mut = ep;
                    mut.deinit(allocator);
                }
                if (ep.is_present) {
                    const sh_opt = cat.getShowById(allocator, ep.show_id) catch null;
                    if (sh_opt) |sh| {
                        defer {
                            var mut_sh = sh;
                            mut_sh.deinit(allocator);
                        }
                        if (sh.is_present) {
                            if (!first) try json_buf.append(allocator, ',');
                            first = false;

                            const show_title = sh.title orelse sh.clean_name;
                            const ep_title = ep.title orelse "";
                            const poster = ep.still_path orelse ep.poster_path orelse sh.poster_path orelse "";
                            const backdrop = sh.backdrop_path orelse "";
                            const progress_pct = if (item.duration > 0) (item.position / item.duration) * 100.0 else 0.0;

                            const escaped_show = try utils.escapeJsonString(allocator, show_title);
                            defer allocator.free(escaped_show);

                            const escaped_ep = try utils.escapeJsonString(allocator, ep_title);
                            defer allocator.free(escaped_ep);

                            const entry = try std.fmt.allocPrint(
                                allocator,
                                "{{\"id\":{d},\"type\":\"episode\",\"show_id\":{d},\"show_title\":\"{s}\",\"title\":\"{s}\",\"season\":{d},\"episode\":{d},\"poster_path\":\"{s}\",\"backdrop_path\":\"{s}\",\"position\":{d:.1},\"duration\":{d:.1},\"progress_percent\":{d:.1},\"updated_at\":{d}}}",
                                .{ item.item_id, ep.show_id, escaped_show, escaped_ep, ep.season, ep.episode, poster, backdrop, item.position, item.duration, progress_pct, item.updated_at }
                            );
                            defer allocator.free(entry);
                            try json_buf.appendSlice(allocator, entry);
                        }
                    }
                }
            }
        }
    }

    try json_buf.appendSlice(allocator, "]}");

    try request.respond(json_buf.items, .{
        .status = .ok,
        .extra_headers = &.{
            .{ .name = "content-type", .value = "application/json" },
        },
    });
}
```

### Step 2: Route the endpoint in `src/web/routers/api_v1.zig`

In `api_v1.zig`:
```zig
pub const handleGetRecentlyWatched = media.handleGetRecentlyWatched;
```

And inside the `route()` function:
```zig
    } else if (std.mem.startsWith(u8, target, "/api/v1/recently-watched")) {
        try media.handleGetRecentlyWatched(request, allocator, database, session_info);
        return true;
    }
```

# Sratim API Specification (v1)

This document outlines the JSON API specification for the Android TV client to communicate with the Sratim backend.

## 1. Authentication (Login)

Use this endpoint to exchange a username and password for a session token.

**Endpoint:** `POST /api/v1/login`
**Content-Type:** `application/json`

### Request Body
```json
{
  "username": "your_username",
  "password": "your_password"
}
```

### Success Response (200 OK)
```json
{
  "success": true,
  "token": "your_session_token_string",
  "is_admin": true
}
```

### Error Responses
**400 Bad Request** (Malformed JSON)
**401 Unauthorized** (Invalid credentials)

---

## Important Note on API Authentication

Once you receive the `token` from the login response, you **must** pass it along with every subsequent API request (and video stream request) using the `Cookie` HTTP header. 

For example:
```http
Cookie: session=your_session_token_string
```

---

## 2. Get Libraries

Use this endpoint to retrieve all the libraries configured on the server (e.g., Movies, Shows).

**Endpoint:** `GET /api/v1/libraries`
**Headers Required:** `Cookie: session=<token>`

### Success Response (200 OK)
```json
{
  "success": true,
  "libraries": [
    {
      "id": 1,
      "name": "Action Movies",
      "type": "Movies"
    },
    {
      "id": 2,
      "name": "TV Shows",
      "type": "Shows"
    }
  ]
}
```

---

## 3. Get Library Items

Use this endpoint to retrieve the list of movies or shows within a specific library.

**Endpoint:** `GET /api/v1/library?id=<LIBRARY_ID>`
**Headers Required:** `Cookie: session=<token>`

### Success Response (200 OK)
```json
{
  "success": true,
  "items": [
    {
      "id": 42,
      "title": "Inception",
      "poster_path": "/9gk7adHYeDvHkCSEqAvQQsV5ZXl.jpg",
      "tmdb_id": "27205",
      "type": "movie"
    },
    {
      "id": 43,
      "title": "Breaking Bad",
      "poster_path": "/ggFHVNu6YYI5L9pCfOacjizRGt.jpg",
      "tmdb_id": "1396",
      "type": "show"
    }
  ]
}
```

*Note: The `type` field will either be `"movie"` or `"show"`. If the backend doesn't have a poster for an item, `poster_path` will be an empty string `""`.*

---

## 4. Get Movie Details

Use this endpoint to retrieve detailed information about a specific movie, including overview, release date, and file size.

**Endpoint:** `GET /api/v1/movie?id=<MOVIE_ID>`
**Headers Required:** `Cookie: session=<token>`

### Success Response (200 OK)
```json
{
  "success": true,
  "movie": {
    "id": 42,
    "library_id": 1,
    "title": "Inception",
    "overview": "Cobb, a skilled thief who commits corporate espionage by infiltrating the subconscious of his targets is offered a chance to regain his old life as payment for a task considered to be impossible: \"inception\", the implantation of another person's idea into a target's subconscious.",
    "poster_path": "/9gk7adHYeDvHkCSEqAvQQsV5ZXl.jpg",
    "backdrop_path": "/s3TBrRGB1invsyVmLjvcb4gJ0Cw.jpg",
    "release_date": "2010-07-15",
    "tmdb_id": "27205",
    "file_size": 2147483648,
    "file_path": "/movies/Inception.mkv"
  }
}
```

---

## 5. Loading Images (Posters & Backdrops)

The JSON responses return image paths (like `poster_path` or `backdrop_path`) as relative file names (e.g., `"/9gk7adHYeDvHkCSEqAvQQsV5ZXl.jpg"`). They are **not** absolute URLs.

To load the image in Android (using Coil, Glide, or Picasso), you must construct the full URL by appending the `poster_path` to your base Zig server URL and the image directory path.

**Base URL Format:**
`http://<SERVER_IP>:<SERVER_PORT>/images/posters/w185<POSTER_PATH>`
*(You can change `w185` to `original` or other valid TMDB sizes if the server caches them)*

**Example in Kotlin:**
```kotlin
val serverBaseUrl = "http://192.168.1.100:8080"
val posterPath = item.poster_path // e.g., "/9gk7adHY...jpg"

val fullImageUrl = "$serverBaseUrl/images/posters/w185$posterPath"

// Load fullImageUrl into your Image/AsyncImage component
```

*Note: No `Cookie` header is strictly required to fetch the static image assets, as they are served via a public static file handler in the Zig backend.*

## 6. Play Media

Use this endpoint to play a movie or TV show natively on Android TV (e.g., using ExoPlayer).
Unlike `/stream`, this endpoint supports standard HTTP byte-range requests (`206 Partial Content`) so the player can properly seek and buffer the raw media file.

**Endpoint:** `GET /api/v1/play?id=<MOVIE_ID>` (or `?episode_id=<EPISODE_ID>`)
**Headers Required:** None (Public endpoint)
**Supports:** `Range: bytes=X-Y` headers.

### Example in Kotlin (ExoPlayer)
```kotlin
val videoUrl = "http://192.168.1.100:8080/api/v1/play?id=42"
val mediaItem = MediaItem.fromUri(videoUrl)
player.setMediaItem(mediaItem)
player.prepare()
player.play()
```

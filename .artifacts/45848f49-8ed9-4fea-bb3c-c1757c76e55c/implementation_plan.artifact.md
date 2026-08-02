# Implementation Plan - TV Show Support for Android TV

Implement the Shows and Episodes display logic in the `app-tv` module, mirroring the functionality recently added to the mobile app but optimized for a television interface.

## User Review Required

> [!IMPORTANT]
> - I will add a new `ShowDetailScreen` to the TV app.
> - The TV navigation will be updated to handle `?episode_id=` for playback, just like the mobile app.
> - The `LibraryScreen` on TV will now correctly navigate to either Movie or Show details.

## Proposed Changes

### UI Layer (`:app-tv`)

#### [NEW] `ShowDetailScreen.kt` in `ui.details`
- Implement a TV-optimized layout using `androidx.tv.material3`.
- **Left/Hero Section**: Show title, overview, and high-res poster.
- **Season Selection**: Horizontal list of seasons (using `TabRow` or `LazyRow`).
- **Episode List**: Vertical or Grid list of episodes with stills and titles, focusable for D-pad navigation.

#### [MODIFY] [LibraryScreen.kt](file:///Users/borisk/AndroidStudioProjects/Sratim/app-tv/src/main/java/com/ru9n/sratim/ui/library/LibraryScreen.kt)
- Update `onMovieClick` to a more generic `onItemClick(id, type)`.
- Navigate to `show/{id}` for show types.

#### [MODIFY] [MainActivity.kt](file:///Users/borisk/AndroidStudioProjects/Sratim/app-tv/src/main/java/com/ru9n/sratim/MainActivity.kt)
- Add `show/{showId}` route using the shared `ShowDetailViewModel`.
- Update `playback` routes to support both `playback/movie/{id}` and `playback/episode/{id}` to match the mobile app's structure for consistency.

### Core Logic (`:core`)
- The `ShowDetailViewModel` and `PlaybackViewModel` are already shared and support the required logic.

## Verification Plan

### Automated Tests
- `gradlew :app-tv:assembleDebug`

### Manual Verification
1. Open the TV app.
2. Navigate to a "Shows" library.
3. Select a show.
4. Verify D-pad navigation between seasons and episodes.
5. Play an episode and verify it starts correctly in the TV player.

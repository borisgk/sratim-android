package com.ru9n.sratim.core.network

import com.google.gson.annotations.SerializedName
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface SratimApi {
    @POST("api/v1/login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    @GET("api/v1/libraries")
    suspend fun getLibraries(): Response<LibraryResponse>

    @GET("api/v1/library")
    suspend fun getLibraryItems(@Query("id") libraryId: Int): Response<LibraryItemsResponse>

    @GET("api/v1/movie")
    suspend fun getMovieDetails(@Query("id") movieId: Int): Response<MovieDetailResponse>

    @GET("api/v1/show")
    suspend fun getShowDetails(@Query("id") showId: Int): Response<ShowDetailResponse>

    @GET("api/v1/episode")
    suspend fun getEpisodeDetails(@Query("id") episodeId: Int): Response<EpisodeDetailResponse>

    @GET("api/v1/recently-watched")
    suspend fun getRecentlyWatched(): Response<RecentlyWatchedResponse>

    @POST("api/watch/event")
    suspend fun postWatchEvent(@Body event: WatchEventRequest): Response<Unit>

    @GET("player")
    suspend fun getPlayerHtml(
        @Query("id") movieId: Int? = null,
        @Query("episode_id") episodeId: Int? = null
    ): Response<ResponseBody>
}

data class LoginRequest(
    @SerializedName("username") val username: String,
    @SerializedName("password") val password: String
)

data class LoginResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("token") val token: String? = null,
    @SerializedName("error") val error: String? = null,
    @SerializedName("is_admin") val isAdmin: Boolean? = null
)

data class LibraryResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("libraries") val libraries: List<Library>? = null,
    @SerializedName("error") val error: String? = null
)

data class Library(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("name") val name: String = "",
    @SerializedName("type") val type: String = ""
)

data class LibraryItemsResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("items") val items: List<LibraryItem>? = null,
    @SerializedName("error") val error: String? = null
)

data class LibraryItem(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("title") val title: String = "",
    @SerializedName("poster_path") val posterPath: String = "",
    @SerializedName("tmdb_id") val tmdbId: String? = null,
    @SerializedName("type") val type: String = ""
)

data class MovieDetailResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("movie") val movie: MovieDetail? = null,
    @SerializedName("error") val error: String? = null
)

data class MovieDetail(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("library_id") val libraryId: Int = 0,
    @SerializedName("title") val title: String = "",
    @SerializedName("overview") val overview: String = "",
    @SerializedName("poster_path") val posterPath: String = "",
    @SerializedName("backdrop_path") val backdropPath: String = "",
    @SerializedName("release_date") val releaseDate: String = "",
    @SerializedName("tmdb_id") val tmdbId: String? = null,
    @SerializedName("file_size") val fileSize: Long? = null,
    @SerializedName("filesize") val filesize: Long? = null,
    @SerializedName("runtime") val runtime: Int? = null,
    @SerializedName("duration") val duration: Int? = null,
    @SerializedName("duration_sec") val durationSec: Double? = null,
    @SerializedName("file_path") val filePath: String = "",
    @SerializedName("resume_position") val resumePosition: Double? = null,
    @SerializedName("position") val position: Double? = null,
    @SerializedName("audio_tracks") val audioTracks: List<AudioTrackDto>? = null
)

data class ShowDetailResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("show") val show: ShowDetail? = null,
    @SerializedName("error") val error: String? = null
)

data class ShowDetail(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("library_id") val libraryId: Int = 0,
    @SerializedName("title") val title: String = "",
    @SerializedName("overview") val overview: String = "",
    @SerializedName("poster_path") val posterPath: String = "",
    @SerializedName("backdrop_path") val backdropPath: String = "",
    @SerializedName("tmdb_id") val tmdbId: String? = null,
    @SerializedName("episodes") val episodes: List<Episode>? = null
)

data class EpisodeDetailResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("episode") val episode: Episode? = null,
    @SerializedName("error") val error: String? = null
)

data class Episode(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("show_id") val showId: Int? = null,
    @SerializedName("season") val season: Int = 0,
    @SerializedName("episode") val episode: Int = 0,
    @SerializedName("title") val title: String = "",
    @SerializedName("overview") val overview: String = "",
    @SerializedName("still_path") val stillPath: String? = null,
    @SerializedName("poster_path") val posterPath: String? = null,
    @SerializedName("file_size") val fileSize: Long? = null,
    @SerializedName("filesize") val filesize: Long? = null,
    @SerializedName("runtime") val runtime: Int? = null,
    @SerializedName("duration") val duration: Int? = null,
    @SerializedName("duration_sec") val durationSec: Double? = null,
    @SerializedName("resume_position") val resumePosition: Double? = null,
    @SerializedName("position") val position: Double? = null,
    @SerializedName("audio_tracks") val audioTracks: List<AudioTrackDto>? = null
)

data class AudioTrackDto(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("label") val label: String? = null,
    @SerializedName("codec") val codec: String? = null,
    @SerializedName("language") val language: String? = null
)

data class RecentlyWatchedResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("items") val items: List<RecentlyWatchedItem>? = null,
    @SerializedName("error") val error: String? = null
)

data class RecentlyWatchedItem(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("type") val type: String = "",
    @SerializedName("show_id") val showId: Int? = null,
    @SerializedName("show_title") val showTitle: String? = null,
    @SerializedName("title") val title: String = "",
    @SerializedName("season") val season: Int? = null,
    @SerializedName("episode") val episode: Int? = null,
    @SerializedName("poster_path") val posterPath: String? = null,
    @SerializedName("show_poster_path") val showPosterPath: String? = null,
    @SerializedName("backdrop_path") val backdropPath: String? = null,
    @SerializedName("position") val position: Double? = null,
    @SerializedName("duration") val duration: Double? = null,
    @SerializedName("progress_percent") val progressPercent: Double? = null,
    @SerializedName("updated_at") val updatedAt: Long? = null
)

data class WatchEventRequest(
    @SerializedName("movie_id") val movieId: Int? = null,
    @SerializedName("episode_id") val episodeId: Int? = null,
    @SerializedName("event") val event: String,
    @SerializedName("position") val position: Double,
    @SerializedName("duration") val duration: Double
)

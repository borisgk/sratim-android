package com.ru9n.sratim.core.network

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface SratimApi {
    @POST("api/v1/login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    @GET("api/v1/libraries")
    suspend fun getLibraries(): Response<LibraryResponse>

    @GET("api/v1/library")
    suspend fun getLibraryItems(@retrofit2.http.Query("id") libraryId: Int): Response<LibraryItemsResponse>

    @GET("api/v1/movie")
    suspend fun getMovieDetails(@retrofit2.http.Query("id") movieId: Int): Response<MovieDetailResponse>

    @GET("api/v1/show")
    suspend fun getShowDetails(@retrofit2.http.Query("id") showId: Int): Response<ShowDetailResponse>

    @GET("api/v1/recently-watched")
    suspend fun getRecentlyWatched(): Response<RecentlyWatchedResponse>

    @POST("api/watch/event")
    suspend fun postWatchEvent(@Body event: WatchEventRequest): Response<Unit>
}

data class LoginRequest(
    val username: String,
    val password: String
)

data class LoginResponse(
    val success: Boolean,
    val token: String?,
    val error: String?,
    @SerializedName("is_admin") val isAdmin: Boolean?
)

data class LibraryResponse(
    val success: Boolean,
    val libraries: List<Library>?,
    val error: String?
)

data class Library(
    val id: Int,
    val name: String,
    val type: String
)

data class LibraryItemsResponse(
    val success: Boolean,
    val items: List<LibraryItem>?,
    val error: String?
)

data class LibraryItem(
    val id: Int,
    val title: String,
    @SerializedName("poster_path") val posterPath: String,
    @SerializedName("tmdb_id") val tmdbId: String?,
    val type: String
)

data class MovieDetailResponse(
    val success: Boolean,
    val movie: MovieDetail?,
    val error: String?
)

data class MovieDetail(
    val id: Int,
    @SerializedName("library_id") val libraryId: Int,
    val title: String,
    val overview: String,
    @SerializedName("poster_path") val posterPath: String,
    @SerializedName("backdrop_path") val backdropPath: String,
    @SerializedName("release_date") val releaseDate: String,
    @SerializedName("tmdb_id") val tmdbId: String?,
    @SerializedName("file_size") val fileSize: Long?,
    @SerializedName("filesize") val filesize: Long?,
    val runtime: Int?,
    val duration: Int?,
    @SerializedName("file_path") val filePath: String,
    @SerializedName("resume_position") val resumePosition: Double? = null,
    @SerializedName("position") val position: Double? = null
)

data class ShowDetailResponse(
    val success: Boolean,
    val show: ShowDetail?,
    val error: String?
)

data class ShowDetail(
    val id: Int,
    @SerializedName("library_id") val libraryId: Int,
    val title: String,
    val overview: String,
    @SerializedName("poster_path") val posterPath: String,
    @SerializedName("backdrop_path") val backdropPath: String,
    @SerializedName("tmdb_id") val tmdbId: String?,
    val episodes: List<Episode>?
)

data class Episode(
    val id: Int,
    val season: Int,
    val episode: Int,
    val title: String,
    val overview: String,
    @SerializedName("still_path") val stillPath: String?,
    @SerializedName("poster_path") val posterPath: String?,
    @SerializedName("file_size") val fileSize: Long?,
    @SerializedName("filesize") val filesize: Long?,
    val runtime: Int?,
    val duration: Int?,
    @SerializedName("resume_position") val resumePosition: Double? = null,
    @SerializedName("position") val position: Double? = null
)

data class RecentlyWatchedResponse(
    val success: Boolean,
    val items: List<RecentlyWatchedItem>?,
    val error: String?
)

data class RecentlyWatchedItem(
    val id: Int,
    val type: String,
    @SerializedName("show_id") val showId: Int? = null,
    @SerializedName("show_title") val showTitle: String? = null,
    val title: String,
    val season: Int? = null,
    val episode: Int? = null,
    @SerializedName("poster_path") val posterPath: String? = null,
    @SerializedName("show_poster_path") val showPosterPath: String? = null,
    @SerializedName("backdrop_path") val backdropPath: String? = null,
    val position: Double? = null,
    val duration: Double? = null,
    @SerializedName("progress_percent") val progressPercent: Double? = null,
    @SerializedName("updated_at") val updatedAt: Long? = null
)

data class WatchEventRequest(
    @SerializedName("movie_id") val movieId: Int? = null,
    @SerializedName("episode_id") val episodeId: Int? = null,
    val event: String,
    val position: Double,
    val duration: Double
)


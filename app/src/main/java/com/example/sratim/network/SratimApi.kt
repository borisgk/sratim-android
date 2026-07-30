package com.example.sratim.network

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
    @SerializedName("file_size") val fileSize: Long,
    @SerializedName("file_path") val filePath: String
)

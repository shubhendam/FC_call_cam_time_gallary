package com.example.jetsonapp.utils

import com.example.jetsonapp.data.TimeResponse
import com.example.jetsonapp.data.WeatherResponse
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface WeatherApiService {
    @GET("weather")
    suspend fun getCurrentWeather(
        @Query("q") cityName: String,
        @Query("appid") apiKey: String,
        @Query("units") units: String = "metric"
    ): Response<WeatherResponse>
}

interface TimeApiService {
    @GET("timezone/Etc/UTC")
    suspend fun getCurrentTime(): Response<TimeResponse>

    @GET("timezone/{timezone}")
    suspend fun getTimeForTimezone(
        @Query("timezone") timezone: String
    ): Response<TimeResponse>
}
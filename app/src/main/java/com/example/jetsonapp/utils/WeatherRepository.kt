package com.example.jetsonapp.utils

import android.util.Log
import com.example.jetsonapp.data.TimeResponse
import com.example.jetsonapp.data.WeatherResponse
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WeatherRepository @Inject constructor() {

    // Replace with your OpenWeatherMap API key
    private val weatherApiKey = "your_openweather_api_key_here"

    private val weatherApi: WeatherApiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.openweathermap.org/data/2.5/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(WeatherApiService::class.java)
    }

    private val timeApi: TimeApiService by lazy {
        Retrofit.Builder()
            .baseUrl("http://worldtimeapi.org/api/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TimeApiService::class.java)
    }

    suspend fun getWeather(cityName: String): Result<String> {
        return try {
            val response = weatherApi.getCurrentWeather(cityName, weatherApiKey)
            if (response.isSuccessful) {
                val weather = response.body()
                if (weather != null) {
                    val message = formatWeatherMessage(weather)
                    Result.success(message)
                } else {
                    Result.failure(Exception("Weather data not available"))
                }
            } else {
                Result.failure(Exception("Failed to get weather: ${response.message()}"))
            }
        } catch (e: Exception) {
            Log.e("WeatherRepository", "Error getting weather", e)
            Result.failure(e)
        }
    }

    suspend fun getCurrentTime(): Result<String> {
        return try {
            val currentTime = System.currentTimeMillis()
            val dateFormat = java.text.SimpleDateFormat("HH:mm:ss 'on' EEEE, MMMM dd, yyyy", java.util.Locale.getDefault())
            val formattedTime = dateFormat.format(java.util.Date(currentTime))
            val timeZone = java.util.TimeZone.getDefault().displayName

            val message = "The current time is $formattedTime. Your timezone is $timeZone."
            Result.success(message)
        } catch (e: Exception) {
            Log.e("WeatherRepository", "Error getting local time", e)
            Result.failure(e)
        }
    }

    private fun formatWeatherMessage(weather: WeatherResponse): String {
        val temp = weather.main.temperature.toInt()
        val description = weather.weather.firstOrNull()?.description ?: "Unknown"
        val city = weather.cityName
        val country = weather.sys.country
        val humidity = weather.main.humidity
        val windSpeed = weather.wind?.speed?.toInt() ?: 0

        return "The weather in $city, $country is $description with a temperature of $temp degrees Celsius. " +
                "Humidity is $humidity percent and wind speed is $windSpeed meters per second."
    }

    private fun formatTimeMessage(time: TimeResponse): String {
        // Parse the datetime string (format: 2024-01-01T12:00:00.000000+00:00)
        val datetime = time.datetime
        val parts = datetime.split("T")
        val datePart = parts[0]
        val timePart = parts[1].split(".")[0] // Remove milliseconds

        return "The current UTC time is $timePart on $datePart. Timezone is ${time.timezone}."
    }
}
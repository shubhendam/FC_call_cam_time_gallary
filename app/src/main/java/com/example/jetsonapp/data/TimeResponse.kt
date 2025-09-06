package com.example.jetsonapp.data

import com.google.gson.annotations.SerializedName

data class TimeResponse(
    @SerializedName("datetime")
    val datetime: String,

    @SerializedName("timezone")
    val timezone: String,

    @SerializedName("day_of_week")
    val dayOfWeek: Int,

    @SerializedName("day_of_year")
    val dayOfYear: Int,

    @SerializedName("week_number")
    val weekNumber: Int,

    @SerializedName("utc_datetime")
    val utcDatetime: String,

    @SerializedName("utc_offset")
    val utcOffset: String,

    @SerializedName("abbreviation")
    val abbreviation: String
)
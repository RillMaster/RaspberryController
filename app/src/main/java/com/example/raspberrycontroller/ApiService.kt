package com.example.raspberrycontroller

import retrofit2.http.*

data class LedRequest(val state: Boolean)
data class LedResponse(val led: Boolean)
data class TempResponse(val temperature: Float)
data class CommandRequest(val command: String)
data class CommandResponse(val output: String, val error: String)

interface ApiService {
    @GET("temperature")
    suspend fun getTemperature(): TempResponse

    @POST("gpio/led")
    suspend fun controlLed(@Body body: LedRequest): LedResponse

    @POST("ssh/command")
    suspend fun runCommand(@Body body: CommandRequest): CommandResponse
}
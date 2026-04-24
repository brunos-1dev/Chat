package com.example.shadowrec

import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.POST

interface AuthService {

    @POST("login")
    fun login(@Body request: LoginRequest): Call<LoginResponse>

    @POST("register")
    fun register(@Body request: RegisterRequest): Call<LoginResponse>

    @retrofit2.http.GET("conversations")
    fun getConversations(
        @retrofit2.http.Header("Authorization") token: String
    ): Call<ConversationsResponse>
}
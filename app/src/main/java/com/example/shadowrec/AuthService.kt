package com.example.shadowrec

import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

interface AuthService {

    @POST("login")
    fun login(@Body request: LoginRequest): Call<LoginResponse>

    @POST("register")
    fun register(@Body request: RegisterRequest): Call<LoginResponse>

    @GET("conversations")
    fun getConversations(
        @Header("Authorization") token: String
    ): Call<ConversationsResponse>

    @GET("users")
    fun getUsers(
        @Header("Authorization") token: String
    ): Call<UsersResponse>

    @POST("conversations")
    fun createConversation(
        @Header("Authorization") token: String,
        @Body request: CreateConversationRequest
    ): Call<CreateConversationResponse>

    @POST("conversations/{id}/messages")
    fun sendMessage(
        @Header("Authorization") token: String,
        @retrofit2.http.Path("id") id: String,
        @Body body: Map<String, String>
    ): Call<Map<String, Any>>

    @GET("conversations/{id}/messages")
    fun getMessages(
        @Header("Authorization") token: String,
        @Path("id") id: String
    ): Call<MessagesResponse>
}
package com.example.shadowrec

data class LoginRequest(
    val email: String,
    val password: String
)

data class LoginResponse(
    val ok: Boolean,
    val message: String,
    val token: String?,
    val usuario: UsuarioApi?
)

data class UsuarioApi(
    val id: Int,
    val device_id: String?,
    val email: String,
    val nombre: String,
    val apellido: String
)

data class RegisterRequest(
    val nombre: String,
    val apellido: String,
    val email: String,
    val password: String,
    val device_id: String?
)

data class ConversationsResponse(
    val ok: Boolean,
    val conversations: List<ConversationApi>
)

data class ConversationApi(
    val id: Int,
    val type: String,
    val title: String,
    val lastMessage: String,
    val hasUnread: Boolean,
    val participantEmails: List<String>,
    val lastTimestamp: String?,
    val lastReadMillis: Long
)
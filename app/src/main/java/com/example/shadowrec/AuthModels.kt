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

data class UsersResponse(
    val ok: Boolean,
    val users: List<UserApi>
)

data class UserApi(
    val id: Int,
    val nombre: String,
    val apellido: String,
    val email: String
)

data class CreateConversationRequest(
    val emails: List<String>,
    val nombre: String?
)

data class CreateConversationResponse(
    val ok: Boolean,
    val conversacion_id: Int
)

data class MessagesResponse(
    val ok: Boolean,
    val messages: List<MessageApi>
)

data class MessageApi(
    val id: Int,
    val conversationId: Int,
    val fromUid: String,
    val fromEmail: String?,
    val fromName: String?,
    val text: String,
    val createdAt: String?,
    val delivered: Boolean = false,
    val read: Boolean = false
)

data class StartTrackingRequest(
    val device_id: String,
    val marca: String?,
    val modelo: String?,
    val version_android: Int?
)

data class StartTrackingResponse(
    val ok: Boolean,
    val session_id: Int
)

data class SendPointRequest(
    val session_id: Int,
    val device_id: String,
    val lat: Double,
    val lon: Double,
    val accuracy: Double?,
    val provider: String?,
    val origin: String?
)

data class DeviceStatusRequest(
    val device_id: String,
    val status: String,
    val lat: Double?,
    val lon: Double?,
    val accuracy: Double?,
    val marca: String?,
    val modelo: String?,
    val version_android: Int?
)

data class StopTrackingRequest(
    val session_id: Int,
    val device_id: String
)

data class GenericResponse(
    val ok: Boolean
)

data class QrScanRequest(
    val device_id: String,
    val texto: String,
    val origen: String?
)
package com.example.shadowrec

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import androidx.appcompat.app.AlertDialog




class UsersActivity : AppCompatActivity() {

    private lateinit var listUsers: ListView          // ahora lista de CHATS
    private lateinit var progressUsers: ProgressBar
    private lateinit var btnNewChat: Button
    private lateinit var adapter: ArrayAdapter<String>

    private val db by lazy { Firebase.firestore }
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val prefs by lazy {
        getSharedPreferences("shadowrec_prefs", MODE_PRIVATE)
    }

    // prefs locales solo para “NUEVO” por dispositivo
    private val convoPrefs by lazy {
        getSharedPreferences("shadowrec_conversations", MODE_PRIVATE)
    }

    private var currentUid: String? = null
    private var currentEmail: String? = null

    // Mapa email -> nombre completo (se carga una sola vez)
    private val userNameByEmail = mutableMapOf<String, String>()

    data class ConversationItem(
        val id: String,
        val type: String,             // "direct" o "group"
        var title: String,            // nombre grupo o persona
        var lastMessage: String,
        var hasUnread: Boolean,
        val participantEmails: List<String>,
        var lastTimestamp: Timestamp? // para manejar leído local
    )

    private val conversations = mutableListOf<ConversationItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_users)

        listUsers = findViewById(R.id.listUsers)
        progressUsers = findViewById(R.id.progressUsers)
        btnNewChat = findViewById(R.id.btnNewChat)

        adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            mutableListOf<String>()
        )
        listUsers.adapter = adapter

        val user = auth.currentUser
        if (user == null) {
            Toast.makeText(this, "No hay usuario logueado", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        currentUid = user.uid
        currentEmail = prefs.getString("user_email", user.email) ?: user.email

        // Tap corto -> abrir chat y marcar leído local
        listUsers.setOnItemClickListener { _, _, position, _ ->
            val conv = conversations.getOrNull(position) ?: return@setOnItemClickListener

            if (conv.hasUnread) {
                conv.hasUnread = false
                markConversationLocallyRead(conv)
                refreshLabels()
            }

            val i = Intent(this, ChatActivity::class.java).apply {
                putExtra(ChatActivity.EXTRA_CONVERSATION_ID, conv.id)
                putExtra(ChatActivity.EXTRA_IS_GROUP, conv.type == "group")
                putExtra(ChatActivity.EXTRA_CHAT_TITLE, conv.title)
            }
            startActivity(i)
        }

        // Tap largo -> eliminar (ocultar) chat para este usuario
        listUsers.setOnItemLongClickListener { _, _, position, _ ->
            val conv = conversations.getOrNull(position) ?: return@setOnItemLongClickListener true

            AlertDialog.Builder(this)
                .setTitle("Eliminar chat")
                .setMessage(
                    "¿Querés eliminar este chat de tu lista?\n\n" +
                            "No se borrará para los demás participantes."
                )
                .setPositiveButton("Eliminar") { _, _ ->
                    deleteConversationForUser(conv)
                }
                .setNegativeButton("Cancelar", null)
                .show()

            true
        }

        // Botón "Nuevo chat"
        btnNewChat.setOnClickListener {
            val i = Intent(this, NewChatActivity::class.java)
            startActivity(i)
        }

        loadConversations()
    }

    override fun onResume() {
        super.onResume()
        // Refrescamos al volver desde un chat o desde nuevo chat
        loadConversations()
    }

    private fun loadConversations() {
        val uid = currentUid ?: run {
            Toast.makeText(this, "No hay usuario logueado", Toast.LENGTH_SHORT).show()
            return
        }

        progressUsers.visibility = View.VISIBLE
        conversations.clear()

        // 1) Traemos TODOS los usuarios una vez para armar mapa email -> nombre
        db.collection("users")
            .get()
            .addOnSuccessListener { qs ->
                userNameByEmail.clear()
                for (doc in qs.documents) {
                    val email = doc.getString("email") ?: continue
                    val first = doc.getString("firstName") ?: ""
                    val last = doc.getString("lastName") ?: ""
                    val name = (first + " " + last).trim().ifEmpty { email }
                    userNameByEmail[email] = name
                }
                // 2) Ahora sí, traemos las conversaciones del usuario
                loadConversationsForUser(uid)
            }
            .addOnFailureListener {
                // Si falla, igual intentamos cargar las conversaciones (usando email como título)
                loadConversationsForUser(uid)
            }
    }

    private fun loadConversationsForUser(uid: String) {
        db.collection("conversations")
            .whereArrayContains("participants", uid)
            .get()
            .addOnSuccessListener { qs ->
                conversations.clear()

                for (doc in qs.documents) {
                    val id = doc.id
                    val type = doc.getString("type") ?: "direct"
                    val isGroup = type == "group"

                    // Si la conversación está oculta para este usuario, la saltamos
                    @Suppress("UNCHECKED_CAST")
                    val hiddenFor =
                        (doc.get("hiddenFor") as? List<*>)?.mapNotNull { it as? String }
                            ?: emptyList()
                    if (hiddenFor.contains(uid)) {
                        continue
                    }

                    val lastMessage = doc.getString("lastMessage") ?: "(sin mensajes)"
                    val lastTs = doc.getTimestamp("lastTimestamp")
                    val lastFromUid = doc.getString("lastFromUid") // puede ser null en chats viejos

                    // --- LÓGICA LOCAL DE "NUEVO" ---
                    val lastServerMillis = lastTs?.toDate()?.time ?: 0L
                    val localKey = "last_read_$id"
                    val localReadMillis = convoPrefs.getLong(localKey, 0L)

                    // Lo mismo que tenías antes: ¿hay algo más nuevo que lo último que leí localmente?
                    val baseHasUnread =
                        lastTs != null && lastServerMillis > localReadMillis

                    // Extra: si el último mensaje lo escribí YO, no lo marco como nuevo
                    val hasUnread = baseHasUnread && lastFromUid != uid
                    // --------------------------------

                    @Suppress("UNCHECKED_CAST")
                    val participantEmails =
                        (doc.get("participantEmails") as? List<*>)?.mapNotNull { it as? String }
                            ?: emptyList()

                    val myEmail = currentEmail
                    val otherEmailForDirect =
                        if (!isGroup && myEmail != null) {
                            participantEmails.firstOrNull { it != myEmail }
                                ?: participantEmails.firstOrNull()
                        } else null

                    val title: String = if (isGroup) {
                        doc.getString("name")
                            ?: if (participantEmails.isNotEmpty()) {
                                val nombres = participantEmails.map { email ->
                                    userNameByEmail[email] ?: email
                                }
                                "Grupo: " + nombres.joinToString(", ")
                            } else {
                                "Chat grupal"
                            }
                    } else {
                        val email = otherEmailForDirect
                        if (email != null) {
                            userNameByEmail[email] ?: email
                        } else {
                            "Chat directo"
                        }
                    }

                    conversations.add(
                        ConversationItem(
                            id = id,
                            type = type,
                            title = title,
                            lastMessage = lastMessage,
                            hasUnread = hasUnread,
                            participantEmails = participantEmails,
                            lastTimestamp = lastTs
                        )
                    )
                }

                // Ordenamos por último mensaje (más reciente arriba)
                conversations.sortByDescending { conv ->
                    conv.lastTimestamp ?: Timestamp(0, 0)
                }

                refreshLabels()
            }
            .addOnFailureListener { e ->
                Toast.makeText(
                    this,
                    "Error cargando chats: ${e.localizedMessage}",
                    Toast.LENGTH_LONG
                ).show()
                refreshLabels()
            }
            .addOnCompleteListener {
                progressUsers.visibility = View.GONE
            }
    }


    private fun markConversationLocallyRead(conv: ConversationItem) {
        val millis = conv.lastTimestamp?.toDate()?.time ?: System.currentTimeMillis()
        val key = "last_read_${conv.id}"
        convoPrefs.edit().putLong(key, millis).apply()
    }

    private fun deleteConversationForUser(conv: ConversationItem) {
        val uid = currentUid ?: return
        val convId = conv.id

        // 1) Marcarla oculta en Firestore para este usuario
        db.collection("conversations")
            .document(convId)
            .update("hiddenFor", FieldValue.arrayUnion(uid))
            .addOnSuccessListener {
                // 2) Limpiar read local
                val key = "last_read_$convId"
                convoPrefs.edit().remove(key).apply()

                // 3) Sacarla de la lista local
                val idx = conversations.indexOfFirst { it.id == convId }
                if (idx != -1) {
                    conversations.removeAt(idx)
                    refreshLabels()
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(
                    this,
                    "Error al eliminar chat: ${e.localizedMessage}",
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    private fun refreshLabels() {
        val labels = conversations.map { conv ->
            val unreadSuffix = if (conv.hasUnread) "\n🔴 NUEVO" else ""
            "${conv.title}\n${conv.lastMessage}$unreadSuffix"
        }

        adapter.clear()
        adapter.addAll(labels)
        adapter.notifyDataSetChanged()
    }
}

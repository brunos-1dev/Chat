package com.example.shadowrec

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import java.text.SimpleDateFormat
import java.util.Locale

class ChatActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_OTHER_EMAIL = "extra_other_email"        // compat viejo
        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
        const val EXTRA_IS_GROUP = "extra_is_group"
        const val EXTRA_CHAT_TITLE = "extra_chat_title"
    }

    private lateinit var txtChatTitle: TextView
    private lateinit var listMessages: ListView
    private lateinit var edtMessage: EditText
    private lateinit var btnSend: Button

    private val messages = mutableListOf<String>()
    private lateinit var adapter: ArrayAdapter<String>

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { Firebase.firestore }
    private val prefs by lazy {
        getSharedPreferences("shadowrec_prefs", MODE_PRIVATE)
    }

    private var currentUid: String? = null
    private var currentEmail: String? = null

    private var conversationId: String? = null
    private var isGroup: Boolean = false

    private var otherEmailForDirect: String? = null // compat por si se entra por email (viejo flujo)

    private var messagesListener: ListenerRegistration? = null
    private var conversationListener: ListenerRegistration? = null

    // uid -> nombre (para mostrar en grupos y directos)
    private val userNameByUid = mutableMapOf<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        txtChatTitle = findViewById(R.id.txtChatTitle)
        listMessages = findViewById(R.id.listMessages)
        edtMessage = findViewById(R.id.edtMessage)
        btnSend = findViewById(R.id.btnSend)

        adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            messages
        )
        listMessages.adapter = adapter

        val user = auth.currentUser
        if (user == null) {
            Toast.makeText(this, "No hay usuario logueado", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        currentUid = user.uid
        currentEmail = prefs.getString("user_email", user.email) ?: user.email

        conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID)
        isGroup = intent.getBooleanExtra(EXTRA_IS_GROUP, false)
        val initialTitle = intent.getStringExtra(EXTRA_CHAT_TITLE)

        if (!initialTitle.isNullOrBlank()) {
            txtChatTitle.text = initialTitle
        }

        // Compat: si no viene conversationId pero sí email (flujo viejo)
        otherEmailForDirect = intent.getStringExtra(EXTRA_OTHER_EMAIL)
        if (conversationId == null && !otherEmailForDirect.isNullOrBlank()) {
            // Creamos/obtenemos convId determinístico entre 2 usuarios
            createOrResolveDirectConversationForEmail(otherEmailForDirect!!)
        } else if (conversationId != null) {
            // Modo nuevo: ya tenemos conversación
            attachConversationListener(conversationId!!)
            startListeningMessages()
            markConversationAsRead()
        } else {
            Toast.makeText(this, "Falta información de la conversación", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        btnSend.setOnClickListener {
            sendMessage()
        }
    }

    // -------------------------------------------------------------
    //   Conversación (información general)
    // -------------------------------------------------------------
    private fun attachConversationListener(convId: String) {
        conversationListener?.remove()
        conversationListener = db.collection("conversations")
            .document(convId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Toast.makeText(
                        this,
                        "Error leyendo conversación: ${error.localizedMessage}",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@addSnapshotListener
                }

                if (snapshot == null || !snapshot.exists()) {
                    Toast.makeText(this, "La conversación ya no existe", Toast.LENGTH_SHORT).show()
                    finish()
                    return@addSnapshotListener
                }

                val data = snapshot.data ?: return@addSnapshotListener

                val type = data["type"] as? String ?: "direct"
                isGroup = type == "group"

                val name = data["name"] as? String
                if (isGroup && !name.isNullOrBlank()) {
                    txtChatTitle.text = name
                }

                @Suppress("UNCHECKED_CAST")
                val participants = (data["participants"] as? List<*>)?.mapNotNull { it as? String }
                    ?: emptyList()

                // Cargar nombres de participantes (grupos)
                loadParticipantNames(participants)
            }
    }

    private fun loadParticipantNames(participants: List<String>) {
        if (participants.isEmpty()) return

        // Pequeña optimización: si ya tenemos nombres para todos, no hacemos nada
        val missing = participants.filter { !userNameByUid.containsKey(it) }
        if (missing.isEmpty()) return

        // whereIn soporta hasta 10 elementos, pero los grupos serán pequeños
        val chunk = missing.take(10)

        db.collection("users")
            .whereIn("uid", chunk)
            .get()
            .addOnSuccessListener { qs ->
                for (doc in qs.documents) {
                    val uid = doc.getString("uid") ?: continue
                    val email = doc.getString("email") ?: uid
                    val first = doc.getString("firstName") ?: ""
                    val last = doc.getString("lastName") ?: ""
                    val name = (first + " " + last).trim().ifEmpty { email }
                    userNameByUid[uid] = name
                }

                // Refrescamos mensajes para que se vean nombres en lugar de "Otro"
                refreshMessagesLabels()
            }
    }

    // -------------------------------------------------------------
    //   Mensajes
    // -------------------------------------------------------------
    private fun startListeningMessages() {
        val convId = conversationId ?: return

        messagesListener?.remove()
        messagesListener = db.collection("conversations")
            .document(convId)
            .collection("messages")
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Toast.makeText(
                        this,
                        "Error escuchando mensajes: ${error.localizedMessage}",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@addSnapshotListener
                }

                messages.clear()
                if (snapshot != null) {
                    for (doc in snapshot.documents) {
                        val text = doc.getString("text") ?: ""
                        val fromUid = doc.getString("fromUid") ?: ""
                        val createdAt = doc.getTimestamp("createdAt")
                        messages.add(buildLabelForMessage(fromUid, text, createdAt))
                    }
                }
                adapter.notifyDataSetChanged()

                if (messages.isNotEmpty()) {
                    listMessages.post {
                        listMessages.setSelection(messages.size - 1)
                    }
                }

                markConversationAsRead()
            }
    }

    private fun buildLabelForMessage(
        fromUid: String,
        text: String,
        createdAt: Timestamp?
    ): String {
        val me = currentUid

        val senderName = when {
            fromUid.isEmpty() -> ""
            me != null && fromUid == me -> "Yo"
            else -> {
                // si es grupo, buscamos por uid
                if (isGroup) {
                    userNameByUid[fromUid] ?: "Otro"
                } else {
                    // directo: usamos título o nombre cacheado
                    userNameByUid[fromUid]
                        ?: txtChatTitle.text?.toString()
                        ?: "Otro"
                }
            }
        }

        val timePart = formatMsgTimestamp(createdAt)

        val base = if (senderName.isNotEmpty()) {
            "$senderName: $text"
        } else {
            text
        }

        return if (timePart.isNotEmpty()) {
            "$base  $timePart"
        } else {
            base
        }
    }

    private fun formatMsgTimestamp(ts: Timestamp?): String {
        if (ts == null) return ""
        val df = SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault())
        return df.format(ts.toDate())   // ej: "03/12 16:05"
    }

    private fun refreshMessagesLabels() {
        // Re-generamos los textos usando buildLabelForMessage
        val convId = conversationId ?: return

        db.collection("conversations")
            .document(convId)
            .collection("messages")
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .get()
            .addOnSuccessListener { snapshot ->
                messages.clear()
                for (doc in snapshot.documents) {
                    val text = doc.getString("text") ?: ""
                    val fromUid = doc.getString("fromUid") ?: ""
                    val createdAt = doc.getTimestamp("createdAt")
                    messages.add(buildLabelForMessage(fromUid, text, createdAt))
                }
                adapter.notifyDataSetChanged()
                if (messages.isNotEmpty()) {
                    listMessages.post {
                        listMessages.setSelection(messages.size - 1)
                    }
                }
            }
    }

    private fun markConversationAsRead() {
        val uid = currentUid ?: return
        val convId = conversationId ?: return

        val update = mapOf(
            "readStatus.$uid" to FieldValue.serverTimestamp()
        )
        db.collection("conversations")
            .document(convId)
            .set(update, SetOptions.merge())
    }

    // -------------------------------------------------------------
    //   Enviar mensaje
    // -------------------------------------------------------------
    private fun sendMessage() {
        val text = edtMessage.text.toString().trim()
        if (text.isEmpty()) return

        val from = currentUid ?: return
        val convId = conversationId

        if (convId == null) {
            Toast.makeText(this, "No se encontró la conversación", Toast.LENGTH_SHORT).show()
            return
        }

        val msg = hashMapOf(
            "fromUid" to from,
            "text" to text,
            "createdAt" to FieldValue.serverTimestamp()
        )

        db.collection("conversations")
            .document(convId)
            .collection("messages")
            .add(msg)
            .addOnSuccessListener {
                edtMessage.text.clear()
            }
            .addOnFailureListener { e ->
                Toast.makeText(
                    this,
                    "Error enviando mensaje: ${e.localizedMessage}",
                    Toast.LENGTH_SHORT
                ).show()
            }

        val summary = hashMapOf(
            "lastMessage" to text,
            "lastTimestamp" to FieldValue.serverTimestamp(),
            "lastFromUid" to from              // 👈 quién mandó el último mensaje
        )

        db.collection("conversations")
            .document(convId)
            .set(summary, SetOptions.merge())
    }

    // -------------------------------------------------------------
    //   Compat: crear/obtener conversación directa usando email
    // -------------------------------------------------------------
    private fun createOrResolveDirectConversationForEmail(email: String) {
        val meUid = currentUid ?: return

        // Buscamos usuario por email
        db.collection("users")
            .whereEqualTo("email", email)
            .limit(1)
            .get()
            .addOnSuccessListener { qs ->
                if (qs.isEmpty) {
                    Toast.makeText(
                        this,
                        "No se encontró usuario con ese email",
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                    return@addOnSuccessListener
                }

                val userDoc = qs.documents[0]
                val otherUid = userDoc.getString("uid") ?: userDoc.id

                val convId = conversationIdFor(meUid, otherUid)
                conversationId = convId
                isGroup = false

                // Aseguramos que exista el doc de conversación
                val myEmail = currentEmail
                val otherEmail = email

                val data = hashMapOf(
                    "type" to "direct",
                    "participants" to listOf(meUid, otherUid),
                    "participantEmails" to listOfNotNull(myEmail, otherEmail),
                    "lastMessage" to "",
                    "lastTimestamp" to FieldValue.serverTimestamp()
                )

                db.collection("conversations")
                    .document(convId)
                    .set(data, SetOptions.merge())
                    .addOnSuccessListener {
                        attachConversationListener(convId)
                        startListeningMessages()
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(
                            this,
                            "Error creando conversación: ${e.localizedMessage}",
                            Toast.LENGTH_SHORT
                        ).show()
                        finish()
                    }
            }
            .addOnFailureListener { e ->
                Toast.makeText(
                    this,
                    "Error buscando usuario: ${e.localizedMessage}",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
    }

    private fun conversationIdFor(u1: String, u2: String): String {
        // corregido: ID determinístico usando ambos UIDs
        return if (u1 < u2) "${u1}_$u2" else "${u2}_$u1"
    }

    override fun onDestroy() {
        super.onDestroy()
        messagesListener?.remove()
        conversationListener?.remove()
    }
}

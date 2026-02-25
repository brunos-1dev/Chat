package com.example.shadowrec

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import java.util.Calendar

class ChatActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_OTHER_EMAIL = "extra_other_email"        // compat viejo
        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
        const val EXTRA_IS_GROUP = "extra_is_group"
        const val EXTRA_CHAT_TITLE = "extra_chat_title"
    }

    private lateinit var txtChatTitle: TextView
    private lateinit var txtChatAvatar: TextView
    private lateinit var listMessages: ListView
    private lateinit var edtMessage: EditText
    private lateinit var btnSend: Button

    // Ahora usamos una lista de filas ricas (mensaje + hora + quién)
    private val messages = mutableListOf<ChatMessageRow>()
    private lateinit var adapter: ChatMessagesAdapter

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { Firebase.firestore }

    // Preferencias de usuario (ya las tenías)
    private val prefs by lazy {
        getSharedPreferences("shadowrec_prefs", MODE_PRIVATE)
    }

    // NUEVO: prefs locales solo para estado de conversaciones (NUEVO)
    private val convoPrefs by lazy {
        getSharedPreferences("shadowrec_conversations", MODE_PRIVATE)
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

    // Datos para cada fila del ListView
    data class ChatMessageRow(
        val fromUid: String,
        val labelText: String,     // texto que se muestra en la burbuja
        val createdAt: Timestamp?  // para mostrar hora/fecha
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        txtChatTitle = findViewById(R.id.txtChatTitle)
        txtChatAvatar = findViewById(R.id.txtChatAvatar)
        listMessages = findViewById(R.id.listMessages)
        edtMessage = findViewById(R.id.edtMessage)
        btnSend = findViewById(R.id.btnSend)

        adapter = ChatMessagesAdapter(messages)
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
            updateAvatarFromTitle(initialTitle)
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
                    updateAvatarFromTitle(name)
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

                        val label = buildLabelForMessage(fromUid, text)
                        messages.add(
                            ChatMessageRow(
                                fromUid = fromUid,
                                labelText = label,
                                createdAt = createdAt
                            )
                        )
                    }
                }
                adapter.notifyDataSetChanged()

                if (messages.isNotEmpty()) {
                    listMessages.post {
                        listMessages.setSelection(messages.size - 1)
                    }
                }

                // Al recibir/actualizar mensajes, marcamos la conversación como leída
                if (messages.isNotEmpty()) {
                    markConversationAsRead()
                }
            }
    }

    private fun buildLabelForMessage(fromUid: String, text: String): String {
        val me = currentUid

        val senderName = when {
            fromUid.isEmpty() -> ""
            // Mensajes míos (tanto en grupo como directos) -> sin "Yo"
            me != null && fromUid == me -> {
                ""
            }
            else -> {
                if (isGroup) {
                    // En grupos: mostrar nombre de la otra persona
                    userNameByUid[fromUid] ?: "Otro"
                } else {
                    // En chats directos: sin nombre, solo el texto
                    ""
                }
            }
        }

        return if (senderName.isNotEmpty()) {
            "$senderName: $text"
        } else {
            text
        }
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

                    val label = buildLabelForMessage(fromUid, text)
                    messages.add(
                        ChatMessageRow(
                            fromUid = fromUid,
                            labelText = label,
                            createdAt = createdAt
                        )
                    )
                }
                adapter.notifyDataSetChanged()
                if (messages.isNotEmpty()) {
                    listMessages.post {
                        listMessages.setSelection(messages.size - 1)
                    }
                }

                // También aquí, si recargamos todo, marcamos como leído
                if (messages.isNotEmpty()) {
                    markConversationAsRead()
                }
            }
    }

    /**
     * Marca la conversación como leída PARA ESTE USUARIO.
     * - Local: guarda la última vez que abriste este chat en este dispositivo.
     * - Remoto: actualiza readStatus.{uid} en Firestore (opcional).
     */
    private fun markConversationAsRead() {
        val uid = currentUid ?: return
        val convId = conversationId ?: return

        // 1) LOCAL: momento de lectura en este dispositivo
        val key = "last_read_${uid}_$convId"
        val nowMillis = System.currentTimeMillis()
        val previous = convoPrefs.getLong(key, 0L)

        if (nowMillis > previous) {
            convoPrefs.edit()
                .putLong(key, nowMillis)
                .apply()
        }

        // 2) REMOTO (opcional): marca de lectura en Firestore
        val update = mapOf(
            "readStatus.$uid" to FieldValue.serverTimestamp()
        )
        db.collection("conversations")
            .document(convId)
            .set(update, SetOptions.merge())
            .addOnFailureListener {
                // Si falla, el estado local igualmente evita que aparezca "NUEVO" en este dispositivo
            }
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
            "lastFromUid" to from
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
        return if (u1 < u2) "${u1}_$u2" else "${u2}_$u1"
    }

    override fun onDestroy() {
        super.onDestroy()
        messagesListener?.remove()
        conversationListener?.remove()
    }

    // -------------------------------------------------------------
    //   Avatar helpers
    // -------------------------------------------------------------
    private fun updateAvatarFromTitle(title: String) {
        val initials = buildInitials(title)
        txtChatAvatar.text = initials
    }

    private fun buildInitials(name: String): String {
        val parts = name.trim().split(" ")
            .filter { it.isNotBlank() }
        if (parts.isEmpty()) return "?"

        return if (parts.size == 1) {
            parts[0].take(2).uppercase()
        } else {
            (parts[0].take(1) + parts[1].take(1)).uppercase()
        }
    }

    // -------------------------------------------------------------
    //   Helpers para menú contextual de mensajes
    // -------------------------------------------------------------
    private fun showMessageOptionsDialog(item: ChatMessageRow) {
        val options = arrayOf("Copiar mensaje", "Ver fecha y hora")

        AlertDialog.Builder(this)
            .setTitle("Mensaje")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> copyMessageToClipboard(item.labelText)
                    1 -> showMessageInfo(item)
                }
            }
            .show()
    }

    private fun copyMessageToClipboard(text: String) {
        val clipboard =
            getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Mensaje", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Mensaje copiado", Toast.LENGTH_SHORT).show()
    }

    private fun showMessageInfo(item: ChatMessageRow) {
        val ts = item.createdAt
        val msgInfo = if (ts != null) {
            val date = ts.toDate()
            val dateStr = android.text.format.DateFormat
                .format("dd/MM/yyyy HH:mm:ss", date)
                .toString()
            "Enviado el: $dateStr"
        } else {
            "Este mensaje aún no tiene marca de tiempo disponible."
        }

        AlertDialog.Builder(this)
            .setTitle("Información del mensaje")
            .setMessage(msgInfo)
            .setPositiveButton("OK", null)
            .show()
    }

    // -------------------------------------------------------------
    //   Adapter de mensajes con burbujas
    // -------------------------------------------------------------
    private inner class ChatMessagesAdapter(
        private val items: List<ChatMessageRow>
    ) : BaseAdapter() {

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): ChatMessageRow = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@ChatActivity)
                .inflate(R.layout.item_message, parent, false)

            val root = view.findViewById<LinearLayout>(R.id.messageRowRoot)
            val bubble = view.findViewById<LinearLayout>(R.id.messageBubble)
            val txtBody = view.findViewById<TextView>(R.id.txtMessageBody)
            val txtTime = view.findViewById<TextView>(R.id.txtMessageTime)

            val item = getItem(position)
            val myUid = currentUid
            val isMine = myUid != null && item.fromUid == myUid

            // Alineamos burbuja
            root.gravity = if (isMine) Gravity.END else Gravity.START

            // Fondo según quién envía
            val bgRes = if (isMine) R.drawable.bg_message_me else R.drawable.bg_message_other
            bubble.background = ContextCompat.getDrawable(this@ChatActivity, bgRes)

            // Texto del mensaje (incluye nombre si aplica)
            txtBody.text = item.labelText

            // Hora (y fecha si no es hoy)
            val ts = item.createdAt
            if (ts != null) {
                val date = ts.toDate()

                val msgCal = Calendar.getInstance().apply { time = date }
                val nowCal = Calendar.getInstance()

                val sameDay =
                    msgCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR) &&
                            msgCal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR)

                val timeStr = android.text.format.DateFormat.format("HH:mm", date).toString()
                val dateStr =
                    if (sameDay) ""
                    else android.text.format.DateFormat.format("dd/MM", date).toString()

                txtTime.text = if (dateStr.isEmpty()) timeStr else "$dateStr $timeStr"
                txtTime.visibility = View.VISIBLE
            } else {
                txtTime.text = ""
                txtTime.visibility = View.GONE
            }

            // Long press sobre la burbuja -> menú contextual
            bubble.setOnLongClickListener {
                showMessageOptionsDialog(item)
                true
            }

            return view
        }
    }
}
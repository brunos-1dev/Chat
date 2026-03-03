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

        // Punto de lectura calculado en UsersActivity
        const val EXTRA_LAST_READ_MILLIS = "extra_last_read_millis"
    }

    private lateinit var txtChatTitle: TextView
    private lateinit var txtChatAvatar: TextView
    private lateinit var listMessages: ListView
    private lateinit var edtMessage: EditText
    private lateinit var btnSend: Button

    // Lista de mensajes (mensaje + hora + quién)
    private val messages = mutableListOf<ChatMessageRow>()
    private lateinit var adapter: ChatMessagesAdapter

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { Firebase.firestore }

    // Preferencias de usuario
    private val prefs by lazy {
        getSharedPreferences("shadowrec_prefs", MODE_PRIVATE)
    }

    // Prefs locales para estado de conversaciones (NUEVO)
    private val convoPrefs by lazy {
        getSharedPreferences("shadowrec_conversations", MODE_PRIVATE)
    }

    private var currentUid: String? = null
    private var currentEmail: String? = null

    private var conversationId: String? = null
    private var isGroup: Boolean = false

    private var otherEmailForDirect: String? = null // compat email

    private var messagesListener: ListenerRegistration? = null
    private var conversationListener: ListenerRegistration? = null

    // uid -> nombre (para grupos)
    private val userNameByUid = mutableMapOf<String, String>()

    // Datos para cada fila del ListView
    data class ChatMessageRow(
        val fromUid: String,
        val labelText: String,
        val createdAt: Timestamp?
    )

    // --------- Paginado ----------
    private val PAGE_SIZE = 50
    private var oldestLoadedTimestamp: Timestamp? = null
    private var isLoadingOlder = false
    private var noMoreOldMessages = false
    private var headerLoadMore: TextView? = null
    // --------- Scroll inicial ---------
    private var initialLastReadMillis: Long = 0L
    private var alreadyScrolledToInitial = false
    // ----------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        txtChatTitle = findViewById(R.id.txtChatTitle)
        txtChatAvatar = findViewById(R.id.txtChatAvatar)
        listMessages = findViewById(R.id.listMessages)
        edtMessage = findViewById(R.id.edtMessage)
        btnSend = findViewById(R.id.btnSend)

        // HEADER "Cargar mensajes anteriores"
        val header = layoutInflater.inflate(
            R.layout.item_load_more_messages,
            listMessages,
            false
        ) as TextView
        header.text = "Cargar mensajes anteriores"
        header.visibility = View.GONE
        header.setOnClickListener {
            if (!isLoadingOlder && !noMoreOldMessages) {
                loadOlderMessages()
            }
        }

        // Primero lo agregamos, después lo guardamos
        listMessages.addHeaderView(header)
        headerLoadMore = header

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

        // 1️⃣ Intentamos usar el punto de lectura que viene de UsersActivity
        initialLastReadMillis = intent.getLongExtra(EXTRA_LAST_READ_MILLIS, 0L)

        // 2️⃣ Si no vino (0), leemos de SharedPreferences (comportamiento viejo)
        if (initialLastReadMillis == 0L) {
            currentUid?.let { uid ->
                conversationId?.let { convId ->
                    val key = "last_read_${uid}_$convId"
                    initialLastReadMillis = convoPrefs.getLong(key, 0L)
                }
            }
        }

        // Compat: si no viene conversationId pero sí email (flujo viejo)
        otherEmailForDirect = intent.getStringExtra(EXTRA_OTHER_EMAIL)
        if (conversationId == null && !otherEmailForDirect.isNullOrBlank()) {
            createOrResolveDirectConversationForEmail(otherEmailForDirect!!)
        } else if (conversationId != null) {
            attachConversationListener(conversationId!!)
            startListeningMessages()
        } else {
            Toast.makeText(this, "Falta información de la conversación", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        btnSend.setOnClickListener { sendMessage() }
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

                loadParticipantNames(participants)
            }
    }

    private fun loadParticipantNames(participants: List<String>) {
        if (participants.isEmpty()) return

        val missing = participants.filter { !userNameByUid.containsKey(it) }
        if (missing.isEmpty()) return

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

                // Solo refrescamos la UI para que se vean los nombres
                adapter.notifyDataSetChanged()
            }
    }

    // -------------------------------------------------------------
    //   Mensajes (paginado)
    // -------------------------------------------------------------
    private fun startListeningMessages() {
        val convId = conversationId ?: return

        messagesListener?.remove()
        messagesListener = db.collection("conversations")
            .document(convId)
            .collection("messages")
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .limitToLast(PAGE_SIZE.toLong())
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Toast.makeText(
                        this,
                        "Error escuchando mensajes: ${error.localizedMessage}",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@addSnapshotListener
                }

                if (snapshot == null) return@addSnapshotListener

                val docs = snapshot.documents
                val pageRows = mutableListOf<ChatMessageRow>()
                for (doc in docs) {
                    val text = doc.getString("text") ?: ""
                    val fromUid = doc.getString("fromUid") ?: ""
                    val createdAt = doc.getTimestamp("createdAt")
                    val label = buildLabelForMessage(fromUid, text)
                    pageRows.add(
                        ChatMessageRow(
                            fromUid = fromUid,
                            labelText = label,
                            createdAt = createdAt
                        )
                    )
                }

                // Mantener mensajes viejos ya cargados (por paginado)
                val currentSize = messages.size
                val currentOldCount = (currentSize - docs.size).coerceAtLeast(0)
                val keepOld: List<ChatMessageRow> =
                    if (currentOldCount > 0 && currentOldCount <= messages.size) {
                        messages.subList(0, currentOldCount).toList()
                    } else {
                        emptyList()
                    }

                messages.clear()
                messages.addAll(keepOld)
                messages.addAll(pageRows)

                oldestLoadedTimestamp = messages.firstOrNull()?.createdAt

                if (!noMoreOldMessages && oldestLoadedTimestamp != null) {
                    headerLoadMore?.visibility = View.VISIBLE
                } else if (noMoreOldMessages) {
                    headerLoadMore?.visibility = View.GONE
                }

                adapter.notifyDataSetChanged()

                if (!isLoadingOlder && messages.isNotEmpty()) {
                    if (!alreadyScrolledToInitial) {
                        scrollToFirstUnreadOrBottom()
                        alreadyScrolledToInitial = true
                    } else {
                        // Auto-scroll solo si estás cerca del final
                        val lastVisible = listMessages.lastVisiblePosition
                        val totalWithHeaders =
                            messages.size + listMessages.headerViewsCount
                        if (lastVisible >= totalWithHeaders - 3) {
                            listMessages.post {
                                val lastIndex =
                                    messages.size - 1 + listMessages.headerViewsCount
                                listMessages.setSelection(lastIndex)
                            }
                        }
                    }
                }

                // Marcamos como leído
                if (messages.isNotEmpty()) {
                    markConversationAsRead()
                }
            }
    }

    /**
     * Posiciona el ListView en:
     * - Primer mensaje nuevo (createdAt > initialLastReadMillis), si existe.
     * - Si no hay nuevos → último mensaje.
     * - Si nunca se leyó (initialLastReadMillis == 0) → primer mensaje.
     */
    private fun scrollToFirstUnreadOrBottom() {
        if (messages.isEmpty()) return

        listMessages.post {
            val headerCount = listMessages.headerViewsCount

            if (initialLastReadMillis == 0L) {
                // Nunca se había abierto: mostrar desde el primero
                listMessages.setSelection(headerCount)
                return@post
            }

            val firstUnreadIndex = messages.indexOfFirst { row ->
                val tsMillis = row.createdAt?.toDate()?.time ?: Long.MAX_VALUE
                tsMillis > initialLastReadMillis
            }

            val targetIndexInAdapter = if (firstUnreadIndex == -1) {
                // No hay nuevos: ir al último
                messages.size - 1 + headerCount
            } else {
                // Primer mensaje nuevo
                firstUnreadIndex + headerCount
            }

            listMessages.setSelection(targetIndexInAdapter)
        }
    }

    /**
     * Carga mensajes más antiguos que el más viejo que tenemos actualmente.
     */
    private fun loadOlderMessages() {
        val convId = conversationId ?: return
        val oldest = oldestLoadedTimestamp ?: run {
            noMoreOldMessages = true
            headerLoadMore?.visibility = View.GONE
            return
        }

        if (isLoadingOlder || noMoreOldMessages) return

        isLoadingOlder = true
        headerLoadMore?.isEnabled = false
        headerLoadMore?.text = "Cargando mensajes..."

        db.collection("conversations")
            .document(convId)
            .collection("messages")
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .endBefore(oldest)
            .limitToLast(PAGE_SIZE.toLong())
            .get()
            .addOnSuccessListener { snapshot ->
                val docs = snapshot.documents
                if (docs.isEmpty()) {
                    noMoreOldMessages = true
                    headerLoadMore?.visibility = View.GONE
                } else {
                    val olderRows = mutableListOf<ChatMessageRow>()
                    for (doc in docs) {
                        val text = doc.getString("text") ?: ""
                        val fromUid = doc.getString("fromUid") ?: ""
                        val createdAt = doc.getTimestamp("createdAt")
                        val label = buildLabelForMessage(fromUid, text)
                        olderRows.add(
                            ChatMessageRow(
                                fromUid = fromUid,
                                labelText = label,
                                createdAt = createdAt
                            )
                        )
                    }

                    val firstVisible = listMessages.firstVisiblePosition
                    val topView = listMessages.getChildAt(0)
                    val topOffset = topView?.top ?: 0

                    messages.addAll(0, olderRows)
                    oldestLoadedTimestamp = messages.firstOrNull()?.createdAt

                    adapter.notifyDataSetChanged()

                    listMessages.post {
                        listMessages.setSelectionFromTop(
                            firstVisible + olderRows.size,
                            topOffset
                        )
                    }

                    if (docs.size < PAGE_SIZE) {
                        noMoreOldMessages = true
                        headerLoadMore?.visibility = View.GONE
                    } else {
                        headerLoadMore?.isEnabled = true
                        headerLoadMore?.text = "Cargar mensajes anteriores"
                        headerLoadMore?.visibility = View.VISIBLE
                    }
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(
                    this,
                    "Error cargando mensajes anteriores: ${e.localizedMessage}",
                    Toast.LENGTH_SHORT
                ).show()
                headerLoadMore?.isEnabled = true
                headerLoadMore?.text = "Cargar mensajes anteriores"
            }
            .addOnCompleteListener {
                isLoadingOlder = false
            }
    }

    private fun buildLabelForMessage(fromUid: String, text: String): String {
        val me = currentUid

        val senderName = when {
            fromUid.isEmpty() -> ""
            me != null && fromUid == me -> ""
            else -> {
                if (isGroup) {
                    userNameByUid[fromUid] ?: "Otro"
                } else {
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
                        val lastIndex =
                            messages.size - 1 + listMessages.headerViewsCount
                        listMessages.setSelection(lastIndex)
                    }
                }

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

        val key = "last_read_${uid}_$convId"
        val nowMillis = System.currentTimeMillis()
        val previous = convoPrefs.getLong(key, 0L)

        if (nowMillis > previous) {
            convoPrefs.edit()
                .putLong(key, nowMillis)
                .apply()
        }

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
            .addOnSuccessListener { edtMessage.text.clear() }
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

                // leo también último read local (si existiera)
                val key = "last_read_${meUid}_$convId"
                initialLastReadMillis = convoPrefs.getLong(key, 0L)

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
    //   Helpers menú contextual
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
    //   Helper separador de fecha
    // -------------------------------------------------------------
    private fun formatDateSeparator(ts: Timestamp): String {
        val date = ts.toDate()

        val msgCal = Calendar.getInstance().apply { time = date }
        val todayCal = Calendar.getInstance()
        val yesterdayCal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -1)
        }

        val sameDayToday =
            msgCal.get(Calendar.YEAR) == todayCal.get(Calendar.YEAR) &&
                    msgCal.get(Calendar.DAY_OF_YEAR) == todayCal.get(Calendar.DAY_OF_YEAR)

        val sameDayYesterday =
            msgCal.get(Calendar.YEAR) == yesterdayCal.get(Calendar.YEAR) &&
                    msgCal.get(Calendar.DAY_OF_YEAR) == yesterdayCal.get(Calendar.DAY_OF_YEAR)

        return when {
            sameDayToday -> "Hoy"
            sameDayYesterday -> "Ayer"
            else -> android.text.format.DateFormat
                .format("dd/MM/yyyy", date)
                .toString()
        }
    }

    // -------------------------------------------------------------
    //   Adapter de mensajes
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

            val dateSeparator = view.findViewById<TextView>(R.id.txtDateSeparator)
            val root = view.findViewById<LinearLayout>(R.id.messageRowRoot)
            val bubble = view.findViewById<LinearLayout>(R.id.messageBubble)
            val txtBody = view.findViewById<TextView>(R.id.txtMessageBody)
            val txtTime = view.findViewById<TextView>(R.id.txtMessageTime)

            val item = getItem(position)
            val myUid = currentUid
            val isMine = myUid != null && item.fromUid == myUid

            // Alineamos burbuja izquierda/derecha
            root.gravity = if (isMine) Gravity.END else Gravity.START

            // Fondo según quién envía
            val bgRes = if (isMine) R.drawable.bg_message_me else R.drawable.bg_message_other
            bubble.background = ContextCompat.getDrawable(this@ChatActivity, bgRes)

            // Texto del mensaje
            txtBody.text = item.labelText

            // Hora (y fecha si no es hoy) dentro de la burbuja
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

            // Separador de fecha (Hoy / Ayer / dd/MM/yyyy)
            if (ts == null) {
                dateSeparator.visibility = View.GONE
            } else {
                val thisCal = Calendar.getInstance().apply { time = ts.toDate() }

                val showSeparator: Boolean = if (position == 0) {
                    // Primer mensaje de la lista → siempre muestra fecha
                    true
                } else {
                    val prevItem = getItem(position - 1)
                    val prevTs = prevItem.createdAt
                    if (prevTs == null) {
                        true
                    } else {
                        val prevCal = Calendar.getInstance().apply { time = prevTs.toDate() }
                        !(
                                thisCal.get(Calendar.YEAR) == prevCal.get(Calendar.YEAR) &&
                                        thisCal.get(Calendar.DAY_OF_YEAR) == prevCal.get(Calendar.DAY_OF_YEAR)
                                )
                    }
                }

                if (showSeparator) {
                    dateSeparator.visibility = View.VISIBLE
                    dateSeparator.text = formatDateSeparator(ts)
                } else {
                    dateSeparator.visibility = View.GONE
                }
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
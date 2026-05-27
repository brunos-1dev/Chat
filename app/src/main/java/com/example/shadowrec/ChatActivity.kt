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
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.text.SimpleDateFormat
import java.util.*

class ChatActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_OTHER_EMAIL = "extra_other_email"
        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
        const val EXTRA_IS_GROUP = "extra_is_group"
        const val EXTRA_CHAT_TITLE = "extra_chat_title"
        const val EXTRA_LAST_READ_MILLIS = "extra_last_read_millis"
    }

    private lateinit var txtChatTitle: TextView
    private lateinit var txtChatAvatar: TextView
    private lateinit var listMessages: ListView
    private lateinit var edtMessage: EditText
    private lateinit var btnSend: Button

    private val messages = mutableListOf<ChatMessageRow>()
    private lateinit var adapter: ChatMessagesAdapter

    private val prefs by lazy {
        getSharedPreferences("shadowrec_prefs", MODE_PRIVATE)
    }

    private val convoPrefs by lazy {
        getSharedPreferences("shadowrec_conversations", MODE_PRIVATE)
    }

    private var currentUid: String? = null
    private var currentEmail: String? = null
    private var conversationId: String? = null
    private var isGroup: Boolean = false

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private val refreshRunnable = object : Runnable {
        override fun run() {
            loadMessagesFromBackend()
            handler.postDelayed(this, 3000)
        }
    }

    data class ChatMessageRow(
        val fromUid: String,
        val labelText: String,
        val createdAt: String?,
        val delivered: Boolean,
        val read: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        txtChatTitle = findViewById(R.id.txtChatTitle)
        txtChatAvatar = findViewById(R.id.txtChatAvatar)
        listMessages = findViewById(R.id.listMessages)
        edtMessage = findViewById(R.id.edtMessage)
        btnSend = findViewById(R.id.btnSend)

        val header = layoutInflater.inflate(
            R.layout.item_load_more_messages,
            listMessages,
            false
        ) as TextView

        header.text = "Cargar mensajes anteriores"
        header.visibility = View.GONE
        listMessages.addHeaderView(header)

        adapter = ChatMessagesAdapter(messages)
        listMessages.adapter = adapter

        val userId = prefs.getInt("user_id", -1)
        val userEmail = prefs.getString("user_email", null)

        if (userId == -1 || userEmail.isNullOrEmpty()) {
            Toast.makeText(this, "No hay usuario logueado", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        currentUid = userId.toString()
        currentEmail = userEmail

        conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID)
        isGroup = intent.getBooleanExtra(EXTRA_IS_GROUP, false)

        val initialTitle = intent.getStringExtra(EXTRA_CHAT_TITLE)

        if (!initialTitle.isNullOrBlank()) {
            txtChatTitle.text = initialTitle
            updateAvatarFromTitle(initialTitle)
        }

        if (conversationId == null) {
            Toast.makeText(this, "Falta información de la conversación", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        loadMessagesFromBackend()

        btnSend.setOnClickListener {
            sendMessage()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(refreshRunnable)
    }

    private fun loadMessagesFromBackend() {
        val token = prefs.getString("auth_token", null)
        val convId = conversationId

        if (token.isNullOrEmpty() || convId == null) {
            Toast.makeText(this, "Error de sesión", Toast.LENGTH_SHORT).show()
            return
        }

        ApiClient.authService.getMessages("Bearer $token", convId)
            .enqueue(object : Callback<MessagesResponse> {
                override fun onResponse(
                    call: Call<MessagesResponse>,
                    response: Response<MessagesResponse>
                ) {
                    if (!response.isSuccessful) {
                        Toast.makeText(
                            this@ChatActivity,
                            "Error cargando mensajes",
                            Toast.LENGTH_SHORT
                        ).show()
                        return
                    }

                    val body = response.body()

                    if (body == null || !body.ok) {
                        Toast.makeText(
                            this@ChatActivity,
                            "No se pudieron cargar los mensajes",
                            Toast.LENGTH_SHORT
                        ).show()
                        return
                    }

                    messages.clear()

                    for (msg in body.messages) {
                        val label = buildLabelForMessage(
                            fromUid = msg.fromUid,
                            fromName = msg.fromName,
                            text = msg.text
                        )

                        messages.add(
                            ChatMessageRow(
                                fromUid = msg.fromUid,
                                labelText = label,
                                createdAt = msg.createdAt,
                                delivered = msg.delivered,
                                read = msg.read
                            )
                        )
                    }

                    adapter.notifyDataSetChanged()

                    if (messages.isNotEmpty()) {
                        listMessages.post {
                            val lastIndex = messages.size - 1 + listMessages.headerViewsCount
                            listMessages.setSelection(lastIndex)
                        }
                    }

                    markConversationAsRead()
                }

                override fun onFailure(call: Call<MessagesResponse>, t: Throwable) {
                    Toast.makeText(
                        this@ChatActivity,
                        "Error de conexión: ${t.localizedMessage}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })
    }

    private fun sendMessage() {
        val text = edtMessage.text.toString().trim()
        if (text.isEmpty()) return

        val token = prefs.getString("auth_token", null)
        val convId = conversationId

        if (token.isNullOrEmpty() || convId == null) {
            Toast.makeText(this, "Error de sesión", Toast.LENGTH_SHORT).show()
            return
        }

        val body = mapOf("text" to text)

        ApiClient.authService.sendMessage("Bearer $token", convId, body)
            .enqueue(object : Callback<Map<String, Any>> {
                override fun onResponse(
                    call: Call<Map<String, Any>>,
                    response: Response<Map<String, Any>>
                ) {
                    if (response.isSuccessful) {
                        edtMessage.text.clear()
                        loadMessagesFromBackend()
                    } else {
                        Toast.makeText(
                            this@ChatActivity,
                            "Error enviando",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                override fun onFailure(call: Call<Map<String, Any>>, t: Throwable) {
                    Toast.makeText(
                        this@ChatActivity,
                        "Error: ${t.localizedMessage}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })
    }

    private fun markConversationAsRead() {
        val token = prefs.getString("auth_token", null)
        val convId = conversationId

        if (token.isNullOrEmpty() || convId == null) return

        ApiClient.authService.markConversationAsRead("Bearer $token", convId)
            .enqueue(object : Callback<GenericResponse> {
                override fun onResponse(
                    call: Call<GenericResponse>,
                    response: Response<GenericResponse>
                ) {
                    // No mostramos Toast para no molestar al usuario.
                }

                override fun onFailure(call: Call<GenericResponse>, t: Throwable) {
                    // Silencioso por ahora.
                }
            })
    }

    private fun buildLabelForMessage(
        fromUid: String,
        fromName: String?,
        text: String
    ): String {
        val me = currentUid

        return if (isGroup && fromUid != me && !fromName.isNullOrBlank()) {
            "$fromName: $text"
        } else {
            text
        }
    }

    private fun updateAvatarFromTitle(title: String) {
        txtChatAvatar.text = buildInitials(title)
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
        val msgInfo = item.createdAt?.let {
            "Enviado el: ${formatDateTime(it)}"
        } ?: "Este mensaje aún no tiene marca de tiempo disponible."

        AlertDialog.Builder(this)
            .setTitle("Información del mensaje")
            .setMessage(msgInfo)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun parseBackendDate(value: String?): Date? {
        if (value.isNullOrBlank()) return null

        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd HH:mm:ss"
        )

        for (pattern in formats) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                return sdf.parse(value)
            } catch (_: Exception) {
            }
        }

        return null
    }

    private fun formatDateTime(value: String): String {
        val date = parseBackendDate(value) ?: return value
        return android.text.format.DateFormat
            .format("dd/MM/yyyy HH:mm:ss", date)
            .toString()
    }

    private fun formatMessageTime(value: String?): String {
        val date = parseBackendDate(value) ?: return ""

        val msgCal = Calendar.getInstance().apply { time = date }
        val nowCal = Calendar.getInstance()

        val sameDay =
            msgCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR) &&
                    msgCal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR)

        val timeStr = android.text.format.DateFormat.format("HH:mm", date).toString()
        val dateStr =
            if (sameDay) ""
            else android.text.format.DateFormat.format("dd/MM", date).toString()

        return if (dateStr.isEmpty()) timeStr else "$dateStr $timeStr"
    }

    private fun formatDateSeparator(value: String?): String {
        val date = parseBackendDate(value) ?: return ""

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
            val txtStatus = view.findViewById<TextView>(R.id.txtMessageStatus)

            val item = getItem(position)
            val myUid = currentUid
            val isMine = myUid != null && item.fromUid == myUid

            root.gravity = if (isMine) Gravity.END else Gravity.START

            val bgRes = if (isMine) {
                R.drawable.bg_message_me
            } else {
                R.drawable.bg_message_other
            }

            bubble.background = ContextCompat.getDrawable(this@ChatActivity, bgRes)

            txtBody.text = item.labelText

            val timeText = formatMessageTime(item.createdAt)
            if (timeText.isNotEmpty()) {
                txtTime.text = timeText
                txtTime.visibility = View.VISIBLE
            } else {
                txtTime.text = ""
                txtTime.visibility = View.GONE
            }

            if (isMine) {
                txtStatus.visibility = View.VISIBLE

                txtStatus.text = when {
                    item.read -> "✓✓"
                    item.delivered -> "✓✓"
                    else -> "✓"
                }

                val statusColor = if (item.read) {
                    android.graphics.Color.rgb(33, 150, 243) // azul leído
                } else {
                    ContextCompat.getColor(this@ChatActivity, R.color.sr_text_secondary)
                }

                txtStatus.setTextColor(statusColor)

            } else {
                txtStatus.visibility = View.GONE
            }

            val showSeparator = if (item.createdAt.isNullOrBlank()) {
                false
            } else if (position == 0) {
                true
            } else {
                val previous = getItem(position - 1)
                val currentDate = parseBackendDate(item.createdAt)
                val previousDate = parseBackendDate(previous.createdAt)

                if (currentDate == null || previousDate == null) {
                    false
                } else {
                    val currentCal = Calendar.getInstance().apply { time = currentDate }
                    val previousCal = Calendar.getInstance().apply { time = previousDate }

                    !(
                            currentCal.get(Calendar.YEAR) == previousCal.get(Calendar.YEAR) &&
                                    currentCal.get(Calendar.DAY_OF_YEAR) == previousCal.get(Calendar.DAY_OF_YEAR)
                            )
                }
            }

            if (showSeparator) {
                dateSeparator.visibility = View.VISIBLE
                dateSeparator.text = formatDateSeparator(item.createdAt)
            } else {
                dateSeparator.visibility = View.GONE
            }

            bubble.setOnLongClickListener {
                showMessageOptionsDialog(item)
                true
            }

            return view
        }
    }
}
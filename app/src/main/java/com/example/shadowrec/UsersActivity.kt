package com.example.shadowrec

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class UsersActivity : AppCompatActivity() {

    private lateinit var listUsers: ListView
    private lateinit var progressUsers: ProgressBar
    private lateinit var btnNewChat: Button
    private lateinit var edtSearchChats: EditText

    private val prefs by lazy {
        getSharedPreferences("shadowrec_prefs", MODE_PRIVATE)
    }

    private var currentUid: String? = null
    private var currentEmail: String? = null

    data class ConversationItem(
        val id: String,
        val type: String,
        val title: String,
        val lastMessage: String,
        val hasUnread: Boolean,
        val participantEmails: List<String>,
        val lastTimestamp: String?,
        val lastReadMillis: Long
    )

    private val allConversations = mutableListOf<ConversationItem>()
    private val conversations = mutableListOf<ConversationItem>()

    private lateinit var adapter: ConversationsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_users)

        listUsers = findViewById(R.id.listUsers)
        progressUsers = findViewById(R.id.progressUsers)
        btnNewChat = findViewById(R.id.btnNewChat)
        edtSearchChats = findViewById(R.id.edtSearchChats)

        adapter = ConversationsAdapter(conversations)
        listUsers.adapter = adapter

        val userId = prefs.getInt("user_id", -1)
        val userEmail = prefs.getString("user_email", null)

        if (userId == -1 || userEmail.isNullOrEmpty()) {
            Toast.makeText(this, "No hay usuario logueado", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        currentUid = userId.toString()
        currentEmail = userEmail

        listUsers.setOnItemClickListener { _, _, position, _ ->
            val conv = conversations.getOrNull(position) ?: return@setOnItemClickListener

            val intent = Intent(this, ChatActivity::class.java).apply {
                putExtra(ChatActivity.EXTRA_CONVERSATION_ID, conv.id)
                putExtra(ChatActivity.EXTRA_IS_GROUP, conv.type == "group")
                putExtra(ChatActivity.EXTRA_CHAT_TITLE, conv.title)
                putExtra(ChatActivity.EXTRA_LAST_READ_MILLIS, conv.lastReadMillis)
            }

            startActivity(intent)
        }

        listUsers.setOnItemLongClickListener { _, _, position, _ ->
            val conv = conversations.getOrNull(position) ?: return@setOnItemLongClickListener true

            AlertDialog.Builder(this)
                .setTitle("Ocultar chat")
                .setMessage(
                    "La función de ocultar chat todavía está pendiente de migrar al backend.\n\n" +
                            "Chat: \"${conv.title}\""
                )
                .setPositiveButton("OK", null)
                .show()

            true
        }

        btnNewChat.setOnClickListener {
            startActivity(Intent(this, NewChatActivity::class.java))
        }

        edtSearchChats.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                applyFilter(s?.toString() ?: "")
            }
        })

        loadConversations()
    }

    override fun onResume() {
        super.onResume()
        loadConversations()
    }

    private fun loadConversations() {
        val token = prefs.getString("auth_token", null)

        if (token.isNullOrEmpty()) {
            Toast.makeText(this, "No hay token de sesión", Toast.LENGTH_SHORT).show()
            return
        }

        progressUsers.visibility = View.VISIBLE
        allConversations.clear()
        conversations.clear()
        adapter.notifyDataSetChanged()

        ApiClient.authService.getConversations("Bearer $token")
            .enqueue(object : Callback<ConversationsResponse> {
                override fun onResponse(
                    call: Call<ConversationsResponse>,
                    response: Response<ConversationsResponse>
                ) {
                    progressUsers.visibility = View.GONE

                    if (!response.isSuccessful) {
                        Toast.makeText(
                            this@UsersActivity,
                            "Error cargando chats",
                            Toast.LENGTH_LONG
                        ).show()
                        return
                    }

                    val body = response.body()

                    if (body == null || !body.ok) {
                        Toast.makeText(
                            this@UsersActivity,
                            "No se pudieron cargar los chats",
                            Toast.LENGTH_LONG
                        ).show()
                        return
                    }

                    allConversations.clear()

                    for (conv in body.conversations) {
                        allConversations.add(
                            ConversationItem(
                                id = conv.id.toString(),
                                type = conv.type,
                                title = conv.title,
                                lastMessage = conv.lastMessage,
                                hasUnread = conv.hasUnread,
                                participantEmails = conv.participantEmails,
                                lastTimestamp = conv.lastTimestamp,
                                lastReadMillis = conv.lastReadMillis
                            )
                        )
                    }

                    applyFilter(edtSearchChats.text?.toString() ?: "")
                }

                override fun onFailure(call: Call<ConversationsResponse>, t: Throwable) {
                    progressUsers.visibility = View.GONE

                    Toast.makeText(
                        this@UsersActivity,
                        "Error de conexión: ${t.localizedMessage}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            })
    }

    private fun applyFilter(query: String) {
        val q = query.trim().lowercase()

        conversations.clear()

        if (q.isEmpty()) {
            conversations.addAll(allConversations)
        } else {
            for (conv in allConversations) {
                val inTitle = conv.title.lowercase().contains(q)
                val inLastMessage = conv.lastMessage.lowercase().contains(q)
                val inEmails = conv.participantEmails.any { it.lowercase().contains(q) }

                if (inTitle || inLastMessage || inEmails) {
                    conversations.add(conv)
                }
            }
        }

        adapter.notifyDataSetChanged()
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

    private inner class ConversationsAdapter(
        private val items: List<ConversationItem>
    ) : BaseAdapter() {

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): ConversationItem = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@UsersActivity)
                .inflate(R.layout.item_conversation_summary, parent, false)

            val txtTitle = view.findViewById<TextView>(R.id.txtConversationTitle)
            val txtLastMessage = view.findViewById<TextView>(R.id.txtConversationLastMessage)
            val txtTime = view.findViewById<TextView>(R.id.txtConversationTime)
            val txtUnread = view.findViewById<TextView>(R.id.txtConversationUnread)

            val item = getItem(position)

            txtTitle.text = item.title
            txtLastMessage.text = item.lastMessage

            val primaryColor = ContextCompat.getColor(this@UsersActivity, R.color.sr_text_primary)
            val secondaryColor = ContextCompat.getColor(this@UsersActivity, R.color.sr_text_secondary)

            if (item.hasUnread) {
                txtTitle.setTypeface(null, Typeface.BOLD)
                txtLastMessage.setTypeface(null, Typeface.BOLD)
                txtTitle.setTextColor(primaryColor)
                txtLastMessage.setTextColor(primaryColor)
            } else {
                txtTitle.setTypeface(null, Typeface.NORMAL)
                txtLastMessage.setTypeface(null, Typeface.NORMAL)
                txtTitle.setTextColor(secondaryColor)
                txtLastMessage.setTextColor(secondaryColor)
            }

            val date = parseBackendDate(item.lastTimestamp)

            if (date != null) {
                val msgCal = Calendar.getInstance().apply { time = date }
                val nowCal = Calendar.getInstance()

                val sameDay =
                    msgCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR) &&
                            msgCal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR)

                val timeStr = android.text.format.DateFormat.format("HH:mm", date).toString()
                val dateStr =
                    if (sameDay) ""
                    else android.text.format.DateFormat.format("dd/MM", date).toString()

                txtTime.text = if (dateStr.isEmpty()) timeStr else dateStr
                txtTime.visibility = View.VISIBLE
            } else {
                txtTime.text = ""
                txtTime.visibility = View.GONE
            }

            txtUnread.visibility = if (item.hasUnread) View.VISIBLE else View.GONE

            return view
        }
    }
}
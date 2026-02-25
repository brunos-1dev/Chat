package com.example.shadowrec

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import java.util.Calendar

class UsersActivity : AppCompatActivity() {

    private lateinit var listUsers: ListView          // ahora lista de CHATS
    private lateinit var progressUsers: ProgressBar
    private lateinit var btnNewChat: Button

    private val db by lazy { Firebase.firestore }
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val prefs by lazy {
        getSharedPreferences("shadowrec_prefs", MODE_PRIVATE)
    }

    // NUEVO: mismas prefs que usa ChatActivity para "NUEVO"
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
        val lastMessage: String,
        val hasUnread: Boolean,
        val participantEmails: List<String>,
        val lastTimestamp: Timestamp?
    )

    private val conversations = mutableListOf<ConversationItem>()
    private lateinit var adapter: ConversationsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_users)

        listUsers = findViewById(R.id.listUsers)
        progressUsers = findViewById(R.id.progressUsers)
        btnNewChat = findViewById(R.id.btnNewChat)

        adapter = ConversationsAdapter(conversations)
        listUsers.adapter = adapter

        val user = auth.currentUser
        if (user == null) {
            Toast.makeText(this, "No hay usuario logueado", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        currentUid = user.uid
        currentEmail = prefs.getString("user_email", user.email) ?: user.email

        // Tap sobre un chat -> abrir ChatActivity
        listUsers.setOnItemClickListener { _, _, position, _ ->
            val conv = conversations.getOrNull(position) ?: return@setOnItemClickListener

            val i = Intent(this, ChatActivity::class.java).apply {
                putExtra(ChatActivity.EXTRA_CONVERSATION_ID, conv.id)
                putExtra(ChatActivity.EXTRA_IS_GROUP, conv.type == "group")
                putExtra(ChatActivity.EXTRA_CHAT_TITLE, conv.title)
            }
            startActivity(i)
        }

        // Long press -> ocultar chat para este usuario
        listUsers.setOnItemLongClickListener { _, _, position, _ ->
            val conv = conversations.getOrNull(position) ?: return@setOnItemLongClickListener true

            AlertDialog.Builder(this)
                .setTitle("Eliminar chat")
                .setMessage(
                    "¿Querés ocultar el chat \"${conv.title}\"?\n" +
                            "Solo se ocultará para vos, los mensajes siguen existiendo en la nube."
                )
                .setPositiveButton("Ocultar") { _, _ ->
                    removeConversationForCurrentUser(conv.id)
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
                    val lastFromUid = doc.getString("lastFromUid")

                    // --- NUEVO: cálculo combinado local + remoto para saber si hay no leídos ---

                    // 1) millis del último mensaje
                    val lastTsMillis = lastTs?.toDate()?.time ?: 0L

                    // 2) Lectura LOCAL en este dispositivo
                    val localKey = "last_read_${uid}_$id"
                    val localReadMillis = convoPrefs.getLong(localKey, 0L)

                    // 3) Lectura REMOTA en Firestore (si existe)
                    val myReadTs = doc.getTimestamp("readStatus.$uid")
                    val remoteReadMillis = myReadTs?.toDate()?.time ?: 0L

                    // 4) Lo más avanzado de ambos (el usuario pudo leer en otro dispositivo)
                    val effectiveReadMillis = maxOf(localReadMillis, remoteReadMillis)

                    // 5) Regla final para "NUEVO"
                    val hasUnread = when {
                        lastTs == null -> false               // sin mensajes
                        lastFromUid == uid -> false           // el último lo mandé yo
                        effectiveReadMillis == 0L -> true     // nunca lo leí en ningún lado
                        else -> lastTsMillis > effectiveReadMillis
                    }

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

    private fun refreshLabels() {
        // Ahora el adapter arma las filas, no usamos más Strings planos
        adapter.notifyDataSetChanged()
    }

    private fun removeConversationForCurrentUser(conversationId: String) {
        val uid = currentUid ?: return

        val convRef = db.collection("conversations").document(conversationId)

        // La conversación se oculta SOLO para este usuario usando hiddenFor
        convRef.update("hiddenFor", FieldValue.arrayUnion(uid))
            .addOnSuccessListener {
                loadConversations()
            }
            .addOnFailureListener { e ->
                Toast.makeText(
                    this,
                    "Error al ocultar chat: ${e.localizedMessage}",
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    // -------------------------------------------------------------
    //   Adapter custom para la lista de chats
    // -------------------------------------------------------------
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

            // Hora o fecha (si no es de hoy)
            val ts = item.lastTimestamp
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

                // En la lista de chats suele alcanzar mostrar hora o, si es viejo, solo fecha
                txtTime.text = if (dateStr.isEmpty()) timeStr else dateStr
                txtTime.visibility = View.VISIBLE
            } else {
                txtTime.text = ""
                txtTime.visibility = View.GONE
            }

            // Badge "NUEVO"
            txtUnread.visibility = if (item.hasUnread) View.VISIBLE else View.GONE

            return view
        }
    }
}
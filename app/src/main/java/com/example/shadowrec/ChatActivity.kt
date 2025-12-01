package com.example.shadowrec

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.ktx.Firebase
import com.google.firebase.firestore.ktx.firestore

class ChatActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_OTHER_EMAIL = "extra_other_email"
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

    private var otherUid: String? = null
    private var otherEmail: String? = null
    private var otherDisplayName: String = ""

    private var conversationId: String? = null
    private var messagesListener: ListenerRegistration? = null

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

        otherEmail = intent.getStringExtra(EXTRA_OTHER_EMAIL)
        if (otherEmail.isNullOrBlank()) {
            Toast.makeText(this, "Falta email del destinatario", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        txtChatTitle.text = otherEmail

        // 1) Buscar el usuario destino en la colección "users" por email
        db.collection("users")
            .whereEqualTo("email", otherEmail)
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

                val doc = qs.documents[0]
                otherUid = doc.getString("uid") ?: doc.id
                val firstName = doc.getString("firstName") ?: ""
                val lastName = doc.getString("lastName") ?: ""
                otherDisplayName = (firstName + " " + lastName).trim()

                if (otherDisplayName.isNotEmpty()) {
                    txtChatTitle.text = otherDisplayName
                }

                // 2) Generar ID determinístico de conversación y empezar a escuchar mensajes
                val me = currentUid ?: return@addOnSuccessListener
                val other = otherUid ?: return@addOnSuccessListener
                conversationId = conversationIdFor(me, other)
                startListeningMessages()
            }
            .addOnFailureListener { e ->
                Toast.makeText(
                    this,
                    "Error buscando usuario destino: ${e.localizedMessage}",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }

        btnSend.setOnClickListener {
            sendMessage()
        }
    }

    private fun conversationIdFor(u1: String, u2: String): String {
        return if (u1 < u2) "${u1}_$u2" else "${u2}_$u1"
    }

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
                var hasMessages = false

                if (snapshot != null) {
                    for (doc in snapshot.documents) {
                        val text = doc.getString("text") ?: ""
                        val fromUid = doc.getString("fromUid") ?: ""
                        val label = if (fromUid == currentUid) {
                            "Yo"
                        } else if (otherDisplayName.isNotBlank()) {
                            otherDisplayName
                        } else {
                            otherEmail ?: "Otro"
                        }
                        messages.add("$label: $text")
                    }
                    // 🔧 Corregido: usamos !snapshot.isEmpty en vez de isNotEmpty
                    hasMessages = !snapshot.isEmpty
                }

                adapter.notifyDataSetChanged()
                // scrollear al final
                if (messages.isNotEmpty()) {
                    listMessages.post {
                        listMessages.setSelection(messages.size - 1)
                    }
                }

                // Marcar esta conversación como leída cuando hay mensajes
                if (hasMessages) {
                    markConversationAsRead()
                }
            }
    }

    // Actualizar readStatus[currentUid] con serverTimestamp
    private fun markConversationAsRead() {
        val convId = conversationId ?: return
        val uid = currentUid ?: return

        val update = hashMapOf(
            "readStatus" to hashMapOf(
                uid to FieldValue.serverTimestamp()
            )
        )

        db.collection("conversations")
            .document(convId)
            .set(update, SetOptions.merge())
    }

    private fun sendMessage() {
        val text = edtMessage.text.toString().trim()
        if (text.isEmpty()) return

        val from = currentUid ?: return
        val to = otherUid ?: return
        val convId = conversationId ?: return

        val msg = hashMapOf(
            "fromUid" to from,
            "toUid" to to,
            "text" to text,
            "createdAt" to FieldValue.serverTimestamp()
        )

        // 1) Guardar mensaje en subcolección
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

        // 2) Actualizar resumen de la conversación
        val summary = hashMapOf(
            "participants" to listOf(from, to),
            "participantEmails" to listOfNotNull(currentEmail, otherEmail),
            "lastMessage" to text,
            "lastTimestamp" to FieldValue.serverTimestamp()
        )

        db.collection("conversations")
            .document(convId)
            .set(summary, SetOptions.merge())
    }

    override fun onDestroy() {
        super.onDestroy()
        messagesListener?.remove()
    }
}

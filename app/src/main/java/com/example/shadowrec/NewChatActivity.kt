package com.example.shadowrec

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class NewChatActivity : AppCompatActivity() {

    private lateinit var edtGroupName: EditText
    private lateinit var listUsers: ListView
    private lateinit var progress: ProgressBar
    private lateinit var btnCreate: Button

    private val db by lazy { Firebase.firestore }
    private val auth by lazy { FirebaseAuth.getInstance() }

    private var currentUid: String? = null
    private var currentEmail: String? = null

    data class UserItem(
        val uid: String,
        val name: String,
        val email: String
    )

    private val users = mutableListOf<UserItem>()
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_new_chat)

        edtGroupName = findViewById(R.id.edtGroupName)
        listUsers = findViewById(R.id.listSelectableUsers)
        progress = findViewById(R.id.progressSelectableUsers)
        btnCreate = findViewById(R.id.btnCreateChat)

        val user = auth.currentUser
        if (user == null) {
            Toast.makeText(this, "No hay usuario logueado", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        currentUid = user.uid
        currentEmail = user.email

        adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_multiple_choice,
            mutableListOf<String>()
        )
        listUsers.adapter = adapter
        listUsers.choiceMode = ListView.CHOICE_MODE_MULTIPLE

        loadUsers()

        btnCreate.setOnClickListener {
            createChat()
        }
    }

    private fun loadUsers() {
        progress.visibility = View.VISIBLE

        db.collection("users")
            .get()
            .addOnSuccessListener { qs ->
                users.clear()
                val labels = mutableListOf<String>()
                val meUid = currentUid

                for (doc in qs.documents) {
                    val uid = doc.getString("uid") ?: doc.id
                    if (uid == meUid) continue  // no me agrego a mí mismo, lo hago siempre por código

                    val email = doc.getString("email") ?: ""
                    val first = doc.getString("firstName") ?: ""
                    val last = doc.getString("lastName") ?: ""
                    val name = (first + " " + last).trim().ifEmpty { email }

                    users.add(UserItem(uid, name, email))
                    labels.add("$name\n$email")
                }

                adapter.clear()
                adapter.addAll(labels)
                adapter.notifyDataSetChanged()
            }
            .addOnFailureListener { e ->
                Toast.makeText(
                    this,
                    "Error cargando usuarios: ${e.localizedMessage}",
                    Toast.LENGTH_LONG
                ).show()
            }
            .addOnCompleteListener {
                progress.visibility = View.GONE
            }
    }

    private fun createChat() {
        val meUid = currentUid ?: return
        val meEmail = currentEmail

        // Qué usuarios tildó el usuario en la lista
        val checked = listUsers.checkedItemPositions
        val selectedUsers = mutableListOf<UserItem>()

        for (i in 0 until users.size) {
            if (checked.get(i)) {
                selectedUsers.add(users[i])
            }
        }

        if (selectedUsers.isEmpty()) {
            Toast.makeText(this, "Seleccioná al menos 1 usuario", Toast.LENGTH_SHORT).show()
            return
        }

        // UIDs y emails de TODOS los participantes (yo + seleccionados)
        val allUids = mutableListOf<String>().apply {
            add(meUid)
            addAll(selectedUsers.map { it.uid })
        }

        val allEmails = mutableListOf<String>().apply {
            meEmail?.let { add(it) }
            addAll(selectedUsers.mapNotNull { u ->
                u.email.takeIf { it.isNotBlank() }
            })
        }

        val isGroup = selectedUsers.size > 1
        val groupName = edtGroupName.text.toString().trim()
        val type = if (isGroup) "group" else "direct"

        val titleForIntent: String = if (isGroup) {
            if (groupName.isNotEmpty()) groupName
            else "Grupo sin nombre"
        } else {
            val other = selectedUsers.first()
            other.name.ifBlank { other.email }
        }

        // === CLAVE: ID de la conversación ===
        // - Directo (1 persona): usamos un ID determinístico por pareja de UIDs
        //   → si ya existía, lo reusa; si no, crea uno nuevo con ese mismo ID.
        // - Grupo: dejamos que Firestore genere un ID aleatorio.
        val convDoc = if (!isGroup) {
            val otherUid = selectedUsers.first().uid
            val convId = conversationIdFor(meUid, otherUid)
            db.collection("conversations").document(convId)
        } else {
            db.collection("conversations").document()
        }

        val data = hashMapOf<String, Any?>(
            "type" to type,
            "participants" to allUids,
            "participantEmails" to allEmails,
            "lastMessage" to "",
            "lastTimestamp" to FieldValue.serverTimestamp(),
            "hiddenFor" to emptyList<String>()    // por si usás ocultar chat
        )

        if (isGroup && groupName.isNotEmpty()) {
            data["name"] = groupName
        }

        convDoc.set(data, SetOptions.merge())
            .addOnSuccessListener {
                val convId = convDoc.id

                val i = Intent(this, ChatActivity::class.java).apply {
                    putExtra(ChatActivity.EXTRA_CONVERSATION_ID, convId)
                    putExtra(ChatActivity.EXTRA_IS_GROUP, isGroup)
                    putExtra(ChatActivity.EXTRA_CHAT_TITLE, titleForIntent)
                }
                startActivity(i)
                finish()
            }
            .addOnFailureListener { e ->
                Toast.makeText(
                    this,
                    "Error creando chat: ${e.localizedMessage}",
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    // Un solo ID de conversación para la misma pareja de usuarios
    private fun conversationIdFor(uid1: String, uid2: String): String {
        return if (uid1 < uid2) {
            "${uid1}_${uid2}"
        } else {
            "${uid2}_${uid1}"
        }
    }
}

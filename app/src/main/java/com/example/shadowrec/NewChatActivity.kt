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

        val allUids = mutableListOf<String>()
        val allEmails = mutableListOf<String>()

        allUids.add(meUid)
        meEmail?.let { allEmails.add(it) }

        for (u in selectedUsers) {
            allUids.add(u.uid)
            if (u.email.isNotBlank()) allEmails.add(u.email)
        }

        val isGroup = allUids.size > 2
        val groupName = edtGroupName.text.toString().trim()

        val type = if (isGroup) "group" else "direct"
        val titleForIntent: String = if (isGroup) {
            if (groupName.isNotEmpty()) groupName
            else "Grupo sin nombre"
        } else {
            // Directo: nombre del otro
            selectedUsers.first().name
        }

        val convDoc = db.collection("conversations").document()

        val data = hashMapOf(
            "type" to type,
            "participants" to allUids,
            "participantEmails" to allEmails,
            "lastMessage" to "",
            "lastTimestamp" to FieldValue.serverTimestamp()
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
}

package com.example.shadowrec

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class NewChatActivity : AppCompatActivity() {

    private lateinit var edtGroupName: EditText
    private lateinit var listUsers: ListView
    private lateinit var progress: ProgressBar
    private lateinit var btnCreate: Button
    private var currentUid: String? = null
    private var currentEmail: String? = null

    data class UserItem(
        val uid: String,
        val name: String,
        val email: String
    )

    private val users = mutableListOf<UserItem>()
    private lateinit var adapter: ArrayAdapter<String>
    private val prefs by lazy {
        getSharedPreferences("shadowrec_prefs", MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_new_chat)

        edtGroupName = findViewById(R.id.edtGroupName)
        listUsers = findViewById(R.id.listSelectableUsers)
        progress = findViewById(R.id.progressSelectableUsers)
        btnCreate = findViewById(R.id.btnCreateChat)

        val userId = prefs.getInt("user_id", -1)
        val userEmail = prefs.getString("user_email", null)

        if (userId == -1 || userEmail.isNullOrEmpty()) {
            Toast.makeText(this, "No hay usuario logueado", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        currentUid = userId.toString()
        currentEmail = userEmail

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
        val token = prefs.getString("auth_token", null)

        if (token.isNullOrEmpty()) {
            Toast.makeText(this, "No hay token de sesión", Toast.LENGTH_SHORT).show()
            return
        }

        progress.visibility = View.VISIBLE

        ApiClient.authService.getUsers("Bearer $token")
            .enqueue(object : Callback<UsersResponse> {
                override fun onResponse(
                    call: Call<UsersResponse>,
                    response: Response<UsersResponse>
                ) {
                    progress.visibility = View.GONE

                    if (!response.isSuccessful) {
                        Toast.makeText(
                            this@NewChatActivity,
                            "Error cargando usuarios",
                            Toast.LENGTH_LONG
                        ).show()
                        return
                    }

                    val body = response.body()

                    if (body == null || !body.ok) {
                        Toast.makeText(
                            this@NewChatActivity,
                            "No se pudieron cargar los usuarios",
                            Toast.LENGTH_LONG
                        ).show()
                        return
                    }

                    users.clear()
                    val labels = mutableListOf<String>()

                    for (u in body.users) {
                        val name = "${u.nombre} ${u.apellido}".trim().ifEmpty { u.email }

                        users.add(
                            UserItem(
                                uid = u.id.toString(),
                                name = name,
                                email = u.email
                            )
                        )

                        labels.add("$name\n${u.email}")
                    }

                    adapter.clear()
                    adapter.addAll(labels)
                    adapter.notifyDataSetChanged()
                }

                override fun onFailure(call: Call<UsersResponse>, t: Throwable) {
                    progress.visibility = View.GONE
                    Toast.makeText(
                        this@NewChatActivity,
                        "Error de conexión: ${t.localizedMessage}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            })
    }

    private fun createChat() {

        val token = prefs.getString("auth_token", null)

        if (token.isNullOrEmpty()) {
            Toast.makeText(this, "No hay token de sesión", Toast.LENGTH_SHORT).show()
            return
        }

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

        val isGroup = selectedUsers.size > 1
        val groupName = edtGroupName.text.toString().trim()

        val titleForIntent = if (isGroup) {
            if (groupName.isNotEmpty()) groupName else "Grupo sin nombre"
        } else {
            val other = selectedUsers.first()
            other.name.ifBlank { other.email }
        }

        val request = CreateConversationRequest(
            emails = selectedUsers.map { it.email },
            nombre = if (isGroup && groupName.isNotEmpty()) groupName else null
        )

        ApiClient.authService.createConversation("Bearer $token", request)
            .enqueue(object : Callback<CreateConversationResponse> {
                override fun onResponse(
                    call: Call<CreateConversationResponse>,
                    response: Response<CreateConversationResponse>
                ) {
                    if (!response.isSuccessful) {
                        Toast.makeText(
                            this@NewChatActivity,
                            "Error creando chat",
                            Toast.LENGTH_LONG
                        ).show()
                        return
                    }

                    val body = response.body()

                    if (body == null || !body.ok) {
                        Toast.makeText(
                            this@NewChatActivity,
                            "No se pudo crear el chat",
                            Toast.LENGTH_LONG
                        ).show()
                        return
                    }

                    Toast.makeText(
                        this@NewChatActivity,
                        "Chat listo",
                        Toast.LENGTH_SHORT
                    ).show()

                    val i = Intent(this@NewChatActivity, ChatActivity::class.java).apply {
                        putExtra(ChatActivity.EXTRA_CONVERSATION_ID, body.conversacion_id.toString())
                        putExtra(ChatActivity.EXTRA_IS_GROUP, isGroup)
                        putExtra(ChatActivity.EXTRA_CHAT_TITLE, titleForIntent)
                    }
                    startActivity(i)
                    finish()
                }

                override fun onFailure(call: Call<CreateConversationResponse>, t: Throwable) {
                    Toast.makeText(
                        this@NewChatActivity,
                        "Error de conexión: ${t.localizedMessage}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            })
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

package com.example.shadowrec

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class UsersActivity : AppCompatActivity() {

    private lateinit var listUsers: ListView
    private lateinit var progressUsers: ProgressBar
    private lateinit var adapter: ArrayAdapter<String>

    private val db by lazy { Firebase.firestore }

    // Modelo simple para la lista
    private val users = mutableListOf<UserItem>()

    data class UserItem(
        val uid: String,
        val name: String,
        val email: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_users)

        listUsers = findViewById(R.id.listUsers)
        progressUsers = findViewById(R.id.progressUsers)

        adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            mutableListOf<String>()
        )
        listUsers.adapter = adapter

        // Cargar usuarios desde Firestore
        loadUsers()

        // Al hacer tap en un usuario → abrir ChatActivity con su email
        listUsers.setOnItemClickListener { _, _, position, _ ->
            val user = users[position]
            val i = Intent(this, ChatActivity::class.java).apply {
                putExtra(ChatActivity.EXTRA_OTHER_EMAIL, user.email)
            }
            startActivity(i)
        }
    }

    private fun loadUsers() {
        progressUsers.visibility = View.VISIBLE

        db.collection("users")
            .orderBy("firstName") // si no existe en todos, no pasa nada grave
            .get()
            .addOnSuccessListener { qs ->
                users.clear()
                val labels = mutableListOf<String>()

                for (doc in qs.documents) {
                    val uid = doc.getString("uid") ?: doc.id
                    val first = doc.getString("firstName") ?: ""
                    val last = doc.getString("lastName") ?: ""
                    val email = doc.getString("email") ?: ""
                    val name = (first + " " + last).trim().ifEmpty { email }

                    users.add(UserItem(uid, name, email))
                    // Lo que se ve en la lista
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
                progressUsers.visibility = View.GONE
            }
    }
}

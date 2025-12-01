package com.example.shadowrec

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth           // === NUEVO
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.Timestamp                 // === NUEVO

class UsersActivity : AppCompatActivity() {

    private lateinit var listUsers: ListView
    private lateinit var progressUsers: ProgressBar
    private lateinit var adapter: ArrayAdapter<String>

    private val db by lazy { Firebase.firestore }
    private val auth by lazy { FirebaseAuth.getInstance() }   // === NUEVO

    private var currentUid: String? = null                    // === NUEVO

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

        val user = auth.currentUser                      // === NUEVO
        if (user == null) {                              // === NUEVO
            Toast.makeText(this, "No hay usuario logueado", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        currentUid = user.uid                            // === NUEVO

        // Cargar usuarios desde Firestore
        loadUsers()

        // Al hacer tap en un usuario → abrir ChatActivity con su email
        listUsers.setOnItemClickListener { _, _, position, _ ->
            val userItem = users[position]
            val i = Intent(this, ChatActivity::class.java).apply {
                putExtra(ChatActivity.EXTRA_OTHER_EMAIL, userItem.email)
            }
            startActivity(i)
        }
    }

    override fun onResume() {
        super.onResume()
        // Cada vez que volvés a esta pantalla, recargamos la lista
        loadUsers()
    }

    private fun loadUsers() {
        progressUsers.visibility = View.VISIBLE

        db.collection("users")
            .orderBy("firstName") // si no existe en todos, no pasa nada grave
            .get()
            .addOnSuccessListener { qs ->
                users.clear()

                for (doc in qs.documents) {
                    val uid = doc.getString("uid") ?: doc.id
                    val first = doc.getString("firstName") ?: ""
                    val last = doc.getString("lastName") ?: ""
                    val email = doc.getString("email") ?: ""
                    val name = (first + " " + last).trim().ifEmpty { email }

                    users.add(UserItem(uid, name, email))
                }

                // Una vez que tenemos la lista de usuarios, consultamos las conversaciones
                loadUnreadStatusForUsers()               // === NUEVO
            }
            .addOnFailureListener { e ->
                Toast.makeText(
                    this,
                    "Error cargando usuarios: ${e.localizedMessage}",
                    Toast.LENGTH_LONG
                ).show()

                // Si falla, igual mostramos la lista sin indicadores
                updateUserLabels(emptyMap())            // === NUEVO
            }
            .addOnCompleteListener {
                progressUsers.visibility = View.GONE
            }
    }

    // === NUEVO: cargar conversaciones y calcular qué usuarios tienen mensajes sin leer
    private fun loadUnreadStatusForUsers() {
        val uid = currentUid ?: run {
            updateUserLabels(emptyMap())
            return
        }

        db.collection("conversations")
            .whereArrayContains("participants", uid)
            .get()
            .addOnSuccessListener { qs ->
                val unreadByOther = mutableMapOf<String, Boolean>()

                for (doc in qs.documents) {
                    val participants = doc.get("participants") as? List<*> ?: continue
                    if (participants.size < 2) continue

                    val u1 = participants.getOrNull(0) as? String
                    val u2 = participants.getOrNull(1) as? String

                    // identificamos el "otro" participante (no currentUid)
                    val otherUid =
                        when (uid) {
                            u1 -> u2
                            u2 -> u1
                            else -> null
                        } ?: continue

                    val lastTs = doc.getTimestamp("lastTimestamp")

                    @Suppress("UNCHECKED_CAST")
                    val readStatusMap = doc.get("readStatus") as? Map<String, Any?>
                    val myReadTs = (readStatusMap?.get(uid) as? Timestamp)

                    val hasUnread = lastTs != null && (myReadTs == null || lastTs > myReadTs)

                    if (hasUnread) {
                        unreadByOther[otherUid] = true
                    }
                }

                updateUserLabels(unreadByOther)
            }
            .addOnFailureListener { e ->
                Toast.makeText(
                    this,
                    "Error leyendo conversaciones: ${e.localizedMessage}",
                    Toast.LENGTH_SHORT
                ).show()
                updateUserLabels(emptyMap())
            }
    }

    // === NUEVO: reconstruir los textos de la lista, agregando “🔴 NUEVO” donde corresponda
    private fun updateUserLabels(unreadByOther: Map<String, Boolean>) {
        val labels = mutableListOf<String>()

        for (u in users) {
            val hasUnread = unreadByOther[u.uid] == true
            val suffix = if (hasUnread) "\n🔴 NUEVO" else ""
            labels.add("${u.name}\n${u.email}$suffix")
        }

        adapter.clear()
        adapter.addAll(labels)
        adapter.notifyDataSetChanged()
    }
}

package com.example.shadowrec

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class AuthActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private val db by lazy { Firebase.firestore }

    private val prefs by lazy {
        getSharedPreferences("shadowrec_prefs", MODE_PRIVATE)
    }

    private lateinit var edtEmail: EditText
    private lateinit var edtPassword: EditText
    private lateinit var btnLogin: Button
    private lateinit var txtGoToRegister: TextView    // link “Crear cuenta”

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auth)

        auth = FirebaseAuth.getInstance()

        edtEmail = findViewById(R.id.edtEmail)
        edtPassword = findViewById(R.id.edtPassword)
        btnLogin = findViewById(R.id.btnLogin)
        txtGoToRegister = findViewById(R.id.txtGoToRegister)

        // Ingresar
        btnLogin.setOnClickListener { loginUser() }

        txtGoToRegister.setOnClickListener {
            val i = Intent(this, RegisterActivity::class.java)
            startActivity(i)
        }

    }

    private fun loginUser() {
        val email = edtEmail.text.toString().trim()
        val password = edtPassword.text.toString().trim()

        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(
                this,
                "Ingresá email y contraseña para iniciar sesión",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        auth.signInWithEmailAndPassword(email, password)
            .addOnSuccessListener { result ->
                val user = result.user
                val uid = user?.uid

                if (uid == null) {
                    Toast.makeText(this, "No se pudo obtener el usuario", Toast.LENGTH_LONG).show()
                    return@addOnSuccessListener
                }

                // Leemos el perfil en users/{uid} (nuevo esquema por uid)
                db.collection("users")
                    .document(uid)
                    .get()
                    .addOnSuccessListener { doc ->
                        val firstName = doc.getString("firstName") ?: ""
                        val lastName = doc.getString("lastName") ?: ""
                        val emailSaved = doc.getString("email") ?: email

                        // Guardamos datos básicos en prefs
                        prefs.edit()
                            .putString("user_first_name", firstName)
                            .putString("user_last_name", lastName)
                            .putString("user_email", emailSaved)
                            .putBoolean("user_profile_complete", true)
                            .apply()

                        Toast.makeText(this, "Sesión iniciada", Toast.LENGTH_SHORT).show()

                        // Ir a MainActivity y limpiar el backstack
                        val i = Intent(this, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                        startActivity(i)
                        finish()
                    }
                    .addOnFailureListener {
                        // Si falla la lectura, igual marcamos la sesión como iniciada
                        prefs.edit()
                            .putBoolean("user_profile_complete", true)
                            .apply()

                        Toast.makeText(
                            this,
                            "Sesión iniciada (sin cargar perfil completo)",
                            Toast.LENGTH_SHORT
                        ).show()

                        val i = Intent(this, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                        startActivity(i)
                        finish()
                    }
            }
            .addOnFailureListener { e ->
                Toast.makeText(
                    this,
                    "Error al iniciar sesión: ${e.localizedMessage}",
                    Toast.LENGTH_LONG
                ).show()
            }
    }
}

package com.example.shadowrec

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.userProfileChangeRequest
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class RegisterActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private val db by lazy { Firebase.firestore }

    private val prefs by lazy {
        getSharedPreferences("shadowrec_prefs", MODE_PRIVATE)
    }

    private lateinit var edtFirstName: EditText
    private lateinit var edtLastName: EditText
    private lateinit var edtEmail: EditText
    private lateinit var edtPassword: EditText
    private lateinit var btnRegister: Button
    private lateinit var txtGoToLogin: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        auth = FirebaseAuth.getInstance()

        edtFirstName = findViewById(R.id.edtFirstName)
        edtLastName = findViewById(R.id.edtLastName)
        edtEmail = findViewById(R.id.edtEmail)
        edtPassword = findViewById(R.id.edtPassword)
        btnRegister = findViewById(R.id.btnRegister)
        txtGoToLogin = findViewById(R.id.txtGoToLogin)

        btnRegister.setOnClickListener { registerUser() }

        txtGoToLogin.setOnClickListener {
            // Volver a pantalla de login
            val i = Intent(this, AuthActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(i)
            finish()
        }
    }

    private fun registerUser() {
        val firstName = edtFirstName.text.toString().trim()
        val lastName = edtLastName.text.toString().trim()
        val email = edtEmail.text.toString().trim()
        val password = edtPassword.text.toString().trim()

        if (firstName.isEmpty() || lastName.isEmpty() ||
            email.isEmpty() || password.isEmpty()
        ) {
            Toast.makeText(
                this,
                "Completa todos los campos para registrarte",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (password.length < 6) {
            Toast.makeText(
                this,
                "La contraseña debe tener al menos 6 caracteres",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        auth.createUserWithEmailAndPassword(email, password)
            .addOnSuccessListener { result ->
                val user = result.user
                val uid = user?.uid ?: ""

                // 1) Setear displayName en Firebase Auth
                if (user != null) {
                    val fullName = "$firstName $lastName".trim()
                    val profileUpdates = userProfileChangeRequest {
                        displayName = fullName
                    }
                    user.updateProfile(profileUpdates)
                }

                // 2) Guardar perfil en users/{uid}  (👉 ya no por deviceId)
                val userMap = hashMapOf(
                    "uid" to uid,
                    "firstName" to firstName,
                    "lastName" to lastName,
                    "email" to email,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )

                db.collection("users")
                    .document(uid)
                    .set(userMap, SetOptions.merge())
                    .addOnSuccessListener {
                        // 3) Guardar datos básicos en prefs
                        prefs.edit()
                            .putString("user_first_name", firstName)
                            .putString("user_last_name", lastName)
                            .putString("user_email", email)
                            .putBoolean("user_profile_complete", true)
                            .apply()

                        Toast.makeText(
                            this,
                            "Usuario registrado correctamente",
                            Toast.LENGTH_SHORT
                        ).show()

                        // 4) Ir directo a MainActivity
                        val i = Intent(this, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                        startActivity(i)
                        finish()
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(
                            this,
                            "Error guardando perfil: ${e.localizedMessage}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
            }
            .addOnFailureListener { e ->
                Toast.makeText(
                    this,
                    "Error al registrar: ${e.localizedMessage}",
                    Toast.LENGTH_LONG
                ).show()
            }
    }
}

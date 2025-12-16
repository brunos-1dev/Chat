package com.example.shadowrec

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.userProfileChangeRequest
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class AuthActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private val db by lazy { Firebase.firestore }

    // mismo deviceId que usás en el servicio y en MainActivity
    private val deviceId by lazy {
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
    }

    // mismo prefs que ya usás: "shadowrec_prefs"
    private val prefs by lazy {
        getSharedPreferences("shadowrec_prefs", Context.MODE_PRIVATE)
    }

    private lateinit var edtFirstName: EditText
    private lateinit var edtLastName: EditText
    private lateinit var edtEmail: EditText
    private lateinit var edtPassword: EditText
    private lateinit var btnRegister: Button
    private lateinit var btnLogin: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auth)

        auth = FirebaseAuth.getInstance()

        edtFirstName = findViewById(R.id.edtFirstName)
        edtLastName = findViewById(R.id.edtLastName)
        edtEmail = findViewById(R.id.edtEmail)
        edtPassword = findViewById(R.id.edtPassword)
        btnRegister = findViewById(R.id.btnRegister)
        btnLogin = findViewById(R.id.btnLogin)

        btnRegister.setOnClickListener { registerUser() }
        btnLogin.setOnClickListener { loginUser() }
    }

    // ---------- REGISTRO ----------

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

                // 2) Documento users/{deviceId} en Firestore (SIN contraseña)
                val userMap = hashMapOf(
                    "uid" to uid,
                    "deviceId" to deviceId,
                    "firstName" to firstName,
                    "lastName" to lastName,
                    "email" to email,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )

                db.collection("users")
                    .document(deviceId)
                    .set(userMap, SetOptions.merge())
                    .addOnSuccessListener {
                        // Guardamos algunos datos básicos en prefs
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

                        // 👉 Ir a MainActivity después de registrar
                        goToMainAndFinish()
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

    // ---------- LOGIN ----------

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
            .addOnSuccessListener {
                // Traemos el perfil para llenar prefs si existe
                db.collection("users")
                    .document(deviceId)
                    .get()
                    .addOnSuccessListener { doc ->
                        val firstName = doc.getString("firstName") ?: ""
                        val lastName = doc.getString("lastName") ?: ""
                        val emailSaved = doc.getString("email") ?: email

                        prefs.edit()
                            .putString("user_first_name", firstName)
                            .putString("user_last_name", lastName)
                            .putString("user_email", emailSaved)
                            .putBoolean("user_profile_complete", true)
                            .apply()

                        Toast.makeText(this, "Sesión iniciada", Toast.LENGTH_SHORT).show()

                        // 👉 Ir a MainActivity después de iniciar sesión
                        goToMainAndFinish()
                    }
                    .addOnFailureListener {
                        // Si falla la lectura pero login fue OK, igual marcamos como logueado
                        prefs.edit()
                            .putBoolean("user_profile_complete", true)
                            .apply()
                        Toast.makeText(
                            this,
                            "Sesión iniciada (sin cargar perfil)",
                            Toast.LENGTH_SHORT
                        ).show()

                        // 👉 También redirigimos a MainActivity aquí
                        goToMainAndFinish()
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

    // ---------- NAVEGAR A MAIN Y CERRAR AUTH ----------

    private fun goToMainAndFinish() {
        val intent = Intent(this, MainActivity::class.java).apply {
            // limpiamos la pila para que Auth no quede atrás
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        finish()
    }
}

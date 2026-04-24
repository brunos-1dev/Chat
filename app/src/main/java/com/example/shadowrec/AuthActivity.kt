package com.example.shadowrec

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class AuthActivity : AppCompatActivity() {

    private val prefs by lazy {
        getSharedPreferences("shadowrec_prefs", MODE_PRIVATE)
    }

    private lateinit var edtEmail: EditText
    private lateinit var edtPassword: EditText
    private lateinit var btnLogin: Button
    private lateinit var txtGoToRegister: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auth)

        edtEmail = findViewById(R.id.edtEmail)
        edtPassword = findViewById(R.id.edtPassword)
        btnLogin = findViewById(R.id.btnLogin)
        txtGoToRegister = findViewById(R.id.txtGoToRegister)

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

        val request = LoginRequest(
            email = email,
            password = password
        )

        ApiClient.authService.login(request).enqueue(object : Callback<LoginResponse> {
            override fun onResponse(
                call: Call<LoginResponse>,
                response: Response<LoginResponse>
            ) {
                if (!response.isSuccessful) {
                    Toast.makeText(
                        this@AuthActivity,
                        "Error al iniciar sesión",
                        Toast.LENGTH_LONG
                    ).show()
                    return
                }

                val body = response.body()

                if (body == null || !body.ok || body.usuario == null || body.token == null) {
                    Toast.makeText(
                        this@AuthActivity,
                        body?.message ?: "Credenciales inválidas",
                        Toast.LENGTH_LONG
                    ).show()
                    return
                }

                val usuario = body.usuario

                prefs.edit()
                    .putString("auth_token", body.token)
                    .putInt("user_id", usuario.id)
                    .putString("user_first_name", usuario.nombre)
                    .putString("user_last_name", usuario.apellido)
                    .putString("user_email", usuario.email)
                    .putString("device_id", usuario.device_id)
                    .putBoolean("user_profile_complete", true)
                    .apply()

                Toast.makeText(
                    this@AuthActivity,
                    "Sesión iniciada",
                    Toast.LENGTH_SHORT
                ).show()

                val i = Intent(this@AuthActivity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(i)
                finish()
            }

            override fun onFailure(call: Call<LoginResponse>, t: Throwable) {
                Toast.makeText(
                    this@AuthActivity,
                    "Error de conexión: ${t.localizedMessage}",
                    Toast.LENGTH_LONG
                ).show()
            }
        })
    }
}
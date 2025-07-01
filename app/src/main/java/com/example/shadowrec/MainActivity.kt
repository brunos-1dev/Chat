package com.example.shadowrec
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.example.shadowrec.R

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val buttonCamera = findViewById<Button>(R.id.buttonCamera)
        val buttonVoice = findViewById<Button>(R.id.buttonVoice)

        buttonCamera.setOnClickListener {
            // TODO: abrir pantalla de configuración de cámara
        }

        buttonVoice.setOnClickListener {
            // TODO: abrir pantalla de configuración de voz
        }
    }
}

package com.example.cienmexicanosdijeron

import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.example.cienmexicanosdijeron.databinding.ActivityInstructionsBinding

class InstructionsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInstructionsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInstructionsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Pantalla completa
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        // Lógica del botón de Regresar
        binding.btnBack.setOnClickListener {
            finish() // Cierra esta actividad y vuelve al menú
        }
    }

    // Manejo de la música del menú
    override fun onResume() {
        super.onResume()
        MusicManager.resume()
    }

    override fun onPause() {
        super.onPause()
        MusicManager.pause()
    }
}
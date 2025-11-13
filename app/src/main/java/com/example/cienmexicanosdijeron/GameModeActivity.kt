package com.example.cienmexicanosdijeron

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.cienmexicanosdijeron.databinding.ActivityGameModeBinding

class GameModeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGameModeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGameModeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Pantalla completa
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        setupClickListeners()
    }

    private fun setupClickListeners() {
        // Botón Offline (por ahora un mensaje)
        binding.btnOffline.setOnClickListener {
            // Aquí iría la lógica para el juego offline
            MusicManager.stop()
            val intent = Intent(this, SpinWheelActivity::class.java)
            startActivity(intent)
        }

        // Botón Multijugador (nos lleva al Lobby)
        binding.btnMultiplayer.setOnClickListener {
            MusicManager.stop()
            val intent = Intent(this, MultiplayerLobbyActivity::class.java)
            startActivity(intent)
        }
    }

    // Manejo de música para que no se corte
    override fun onResume() {
        super.onResume()
        MusicManager.resume()
    }

    override fun onPause() {
        super.onPause()
        if (!isFinishing) {
            MusicManager.pause()
        }
    }
}
package com.example.cienmexicanosdijeron

import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.example.cienmexicanosdijeron.databinding.ActivityMultiplayerLobbyBinding

class MultiplayerLobbyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMultiplayerLobbyBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMultiplayerLobbyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Pantalla completa
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
    }

    // Manejo de música
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
package com.example.cienmexicanosdijeron

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.cienmexicanosdijeron.databinding.ActivityNameInputBinding

class NameInputActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNameInputBinding
    private var menuMusicPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNameInputBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1. INICIA LA NUEVA MÚSICA DE FONDO (en loop)
        startMenuMusic()

        // 2. LÓGICA DEL BOTÓN GUARDAR
        binding.btnSaveName.setOnClickListener {
            val playerName = binding.etPlayerName.text.toString().trim()

            if (playerName.isNotEmpty()) {
                // Si el nombre no está vacío, lo guardamos
                savePlayerName(playerName)

                // Y vamos al menú principal del juego
                goToMainActivity()
            } else {
                // Si está vacío, pedimos que lo escriba
                Toast.makeText(this, "Por favor, escribe un nombre", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startMenuMusic() {
        // Usa el MusicManager para iniciar la música
        MusicManager.start(this, R.raw.fondo_juego) // CAMBIA por tu música
    }

    private fun savePlayerName(name: String) {
        // SharedPreferences es el almacenamiento persistente perfecto para esto
        val sharedPreferences = getSharedPreferences("GamePrefs", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        editor.putString("PlayerName", name)
        editor.apply() // .apply() lo guarda en segundo plano
    }

    private fun goToMainActivity() {
        // Detenemos la música del menú
        // (Si quieres que la música siga en MainActivity, me dices y lo cambiamos)
        //menuMusicPlayer?.stop()
        //menuMusicPlayer?.release()
        //menuMusicPlayer = null

        // Inicia la actividad principal
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }

    override fun onStop() {
        super.onStop()
        // En lugar de parar, solo pausamos
        if (!isFinishing) {
            MusicManager.pause()
        }
    }

    override fun onStart() {
        super.onStart()
        MusicManager.resume()
    }
}
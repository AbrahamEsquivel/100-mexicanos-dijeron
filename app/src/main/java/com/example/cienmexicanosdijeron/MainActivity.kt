package com.example.cienmexicanosdijeron

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.example.cienmexicanosdijeron.databinding.ActivityMainBinding

// --- ¡¡IMPORTS AÑADIDOS!! ---
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
// ------------------------------

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ViewBinding primero
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // --- Pantalla completa simple ---
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        // Poner nombre del jugador
        loadPlayerName()

        // Botones
        setupButtons()

        // GIF central (opcional)
        loadAnimatedGif()

        // ¡Ahora esto funcionará!
        animateMenu()
    }

    private fun animateMenu() {
        val buttons = listOf(
            binding.btnRuleta,
            binding.btnVip,
            binding.btnMisiones,
            binding.btnRacha,
            binding.btnCofre,
            binding.btnEventos
        )

        // 1. Oculta todos los botones al inicio
        for (button in buttons) {
            button.scaleX = 0f
            button.scaleY = 0f
        }

        // 2. Anímalos uno por uno
        lifecycleScope.launch {
            for (button in buttons) {
                delay(100) // Un pequeño retraso entre cada botón
                button.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(300)
                    .start()
            }
        }
    }

    // CÓDIGO CORREGIDO
    private fun loadPlayerName() {
        val sp = getSharedPreferences("GamePrefs", Context.MODE_PRIVATE)
        val playerName = sp.getString("PlayerName", "Jugador") ?: "Jugador"
        binding.tvUsername.text = "@$playerName"
    }

    private fun setupButtons() {
        // Botón JUGAR
        binding.btnJugar.setOnClickListener {
            startActivity(Intent(this, GameModeActivity::class.java))
        }

        // Botones del menu
        binding.btnRuleta.setOnClickListener {
            toast("Ruleta de la Suerte — Próximamente")
        }

        binding.btnVip.setOnClickListener {
            toast("VIP — Próximamente")
        }



        binding.btnMisiones.setOnClickListener {
            toast("Misiones — Próximamente")
        }

        binding.btnRacha.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        binding.btnCofre.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        binding.btnEventos.setOnClickListener {
            startActivity(Intent(this, InstructionsActivity::class.java))
        }

        // Configuración
        binding.ivSettings.setOnClickListener {
            toast("Configuración — Próximamente")
        }
    }

    private fun loadAnimatedGif() {
        try {
            Glide.with(this)
                .asGif()
                .load(R.drawable.personaje) // Cambia por el nombre de tu GIF
                .into(binding.ivCentralGif)
        } catch (e: Exception) {
            // Si hay error, muestra una imagen estática o oculta
            binding.ivCentralGif.setImageResource(R.drawable.logo) // fallback
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // Música de fondo
    override fun onResume() {
        super.onResume()
        MusicManager.resume()
    }

    override fun onPause() {
        super.onPause()
        MusicManager.pause()
    }
}
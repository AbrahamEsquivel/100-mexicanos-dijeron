package com.example.cienmexicanosdijeron

import androidx.appcompat.app.AlertDialog
import android.content.Intent
import android.widget.Button
import android.widget.TextView
import android.content.Context
import android.media.MediaPlayer
import android.os.Bundle
import android.view.WindowManager
import android.view.animation.Animation
import android.view.animation.DecelerateInterpolator
import android.view.animation.RotateAnimation
import android.widget.Toast
import com.example.cienmexicanosdijeron.databinding.ActivitySpinWheelBinding
import kotlin.random.Random
import androidx.appcompat.app.AppCompatActivity

// ¡¡NUEVOS IMPORTS!!
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SpinWheelActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySpinWheelBinding

    private var currentAngle = 0f
    private var isSpinning = false
    private var spinSoundPlayer: MediaPlayer? = null

    // ¡¡NUEVAS VARIABLES PARA MULTIJUGADOR!!
    private var isMultiplayer = false
    private var isHost = false
    private var opponentName = "Bot"
    private var clientListenerJob: Job? = null // El "oído" del Cliente

    private val categories = listOf(
        "Objetos",     // 1. Rojo (Zapato)
        "Comida",       // 2. Rosa (Cupcake)
        "Cultura Pop",  // 3. Morado (Corona)
        "Geografía",    // 4. Azul (Mundo)
        "Ciencia",      // 5. Verde (Tubo de ensayo)
        "Arte",         // 6. Amarillo (Plumón)
        "Deportes"    // 7. Naranja (Balón)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySpinWheelBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        // ¡¡NUEVO!! Leemos los datos que nos mandó el Lobby
        isMultiplayer = intent.getBooleanExtra("IS_MULTIPLAYER", false)
        isHost = intent.getBooleanExtra("IS_HOST", false)
        opponentName = intent.getStringExtra("OPPONENT_NAME") ?: "Bot"

        // Modificamos el header y el botón de girar
        loadHeaderInfo()
        setupSpinButton()

        // ¡¡NUEVO!! Si somos el Cliente, empezamos a escuchar
        if (isMultiplayer && !isHost) {
            startClientListener()
        }
    }

    /**
     * MODIFICADA: Muestra el nombre del Oponente (si es multi)
     * o un Bot (si es offline).
     */
    private fun loadHeaderInfo() {
        val sp = getSharedPreferences("GamePrefs", Context.MODE_PRIVATE)
        val playerName = sp.getString("PlayerName", "Jugador") ?: "Jugador"
        binding.tvPlayerName.text = "@$playerName"

        // ¡¡LÓGICA HÍBRIDA!!
        if (isMultiplayer) {
            // Si es multi, el oponente es el que nos pasó el Intent
            binding.tvMachineName.text = "@$opponentName"
        } else {
            // Si es offline, generamos un bot random (como antes)
            val machineNames = listOf("@Geminis", "@ChatGtp", "@DeepSeek", "@Cloude", "@CPU_Bot","CloudeAi","CecyAi","Copilot")
            binding.tvMachineName.text = machineNames.random()
        }
    }

    /**
     * NUEVA: Decide qué hace el botón de "Girar"
     */
    private fun setupSpinButton() {
        binding.btnGirar.setOnClickListener {
            if (isSpinning) return@setOnClickListener // No hagas nada si ya está girando

            // ¡¡LÓGICA HÍBRIDA!!
            if (!isMultiplayer) {
                // MODO OFFLINE: Cualquiera puede girar
                spinWheel()
            } else if (isHost) {
                // MODO ONLINE (Host): El Host puede girar
                spinWheel()
            } else {
                // MODO ONLINE (Cliente): El Cliente NO puede girar
                toast("Esperando a que el Host gire la ruleta...")
            }
        }
    }

    /**
     * MODIFICADA: Ahora envía el resultado al Cliente si es el Host
     */
    private fun spinWheel() {
        isSpinning = true
        binding.btnGirar.isEnabled = false // Desactivamos el botón

        val degreesPerCategory = 360f / categories.size
        val targetIndex = Random.nextInt(categories.size)
        val targetCategory = categories[targetIndex]

        val targetAngle = 360f - (targetIndex * degreesPerCategory) - (degreesPerCategory / 2)
        val finalRotation = (5 * 360f) + targetAngle - (currentAngle % 360)

        val animation = RotateAnimation(
            currentAngle,
            finalRotation,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        )
        animation.duration = 4000
        animation.interpolator = DecelerateInterpolator()
        animation.fillAfter = true

        currentAngle = finalRotation % 360

        // Inicia el sonido (como antes)
        try {
            spinSoundPlayer?.stop()
            spinSoundPlayer?.release()
        } catch (e: Exception) {}
        spinSoundPlayer = MediaPlayer.create(this, R.raw.ruleta)
        spinSoundPlayer?.isLooping = true
        spinSoundPlayer?.start()

        animation.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation?) {}

            override fun onAnimationEnd(animation: Animation?) {
                // Para el sonido (como antes)
                try {
                    spinSoundPlayer?.stop()
                    spinSoundPlayer?.release()
                    spinSoundPlayer = null
                } catch (e: Exception) {}

                // ¡¡NUEVA LÓGICA DE HOST!!
                if (isMultiplayer && isHost) {
                    // Si soy el Host, le AVISO al cliente qué salió
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            // Le mandamos el nombre de la categoría
                            ConnectionManager.dataOut?.writeUTF("CATEGORY:$targetCategory")
                            ConnectionManager.dataOut?.flush()
                        } catch (e: Exception) {
                            e.printStackTrace()
                            // (Manejar error de desconexión)
                        }
                    }
                }

                // TODOS (Offline y Host) van a la siguiente pantalla
                showCategoryDialog(targetCategory)

                // No reactivamos el botón, nos vamos de pantalla
            }

            override fun onAnimationRepeat(animation: Animation?) {}
        })

        binding.ivRuletaBase.startAnimation(animation)
    }

    /**
     * NUEVA: La función "oído" del Cliente
     */
    private fun startClientListener() {
        // Desactivamos el botón de girar
        binding.btnGirar.isEnabled = false
        binding.btnGirar.alpha = 0.5f // Lo hacemos semi-transparente
        toast("Esperando al Host...")

        clientListenerJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Nos quedamos atorados aquí hasta que el Host mande algo
                val command = ConnectionManager.dataIn?.readUTF()

                if (command != null && command.startsWith("CATEGORY:")) {
                    // ¡Recibimos la categoría!
                    val category = command.substringAfter(":")

                    // Saltamos al hilo principal para ir a la siguiente pantalla
                    withContext(Dispatchers.Main) {
                        toast("¡El Host eligió: $category!")
                        showCategoryDialog(category)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    toast("¡Error de conexión con el Host!")
                    // TODO: Mandar al usuario de vuelta al menú
                }
            }
        }
    }

    /**
     * MODIFICADA: Ahora pasa la info de Multi a la GamePlayActivity
     */
    private fun showCategoryDialog(category: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_category_selected, null)
        val builder = AlertDialog.Builder(this)
        builder.setView(dialogView)
        val dialog = builder.create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val tvCategoryName = dialogView.findViewById<TextView>(R.id.tvCategoryName)
        val btnStartGame = dialogView.findViewById<Button>(R.id.btnStartGame)

        tvCategoryName.text = category

        btnStartGame.setOnClickListener {
            dialog.dismiss()

            val intent = Intent(this, GamePlayActivity::class.java)

            // ¡¡PASAMOS TODA LA INFO!!
            intent.putExtra("SELECTED_CATEGORY", category)
            intent.putExtra("IS_MULTIPLAYER", isMultiplayer)
            intent.putExtra("IS_HOST", isHost)
            intent.putExtra("OPPONENT_NAME", opponentName)

            startActivity(intent)
            finish()
        }

        dialog.setCancelable(false) // No dejamos que lo cierren
        dialog.show()
    }

    // NUEVA
    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onStop() {
        super.onStop()
        try {
            spinSoundPlayer?.stop()
            spinSoundPlayer?.release()
            spinSoundPlayer = null
        } catch (e: Exception) {}

        // ¡¡NUEVO!! Si somos el cliente, cancelamos el "oído"
        clientListenerJob?.cancel()
    }
}
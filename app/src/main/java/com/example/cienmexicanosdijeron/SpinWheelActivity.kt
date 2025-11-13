package com.example.cienmexicanosdijeron

import androidx.appcompat.app.AlertDialog
import android.content.Intent
import android.widget.Button
import android.widget.TextView
import android.media.MediaPlayer
import android.content.Context
import android.os.Bundle

import android.view.WindowManager
import android.view.animation.Animation
import android.view.animation.DecelerateInterpolator
import android.view.animation.RotateAnimation

import com.example.cienmexicanosdijeron.databinding.ActivitySpinWheelBinding
import kotlin.random.Random
import androidx.appcompat.app.AppCompatActivity

class SpinWheelActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySpinWheelBinding

    private var currentAngle = 0f
    private var isSpinning = false

    private var spinSoundPlayer: MediaPlayer? = null

    private var botName: String = "" // NUEVA VARIABLE DE CLASE

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

        loadHeaderInfo()

        binding.btnGirar.setOnClickListener {
            if (!isSpinning) {
                spinWheel()
            }
        }
    }

    private fun loadHeaderInfo() {
        val sp = getSharedPreferences("GamePrefs", Context.MODE_PRIVATE)
        val playerName = sp.getString("PlayerName", "Jugador") ?: "Jugador"
        binding.tvPlayerName.text = "@$playerName"

        val machineNames = listOf("@Geminis", "@ChatGtp", "@DeepSeek", "@Cloude", "@CPU_Bot","CloudeAi","CecyAi","Copilot")
        botName = machineNames.random()
        binding.tvMachineName.text = botName
    }

    // Pega esta función dentro de SpinWheelActivity
    private fun showCategoryDialog(category: String) {
        // 1. Inflar el layout del diálogo
        val dialogView = layoutInflater.inflate(R.layout.dialog_category_selected, null)

        // CAMBIO: Usar tema por defecto en lugar del que causa error
        val builder = AlertDialog.Builder(this)
        builder.setView(dialogView)

        // 2. Crear el diálogo
        val dialog = builder.create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent) // Fondo transparente

        // 3. Configurar las vistas del diálogo
        val tvCategoryName = dialogView.findViewById<TextView>(R.id.tvCategoryName)
        val btnStartGame = dialogView.findViewById<Button>(R.id.btnStartGame)

        tvCategoryName.text = category

        // 4. Configurar el botón "¡A JUGAR!"
        btnStartGame.setOnClickListener {
            dialog.dismiss()
            val intent = Intent(this, GamePlayActivity::class.java)
            intent.putExtra("SELECTED_CATEGORY", category)
            intent.putExtra("BOT_NAME", botName) // ¡¡PASAMOS EL NOMBRE DEL BOT!!
            startActivity(intent)
            finish()
        }

        // 5. Mostrar el diálogo
        dialog.show()
    }

    private fun spinWheel() {
        isSpinning = true

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
        animation.duration = 5000
        animation.interpolator = DecelerateInterpolator()
        animation.fillAfter = true

        currentAngle = finalRotation % 360

        // Detenemos cualquier sonido anterior (por si acaso)
        try {
            spinSoundPlayer?.stop()
            spinSoundPlayer?.release()
        } catch (e: Exception) {}

        // Creamos e iniciamos el sonido de la ruleta
        spinSoundPlayer = MediaPlayer.create(this, R.raw.ruleta) // <-- (Usa tu archivo .mp3)
        spinSoundPlayer?.isLooping = true // ¡Importante para que se repita!
        spinSoundPlayer?.start()
        // --- FIN DEL CAMBIO ---

        animation.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation?) {}

            override fun onAnimationEnd(animation: Animation?) {
                // --- ¡¡CAMBIO AQUÍ!! ---
                // Cuando la animación para, paramos el sonido
                try {
                    spinSoundPlayer?.stop()
                    spinSoundPlayer?.release()
                    spinSoundPlayer = null
                } catch (e: Exception) {}
                // --- FIN DEL CAMBIO ---

                showCategoryDialog(targetCategory)
                isSpinning = false
            }

            override fun onAnimationRepeat(animation: Animation?) {}

        })



        binding.ivRuletaBase.startAnimation(animation)
    }

    override fun onStop() {
        super.onStop()
        // Si el sonido sigue sonando, lo paramos y liberamos
        try {
            spinSoundPlayer?.stop()
            spinSoundPlayer?.release()
            spinSoundPlayer = null
        } catch (e: Exception) {}
    }

}
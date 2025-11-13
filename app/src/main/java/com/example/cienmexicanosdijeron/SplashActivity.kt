package com.example.cienmexicanosdijeron

import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.cienmexicanosdijeron.databinding.ActivitySplashBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private var mediaPlayer: MediaPlayer? = null

    // Duración total de la carga en milisegundos (ej. 5 segundos)
    private val splashTime: Long = 4000
    // Intervalo de actualización de la barra (ej. 50ms)
    private val updateInterval: Long = 50

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configura ViewBinding (recuerda que lo habilitamos en el build.gradle)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Inicia la lógica de carga
        startLoading()
    }

    private fun startLoading() {
        // 1. Inicia la música
        try {
            mediaPlayer = MediaPlayer.create(this, R.raw.intro_juego) // CAMBIA por tu música
            mediaPlayer?.isLooping = false // Para que no se repita
            mediaPlayer?.start()
        } catch (e: Exception) {
            e.printStackTrace()
            // Manejar error si no se puede cargar la música
        }

        // 2. Inicia la barra de progreso usando una Corutina
        // (Es la forma moderna de hacer tareas en segundo plano en Kotlin)
        lifecycleScope.launch {
            val steps = (splashTime / updateInterval).toInt()
            for (i in 0..steps) {
                // Calcula el progreso de 0 a 100
                val progress = (i * 100 / steps)
                binding.pbLoading.progress = progress.toInt()
                delay(updateInterval)
            }

            // 3. Al terminar la carga, ve a la actividad principal
            goToNameInputActivity()
        }
    }

    private fun goToNameInputActivity() {
        // 1. Detiene y libera la música del SPLASH
        //mediaPlayer?.stop()
        //mediaPlayer?.release()
        //mediaPlayer = null

        //Inicia la NUEVA actividad de Input de Nombre
        val intent = Intent(this, NameInputActivity::class.java) // <--- CAMBIO AQUÍ
        startActivity(intent)

        // Cierra esta SplashActivity
        finish()
    }

    override fun onStop() {
        super.onStop()
        // Medida de seguridad: Si el usuario sale de la app
        // mientras está en el splash, detenemos la música.
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
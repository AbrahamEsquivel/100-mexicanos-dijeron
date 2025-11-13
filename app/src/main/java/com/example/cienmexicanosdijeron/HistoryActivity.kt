package com.example.cienmexicanosdijeron

import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.cienmexicanosdijeron.databinding.ActivityHistoryBinding
import kotlinx.coroutines.launch

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Pantalla completa
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        binding.btnBack.setOnClickListener {
            finish() // Cierra esta actividad
        }

        // Cargar el historial desde la base de datos
        loadHistory()
    }

    private fun loadHistory() {
        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(applicationContext)
            val historyList = db.gameResultDao().getAll()

            // Actualizar la UI
            binding.rvHistory.adapter = HistoryAdapter(historyList)
            binding.rvHistory.layoutManager = LinearLayoutManager(this@HistoryActivity)
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
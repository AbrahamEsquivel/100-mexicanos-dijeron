package com.example.cienmexicanosdijeron

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class HistoryAdapter(private val gameHistory: List<GameResult>) :
    RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder>() {

    class HistoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val gameTitle: TextView = itemView.findViewById(R.id.tvGameTitle)
        val gameDate: TextView = itemView.findViewById(R.id.tvGameDate)
        val gameWinner: TextView = itemView.findViewById(R.id.tvGameWinner)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history_game, parent, false)
        return HistoryViewHolder(view)
    }

    override fun getItemCount() = gameHistory.size

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        val game = gameHistory[position]

        holder.gameTitle.text = "${game.playerName} vs. ${game.botName}"
        holder.gameDate.text = game.date

        // Cambiamos el color según quién ganó
        when (game.winnerName) {
            game.playerName -> {
                holder.gameWinner.text = "¡GANASTE!"
                holder.gameWinner.setTextColor(Color.parseColor("#4CAF50")) // Verde
            }
            game.botName -> {
                holder.gameWinner.text = "PERDISTE"
                holder.gameWinner.setTextColor(Color.parseColor("#F44336")) // Rojo
            }
            else -> {
                holder.gameWinner.text = "EMPATE"
                holder.gameWinner.setTextColor(Color.parseColor("#FFC107")) // Amarillo
            }
        }
    }
}
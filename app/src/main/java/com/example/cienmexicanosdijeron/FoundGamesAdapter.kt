package com.example.cienmexicanosdijeron

import android.net.nsd.NsdServiceInfo
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class FoundGamesAdapter(
    private val services: List<NsdServiceInfo>,
    // Usamos una "lambda" para pasar el clic de "Unirse" a la Activity
    private val onJoinClickListener: (NsdServiceInfo) -> Unit
) : RecyclerView.Adapter<FoundGamesAdapter.GameViewHolder>() {

    class GameViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val gameName: TextView = itemView.findViewById(R.id.tvGameName)
        val joinButton: Button = itemView.findViewById(R.id.btnJoin)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GameViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_found_game, parent, false)
        return GameViewHolder(view)
    }

    override fun getItemCount() = services.size

    override fun onBindViewHolder(holder: GameViewHolder, position: Int) {
        val service = services[position]

        // El nombre del servicio (ej. "Partida de @Abraham")
        holder.gameName.text = service.serviceName

        holder.joinButton.setOnClickListener {
            // Cuando le piquen "Unirse", avisamos a la Activity
            onJoinClickListener(service)
        }
    }
}
package com.example.cienmexicanosdijeron

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "game_history")
data class GameResult(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    val playerName: String,
    val botName: String,
    val winnerName: String, // "Jugador", "Máquina" o "Empate"
    val finalScore: Int,
    val date: String // Guardaremos la fecha como texto simple
)
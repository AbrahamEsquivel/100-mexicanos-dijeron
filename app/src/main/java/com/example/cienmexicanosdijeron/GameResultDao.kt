package com.example.cienmexicanosdijeron

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface GameResultDao {
    @Insert
    suspend fun insert(game: GameResult)

    @Query("SELECT * FROM game_history ORDER BY id DESC")
    suspend fun getAll(): List<GameResult>
}
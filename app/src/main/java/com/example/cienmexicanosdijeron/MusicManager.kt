package com.example.cienmexicanosdijeron

import android.content.Context
import android.media.MediaPlayer

object MusicManager {

    private var mediaPlayer: MediaPlayer? = null

    // Llama a esta función para INICIAR la música
    fun start(context: Context, musicResId: Int) {
        if (mediaPlayer == null) {
            mediaPlayer = MediaPlayer.create(context.applicationContext, musicResId)
            mediaPlayer?.isLooping = true
        }
        mediaPlayer?.start()
    }

    // Llama a esta cuando el usuario pause la app
    fun pause() {
        mediaPlayer?.pause()
    }

    // Llama a esta cuando el usuario vuelva a la app
    fun resume() {
        mediaPlayer?.start()
    }

    // Llama a esta (cuando tú me digas) para PARARLA por completo
    fun stop() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
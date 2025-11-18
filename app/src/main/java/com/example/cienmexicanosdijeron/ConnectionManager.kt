package com.example.cienmexicanosdijeron

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket

/**
 * Este Singleton "guarda" la conexión del socket
 * para que no se pierda entre Actividades.
 */
object ConnectionManager {
    private var socket: Socket? = null
    var dataIn: DataInputStream? = null
    var dataOut: DataOutputStream? = null
    fun setHostSocket(clientSocket: Socket) {
        socket = clientSocket
        try {
            dataIn = DataInputStream(socket!!.getInputStream())
            dataOut = DataOutputStream(socket!!.getOutputStream())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    fun setClientSocket(clientSocket: Socket) {
        socket = clientSocket
        try {
            dataIn = DataInputStream(socket!!.getInputStream())
            dataOut = DataOutputStream(socket!!.getOutputStream())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    fun closeConnections() {
        try {
            dataIn?.close()
            dataOut?.close()
            socket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            dataIn = null
            dataOut = null
            socket = null
        }
    }
}
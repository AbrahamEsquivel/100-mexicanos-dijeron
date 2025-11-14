package com.example.cienmexicanosdijeron

import java.net.Socket
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast // <-- ¡¡IMPORT AÑADIDO!!
import androidx.appcompat.app.AppCompatActivity
import com.example.cienmexicanosdijeron.databinding.ActivityMultiplayerLobbyBinding
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.ServerSocket
import android.net.nsd.NsdServiceInfo
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.lifecycle.lifecycleScope
import java.io.DataInputStream
import java.io.DataOutputStream

class MultiplayerLobbyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMultiplayerLobbyBinding

    private var serverSocket: ServerSocket? = null
    private var nsdManager: NsdManager? = null

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var serviceName: String = "" // El nombre de nuestra partida
    private var playerName: String = "Jugador" // El nombre del Host

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var resolveListener: NsdManager.ResolveListener? = null
    private val foundServices = mutableListOf<NsdServiceInfo>()
    private lateinit var serviceAdapter: FoundGamesAdapter

    private var clientSocket: Socket? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMultiplayerLobbyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadPlayerName()
        setupRecyclerView()

        // Pantalla completa
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        // Configurar botones
        binding.btnBack.setOnClickListener {
            finish() // Regresa
        }

        binding.btnHostGame.setOnClickListener {
            // Lógica para ser el Servidor
            startHosting()
        }

        binding.btnDiscoverGames.setOnClickListener {
            // Lógica para ser el Cliente
            startDiscovery()
        }
    }

    private fun setupRecyclerView() {
        // Inicializamos el adapter con nuestra lista (vacía al inicio)
        serviceAdapter = FoundGamesAdapter(foundServices) { service ->
            // ESTO ES LO QUE PASA AL PICAR "UNIRSE"

            // ¡¡AQUÍ ESTÁ LA CORRECIÓN!!
            // Debe llamar a initializeResolveListener(), no a initializeDiscoveryListener()
            nsdManager?.resolveService(service, initializeResolveListener())
        }

        binding.rvFoundGames.adapter = serviceAdapter
        binding.rvFoundGames.layoutManager = LinearLayoutManager(this)
    }

    private fun registerService(port: Int) {
        // 1. Inicializa el listener (lo creamos abajo)
        initializeRegistrationListener()

        // 2. Crea el objeto del servicio
        val serviceInfo = NsdServiceInfo().apply {
            // El nombre que verán otros (ej: "Partida de Abraham")
            serviceName = "Partida de $playerName"

            // El tipo de servicio (es un nombre custom)
            // Esto asegura que SÓLO nuestra app vea nuestras partidas
            serviceType = "_100mexicanos._tcp."

            // El puerto que obtuvimos del ServerSocket
            setPort(port)
        }

        // 3. Inicializa el NsdManager y registra
        nsdManager = (getSystemService(Context.NSD_SERVICE) as NsdManager).apply {
            registerService(
                serviceInfo,
                NsdManager.PROTOCOL_DNS_SD,
                registrationListener
            )
        }
    }

    /**
     * NUEVA: Este objeto es el "oído" que nos dice si la publicación funcionó.
     */
    private fun initializeRegistrationListener() {
        registrationListener = object : NsdManager.RegistrationListener {

            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                // ¡ÉXITO! Tu partida ya es visible en la red
                serviceName = serviceInfo.serviceName
                runOnUiThread {
                    toast("¡Partida publicada en la red!")
                    // Podríamos cambiar el texto del botón a "Cancelar"
                    binding.btnHostGame.text = "PARTIDA CREADA (HOST)"
                }
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // Falló
                runOnUiThread {
                    toast("Error al publicar la partida. Código: $errorCode")
                    binding.btnHostGame.isEnabled = true
                    binding.btnDiscoverGames.isEnabled = true
                    binding.pbSearching.visibility = View.GONE
                }
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                // (Se llama cuando dejas de ser Host)
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // (Raro que pase)
            }
        }
    }
    private fun loadPlayerName() {
        val sp = getSharedPreferences("GamePrefs", Context.MODE_PRIVATE)
        playerName = sp.getString("PlayerName", "Jugador") ?: "Jugador"
    }
    private fun startHosting() {
        binding.btnHostGame.isEnabled = false
        binding.btnDiscoverGames.isEnabled = false
        binding.pbSearching.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. Abrimos puerto y publicamos (como antes)
                serverSocket = ServerSocket(0)
                val port = serverSocket!!.localPort
                withContext(Dispatchers.Main) {
                    registerService(port)
                }

                // 2. Esperamos al cliente
                val clientSocket = serverSocket!!.accept()
                ConnectionManager.setHostSocket(clientSocket)
                val clientName = ConnectionManager.dataIn?.readUTF() ?: "Invitado"

                // 3. ¡¡CAMBIO AQUÍ!!
                // (Avisamos al Host en la UI)
                withContext(Dispatchers.Main) {
                    toast("¡'$clientName' se ha conectado!")
                    if (registrationListener != null) {
                        try { nsdManager?.unregisterService(registrationListener) }
                        catch (e: Exception) { e.printStackTrace() }
                    }
                    binding.btnHostGame.text = "¡JUGADOR ENCONTRADO!"
                }

                // 4. ¡¡NUEVO!! Enviamos la orden de empezar
                ConnectionManager.dataOut?.writeUTF("START_GAME")
                ConnectionManager.dataOut?.flush()

                // 5. ¡¡NUEVO!! Iniciamos la pantalla de la Ruleta (para el Host)
                withContext(Dispatchers.Main) {
                    val intent =
                        Intent(this@MultiplayerLobbyActivity, SpinWheelActivity::class.java)
                    intent.putExtra("IS_MULTIPLAYER", true)
                    intent.putExtra("IS_HOST", true)
                    intent.putExtra("OPPONENT_NAME", clientName) // Le pasamos el nombre del rival
                    startActivity(intent)
                    finish() // Cerramos el Lobby
                }

                serverSocket?.close()

            } catch (e: Exception) {
                // ... (el 'catch' se queda igual)
            }
        }
    }

    private fun initializeDiscoveryListener() {
        discoveryListener = object : NsdManager.DiscoveryListener {

            override fun onDiscoveryStarted(regType: String) {
                runOnUiThread {
                    binding.pbSearching.visibility = View.VISIBLE
                    toast("Buscando partidas...")
                }
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                // ¡Encontramos una partida!
                runOnUiThread {
                    // Evitamos duplicados
                    if (!foundServices.any { it.serviceName == service.serviceName }) {
                        foundServices.add(service)
                        serviceAdapter.notifyItemInserted(foundServices.size - 1)
                    }
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                // Alguien se desconectó
                runOnUiThread {
                    val lostService = foundServices.find { it.serviceName == service.serviceName }
                    if (lostService != null) {
                        val index = foundServices.indexOf(lostService)
                        foundServices.removeAt(index)
                        serviceAdapter.notifyItemRemoved(index)
                    }
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                runOnUiThread {
                    binding.pbSearching.visibility = View.GONE
                    toast("Búsqueda detenida.")
                }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                runOnUiThread {
                    toast("Error al buscar: $errorCode")
                    nsdManager?.stopServiceDiscovery(this)
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                runOnUiThread {
                    toast("Error al detener: $errorCode")
                    nsdManager?.stopServiceDiscovery(this)
                }
            }
        }
    }

    // En MultiplayerLobbyActivity.kt

    /**
     * MODIFICADA: Ahora el Cliente ENVÍA su nombre al Host
     */
    private fun initializeResolveListener(): NsdManager.ResolveListener {
        return object : NsdManager.ResolveListener {

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host
                val port = serviceInfo.port

                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        // 1. Nos conectamos y guardamos (como antes)
                        val clientSocket = Socket(host, port)
                        ConnectionManager.setClientSocket(clientSocket)

                        // 2. Enviamos nuestro nombre (como antes)
                        ConnectionManager.dataOut?.writeUTF(playerName)
                        ConnectionManager.dataOut?.flush()

                        // 3. Avisamos al usuario que estamos "Esperando"
                        withContext(Dispatchers.Main) {
                            toast("¡Conectado! Esperando a que el Host inicie...")
                            stopDiscovery()
                            binding.btnDiscoverGames.text = "¡CONECTADO!"
                            binding.btnHostGame.isEnabled = false
                        }

                        // 4. ¡¡NUEVO!! Nos quedamos escuchando la orden del Host
                        val command = ConnectionManager.dataIn?.readUTF()

                        if (command == "START_GAME") {
                            // ¡Recibimos la orden!
                            // 5. ¡¡NUEVO!! Iniciamos la Ruleta (para el Cliente)
                            withContext(Dispatchers.Main) {
                                val intent = Intent(this@MultiplayerLobbyActivity, SpinWheelActivity::class.java)
                                intent.putExtra("IS_MULTIPLAYER", true)
                                intent.putExtra("IS_HOST", false)
                                intent.putExtra("OPPONENT_NAME", serviceInfo.serviceName) // El nombre del Host
                                startActivity(intent)
                                finish() // Cerramos el Lobby
                            }
                        }

                    }catch (e: Exception) {
                        e.printStackTrace()
                        withContext(Dispatchers.Main) {
                            toast("Error al conectarse: ${e.message}")
                        }
                    }
                }
            }

            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                runOnUiThread {
                    toast("Error al conectar con ${serviceInfo.serviceName}")
                }
            }
        }
    }
    private fun startDiscovery() {
        // Preparamos los botones
        binding.btnHostGame.isEnabled = false
        binding.btnDiscoverGames.isEnabled = false
        binding.pbSearching.visibility = View.VISIBLE

        // Limpiamos la lista vieja
        foundServices.clear()
        serviceAdapter.notifyDataSetChanged()

        // Inicializamos los "oídos"
        initializeDiscoveryListener()

        // Empezamos la búsqueda
        nsdManager = (getSystemService(Context.NSD_SERVICE) as NsdManager).apply {
            discoverServices(
                "_100mexicanos._tcp.", // ¡El mismo serviceType que el Host!
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener
            )
        }
    }

    private fun stopDiscovery() {
        if (discoveryListener != null) {
            try {
                nsdManager?.stopServiceDiscovery(discoveryListener)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // No olvides pausar/resumir la música del menú
    override fun onResume() {
        super.onResume()
        MusicManager.resume()
    }

    override fun onPause() {
        super.onPause()
        MusicManager.pause()
        stopDiscovery()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDiscovery()

        if (registrationListener != null) {
            try { nsdManager?.unregisterService(registrationListener) }
            catch (e: Exception) { e.printStackTrace() }
        }
        try { serverSocket?.close() }
        catch (e: Exception) { e.printStackTrace() }

        // ¡¡CAMBIO AQUÍ!!
        // Le decimos al guardián que cierre todo
        //ConnectionManager.closeConnections()
    }

    // ¡¡FUNCIÓN AÑADIDA AQUÍ!!
    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
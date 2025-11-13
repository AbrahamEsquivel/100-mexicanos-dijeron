package com.example.cienmexicanosdijeron

import java.net.Socket
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast // <-- ¡¡IMPORT AÑADIDO!!
import androidx.appcompat.app.AppCompatActivity
import com.example.cienmexicanosdijeron.databinding.ActivityMultiplayerLobbyBinding
import android.content.Context
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
                // 1. Abrimos el puerto
                serverSocket = ServerSocket(0)
                val port = serverSocket!!.localPort

                // 2. ¡¡CAMBIO AQUÍ!!
                // Le decimos al hilo principal que publique este puerto
                withContext(Dispatchers.Main) {
                    registerService(port) // <-- ¡LA LLAMAMOS!
                }

                // 3. (Esto se queda igual)
                // Nos quedamos esperando a que alguien se conecte
                while (true) {
                    val clientSocket = serverSocket!!.accept()

                    withContext(Dispatchers.Main) {
                        toast("¡Un jugador se ha conectado!")
                        // TODO: Iniciar el juego
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    toast("Error al iniciar el Host: ${e.message}")
                    binding.btnHostGame.isEnabled = true
                    binding.btnDiscoverGames.isEnabled = true
                    binding.pbSearching.visibility = View.GONE
                }
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

    private fun initializeResolveListener(): NsdManager.ResolveListener {
        return object : NsdManager.ResolveListener {

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                // ¡ÉXITO! Ya tenemos la IP y el Puerto
                val host = serviceInfo.host
                val port = serviceInfo.port

                // ¡¡AQUÍ VIENE EL CAMBIO!!
                // Tenemos que conectarnos en un hilo de fondo (IO)
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        // 1. ¡Creamos el Socket y nos conectamos!
                        clientSocket = Socket(host, port)

                        // 2. Si llegamos aquí, ¡lo logramos!
                        // Avisamos al usuario en el hilo principal (Main)
                        withContext(Dispatchers.Main) {
                            toast("¡Conectado al Host!")
                            // TODO: Aquí es donde enviaremos nuestro nombre
                        }

                    } catch (e: Exception) {
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

        // Damos de baja el servicio NSD (si éramos Host)
        if (registrationListener != null) {
            try { nsdManager?.unregisterService(registrationListener) }
            catch (e: Exception) { e.printStackTrace() }
        }

        // Cerramos el ServerSocket (si éramos Host)
        try {
            serverSocket?.close()
        } catch (e: Exception) { e.printStackTrace() }

        // ¡¡NUEVO!! Cerramos el Socket del Cliente (si éramos Cliente)
        try {
            clientSocket?.close()
        } catch (e: Exception) { e.printStackTrace() }
    }

    // ¡¡FUNCIÓN AÑADIDA AQUÍ!!
    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
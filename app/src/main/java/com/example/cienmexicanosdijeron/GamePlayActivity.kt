package com.example.cienmexicanosdijeron

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.cienmexicanosdijeron.databinding.ActivityGamePlayBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.Normalizer
import kotlin.random.Random
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GamePlayActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGamePlayBinding
    private var machineAITask: Job? = null
    private var faceOffDialog: AlertDialog? = null
    private var isFaceOffWon = false

    // --- NUEVAS VARIABLES PARA EL JUEGO ---
    private var currentQuestionData: QuestionData? = null
    private lateinit var answerAdapter: AnswerAdapter
    private var speechRecognizer: SpeechRecognizer? = null
    private var isRecording = false
    private var currentStrikes = 0
    private var currentScore = 0

    // --- NUEVAS VARIABLES (Timer, Sonido, Vibración) ---
    private var answerTimer: Job? = null
    private var soundPool: SoundPool? = null
    private var wrongSoundId: Int = 0
    private var correctSoundId: Int = 0
    private var vibrator: Vibrator? = null

    // ... (justo después de tus otras variables)

    // --- NUEVAS VARIABLES DE LÓGICA DE TURNOS ---
    private var isPlayerTurn = true // Para saber quién está jugando
    private var isStealAttempt = false // Para saber si es un robo (solo 1 intento)
    private var hasAnyAnswerBeenRevealed = false // Para la lógica de empate
    private var playerStrikes = 0 // Contador separado
    private var machineStrikes = 0 // Contador separado
    private var thinkingTimer: Job? = null // NUEVO: Timer para "pensar"

    private var botName: String = "Bot"
    private var playerName: String = "Jugador"

    // ¡¡NUEVAS VARIABLES DE MULTIJUGADOR!!
    private var isMultiplayer = false
    private var isHost = false
    private var clientListenerJob: Job? = null // El "oído" del Cliente

    // ¡¡NUEVA!! El "oído" del Host para el BUZZER
    private var hostBuzzerListenerJob: Job? = null

    private val sillyAnswers = listOf(
        "¿Pato?",
        "No sé, ¿Uvas?",
        "Maradona",
        "El... ¿Sol?",
        "Tacos"
    )


    // --- MANEJADOR DE PERMISO DE MICRÓFONO ---
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                // Permiso concedido, podemos empezar a grabar
                startRecording()
            } else {
                toast("Necesitamos el permiso del micrófono para jugar.")
            }
        }



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGamePlayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hideSystemUI()
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator?
        setupSounds()

        // --- ¡¡LÓGICA HÍBRIDA!! ---

        // 1. Leemos las variables del Intent
        isMultiplayer = intent.getBooleanExtra("IS_MULTIPLAYER", false)
        isHost = intent.getBooleanExtra("IS_HOST", false)
        val opponentName = intent.getStringExtra("OPPONENT_NAME") ?: "Bot"
        val categoryName = intent.getStringExtra("SELECTED_CATEGORY") ?: "Sin Categoría"

        // 2. Guardamos los nombres (para el historial)
        val sp = getSharedPreferences("GamePrefs", Context.MODE_PRIVATE)
        playerName = sp.getString("PlayerName", "Jugador") ?: "Jugador"
        botName = opponentName // En multi, el "botName" es nuestro oponente

        // 3. Configuramos el botón de micrófono (siempre lo necesitamos)
        setupMicButton()

        // 4. Decidimos cómo empezar
        if (isMultiplayer) {
            if (isHost) {
                // Soy el Host: Tengo que cargar la pregunta y enviarla
                initializeHostGame(categoryName)
            } else {
                // Soy el Cliente: Tengo que esperar a que el Host me diga la pregunta
                initializeClientGame()
            }
        } else {
            // MODO OFFLINE: Hacemos lo de siempre
            initializeOfflineGame(categoryName)
        }
    }

    private fun initializeOfflineGame(categoryName: String) {
        // 1. Carga la pregunta
        currentQuestionData = loadQuestionForCategory(categoryName)

        if (currentQuestionData != null) {
            // 2. Muestra la pregunta
            binding.tvQuestion.text = currentQuestionData!!.question
            // 3. Configura el tablero
            setupRecyclerView()
            // 4. Inicia el buzzer
            showFaceOffDialog()
        } else {
            binding.tvQuestion.text = "Error: No se encontró pregunta para $categoryName"
        }
    }

    /**
     * NUEVA: Inicia el juego como HOST (Servidor)
     */
    private fun initializeHostGame(categoryName: String) {
        // 1. El Host carga la pregunta
        currentQuestionData = loadQuestionForCategory(categoryName)

        if (currentQuestionData != null) {
            binding.tvQuestion.text = currentQuestionData!!.question
            setupRecyclerView()

            // 2. El Host ENVÍA la pregunta al Cliente
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    // Mandamos la pregunta (que incluye las respuestas)
                    val questionJson = convertQuestionDataToJson(currentQuestionData!!)
                    ConnectionManager.dataOut?.writeUTF("QUESTION::SEP::$questionJson")
                    ConnectionManager.dataOut?.flush()

                    // 3. Ahora que el Cliente sabe, iniciamos el buzzer
                    withContext(Dispatchers.Main) {
                        showFaceOffDialog() // <-- Esto AHORA iniciará el listener del Host
                    }

                    // ¡¡BORRAMOS EL LISTENER DE "BUZZ" DE AQUÍ!!

                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        toast("Error al enviar pregunta: ${e.message}")
                    }
                }
            }
        } else {
            // TODO: Avisar al cliente que hubo un error
            binding.tvQuestion.text = "Error: No se encontró pregunta para $categoryName"
        }
    }

    /**
     * NUEVA: Inicia el juego como CLIENTE (Espejo)
     */
    private fun initializeClientGame() {
        toast("Conectado. Esperando pregunta del Host...")
        binding.btnMic.visibility = View.GONE

        clientListenerJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. Espera la pregunta (como antes)
                val questionCommand = ConnectionManager.dataIn?.readUTF()

                if (questionCommand != null && questionCommand.startsWith("QUESTION::SEP::")) {

                    val questionJson = questionCommand.substringAfter("QUESTION::SEP::")
                    currentQuestionData = parseQuestionDataFromJson(questionJson)

                    withContext(Dispatchers.Main) {
                        if (currentQuestionData != null) {
                            binding.tvQuestion.text = currentQuestionData!!.question
                            setupRecyclerView()
                            showFaceOffDialog() // Muestra el buzzer (del Cliente)
                        } else {
                            toast("Error al recibir la pregunta.")
                        }
                    }

                    // ¡¡BORRAMOS EL RESTO!!
                    // La lógica de "YOU_WIN" / "YOU_LOSE" se va de aquí.

                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    toast("¡Error de conexión con el Host!")
                    // TODO: Volver al menú
                }
            }
        }
    }

    private fun convertQuestionDataToJson(data: QuestionData): String {
        val questionJson = JSONObject()
        questionJson.put("pregunta", data.question)

        val answersJsonArray = org.json.JSONArray()
        data.answers.forEach { answer ->
            val answerJson = JSONObject()
            answerJson.put("texto", answer.text)
            answerJson.put("puntos", answer.points)
            answersJsonArray.put(answerJson)
        }
        questionJson.put("respuestas", answersJsonArray)

        return questionJson.toString()
    }

    /**
     * NUEVA: Convierte un String de JSON de vuelta a nuestro objeto QuestionData
     */
    private fun parseQuestionDataFromJson(jsonString: String): QuestionData? {
        return try {
            val questionJson = JSONObject(jsonString)
            val questionText = questionJson.getString("pregunta")

            val answersJsonArray = questionJson.getJSONArray("respuestas")
            val answersList = mutableListOf<Answer>()
            for (j in 0 until answersJsonArray.length()) {
                val answerObj = answersJsonArray.getJSONObject(j)
                answersList.add(
                    Answer(
                        answerObj.getString("texto"),
                        answerObj.getInt("puntos")
                    )
                )
            }
            QuestionData(questionText, answersList)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    private fun saveGameResult(winner: String, score: Int) {
        // Obtenemos la fecha y hora
        val sdf = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
        val currentDate = sdf.format(java.util.Date())

        // Creamos el objeto
        val result = GameResult(
            playerName = this.playerName,
            botName = this.botName,
            winnerName = winner,
            finalScore = score,
            date = currentDate
        )

        // Lo guardamos en la base de datos en un hilo separado
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val db = AppDatabase.getDatabase(applicationContext)
            db.gameResultDao().insert(result)
        }
    }
    private fun setupSounds() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(audioAttributes)
            .build()

        wrongSoundId = soundPool!!.load(this, R.raw.wrong_answer, 1)
        correctSoundId = soundPool!!.load(this, R.raw.correct_answer, 1)
    }

    // --- MODIFICADA: Ahora devuelve un 'QuestionData' ---
    private fun loadQuestionForCategory(category: String): QuestionData? {
        try {
            val inputStream = resources.openRawResource(R.raw.questions)
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(jsonString)

            val categoriesArray = json.getJSONArray("categorias")
            for (i in 0 until categoriesArray.length()) {
                val categoryObj = categoriesArray.getJSONObject(i)
                if (categoryObj.getString("nombre") == category) {
                    val questionsArray = categoryObj.getJSONArray("preguntas")
                    if (questionsArray.length() > 0) {
                        val randomIndex = Random.nextInt(questionsArray.length())
                        val randomQuestionObj = questionsArray.getJSONObject(randomIndex)

                        val questionText = randomQuestionObj.getString("pregunta")
                        val answersArray = randomQuestionObj.getJSONArray("respuestas")

                        val answersList = mutableListOf<Answer>()
                        for (j in 0 until answersArray.length()) {
                            val answerObj = answersArray.getJSONObject(j)
                            answersList.add(
                                Answer(
                                    answerObj.getString("texto"),
                                    answerObj.getInt("puntos")
                                )
                            )
                        }
                        return QuestionData(questionText, answersList)
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return null
    }

    // --- FUNCIÓN PARA EL BUZZER (Se queda igual) ---
    private fun showFaceOffDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_face_off, null)
        val builder = AlertDialog.Builder(this)
        builder.setView(dialogView)
        builder.setCancelable(false)
        faceOffDialog = builder.create()
        isFaceOffWon = false

        faceOffDialog?.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setBackgroundDrawableResource(android.R.color.transparent)
        }

        val btnPlayerBuzzer = dialogView.findViewById<Button>(R.id.btnPlayerBuzzer)
        val btnMachineBuzzer = dialogView.findViewById<Button>(R.id.btnMachineBuzzer)

        // --- Lógica del Jugador (TÚ) ---
        btnPlayerBuzzer.setOnClickListener {
            if (!isFaceOffWon) {
                if (isMultiplayer && !isHost) {
                    // --- SOY CLIENTE (ONLINE) ---
                    isFaceOffWon = true
                    btnPlayerBuzzer.isPressed = true
                    btnPlayerBuzzer.text = "¡PRESIONADO!"
                    btnPlayerBuzzer.isEnabled = false
                    lifecycleScope.launch(Dispatchers.IO) {
                        ConnectionManager.dataOut?.writeUTF("BUZZ")
                        ConnectionManager.dataOut?.flush()
                    }
                } else {
                    // --- SOY OFFLINE o HOST (ONLINE) ---
                    handleBuzzerResult(didPlayerWin = true)
                }
            }
        }

        // --- Lógica del Oponente (IA o Cliente) ---
        if (isMultiplayer) {
            // --- MODO MULTIJUGADOR ---
            btnMachineBuzzer.isClickable = false
            btnMachineBuzzer.alpha = 0.5f
            btnMachineBuzzer.text = "@$botName"

            if (isHost) {
                // ¡¡LISTENER DEL HOST!! (Esto estaba bien)
                // Si soy el Host, me pongo a escuchar el "BUZZ"
                hostBuzzerListenerJob?.cancel()
                hostBuzzerListenerJob = lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val clientCommand = ConnectionManager.dataIn?.readUTF()
                        if (clientCommand == "BUZZ" && !isFaceOffWon) {
                            withContext(Dispatchers.Main) {
                                handleBuzzerResult(didPlayerWin = false, isClientWinner = true)
                            }
                        }
                    } catch (e: Exception) { /* (El socket se cerró, no pasa nada) */ }
                }
            } else {
                // ¡¡NUEVO LISTENER DEL CLIENTE!!
                // Si soy el Cliente, me pongo a escuchar el "RESULTADO"
                clientListenerJob?.cancel()
                clientListenerJob = lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val resultCommand = ConnectionManager.dataIn?.readUTF()
                        if (resultCommand == "YOU_WIN") {
                            withContext(Dispatchers.Main) {
                                faceOffDialog?.dismiss() // ¡¡SE CIERRA!!
                                toast("¡Ganaste el turno!")
                                startPlayerTurn()
                            }
                        } else if (resultCommand == "YOU_LOSE") {
                            withContext(Dispatchers.Main) {
                                faceOffDialog?.dismiss() // ¡¡SE CIERRA!!
                                toast("¡'$botName' ganó el turno!")
                                startMachineTurn()
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            toast("Error de conexión con '$botName'")
                        }
                    }
                }
            }

        } else {
            // --- MODO OFFLINE ---
            // (La lógica de la IA se queda igual)
            btnMachineBuzzer.isClickable = false
            btnMachineBuzzer.text = "MÁQUINA"
            machineAITask?.cancel()
            machineAITask = lifecycleScope.launch {
                delay(Random.nextLong(1000, 4000))
                if (!isFaceOffWon) {
                    handleBuzzerResult(didPlayerWin = false) // La IA gana
                }
            }
        }

        faceOffDialog?.show()
    }

    private fun handleBuzzerResult(didPlayerWin: Boolean, isClientWinner: Boolean = false) {
        if (isFaceOffWon) return
        isFaceOffWon = true
        machineAITask?.cancel() // Matamos la IA (si estaba en modo Offline)

        // ¡¡FIX IMPORTANTE!!
        // Matamos el "oído" del Host para que no se quede atorado
        hostBuzzerListenerJob?.cancel()

        val btnPlayer = faceOffDialog?.findViewById<Button>(R.id.btnPlayerBuzzer)
        val btnMachine = faceOffDialog?.findViewById<Button>(R.id.btnMachineBuzzer)

        lifecycleScope.launch {
            if (isClientWinner) {
                // --- GANÓ EL CLIENTE ---
                toast("¡'$botName' ganó el turno!")
                launch(Dispatchers.IO) {
                    ConnectionManager.dataOut?.writeUTF("YOU_WIN")
                    ConnectionManager.dataOut?.flush()
                }
                delay(1000)
                faceOffDialog?.dismiss() // Host (Perdedor) se cierra
                startMachineTurn()

            } else if (didPlayerWin) {
                // --- GANÓ EL JUGADOR (HOST) ---
                btnPlayer?.isPressed = true
                btnPlayer?.text = "¡GANASTE!\nPRIMERO"
                toast("¡Ganaste el turno!")

                if(isMultiplayer) { // Si es multi, avisamos al Cliente
                    launch(Dispatchers.IO) {
                        ConnectionManager.dataOut?.writeUTF("YOU_LOSE")
                        ConnectionManager.dataOut?.flush()
                    }
                }
                delay(1000)
                faceOffDialog?.dismiss() // Host (Ganador) se cierra
                startPlayerTurn()

            } else {
                // --- GANÓ LA MÁQUINA (IA) ---
                btnMachine?.isPressed = true
                btnMachine?.text = "¡GANÓ!\nPRIMERO"
                toast("¡La máquina ganó el turno!")

                // (No necesitamos enviar nada en Offline)

                delay(1000)
                faceOffDialog?.dismiss() // Host (Perdedor) se cierra
                startMachineTurn()
            }
        }
    }

    // --- NUEVA: Configura la lista vacía ---
    private fun setupRecyclerView() {
        if(currentQuestionData == null) return // Seguridad
        answerAdapter = AnswerAdapter(currentQuestionData!!.answers)
        binding.rvAnswers.adapter = answerAdapter
        binding.rvAnswers.layoutManager = LinearLayoutManager(this)
    }

    // --- MODIFICADA: Activa el botón de Mic ---
    private fun startPlayerTurn() {
        isPlayerTurn = true // ¡Mi turno!
        isStealAttempt = false
        playerStrikes = 0
        resetStrikeUI()

        binding.btnMic.visibility = View.VISIBLE
        binding.btnMic.isEnabled = true // Aseguramos que esté activo
        toast("¡Tu turno! Piensa tu respuesta...")

        startThinkingTimer() // Inicia el timer de pensar
    }

    // En GamePlayActivity.kt

    /**
     * NUEVA: Muestra una alerta simple y ejecuta una acción al cerrarla.
     */
    private fun showGameAlert(message: String, onDismiss: () -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_game_alert, null)
        val builder = AlertDialog.Builder(this)
        builder.setView(dialogView)
        builder.setCancelable(false)

        val dialog = builder.create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val tvMessage = dialogView.findViewById<TextView>(R.id.tvAlertMessage)
        val btnOK = dialogView.findViewById<Button>(R.id.btnAlertOK)

        tvMessage.text = message

        btnOK.setOnClickListener {
            dialog.dismiss()
            onDismiss() // Ejecuta la acción que nos pasaron
        }

        dialog.show()
    }

    private fun showEndRoundDialog(title: String, score: Int) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_end_round, null)
        val builder = AlertDialog.Builder(this)
        builder.setView(dialogView)
        builder.setCancelable(false)

        val dialog = builder.create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val tvTitle = dialogView.findViewById<TextView>(R.id.tvEndTitle)
        val tvScore = dialogView.findViewById<TextView>(R.id.tvEndScore)
        val btnPlayAgain = dialogView.findViewById<Button>(R.id.btnPlayAgain)
        val btnGoToMenu = dialogView.findViewById<Button>(R.id.btnGoToMenu)

        tvTitle.text = title
        tvScore.text = "Puntaje: $score"

        // 1. Calculamos quién ganó (para la BD)
        val winnerName = when {
            title.contains("GANASTE") || title.contains("ROBO EXITOSO") -> playerName
            title.contains("GANA") || title.contains("ROBA") -> botName // Ajustado para 'botName'
            else -> "Empate"
        }
        saveGameResult(winnerName, score) // Guardamos en la BD

        // --- ¡¡AQUÍ ESTÁ EL FIX!! ---
        // 2. Si soy el Host y estoy en Multi, le aviso al Cliente que el juego se acabó.
        if (isMultiplayer && isHost) {

            // Creamos el título para el Cliente (al revés que el nuestro)
            val remoteTitle = when {
                title.contains("GANASTE") || title.contains("ROBO EXITOSO") -> "¡PERDISTE!"
                title.contains("GANA") || title.contains("ROBA") -> "¡GANASTE!"
                title.contains("EMPATE") -> "¡EMPATE!"
                title.contains("ROBO FALLIDO! ¡GANASTE!") -> "¡ROBO FALLIDO! PERDISTE"
                title.contains("¡ROBO FALLIDO!") -> "¡ROBO FALLIDO! ¡GANASTE!"
                else -> "RONDA TERMINADA"
            }

            lifecycleScope.launch(Dispatchers.IO) {
                // Comando: "END_ROUND:Título_para_el_perdedor:Puntaje"
                ConnectionManager.dataOut?.writeUTF("END_ROUND:$remoteTitle:$score")
                ConnectionManager.dataOut?.flush()
            }
        }
        // --- FIN DEL FIX ---

        // 3. Botones (se quedan igual)
        btnPlayAgain.setOnClickListener {
            dialog.dismiss()
            val intent = Intent(this, SpinWheelActivity::class.java)
            startActivity(intent)
            finish()
        }

        btnGoToMenu.setOnClickListener {
            dialog.dismiss()
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            startActivity(intent)
            finish()
        }

        dialog.show()
    }

    // NUEVA: La alerta de Empate
    private fun showEmpateDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_empate, null)
        val builder = AlertDialog.Builder(this)
        builder.setView(dialogView)
        builder.setCancelable(false)

        val dialog = builder.create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val btnSeguir = dialogView.findViewById<Button>(R.id.btnSeguir)

        btnSeguir.setOnClickListener {
            dialog.dismiss()
            restartRound() // ¡Reinicia la ronda!
        }
        dialog.show()
    }

    private fun restartRound() {
        // Resetea todas las variables del juego
        isPlayerTurn = true
        isStealAttempt = false
        hasAnyAnswerBeenRevealed = false
        playerStrikes = 0
        machineStrikes = 0
        currentStrikes = 0 // <-- Asegúrate de tener esta
        currentScore = 0

        resetStrikeUI()

        // Volver a ocultar todas las respuestas
        currentQuestionData?.answers?.forEach { it.isRevealed = false }
        answerAdapter.notifyDataSetChanged()

        // Volver al buzzer
        showFaceOffDialog()
    }

    // En GamePlayActivity.kt
    /**
     * MODIFICADA: Lógica de la máquina con las nuevas alertas de fin de ronda.
     */
    private fun startMachineTurn() {
        isPlayerTurn = false
        isStealAttempt = false
        machineStrikes = 0
        resetStrikeUI()

        // --- ¡¡FIX A LA FUERZA 2.0!! ---
        // 1. Nos aseguramos de que el buzzer SÍ se cierre
        faceOffDialog?.dismiss()

        // 2. FORZAMOS el redibujado volviendo a poner los datos
        if (currentQuestionData != null) {
            binding.tvQuestion.text = currentQuestionData!!.question
            binding.rvAnswers.adapter = answerAdapter // Volver a setear el adapter

            // Y nos aseguramos de que sean visibles
            binding.tvQuestion.visibility = View.VISIBLE
            binding.rvAnswers.visibility = View.VISIBLE
        }
        // --- FIN DEL FIX ---

        // 3. Apagamos todos los controles locales (esto estaba bien)
        binding.btnMic.visibility = View.GONE
        thinkingTimer?.cancel()
        answerTimer?.cancel()
        binding.pbTimer.visibility = View.GONE
        isRecording = false

        if (isMultiplayer) {
            // --- MODO MULTIJUGADOR (Soy Pasivo/Escuchando) ---
            toast("Turno de '$botName'. Esperando...")

            clientListenerJob?.cancel()
            clientListenerJob = lifecycleScope.launch(Dispatchers.IO) {
                try {
                    while(true) {
                        val command = ConnectionManager.dataIn?.readUTF() ?: break
                        withContext(Dispatchers.Main) {
                            handleNetworkCommand(command)
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        toast("¡Error de conexión con '$botName'!")
                    }
                }
            }
        } else {
            // --- MODO OFFLINE (Soy Activo con IA) ---
            toast("Turno de la máquina...")
            machineMakesAGuess()
        }
    }



    /**
     * NUEVA: El Cliente aplica la respuesta correcta que el Host le dijo
     */
    private fun applyRemoteAnswer(index: Int) {
        if (::answerAdapter.isInitialized) {
            answerAdapter.revealAnswer(index)
            soundPool?.play(correctSoundId, 1f, 1f, 0, 0, 1f)

            // Si yo (Cliente) sigo jugando, reinicio mi timer de pensar
            if (isPlayerTurn && playerStrikes < 3) {
                startThinkingTimer()
            }
        }
    }

    /**
     * NUEVA: El Cliente aplica el strike que el Host le dijo
     */
    private fun applyRemoteStrike(strikeCount: Int? = null) {
        // Si el Host nos dice el # de strike (para el oponente)
        if (strikeCount != null) {
            when (strikeCount) {
                1 -> binding.ivStrike1.apply { setImageResource(R.drawable.ic_strike_off); visibility = View.VISIBLE }
                2 -> binding.ivStrike2.apply { setImageResource(R.drawable.ic_strike_off); visibility = View.VISIBLE }
                3 -> binding.ivStrike3.apply { setImageResource(R.drawable.ic_strike_off); visibility = View.VISIBLE }
            }
        }
        // Si no (fue nuestro propio error)
        else {
            toast("¡Incorrecto!")
            soundPool?.play(wrongSoundId, 1f, 1f, 0, 0, 1f)
            vibrateOnError()

            // Como clientes, no llevamos la cuenta de strikes, solo reiniciamos
            if (isPlayerTurn && playerStrikes < 3) {
                startThinkingTimer()
            }
        }
    }

    private fun machineMakesAGuess() {
        lifecycleScope.launch {
            delay(5000) // "Pensando..."
            if (currentQuestionData == null) return@launch

            val unrevealedAnswers = currentQuestionData!!.answers.filter { !it.isRevealed }
            if (unrevealedAnswers.isEmpty()) {
                showEndRoundDialog("¡RONDA LIMPIA!", currentScore) // No debería pasar, pero por si acaso
                return@launch
            }

            val chanceToGuessCorrectly = 50
            if (Random.nextInt(100) < chanceToGuessCorrectly) {
                // Adivina la mejor
                val machineAnswer = unrevealedAnswers.maxByOrNull { it.points }!!
                checkAnswer(machineAnswer.text)
            } else {
                // Falla
                checkAnswer(sillyAnswers.random()) // Llama a checkAnswer con una respuesta tonta
            }
        }
    }

    // --- NUEVA: Configura el botón de micrófono ---
    // En GamePlayActivity.kt

    // ¡¡REEMPLAZA ESTA FUNCIÓN!!
    // ¡¡REEMPLAZA ESTA FUNCIÓN!!
    /**
     * MODIFICADA: Sabe si "Checar" (Host) o "Enviar" (Cliente)
     */
    private fun setupMicButton() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {

            override fun onReadyForSpeech(params: Bundle?) {
                binding.btnMic.setImageResource(android.R.drawable.presence_audio_online)
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                isRecording = false
                binding.btnMic.setImageResource(android.R.drawable.ic_btn_speak_now)
            }

            override fun onError(error: Int) {
                isRecording = false
                answerTimer?.cancel()
                binding.pbTimer.visibility = View.GONE
                binding.btnMic.setImageResource(android.R.drawable.ic_btn_speak_now)

                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    // Si soy Host u Offline, yo proceso el strike
                    if (isHost || !isMultiplayer) {
                        toast("No entendí. ¡Incorrecto!")
                        vibrateOnError()
                        addStrike()
                    } else {
                        // Soy Cliente, le aviso al Host que no entendí
                        toast("No entendí. Enviando...")
                        binding.btnMic.isEnabled = false
                        lifecycleScope.launch(Dispatchers.IO) {
                            ConnectionManager.dataOut?.writeUTF("GUESS:error_no_match")
                            ConnectionManager.dataOut?.flush()
                        }
                        startMachineTurn() // <-- Pone isPlayerTurn = false
                    }
                } else {
                    toast("Error de micrófono, intenta de nuevo.")
                }

                // Reinicia el timer de pensar SI es mi turno y no he perdido
                if (isPlayerTurn && playerStrikes < 3) {
                    startThinkingTimer()
                }
            }

            override fun onResults(results: Bundle?) {
                isRecording = false
                answerTimer?.cancel()
                binding.pbTimer.visibility = View.GONE

                val spokenText = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.get(0)

                if (!spokenText.isNullOrEmpty()) {
                    if (isHost || !isMultiplayer) {
                        // Soy Host u Offline: Yo checo la respuesta
                        checkAnswer(spokenText)
                    } else {
                        // Soy Cliente: Le envío mi respuesta al Host
                        toast("¡Respuesta enviada! Esperando...")
                        binding.btnMic.isEnabled = false
                        lifecycleScope.launch(Dispatchers.IO) {
                            ConnectionManager.dataOut?.writeUTF("GUESS:$spokenText")
                            ConnectionManager.dataOut?.flush()
                        }
                        startMachineTurn() // <-- Pone isPlayerTurn = false
                    }
                } else {
                    // ... (misma lógica de 'onError' si el texto es vacío)
                    if (isHost || !isMultiplayer) {
                        toast("No entendí. ¡Incorrecto!")
                        vibrateOnError()
                        addStrike()
                    } else {
                        toast("No entendí. Enviando...")
                        binding.btnMic.isEnabled = false
                        lifecycleScope.launch(Dispatchers.IO) {
                            ConnectionManager.dataOut?.writeUTF("GUESS:error_empty")
                            ConnectionManager.dataOut?.flush()
                        }
                        startMachineTurn()
                    }
                    if (isPlayerTurn && playerStrikes < 3) {
                        startThinkingTimer()
                    }
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        binding.btnMic.setOnClickListener {
            if (!binding.btnMic.isEnabled) return@setOnClickListener
            if (isRecording) {
                stopRecording()
            } else {
                checkAudioPermission()
            }
        }
    }

    // --- NUEVA: Lógica del Micrófono 1 (Pedir permiso) ---
    private fun checkAudioPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                startRecording()
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    // --- NUEVA: Lógica del Micrófono 2 (Empezar) ---
    private fun startRecording() {
        if (!isRecording) {
            thinkingTimer?.cancel()
            isRecording = true
            toast("¡Escuchando...")
            binding.pbTimer.visibility = View.VISIBLE // Muestra la barra

            speechRecognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX")
            })

            // --- NUEVO: Timer Visual ---
            answerTimer?.cancel()
            answerTimer = lifecycleScope.launch {
                val totalTime = 5000L
                val interval = 50L
                var currentTime = totalTime
                binding.pbTimer.max = totalTime.toInt()
                binding.pbTimer.progress = totalTime.toInt()

                while (currentTime > 0) {
                    delay(interval)
                    currentTime -= interval
                    binding.pbTimer.progress = currentTime.toInt()
                }

                if (isRecording) {
                    // ¡¡CAMBIO AQUÍ!!
                    speakingTimeUp() // Llamamos a la función renombrada
                }
            }
        }
    }

    private fun speakingTimeUp() {
        if (isRecording) {
            stopRecording()

            // Pausamos todo
            thinkingTimer?.cancel()
            binding.btnMic.isEnabled = false // Desactivamos

            lifecycleScope.launch {
                toast("¡Se acabó el tiempo para hablar!")
                binding.pbTimer.visibility = View.GONE

                // Ponemos el sonido de error
                soundPool?.play(wrongSoundId, 1f, 1f, 0, 0, 1f)
                vibrateOnError()

                delay(2000) // Pausa por el sonido

                addStrike() // ¡Strike!

                // Si el juego no se acabó, reactivamos
                if (isPlayerTurn && playerStrikes < 3) {
                    binding.btnMic.isEnabled = true
                    startThinkingTimer()
                }
            }
        }
    }

    // En GamePlayActivity.kt

    // ¡¡REEMPLAZA ESTA FUNCIÓN!!
    private fun startThinkingTimer() {
        // Cancela cualquier timer que estuviera corriendo
        thinkingTimer?.cancel()
        answerTimer?.cancel()

        // ¡¡CAMBIO AQUÍ!!
        // Reactivamos el botón por si estaba en cooldown
        binding.btnMic.isEnabled = true
        isRecording = false
        binding.btnMic.setImageResource(android.R.drawable.ic_btn_speak_now)

        // Muestra y resetea la barra de progreso
        binding.pbTimer.visibility = View.VISIBLE
        binding.pbTimer.progress = 5000 // 5 segundos

        thinkingTimer = lifecycleScope.launch {
            // ... (el resto de la lógica del timer se queda igual) ...
            val totalTime = 5000L
            val interval = 50L
            var currentTime = totalTime

            binding.pbTimer.max = totalTime.toInt()
            binding.pbTimer.progress = totalTime.toInt()

            while (currentTime > 0) {
                delay(interval)
                currentTime -= interval
                binding.pbTimer.progress = currentTime.toInt()
            }

            // ¡Se acabó el tiempo para PENSAR!
            if (!isRecording) { // Solo si no ha presionado el mic
                binding.pbTimer.visibility = View.GONE

                // Pausamos
                binding.btnMic.isEnabled = false
                toast("¡Se acabó el tiempo para pensar!")
                soundPool?.play(wrongSoundId, 1f, 1f, 0, 0, 1f)
                vibrateOnError()

                delay(2000) // Pausa por el sonido

                addStrike()

                // Si el juego no se acabó, reactivamos
                if (isPlayerTurn && playerStrikes < 3) {
                    binding.btnMic.isEnabled = true
                    startThinkingTimer()
                }
            }
        }
    }
    // --- NUEVA: Lógica del Micrófono 3 (Parar) ---
    private fun stopRecording() {
        if (isRecording) {
            isRecording = false
            answerTimer?.cancel() // Detenemos el timer
            speechRecognizer?.stopListening()
        }
    }

    // --- NUEVA: Lógica para checar la respuesta ---

    private fun vibrateOnError() {
        if (vibrator?.hasVibrator() == true) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Para Android 8+
                vibrator?.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                // Para versiones viejas
                vibrator?.vibrate(400)
            }
        }
    }

    // En GamePlayActivity.kt

    // ¡¡REEMPLAZA ESTA FUNCIÓN!!
    private fun checkAnswer(spokenText: String) {
        // 1. Pausamos todo
        thinkingTimer?.cancel()
        binding.btnMic.isEnabled = false // Desactivamos el mic del Host

        lifecycleScope.launch {
            val normalizedSpokenText = normalizeText(spokenText)
            var foundMatch = false
            var pointsGained = 0
            var answerIndex = -1

            // 2. Buscamos el match
            currentQuestionData?.answers?.forEachIndexed { index, answer ->
                val normalizedAnswer = normalizeText(answer.text)
                if (normalizedAnswer == normalizedSpokenText && !answer.isRevealed) {
                    foundMatch = true
                    pointsGained = answer.points
                    answerIndex = index
                }
            }

            // 3. Lógica de ACIERTO
            if (foundMatch) {
                hasAnyAnswerBeenRevealed = true
                answerAdapter.revealAnswer(answerIndex) // Host revela
                soundPool?.play(correctSoundId, 1f, 1f, 0, 0, 1f) // Host suena

                if (isPlayerTurn) {
                    currentScore += pointsGained
                    toast("¡Correcto! +$pointsGained puntos. (Total: $currentScore)")
                } else {
                    toast("¡'$botName' acierta! +$pointsGained puntos.")
                }

                // ¡¡ENVIAR COMANDO!!
                if (isMultiplayer && isHost) {
                    launch(Dispatchers.IO) {
                        // "REVEAL:indice_de_respuesta"
                        ConnectionManager.dataOut?.writeUTF("REVEAL:$answerIndex")
                        ConnectionManager.dataOut?.flush()
                    }
                }

                delay(1500) // Pausa por el sonido

                // 4. Checamos si se acabó
                var allAreNowRevealed = true
                currentQuestionData?.answers?.forEach { if (!it.isRevealed) allAreNowRevealed = false }

                if (allAreNowRevealed) {
                    showEndRoundDialog(if (isPlayerTurn) "¡GANASTE!" else "¡'$botName' GANA!", currentScore)
                } else if (isStealAttempt) {
                    showEndRoundDialog(if (isPlayerTurn) "¡ROBO EXITOSO!" else "¡'$botName' ROBA!", currentScore)
                }
                else if (!isPlayerTurn) { // Sigue el turno del oponente
                    if (isMultiplayer) {
                        startMachineTurn() // Host se pone a escuchar
                    } else {
                        machineMakesAGuess() // IA sigue jugando
                    }
                }
                else if (isPlayerTurn) { // Sigue mi turno
                    binding.btnMic.isEnabled = true
                    startThinkingTimer()
                }

            }
            // 5. Lógica de FALLO
            else {
                toast("¡Incorrecto!")
                soundPool?.play(wrongSoundId, 1f, 1f, 0, 0, 1f)
                vibrateOnError()

                // ¡¡ENVIAR COMANDO!!
                if (isMultiplayer && isHost) {
                    launch(Dispatchers.IO) {
                        ConnectionManager.dataOut?.writeUTF("WRONG")
                        ConnectionManager.dataOut?.flush()
                    }
                }

                delay(1500) // Pausa por el sonido

                if (isStealAttempt) {
                    // ... (lógica de robo fallido y empate)
                } else {
                    addStrike() // Esto enviará el strike

                    if (!isPlayerTurn) {
                        if (isMultiplayer && machineStrikes < 3) {
                            startMachineTurn() // Host se pone a escuchar
                        } else if (!isMultiplayer && machineStrikes < 3) {
                            machineMakesAGuess() // IA sigue jugando
                        }
                    }
                    else if (isPlayerTurn && playerStrikes < 3) {
                        binding.btnMic.isEnabled = true
                        startThinkingTimer()
                    }
                }
            }
        }
    }

    private fun addStrike() {
        val strikeTakerIsPlayer = isPlayerTurn // Guardamos quién es

        if (isPlayerTurn) {
            playerStrikes++
            currentStrikes = playerStrikes
        } else {
            machineStrikes++
            currentStrikes = machineStrikes
        }

        // ¡¡ENVIAR COMANDO!!
        if (isMultiplayer && isHost) {
            lifecycleScope.launch(Dispatchers.IO) {
                // "STRIKE:numero_de_strike"
                ConnectionManager.dataOut?.writeUTF("STRIKE:$currentStrikes")
                ConnectionManager.dataOut?.flush()
            }
        }

        // Aplicamos el strike (en el Host)
        applyStrikeToUI(currentStrikes)

        // Checamos si se acabó
        if (currentStrikes >= 3) {
            if (strikeTakerIsPlayer) {
                binding.btnMic.visibility = View.GONE
                startMachineStealAttempt()
            } else {
                startPlayerStealAttempt()
            }
        }
    }

    private fun startPlayerStealAttempt() {
        // (Llamado cuando la MÁQUINA o el CLIENTE fallan 3 veces)

        // ¡¡AQUÍ ESTÁ EL FIX!!
        if (isMultiplayer) {
            // MODO MULTI (Host): Le digo al Cliente que robe
            isPlayerTurn = false // (Para mí, Host, es el turno del oponente)
            isStealAttempt = true

            showGameAlert("¡3 Strikes de '$botName'!\n¡Te toca robar!") {
                // El Host se pone a escuchar la respuesta del Cliente
                startMachineTurn()
            }

            // Enviamos la orden
            lifecycleScope.launch(Dispatchers.IO) {
                ConnectionManager.dataOut?.writeUTF("STEAL:opponent") // "Oponente" (tú, Cliente) roba
                ConnectionManager.dataOut?.flush()
            }

        } else {
            // MODO OFFLINE (Jugador): Yo robo
            isPlayerTurn = true
            isStealAttempt = true

            showGameAlert("¡3 Strikes de la Máquina!\n¡Tu turno de robar! (1 intento)") {
                binding.btnMic.visibility = View.VISIBLE
                startThinkingTimer() // Inicia el timer de pensar para el robo
            }
        }
    }

    private fun startMachineStealAttempt() {
        // (Llamado cuando el JUGADOR (local) falla 3 veces)

        binding.btnMic.visibility = View.GONE

        // ¡¡AQUÍ ESTÁ EL FIX!!
        if (isMultiplayer) {
            // MODO MULTI (Host): Le digo al Cliente que YO (Host) voy a robar
            isPlayerTurn = true // (Es mi turno, Host)
            isStealAttempt = true

            showGameAlert("¡3 STRIKES!\n¡Intentaré robar!") {
                // El Host activa su propio micrófono para robar
                binding.btnMic.visibility = View.VISIBLE
                startThinkingTimer()
            }

            // Enviamos la orden
            lifecycleScope.launch(Dispatchers.IO) {
                ConnectionManager.dataOut?.writeUTF("STEAL:player") // "Player" (yo, Host) roba
                ConnectionManager.dataOut?.flush()
            }

        } else {
            // MODO OFFLINE (IA): La IA roba
            isPlayerTurn = false
            isStealAttempt = true

            showGameAlert("¡3 STRIKES!\nLa máquina intentará robar.") {
                machineMakesAGuess()
            }
        }
    }


    private fun handleNetworkCommand(command: String) {
        val parts = command.split(":")
        when (parts[0]) {
            "GUESS" -> {
                val guess = parts[1]
                toast("'$botName' dice: $guess")
                checkAnswer(guess) // El Host (cerebro) checa la respuesta
            }
            "REVEAL" -> {
                val index = parts[1].toInt()
                answerAdapter.revealAnswer(index)
                soundPool?.play(correctSoundId, 1f, 1f, 0, 0, 1f)
                if (isPlayerTurn) {
                    startThinkingTimer()
                }
            }
            "WRONG" -> {
                toast("¡Incorrecto!")
                soundPool?.play(wrongSoundId, 1f, 1f, 0, 0, 1f)
                vibrateOnError()
                if (isPlayerTurn) {
                    startThinkingTimer()
                }
            }
            "STRIKE" -> {
                val strikeNum = parts[1].toInt()
                applyStrikeToUI(strikeNum)
            }
            "STEAL" -> {
                if (parts[1] == "player") {
                    isPlayerTurn = false
                    showGameAlert("¡3 STRIKES!\n¡'$botName' intentará robar!") {}
                } else if (parts[1] == "opponent") {
                    isPlayerTurn = true
                    isStealAttempt = true
                    showGameAlert("¡3 Strikes del Oponente!\n¡Tu turno de robar! (1 intento)") {
                        binding.btnMic.visibility = View.VISIBLE
                        startThinkingTimer()
                    }
                }
            }

            "END_ROUND" -> {
                val title = parts[1] // Este es el título de PERDEDOR
                val score = parts[2].toInt()

                // Paramos de escuchar
                clientListenerJob?.cancel()

                // Mostramos la alerta de fin de ronda (la misma que el Host)
                showEndRoundDialog(title, score)
            }
            // --- FIN DEL FIX ---
        }
    }

    /**
     * NUEVA: Función helper para poner los strikes en la UI (para ambos)
     */
    private fun applyStrikeToUI(strikeCount: Int) {
        when (strikeCount) {
            1 -> binding.ivStrike1.apply { setImageResource(R.drawable.ic_strike_off); visibility = View.VISIBLE }
            2 -> binding.ivStrike2.apply { setImageResource(R.drawable.ic_strike_off); visibility = View.VISIBLE }
            3 -> binding.ivStrike3.apply { setImageResource(R.drawable.ic_strike_off); visibility = View.VISIBLE }
        }
    }
    // --- NUEVA: Función para limpiar texto ---
    private fun normalizeText(text: String): String {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
        return normalized.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase()
            .trim()
    }

    // --- NUEVA: Ocultar barras de sistema (Modo Inmersivo) ---
    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onStop() {
        super.onStop()
        machineAITask?.cancel()
        faceOffDialog?.dismiss()

        // ¡¡NUEVO!! Si somos el cliente, dejamos de escuchar
        clientListenerJob?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()

        // NUEVO: Liberar el SoundPool
        soundPool?.release()
        soundPool = null
    }

    private fun resetStrikeUI() {
        binding.ivStrike1.visibility = View.INVISIBLE
        binding.ivStrike2.visibility = View.INVISIBLE
        binding.ivStrike3.visibility = View.INVISIBLE
    }
}
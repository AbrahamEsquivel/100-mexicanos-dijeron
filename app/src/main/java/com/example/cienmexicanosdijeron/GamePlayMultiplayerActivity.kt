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
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
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
import kotlinx.coroutines.*
import org.json.JSONObject
import java.text.Normalizer
import kotlin.random.Random

class GamePlayMultiplayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGamePlayBinding

    // --- Estado general ---
    private var machineAITask: Job? = null
    private var faceOffDialog: AlertDialog? = null
    private var isFaceOffWon = false

    private var currentQuestionData: QuestionData? = null
    private lateinit var answerAdapter: AnswerAdapter

    // --- Voz / audio ---
    private var speechRecognizer: SpeechRecognizer? = null
    private var isRecording = false

    private var thinkingTimer: Job? = null
    private var answerTimer: Job? = null

    private var soundPool: SoundPool? = null
    private var wrongSoundId: Int = 0
    private var correctSoundId: Int = 0
    private var vibrator: Vibrator? = null

    // --- Juego / rondas ---
    private var isPlayerTurn = true
    private var isStealAttempt = false
    private var hasAnyAnswerBeenRevealed = false

    private var playerStrikes = 0
    private var machineStrikes = 0
    private var currentStrikes = 0
    private var currentScore = 0

    private var botName: String = "Oponente"
    private var playerName: String = "Jugador"

    // --- Multijugador / red ---
    private var isMultiplayer: Boolean = true
    private var isHost: Boolean = false
    private var clientListenerJob: Job? = null
    private var hostBuzzerListenerJob: Job? = null

    private val sillyAnswers = listOf("¿Pato?", "No sé, ¿Uvas?", "Maradona", "El... ¿Sol?", "Tacos")

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) startRecording()
            else toast("Necesitamos el permiso del micrófono para jugar.")
        }

    // --------------------------------------------------------------------
    // onCreate
    // --------------------------------------------------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGamePlayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hideSystemUI()
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator?
        setupSounds()

        // 🔥 ESTA ACTIVITY ES SOLO MULTIJUGADOR
        isMultiplayer = true
        isHost = intent.getBooleanExtra("IS_HOST", false)
        val opponentName = intent.getStringExtra("OPPONENT_NAME") ?: "Oponente"
        val categoryName = intent.getStringExtra("SELECTED_CATEGORY") ?: "Sin Categoría"

        val sp = getSharedPreferences("GamePrefs", Context.MODE_PRIVATE)
        playerName = sp.getString("PlayerName", "Jugador") ?: "Jugador"
        botName = opponentName

        setupMicButton()

        if (isHost) {
            initializeHostGame(categoryName)
        } else {
            initializeClientGame()
        }
    }

    // --------------------------------------------------------------------
    // Carga / envío de preguntas
    // --------------------------------------------------------------------

    private fun initializeHostGame(categoryName: String) {
        currentQuestionData = loadQuestionForCategory(categoryName)

        if (currentQuestionData != null) {
            binding.tvQuestion.text = currentQuestionData!!.question
            setupRecyclerView()

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val questionJson = convertQuestionDataToJson(currentQuestionData!!)
                    ConnectionManager.dataOut?.writeUTF("QUESTION::SEP::$questionJson")
                    ConnectionManager.dataOut?.flush()

                    withContext(Dispatchers.Main) {
                        showFaceOffDialog()
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        toast("Error al enviar pregunta")
                    }
                }
            }
        } else {
            binding.tvQuestion.text = "Error: No se encontró pregunta"
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
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    // --------------------------------------------------------------------
    // Face-off (buzzer)
    // --------------------------------------------------------------------

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

        val btnPlayer = dialogView.findViewById<Button>(R.id.btnPlayerBuzzer)
        val btnMachine = dialogView.findViewById<Button>(R.id.btnMachineBuzzer)

        // --- Lógica del Jugador ---
        btnPlayer.setOnClickListener {
            if (!isFaceOffWon) {
                if (isMultiplayer && !isHost) {
                    // CLIENTE: Envía BUZZ
                    isFaceOffWon = true
                    btnPlayer.isPressed = true
                    btnPlayer.text = "¡PRESIONADO!"
                    btnPlayer.isEnabled = false
                    lifecycleScope.launch(Dispatchers.IO) {
                        ConnectionManager.dataOut?.writeUTF("BUZZ")
                        ConnectionManager.dataOut?.flush()
                    }
                } else {
                    // HOST: Gana directo
                    handleBuzzerResult(didPlayerWin = true)
                }
            }
        }

        // --- Lógica del Oponente ---
        btnMachine.isClickable = false
        btnMachine.alpha = 0.5f
        btnMachine.text = "@$botName"

        if (isHost) {
            // HOST: Escucha el BUZZ del cliente
            hostBuzzerListenerJob?.cancel()
            hostBuzzerListenerJob = lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val clientCommand = ConnectionManager.dataIn?.readUTF()
                    if (clientCommand == "BUZZ" && !isFaceOffWon) {
                        withContext(Dispatchers.Main) {
                            handleBuzzerResult(didPlayerWin = false, isClientWinner = true)
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        faceOffDialog?.show()
    }

    private fun handleBuzzerResult(didPlayerWin: Boolean, isClientWinner: Boolean = false) {
        if (isFaceOffWon) return
        isFaceOffWon = true
        machineAITask?.cancel()
        hostBuzzerListenerJob?.cancel()

        val btnPlayer = faceOffDialog?.findViewById<Button>(R.id.btnPlayerBuzzer)
        val btnMachine = faceOffDialog?.findViewById<Button>(R.id.btnMachineBuzzer)

        lifecycleScope.launch {
            if (isClientWinner) {
                toast("¡'$botName' ganó el turno!")
                launch(Dispatchers.IO) {
                    ConnectionManager.dataOut?.writeUTF("YOU_WIN")
                    ConnectionManager.dataOut?.flush()
                }
                delay(1000)
                faceOffDialog?.dismiss()
                startMachineTurn()
            } else if (didPlayerWin) {
                btnPlayer?.isPressed = true
                btnPlayer?.text = "¡GANASTE!\nPRIMERO"
                toast("¡Ganaste el turno!")
                launch(Dispatchers.IO) {
                    ConnectionManager.dataOut?.writeUTF("YOU_LOSE")
                    ConnectionManager.dataOut?.flush()
                }
                delay(1000)
                faceOffDialog?.dismiss()
                startPlayerTurn()
            }
        }
    }

    // --------------------------------------------------------------------
    // Turnos
    // --------------------------------------------------------------------

    private fun startPlayerTurn() {
        cleanupAllListeners()

        isPlayerTurn = true
        isStealAttempt = false
        hasAnyAnswerBeenRevealed = false
        playerStrikes = 0
        currentStrikes = 0
        resetStrikeUI()

        isRecording = false
        binding.pbTimer.visibility = View.GONE
        binding.pbTimer.progress = 0

        binding.btnMic.apply {
            visibility = View.VISIBLE
            isEnabled = true
            alpha = 1f
            setImageResource(android.R.drawable.ic_btn_speak_now)
        }

        toast("¡Tu turno! Piensa tu respuesta...")
        startThinkingTimer()
    }

    private fun startMachineTurn() {
        // Turno del otro → yo solo observo
        cleanupAllListeners()

        isPlayerTurn = false
        isStealAttempt = false

        // Ojo: NO resetees strikes aquí si quieres que se acumulen para robo.
        // Dejamos machineStrikes/currentStrikes tal como estaban.
        faceOffDialog?.dismiss()

        currentQuestionData?.let {
            binding.tvQuestion.text = it.question
            binding.rvAnswers.adapter = answerAdapter
            binding.tvQuestion.visibility = View.VISIBLE
            binding.rvAnswers.visibility = View.VISIBLE
        }

        binding.btnMic.apply {
            visibility = View.GONE
            isEnabled = false
            alpha = 0.5f
            setImageResource(android.R.drawable.ic_btn_speak_now)
        }

        if (isMultiplayer) {
            toast("Turno de '$botName'. Esperando...")

            clientListenerJob?.cancel()
            clientListenerJob = lifecycleScope.launch(Dispatchers.IO) {
                try {
                    while (isActive && !isPlayerTurn) {
                        val command = ConnectionManager.dataIn?.readUTF() ?: break
                        withContext(Dispatchers.Main) {
                            handleNetworkCommand(command)
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        toast("¡Error de conexión!")
                    }
                }
            }
        }
    }

    private fun cleanupAllListeners() {
        // En esta versión unificada, SOLO detenemos timers y el buzzer.
        // NO detenemos clientListenerJob porque debe seguir escuchando para el "Reset Game" o siguiente ronda.
        thinkingTimer?.cancel()
        answerTimer?.cancel()
        hostBuzzerListenerJob?.cancel()
    }

    // --------------------------------------------------------------------
    // MIC / reconocimiento de voz
    // --------------------------------------------------------------------

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
                thinkingTimer?.cancel()
                answerTimer?.cancel()
                binding.pbTimer.visibility = View.GONE
                binding.btnMic.setImageResource(android.R.drawable.ic_btn_speak_now)

                val isSpeechError = (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)

                if (isSpeechError) {
                    if (isMultiplayer && !isHost) {
                        // Cliente: Solo notifica error, no decide
                        sendClientGuess("error_no_match")
                    } else {
                        // Host: Decide fallo
                        toast("No entendí. ¡Incorrecto!")
                        vibrateOnError()
                        addStrike()
                    }
                } else {
                    toast("Error de micrófono. Intenta de nuevo.")
                    if (isPlayerTurn && !isStealAttempt && playerStrikes < 3) {
                        restoreMicButton()
                        startThinkingTimer()
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                isRecording = false
                thinkingTimer?.cancel()
                answerTimer?.cancel()
                binding.pbTimer.visibility = View.GONE

                val spokenText = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.getOrNull(0)

                if (spokenText.isNullOrEmpty()) {
                    if (isMultiplayer && !isHost) sendClientGuess("error_empty")
                    else {
                        toast("No entendí. ¡Incorrecto!")
                        vibrateOnError()
                        addStrike()
                    }
                    return
                }

                // 🔥 LÓGICA CORREGIDA: ENVIAR Y OLVIDAR
                if (isMultiplayer && !isHost) {
                    toast("Enviando respuesta...")
                    binding.btnMic.isEnabled = false // Bloqueo visual
                    binding.btnMic.alpha = 0.5f
                    sendClientGuess(spokenText)
                    // YA NO LLAMAMOS A waitForHostVerification()
                } else {
                    // Host procesa directo
                    checkAnswer(spokenText)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        binding.btnMic.setOnClickListener {
            if (!binding.btnMic.isEnabled) return@setOnClickListener
            if (isRecording) stopRecording() else checkAudioPermission()
        }
    }

    // Helper pequeña para enviar mensajes
    private fun sendClientGuess(text: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            ConnectionManager.dataOut?.writeUTF("GUESS:$text")
            ConnectionManager.dataOut?.flush()
        }
    }

    private fun checkAudioPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> startRecording()

            else -> requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRecording() {
        if (!isRecording) {
            thinkingTimer?.cancel()
            isRecording = true
            toast("¡Escuchando...")
            binding.pbTimer.visibility = View.VISIBLE

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX")
            }
            speechRecognizer?.startListening(intent)

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
                if (isRecording) speakingTimeUp()
            }
        }
    }

    private fun speakingTimeUp() {
        if (isRecording) {
            stopRecording()

            if (isMultiplayer && !isHost) {
                toast("¡Tiempo agotado! Enviando...")

                // CORRECCIÓN: Solo enviamos el mensaje.
                // Ya NO esperamos respuesta aquí, el listener principal lo hará.
                lifecycleScope.launch(Dispatchers.IO) {
                    ConnectionManager.dataOut?.writeUTF("GUESS:TIMEOUT")
                    ConnectionManager.dataOut?.flush()
                }

            } else {
                // Lógica del HOST (se queda igual)
                toast("¡Se acabó el tiempo para hablar!")
                binding.pbTimer.visibility = View.GONE
                soundPool?.play(wrongSoundId, 1f, 1f, 0, 0, 1f)
                vibrateOnError()

                if (isStealAttempt) {
                    checkAnswer("TIMEOUT")
                } else {
                    addStrike()
                    if (isPlayerTurn && playerStrikes < 3) {
                        startThinkingTimer()
                    }
                }
            }
        }
    }

    private fun startThinkingTimer() {
        thinkingTimer?.cancel()
        answerTimer?.cancel()
        restoreMicButton()

        binding.pbTimer.visibility = View.VISIBLE
        binding.pbTimer.max = 5000
        binding.pbTimer.progress = 5000

        thinkingTimer = lifecycleScope.launch {
            var currentTime = 5000L
            while (currentTime > 0) {
                delay(50)
                currentTime -= 50
                binding.pbTimer.progress = currentTime.toInt()
            }

            if (!isRecording) {
                binding.pbTimer.visibility = View.GONE
                binding.btnMic.isEnabled = false
                toast("¡Se acabó el tiempo para pensar!")
                soundPool?.play(wrongSoundId, 1f, 1f, 0, 0, 1f)
                vibrateOnError()
                delay(1500)

                if (isMultiplayer && !isHost) {
                    sendClientGuess("TIMEOUT")
                } else {
                    if (isStealAttempt) checkAnswer("TIMEOUT")
                    else {
                        addStrike()
                        if (isPlayerTurn && playerStrikes < 3) {
                            delay(1000)
                            restoreMicButton()
                            startThinkingTimer()
                        }
                    }
                }
            }
        }
    }

    private fun showStealResultDialog(success: Boolean) {
        val winnerCode: String
        val reason = "STEAL"

        // Lógica para determinar ganador y códigos
        if (success) {
            // Robo Exitoso
            if (isPlayerTurn) winnerCode = "HOST_WINS" // Host robó bien
            else winnerCode = "CLIENT_WINS"            // Cliente robó bien
        } else {
            // Robo Fallido (Gana el que defendía)
            if (isPlayerTurn) winnerCode = "CLIENT_WINS" // Host falló -> Cliente gana
            else winnerCode = "HOST_WINS"                // Cliente falló -> Host gana
        }

        // Título local (para el Host)
        val localTitle = if (winnerCode == "HOST_WINS") {
            if (isPlayerTurn) "¡ROBO EXITOSO!\n¡GANASTE LOS PUNTOS!" else "¡ROBO FALLIDO DEL OPONENTE!\n¡GANASTE!"
        } else {
            if (!isPlayerTurn) "¡ROBO EXITOSO DEL OPONENTE!\nPerdiste los puntos." else "¡ROBO FALLIDO!\n¡EL OPONENTE GANA!"
        }

        showEndRoundDialogInternal(localTitle, winnerCode, reason, currentScore)
    }


    private fun stopRecording() {
        if (isRecording) {
            isRecording = false
            answerTimer?.cancel()
            speechRecognizer?.stopListening()
        }
    }

    private var hostWaitJob: Job? = null
    private fun restoreMicButton() {
        binding.btnMic.apply {
            visibility = View.VISIBLE
            isEnabled = true
            alpha = 1.0f
            setImageResource(android.R.drawable.ic_btn_speak_now)
        }
        isRecording = false
    }

    private fun initializeClientGame() {
        toast("Conectado. Esperando pregunta del Host...")
        binding.btnMic.visibility = View.GONE

        // Iniciamos UN SOLO listener que durará toda la actividad
        clientListenerJob?.cancel()
        clientListenerJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    // Bloqueamos aquí esperando CUALQUIER mensaje del Host
                    val command = ConnectionManager.dataIn?.readUTF() ?: break

                    withContext(Dispatchers.Main) {
                        // Procesamos todo en una sola función central
                        handleNetworkCommand(command)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    toast("¡Error de conexión con el Host!")
                }
            }
        }
    }

    // --------------------------------------------------------------------
    // Lógica de respuestas
    // --------------------------------------------------------------------

    private fun checkAnswer(spokenText: String) {
        thinkingTimer?.cancel()
        answerTimer?.cancel()
        binding.btnMic.isEnabled = false

        lifecycleScope.launch {
            if (spokenText == "TIMEOUT" || spokenText == "error_no_match" || spokenText == "error_empty") {
                handleWrongAnswer()
                return@launch
            }

            val normalizedSpokenText = normalizeText(spokenText)
            var foundMatch = false
            var pointsGained = 0
            var answerIndex = -1

            currentQuestionData?.answers?.forEachIndexed { index, answer ->
                val normalizedAnswer = normalizeText(answer.text)
                if (normalizedAnswer == normalizedSpokenText && !answer.isRevealed) {
                    foundMatch = true
                    pointsGained = answer.points
                    answerIndex = index
                }
            }

            if (foundMatch) {
                hasAnyAnswerBeenRevealed = true
                answerAdapter.revealAnswer(answerIndex)
                soundPool?.play(correctSoundId, 1f, 1f, 0, 0, 1f)
                currentScore += pointsGained

                // --- LÓGICA DE DETECCIÓN DE VICTORIA ---
                val allAreNowRevealed = currentQuestionData?.answers?.all { it.isRevealed } == true

                // Mensajes personalizados
                if (isStealAttempt) {
                    // Si acertó en el robo, ES VICTORIA AUTOMÁTICA
                    val winnerName = if (isPlayerTurn) playerName else botName
                    toast("¡ROBO CORRECTO! ¡$winnerName GANA LA RONDA!")
                } else if (allAreNowRevealed) {
                    // Si completó el tablero, ES VICTORIA
                    val winnerName = if (isPlayerTurn) playerName else botName
                    toast("¡TABLERO COMPLETO! ¡$winnerName GANA!")
                } else {
                    // Solo acertó una, sigue el juego
                    if (isPlayerTurn) {
                        toast("¡Correcto! +$pointsGained puntos.")
                    } else {
                        toast("¡$botName acierta!")
                    }
                }

                // Enviar REVEAL en multiplayer
                if (isMultiplayer && isHost) {
                    launch(Dispatchers.IO) {
                        ConnectionManager.dataOut?.writeUTF("REVEAL:$answerIndex")
                        ConnectionManager.dataOut?.flush()
                    }
                }

                delay(1500)

                // Decisiones de fin de juego
                when {
                    isStealAttempt -> showStealResultDialog(success = true)

                    allAreNowRevealed -> {
                        // Determinamos el título según quién acertó la última
                        val title = if (isPlayerTurn) "¡GANASTE LA RONDA!" else "¡$botName GANA LA RONDA!"
                        showEndRoundDialog(title, currentScore)
                    }

                    !isPlayerTurn -> { /* Esperamos al oponente */ }

                    else -> {
                        binding.btnMic.isEnabled = true
                        startThinkingTimer()
                    }
                }

            } else {
                handleWrongAnswer()
            }
        }
    }





    private suspend fun handleWrongAnswer() {
        toast("¡Incorrecto!")
        soundPool?.play(wrongSoundId, 1f, 1f, 0, 0, 1f)
        vibrateOnError()

        delay(1500)

        if (isStealAttempt) {
            binding.btnMic.visibility = View.GONE

            // 🔥 CAMBIO: Quitamos showEmpateDialog.
            // Si es un robo y fallan, se acaba la ronda formalmente con "success=false".
            // Esto asegura que se envíe el comando END_ROUND al cliente.
            showStealResultDialog(success = false)

        } else {
            val wasLocal = isPlayerTurn
            addStrike()

            if (wasLocal && playerStrikes < 3 && !isStealAttempt) {
                binding.btnMic.isEnabled = true
                startThinkingTimer()
            }
            // Si fue remoto (cliente), solo esperamos más comandos.
        }
    }

    private fun addStrike() {
        // 1. Aumentamos contadores
        if (isPlayerTurn) {
            playerStrikes++
            currentStrikes = playerStrikes
        } else {
            machineStrikes++
            currentStrikes = machineStrikes
        }

        // 2. Host notifica al cliente
        if (isHost) {
            lifecycleScope.launch(Dispatchers.IO) {
                ConnectionManager.dataOut?.writeUTF("STRIKE:$currentStrikes")
                ConnectionManager.dataOut?.flush()
            }
        }

        // 3. Actualizamos las X visuales
        applyStrikeToUI(currentStrikes)

        // 4. Checamos si murió (3 strikes)
        if (currentStrikes >= 3) {
            // 🔥 CORRECCIÓN: Silencio inmediato para todos antes de cambiar de fase
            stopRecording()

            if (isPlayerTurn) {
                // EL JUGADOR ACTUAL (LOCAL) PERDIÓ
                // Apagamos su micro y pasamos al robo del otro
                binding.btnMic.visibility = View.GONE
                binding.btnMic.isEnabled = false
                startMachineStealAttempt()
            } else {
                // EL OPONENTE (REMOTO) PERDIÓ
                // El local roba, así que no ocultamos el micro aquí (se activa en startPlayerStealAttempt)
                startPlayerStealAttempt()
            }
        }
    }

    private fun startPlayerStealAttempt() {
        // El Host (Player) va a robar
        isPlayerTurn = true
        isStealAttempt = true

        showGameAlert("¡3 Strikes de '$botName'!\n¡Tu turno de robar!") {
            binding.btnMic.visibility = View.VISIBLE
            startThinkingTimer()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            // CORRECCIÓN: Enviamos "opponent" para que el Cliente sepa que NO es su turno
            ConnectionManager.dataOut?.writeUTF("STEAL:opponent")
            ConnectionManager.dataOut?.flush()
        }
    }

    // Se ejecuta cuando el HOST pierde 3 strikes y le toca al CLIENTE robar
    private fun startMachineStealAttempt() {
        isPlayerTurn = false // Turno del otro (Cliente)
        isStealAttempt = true

        // 1. Aseguramos que el Host esté callado y sin botón
        stopRecording()
        binding.btnMic.visibility = View.GONE
        binding.btnMic.isEnabled = false

        // 2. Notificamos al cliente que ÉL (player/cliente) debe robar
        lifecycleScope.launch(Dispatchers.IO) {
            ConnectionManager.dataOut?.writeUTF("STEAL:player")
            ConnectionManager.dataOut?.flush()
        }

        showGameAlert("¡3 STRIKES!\n¡'$botName' intentará robar!") {
            // Solo esperar en silencio
        }

        // 3. Activamos el oído del Host
        if (isMultiplayer && isHost) {
            clientListenerJob?.cancel()
            clientListenerJob = lifecycleScope.launch(Dispatchers.IO) {
                try {
                    while (isActive) {
                        val command = ConnectionManager.dataIn?.readUTF() ?: break

                        withContext(Dispatchers.Main) {
                            handleNetworkCommand(command)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    // --------------------------------------------------------------------
    // Red: comandos
    // --------------------------------------------------------------------

    private fun handleNetworkCommand(command: String) {
        Log.d("NET_MSG", "Recibido: $command")

        if (command.startsWith("QUESTION::SEP::")) {
            val questionJson = command.substringAfter("QUESTION::SEP::")
            currentQuestionData = parseQuestionDataFromJson(questionJson)
            if (currentQuestionData != null) {
                binding.tvQuestion.text = currentQuestionData!!.question
                setupRecyclerView()
                showFaceOffDialog()
            }
            return
        }

        val parts = command.split(":")
        val action = parts[0]

        when (action) {
            "YOU_WIN" -> {
                faceOffDialog?.dismiss()
                toast("¡Ganaste el turno!")
                startPlayerTurn()
            }
            "YOU_LOSE" -> {
                faceOffDialog?.dismiss()
                toast("¡'$botName' ganó el turno!")
                startMachineTurn()
            }
            "GUESS" -> {
                if (isHost) {
                    val guess = command.substringAfter("GUESS:")
                    toast("El oponente dice: $guess")
                    checkAnswer(guess)
                }
            }
            "REVEAL" -> {
                val index = parts[1].toInt()
                answerAdapter.revealAnswer(index)
                soundPool?.play(correctSoundId, 1f, 1f, 0, 0, 1f)

                thinkingTimer?.cancel()
                answerTimer?.cancel()
                binding.pbTimer.visibility = View.GONE

                lifecycleScope.launch {
                    delay(1500)
                    if (isPlayerTurn && !isStealAttempt) {
                        restoreMicButton()
                        startThinkingTimer()
                    }
                }
            }

            // 🔥 AQUÍ ESTÁ LA CORRECCIÓN CLAVE 🔥
            "STRIKE" -> {
                val strikeNum = parts[1].toInt()
                applyStrikeToUI(strikeNum)

                lifecycleScope.launch {
                    toast("¡Incorrecto!")
                    soundPool?.play(wrongSoundId, 1f, 1f, 0, 0, 1f)
                    vibrateOnError()
                    delay(1500)

                    // SI LLEGAMOS AL 3er STRIKE, CORTAMOS TODO
                    if (strikeNum >= 3) {
                        stopRecording()
                        thinkingTimer?.cancel()
                        answerTimer?.cancel()
                        binding.btnMic.visibility = View.GONE
                        binding.btnMic.isEnabled = false
                        // No hacemos nada más, esperamos el comando "STEAL" que viene en camino
                    }
                    // SI ES MENOS DE 3, SEGUIMOS JUGANDO
                    else if (isPlayerTurn && !isStealAttempt) {
                        restoreMicButton()
                        startThinkingTimer()
                    }
                }
            }

            "STEAL" -> {
                thinkingTimer?.cancel()
                answerTimer?.cancel()
                binding.btnMic.visibility = View.GONE

                if (parts[1] == "player") {
                    // Host dice: "El Host falló. TE TOCA A TI (Cliente) ROBAR."
                    isPlayerTurn = true
                    isStealAttempt = true

                    showGameAlert("¡3 Strikes del Anfitrión!\n¡TU TURNO DE ROBAR!") {
                        binding.btnMic.visibility = View.VISIBLE
                        restoreMicButton()
                        startThinkingTimer()
                    }
                } else if (parts[1] == "opponent") {
                    // Host dice: "TÚ (Cliente) fallaste los 3 strikes. EL HOST ROBA."
                    isPlayerTurn = false
                    isStealAttempt = true

                    // Aseguramos silencio por si acaso
                    stopRecording()
                    binding.btnMic.visibility = View.GONE
                    binding.btnMic.isEnabled = false

                    showGameAlert("¡El Anfitrión intentará robar!") {
                        // Solo espero
                    }
                }
            }

            "END_ROUND" -> {
                val winnerCode = parts[1]
                val reason = parts[2]
                val score = parts[3].toInt()

                cleanupAllListeners()

                val finalTitle = if (winnerCode == "CLIENT_WINS") {
                    if (reason == "STEAL") "¡ROBO EXITOSO!\n¡GANASTE LA RONDA!" else "¡GANASTE LA RONDA!"
                } else {
                    if (reason == "STEAL") "¡TE ROBARON LOS PUNTOS!\nEl anfitrión gana." else "¡EL ANFITRIÓN GANA LA RONDA!"
                }

                showEndRoundDialog(finalTitle, score)
            }

            "RESET_GAME" -> {
                toast("El anfitrión reinició la partida.")
                val intent = Intent(this, SpinWheelActivity::class.java)
                startActivity(intent)
                finish()
            }
        }
    }

    // --------------------------------------------------------------------
    // Diálogos fin de ronda / robo / empate
    // --------------------------------------------------------------------

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
            onDismiss()
        }

        dialog.show()
    }

    private fun saveGameResult(winner: String, score: Int) {
        val sdf = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
        val currentDate = sdf.format(java.util.Date())

        val result = GameResult(
            playerName = this.playerName,
            botName = this.botName,
            winnerName = winner,
            finalScore = score,
            date = currentDate
        )

        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(applicationContext)
            db.gameResultDao().insert(result)
        }
    }

    private fun showEndRoundDialog(title: String, score: Int) {
        // Si soy host y llego aquí, debo construir el mensaje de red
        if (isHost) {
            val winnerCode = if (title.contains(playerName) || title.contains("GANASTE")) "HOST_WINS" else "CLIENT_WINS"
            showEndRoundDialogInternal(title, winnerCode, "NORMAL", score)
        } else {
            // Cliente solo muestra lo que recibió
            showEndRoundDialogUI(title, score)
        }
    }

    private fun showEndRoundDialogInternal(localTitle: String, winnerCode: String, reason: String, score: Int) {
        // 1. Enviar resultado al cliente
        if (isHost) {
            lifecycleScope.launch(Dispatchers.IO) {
                ConnectionManager.dataOut?.writeUTF("END_ROUND:$winnerCode:$reason:$score")
                ConnectionManager.dataOut?.flush()
            }
        }
        // 2. Mostrar localmente
        showEndRoundDialogUI(localTitle, score)
    }

    // Esta es la parte de UI pura (NO CAMBIA MUCHO, pero asegúrate de tenerla separada)
    private fun showEndRoundDialogUI(title: String, score: Int) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_end_round, null)
        val builder = AlertDialog.Builder(this)
        builder.setView(dialogView)
        builder.setCancelable(false)
        val dialog = builder.create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<TextView>(R.id.tvEndTitle).text = title
        dialogView.findViewById<TextView>(R.id.tvEndScore).text = "Puntaje Final: $score"

        val winnerName = if (title.contains("GANASTE") || title.contains(playerName)) playerName else botName
        saveGameResult(winnerName, score)

        dialogView.findViewById<Button>(R.id.btnPlayAgain).setOnClickListener {
            dialog.dismiss()
            if (isHost) {
                lifecycleScope.launch(Dispatchers.IO) {
                    ConnectionManager.dataOut?.writeUTF("RESET_GAME")
                    ConnectionManager.dataOut?.flush()
                }
            }
            val intent = Intent(this, SpinWheelActivity::class.java).apply {
                putExtra("IS_MULTIPLAYER", isMultiplayer)
                putExtra("IS_HOST", isHost)
                putExtra("OPPONENT_NAME", botName)
            }
            startActivity(intent)
            finish()
        }

        dialogView.findViewById<Button>(R.id.btnGoToMenu).setOnClickListener {
            dialog.dismiss()
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            finish()
        }
        dialog.show()
    }


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
            restartRound()
        }
        dialog.show()
    }

    private fun restartRound() {
        isPlayerTurn = true
        isStealAttempt = false
        hasAnyAnswerBeenRevealed = false
        playerStrikes = 0
        machineStrikes = 0
        currentStrikes = 0
        currentScore = 0

        resetStrikeUI()

        currentQuestionData?.answers?.forEach { it.isRevealed = false }
        answerAdapter.notifyDataSetChanged()

        showFaceOffDialog()
    }

    // --------------------------------------------------------------------
    // Utils de UI y sistema
    // --------------------------------------------------------------------

    private fun setupRecyclerView() {
        currentQuestionData ?: return
        answerAdapter = AnswerAdapter(currentQuestionData!!.answers)
        binding.rvAnswers.adapter = answerAdapter
        binding.rvAnswers.layoutManager = LinearLayoutManager(this)
    }

    private fun applyStrikeToUI(strikeCount: Int) {
        when (strikeCount) {
            1 -> binding.ivStrike1.apply {
                setImageResource(R.drawable.ic_strike_off); visibility = View.VISIBLE
            }
            2 -> binding.ivStrike2.apply {
                setImageResource(R.drawable.ic_strike_off); visibility = View.VISIBLE
            }
            3 -> binding.ivStrike3.apply {
                setImageResource(R.drawable.ic_strike_off); visibility = View.VISIBLE
            }
        }
    }

    private fun resetStrikeUI() {
        binding.ivStrike1.visibility = View.INVISIBLE
        binding.ivStrike2.visibility = View.INVISIBLE
        binding.ivStrike3.visibility = View.INVISIBLE
    }

    private fun vibrateOnError() {
        if (vibrator?.hasVibrator() == true) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(
                    VibrationEffect.createOneShot(
                        400,
                        VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(400)
            }
        }
    }

    private fun normalizeText(text: String): String {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
        return normalized.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase()
            .trim()
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

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onStop() {
        super.onStop()
        cleanupAllListeners()
        faceOffDialog?.dismiss()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupAllListeners()
        speechRecognizer?.destroy()
        soundPool?.release()
        soundPool = null
    }
}
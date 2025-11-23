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

    private fun initializeClientGame() {
        toast("Conectado. Esperando pregunta del Host...")
        binding.btnMic.visibility = View.GONE

        clientListenerJob?.cancel()
        clientListenerJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1) Recibir pregunta
                val questionCommand = ConnectionManager.dataIn?.readUTF()

                if (questionCommand != null && questionCommand.startsWith("QUESTION::SEP::")) {
                    val questionJson = questionCommand.substringAfter("QUESTION::SEP::")
                    currentQuestionData = parseQuestionDataFromJson(questionJson)

                    withContext(Dispatchers.Main) {
                        if (currentQuestionData != null) {
                            binding.tvQuestion.text = currentQuestionData!!.question
                            setupRecyclerView()
                            showFaceOffDialog()
                        } else {
                            toast("Error al recibir la pregunta.")
                        }
                    }
                }

                // 2) Esperar resultado del FACE-OFF
                val resultCommand = ConnectionManager.dataIn?.readUTF()

                withContext(Dispatchers.Main) {
                    faceOffDialog?.dismiss()

                    when (resultCommand) {
                        "YOU_WIN" -> {
                            toast("¡Ganaste el turno!")
                            startPlayerTurn()
                        }
                        "YOU_LOSE" -> {
                            toast("¡'$botName' ganó el turno!")
                            startMachineTurn()
                        }
                        else -> {
                            toast("Respuesta desconocida del host.")
                        }
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    toast("¡Error de conexión!")
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
        machineAITask?.cancel()
        thinkingTimer?.cancel()
        answerTimer?.cancel()
        clientListenerJob?.cancel()
        hostBuzzerListenerJob?.cancel()
        hostWaitJob?.cancel()
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

                val isSpeechError =
                    (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)

                if (isSpeechError) {
                    if (isMultiplayer && !isHost) {
                        // CLIENTE → mandar al host
                        toast("No entendí. Enviando...")
                        binding.btnMic.isEnabled = false

                        lifecycleScope.launch(Dispatchers.IO) {
                            ConnectionManager.dataOut?.writeUTF("GUESS:error_no_match")
                            ConnectionManager.dataOut?.flush()
                        }

                        waitForHostVerification()
                    } else {
                        // HOST
                        toast("No entendí. ¡Incorrecto!")
                        vibrateOnError()
                        addStrike()
                    }
                } else {
                    toast("Error de micrófono, intenta de nuevo.")
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

                val spokenText = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.getOrNull(0)

                if (spokenText.isNullOrEmpty()) {
                    if (isMultiplayer && !isHost) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            ConnectionManager.dataOut?.writeUTF("GUESS:error_empty")
                            ConnectionManager.dataOut?.flush()
                        }
                        waitForHostVerification()
                    } else {
                        toast("No entendí. ¡Incorrecto!")
                        vibrateOnError()
                        addStrike()
                    }
                    return
                }

                if (isMultiplayer && !isHost) {
                    toast("¡Respuesta enviada! Esperando verificación del anfitrión...")
                    binding.btnMic.isEnabled = false

                    lifecycleScope.launch(Dispatchers.IO) {
                        Log.d("NET_CLIENT", "Enviando GUESS: $spokenText")
                        ConnectionManager.dataOut?.writeUTF("GUESS:$spokenText")
                        ConnectionManager.dataOut?.flush()
                    }

                    waitForHostVerification()
                } else {
                    // HOST
                    checkAnswer(spokenText)
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
                toast("¡Se acabó el tiempo! Enviando...")
                lifecycleScope.launch(Dispatchers.IO) {
                    ConnectionManager.dataOut?.writeUTF("GUESS:TIMEOUT")
                    ConnectionManager.dataOut?.flush()
                }
                waitForHostVerification()
            } else {
                toast("¡Se acabó el tiempo para hablar!")
                binding.pbTimer.visibility = View.GONE
                soundPool?.play(wrongSoundId, 1f, 1f, 0, 0, 1f)
                vibrateOnError()
                if (isStealAttempt) checkAnswer("TIMEOUT")
                else {
                    addStrike()
                    if (isPlayerTurn && playerStrikes < 3) startThinkingTimer()
                }
            }
        }
    }

    private fun startThinkingTimer() {
        // 1. Limpiar timers anteriores
        thinkingTimer?.cancel()
        answerTimer?.cancel()

        // 2. Dejar el mic listo siempre que empiece un turno de pensar
        restoreMicButton()

        // 3. Preparamos la barra
        val totalTime = 5000L
        val interval = 50L

        binding.pbTimer.visibility = View.VISIBLE
        binding.pbTimer.max = totalTime.toInt()
        binding.pbTimer.progress = totalTime.toInt()

        // 4. Iniciamos el conteo de "pensar"
        thinkingTimer = lifecycleScope.launch {
            var currentTime = totalTime

            while (currentTime > 0) {
                delay(interval)
                currentTime -= interval
                binding.pbTimer.progress = currentTime.toInt()
            }

            // Si se acabó el tiempo y NO está grabando, es fallo
            if (!isRecording) {
                binding.pbTimer.visibility = View.GONE
                binding.btnMic.isEnabled = false

                toast("¡Se acabó el tiempo para pensar!")
                soundPool?.play(wrongSoundId, 1f, 1f, 0, 0, 1f)
                vibrateOnError()

                delay(1500)

                if (isMultiplayer && !isHost) {
                    // CLIENTE: avisa al host y luego espera verificación
                    lifecycleScope.launch(Dispatchers.IO) {
                        ConnectionManager.dataOut?.writeUTF("GUESS:TIMEOUT")
                        ConnectionManager.dataOut?.flush()
                    }
                    waitForHostVerification()
                } else {
                    // HOST / offline
                    if (isStealAttempt) {
                        checkAnswer("TIMEOUT")
                    } else {
                        addStrike()
                        if (isPlayerTurn && playerStrikes < 3) {
                            delay(1000)
                            binding.btnMic.isEnabled = true
                            startThinkingTimer()
                        }
                    }
                }
            }
        }
    }


    private fun showStealResultDialog(success: Boolean) {
        val title: String

        // Determinamos los nombres para ser explícitos en el mensaje
        // isPlayerTurn = true  -> Es el turno del Host (Anfitrión)
        // isPlayerTurn = false -> Es el turno del Oponente (Invitado)

        if (success) {
            // CASO A: El que intentó robar ACERTÓ -> Gana los puntos.
            if (isPlayerTurn) {
                // El Host robó con éxito
                title = "¡ROBO EXITOSO DEL ANFITRIÓN!\n¡$playerName SE LLEVA LOS PUNTOS!"
            } else {
                // El Invitado robó con éxito
                title = "¡ROBO EXITOSO DEL INVITADO!\n¡$botName SE LLEVA LOS PUNTOS!"
            }
        } else {
            // CASO B: El que intentó robar FALLÓ -> Gana el equipo contrario (el que defendía).
            if (isPlayerTurn) {
                // Host falló el robo -> Gana el Invitado
                title = "¡ROBO FALLIDO DEL ANFITRIÓN!\n¡$botName GANA LA RONDA!"
            } else {
                // Invitado falló el robo -> Gana el Host
                title = "¡ROBO FALLIDO DEL INVITADO!\n¡$playerName GANA LA RONDA!"
            }
        }

        showEndRoundDialog(title, currentScore)
    }






    private fun stopRecording() {
        if (isRecording) {
            isRecording = false
            answerTimer?.cancel()
            speechRecognizer?.stopListening()
        }
    }

    private var hostWaitJob: Job? = null
    private fun waitForHostVerification() {
        binding.pbTimer.visibility = View.GONE
        binding.btnMic.isEnabled = false
        binding.btnMic.setImageResource(android.R.drawable.ic_btn_speak_now)
        binding.btnMic.alpha = 0.5f

        // Cancelar cualquier listener previo
        clientListenerJob?.cancel()
        hostWaitJob?.cancel()

        thinkingTimer?.cancel()
        answerTimer?.cancel()
        isRecording = false

        hostWaitJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (isStealAttempt) {
                    // 🔥 ROBO: el host va a mandar REVEAL y luego END_ROUND
                    while (isActive) {
                        val cmd = withTimeoutOrNull(7000L) {
                            ConnectionManager.dataIn?.readUTF()
                        } ?: break

                        withContext(Dispatchers.Main) {
                            Log.d("NET_CLIENT", "Robo: recibido del host -> $cmd")
                            handleNetworkCommand(cmd)
                        }

                        // Cuando llegue END_ROUND, ya cerramos el robo
                        if (cmd.startsWith("END_ROUND")) break
                    }
                } else {
                    // 🔹 Turno normal: solo esperamos un comando (REVEAL o STRIKE)
                    val command = withTimeoutOrNull(7000L) {
                        ConnectionManager.dataIn?.readUTF()
                    }

                    if (command == null) {
                        withContext(Dispatchers.Main) {
                            toast("No hubo respuesta del anfitrión. Intenta de nuevo.")
                            restoreMicButton()
                            startThinkingTimer()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Log.d("NET_CLIENT", "Normal: recibido del host -> $command")
                            handleNetworkCommand(command)
                        }
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toast("Error de conexión esperando al anfitrión.")
                    restoreMicButton()
                    startThinkingTimer()
                }
            }
        }
    }



    private fun restoreMicButton() {
        binding.btnMic.apply {
            visibility = View.VISIBLE
            isEnabled = true
            alpha = 1.0f
            setImageResource(android.R.drawable.ic_btn_speak_now)
        }
        isRecording = false
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
        val strikeTakerIsPlayer = isPlayerTurn

        if (isPlayerTurn) {
            playerStrikes++
            currentStrikes = playerStrikes
        } else {
            machineStrikes++
            currentStrikes = machineStrikes
        }

        if (isMultiplayer && isHost) {
            lifecycleScope.launch(Dispatchers.IO) {
                Log.d("NET_HOST", "Enviando STRIKE:$currentStrikes al cliente")
                ConnectionManager.dataOut?.writeUTF("STRIKE:$currentStrikes")
                ConnectionManager.dataOut?.flush()
            }
        }

        applyStrikeToUI(currentStrikes)

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
        isPlayerTurn = true
        isStealAttempt = true
        showGameAlert("¡3 Strikes de '$botName'!\n¡Tu turno de robar!") {
            binding.btnMic.visibility = View.VISIBLE
            startThinkingTimer()
        }
        lifecycleScope.launch(Dispatchers.IO) {
            ConnectionManager.dataOut?.writeUTF("STEAL:player")
            ConnectionManager.dataOut?.flush()
        }
    }

    private fun startMachineStealAttempt() {
        // El jugador local (host) ya no puede hablar
        binding.btnMic.visibility = View.GONE

        if (isMultiplayer) {
            // Desde aquí ya estamos en modo ROBO
            isPlayerTurn = false
            isStealAttempt = true

            // 🔥 CAMBIO CRITICO: Iniciamos la escucha inmediatamente (sin esperar al click del Dialog)
            // para evitar el bloqueo si el cliente responde rápido.
            startMachineTurn()

            showGameAlert("¡3 STRIKES!\n¡'$botName' intentará robar!") {
                // Al dar OK solo cerramos el diálogo, la lógica de red ya está corriendo.
            }

            // Avisamos al invitado que él tiene el robo
            lifecycleScope.launch(Dispatchers.IO) {
                ConnectionManager.dataOut?.writeUTF("STEAL:opponent")
                ConnectionManager.dataOut?.flush()
            }
        }
    }





    // --------------------------------------------------------------------
    // Red: comandos
    // --------------------------------------------------------------------

    private fun handleNetworkCommand(command: String) {
        Log.d(
            "NET_ANY",
            "handleNetworkCommand: $command  (isHost=$isHost, isPlayerTurn=$isPlayerTurn)"
        )
        val parts = command.split(":")

        when (parts[0]) {

            "GUESS" -> {
                if (isMultiplayer && isHost) {
                    val guess = command.substringAfter("GUESS:")
                    Log.d("NET_HOST", "Recibido GUESS del cliente: $guess")
                    toast("El oponente dice: $guess")
                    checkAnswer(guess)
                }
            }

            "REVEAL" -> {
                val index = parts[1].toInt()
                answerAdapter.revealAnswer(index)
                soundPool?.play(correctSoundId, 1f, 1f, 0, 0, 1f)

                lifecycleScope.launch {
                    delay(1000)

                    // 🔥 CORRECCIÓN 1: Solo reactivamos el micro si es MI turno.
                    // Si soy el visitante y el host reveló una respuesta, yo sigo callado.
                    if (isPlayerTurn && !isStealAttempt && playerStrikes < 3) {
                        restoreMicButton()
                        startThinkingTimer()
                    }
                }
            }

            "STRIKE" -> {
                val strikeNum = parts[1].toInt()
                applyStrikeToUI(strikeNum)

                lifecycleScope.launch {
                    delay(500)

                    toast("¡Incorrecto!")
                    soundPool?.play(wrongSoundId, 1f, 1f, 0, 0, 1f)
                    vibrateOnError()

                    // 🔥 CORRECCIÓN 2: IMPORTANTE
                    // Antes reactivábamos el micro ciegamente.
                    // Ahora verificamos "isPlayerTurn".
                    // Si el Host falló (isPlayerTurn = false para el cliente),
                    // el cliente NO debe activar su micrófono.
                    if (isPlayerTurn && strikeNum < 3 && !isStealAttempt) {
                        restoreMicButton()
                        startThinkingTimer()
                    }
                }
            }

            "STEAL" -> {
                if (parts[1] == "player") {
                    // Host me avisa: Su jugador perdió, el OTRO va a robar.
                    isPlayerTurn = false
                    showGameAlert("¡3 STRIKES!\n¡'$botName' intentará robar!") {
                        clientListenerJob?.cancel()
                    }
                } else if (parts[1] == "opponent") {
                    // Host me avisa: YO (el oponente) voy a robar.
                    isPlayerTurn = true
                    isStealAttempt = true

                    // Limpiamos cualquier estado previo para asegurar que el micro funcione
                    stopRecording()

                    showGameAlert("¡3 Strikes del Oponente!\n¡Tu turno de robar! (1 intento)") {
                        // 🔥 CORRECCIÓN 3: Forzamos la visibilidad del botón
                        // para asegurar que aparezca en el robo.
                        binding.btnMic.visibility = View.VISIBLE
                        restoreMicButton()
                        startThinkingTimer()
                    }
                }
            }

            "END_ROUND" -> {
                val title = parts[1]
                val score = parts[2].toInt()
                cleanupAllListeners()
                showEndRoundDialog(title, score)
            }

            "RESET_GAME" -> {
                // El Host decidió volver a jugar, nos movemos a la Ruleta
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
        // Inflamos TU diseño XML
        val dialogView = layoutInflater.inflate(R.layout.dialog_end_round, null)
        val builder = AlertDialog.Builder(this)
        builder.setView(dialogView)
        builder.setCancelable(false) // No se puede cerrar tocando afuera

        val dialog = builder.create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val tvTitle = dialogView.findViewById<TextView>(R.id.tvEndTitle)
        val tvScore = dialogView.findViewById<TextView>(R.id.tvEndScore)
        val btnPlayAgain = dialogView.findViewById<Button>(R.id.btnPlayAgain)
        val btnGoToMenu = dialogView.findViewById<Button>(R.id.btnGoToMenu)

        tvTitle.text = title
        tvScore.text = "Puntaje Final: $score"

        // Guardar resultado en BD (si aplica)
        val winnerName = if (title.contains(playerName, ignoreCase = true) || title.contains("GANASTE")) playerName else botName
        if (!title.contains("Empate")) {
            saveGameResult(winnerName, score)
        }

        // Si soy HOST, envío el resultado al cliente para que vea la misma ventana
        if (isMultiplayer && isHost) {
            lifecycleScope.launch(Dispatchers.IO) {
                // Solo enviamos si acabamos de generar el diálogo, para evitar bucles
                // (Opcional: puedes poner una bandera boolean dialogShown = true)
                ConnectionManager.dataOut?.writeUTF("END_ROUND:$title:$score")
                ConnectionManager.dataOut?.flush()
            }
        }

        // --- BOTÓN: VOLVER A JUGAR ---
        btnPlayAgain.setOnClickListener {
            dialog.dismiss()

            // Si soy HOST, le ordeno al cliente que también se vaya a la ruleta
            if (isMultiplayer && isHost) {
                lifecycleScope.launch(Dispatchers.IO) {
                    ConnectionManager.dataOut?.writeUTF("RESET_GAME") // Comando nuevo
                    ConnectionManager.dataOut?.flush()
                }
            }

            // Movernos a la Ruleta
            val intent = Intent(this, SpinWheelActivity::class.java)
            startActivity(intent)
            finish()
        }

        // --- BOTÓN: MENÚ ---
        btnGoToMenu.setOnClickListener {
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

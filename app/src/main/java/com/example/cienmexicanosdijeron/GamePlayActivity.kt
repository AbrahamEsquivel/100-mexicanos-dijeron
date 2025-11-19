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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.Normalizer
import kotlin.random.Random

class GamePlayActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGamePlayBinding
    private var machineAITask: Job? = null
    private var faceOffDialog: AlertDialog? = null
    private var isFaceOffWon = false

    private var currentQuestionData: QuestionData? = null
    private lateinit var answerAdapter: AnswerAdapter
    private var speechRecognizer: SpeechRecognizer? = null
    private var isRecording = false
    private var currentStrikes = 0
    private var currentScore = 0

    private var answerTimer: Job? = null
    private var soundPool: SoundPool? = null
    private var wrongSoundId: Int = 0
    private var correctSoundId: Int = 0
    private var vibrator: Vibrator? = null

    private var isPlayerTurn = true
    private var isStealAttempt = false
    private var hasAnyAnswerBeenRevealed = false
    private var playerStrikes = 0
    private var machineStrikes = 0
    private var thinkingTimer: Job? = null

    private var botName: String = "Bot"
    private var playerName: String = "Jugador"

    private var isMultiplayer = false
    private var isHost = false
    private var clientListenerJob: Job? = null
    private var hostBuzzerListenerJob: Job? = null

    private val sillyAnswers = listOf("¿Pato?", "No sé, ¿Uvas?", "Maradona", "El... ¿Sol?", "Tacos")

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) startRecording()
            else toast("Necesitamos el permiso del micrófono para jugar.")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGamePlayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hideSystemUI()
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator?
        setupSounds()

        isMultiplayer = intent.getBooleanExtra("IS_MULTIPLAYER", false)
        isHost = intent.getBooleanExtra("IS_HOST", false)
        val opponentName = intent.getStringExtra("OPPONENT_NAME") ?: "Bot"
        val categoryName = intent.getStringExtra("SELECTED_CATEGORY") ?: "Sin Categoría"

        val sp = getSharedPreferences("GamePrefs", Context.MODE_PRIVATE)
        playerName = sp.getString("PlayerName", "Jugador") ?: "Jugador"
        botName = opponentName

        setupMicButton()

        if (isMultiplayer) {
            if (isHost) initializeHostGame(categoryName)
            else initializeClientGame()
        } else {
            initializeOfflineGame(categoryName)
        }
    }

    private fun initializeOfflineGame(categoryName: String) {
        currentQuestionData = loadQuestionForCategory(categoryName)
        if (currentQuestionData != null) {
            binding.tvQuestion.text = currentQuestionData!!.question
            setupRecyclerView()
            showFaceOffDialog()
        } else {
            binding.tvQuestion.text = "Error: No se encontró pregunta"
        }
    }

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
                    withContext(Dispatchers.Main) { showFaceOffDialog() }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) { toast("Error al enviar pregunta") }
                }
            }
        }
    }

    private fun initializeClientGame() {
        toast("Conectado. Esperando pregunta del Host...")
        binding.btnMic.visibility = View.GONE

        clientListenerJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
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

                    // --- EL CLIENTE ESPERA EL RESULTADO AQUÍ ---
                    val resultCommand = ConnectionManager.dataIn?.readUTF()

                    if (resultCommand == "YOU_WIN") {
                        withContext(Dispatchers.Main) {
                            faceOffDialog?.dismiss()
                            toast("¡Ganaste el turno!")
                            startPlayerTurn()
                        }
                    } else if (resultCommand == "YOU_LOSE") {
                        withContext(Dispatchers.Main) {
                            faceOffDialog?.dismiss()
                            toast("¡'$botName' ganó el turno!")
                            startMachineTurn()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) { toast("¡Error de conexión!") }
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
                    // HOST/OFFLINE: Gana directo
                    handleBuzzerResult(didPlayerWin = true)
                }
            }
        }

        // --- Lógica del Oponente ---
        if (isMultiplayer) {
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
                    } catch (e: Exception) {}
                }
            }
        } else {
            // OFFLINE: IA
            btnMachine.isClickable = false
            btnMachine.text = "MÁQUINA"
            machineAITask?.cancel()
            machineAITask = lifecycleScope.launch {
                delay(Random.nextLong(1000, 4000))
                if (!isFaceOffWon) handleBuzzerResult(didPlayerWin = false)
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
                if(isMultiplayer) {
                    launch(Dispatchers.IO) {
                        ConnectionManager.dataOut?.writeUTF("YOU_LOSE")
                        ConnectionManager.dataOut?.flush()
                    }
                }
                delay(1000)
                faceOffDialog?.dismiss()
                startPlayerTurn()
            } else {
                btnMachine?.isPressed = true
                btnMachine?.text = "¡GANÓ!\nPRIMERO"
                toast("¡La máquina ganó el turno!")
                delay(1000)
                faceOffDialog?.dismiss()
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

    private fun startPlayerTurn() {
        isPlayerTurn = true
        isStealAttempt = false
        playerStrikes = 0
        resetStrikeUI()

        // Aseguramos que se vea
        binding.btnMic.visibility = View.VISIBLE
        binding.btnMic.isEnabled = true

        toast("¡Tu turno! Piensa tu respuesta...")
        startThinkingTimer()
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
        machineStrikes = 0
        resetStrikeUI()
        faceOffDialog?.dismiss()

        if (currentQuestionData != null) {
            binding.tvQuestion.text = currentQuestionData!!.question
            binding.rvAnswers.adapter = answerAdapter
            binding.tvQuestion.visibility = View.VISIBLE
            binding.rvAnswers.visibility = View.VISIBLE
        }

        binding.btnMic.visibility = View.GONE
        thinkingTimer?.cancel()
        answerTimer?.cancel()
        binding.pbTimer.visibility = View.GONE
        isRecording = false

        if (isMultiplayer) {
            toast("Turno de '$botName'. Esperando...")
            clientListenerJob?.cancel()
            clientListenerJob = lifecycleScope.launch(Dispatchers.IO) {
                try {
                    while(true) {
                        val command = ConnectionManager.dataIn?.readUTF() ?: break
                        withContext(Dispatchers.Main) { handleNetworkCommand(command) }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { toast("¡Error de conexión!") }
                }
            }
        } else {
            toast("Turno de la máquina...")
            machineMakesAGuess()
        }
    }

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
                    if (isHost || !isMultiplayer) {
                        // Host/Offline
                        toast("No entendí. ¡Incorrecto!")
                        vibrateOnError()
                        addStrike()
                    } else {
                        // Cliente
                        toast("No entendí. Enviando...")
                        binding.btnMic.isEnabled = false
                        lifecycleScope.launch(Dispatchers.IO) {
                            ConnectionManager.dataOut?.writeUTF("GUESS:error_no_match")
                            ConnectionManager.dataOut?.flush()
                        }
                        // ¡¡CORRECCIÓN AQUÍ!! (Antes era startMachineTurn)
                        waitForHostVerification()
                    }
                } else {
                    toast("Error de micrófono, intenta de nuevo.")
                    // Si es error técnico, reiniciamos
                    if (isPlayerTurn && playerStrikes < 3 && !isStealAttempt) {
                        startThinkingTimer()
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                isRecording = false
                answerTimer?.cancel()
                binding.pbTimer.visibility = View.GONE

                val spokenText = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.get(0)

                if (!spokenText.isNullOrEmpty()) {
                    if (isHost || !isMultiplayer) {
                        checkAnswer(spokenText)
                    } else {
                        // Cliente: Envía y espera VERIFICACIÓN
                        toast("¡Respuesta enviada! Esperando...")
                        binding.btnMic.isEnabled = false
                        lifecycleScope.launch(Dispatchers.IO) {
                            ConnectionManager.dataOut?.writeUTF("GUESS:$spokenText")
                            ConnectionManager.dataOut?.flush()
                        }
                        // ¡¡CORRECCIÓN AQUÍ!! (Antes era startMachineTurn)
                        waitForHostVerification()
                    }
                } else {
                    // Respuesta vacía
                    if (isHost || !isMultiplayer) {
                        toast("No entendí. ¡Incorrecto!")
                        vibrateOnError()
                        addStrike()
                    } else {
                        lifecycleScope.launch(Dispatchers.IO) {
                            ConnectionManager.dataOut?.writeUTF("GUESS:error_empty")
                            ConnectionManager.dataOut?.flush()
                        }
                        // ¡¡CORRECCIÓN AQUÍ!!
                        waitForHostVerification()
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

    private fun waitForHostVerification() {
        // 1. Ocultamos controles visuales
        binding.btnMic.visibility = View.GONE
        binding.pbTimer.visibility = View.GONE

        // 2. Cancelamos timers locales
        thinkingTimer?.cancel()
        answerTimer?.cancel()
        isRecording = false

        // 3. Nos ponemos a escuchar (igual que en startMachineTurn)
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
                    toast("Error de conexión esperando verificación.")
                }
            }
        }

    }

    // --- NUEVA: Lógica del Micrófono 1 (Pedir permiso) ---
    private fun checkAudioPermission() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> startRecording()
            else -> requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // --- NUEVA: Lógica del Micrófono 2 (Empezar) ---
    private fun startRecording() {
        if (!isRecording) {
            thinkingTimer?.cancel()
            isRecording = true
            toast("¡Escuchando...")
            binding.pbTimer.visibility = View.VISIBLE
            speechRecognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX")
            })
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
        // 1. Limpieza
        thinkingTimer?.cancel()
        answerTimer?.cancel()

        // ¡¡ESTA ES LA LÍNEA QUE TE FALTA!!
        // ¡¡TIENES QUE VOLVER A MOSTRARLO!!
        binding.btnMic.visibility = View.VISIBLE

        binding.btnMic.isEnabled = true
        isRecording = false
        binding.btnMic.setImageResource(android.R.drawable.ic_btn_speak_now)

        // 3. Preparamos la barra
        binding.pbTimer.visibility = View.VISIBLE
        binding.pbTimer.progress = 5000

        // 4. Iniciamos el conteo
        thinkingTimer = lifecycleScope.launch {
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
            if (!isRecording) {
                binding.pbTimer.visibility = View.GONE
                binding.btnMic.isEnabled = false

                toast("¡Se acabó el tiempo para pensar!")
                soundPool?.play(wrongSoundId, 1f, 1f, 0, 0, 1f)
                vibrateOnError()

                delay(1500)

                // Lógica de Timeout
                if (isMultiplayer && !isHost) {
                    // Cliente avisa al Host
                    lifecycleScope.launch(Dispatchers.IO) {
                        ConnectionManager.dataOut?.writeUTF("GUESS:TIMEOUT")
                        ConnectionManager.dataOut?.flush()
                    }
                    waitForHostVerification()
                } else {
                    // Host/Offline
                    if (isStealAttempt) {
                        checkAnswer("TIMEOUT")
                    } else {
                        addStrike()
                        if (isPlayerTurn && playerStrikes < 3) {
                            // Pequeña pausa y reiniciamos
                            delay(1000)
                            binding.btnMic.isEnabled = true
                            startThinkingTimer() // Esto volverá a llamar a VISIBLE
                        }
                    }
                }
            }
        }
    }

    private fun stopRecording() {
        if (isRecording) {
            isRecording = false
            answerTimer?.cancel()
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

    private fun checkAnswer(spokenText: String) {
        thinkingTimer?.cancel()
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

                if (isPlayerTurn) {
                    currentScore += pointsGained
                    toast("¡Correcto! +$pointsGained puntos.")
                } else {
                    toast("¡'$botName' acierta! +$pointsGained puntos.")
                }

                if (isMultiplayer && isHost) {
                    launch(Dispatchers.IO) {
                        ConnectionManager.dataOut?.writeUTF("RESULT:CORRECT:$answerIndex")
                        ConnectionManager.dataOut?.flush()
                    }
                }
                delay(1500)

                var allAreNowRevealed = true
                currentQuestionData?.answers?.forEach { if (!it.isRevealed) allAreNowRevealed = false }

                if (allAreNowRevealed) {
                    showEndRoundDialog(if (isPlayerTurn) "¡GANASTE!" else "¡'$botName' GANA!", currentScore)
                } else if (isStealAttempt) {
                    if (isPlayerTurn) showEndRoundDialog("¡ROBO EXITOSO! ¡GANASTE!", currentScore)
                    else showEndRoundDialog("¡TE ROBARON! PERDISTE", currentScore)
                } else if (!isPlayerTurn) {
                    if (isMultiplayer) startMachineTurn() else machineMakesAGuess()
                } else if (isPlayerTurn && !isStealAttempt) {
                    binding.btnMic.isEnabled = true
                    startThinkingTimer()
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

        if (isMultiplayer && isHost) {
            withContext(Dispatchers.IO) {
                ConnectionManager.dataOut?.writeUTF("RESULT:WRONG")
                ConnectionManager.dataOut?.flush()
            }
        }
        delay(1500)

        if (isStealAttempt) {
            binding.btnMic.visibility = View.GONE
            if (!hasAnyAnswerBeenRevealed) {
                showEmpateDialog()
            } else {
                val winnerMsg = if (isPlayerTurn) "¡ROBO FALLIDO! PERDISTE" else "¡ROBO FALLIDO! ¡GANASTE!"
                showEndRoundDialog(winnerMsg, currentScore)
            }
        } else {
            addStrike()
            if (!isPlayerTurn) {
                if (isMultiplayer && machineStrikes < 3) startMachineTurn()
                else if (!isMultiplayer && machineStrikes < 3) machineMakesAGuess()
            } else if (isPlayerTurn && playerStrikes < 3 && !isStealAttempt) {
                binding.btnMic.isEnabled = true
                startThinkingTimer()
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
        if (isMultiplayer) {
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
        } else {
            isPlayerTurn = true
            isStealAttempt = true
            showGameAlert("¡3 Strikes de la Máquina!\n¡Tu turno de robar! (1 intento)") {
                binding.btnMic.visibility = View.VISIBLE
                startThinkingTimer()
            }
        }
    }

    private fun startMachineStealAttempt() {
        binding.btnMic.visibility = View.GONE
        if (isMultiplayer) {
            isPlayerTurn = false

            showGameAlert("¡3 STRIKES!\n¡'$botName' intentará robar!") {
                startMachineTurn()
                // ¡¡FIX!! Forzamos la bandera después de que startMachineTurn la pudiera haber borrado
                isStealAttempt = true
            }

            lifecycleScope.launch(Dispatchers.IO) {
                ConnectionManager.dataOut?.writeUTF("STEAL:opponent")
                ConnectionManager.dataOut?.flush()
            }
        } else {
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
                checkAnswer(guess)
            }
            "REVEAL" -> {
                val index = parts[1].toInt()
                answerAdapter.revealAnswer(index)
                soundPool?.play(correctSoundId, 1f, 1f, 0, 0, 1f)

                // Si es mi turno (y no es robo), prendo el micro de nuevo
                if (isPlayerTurn && !isStealAttempt) {
                    startThinkingTimer() // <-- Esto hará visible el botón
                }
            }
            "WRONG" -> {
                toast("¡Incorrecto!")
                soundPool?.play(wrongSoundId, 1f, 1f, 0, 0, 1f)
                vibrateOnError()

                // Si es mi turno (y no es robo), prendo el micro de nuevo
                if (isPlayerTurn && !isStealAttempt) {
                    startThinkingTimer() // <-- Esto hará visible el botón
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
                        // Aquí también aseguramos que se vea
                        binding.btnMic.visibility = View.VISIBLE
                        startThinkingTimer()
                    }
                }
            }
            "END_ROUND" -> {
                val title = parts[1]
                val score = parts[2].toInt()
                clientListenerJob?.cancel()
                showEndRoundDialog(title, score)
            }
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
        return normalized.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "").lowercase().trim()
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
        clientListenerJob?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        soundPool?.release()
        soundPool = null
    }

    private fun resetStrikeUI() {
        binding.ivStrike1.visibility = View.INVISIBLE
        binding.ivStrike2.visibility = View.INVISIBLE
        binding.ivStrike3.visibility = View.INVISIBLE
    }
}
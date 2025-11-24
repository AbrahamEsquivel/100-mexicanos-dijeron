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

class GamePlayOfflineActivity : AppCompatActivity() {

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


        hideSystemUI() // Oculta barras de sistema

        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator?
        setupSounds()


        val categoryName = intent.getStringExtra("SELECTED_CATEGORY") ?: "Sin Categoría"

        botName = intent.getStringExtra("BOT_NAME") ?: "Bot"
        val sp = getSharedPreferences("GamePrefs", Context.MODE_PRIVATE)
        playerName = sp.getString("PlayerName", "Jugador") ?: "Jugador"
        // 2. Cargamos la pregunta COMPLETA
        currentQuestionData = loadQuestionForCategory(categoryName)

        if (currentQuestionData != null) {
            // Mostramos la pregunta
            binding.tvQuestion.text = currentQuestionData!!.question

            // 3. Configuramos el RecyclerView (la lista de respuestas)
            setupRecyclerView()

            // 4. ¡Lanzamos el pop-up del buzzer!
            showFaceOffDialog()
        } else {
            binding.tvQuestion.text = "Error: No se encontró pregunta para $categoryName"
        }

        // 5. Configurar el botón de micrófono
        setupMicButton()
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

        btnPlayerBuzzer.setOnClickListener {
            if (!isFaceOffWon) {
                isFaceOffWon = true
                machineAITask?.cancel()
                btnPlayerBuzzer.isPressed = true
                btnPlayerBuzzer.text = "¡GANASTE!\nPRIMERO"

                lifecycleScope.launch {
                    delay(1000)
                    faceOffDialog?.dismiss()
                    startPlayerTurn()
                }
            }
        }

        btnMachineBuzzer.isClickable = false
        machineAITask = lifecycleScope.launch {
            delay(Random.nextLong(500, 1500))
            if (!isFaceOffWon) {
                isFaceOffWon = true
                btnMachineBuzzer.isPressed = true
                btnMachineBuzzer.text = "¡GANÓ!\nPRIMERO"

                lifecycleScope.launch {
                    delay(1000)
                    faceOffDialog?.dismiss()
                    startMachineTurn()
                }
            }
        }
        faceOffDialog?.show()
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
        toast("¡Tu turno! Piensa tu respuesta...")
        binding.btnMic.visibility = View.VISIBLE

        // ¡¡CAMBIO AQUÍ!!
        startThinkingTimer() // Inicia el contador de 5 seg para pensar
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

        // 1. Calculamos quién ganó (esto ya lo tenías)
        val winnerName = when {
            title.contains("GANASTE") || title.contains("ROBO EXITOSO") -> playerName
            title.contains("MÁQUINA GANA") || title.contains("MÁQUINA ROBA") -> botName
            else -> "Empate" // Para "ROBO FALLIDO", "EMPATE", etc.
        }

        // ¡¡¡AQUÍ ESTÁ LA LÍNEA QUE FALTABA!!!
        // 2. Llamamos a la función para guardar en la BD
        saveGameResult(winnerName, score)
        // ¡¡¡FIN DE LA CORRECCIÓN!!!

        // Botón "Volver a Jugar" (te manda a la Ruleta)
        btnPlayAgain.setOnClickListener {
            dialog.dismiss()
            val intent = Intent(this, SpinWheelActivity::class.java)
            startActivity(intent)
            finish() // Cierra el juego actual
        }

        // Botón "Menú Principal" (te manda al Menú)
        btnGoToMenu.setOnClickListener {
            dialog.dismiss()
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP // Limpia las pantallas anteriores
            startActivity(intent)
            finish() // Cierra el juego actual
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
        binding.btnMic.visibility = View.GONE

        // La máquina empieza a adivinar
        machineMakesAGuess()
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

                    // ¡¡CAMBIO AQUÍ!! Inicia la pausa
                    binding.btnMic.isEnabled = false
                    lifecycleScope.launch {
                        toast("No entendí. ¡Incorrecto!")
                        soundPool?.play(wrongSoundId, 1f, 1f, 0, 0, 1f)
                        vibrateOnError()
                        delay(2000) // Pausa

                        addStrike()

                        if (isPlayerTurn && playerStrikes < 3) {
                            binding.btnMic.isEnabled = true
                            startThinkingTimer()
                        }
                    }
                } else {
                    toast("Error de micrófono, intenta de nuevo.")
                }
            }

            override fun onResults(results: Bundle?) {
                isRecording = false
                answerTimer?.cancel()
                binding.pbTimer.visibility = View.GONE

                val spokenText = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.get(0)
                if (!spokenText.isNullOrEmpty()) {
                    // Esta función ya tiene el delay y el reinicio del timer
                    lifecycleScope.launch {
                        checkAnswer(spokenText)
                    }
                } else {
                    // ¡¡CAMBIO AQUÍ!! Inicia la pausa
                    binding.btnMic.isEnabled = false
                    lifecycleScope.launch {
                        toast("No entendí. ¡Incorrecto!")
                        soundPool?.play(wrongSoundId, 1f, 1f, 0, 0, 1f)
                        vibrateOnError()
                        delay(2000) // Pausa

                        addStrike()

                        if (isPlayerTurn && playerStrikes < 3) {
                            binding.btnMic.isEnabled = true
                            startThinkingTimer()
                        }
                    }
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        // El OnClickListener se modifica para que no haga nada si está pausado
        binding.btnMic.setOnClickListener {
            // ¡¡CAMBIO AQUÍ!!
            if (!binding.btnMic.isEnabled) return@setOnClickListener // Si está desactivado, no hagas nada

            if (isRecording) {
                stopRecording() // El usuario lo para manualmente
            } else {
                checkAudioPermission() // Esto llama a startRecording()
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
        // 1. Pausamos todo INMEDIATAMENTE
        thinkingTimer?.cancel()
        binding.btnMic.isEnabled = false // Desactivamos el mic

        // Usamos una corutina para manejar la pausa del sonido
        lifecycleScope.launch {
            val normalizedSpokenText = normalizeText(spokenText)
            var foundMatch = false
            var allAreNowRevealed = true
            var pointsGained = 0
            var answerIndex = -1

            // 2. Buscamos el match (igual que antes)
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
                answerAdapter.revealAnswer(answerIndex)
                soundPool?.play(correctSoundId, 1f, 1f, 0, 0, 1f) // Sonido

                if (isPlayerTurn) {
                    currentScore += pointsGained
                    toast("¡Correcto! +$pointsGained puntos. (Total: $currentScore)")
                } else {
                    toast("¡Máquina acierta! +$pointsGained puntos.")
                }

                // 4. Pausa para el sonido
                delay(2000) // 1.5 segundos de pausa para el sonido

                // 5. Revisamos si el juego terminó
                currentQuestionData?.answers?.forEach { if (!it.isRevealed) allAreNowRevealed = false }

                if (allAreNowRevealed) {
                    binding.btnMic.isEnabled = true // Reactivamos por si acaso
                    showEndRoundDialog(if (isPlayerTurn) "¡GANASTE!" else "¡LA MÁQUINA GANA!", currentScore)
                } else if (isStealAttempt) {
                    showEndRoundDialog(if (isPlayerTurn) "¡ROBO EXITOSO!" else "¡LA MÁQUINA ROBA!", currentScore)
                } else if (!isPlayerTurn) {
                    machineMakesAGuess() // La máquina sigue (no reactivamos el mic)
                } else if (isPlayerTurn) {
                    binding.btnMic.isEnabled = true // Reactivamos
                    startThinkingTimer() // Reinicia el timer de pensar
                }

            }
            // 6. Lógica de FALLO
            else {
                toast("¡Incorrecto!")
                soundPool?.play(wrongSoundId, 1f, 1f, 0, 0, 1f) // Sonido
                vibrateOnError()

                // 7. Pausa para el sonido
                delay(2000) // 1.5 segundos de pausa

                if (isStealAttempt) {
                    binding.btnMic.visibility = View.GONE
                    if (!hasAnyAnswerBeenRevealed) {
                        showEmpateDialog()
                    } else {
                        showEndRoundDialog(if (isPlayerTurn) "¡ROBO FALLIDO!" else "¡ROBO FALLIDO! ¡GANASTE!", currentScore)
                    }
                } else {
                    addStrike() // Esto puede llamar a startMachineTurn()

                    if (!isPlayerTurn && machineStrikes < 3) {
                        machineMakesAGuess() // La máquina sigue (no reactivamos el mic)
                    } else if (isPlayerTurn && playerStrikes < 3) {
                        binding.btnMic.isEnabled = true // Reactivamos
                        startThinkingTimer() // Reinicia el timer de pensar
                    }
                }
            }
        }
    }

    private fun addStrike() {
        if (isPlayerTurn) {
            playerStrikes++
            currentStrikes = playerStrikes // Sincronizamos con el contador general

            // Actualizar UI
            when (playerStrikes) {
                1 -> binding.ivStrike1.apply { setImageResource(R.drawable.ic_strike_off); visibility = View.VISIBLE }
                2 -> binding.ivStrike2.apply { setImageResource(R.drawable.ic_strike_off); visibility = View.VISIBLE }
                3 -> {
                    binding.ivStrike3.apply { setImageResource(R.drawable.ic_strike_off); visibility = View.VISIBLE }
                    binding.btnMic.visibility = View.GONE
                    // El jugador falló, la máquina intenta robar
                    startMachineStealAttempt()
                }
            }
        } else {
            // Es el turno de la máquina
            machineStrikes++
            currentStrikes = machineStrikes // Sincronizamos

            // Actualizar UI
            when (machineStrikes) {
                1 -> binding.ivStrike1.apply { setImageResource(R.drawable.ic_strike_off); visibility = View.VISIBLE }
                2 -> binding.ivStrike2.apply { setImageResource(R.drawable.ic_strike_off); visibility = View.VISIBLE }
                3 -> {
                    binding.ivStrike3.apply { setImageResource(R.drawable.ic_strike_off); visibility = View.VISIBLE }
                    // La máquina falló, el jugador intenta robar
                    startPlayerStealAttempt()
                }
            }
        }
    }

    private fun startPlayerStealAttempt() {
        isPlayerTurn = true
        isStealAttempt = true // ¡Es un robo!

        showGameAlert("¡3 Strikes de la Máquina!\n¡Tu turno de robar! (1 intento)") {
            // Esto se ejecuta cuando el usuario presiona "OK"
            binding.btnMic.visibility = View.VISIBLE
            // El timer de 5 seg se activará cuando presione el mic
        }
    }

    private fun startMachineStealAttempt() {
        isPlayerTurn = false
        isStealAttempt = true // ¡Es un robo!

        showGameAlert("¡3 STRIKES!\nLa máquina intentará robar.") {
            // Esto se ejecuta cuando el usuario presiona "OK"
            machineMakesAGuess() // La máquina usa la misma lógica para adivinar
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
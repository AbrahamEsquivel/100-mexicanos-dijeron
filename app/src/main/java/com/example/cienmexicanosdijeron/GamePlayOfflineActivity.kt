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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.Normalizer
import kotlin.random.Random
import android.view.View

class GamePlayOfflineActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGamePlayBinding

    private var machineAITask: Job? = null
    private var faceOffDialog: AlertDialog? = null
    private var isFaceOffWon = false

    private var currentQuestionData: QuestionData? = null
    private lateinit var answerAdapter: AnswerAdapter

    private var speechRecognizer: SpeechRecognizer? = null
    private var isRecording = false

    private var thinkingTimer: Job? = null
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
    private var currentStrikes = 0
    private var currentScore = 0

    private var botName: String = "Máquina"
    private var playerName: String = "Jugador"

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

        val categoryName = intent.getStringExtra("SELECTED_CATEGORY") ?: "Sin Categoría"

        val sp = getSharedPreferences("GamePrefs", Context.MODE_PRIVATE)
        playerName = sp.getString("PlayerName", "Jugador") ?: "Jugador"
        botName = "Máquina"

        setupMicButton()
        initializeOfflineGame(categoryName)
    }

    // ---------- OFFLINE ONLY ----------

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

    private fun showFaceOffDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_face_off, null)
        val builder = AlertDialog.Builder(this)
        builder.setView(dialogView)
        builder.setCancelable(false)

        faceOffDialog = builder.create()
        isFaceOffWon = false

        faceOffDialog?.window?.apply {
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            setBackgroundDrawableResource(android.R.color.transparent)
        }

        val btnPlayer = dialogView.findViewById<Button>(R.id.btnPlayerBuzzer)
        val btnMachine = dialogView.findViewById<Button>(R.id.btnMachineBuzzer)

        // 👉 OFFLINE: el jugador siempre puede presionar
        btnPlayer.setOnClickListener {
            if (isFaceOffWon) return@setOnClickListener

            isFaceOffWon = true
            machineAITask?.cancel()

            btnPlayer.isPressed = true
            btnPlayer.text = "¡GANASTE!\nPRIMERO"

            lifecycleScope.launch {
                toast("¡Ganaste el turno!")
                delay(1000)
                faceOffDialog?.dismiss()
                startPlayerTurn()   // 👈 AQUÍ ARRANCA TU TURNO
            }
        }

        // 👉 Botón de la máquina: no se puede presionar, solo es visual
        btnMachine.isClickable = false
        btnMachine.text = "MÁQUINA"

        // La máquina “reacciona” sola después de un tiempo random
        machineAITask?.cancel()
        machineAITask = lifecycleScope.launch {
            delay(Random.nextLong(1000, 4000))
            if (isFaceOffWon) return@launch

            isFaceOffWon = true
            btnMachine.isPressed = true
            btnMachine.text = "¡GANÓ!\nPRIMERO"

            toast("¡La máquina ganó el turno!")
            delay(1000)
            faceOffDialog?.dismiss()
            startMachineTurn()   // 👈 Turno de la máquina
        }

        faceOffDialog?.show()
    }


    private fun handleBuzzerResult(didPlayerWin: Boolean) {
        if (isFaceOffWon) return
        isFaceOffWon = true
        machineAITask?.cancel()

        lifecycleScope.launch {
            if (didPlayerWin) {
                toast("¡Ganaste el turno!")
                delay(1000)
                faceOffDialog?.dismiss()
                startPlayerTurn()
            } else {
                toast("¡La máquina ganó el turno!")
                delay(1000)
                faceOffDialog?.dismiss()
                startMachineTurn()
            }
        }
    }


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
        cleanupAllListeners()

        isPlayerTurn = false
        isStealAttempt = false
        machineStrikes = 0
        currentStrikes = 0
        resetStrikeUI()

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
        }

        toast("Turno de la máquina...")
        machineMakesAGuess()
    }

    private fun machineMakesAGuess() {
        lifecycleScope.launch {
            delay(5000)
            val question = currentQuestionData ?: return@launch

            val unrevealedAnswers = question.answers.filter { !it.isRevealed }
            if (unrevealedAnswers.isEmpty()) {
                showEndRoundDialog("¡RONDA LIMPIA!", currentScore)
                return@launch
            }

            val chanceToGuessCorrectly = 50
            if (Random.nextInt(100) < chanceToGuessCorrectly) {
                val machineAnswer = unrevealedAnswers.maxByOrNull { it.points }!!
                checkAnswer(machineAnswer.text)
            } else {
                checkAnswer(sillyAnswers.random())
            }
        }
    }

    // ---------- MIC / TIMERS (offline) ----------

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

                toast("No se entendió. ¡Incorrecto!")
                vibrateOnError()

                lifecycleScope.launch {
                    handleWrongAnswer()
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
                    toast("No se escuchó nada. ¡Incorrecto!")
                    vibrateOnError()
                    lifecycleScope.launch {
                        handleWrongAnswer()
                    }
                    return
                }

                checkAnswer(spokenText)
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
            toast("¡Se acabó el tiempo para hablar!")
            binding.pbTimer.visibility = View.GONE
            soundPool?.play(wrongSoundId, 1f, 1f, 0, 0, 1f)
            vibrateOnError()

            if (isStealAttempt) {
                checkAnswer("TIMEOUT")
            } else {
                addStrike()
                if (isPlayerTurn && playerStrikes < 3) startThinkingTimer()
            }
        }
    }

    private fun startThinkingTimer() {
        thinkingTimer?.cancel()
        answerTimer?.cancel()

        binding.btnMic.apply {
            visibility = View.VISIBLE
            isEnabled = true
            alpha = 1f
            setImageResource(android.R.drawable.ic_btn_speak_now)
        }

        binding.pbTimer.visibility = View.VISIBLE
        val totalTime = 5000L
        binding.pbTimer.max = totalTime.toInt()
        binding.pbTimer.progress = totalTime.toInt()

        thinkingTimer = lifecycleScope.launch {
            val interval = 50L
            var currentTime = totalTime

            while (currentTime > 0) {
                delay(interval)
                currentTime -= interval
                binding.pbTimer.progress = currentTime.toInt()
            }

            if (!isRecording) {
                binding.pbTimer.visibility = View.GONE
                binding.btnMic.isEnabled = false

                toast("¡Se acabó el tiempo para pensar!")
                soundPool?.play(wrongSoundId, 1f, 1f, 0, 0, 1f)
                vibrateOnError()

                delay(1500)

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

    private fun stopRecording() {
        if (isRecording) {
            isRecording = false
            answerTimer?.cancel()
            speechRecognizer?.stopListening()
        }
    }

    // ---------- LÓGICA DE RESPUESTAS (offline) ----------

    private fun checkAnswer(spokenText: String) {
        thinkingTimer?.cancel()
        answerTimer?.cancel()
        binding.btnMic.isEnabled = false

        lifecycleScope.launch {
            if (spokenText == "TIMEOUT") {
                lifecycleScope.launch {
                    handleWrongAnswer()
                }
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
                    toast("¡Correcto! +$pointsGained puntos. (Total: $currentScore)")
                } else {
                    toast("¡$botName acierta! +$pointsGained puntos.")
                }

                delay(1500)

                val allAreNowRevealed =
                    currentQuestionData?.answers?.all { it.isRevealed } == true

                if (allAreNowRevealed) {
                    showEndRoundDialog(
                        if (isPlayerTurn) "¡GANASTE!" else "¡$botName GANA!",
                        currentScore
                    )
                } else if (isStealAttempt) {
                    showStealResultDialog(success = true)
                } else if (!isPlayerTurn) {
                    machineMakesAGuess()
                } else if (isPlayerTurn && !isStealAttempt) {
                    binding.btnMic.isEnabled = true
                    startThinkingTimer()
                }
            } else {
                lifecycleScope.launch {
                    handleWrongAnswer()
                }
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
            if (!hasAnyAnswerBeenRevealed) {
                showEmpateDialog()
            } else {
                showStealResultDialog(success = false)
            }
        } else {
            addStrike()
            if (!isPlayerTurn) {
                if (machineStrikes < 3) machineMakesAGuess()
            } else if (isPlayerTurn && playerStrikes < 3 && !isStealAttempt) {
                binding.btnMic.isEnabled = true
                startThinkingTimer()
            }
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
        showGameAlert("¡3 Strikes de la Máquina!\n¡Tu turno de robar! (1 intento)") {
            binding.btnMic.visibility = View.VISIBLE
            startThinkingTimer()
        }
    }

    private fun startMachineStealAttempt() {
        binding.btnMic.visibility = View.GONE
        isPlayerTurn = false
        isStealAttempt = true
        showGameAlert("¡3 STRIKES!\nLa máquina intentará robar.") {
            machineMakesAGuess()
        }
    }

    private fun showStealResultDialog(success: Boolean) {
        val title = if (success) {
            if (isPlayerTurn) {
                "¡ROBO EXITOSO! ¡TE LLEVASTE LA RONDA!"
            } else {
                "¡LA MÁQUINA TE ROBÓ LA RONDA!"
            }
        } else {
            if (isPlayerTurn) {
                "¡ROBO FALLIDO! PERDISTE EL ROBO"
            } else {
                "¡ROBO FALLIDO DE LA MÁQUINA!\n¡TE QUEDAS CON LOS PUNTOS!"
            }
        }
        showEndRoundDialog(title, currentScore)
    }

    // ---------- DIÁLOGOS GENERALES / UTILS ----------

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

        // Aquí puedes guardar en BD si quieres, similar a tu versión original

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

    private fun normalizeText(text: String): String {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
        return normalized.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "").lowercase()
            .trim()
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

    private fun cleanupAllListeners() {
        machineAITask?.cancel()
        thinkingTimer?.cancel()
        answerTimer?.cancel()
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

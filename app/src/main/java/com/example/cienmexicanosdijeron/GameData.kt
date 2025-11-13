package com.example.cienmexicanosdijeron

// Molde para una respuesta individual
data class Answer(
    val text: String,
    val points: Int,
    var isRevealed: Boolean = false // Para saber si ya la adivinaron
)

// Molde para la pregunta completa
data class QuestionData(
    val question: String,
    val answers: List<Answer>
)
package com.example.cienmexicanosdijeron

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AnswerAdapter(private val answers: List<Answer>) :
    RecyclerView.Adapter<AnswerAdapter.AnswerViewHolder>() {

    class AnswerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val answerText: TextView = itemView.findViewById(R.id.tvAnswerText)
        val answerPoints: TextView = itemView.findViewById(R.id.tvAnswerPoints)
        val cover: View = itemView.findViewById(R.id.vCover)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AnswerViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_answer, parent, false)
        return AnswerViewHolder(view)
    }

    override fun getItemCount() = answers.size

    override fun onBindViewHolder(holder: AnswerViewHolder, position: Int) {
        val answer = answers[position]

        // Formateamos el texto "1. Pera"
        holder.answerText.text = "${position + 1}. ${answer.text}"
        holder.answerPoints.text = answer.points.toString()

        // Si ya fue revelada, quitamos la cubierta. Si no, la mostramos.
        if (answer.isRevealed) {
            holder.cover.visibility = View.GONE
        } else {
            holder.cover.visibility = View.VISIBLE
        }
    }

    // Esta función la llamaremos para revelar una respuesta
    fun revealAnswer(index: Int) {
        answers[index].isRevealed = true
        notifyItemChanged(index)
    }
}
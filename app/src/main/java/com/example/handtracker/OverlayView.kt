package com.example.handtracker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlin.math.max

/**
 * Desenha os 21 pontos da mão (landmarks) e as conexões sobre o preview da câmera.
 * As PONTAS dos dedos levantados ficam verdes; as dobradas ficam acinzentadas.
 */
class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var results: HandLandmarkerResult? = null
    private var raisedPerHand: List<List<Boolean>> = emptyList()

    private var scaleFactor: Float = 1f
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1

    // Mapeia índice da ponta -> ordem no vetor de booleans (polegar,indicador,...)
    private val tipToOrder = mapOf(4 to 0, 8 to 1, 12 to 2, 16 to 3, 20 to 4)

    private val connectionPaint = Paint().apply {
        color = Color.parseColor("#00E676") // verde (esqueleto)
        strokeWidth = 6f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val jointPaint = Paint().apply {
        color = Color.parseColor("#FFD54F") // amarelo (juntas)
        strokeWidth = 9f
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val tipUpPaint = Paint().apply {
        color = Color.parseColor("#69F0AE") // verde claro (levantado)
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val tipDownPaint = Paint().apply {
        color = Color.parseColor("#B0BEC5") // cinza (dobrado)
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    fun clear() {
        results = null
        raisedPerHand = emptyList()
        invalidate()
    }

    fun setResults(result: HandLandmarkerResult, imageWidth: Int, imageHeight: Int) {
        this.results = result
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        // PreviewView em FILL_START: usamos o maior fator de escala.
        scaleFactor = max(width * 1f / imageWidth, height * 1f / imageHeight)
        invalidate()
    }

    /** Define quais pontas de dedo estão levantadas, por mão (para colorir). */
    fun setRaised(raised: List<List<Boolean>>) {
        this.raisedPerHand = raised
        invalidate()
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        val result = results ?: return

        for ((handIndex, hand) in result.landmarks().withIndex()) {
            val raised = raisedPerHand.getOrNull(handIndex)

            // Conexões (esqueleto)
            for (conn in HandLandmarker.HAND_CONNECTIONS) {
                val start = hand[conn.start()]
                val end = hand[conn.end()]
                canvas.drawLine(
                    start.x() * imageWidth * scaleFactor,
                    start.y() * imageHeight * scaleFactor,
                    end.x() * imageWidth * scaleFactor,
                    end.y() * imageHeight * scaleFactor,
                    connectionPaint
                )
            }

            // Pontos
            for ((i, lm) in hand.withIndex()) {
                val x = lm.x() * imageWidth * scaleFactor
                val y = lm.y() * imageHeight * scaleFactor
                val order = tipToOrder[i]
                if (order != null) {
                    val isUp = raised != null && order < raised.size && raised[order]
                    canvas.drawCircle(x, y, 13f, if (isUp) tipUpPaint else tipDownPaint)
                } else {
                    canvas.drawCircle(x, y, 7f, jointPaint)
                }
            }
        }
    }
}

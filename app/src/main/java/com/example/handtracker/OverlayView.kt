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
 * Baseado no exemplo oficial do MediaPipe.
 */
class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var results: HandLandmarkerResult? = null
    private var scaleFactor: Float = 1f
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1

    private val linePaint = Paint().apply {
        color = Color.parseColor("#00E676") // verde
        strokeWidth = 8f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val pointPaint = Paint().apply {
        color = Color.YELLOW
        strokeWidth = 12f
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    fun clear() {
        results = null
        invalidate()
    }

    /**
     * Recebe o resultado da detecção e as dimensões (rotacionadas) da imagem.
     */
    fun setResults(result: HandLandmarkerResult, imageWidth: Int, imageHeight: Int) {
        this.results = result
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        // O PreviewView usa FILL_START, então usamos o maior fator de escala.
        scaleFactor = max(width * 1f / imageWidth, height * 1f / imageHeight)
        invalidate()
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        val result = results ?: return

        for (hand in result.landmarks()) {
            // Ponta de cada landmark
            for (lm in hand) {
                canvas.drawPoint(
                    lm.x() * imageWidth * scaleFactor,
                    lm.y() * imageHeight * scaleFactor,
                    pointPaint
                )
            }
            // Conexões (esqueleto da mão)
            for (conn in HandLandmarker.HAND_CONNECTIONS) {
                val start = hand[conn.start()]
                val end = hand[conn.end()]
                canvas.drawLine(
                    start.x() * imageWidth * scaleFactor,
                    start.y() * imageHeight * scaleFactor,
                    end.x() * imageWidth * scaleFactor,
                    end.y() * imageHeight * scaleFactor,
                    linePaint
                )
            }
        }
    }
}

package com.example.handtracker

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.handtracker.databinding.ActivityMainBinding
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * App que usa a câmera traseira + MediaPipe Hand Landmarker para contar
 * quantos dedos estão levantados (até 2 mãos).
 *
 * Melhorias nesta versão:
 *  - Contagem estável (votação dos últimos frames) sem "piscar".
 *  - Detecção de dedos mais confiável (polegar incluso).
 *  - Vibração quando o número muda.
 *  - UI com badge circular, indicadores por dedo e latência da detecção.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var handLandmarker: HandLandmarker? = null
    private var modelReady = false
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val modelFile by lazy { File(filesDir, "hand_landmarker.task") }
    private val vibrator by lazy { getSystemService(Vibrator::class.java) }

    @Volatile private var imageWidth = 1
    @Volatile private var imageHeight = 1

    // --- Anti-tremor: guarda os últimos frames e vota qual dedo está levantado ---
    private data class FrameSummary(val hands: List<List<Boolean>>)
    private val history = ArrayDeque<FrameSummary>()
    private var displayedCount = -1

    // Os 5 indicadores de dedo na ordem: polegar, indicador, médio, anelar, mínimo
    private val dots by lazy {
        listOf(binding.dotThumb, binding.dotIndex, binding.dotMiddle, binding.dotRing, binding.dotPinky)
    }

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else Toast.makeText(this, "Permissão de câmera é necessária.", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.statusText.text = "Baixando modelo de detecção..."
        lifecycleScope.launch {
            try {
                downloadModelIfNeeded()
                withContext(Dispatchers.IO) { setupLandmarker() }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao preparar o modelo", e)
                binding.statusText.text = "Erro ao baixar modelo: ${e.message}"
            }

            val hasCamera = ContextCompat.checkSelfPermission(
                this@MainActivity, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED

            if (hasCamera) startCamera()
            else requestPermission.launch(Manifest.permission.CAMERA)
        }
    }

    /** Baixa o modelo hand_landmarker.task uma única vez (guarda em filesDir). */
    private suspend fun downloadModelIfNeeded() = withContext(Dispatchers.IO) {
        if (modelFile.exists() && modelFile.length() > 0) return@withContext
        val url =
            "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"
        URL(url).openStream().use { input ->
            FileOutputStream(modelFile).use { output -> input.copyTo(output) }
        }
    }

    private fun setupLandmarker() {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(modelFile.absolutePath)
                .build()

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(2)
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setResultListener { result, _ -> runOnUiThread { onResult(result) } }
                .setErrorListener { error ->
                    runOnUiThread {
                        binding.statusText.text = "Erro na detecção: ${error.message}"
                    }
                }
                .build()

            handLandmarker = HandLandmarker.createFromOptions(this, options)
            modelReady = true
            runOnUiThread { binding.statusText.text = "Mostre sua mão para a câmera" }
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao iniciar o modelo", e)
            runOnUiThread { binding.statusText.text = "Falha ao iniciar o modelo." }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setTargetRotation(binding.viewFinder.display?.rotation ?: 0)
                .build()
                .also { it.setAnalyzer(cameraExecutor, ::analyzeFrame) }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalyzer
                )
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao abrir a câmera", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Converte o frame da câmera em Bitmap, rotaciona e envia para o MediaPipe. */
    private fun analyzeFrame(imageProxy: ImageProxy) {
        if (!modelReady || handLandmarker == null) {
            imageProxy.close()
            return
        }
        try {
            val bitmapBuffer = Bitmap.createBitmap(
                imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888
            )
            bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer)

            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
            }
            val rotatedBitmap = Bitmap.createBitmap(
                bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height, matrix, true
            )

            imageWidth = rotatedBitmap.width
            imageHeight = rotatedBitmap.height

            val mpImage = BitmapImageBuilder(rotatedBitmap).build()
            handLandmarker?.detectAsync(mpImage, SystemClock.uptimeMillis())
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao processar frame", e)
        } finally {
            imageProxy.close()
        }
    }

    /** Recebe o resultado (já na UI thread) e atualiza a tela. */
    private fun onResult(result: HandLandmarkerResult) {
        binding.overlay.setResults(result, imageWidth, imageHeight)

        // Estado bruto (com ruído) de cada dedo de cada mão.
        val rawHands = result.landmarks().map { raisedBooleans(it) }

        // Suaviza votando nos últimos frames.
        pushFrame(rawHands)
        val voted = votedHands()
        binding.overlay.setRaised(voted)

        val total = voted.sumOf { hand -> hand.count { it } }
        binding.countText.text = total.toString()

        // Indicadores do primeiro dedo da mão principal.
        updateDots(voted.firstOrNull())

        val hands = result.landmarks().size
        val latency = SystemClock.uptimeMillis() - result.timestampMs()
        binding.statusText.text = if (hands == 0) {
            "Mostre sua mão para a câmera"
        } else {
            "$hands mão(s) detectada(s) • ${latency}ms"
        }

        // Vibra quando o total muda (exceto na 1ª leitura).
        if (total != displayedCount) {
            if (displayedCount != -1) vibrate()
            displayedCount = total
        }
    }

    // ───────────────────────── Detecção de dedos ─────────────────────────

    /**
     * Retorna 5 booleanos: [polegar, indicador, médio, anelar, mínimo].
     *
     * 4 dedos: levantados quando a ponta está ACIMA da junta do meio (PIP)
     *          E mais distante do pulso que a junta (evita contar dedos dobrados).
     * Polegar: levantado quando a ponta está mais AFASTADA da base do indicador
     *          (MCP) que a junta IP — funciona independente da mão ser esq/dir.
     */
    private fun raisedBooleans(hand: List<NormalizedLandmark>): List<Boolean> {
        if (hand.size < 21) return List(5) { false }
        val wrist = hand[0]

        // (ponta, pip, mcp) dos 4 dedos
        val fingers = listOf(
            Triple(8, 6, 5),    // indicador
            Triple(12, 10, 9),  // médio
            Triple(16, 14, 13), // anelar
            Triple(20, 18, 17)  // mínimo
        )
        val four = fingers.map { (tip, pip, _) ->
            hand[tip].y() < hand[pip].y() &&
                dist(hand[tip], wrist) > dist(hand[pip], wrist)
        }

        val thumbUp = dist(hand[4], hand[5]) > dist(hand[3], hand[5]) * 1.15f
        return listOf(thumbUp) + four
    }

    private fun dist(a: NormalizedLandmark, b: NormalizedLandmark): Float {
        val dx = a.x() - b.x()
        val dy = a.y() - b.y()
        return sqrt(dx * dx + dy * dy)
    }

    // ───────────────────────── Anti-tremor (smoothing) ─────────────────────────

    private fun pushFrame(hands: List<List<Boolean>>) {
        history.addLast(FrameSummary(hands))
        while (history.size > HISTORY_SIZE) history.removeFirst()
    }

    /** Para cada "slot" de mão, vota cada dedo pela maioria dos últimos frames. */
    private fun votedHands(): List<List<Boolean>> {
        if (history.isEmpty()) return emptyList()
        val maxHands = history.maxOf { it.hands.size }
        val result = ArrayList<List<Boolean>>()
        for (h in 0 until maxHands) {
            val counts = IntArray(5)
            var present = 0
            for (frame in history) {
                if (h < frame.hands.size) {
                    present++
                    frame.hands[h].forEachIndexed { i, up -> if (up) counts[i]++ }
                }
            }
            if (present == 0) continue
            result.add((0 until 5).map { counts[it] * 2 >= present })
        }
        return result
    }

    // ───────────────────────── UI / feedback ─────────────────────────

    private fun updateDots(raised: List<Boolean>?) {
        for (i in dots.indices) {
            val on = raised != null && i < raised.size && raised[i]
            dots[i].setBackgroundResource(
                if (on) R.drawable.finger_dot_on else R.drawable.finger_dot_off
            )
        }
    }

    private fun vibrate() {
        vibrator?.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        handLandmarker?.close()
    }

    companion object {
        private const val TAG = "HandTracker"
        private const val HISTORY_SIZE = 6
    }
}

package com.example.handtracker

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.SystemClock
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * App que usa a câmera traseira + MediaPipe Hand Landmarker para contar
 * quantos dedos estão levantados (até 2 mãos).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var handLandmarker: HandLandmarker? = null
    private var modelReady = false
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val modelFile by lazy { File(filesDir, "hand_landmarker.task") }

    @Volatile private var imageWidth = 1
    @Volatile private var imageHeight = 1

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
                .setResultListener { result, _ -> runOnUiThread { handleResult(result) } }
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
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_4)
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

    /** Recebe o resultado (já na UI thread) e atualiza a contagem na tela. */
    private fun handleResult(result: HandLandmarkerResult) {
        binding.overlay.setResults(result, imageWidth, imageHeight)

        val hands = result.landmarks()
        if (hands.isEmpty()) {
            binding.countText.text = "0"
            binding.statusText.text = "Mostre sua mão para a câmera"
        } else {
            val count = countRaisedFingers(result)
            binding.countText.text = count.toString()
            binding.statusText.text =
                "${hands.size} mão(s) • $count dedo(s) levantado(s)"
        }
    }

    /**
     * Conta os dedos levantados.
     * Para os 4 dedos: a ponta (tip) precisa estar ACIMA da junta do meio (PIP).
     * Para o polegar: a ponta precisa estar mais "afastada" da base do mínimo
     * do que a junta IP.
     *
     * Índices MediaPipe: 4=polegar, 8=indicador, 12=médio, 16=anelar, 20=mínimo.
     */
    private fun countRaisedFingers(result: HandLandmarkerResult): Int {
        var total = 0
        for (hand in result.landmarks()) {
            if (hand.size < 21) continue

            // (ponta, juntaPIP) dos 4 dedos — y menor = mais alto na imagem
            val fingers = listOf(
                8 to 6,   // indicador
                12 to 10, // médio
                16 to 14, // anelar
                20 to 18  // mínimo
            )
            for ((tip, pip) in fingers) {
                if (hand[tip].y() < hand[pip].y()) total++
            }

            // Polegar: ponta (4) mais distante da base do mínimo (17) que a junta (3)
            if (abs(hand[4].x() - hand[17].x()) > abs(hand[3].x() - hand[17].x())) {
                total++
            }
        }
        return total
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        handLandmarker?.close()
    }

    companion object {
        private const val TAG = "HandTracker"
    }
}

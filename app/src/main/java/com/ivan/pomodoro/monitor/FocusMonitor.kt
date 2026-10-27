package com.ivan.pomodoro.monitor

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 前置摄像头 + ML Kit 人脸检测的专注监控。
 * 所有推理均在本机完成，不上传任何图像。
 */
class FocusMonitor(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val lens: Lens = Lens.FRONT,
    private val task: DistractionJudge.Task = DistractionJudge.Task.SCREEN,
    private val onVerdict: (DistractionJudge.Verdict, Boolean) -> Unit
) {

    /** 监控视角：前置正对用户，或后置由上方俯拍 */
    enum class Lens { FRONT, BACK }

    private val judge = DistractionJudge(
        mode = if (lens == Lens.BACK) DistractionJudge.Mode.OVERHEAD else DistractionJudge.Mode.FRONT,
        task = task
    )
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            // 俯拍时人脸在画面中偏小，放宽最小人脸比例
            .setMinFaceSize(if (lens == Lens.BACK) 0.08f else 0.15f)
            .build()
    )

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var executor: ExecutorService? = null
    private var lastAnalyzeAt = 0L
    private var started = false
    private var closed = false

    fun start() {
        if (started || closed) return
        started = true
        executor = Executors.newSingleThreadExecutor()
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (closed) return@addListener
            runCatching {
                val provider = future.get()
                cameraProvider = provider

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor!!) { proxy -> analyze(proxy) }
                imageAnalysis = analysis

                val selector = when {
                    lens == Lens.BACK &&
                        provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ->
                        CameraSelector.DEFAULT_BACK_CAMERA

                    provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ->
                        CameraSelector.DEFAULT_FRONT_CAMERA

                    else -> CameraSelector.DEFAULT_BACK_CAMERA
                }
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                Log.i(
                    TAG,
                    "摄像头监控已启动，视角=${if (lens == Lens.BACK) "后置俯拍" else "前置正对"}，" +
                        "任务=${if (task == DistractionJudge.Task.PAPER) "纸面" else "盯屏"}"
                )
                onVerdict(DistractionJudge.Verdict(false, ""), true)
            }.onFailure {
                Log.e(TAG, "摄像头启动失败", it)
                onVerdict(DistractionJudge.Verdict(false, "摄像头不可用"), false)
            }
        }, mainExecutor)
    }

    fun stop() {
        started = false
        closed = true
        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        runCatching { cameraProvider?.unbindAll() }
        cameraProvider = null
        executor?.shutdown()
        executor = null
        runCatching { detector.close() }
        judge.reset()
    }

    private fun analyze(proxy: ImageProxy) {
        if (closed) {
            proxy.close()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastAnalyzeAt < 300L) {
            proxy.close()
            return
        }
        lastAnalyzeAt = now

        val mediaImage = proxy.image
        if (mediaImage == null) {
            proxy.close()
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val frameWidth = proxy.width
        val frameHeight = proxy.height

        detector.process(image)
            .addOnSuccessListener(mainExecutor) { faces ->
                if (!closed) {
                    val verdict = judge.evaluate(
                        faces, SystemClock.elapsedRealtime(), frameWidth, frameHeight
                    )
                    onVerdict(verdict, true)
                }
            }
            .addOnFailureListener(mainExecutor) { e ->
                Log.w(TAG, "人脸检测失败", e)
            }
            .addOnCompleteListener { proxy.close() }
    }

    companion object {
        private const val TAG = "FocusMonitor"
    }
}

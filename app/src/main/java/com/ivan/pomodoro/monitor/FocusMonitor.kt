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

    /** 纸面俯拍时额外跑人像分割，用"画面里还有没有手/头发"判断人是否还在 */
    private val presenceDetector: PresenceDetector? =
        if (lens == Lens.BACK && task == DistractionJudge.Task.PAPER) PresenceDetector() else null

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var executor: ExecutorService? = null
    private var lastAnalyzeAt = 0L
    private var lastPresence = -1f
    private var lastLuma: ByteArray? = null
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
        presenceDetector?.close()
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
        val rotation = proxy.imageInfo.rotationDegrees
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val frameWidth = proxy.width
        val frameHeight = proxy.height
        val segmenter = presenceDetector
        // 画面活动必须在本帧关闭前取样
        val motion = if (segmenter == null) -1f else motionOf(proxy)

        detector.process(InputImage.fromMediaImage(mediaImage, rotation))
            .addOnCompleteListener(mainExecutor) { faceTask ->
                if (!faceTask.isSuccessful) {
                    Log.w(TAG, "人脸检测失败", faceTask.exception)
                }
                val faces = faceTask.result ?: emptyList()
                if (closed || segmenter == null) {
                    dispatch(faces, frameWidth, frameHeight, -1f, -1f)
                    proxy.close()
                    return@addOnCompleteListener
                }
                // 同一帧再跑一次人像分割：face proxy 尚未关闭，底图仍然有效
                segmenter.process(InputImage.fromMediaImage(mediaImage, rotation))
                    .addOnSuccessListener(mainExecutor) { mask ->
                        lastPresence = PresenceDetector.foregroundRatio(mask)
                    }
                    .addOnFailureListener(mainExecutor) { e ->
                        lastPresence = -1f
                        Log.w(TAG, "人像分割失败", e)
                    }
                    .addOnCompleteListener(mainExecutor) {
                        dispatch(faces, frameWidth, frameHeight, lastPresence, motion)
                        proxy.close()
                    }
            }
    }

    /**
     * 帧间亮度差：把画面降采样成小网格，与上一帧比较平均绝对差。
     * 用来区分"手在动（在写字）"和"桌面完全静止（人走了）"。
     */
    private fun motionOf(proxy: ImageProxy): Float {
        val plane = proxy.planes.firstOrNull() ?: return -1f
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val w = proxy.width
        val h = proxy.height
        if (w <= 0 || h <= 0 || buffer.capacity() <= 0) return -1f

        val grid = ByteArray(GRID_W * GRID_H)
        for (gy in 0 until GRID_H) {
            val y = (h - 1).toLong() * gy / (GRID_H - 1)
            val rowStart = y * rowStride
            for (gx in 0 until GRID_W) {
                val x = (w - 1).toLong() * gx / (GRID_W - 1)
                val index = rowStart + x * pixelStride
                grid[gy * GRID_W + gx] =
                    if (index < buffer.capacity()) buffer.get(index.toInt()) else 0
            }
        }
        val previous = lastLuma
        lastLuma = grid
        if (previous == null || previous.size != grid.size) return 0f
        var sum = 0
        for (i in grid.indices) {
            sum += kotlin.math.abs((grid[i].toInt() and 0xFF) - (previous[i].toInt() and 0xFF))
        }
        return sum.toFloat() / (grid.size * 255f)
    }

    private fun dispatch(
        faces: List<com.google.mlkit.vision.face.Face>,
        width: Int,
        height: Int,
        presence: Float,
        motion: Float
    ) {
        if (closed) return
        val verdict = judge.evaluate(
            faces, SystemClock.elapsedRealtime(), width, height, presence, motion
        )
        if (presence >= 0f) {
            Log.d(
                TAG,
                "前景占比=%.3f 画面活动=%.4f 判分心=%s".format(
                    presence, motion.coerceAtLeast(0f), verdict.distracted
                )
            )
        }
        onVerdict(verdict, true)
    }

    companion object {
        private const val TAG = "FocusMonitor"

        /** 活动检测的降采样网格 */
        private const val GRID_W = 32
        private const val GRID_H = 24
    }
}

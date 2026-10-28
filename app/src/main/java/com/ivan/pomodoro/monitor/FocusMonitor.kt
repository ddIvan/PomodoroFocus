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
import java.util.concurrent.CancellationException
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

    /**
     * 纸面 + 后置俯拍：画面里通常看不到脸，只能看到手、笔和头发，
     * 这时唯一可靠的信号是"画面里还有没有东西在动"。
     */
    private val trackMotion = lens == Lens.BACK && task == DistractionJudge.Task.PAPER

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var executor: ExecutorService? = null
    private var lastAnalyzeAt = 0L
    private var lastMotionStats = floatArrayOf(-1f, -1f, -1f)
    private var lastLuma: ByteArray? = null
    private var started = false

    /** 诊断日志节流：3 帧/秒的检测结果没必要每帧都打 */
    private var lastLogAt = 0L

    /** 分析线程与主线程都会读写，必须 volatile，否则退出时可能有帧漏进已关闭的回调 */
    @Volatile
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
        // 清掉上一帧缓存，避免重新开始专注时首帧被当成剧烈运动
        lastLuma = null
        lastMotionStats = floatArrayOf(-1f, -1f, -1f)
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
        // 画面活动必须在本帧关闭前取样；退出瞬间画面被回收也只会得到"无数据"
        lastMotionStats = try {
            if (trackMotion) motionStats(proxy) else floatArrayOf(-1f, -1f, -1f)
        } catch (e: Throwable) {
            Log.w(TAG, "帧差计算失败", e)
            floatArrayOf(-1f, -1f, -1f)
        }
        val motionActive = lastMotionStats[1]

        // 退出专注时 detector 已被关闭，此时再提交会抛异常，这一帧直接丢弃
        val task = try {
            detector.process(InputImage.fromMediaImage(mediaImage, rotation))
        } catch (e: Throwable) {
            Log.w(TAG, "人脸检测提交失败", e)
            null
        }
        if (task == null) {
            proxy.close()
            return
        }

        task.addOnCompleteListener(mainExecutor) { faceTask ->
            // 任务被取消（退出监控）时 getResult() 会抛 CancellationException，
            // 必须先判 isSuccessful，再兜一层异常，避免闪退
            val faces = try {
                if (faceTask.isSuccessful) {
                    faceTask.result ?: emptyList()
                } else {
                    if (faceTask.exception !is CancellationException) {
                        Log.w(TAG, "人脸检测失败", faceTask.exception)
                    }
                    emptyList()
                }
            } catch (e: CancellationException) {
                emptyList()
            } catch (e: Throwable) {
                Log.w(TAG, "读取人脸结果失败", e)
                emptyList()
            }
            if (!closed) {
                dispatch(faces, frameWidth, frameHeight, motionActive)
            }
            proxy.close()
        }
    }

    /**
     * 帧间亮度差：把画面降采样成小网格，与上一帧比较。
     * 返回 [平均绝对差, 显著变化格占比(帧差≥20), 最大格差/255]。
     *
     * 平均值会被全图噪声稀释，显著变化格占比才是"局部有东西在动"的证据：
     * 空桌面只有 1~2 个灰度级的随机噪声，几乎没有格能超过 20；
     * 手在写字时总有一小片区域灰度剧烈变化。
     */
    private fun motionStats(proxy: ImageProxy): FloatArray {
        val plane = proxy.planes.firstOrNull() ?: return floatArrayOf(-1f, -1f, -1f)
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val w = proxy.width
        val h = proxy.height
        if (w <= 0 || h <= 0 || buffer.capacity() <= 0) return floatArrayOf(-1f, -1f, -1f)

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
        if (previous == null || previous.size != grid.size) return floatArrayOf(0f, 0f, 0f)
        var sum = 0
        var active = 0
        var maxDiff = 0
        for (i in grid.indices) {
            val diff = kotlin.math.abs(
                (grid[i].toInt() and 0xFF) - (previous[i].toInt() and 0xFF)
            )
            sum += diff
            if (diff >= ACTIVE_DIFF) active++
            if (diff > maxDiff) maxDiff = diff
        }
        return floatArrayOf(
            sum.toFloat() / (grid.size * 255f),
            active.toFloat() / grid.size,
            maxDiff / 255f
        )
    }

    private fun dispatch(
        faces: List<com.google.mlkit.vision.face.Face>,
        width: Int,
        height: Int,
        motionActive: Float
    ) {
        if (closed) return
        val verdict = judge.evaluate(
            faces, SystemClock.elapsedRealtime(), width, height, motionActive
        )
        logFrame(faces, width, height, motionActive, verdict)
        onVerdict(verdict, true)
    }

    /**
     * 每秒打一行诊断日志：人脸姿态角、睁眼概率、画面活动量与判定结果。
     * 纸面前置的低头/抬头判据完全依赖 pitch 的正负方向，这行日志是校准阈值的唯一依据。
     */
    private fun logFrame(
        faces: List<com.google.mlkit.vision.face.Face>,
        width: Int,
        height: Int,
        motionActive: Float,
        verdict: DistractionJudge.Verdict
    ) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLogAt < LOG_INTERVAL_MS) return
        lastLogAt = now

        val face = faces.maxByOrNull {
            it.boundingBox.width().toLong() * it.boundingBox.height().toLong()
        }
        val faceInfo = if (face == null) {
            "无"
        } else {
            "%.0f%% pitch=%.1f yaw=%.1f roll=%.1f 眼L=%.2f 眼R=%.2f 中心=(%.2f,%.2f)".format(
                face.boundingBox.width() * face.boundingBox.height() * 100f /
                    (width.toFloat() * height.toFloat()),
                face.headEulerAngleX,
                face.headEulerAngleY,
                face.headEulerAngleZ,
                face.leftEyeOpenProbability ?: -1f,
                face.rightEyeOpenProbability ?: -1f,
                (face.boundingBox.left + face.boundingBox.right) / 2f / width,
                (face.boundingBox.top + face.boundingBox.bottom) / 2f / height
            )
        }
        val motionInfo = if (motionActive >= 0f) {
            "活动=%.4f 变化格=%.2f%% 峰值=%.3f".format(
                lastMotionStats[0].coerceAtLeast(0f),
                motionActive * 100f,
                lastMotionStats[2].coerceAtLeast(0f)
            )
        } else {
            "活动=-"
        }
        Log.d(
            TAG,
            "人脸=%d(%s) %s 判分心=%s 原因=%s 提示=%s".format(
                faces.size, faceInfo, motionInfo, verdict.distracted, verdict.reason, verdict.note
            )
        )
    }

    companion object {
        private const val TAG = "FocusMonitor"

        /** 活动检测的降采样网格 */
        private const val GRID_W = 32
        private const val GRID_H = 24

        /** 判定为"这一格真的在变"的最小灰度差 */
        private const val ACTIVE_DIFF = 20

        /** 诊断日志最短间隔 */
        private const val LOG_INTERVAL_MS = 1000L
    }
}

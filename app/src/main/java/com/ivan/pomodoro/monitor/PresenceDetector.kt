package com.ivan.pomodoro.monitor

import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions

/**
 * 人像（前景）分割。
 *
 * 用途只有一处：纸面任务 + 后置俯拍时，画面里通常看不到正脸，只能看到
 * 头顶、头发和写字的手。低头时人脸消失是常态，不能据此判分心；但"人根本
 * 不在画面里"（空桌子）同样检测不到脸。二者只能靠前景占比区分：
 *
 * - 有手/头发/头顶 → 前景占比明显大于 0 → 视为人在写作业；
 * - 空桌面 → 前景占比接近 0 → 视为离座。
 *
 * 全部在本机推理，不上传任何图像。
 */
class PresenceDetector {

    private val segmenter = Segmentation.getClient(
        SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .build()
    )

    fun process(image: InputImage): Task<SegmentationMask> = segmenter.process(image)

    fun close() {
        runCatching { segmenter.close() }
    }

    companion object {
        /**
         * 平均前景置信度（0~1）。用置信度均值而非硬阈值计数，
         * 边缘毛发、半透明遮挡不会让结果跳变。
         */
        fun foregroundRatio(mask: SegmentationMask): Float {
            val buffer = mask.buffer
            buffer.rewind()
            val size = buffer.remaining()
            if (size <= 0) return 0f
            var sum = 0L
            while (buffer.hasRemaining()) {
                sum += (buffer.get().toInt() and 0xFF)
            }
            return sum.toFloat() / (size * 255f)
        }
    }
}

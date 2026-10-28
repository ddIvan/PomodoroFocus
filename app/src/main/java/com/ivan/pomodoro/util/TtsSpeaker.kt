package com.ivan.pomodoro.util

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * 语音播报的统一入口。
 *
 * 首页的「检测语音」和专注页的提醒共用同一套状态，避免两边各写一份引擎初始化。
 * 引擎绑定是异步的，绑定完成前的播报先暂存一条、就绪后补播——否则华为这类
 * 首次加载语音包较慢的机型，开头几秒的提醒会直接丢掉。
 */
class TtsSpeaker(context: Context) {

    companion object {
        private const val TAG = "FocusVoice"
        private const val UTTERANCE_ID = "pomodoro_tts"
    }

    enum class State { IDLE, INITIALIZING, READY, UNAVAILABLE }

    private val appContext = context.applicationContext
    private var engine: TextToSpeech? = null

    @Volatile
    var state: State = State.IDLE
        private set

    /** 引擎在但缺中文语音包时为 false，此时播报通常没有声音 */
    @Volatile
    var languageOk = false
        private set

    /** 引擎不可用或缺中文语音包时回调，供界面提示去系统设置里启用 */
    var onUnavailable: (() -> Unit)? = null

    /** 最多缓存一条待播文本，提醒类内容排队积压没有意义 */
    private var pendingText: String? = null

    val isReady: Boolean get() = state == State.READY

    fun prepare() {
        if (state != State.IDLE) return
        state = State.INITIALIZING
        engine = TextToSpeech(appContext) { status -> handleInit(status) }
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        when (state) {
            State.IDLE -> {
                prepare()
                pendingText = text
            }

            State.INITIALIZING -> pendingText = text

            State.READY -> doSpeak(text)

            State.UNAVAILABLE -> Log.w(TAG, "TTS 不可用，忽略播报：$text")
        }
    }

    fun shutdown() {
        pendingText = null
        runCatching {
            engine?.stop()
            engine?.shutdown()
        }
        engine = null
        state = State.IDLE
        languageOk = false
    }

    private fun handleInit(status: Int) {
        val tts = engine
        if (status != TextToSpeech.SUCCESS || tts == null) {
            Log.w(TAG, "TTS 初始化失败：status=$status")
            state = State.UNAVAILABLE
            pendingText = null
            onUnavailable?.invoke()
            return
        }

        val lang = runCatching { tts.setLanguage(Locale.SIMPLIFIED_CHINESE) }
            .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        languageOk = lang != TextToSpeech.LANG_MISSING_DATA &&
            lang != TextToSpeech.LANG_NOT_SUPPORTED
        Log.i(TAG, "TTS 语言设置：result=$lang languageOk=$languageOk")
        if (!languageOk) {
            runCatching { tts.setLanguage(Locale.getDefault()) }
        }

        runCatching {
            // 用媒体通道：提示音通道在华为/鸿蒙上会被静音模式一并掐掉
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.i(TAG, "语音开始播放")
                }

                override fun onDone(utteranceId: String?) {
                    Log.i(TAG, "语音播放完成")
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    Log.w(TAG, "语音播放失败：$utteranceId")
                }
            })
        }

        state = State.READY
        if (!languageOk) {
            // 缺中文语音包时引擎仍算"就绪"，但播出来没声音，主动提示一次
            onUnavailable?.invoke()
        }
        pendingText?.let {
            pendingText = null
            doSpeak(it)
        }
    }

    private fun doSpeak(text: String) {
        val code = runCatching {
            engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        }.getOrNull()
        Log.i(TAG, "播报「$text」speak=$code")
        if (code == TextToSpeech.ERROR) {
            state = State.UNAVAILABLE
            onUnavailable?.invoke()
        }
    }
}

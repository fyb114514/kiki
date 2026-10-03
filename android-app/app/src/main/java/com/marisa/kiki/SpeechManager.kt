package com.marisa.kiki

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 语音壳(Android 原生):TTS 念回答 + 语音识别听你说话。
 *
 * 为什么手机上比 PC 强
 * --------------------
 * Windows 那台机器只有英文语音、语音识别引擎 0 个,而且沙箱里 SAPI 拿不到权限。
 * Android 系统自带中文 TTS 与识别引擎(多数机型离线可用),所以
 * **手机反而是最接近 2011 年 kiki 的那个平台**。
 *
 * 设计:能力探测 + 优雅降级。装不了中文语音就如实提示,不假装能说。
 */
class SpeechManager(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var hasChinese = false
    private var recognizer: SpeechRecognizer? = null

    var onResult: ((String) -> Unit)? = null
    var onPartial: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    fun initTts(onReady: (Boolean, Boolean) -> Unit) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts
                val zh = Locale.SIMPLIFIED_CHINESE
                val r = engine?.setLanguage(zh)
                hasChinese = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                if (!hasChinese) {
                    // 退回默认语言,至少还能念数字/英文
                    engine?.setLanguage(Locale.getDefault())
                }
                engine?.setSpeechRate(1.05f)
                ttsReady = true
                onReady(true, hasChinese)
            } else {
                ttsReady = false
                onReady(false, false)
            }
        }
    }

    /** 是否能念中文 */
    val chineseAvailable: Boolean get() = hasChinese && ttsReady

    /** 念一句话(异步,不阻塞界面) */
    fun speak(text: String) {
        if (!ttsReady) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "marisa")
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    val canRecognize: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    /** 开麦听一句 */
    fun listen() {
        if (!canRecognize) {
            onError?.invoke("本机没有语音识别引擎,无法开麦。你仍然可以用键盘输入。")
            return
        }
        if (recognizer == null) {
            recognizer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onPartialResults(partialResults: Bundle?) {
                    val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!list.isNullOrEmpty()) onPartial?.invoke(list[0])
                }

                override fun onResults(results: Bundle?) {
                    val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!list.isNullOrEmpty()) onResult?.invoke(list[0])
                    else onError?.invoke("没听清,再说一次?")
                }

                override fun onError(error: Int) {
                    onError?.invoke(recognizeErrorText(error))
                }
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer?.startListening(intent)
    }

    fun stopListening() {
        recognizer?.stopListening()
    }

    fun release() {
        try {
            tts?.stop(); tts?.shutdown()
        } catch (_: Exception) {
        }
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        tts = null
        recognizer = null
    }

    private fun recognizeErrorText(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "没听清,再说一次?"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听到声音"
        SpeechRecognizer.ERROR_AUDIO -> "录音出错"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有麦克风权限"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "识别服务连不上(可能需要网络或改用语离线识别)"
        else -> "识别失败(代码 $error)"
    }
}

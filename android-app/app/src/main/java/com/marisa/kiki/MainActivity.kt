package com.marisa.kiki

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 聊天界面:一个输入框 + 一个对话框 + 语音按钮。
 *
 * 交互与原版一致:打开时她先说一句开场白;你说什么她答什么;
 * 用 `关键词`回答 的形式可以教她说话(与 2011 年那套"调教"玩法相同)。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var bot: MarisaBot
    private lateinit var speech: SpeechManager
    private lateinit var chatView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var micButton: Button
    private lateinit var voiceToggle: Button

    private var voiceOn = true
    private var listening = false
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        chatView = findViewById(R.id.chat)
        scroll = findViewById(R.id.scroll)
        input = findViewById(R.id.input)
        micButton = findViewById(R.id.mic)
        voiceToggle = findViewById(R.id.voiceToggle)
        voiceToggle.text = if (voiceOn) "🔊 语音开" else "🔇 语音关"

        bot = MarisaBot(assets)
        speech = SpeechManager(this)
        speech.onResult = { text -> runOnUiThread { onHeard(text) } }
        speech.onPartial = { text -> runOnUiThread { input.setText(text) } }
        speech.onError = { msg -> runOnUiThread {
            listening = false; micButton.text = "🎤"
            appendLine("系统", msg)
        } }

        speech.initTts { ok, zh ->
            runOnUiThread {
                if (!ok) {
                    appendLine("系统", "语音合成不可用,回答只显示文字。")
                } else if (!zh) {
                    appendLine("系统", "本机没有中文语音包,念中文会不准。可在系统设置里安装「中文(简体,中国)」语音。")
                }
                appendLine("系统", "知识库:事实 ${bot.factCount} 条 / 调教问答 ${bot.qaCount} 条 / 拼音表 ${bot.pinyinCount} 字")
            }
        }

        findViewById<Button>(R.id.send).setOnClickListener { sendCurrentInput() }
        micButton.setOnClickListener { toggleListen() }
        voiceToggle.setOnClickListener {
            voiceOn = !voiceOn
            voiceToggle.text = if (voiceOn) "🔊 语音开" else "🔇 语音关"
            if (!voiceOn) speech.stopSpeaking()
        }

        // 开场白:与原版一致,她先招呼你
        bot.randomGreeting()?.let { greet ->
            appendLine("魔理沙", greet)
            if (voiceOn) speech.speak(greet)
        }
    }

    private fun sendCurrentInput() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        appendLine("你", text)

        // 教学格式:关键词`回答
        val tick = text.indexOf('`')
        if (tick > 0) {
            val kw = text.substring(0, tick).trim()
            val ans = text.substring(tick + 1).trim()
            if (kw.isNotEmpty() && ans.isNotEmpty()) {
                bot.teach(kw, ans)
                val reply = "记住啦:$kw → $ans。本天才的记忆力可是很好的だぜ!"
                appendLine("魔理沙", reply)
                if (voiceOn) speech.speak(reply)
                return
            }
        }

        val answer = bot.reply(text)
        appendLine("魔理沙", answer.text)
        if (voiceOn) speech.speak(answer.text)
    }

    private fun onHeard(text: String) {
        listening = false
        micButton.text = "🎤"
        input.setText(text)
        sendCurrentInput()
    }

    private fun toggleListen() {
        if (listening) {
            speech.stopListening()
            listening = false
            micButton.text = "🎤"
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        startListening()
    }

    private fun startListening() {
        speech.stopSpeaking()
        listening = true
        micButton.text = "⏹"
        speech.listen()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startListening()
            } else {
                Toast.makeText(this, "没有麦克风权限,只能打字聊天", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun appendLine(who: String, text: String) {
        val prefix = when (who) {
            "你" -> "你"
            "魔理沙" -> "魔理沙"
            else -> "系统"
        }
        val line = "[${timeFmt.format(Date())}] $prefix > $text\n"
        chatView.append(line)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        speech.release()
        super.onDestroy()
    }

    private companion object {
        const val REQ_MIC = 1001
    }
}

package com.kiki.android;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 还原 2011 年原版的界面与交互:
 *
 *   1. **一整条消息框**:每条消息占满整行,蓝白交替条纹(不是左右气泡)
 *      —— AI 靠左、用户靠右,靠对齐区分而不是靠气泡
 *   2. **长按下方灰色区域说话**:原版是纯语音,没有打字输入框
 *   3. **不知道就反问**:由 KikiBot 负责("他是谁？",用户回答后学会)
 *
 * 语音用离线的 Vosk 引擎:系统识别服务在本机不可用
 * (小米在线引擎对第三方调用返回 50010004),详见 OfflineRecognizer。
 */
public class MainActivity extends Activity {

    private static final int REQ_MIC = 1001;

    /** 说话区:空闲深灰、录音中绿(给用户明确反馈) */
    private static final int TALK_IDLE = 0xFF3C3C3C;
    private static final int TALK_RECORDING = 0xFF3E7A46;

    private KikiBot bot;
    /** 神经语言模型 + 本地记忆(对应原作者的"本地+网络") */
    private ChatBrain brain;
    private Store store;
    private SpeechManager speech;
    private OfflineRecognizer offline;
    private volatile boolean offlineReady = false;

    private LinearLayout messages;
    private ScrollView scroll;
    private FrameLayout talkArea;
    private TextView talkHint;

    /** 会话记录(旋转屏幕后重建) */
    private final List<String[]> history = new ArrayList<String[]>();
    /** 是否正在录音(长按期间为 true) */
    private volatile boolean recording = false;
    /** 条纹计数:让 kiki/用户的每一行交替深浅 */
    private int rowIndex = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        messages = (LinearLayout) findViewById(R.id.messages);
        scroll = (ScrollView) findViewById(R.id.scroll);
        talkArea = (FrameLayout) findViewById(R.id.talkArea);
        talkHint = (TextView) findViewById(R.id.talkHint);
        // FrameLayout 上 XML 的 background 在部分 ROM 会被忽略,这里用代码再设一次
        talkArea.setBackgroundColor(TALK_IDLE);

        bot = new KikiBot(getAssets());
        try {
            store = new Store(this);
            bot.setStore(store);
        } catch (Throwable t) {
            store = null;
        }

        setupTalkArea();
        // 引擎自检:真机上跑一遍推理用例(结果见 logcat tag kiki-engine)
        // 放到独立线程:它要建第二个引擎(载入全部 assets),
        // 在主线程做会拖慢启动、并和语音初始化抢资源。
        new Thread(new Runnable() {
            @Override
            public void run() {
                EngineSelfTest.run(getAssets());
            }
        }, "kiki-engine-t").start();
        initTts();
        initOfflineAsr();
        initBrain();
        initLlm();
        llmSelfTest();

        // 进入时**不显示任何文字** —— 用户要求把开场白也去掉。
        //
        // 原版 kiki 会在进入时先说一句(随机 greeting),但用户希望界面干净到底:
        // 屏幕上只有背景图和底部说话区,没有任何消息行。
        // 开场白仍然保留在数据里(greeting.txt),只是进入时不再自动上屏 ——
        // 想恢复只需把下面两行注释放开。
        // String greet = bot.randomGreeting();
        // if (greet != null) addMessage("kiki", greet);
    }

    /**
     * 系统/状态通知 —— **只写日志,不上屏**。
     *
     * 用户明确要求"进入时不要弹提示"。这些信息(知识库条数、模型载入进度、
     * 训练损失等)对排障有用,对用户全是噪音,所以导流到 logcat(tag: kiki-notify)。
     */
    private void notifyUi(String msg) {
        android.util.Log.i("kiki-notify", msg == null ? "" : msg);
    }

    // ---------------- 说话区交互 ----------------
    /** 本次录音是否由"点一下"启动(决定松手时要不要停) */
    private boolean tapMode = false;

    /**
     * 说话区交互 —— 同时支持两种习惯,但**绝不允许卡住**:
     *
     *   长按(按住说话):按下开始,松手立刻停。这是原版行为,最可靠。
     *   点一下:点一下开始,说完自动停(靠静音判定收尾);想立刻结束就再点一下。
     *
     * 【踩过的坑】上一版短按也去调 startRecording(),于是第二次点击时
     * recording 已经是 true、startRecording 直接返回;而 ACTION_UP 时
     * 因为按住时间 < 400ms 又不调 stopRecording() —— 结果**再也停不下来**。
     * 用户反馈原话:"长按正常,但是点击只有第一次可选"。
     *
     * 现在规则很简单:**只要正在录音,下一次触摸无论多短都算"停止"**。
     */
    private void setupTalkArea() {
        talkArea.setOnTouchListener(new View.OnTouchListener() {
            private long downAt = 0L;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downAt = System.currentTimeMillis();
                        android.util.Log.i("kiki-touch", "DOWN recording=" + recording + " offlineReady=" + offlineReady);
                        if (recording) {
                            // 已在录音:这次触摸一律当"停止",绝不卡死
                            stopRecording();
                            tapMode = false;
                            return true;
                        }
                        tapMode = true;              // 先假定是"点一下"
                        startRecording();
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (!recording) return true;
                        long held = System.currentTimeMillis() - downAt;
                        if (held >= 400) {
                            stopRecording();         // 长按习惯:松手即停
                            tapMode = false;
                        } else if (tapMode) {
                            // 短按:继续录,交给静音判定收尾
                            setHint("● 正在录音…\n（松开或再点一下就结束）");
                        }
                        return true;
                    default:
                        return true;
                }
            }
        });
    }

    private void startRecording() {
        if (recording) return;
        if (!offlineReady) {
            setHint("还在准备离线语音,稍等一下…");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        recording = true;
        // 视觉反馈要**非常明确**:用户反馈"点一下之后要点第二次才开始录",
        // 实际日志证明第一次点击就已经在录了(DOWN recording=false → startRecording),
        // 只是提示不够醒目、颜色变化看不出来,让人以为还没开始。
        talkArea.setBackgroundColor(TALK_RECORDING);
        setHint("● 正在录音…\n（松开或再点一下就结束）");
        try {
            offline.start();
        } catch (Throwable t) {
            recording = false;
            talkArea.setBackgroundColor(TALK_IDLE);
            setHint("启动录音失败: " + t.getMessage());
        }
    }

    private void stopRecording() {
        if (!recording) return;
        recording = false;
        talkArea.setBackgroundColor(TALK_IDLE);
        try {
            offline.stop();
        } catch (Throwable ignored) {
        }
        setHint("…识别中…");
    }

    /**
     * 对话模型自检:载入完成后自动问一句,把回答写进 logcat(tag kiki-llm)。
     * 排障用 —— 否则没有语音输入就永远看不到模型输出。
     */
    private void llmSelfTest() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 只跑一次(用 SharedPreferences 记标记)。
                    // 自检要生成 3 次,每次几秒 —— 不加这个标记的话每次启动都白耗一次电。
                    android.content.SharedPreferences sp =
                            getSharedPreferences("kiki_llm_test", MODE_PRIVATE);
                    if (sp.getBoolean("done", false)) return;

                    // 等模型接入(最多 120 秒)
                    for (int i = 0; i < 120 && !brain.hasLlm(); i++) Thread.sleep(1000);
                    if (!brain.hasLlm()) {
                        android.util.Log.w("kiki-llm", "自检跳过:对话模型未就绪");
                        return;
                    }
                    android.util.Log.i("kiki-llm", "=== 对话模型自检开始 ===");
                    String[] qs = {"你好", "你是谁", "我好累啊"};
                    for (String q : qs) {
                        long t0 = System.currentTimeMillis();
                        Answer a = brain.reply(q);
                        android.util.Log.i("kiki-llm", "问: " + q + "  ->  " + a.text
                                + "   (" + (System.currentTimeMillis() - t0) + " ms)");
                    }
                    // 顺便自检 TTS:念一句,验证语音合成真的出声
                    if (speech != null) {
                        android.util.Log.i("kiki-tts", "自检: 准备朗读一句");
                        speech.speak("你好，我是kiki，语音合成测试。");
                    }
                    android.util.Log.i("kiki-llm", "=== 对话模型自检结束 ===");
                    sp.edit().putBoolean("done", true).apply();
                } catch (Throwable t) {
                    android.util.Log.e("kiki-llm", "自检异常: " + t);
                }
            }
        }, "kiki-llm-test").start();
    }

    // ---------------- 真正的对话模型(ONNX) ----------------
    /**
     * 后台载入 Qwen2.5-0.5B。首次要把 460MB 模型从 assets 释放到私有目录(约 1~2 秒),
     * 之后直接复用。载入完成前她走规则引擎,载入后就能正常聊天了。
     */
    private void initLlm() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            notifyUi("正在载入对话模型(首次约几秒)…");
                        }
                    });
                    final LlmEngine e = LlmBootstrap.init(MainActivity.this,
                            ChatBrain.SYSTEM_PROMPT);
                    if (e != null && e.isReady()) {
                        brain.setLlm(e);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                notifyUi("对话模型已就绪,现在我能好好说话了~");
                            }
                        });
                    } else {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                notifyUi("对话模型载入失败,先用规则引擎回答");
                            }
                        });
                    }
                } catch (Throwable t) {
                    android.util.Log.e("kiki-llm", "initLlm 失败: " + t);
                }
            }
        }, "kiki-llm-init").start();
    }

    // ---------------- 神经语言模型 ----------------
    /**
     * 初始化对话大脑:加载/训练神经语言模型。
     * 首次运行要训练(1-2 分钟,后台跑),期间界面照常能用(走检索引擎)。
     */
    private void initBrain() {
        brain = new ChatBrain(this, bot);
        brain.init(new ChatBrain.Status() {
            @Override
            public void onStatus(final String message) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        notifyUi(message);
                    }
                });
                // 模型就绪后跑一次自我进化自检(只跑一次,开发者探针)
                if (message != null && message.contains("训练完成")) {
                    SelfTest.runOnceIfNeeded(MainActivity.this, brain);
                }
            }
        });
    }

    // ---------------- 离线语音识别 ----------------
    private void initOfflineAsr() {
        offline = new OfflineRecognizer(this);
        offline.setCallback(new OfflineRecognizer.Callback() {
            @Override
            public void onPartial(final String text) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setHint("正在听…\n" + text);
                    }
                });
            }

            @Override
            public void onFinal(final String text) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setHint("长按说话 / 点一下开始");
                        talkArea.setBackgroundColor(TALK_IDLE);
                        onHeard(text);
                    }
                });
            }

            @Override
            public void onError(final String message) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setHint("长按说话 / 点一下开始");
                        talkArea.setBackgroundColor(TALK_IDLE);
                        notifyUi(message);
                    }
                });
            }
        });
        new Thread(new Runnable() {
            @Override
            public void run() {
                File dir = ModelInstaller.find(MainActivity.this);
                if (dir == null) {
                    final String msg = ModelInstaller.describeProblem(MainActivity.this);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            notifyUi("离线语音未就绪:" + msg);
                        }
                    });
                    return;
                }
                final boolean ok = offline.loadModel(dir);
                offlineReady = ok;

                if (!ok) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            notifyUi("离线语音模型加载失败");
                        }
                    });
                }
            }
        }, "kiki-model").start();
    }

    // ---------------- TTS(念出她的回答) ----------------
    private void initTts() {
        speech = new SpeechManager(this);
        speech.initTts(new SpeechManager.Boolean2Callback() {
            @Override
            public void onReady(final boolean ok, final boolean chinese) {
                // 必须有日志:之前这里是空的,导致"TTS 到底成没成"完全看不出来
                // (实测用户手机的 tts_default_synth 是 null,引擎根本没得用)。
                android.util.Log.i("kiki-tts", "TTS 初始化: 可用=" + ok + " 支持中文=" + chinese);
                if (!ok) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            notifyUi("本机没有可用的语音合成引擎,回答只会显示文字。"
                                    + "可在系统设置里安装语音引擎(如讯飞语记、Google 语音服务)。");
                        }
                    });
                } else if (!chinese) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            notifyUi("语音引擎不支持中文,念中文会不准。"
                                    + "建议在系统设置→语音合成里装中文语音包。");
                        }
                    });
                }
            }
        });
    }

    /**
     * 识别到一句话之后:走完整的自我进化闭环。
     * 异步:因为中间可能上网搜,不能阻塞界面。
     */
    private void onHeard(String text) {
        // 拼音纠错曾经接过,但实测无效(详见 INSTALL.md):
        // "水灾一百舒适度都沸腾" 与 "水在一百摄氏度沸腾" 的拼音相似度只有 0.50,
        // 达不到安全阈值;而把阈值降到 0.6 又会把 "北京是谁的首都" 误纠成
        // "北京是中国的首都"。所以不启用,避免帮倒忙。
        addMessage("你", text);
        if (brain == null) {
            Answer a = bot.reply(text);
            addMessage("kiki", a.text);
            if (speech != null) speech.speak(a.text);
            return;
        }
        setHint("思考中…");
        brain.replyAsync(text, new ChatBrain.ReplyCallback() {
            @Override
            public void onReply(final Answer answer, final String learnedFrom) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setHint("长按说话 / 点一下开始");
                        if (learnedFrom != null) {
                            notifyUi("刚" + learnedFrom + "学了新东西,已存入本地");
                        }
                        addMessage("kiki", answer.text);
                        if (speech != null) speech.speak(answer.text);
                    }
                });
            }
        });
    }

    /**
     * 底部说话区的提示文字 —— **按用户要求不再显示**。
     *
     * 原本空闲时显示"长按说话 / 点一下开始",录音时显示"● 正在录音…"。
     * 用户希望界面干净(进来只看到她的招呼),所以这里改成空实现:
     * 调用点全部保留(便于将来想恢复时只改这一处),但不再影响界面。
     * 录音状态仍可通过说话区**变绿**看出来。
     */
    private void setHint(String s) {
        // 刻意留空:不显示任何提示文字
    }

    // ---------------- 消息渲染:整行条纹 ----------------
    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void addMessage(String who, String text) {
        history.add(new String[]{who, text});
        renderRow(who, text);
        scroll.post(new Runnable() {
            @Override
            public void run() {
                scroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    /**
     * 渲染一整行 —— 还原原版那条"一长条":
     *   整行满宽 + **半透明**蓝白交替条纹(背景图透出来);AI 靠左、用户靠右。
     */
    private void renderRow(String who, String text) {
        if (messages == null) return;
        boolean isKiki = "kiki".equals(who);
        boolean isUser = "你".equals(who);

        TextView tv = new TextView(this);
        tv.setText(text);
        // 字号:原版聊天区字不小,21sp
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, isKiki || isUser ? 21 : 15);
        // 上下留白加大,让每条消息更"高"、更透气(原版行高就比较宽松)
        tv.setPadding(dp(14), dp(18), dp(14), dp(18));

        // AI 回复:白色半透明(用户指定);用户提问:深色半透明,便于区分。
        // 两者都是半透明,所以背景图会透出来;文字用阴影保证可读性。
        boolean isAi = isKiki;
        tv.setBackgroundResource(isAi ? R.drawable.stripe_a : R.drawable.stripe_b);
        if (isAi) {
            // 白底 → 深色字,不需要阴影
            tv.setTextColor(Color.parseColor("#10243C"));
            tv.setShadowLayer(0f, 0f, 0f, 0);
        } else {
            // 深底 → 白字 + 阴影
            tv.setTextColor(Color.parseColor("#FFFFFF"));
            tv.setShadowLayer(4f, 1f, 1f, Color.parseColor("#CC000000"));
        }

        // AI 靠左、用户靠右(原版用对齐区分,而不是气泡)
        if (isKiki) {
            tv.setGravity(Gravity.LEFT);
            rowIndex++;
        } else if (isUser) {
            tv.setGravity(Gravity.RIGHT);
            rowIndex++;
        } else {
            // 系统提示:保持半透明深色,居中、小字
            tv.setBackgroundResource(R.drawable.stripe_b);
            tv.setGravity(Gravity.CENTER);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tv.setTextColor(Color.parseColor("#E0E0E0"));
            tv.setPadding(dp(14), dp(10), dp(14), dp(10));
        }

        tv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        messages.addView(tv);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startRecording();
            } else {
                Toast.makeText(this, "没有麦克风权限,没法语音聊天", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        try {
            if (offline != null) offline.release();
        } catch (Throwable ignored) {
        }
        try {
            if (speech != null) speech.release();
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }
}

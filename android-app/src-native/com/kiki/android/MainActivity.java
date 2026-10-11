package com.kiki.android;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
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

    /**
     * 横带上的文字颜色 —— **深色**(用户确认:两种带上都是深色字)。
     * 用近黑的深蓝,而不是纯黑,和蓝色横带更搭。
     */
    private static final int STRIPE_TEXT = 0xFF0E2438;

    private KikiBot bot;
    /** 神经语言模型 + 本地记忆(对应原作者的"本地+网络") */
    private KikiBrain brain;
    private Store store;
    private SpeechManager speech;
    private OfflineRecognizer offline;
    private volatile boolean offlineReady = false;

    private LinearLayout messages;
    private ScrollView scroll;
    private FrameLayout talkArea;

    /** 会话记录(旋转屏幕后重建) */
    private final List<String[]> history = new ArrayList<String[]>();

    /** 底部那条左右游走的红色条 = "我在听" */
    /** 特性 2:用户偏好画像(观察说话习惯并反向适应) */
    private PrefProfiler prefProfiler;
    /** 特性 2:联网学习(维基抓取) */
    private NetworkLearner netLearner;

    private ListeningBar listenBar;

    /** 开场白(等 TTS 就绪后再念;TTS 初始化实测要约 2.3 秒) */
    private String pendingGreeting = null;
    /** 开场白是否已念过(防止 TTS 回调与超时兜底重复触发) */
    private boolean greetSpoken = false;

    /** 是否处于"自动对话循环"中(避免重入) */
    private volatile boolean autoLoop = false;

    /** 防止 TTS 播完回调在这台机器上不触发 —— 用超时兜底 */
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable autoListen = new Runnable() {
        @Override
        public void run() {
            android.util.Log.i("kiki-loop", "autoListen 触发");
            beginListening();
        }
    };
    /** 是否正在录音(长按期间为 true) */
    private volatile boolean recording = false;

    /** 正在思考/等回答(期间不收音,避免把她的声音录进去) */
    private volatile boolean thinking = false;
    /** 条纹计数:让 kiki/用户的每一行交替深浅 */
    private int rowIndex = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        messages = (LinearLayout) findViewById(R.id.messages);

        scroll = (ScrollView) findViewById(R.id.scroll);
        talkArea = (FrameLayout) findViewById(R.id.talkArea);
        listenBar = (ListeningBar) findViewById(R.id.listenBar);
        // 【重要】这里**不要**再 setBackgroundColor ——
        // 布局里给的是半透明 #B03C3C3C,而代码里写死 #FF3C3C3C 会把它变成不透明,
        // 用户反馈"底部灰色条不透明"就是这么来的(之前误以为 XML 的 background
        // 在部分 ROM 会被忽略,实测这个担心不成立)。

        bot = new KikiBot(getAssets());
        try {
            store = new Store(this);
            bot.setStore(store);
        } catch (Throwable t) {
            store = null;
        }

        // 引擎自检:真机上跑一遍推理用例(结果见 logcat tag kiki-engine)
        // 放到独立线程:它要建第二个引擎(载入全部 assets),
        // 在主线程做会拖慢启动、并和语音初始化抢资源。
        new Thread(new Runnable() {
            @Override
            public void run() {
                EngineSelfTest.run(getAssets());   // 20 条推理用例
                DialogSelfTest.run();               // 主动学习闭环(特性 1 的核心)
                Feature2SelfTest.run(MainActivity.this);   // 特性 2 自检
                SummarizerSelfTest.run(MainActivity.this);   // 语言总结自检
                Dialog100Test.run(MainActivity.this);   // 100 轮对话测试
                MemoryTest.run(MainActivity.this);   // 记忆持久化专项测试
                HomophoneSelfTest.run(bot);   // 同音词纠正自检(复用已有 bot,避免 OOM)
                ClearCommandSelfTest.run(MainActivity.this);   // 清除数据指令自检
                ReproTest.run(MainActivity.this);   // 截图场景复现
            }
        }, "kiki-engine-t").start();
        // 临时 UI 测试模式(--ez uitest true 启动时进入)
        initTts();
        initOfflineAsr();
        initBrain();

        // 开场白:原版进入时会先说一句(截图里的"欢迎回来"),用户确认要恢复
        // 开场白必须等 TTS 就绪再播 —— 实测 TTS 初始化要约 2.3 秒,
        // 之前直接播会被 speak() 以 "ttsReady=false" 跳过(开场白静音,
        // 而且随后立刻开听,用户以为她没说话)。
        final String greet = bot.randomGreeting();
        pendingGreeting = greet;
        if (greet != null) addMessage("kiki", greet);
        // 兜底:即使 TTS 回调不来,6 秒后也进入监听,避免对话卡死
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!greetSpoken) {
                    greetSpoken = true;
                    android.util.Log.i("kiki-loop", "TTS 未就绪兜底:直接开始听");
                    ui.postDelayed(autoListen, 300);
                }
            }
        }, 6000);
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

    // ---------------- 自动对话循环(原版没有按钮) ----------------
    //
    // 原版行为(用户口述):
    //   "他是自己说完几句话就会开始识别,不需要用户动手点击或长按"
    //
    // 所以这里没有任何触摸监听。循环是:
    //   她说完(TTS onDone) -> 自动开录 -> 静音判定结束 -> 识别 -> 思考 -> 回答 -> 回到开头
    //
    // 红色移动条(叫 listenBar)在"等她说话"时显示,在她说话/处理时隐藏。

    /** 进入"等她说话"状态:显示红色条 + 开录音 */
    private void beginListening() {
        android.util.Log.i("kiki-loop", "beginListening: offlineReady=" + offlineReady
                + " recording=" + recording + " thinking=" + thinking);
        if (isFinishing()) return;
        if (!offlineReady) {
            android.util.Log.i("kiki-loop", "模型未就绪,1.5 秒后重试");
            // 模型还没装好,等一会儿再试(首次启动会解压 42MB 语音模型)
            ui.postDelayed(autoListen, 1500);
            return;
        }
        if (recording || thinking) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        recording = true;
        // 【用户最终确认】红条"只在 AI 说话时出现" ——
        // 所以等待用户说话期间**不显示**。这里不要再 start()。
        android.util.Log.i("kiki-loop", "开始录音(等待用户说话,红条隐藏) offline.isBusy="
                + offline.isBusy());
        try {
            offline.start();
        } catch (Throwable t) {
            android.util.Log.e("kiki-loop", "启动录音异常: " + t);
            recording = false;
            if (listenBar != null) listenBar.stop();
            ui.postDelayed(autoListen, 2000);
        }
    }

    /** 识别到一句话之后:停红条 -> 思考 -> 回答 -> 再听 */

    /**
     * 念出回答,**念完自动开始听**。
     *
     * 兜底:TTS 引擎缺失或 onDone 不回调时,用一个按字数估算的超时来推进循环,
     * 否则对话会停在那里再也不动。
     */
    private void speakThenListen(String text) {
        final boolean[] advanced = new boolean[1];
        final Runnable go = new Runnable() {
            @Override
            public void run() {
                android.util.Log.i("kiki-loop", "准备开听 (advanced=" + advanced[0] + ")");
                if (advanced[0]) return;
                advanced[0] = true;
                // 她说完了 -> 红条收起(之后进入"等你说话",按新语义不显示)
                if (listenBar != null) listenBar.stop();
                ui.removeCallbacks(autoListen);
                ui.postDelayed(autoListen, 350);   // 留一点停顿,别抢话
            }
        };

        boolean spoke = false;
        if (speech != null) {
            try {
                speech.onSpeakDone = go;
                speech.speak(text);
                spoke = speech.isChineseAvailable();
            } catch (Throwable ignored) {
                spoke = false;
            }
        }
        android.util.Log.i("kiki-loop", "speakThenListen: spoke=" + spoke
                + " textLen=" + (text == null ? 0 : text.length()));
        // AI 说话期间显示红条 —— 这是红条**唯一**出现的时机
        if (listenBar != null) listenBar.start();
        if (!spoke) {
            // 没引擎:等一个按字数估的时间,让用户看清字,然后继续
            int ms = Math.min(9000, 900 + (text == null ? 0 : text.length()) * 180);
            android.util.Log.i("kiki-loop", "无 TTS 引擎,兜底 " + ms + "ms 后开听");
            ui.postDelayed(go, ms);
        } else {
            // 引擎在,但 onDone 可能不触发 —— 按字数估一个上限兜底
            int ms = Math.min(30000, 4000 + (text == null ? 0 : text.length()) * 320);
            android.util.Log.i("kiki-loop", "已排定开听: 兜底上限 " + ms + "ms");
            ui.postDelayed(go, ms);
        }
    }

    /** 用户手动打断:停止一切 */
    private void stopAll() {
        ui.removeCallbacks(autoListen);
        recording = false;
        thinking = false;
        if (listenBar != null) listenBar.stop();
        try {
            if (offline != null) offline.stop();
        } catch (Throwable ignored) {
        }
        try {
            if (speech != null) speech.stopSpeaking();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 底部提示文字 —— 按用户要求**不再显示**。
     * 原版这里没有任何文字,只有那条游走的红色条。
     */
    private void setHint(String s) {
        // 刻意留空
    }

    // ---------------- 对话大脑 ----------------

    /**
     * 初始化对话大脑。
     *
     * 不再有任何"训练"步骤 —— 这里完全不跑语言模型:
     * 回答靠规则引擎(事实检索 + 因果推导)与对话状态机(主动学习)。
     * 装载 1795 条事实是瞬时的,所以不需要后台线程。
     */
    private void initBrain() {
        brain = new KikiBrain(this, bot, bot.getBrain());
        brain.setStore(store);
        // 【修"退出就忘"】把本地库里攒下的事实灌回内存事实图。
        // 之前 persistLearned() 一直在写库,但启动时从不读回来,
        // 所以重启后所有自学知识全丢。
        brain.restoreFacts();
        // 特性 2:偏好画像 + 联网学习
        if (store != null) {
            prefProfiler = new PrefProfiler(store);
            netLearner = new NetworkLearner(store);
        }
    }

    // ---------------- 离线语音识别 ----------------
    private void initOfflineAsr() {
        offline = new OfflineRecognizer(this);
        offline.setCallback(new OfflineRecognizer.Callback() {
            @Override
            public void onPartial(final String text) {
                // 原版没有实时字幕,只在日志里留痕(便于排障)
                android.util.Log.i("kiki-vosk", "识别中: " + text);
            }

            @Override
            public void onFinal(final String text) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        onHeard(text);
                    }
                });
            }

            @Override
            public void onError(final String message) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // "等待超时"是常态(用户还没开口)—— 不能就此停住。
                        // 原版是**一直听着**,所以这里让循环自己再起一轮。
                        //
                        // 【注意】这里**不要** stop 红条:用户看到的"红条在闪"
                        // 就是因为超时重试时把它灭了又点亮。红条只应该在
                        // "她说话/处理中/用户已说完"时隐藏(见 onHeard)。
                        recording = false;
                        notifyUi(message);
                        if (!thinking) {
                            ui.removeCallbacks(autoListen);
                            ui.postDelayed(autoListen, 200);
                        }
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

                // TTS 就绪 -> 念开场白 -> 念完自动开始听(对话循环的起点)
                if (!greetSpoken && pendingGreeting != null) {
                    greetSpoken = true;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            android.util.Log.i("kiki-loop", "TTS 就绪,开始念开场白");
                            speakThenListen(pendingGreeting);
                        }
                    });
                }
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
     * 识别到一句话之后:停红条 -> 思考 -> 回答 -> **再自动开始听**。
     *
     * 【踩坑记录】这里曾经留着一份旧实现(只 addMessage + replyAsync + speak),
     * 没有重新开听 —— 表现为"第一轮正常,她答完就再也不听了"。
     * 原因是早先一次脚本删方法时删错了对象(删掉新版、留下旧版)。
     * 现在把整段替换为正确实现,并加日志便于回归。
     */
    private void onHeard(String text) {
        android.util.Log.i("kiki-loop", "onHeard: " + text);
        recording = false;
        // 【用户最终确认】"红条是在识别时消失" ——
        // 所以收到识别结果、进入思考/回答阶段时隐藏它。
        if (listenBar != null) listenBar.stop();
        thinking = true;
        addMessage("你", text);

        // 特性 2:观察这句,更新"她眼里的你"(说话风格/提问习惯/常聊话题)
        if (prefProfiler != null) {
            try { prefProfiler.observe(text); } catch (Throwable ignored) {}
        }

        if (brain == null) {
            Answer a = bot.reply(text);
            addMessage("kiki", a.text);
            thinking = false;
            speakThenListen(a.text);
            return;
        }

        brain.replyAsync(text, new KikiBrain.Callback() {
            @Override
            public void onReply(final Answer answer, final String learnedFrom) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        thinking = false;
                        // 【清理指令的收尾】KikiBrain 清的是库与事实图,
                        // 但主线程这边还缓存着偏好画像、同音索引和
                        // SharedPreferences 里"半截对话"的存档,都要一起清,
                        // 否则重启后旧的偏好与中间态又回来了。
                        if (answer.kind != null && answer.kind.equals("cmd-clear")) {
                            try {
                                getSharedPreferences("kiki", MODE_PRIVATE).edit().clear().commit();
                            } catch (Throwable ignored) {}
                            if (store != null) {
                                prefProfiler = new PrefProfiler(store);
                            }
                            if (brain != null) brain.reloadFacts();
                            setHint("");
                        }
                        // 特性 2:按"她眼里的你"调整措辞
                        // 必须是 final —— 下面的匿名 Callback 会捕获它
                        String adapted = answer.text;
                        if (prefProfiler != null) {
                            try { adapted = prefProfiler.adapt(adapted); } catch (Throwable ignored) {}
                        }
                        final String out = adapted;
                        // 特性 2:本地不懂 -> 先联网学一次,学到就直接答
                        if (shouldTryNet(answer)) {
                            final String subj = netSubject(answer.text);
                            if (subj != null && netLearner != null) {
                                android.util.Log.i("kiki-loop", "本地不懂,尝试联网学: " + subj);
                                netLearner.learnAsync(subj, new NetworkLearner.Callback() {
                                    @Override
                                    public void onLearned(final NetworkLearner.Result r) {
                                        runOnUiThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                if (r.ok && r.factCount > 0 && r.summary != null) {
                                                    // 学到了:直接用网络学到的内容回答
                                                    // 【只念前两句】截图实测:查"康氏是谁"时
                                                    // 把整段维基正文(几百字)当回答吐出来了,
                                                    // 屏幕刷满、语音念半天。这里截断到 60 字。
                                                    String learned = r.summary;
                                                    if (learned.length() > 60) {
                                                        learned = learned.substring(0, 60) + "…";
                                                    }
                                                    // 联网事实是 NetworkLearner 直接写库的,
                                                    // 内存事实图不知道 —— 补一次,否则
                                                    // "刚学完再问还是答不出"。
                                                    if (brain != null) brain.reloadFacts();
                                                    addMessage("kiki", learned);
                                                    speakThenListen(learned);
                                                } else {
                                                    // 【用户要求】联网也没查到 -> **不要反问**,
                                                    // 直接说"我查不到",把主动权交回用户
                                                    // (她想教就教,不想教也不会被她追着问)。
                                                    // 这和特性 1 的"反问"不冲突:那条路径是
                                                    // 她没去查、当场不懂时用的;这里是"查过了,确实没有"。
                                                    String miss = "我查不到「" + subj + "」，你教教我好不好？";
                                                    addMessage("kiki", miss);
                                                    speakThenListen(miss);
                                                }
                                            }
                                        });
                                    }
                                });
                                return;
                            }
                        }
                        addMessage("kiki", out);
                        // 念完自动回到"听" —— 原版全程不需要用户操作
                        speakThenListen(out);
                    }
                });
            }
        });
    }

    /**
     * 这条回答是"我不懂"类的话术吗 —— 是的话就值得联网试一次。
     *
     * 两个条件必须同时满足:
     *   1. 她在说"我不知道…/我不知道 X 是谁";
     *   2. 从这句话里**能抽出要查的主语** —— 抽不出(比如通用话术
     *      "唔…这个我暂时不知道")就没有可查的对象,联网也是白费,
     *      那种情况保持她原来的反问。
     */
    private boolean shouldTryNet(Answer a) {
        if (a == null || netLearner == null) return false;
        String t = a.text == null ? "" : a.text;
        boolean soundsUnknown = t.contains("我不知道") || t.contains("我还不知道");
        if (!soundsUnknown) return false;
        return netSubject(t) != null;
    }

    /** 从"我不知道，X是谁？"里取回 X,用于联网查询 */
    private String netSubject(String t) {
        if (t == null) return null;
        int i = t.indexOf('，');
        if (i < 0) i = t.indexOf(',');
        String sub = (i >= 0 ? t.substring(i + 1) : t).trim();
        for (String tail : new String[]{"是谁？", "是谁?", "是什么？", "是什么?"}) {
            if (sub.endsWith(tail)) {
                sub = sub.substring(0, sub.length() - tail.length());
                break;
            }
        }
        sub = sub.replace("？", "").replace("?", "").trim();
        return (sub.isEmpty() || sub.length() > 12) ? null : sub;
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
     * 渲染一条消息 —— 还原原版的"满宽横带"。
     *
     * 依据(docs/原版界面与交互规格.md,用户逐项确认):
     *   - 整个对话区**铺满交替色横带**(斑马纹),文字直接压在带上
     *   - 横带**随对话一起滚动**(所以它属于消息列表,不是固定背景)
     *   - 带上的字**都是深色**
     *   - 她的话靠左,用户的话靠右
     *   - 长文本换行后**仍是同一条**(同色连成一片)
     */
    private void renderRow(String who, String text) {
        if (messages == null) return;
        boolean isKiki = "kiki".equals(who);
        boolean isUser = "你".equals(who);
        String body = text == null ? "" : text;

        // 斑马纹:按"内容条"计数交替(系统提示不占条纹序号)
        int stripe = (isKiki || isUser) ? (rowIndex++ % 2) : (rowIndex % 2);

        TextView tv = new TextView(this);
        tv.setText(body);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);   // 用户要求"文字更大一点"
        // 行高:**完全由内容决定**(wrap_content),不再写死、也不再事后改。
        // 上下留白让每条消息更透气,相邻消息深浅交替,自然形成斑马纹。
        tv.setPadding(dp(14), dp(18), dp(14), dp(18));

        // 条纹用**每行自己的背景**实现(原版观感,也是用户确认过的那版做法):
        //   她的话 -> stripe_a(浅蓝白,配深色字)
        //   你的话 -> stripe_b(中蓝,  配白色字)
        // 两者都半透明,所以底下背景插画会透出来。
        //
        // 【为什么放弃"整屏画条纹"】
        // 试过三种整屏方案,都栽在同一处:消息高度是变的(1 行 / 3 行),
        // 整屏条纹的带心对不上变高的行,于是要么文字偏、要么行与行重叠。
        // 让**每行自带条纹**最稳:文字永远在自己那块的中间,行高再由内容决定。
        boolean isAi = isKiki;
        tv.setBackgroundResource(isAi ? R.drawable.stripe_a : R.drawable.stripe_b);
        if (isAi) {
            tv.setTextColor(Color.parseColor("#10243C"));   // 浅底 -> 深字
            tv.setShadowLayer(0f, 0f, 0f, 0);
        } else {
            tv.setTextColor(Color.parseColor("#FFFFFF"));   // 深底 -> 白字
            tv.setShadowLayer(4f, 1f, 1f, Color.parseColor("#CC000000"));
        }

        // 水平:她靠左、用户靠右(原版用对齐区分,不用气泡)
        tv.setGravity(isUser ? Gravity.RIGHT : Gravity.LEFT);

        tv.setTag(isUser ? "user" : "ai");

        tv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        messages.addView(tv);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                beginListening();
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

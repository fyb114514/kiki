package com.kiki.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;

/**
 * 底部那条"左右移动的红色条" —— 原版的"我在听"指示器。
 *
 * 用户原话(逐条对照)
 * ------------------
 * > 打开时底部灰色半透明图形上会有一个**左右移动的红色条**,
 * > 还有类似**渐变拖尾**的效果,而且这个效果**除了用户说话时都在**。
 * > 向什么方向移动什么方向就是纯色的,然后拖尾是逐渐变淡的。
 * > 要**更粗一点**,移动速度要**快一倍**。
 * > 而且**原程序的条到了终点会消失 0.1 秒然后再出现**。
 *
 * 所以本类要同时满足:
 *   1. 朝移动方向那端**纯色**,背后渐隐(彗星拖尾,不是两头对称)
 *   2. 走到一端后**停 0.1 秒(消失)**再继续 —— 见 END_PAUSE_MS
 *   3. 方向翻转时纯色端跟着换
 *   4. 显示/隐藏由调用方控制(start = 在听,stop = 她说话/处理)
 */
public class ListeningBar extends View {

    private static final String TAG = "kiki-loop";

    /** 条的颜色(原版是红色系) */
    private static final int BAR_COLOR = 0xFFE53935;
    /** 条高(占视图高度的比例)。用户要求"更粗一点" */
    private static final float BAR_H_RATIO = 0.86f;   // 用户要求"再粗一点"
    /** 条长(占视图宽度的比例) */
    private static final float BAR_W_RATIO = 0.55f;   // 用户要求加长
    /**
     * 单程时长(毫秒)。用户要求"快一倍":
     * 原来一个来回 4200,即单程 2100;现在单程 1050(来回 2100)。
     */
    private static final long SWEEP_MS = 430L;   // 再快一档,增强动感
    /**
     * 走到终点后**消失**的时长。
     * 用户原话:"原程序的条到了终点会消失 0.1 秒然后再出现"。
     */
    private static final long END_PAUSE_MS = 100L;
    /** 一个完整周期 = 向右 + 停 + 向左 + 停 */
    private static final long CYCLE_MS = SWEEP_MS * 2 + END_PAUSE_MS * 2;
    /** 重绘间隔(约 60fps) */
    private static final long FRAME_MS = 16L;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private long startAt = 0L;
    private boolean running = false;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            invalidate();
            if (running) handler.postDelayed(this, FRAME_MS);
        }
    };

    public ListeningBar(Context c) {
        super(c);
        init();
    }

    public ListeningBar(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    public ListeningBar(Context c, AttributeSet a, int defStyle) {
        super(c, a, defStyle);
        init();
    }

    private void init() {
        paint.setStyle(Paint.Style.FILL);
        setWillNotDraw(false);
    }

    /** 开始显示(用户没在说话时一直跑) */
    public void start() {
        android.util.Log.i(TAG, "红条 start()");
        if (running) return;
        running = true;
        startAt = System.currentTimeMillis();
        setVisibility(VISIBLE);
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    /** 隐藏(她说话时 / 处理中)。注意:用户说话时**不**调这个。 */
    public void stop() {
        android.util.Log.i(TAG, "红条 stop()");
        running = false;
        handler.removeCallbacks(tick);
        setVisibility(INVISIBLE);
    }

    public boolean isRunning() {
        return running;
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        handler.removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        // 一个周期:向右 SWEEP -> 停 END_PAUSE -> 向左 SWEEP -> 停 END_PAUSE
        long elapsed = (System.currentTimeMillis() - startAt) % CYCLE_MS;
        boolean goingRight;
        float t;
        boolean pausing = false;

        if (elapsed < SWEEP_MS) {
            // 向右走
            goingRight = true;
            t = elapsed / (float) SWEEP_MS;
        } else if (elapsed < SWEEP_MS + END_PAUSE_MS) {
            // 到达右端 -> 停住并**消失** 0.1 秒
            goingRight = true;
            t = 1f;
            pausing = true;
        } else if (elapsed < SWEEP_MS * 2 + END_PAUSE_MS) {
            // 向左走
            goingRight = false;
            t = 1f - (elapsed - SWEEP_MS - END_PAUSE_MS) / (float) SWEEP_MS;
        } else {
            // 到达左端 -> 停住并消失 0.1 秒
            goingRight = false;
            t = 0f;
            pausing = true;
        }

        // 停顿期间不画 —— 这就是"到终点消失 0.1 秒"
        if (pausing) return;

        float bw = w * BAR_W_RATIO;
        float bh = h * BAR_H_RATIO;
        float travel = w - bw;
        float left = travel * t;
        float top = (h - bh) / 2f;
        rect.set(left, top, left + bw, top + bh);

        // 朝移动方向那端**纯色**,背后渐隐(彗星拖尾)
        int[] colors;
        float[] stops;
        if (goingRight) {
            colors = new int[]{
                    withAlpha(BAR_COLOR, 0),
                    withAlpha(BAR_COLOR, 40),
                    withAlpha(BAR_COLOR, 130),
                    withAlpha(BAR_COLOR, 255),
            };
            stops = new float[]{0f, 0.40f, 0.75f, 1f};
        } else {
            colors = new int[]{
                    withAlpha(BAR_COLOR, 255),
                    withAlpha(BAR_COLOR, 130),
                    withAlpha(BAR_COLOR, 40),
                    withAlpha(BAR_COLOR, 0),
            };
            stops = new float[]{0f, 0.25f, 0.60f, 1f};
        }

        LinearGradient g = new LinearGradient(
                rect.left, 0, rect.right, 0, colors, stops, Shader.TileMode.CLAMP);
        paint.setShader(g);
        float r = Math.min(bh * 0.18f, 10f);
        canvas.drawRoundRect(rect, r, r, paint);
        paint.setShader(null);
    }

    private static int withAlpha(int color, int a) {
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
    }
}

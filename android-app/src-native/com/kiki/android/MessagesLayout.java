package com.kiki.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

/**
 * 对话区容器 —— **逐条消息绘制条纹**,保证每行文字的垂直中心落在一条带的正中。
 *
 * 为什么改成"按行绘制"
 * --------------------
 * 前几版的失败路径(都记在这里,别再走一遍):
 *
 *   1. 整屏固定条纹 + 消息按带高对齐
 *      -> 单行可以,但**多行文字会把后续行全部推偏**。
 *   2. 给每条消息 setHeight(BAND_PX) 写死高度
 *      -> 多行文字被**压扁、下半截被下一行盖住**(用户截图:"重叠在一起了")。
 *   3. addView 之后用 post 把高度向上取整到带高倍数
 *      -> 改完 LayoutParams 不会同步重新布局,**行与行仍然重叠**。
 *
 * 结论:**高度必须由内容决定**(多行不能被压),对齐只能由**绘制**保证。
 *
 * 现在的规则(逐行,从容器顶部往下排):
 *
 *     每行的条纹分配:
 *        [深带 h/4] [浅带 h/2] [深带 h/4]
 *                   ↑ 文字中心落在这里
 *
 *   文字垂直居中于本行 -> 它的中心在 h/2 -> 正好是浅带中心。
 *   所以**无论这行是 1 行还是 5 行文字,文字都在浅带正中间**,对齐是结构性的。
 *
 * 用户的话再叠一层白色半透明(原版"你的话发白、她的话是原条纹")。
 */
public class MessagesLayout extends LinearLayout {

    private final Paint paint = new Paint();

    /** 深带:原版实测 RGB(29,48,81) */
    private static final int DARK = 0xFF1D3051;
    /** 浅带:原版实测 RGB(140,159,202) */
    private static final int LIGHT = 0xFF8C9FCA;
    /** 用户行上的白色叠加(半透明,让下面的蓝条纹透出来) */
    private static final int USER_WHITE = 0x70FFFFFF;

    public MessagesLayout(Context c) {
        super(c);
        init();
    }

    public MessagesLayout(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    public MessagesLayout(Context c, AttributeSet a, int defStyle) {
        super(c, a, defStyle);
        init();
    }

    private void init() {
        paint.setStyle(Paint.Style.FILL);
        setWillNotDraw(false);   // ViewGroup 默认不调 onDraw,必须显式打开
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        if (w <= 0 || getChildCount() == 0) {
            super.onDraw(canvas);
            return;
        }

        final int y = getScrollYSafe();

        // 逐行铺条纹,行与行之间**不留缝也不重叠**
        int last = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            int top = c.getTop();
            int h = c.getBottom() - top;
            if (h <= 0) continue;
            last = top + h;

            boolean isUser = "user".equals(String.valueOf(c.getTag()));
            drawRowBands(canvas, w, top, h, isUser);
        }

        // 【最后一条消息之下也要有纹路】
        // 用户要求"一打开就有条纹",而消息只有几条时下面会露白。
        // 这里按整屏带高把余下区域补满 —— 与消息区同样的配色。
        int bottom = getHeight();
        int band = StripesView.BAND_PX;
        int y2 = last;
        boolean light = false;
        while (y2 < bottom) {
            int next = Math.min(y2 + band, bottom);
            paint.setColor(light ? LIGHT : DARK);
            canvas.drawRect(0, y2, w, next, paint);
            y2 = next;
            light = !light;
        }

        super.onDraw(canvas);
    }

    /** 单行的三带:深 h/4 / 浅 h/2 / 深 h/4,文字中心必落在浅带中心 */
    private void drawRowBands(Canvas canvas, int w, int top, int h, boolean isUser) {
        int q = h / 4;
        if (q < 1) q = 1;
        int mid = h - 2 * q;          // 中间浅带(吸收除不尽的余数)

        paint.setColor(DARK);
        canvas.drawRect(0, top, w, top + q, paint);
        paint.setColor(LIGHT);
        canvas.drawRect(0, top + q, w, top + q + mid, paint);
        paint.setColor(DARK);
        canvas.drawRect(0, top + q + mid, w, top + h, paint);

        if (isUser) {
            paint.setColor(USER_WHITE);
            canvas.drawRect(0, top, w, top + h, paint);
        }
    }

    /**
     * 容器自身的滚动位置。
     * 本 View 在 ScrollView 里是被整体平移的,getScrollY() 恒为 0,
     * 所以直接返回 0;保留方法是为了语义清楚 —— 条纹坐标用的是
     * **子 View 在容器内的坐标**,随容器一起被 ScrollView 平移,
     * 因此天然"随对话滚动"。
     */
    private int getScrollYSafe() {
        return 0;
    }
}

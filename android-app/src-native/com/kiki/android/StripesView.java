package com.kiki.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * 满屏斑马纹 —— 代码绘制,并且**随对话滚动**。
 *
 * 配色与尺寸来自对**原版视频逐帧测量**(见 docs/原版视频分析.md)
 * --------------------------------------------------------------
 * 原版不是"白色半透明盖在插画上",而是**深蓝/浅蓝两色交替的条纹**:
 *
 *   深带 RGB(29, 48, 81)     实测占 52%
 *   浅带 RGB(140, 159, 202)  实测占 48%
 *   带高约 33px(147px 宽的画面里),一屏约 8 条带
 *   文字高度约为带高的 40%,每行文字**垂直居中于一条带内**
 *
 * 历史:最早用位图平铺,被 Android 的密度缩放坑了 5 轮对不准;
 * 改成 onDraw 直接画矩形后"源图像素数 = 屏幕像素数",才可控。
 */
public class StripesView extends View {

    /**
     * 一条带的高度(像素)。
     *
     * 取值依据:原版一屏约 8 条带(带高约 33px / 262px 高的画面)。
     * 按这个比例换到 2712px 高的本机,带高会是 339px,但那会让
     * 一屏只放得下 8 行字、字号必须很小。实际使用中用户要求
     * "比文字粗一倍"并逐步加大,最终定在 192px —— 约 14 条带一屏,
     * 兼顾"带子明显比字高"与"文字够大"。
     */
    public static final int BAND_PX = 192;

    /** 深带:原版实测 RGB(29,48,81) */
    public static final int DARK = 0xFF1D3051;
    /** 浅带:原版实测 RGB(140,159,202) */
    public static final int LIGHT = 0xFF8C9FCA;

    private final Paint paint = new Paint();
    /** 滚动偏移:由 ScrollView 的 onScrollChange 同步过来 */
    private int scrollOffset = 0;
    /** 整体透明度(让背景插画透出来) */
    private int alpha = 255;

    public StripesView(Context c) {
        super(c);
        init();
    }

    public StripesView(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    public StripesView(Context c, AttributeSet a, int defStyle) {
        super(c, a, defStyle);
        init();
    }

    private void init() {
        paint.setStyle(Paint.Style.FILL);
    }

    /** 同步滚动量:让纹路与对话内容锁定在一起 */
    public void setScrollOffset(int offset) {
        if (scrollOffset == offset) return;
        scrollOffset = offset;
        invalidate();
    }

    /**
     * 设置整体透明度(0~255)。
     * 用户要求"原软件里的东西都是半透明的,能看到背景图",
     * 所以条纹要能在背景插画之上半透明显示。
     */
    public void setOverlayAlpha(int a) {
        if (a < 0) a = 0;
        if (a > 255) a = 255;
        if (alpha == a) return;
        alpha = a;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        // 偏移对齐到整条带,避免滚动时出现"半条带"抖动
        int shift = (scrollOffset / BAND_PX) * BAND_PX;

        int y = -shift - BAND_PX;
        boolean light = true;
        while (y < h + BAND_PX) {
            int bottom = y + BAND_PX;
            paint.setColor(light ? LIGHT : DARK);
            paint.setAlpha(alpha);
            canvas.drawRect(0, y, w, bottom, paint);
            y = bottom;
            light = !light;
        }
    }
}

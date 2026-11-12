package com.stepx.gapps.installer;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * 自绘图形。
 *
 * 不用 VectorDrawable —— 构建链没有资源编译，全代码画是唯一不引入新依赖的做法。
 */
final class Glyph {

    private Glyph() {}

    private static Paint stroke(int color, float widthPx) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(color);
        p.setStrokeWidth(widthPx);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        return p;
    }

    private static Paint fill(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        return p;
    }

    /**
     * 发光指示点。
     *
     * 原来用 TextView 画「●」再 setShadowLayer：阴影是被裁在 View 的矩形绘制区域里的，
     * 光晕到四角就出现直角，屏幕上一眼能看出是个正方形。改成在一张圆内画径向渐变，
     * 渐变最外圈 alpha 归零，边界天然不可见，形状必然是圆的。
     *
     * 也别改成先 setLayerType(SOFTWARE) 再用 shadowLayer —— 那只是把裁切位置
     * 从硬件层换到软件位图，方形边缘照样在，而且每帧都要多一次离屏绘制。
     */
    static final class Led extends View {
        private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int color;
        private float coreR;

        Led(Context c, int color) {
            super(c);
            this.color = color;
            halo.setStyle(Paint.Style.FILL);
        }

        void setColor(int c) {
            if (color == c) return;
            color = c;
            build();
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            build();
        }

        private void build() {
            float r = Math.min(getWidth(), getHeight()) / 2f;
            if (r <= 0) return;
            int rgb = color & 0x00FFFFFF;
            halo.setShader(new RadialGradient(getWidth() / 2f, getHeight() / 2f, r,
                    new int[]{rgb | 0xA6000000, rgb | 0x3A000000, rgb},
                    new float[]{0f, 0.52f, 1f}, Shader.TileMode.CLAMP));
            core.setColor(color);
            coreR = r * 0.40f;
        }

        @Override
        protected void onDraw(Canvas cv) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            if (coreR <= 0) build();
            cv.drawCircle(cx, cy, Math.min(cx, cy), halo);
            cv.drawCircle(cx, cy, coreR, core);
        }
    }

    /**
     * 刷新图标：一段开口圆弧 + 切向箭头。
     *
     * 不用 emoji：🔄 是彩色字形，颜色由字体自带，没法跟主题色对齐，在一排 9.5sp
     * 等宽小字里也偏大偏花。这个图是 20dp 的细描边，跟状态格里的 LED 一个分量级。
     * 探测时转起来，就顺手把「正在刷新」这件事说清楚了，不用再加文字。
     */
    static final class Refresh extends View {
        private final Paint arc;
        private final Paint headPaint;
        private final Path head = new Path();
        private int color;
        private ObjectAnimator spin;
        /** 图形外又留出的空白（px）。视图比图形大一圈，点击热区才不至于只有 17dp。 */
        private final float inset;

        Refresh(Context c, int color, float insetDp) {
            super(c);
            this.color = color;
            this.inset = Ui.dp(c, insetDp);
            arc = stroke(color, Ui.dp(c, 1.7f));
            headPaint = fill(color);
        }

        void setColor(int c) {
            if (color == c) return;
            color = c;
            arc.setColor(c);
            headPaint.setColor(c);
            invalidate();
        }

        /** 转起来表示正在探测；停下时把角度归零，免得下次从一个歪的角度开始。 */
        void spinning(boolean on) {
            if (on) {
                if (spin == null) {
                    spin = ObjectAnimator.ofFloat(this, "rotation", 0f, 360f);
                    spin.setDuration(850);
                    spin.setRepeatCount(ValueAnimator.INFINITE);
                    spin.setInterpolator(new LinearInterpolator());
                }
                if (!spin.isStarted()) spin.start();
            } else {
                if (spin != null) spin.cancel();
                setRotation(0f);
            }
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;
            float sw = arc.getStrokeWidth();
            float cx = w / 2f;
            float cy = h / 2f;
            float r = Math.min(w, h) / 2f - inset - sw;

            // 开口留在正上方，箭头正好从缺口处收进来 —— 这是「循环」的通用画法
            final float start = -62f;
            final float sweep = 286f;
            cv.drawArc(cx - r, cy - r, cx + r, cy + r, start, sweep, false, arc);

            double a = Math.toRadians(start + sweep);
            float px = cx + r * (float) Math.cos(a);
            float py = cy + r * (float) Math.sin(a);
            // 圆弧顺时针走，切线方向就是 (-sin, cos)；法线用来撑开箭头的两翼
            float tx = (float) -Math.sin(a);
            float ty = (float) Math.cos(a);
            float nx = (float) Math.cos(a);
            float ny = (float) Math.sin(a);

            // 箭头别做太胖：两翼各 0.2r、全长 0.5r。再宽就成了贴在弧端的一块菱形，
            // 这个尺寸（20dp 的图只有 60px 直径）宁可细一点，能看清是箭头就行。
            float len = r * 0.52f;
            float wing = r * 0.21f;

            head.reset();
            head.moveTo(px + tx * len, py + ty * len);
            head.lineTo(px + nx * wing, py + ny * wing);
            head.lineTo(px - nx * wing, py - ny * wing);
            head.close();
            cv.drawPath(head, headPaint);
        }
    }

    /**
     * 标题左侧的标识：渐变圆角块 + 描边六边形 + 中心节点。
     * 六边形是「芯片 / 节点」最省笔墨的画法，比放个字母 S 更像工具而不是玩具。
     */
    static final class Mark extends View {
        private final Paint strokePaint;
        private final Paint dotPaint;
        private final Path hex = new Path();

        Mark(Context c, int from, int to) {
            super(c);
            strokePaint = stroke(0xE6FFFFFF, Ui.dp(c, 1.5f));
            dotPaint = fill(0xFFFFFFFF);
            setBackground(Ui.gradient(from, to, Ui.dp(c, 10)));
            // 主色块的柔光：深色下才有体积感，浅色下几乎看不出来但不会脏
            setElevation(Ui.dp(c, 2));
        }

        @Override
        protected void onDraw(Canvas cv) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float r = Math.min(getWidth(), getHeight()) * 0.30f;

            hex.reset();
            for (int i = 0; i < 6; i++) {
                double a = Math.toRadians(-90 + i * 60);
                float x = (float) (cx + Math.cos(a) * r);
                float y = (float) (cy + Math.sin(a) * r);
                if (i == 0) hex.moveTo(x, y);
                else hex.lineTo(x, y);
            }
            hex.close();

            cv.drawPath(hex, strokePaint);
            cv.drawCircle(cx, cy, r * 0.34f, dotPaint);
        }
    }
}

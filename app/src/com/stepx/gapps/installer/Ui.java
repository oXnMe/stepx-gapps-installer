package com.stepx.gapps.installer;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 样式层：明暗两套主题 + 控件工厂。
 *
 * 全部代码构建，不依赖资源 XML —— 配合 app/build.sh 那条无 Gradle 的构建链。
 * 主题切换靠 {@link #T} 这个当前主题引用：Activity 在构建界面前赋值，
 * 之后所有半静态方法都按它取色。单个 Activity 的 app，这样比到处传参省事。
 */
final class Ui {

    static Theme T = Theme.light();

    private Ui() {}

    // ------------------------------- 主题 -------------------------------

    static final class Theme {
        final boolean dark;
        final int bg;
        final int card;
        final int cardAlt;
        final int line;
        final int text;
        final int sub;
        final int brand;
        final int brand2;
        final int ok;
        final int warn;
        final int bad;
        final int info;
        final int step;
        final int ripple;

        private Theme(boolean dark, int bg, int card, int cardAlt, int line, int text, int sub,
                      int brand, int brand2, int ok, int warn, int bad, int info, int step,
                      int ripple) {
            this.dark = dark;
            this.bg = bg;
            this.card = card;
            this.cardAlt = cardAlt;
            this.line = line;
            this.text = text;
            this.sub = sub;
            this.brand = brand;
            this.brand2 = brand2;
            this.ok = ok;
            this.warn = warn;
            this.bad = bad;
            this.info = info;
            this.step = step;
            this.ripple = ripple;
        }

        static Theme light() {
            return new Theme(false,
                    0xFFF2F4F7,   // bg
                    0xFFFFFFFF,   // card
                    0xFFF7F9FC,   // cardAlt
                    0xFFDDE2EA,   // line
                    0xFF0F1319,   // text
                    0xFF667085,   // sub
                    0xFF4F46E5,   // brand
                    0xFF7C3AED,   // brand2
                    0xFF15803D,   // ok
                    0xFFB45309,   // warn
                    0xFFDC2626,   // bad
                    0xFF2563EB,   // info
                    0xFF6D28D9,   // step
                    0x12000000);  // ripple
        }

        static Theme dark() {
            return new Theme(true,
                    0xFF07090D,
                    0xFF10141C,
                    0xFF151A24,
                    0xFF242B3A,
                    0xFFE6EAF2,
                    0xFF8792A6,
                    0xFF6D8BFF,
                    0xFF9B7CFF,
                    0xFF34D399,
                    0xFFF5A524,
                    0xFFF2555A,
                    0xFF60A5FA,
                    0xFFA78BFA,
                    0x24FFFFFF);
        }

        /** 语义色统一取口，日志和状态格共用一套。 */
        int tone(int level) {
            switch (level) {
                case Reporter.OK:   return ok;
                case Reporter.WARN: return warn;
                case Reporter.BAD:  return bad;
                case Reporter.STEP: return step;
                default:            return sub;
            }
        }
    }

    // ------------------------------ 基础度量 ------------------------------

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static GradientDrawable round(int color, int radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    static GradientDrawable outline(int color, int radiusPx, int strokePx, int strokeColor) {
        GradientDrawable g = round(color, radiusPx);
        g.setStroke(strokePx, strokeColor);
        return g;
    }

    /** 主色双色渐变，主按钮用。 */
    static GradientDrawable gradient(int from, int to, int radiusPx) {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, new int[]{from, to});
        g.setCornerRadius(radiusPx);
        return g;
    }

    static RippleDrawable ripple(GradientDrawable content, int rippleColor) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, null);
    }

    /**
     * 整屏背景：底色 + 24dp 细网格 + 顶部一团主色雾光。
     *
     * 科技感基本全靠这一层和「小号等宽大写标签」两件事，没有贴图、没有动画。
     * 网格透明度压得很低（浅色 6%、深色 8%），只在余光里能感觉到，
     * 调高一点立刻变成「设计稿背景素材」，反而土。
     */
    static Drawable backdrop(Context c) {
        return new Backdrop(c);
    }

    private static final class Backdrop extends Drawable {
        private final Paint base = new Paint();
        private final Paint grid = new Paint();
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float step;
        private final int gridColor;

        Backdrop(Context c) {
            step = dp(c, 24);
            base.setColor(T.bg);
            grid.setStrokeWidth(Math.max(1f, dp(c, 0.5f)));
            gridColor = T.dark ? 0x12FFFFFF : 0x0B1F2937;
            grid.setColor(gridColor);
        }

        @Override
        public void draw(Canvas cv) {
            Rect b = getBounds();
            cv.drawRect(b, base);

            for (float x = b.left; x <= b.right; x += step) {
                cv.drawLine(x, b.top, x, b.bottom, grid);
            }
            for (float y = b.top; y <= b.bottom; y += step) {
                cv.drawLine(b.left, y, b.right, y, grid);
            }

            // 主色雾光：从顶部中心往下淡出。走的是 brand 的 22% 透明度到全透明，
            // 半径给到整屏宽度，避免出现一个看得见边界的圆盘。
            int from = ((T.dark ? 0x38 : 0x1C) << 24) | (T.brand & 0x00FFFFFF);
            int to = T.brand & 0x00FFFFFF;
            glow.setShader(new RadialGradient(b.centerX(), b.top - b.height() * 0.15f,
                    b.width() * 0.95f, from, to, Shader.TileMode.CLAMP));
            cv.drawRect(b, glow);
        }

        @Override
        public void setAlpha(int alpha) {
            base.setAlpha(alpha);
            grid.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
            base.setColorFilter(cf);
            grid.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.OPAQUE;
        }
    }

    static Typeface medium() {
        return Typeface.create("sans-serif-medium", Typeface.NORMAL);
    }

    static Typeface mono(boolean bold) {
        return Typeface.create("monospace", bold ? Typeface.BOLD : Typeface.NORMAL);
    }

    // ------------------------------ 控件工厂 ------------------------------

    static TextView text(Context c, String s, float sp, int color, boolean medium) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (medium) t.setTypeface(medium());
        return t;
    }

    /** 小号等宽大写标签，状态格和区块标题都用它 —— 这是「科技感」的主要来源。 */
    static TextView label(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
        t.setTextColor(T.sub);
        t.setTypeface(mono(true));
        t.setLetterSpacing(0.09f);
        t.setSingleLine(true);
        return t;
    }

    static TextView mono(Context c, String s, float sp, int color, boolean bold) {
        TextView t = text(c, s, sp, color, false);
        t.setTypeface(mono(bold));
        return t;
    }

    static final int PRIMARY = 0;
    static final int GHOST = 1;

    static TextView button(Context c, String label, int kind) {
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, kind == PRIMARY ? 14.5f : 13);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(medium());
        t.setSingleLine(true);
        t.setClickable(true);
        t.setFocusable(true);
        int r = dp(c, 10);
        if (kind == PRIMARY) {
            t.setTextColor(0xFFFFFFFF);
            GradientDrawable g = gradient(T.brand, T.brand2, r);
            // 顶边一道 1dp 高光：深色下按钮才不像一块纯色色卡
            g.setStroke(Math.max(1, dp(c, 1)), 0x26FFFFFF);
            t.setBackground(ripple(g, 0x33FFFFFF));
        } else {
            t.setTextColor(T.text);
            t.setBackground(ripple(outline(T.cardAlt, r, dp(c, 1), T.line), T.ripple));
        }
        return t;
    }

    static void enabled(View v, boolean on) {
        v.setEnabled(on);
        v.setAlpha(on ? 1f : 0.35f);
    }

    static LinearLayout row(Context c, int gapPx, View... views) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < views.length; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            if (i > 0) p.setMarginStart(gapPx);
            r.addView(views[i], p);
        }
        return r;
    }

    static LinearLayout.LayoutParams lp(int heightPx, int topMarginPx) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, heightPx);
        p.topMargin = topMarginPx;
        return p;
    }
}

package com.ally.tvtube;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

public final class Ui {
    private static final SparseArray<Typeface> FONT_CACHE = new SparseArray<Typeface>();

    private Ui() {}

    public static Typeface font(Context c, int weight) {
        Typeface tf = FONT_CACHE.get(weight);
        if (tf != null) {
            return tf;
        }

        String path;
        switch (weight) {
            case 1:
                path = "fonts/BeVietnamPro-Medium.ttf";
                break;
            case 2:
                path = "fonts/BeVietnamPro-SemiBold.ttf";
                break;
            case 3:
                path = "fonts/BeVietnamPro-Bold.ttf";
                break;
            case 0:
            default:
                path = "fonts/BeVietnamPro-Regular.ttf";
                break;
        }

        try {
            tf = Typeface.createFromAsset(c.getAssets(), path);
        } catch (Exception e) {
            tf = Typeface.DEFAULT;
        }

        FONT_CACHE.put(weight, tf);
        return tf;
    }

    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static void applyFont(View root, Typeface tf) {
        if (root == null || tf == null) return;

        if (root instanceof TextView) {
            ((TextView) root).setTypeface(tf);
        } else if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyFont(group.getChildAt(i), tf);
            }
        }
    }

    public static GradientDrawable cardBg(int color, float radiusPx, int strokeWidthPx, int strokeColor) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(radiusPx);
        if (strokeWidthPx > 0) {
            gd.setStroke(strokeWidthPx, strokeColor);
        }
        return gd;
    }

    public static void focusScale(View v, boolean focused) {
        if (v == null) return;

        float targetScale = focused ? 1.06f : 1.0f;
        v.animate()
         .scaleX(targetScale)
         .scaleY(targetScale)
         .setDuration(120)
         .start();

        // API 19: không có elevation, viền sáng do drawable selector đảm nhiệm
    }
}

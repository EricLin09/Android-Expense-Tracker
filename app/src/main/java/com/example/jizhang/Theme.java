package com.example.jizhang;

import android.content.Context;

import androidx.appcompat.app.AppCompatDelegate;

/** 明暗主题：0=跟随系统 1=浅色 2=深色，存 prefs，App 启动时套用。 */
public class Theme {
    static final String PREFS = "jizhang_prefs";
    public static final String KEY = "theme_mode";

    public static final int FOLLOW = 0, LIGHT = 1, DARK = 2;

    public static int saved(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, FOLLOW);
    }

    /** 套用到全局（会触发已显示 Activity 重建）。 */
    public static void apply(int mode) {
        int night = mode == LIGHT ? AppCompatDelegate.MODE_NIGHT_NO
                : mode == DARK ? AppCompatDelegate.MODE_NIGHT_YES
                : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        AppCompatDelegate.setDefaultNightMode(night);
    }

    public static void applySaved(Context c) {
        apply(saved(c));
    }

    public static String label(int mode) {
        return mode == LIGHT ? "浅色" : mode == DARK ? "深色" : "跟随系统";
    }
}

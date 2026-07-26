package com.example.jizhang;

import android.app.Application;

/** 进程启动时套用用户选择的明暗主题。 */
public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Theme.applySaved(this);
    }
}

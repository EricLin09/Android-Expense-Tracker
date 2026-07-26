package com.example.jizhang;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;

/** 应用统一的底部面板：替代系统 AlertDialog，与整体圆角暖灰风格一致。 */
public class Sheets {

    /** 创建一个带圆角背景和顶部拖拽条的面板容器，show() 之前把内容 add 进去。 */
    public static LinearLayout container(Context ctx) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_sheet);
        int p = dp(ctx, 20);
        root.setPadding(p, dp(ctx, 10), p, dp(ctx, 20));

        // 顶部拖拽小横条
        View grabber = new View(ctx);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(ContextCompat.getColor(ctx, R.color.separator));
        gd.setCornerRadius(dp(ctx, 3));
        grabber.setBackground(gd);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 4));
        glp.gravity = Gravity.CENTER_HORIZONTAL;
        glp.bottomMargin = dp(ctx, 12);
        grabber.setLayoutParams(glp);
        root.addView(grabber);
        return root;
    }

    /** 弹出面板（去掉默认的白色方角背景） */
    public static BottomSheetDialog show(Context ctx, View content) {
        BottomSheetDialog d = new BottomSheetDialog(ctx);
        d.setContentView(content);
        View parent = (View) content.getParent();
        if (parent != null) parent.setBackgroundColor(Color.TRANSPARENT);
        d.show();
        return d;
    }

    /** 破坏性操作确认面板：消息 + 红色主按钮 + 取消 */
    public static void confirm(Context ctx, String message, String action, Runnable onConfirm) {
        LinearLayout root = container(ctx);

        TextView msg = new TextView(ctx);
        msg.setText(message);
        msg.setTextColor(ContextCompat.getColor(ctx, R.color.textPrimary));
        msg.setTextSize(16);
        msg.setGravity(Gravity.CENTER);
        msg.setPadding(0, dp(ctx, 10), 0, dp(ctx, 22));
        root.addView(msg);

        TextView btn = new TextView(ctx);
        btn.setText(action);
        btn.setTextColor(0xFFFFFFFF);
        btn.setTextSize(16);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ContextCompat.getColor(ctx, R.color.expense));
        bg.setCornerRadius(dp(ctx, 14));
        btn.setBackground(bg);
        btn.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 50)));
        root.addView(btn);

        TextView cancel = new TextView(ctx);
        cancel.setText("取消");
        cancel.setTextColor(ContextCompat.getColor(ctx, R.color.textSecondary));
        cancel.setTextSize(15);
        cancel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 46));
        clp.topMargin = dp(ctx, 6);
        cancel.setLayoutParams(clp);
        root.addView(cancel);

        BottomSheetDialog d = show(ctx, root);
        btn.setOnClickListener(v -> {
            d.dismiss();
            onConfirm.run();
        });
        cancel.setOnClickListener(v -> d.dismiss());
    }

    static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}

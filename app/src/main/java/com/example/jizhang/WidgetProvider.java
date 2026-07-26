package com.example.jizhang;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.TypedValue;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

/** 桌面小组件（2×2）：人民币 + 澳元本月收支总览，点 + 快速记账，点卡片打开 App。 */
public class WidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) {
            updateOne(context, mgr, id);
        }
    }

    /** 数据变化后主动刷新所有小组件 */
    public static void refresh(Context context) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        ComponentName cn = new ComponentName(context, WidgetProvider.class);
        int[] ids = mgr.getAppWidgetIds(cn);
        for (int id : ids) {
            updateOne(context, mgr, id);
        }
    }

    private static void updateOne(Context context, AppWidgetManager mgr, int widgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget);

        String ym = new SimpleDateFormat("yyyy-MM", Locale.CHINA).format(new java.util.Date());
        DbHelper db = new DbHelper(context);

        if (Flavor.COMPACT_WIDGET) {
            // 2×1：固定两行「今日支出 / 本月支出」，不跟随设置里的结余/总支出——
            // 这个尺寸装不下四项信息，语义固定反而更好读。
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new java.util.Date());
            views.setTextViewText(R.id.wToday, money("¥", db.monthTotals(today, "CNY")[0]));
            views.setTextViewText(R.id.wMonthExp, money("¥", db.monthTotals(ym, "CNY")[0]));
        } else {
            // 大数字显示结余还是总支出，跟随设置页「总览大数字」
            boolean be = MainActivity.bigExpense(context);
            // 顶行：月份，有汇率缓存时拼上「· 1A$≈¥4.73」
            String month = new SimpleDateFormat("yyyy-M", Locale.CHINA).format(new java.util.Date());
            String rate = Rates.label(context);
            views.setTextViewText(R.id.wMonth, rate == null ? month : month + " · " + rate);

            double[] cny = db.monthTotals(ym, "CNY");
            String cnyBig = be ? expenseText("¥", cny) : balanceText("¥", cny);
            views.setTextViewText(R.id.wCnyBalance, cnyBig);
            views.setTextViewTextSize(R.id.wCnyBalance, TypedValue.COMPLEX_UNIT_SP, bigSize(cnyBig));
            views.setTextColor(R.id.wCnyBalance, be ? 0xFFEFB4A8 : 0xFFFFFFFF);
            views.setTextViewText(R.id.wCnySub, subLine(be, cny));

            double[] aud = db.monthTotals(ym, "AUD");
            String audBig = be ? expenseText("A$", aud) : balanceText("A$", aud);
            views.setTextViewText(R.id.wAudBalance, audBig);
            views.setTextViewTextSize(R.id.wAudBalance, TypedValue.COMPLEX_UNIT_SP, bigSize(audBig));
            views.setTextColor(R.id.wAudBalance, be ? 0xFFEFB4A8 : 0xFFFFFFFF);
            views.setTextViewText(R.id.wAudSub, subLine(be, aud));
        }

        // 点卡片打开首页
        Intent openIntent = new Intent(context, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent openPi = PendingIntent.getActivity(
                context, 2, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.wRoot, openPi);

        mgr.updateAppWidget(widgetId, views);
    }

    /**
     * 大数字的字号：位数最多 6 位已经由 {@link #money} 保证，但结余模式会多一个负号，
     * "A$" 也比 "¥" 宽一格——真到最长的组合（-A$123456）时降一档字号，
     * 比截掉末位数字好。
     */
    private static float bigSize(String text) {
        int n = text.length();
        if (n <= 8) return 20f;      // ¥2088.57
        if (n <= 10) return 18f;     // -A$1817.35
        return 16f;                  // -A$123456 之类的极端情况
    }

    /**
     * 金额文案，自适应位数：整数位越多，自动减少小数位（四舍五入），保证放得下。
     * ≤4 位整数 → 2 位小数（¥9999.99）；5 位 → 1 位小数（¥12345.6）；
     * 6 位 → 无小数（¥123456）；≥7 位 → 用「万」（¥123.5万）。
     */
    private static String money(String sym, double v) {
        double a = Math.abs(v);
        if (a >= 1_000_000) {
            return sym + String.format(Locale.CHINA, "%.1f万", v / 10000.0);
        }
        int intDigits = a < 1 ? 1 : (int) Math.floor(Math.log10(a)) + 1;
        int decimals = intDigits >= 6 ? 0 : intDigits >= 5 ? 1 : 2;
        return sym + String.format(Locale.CHINA, "%." + decimals + "f", v);
    }

    /** 结余：收入-支出，负数带 - */
    private static String balanceText(String sym, double[] t) {
        double b = t[1] - t[0];
        return (b < 0 ? "-" : "") + money(sym, Math.abs(b));
    }

    /** 总支出（大数字模式二：设置里选「总支出」时用） */
    private static String expenseText(String sym, double[] t) {
        return money(sym, t[0]);
    }

    /**
     * 副行内容。大数字是支出时补上收入；大数字是结余时给出支出与收入的拆解。
     * 紧凑小组件（2×1）宽度只够一项，结余模式下只留支出。
     */
    private static CharSequence subLine(boolean bigIsExpense, double[] t) {
        return bigIsExpense ? incomeOnly("", t) : subText("", t);
    }

    /** 只显示支出的小字行（紧凑小组件的结余模式） */
    private static CharSequence expenseOnly(double[] t) {
        String exp = money("", t[0]);
        String s = "支 " + exp;
        android.text.SpannableString sp = new android.text.SpannableString(s);
        sp.setSpan(new android.text.style.ForegroundColorSpan(0xFFEFB4A8), 2, s.length(), 0);
        return sp;
    }

    /** 只显示收入的小字行（大数字已是支出时）。
     *  不带货币符号：上方大数字已经标了币种，副行再带一遍纯属浪费宽度。 */
    private static CharSequence incomeOnly(String sym, double[] t) {
        String inc = money("", t[1]);
        String s = "收 " + inc;
        android.text.SpannableString sp = new android.text.SpannableString(s);
        sp.setSpan(new android.text.style.ForegroundColorSpan(0xFFAECFB6), 2, s.length(), 0);
        return sp;
    }

    /** 支出/收入小字：金额分别着淡珊瑚/淡苔绿（Spannable 可通过 RemoteViews 传递）。
     *  用「支/收」短标签并保持单行；不带货币符号（上方大数字已标明），
     *  否则 "支 A$1817.35 · 收 A$459.41" 会超出 2×2 的半宽被截断。 */
    private static CharSequence subText(String sym, double[] t) {
        String exp = money("", t[0]);
        String inc = money("", t[1]);
        String s = "支 " + exp + " · 收 " + inc;
        android.text.SpannableString sp = new android.text.SpannableString(s);
        int expStart = 2;
        sp.setSpan(new android.text.style.ForegroundColorSpan(0xFFEFB4A8),
                expStart, expStart + exp.length(), 0);
        int incStart = s.length() - inc.length();
        sp.setSpan(new android.text.style.ForegroundColorSpan(0xFFAECFB6),
                incStart, s.length(), 0);
        return sp;
    }
}

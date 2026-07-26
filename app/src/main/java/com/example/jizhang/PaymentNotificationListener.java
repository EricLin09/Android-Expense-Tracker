package com.example.jizhang;

import android.app.Notification;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * 后台监听支付类通知，自动生成账目。
 * 需要用户在系统"通知使用权"里授权本 App。
 *
 * 这条路径上的所有处理（解析 + 商户别名表分类）都在本机完成，不发起任何网络请求。
 * 只有用户在设置页主动触发「批量补分类」时，才会把未命中的记录发到自建的本地模型
 * 服务（见 {@link LocalLlm}），且该服务在用户自己的局域网内，数据不出家门。
 */
public class PaymentNotificationListener extends NotificationListenerService {

    static final String PREFS = "jizhang_prefs";
    static final String KEY_ENABLED = "auto_enabled";          // 总开关
    static final String KEY_PKG_PREFIX = "auto_pkg_";          // 每个来源 App 的开关

    // 默认监听的支付 App
    static final String[] SUPPORTED_PKGS = {
            "com.eg.android.AlipayGphone",   // 支付宝
            "com.tencent.mm",                // 微信
            "com.unionpay",                  // 云闪付
    };

    private String lastKey = "";
    private long lastTime = 0;

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            handle(sbn);
        } catch (Exception ignored) {
            // 解析失败不影响系统通知
        }
    }

    private void handle(StatusBarNotification sbn) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_ENABLED, false)) return;

        String pkg = sbn.getPackageName();
        if (!isSupported(pkg)) return;
        if (!prefs.getBoolean(KEY_PKG_PREFIX + pkg, true)) return; // 该来源被用户关掉

        Notification n = sbn.getNotification();
        if (n == null) return;
        Bundle extras = n.extras;
        if (extras == null) return;

        String title = str(extras.getCharSequence(Notification.EXTRA_TITLE));
        String text = str(extras.getCharSequence(Notification.EXTRA_TEXT));
        String big = str(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        String body = big.isEmpty() ? text : big;

        // 去重：同一条通知短时间内可能多次回调
        String key = pkg + "|" + title + "|" + body;
        long now = System.currentTimeMillis();
        if (key.equals(lastKey) && now - lastTime < 8000) return;
        lastKey = key;
        lastTime = now;

        ingest(this, pkg, title, body);
    }

    /**
     * 把一条支付通知落成账目：解析 →（支出）查商户别名表 → 写库。
     *
     * 抽成静态方法，是为了让调试构建里的「模拟一条支付通知」能调用它：演示和真实
     * 通知之间不存在第二套逻辑，否则演示出来的行为就不代表线上行为。
     *
     * @return 落库的记录；解析失败返回 null
     */
    static Record ingest(Context ctx, String pkg, String title, String body) {
        Record r = PaymentParser.parse(pkg, title, body);
        if (r == null) return null;

        // 支出记录就地查商户别名表补分类：纯本地、不联网、微秒级，不会拖慢通知回调。
        // 没命中就保持「待分类」，之后可在设置页用局域网里的本地模型批量补。
        if (r.type == 0) {
            String cat = CategoryClassifier.byRules(ctx, title + "  " + body);
            if (cat != null) r.category = cat;
        }

        new DbHelper(ctx).insert(r);
        WidgetProvider.refresh(ctx);
        return r;
    }

    private boolean isSupported(String pkg) {
        for (String p : SUPPORTED_PKGS) if (p.equals(pkg)) return true;
        return false;
    }

    private String str(CharSequence cs) {
        return cs == null ? "" : cs.toString();
    }
}

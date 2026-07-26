package com.example.jizhang;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 澳元→人民币汇率（中间市场价，与谷歌显示的基本一致）。
 * 打开 App 时后台拉一次存本地缓存；小组件从缓存读，无网时沿用旧值。
 * 只请求一个汇率数字，不上传任何账目数据。
 */
public class Rates {

    static final String PREFS = "jizhang_prefs";
    static final String KEY_RATE = "rate_aud_cny";   // 1 澳元 = 多少人民币
    static final String KEY_TS = "rate_ts";          // 上次成功拉取时间戳
    private static final long THROTTLE_MS = 30 * 60 * 1000L;  // 30 分钟内不重复拉
    // 免费、无需 key，返回各币种对 AUD 的中间价
    private static final String API = "https://open.er-api.com/v6/latest/AUD";

    /** 缓存的汇率，没有返回 0 */
    public static double cached(Context ctx) {
        return Double.longBitsToDouble(
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .getLong(KEY_RATE, 0));
    }

    /** 小组件顶行用文案，如 "1A$≈¥4.73"，无缓存返回 null */
    public static String label(Context ctx) {
        double r = cached(ctx);
        return r > 0 ? String.format(java.util.Locale.CHINA, "1A$≈¥%.2f", r) : null;
    }

    /** 后台拉取；成功后刷新小组件。距上次不足 30 分钟则跳过。 */
    public static void fetchAsync(Context ctx) {
        final Context app = ctx.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long last = prefs.getLong(KEY_TS, 0);
        if (System.currentTimeMillis() - last < THROTTLE_MS && cached(app) > 0) return;

        new Thread(() -> {
            double r = fetch();
            if (r <= 0) return;   // 失败：静默，沿用旧缓存
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putLong(KEY_RATE, Double.doubleToLongBits(r))
                    .putLong(KEY_TS, System.currentTimeMillis())
                    .apply();
            WidgetProvider.refresh(app);
        }).start();
    }

    /** 拉一次，返回 1 澳元兑人民币；失败返回 0 */
    private static double fetch() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(API).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("Accept", "application/json");
            if (conn.getResponseCode() != 200) return 0;

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            JSONObject root = new JSONObject(sb.toString());
            if (!"success".equals(root.optString("result"))) return 0;
            double cny = root.getJSONObject("rates").optDouble("CNY", 0);
            return cny > 0 && cny < 100 ? cny : 0;   // 合理性校验
        } catch (Exception e) {
            return 0;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}

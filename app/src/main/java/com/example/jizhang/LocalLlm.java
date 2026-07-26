package com.example.jizhang;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 局域网内自建的 llama.cpp 服务客户端（跑在备用机上的 Qwen3-4B）。
 *
 * 只做一件事：给一段支付通知文本，返回一个分类名。金额、币种、收支类型仍由
 * {@link PaymentParser} 的正则解析——那部分正则比模型可靠，不要交给模型。
 *
 * 所有调用都是阻塞的，必须在后台线程执行。任何异常都返回 null，让调用方
 * 保留「待分类」，绝不因为服务不可用而写错数据。
 */
public class LocalLlm {

    private static final String PREFS = "jizhang_prefs";
    public  static final String KEY_ENABLED = "llm_enabled";
    public  static final String KEY_HOST    = "llm_host";      // 形如 192.168.1.50:8080
    public  static final String KEY_APIKEY  = "llm_apikey";    // 可空

    private static final int CONNECT_TIMEOUT = 3000;
    private static final int READ_TIMEOUT    = 30000;

    private LocalLlm() {}

    public static boolean isEnabled(Context ctx) {
        SharedPreferences p = prefs(ctx);
        return p.getBoolean(KEY_ENABLED, false) && !host(ctx).isEmpty();
    }

    public static String host(Context ctx) {
        return prefs(ctx).getString(KEY_HOST, "").trim();
    }

    public static void save(Context ctx, boolean enabled, String host, String apiKey) {
        prefs(ctx).edit()
                .putBoolean(KEY_ENABLED, enabled)
                .putString(KEY_HOST, host == null ? "" : host.trim())
                .putString(KEY_APIKEY, apiKey == null ? "" : apiKey.trim())
                .apply();
    }

    /** 服务是否可达。用于设置页的「测试连接」。 */
    public static boolean ping(Context ctx) {
        HttpURLConnection conn = null;
        try {
            conn = open(ctx, "/health", "GET");
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 让模型判断分类。
     * @param text     通知全文
     * @param allowed  允许的分类名（预设 + 用户自定义），模型只能从中选
     * @return 分类名；服务不可用、超时或返回值不在 allowed 内时返回 null
     */
    public static String classify(Context ctx, String text, String[] allowed) {
        if (text == null || text.isEmpty() || allowed == null || allowed.length == 0) return null;
        if (!isEnabled(ctx)) return null;

        HttpURLConnection conn = null;
        try {
            StringBuilder cats = new StringBuilder();
            for (int i = 0; i < allowed.length; i++) {
                if (i > 0) cats.append('、');
                cats.append(allowed[i]);
            }

            String prompt = "你是记账助手。判断这条支付通知属于哪个消费分类。\n"
                    + "只能从这些分类里选一个：" + cats + "\n"
                    + "只输出分类名，不要输出任何其他内容。\n\n通知：" + text;

            JSONObject msg = new JSONObject();
            msg.put("role", "user");
            msg.put("content", prompt);

            JSONObject body = new JSONObject();
            body.put("messages", new JSONArray().put(msg));
            body.put("temperature", 0);
            body.put("max_tokens", 8);      // 只需要一个分类名

            conn = open(ctx, "/v1/chat/completions", "POST");
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (conn.getResponseCode() != 200) return null;

            String raw = readAll(conn);
            String content = new JSONObject(raw)
                    .getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim();

            // 模型偶尔会多说几个字，取第一个能对上的分类
            for (String c : allowed) {
                if (content.contains(c)) return c;
            }
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static HttpURLConnection open(Context ctx, String path, String method) throws Exception {
        String h = host(ctx);
        if (!h.startsWith("http://") && !h.startsWith("https://")) h = "http://" + h;
        HttpURLConnection conn = (HttpURLConnection) new URL(h + path).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setRequestProperty("Content-Type", "application/json");
        String key = prefs(ctx).getString(KEY_APIKEY, "");
        if (!key.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + key);
        return conn;
    }

    private static String readAll(HttpURLConnection conn) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}

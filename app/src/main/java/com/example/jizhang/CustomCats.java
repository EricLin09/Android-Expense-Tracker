package com.example.jizhang;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 自定义分类：存在 SharedPreferences 里（JSON）。
 * 每个类型（支出/收入）最多 5 个自定义分类——加上 6 个预设 + 「其他」正好 12 个。
 * 图标只提供固定的 5×2 套「风格图标」，存 iconKey 字符串（不存资源 id，避免升级后错位）。
 */
public class CustomCats {

    private static final String PREFS = "jizhang_prefs";
    private static final String KEY = "custom_cats";   // JSON array of {name,type,icon}

    public static final int MAX_PER_TYPE = 5;

    /** 一个自定义分类 */
    public static class Cat {
        public String name;
        public int type;        // 0=支出 1=收入
        public String iconKey;
        Cat(String name, int type, String iconKey) {
            this.name = name; this.type = type; this.iconKey = iconKey;
        }
    }

    // iconKey -> (drawable, 颜色)。支出用暖/金属色，收入用绿/金/青色，风格与预设一致。
    private static final Map<String, Integer> ICON_RES = new HashMap<>();
    private static final Map<String, Integer> ICON_COLOR = new HashMap<>();
    // 每个类型可选的图标顺序
    public static final String[] EXPENSE_ICONS = {"e_medical", "e_travel", "e_pet", "e_digital", "e_sport"};
    public static final String[] INCOME_ICONS = {"i_finance", "i_receipt", "i_interest", "i_giftmoney", "i_refund"};

    static {
        reg("e_medical",  R.drawable.ic_cc_medical,   0xFFA8625B); // 铁锈红
        reg("e_travel",   R.drawable.ic_cc_travel,    0xFF5E7A99); // 钢青
        reg("e_pet",      R.drawable.ic_cc_pet,       0xFFB07A52); // 赤铜
        reg("e_digital",  R.drawable.ic_cc_digital,   0xFF8E7C9E); // 紫铜灰
        reg("e_sport",    R.drawable.ic_cc_sport,     0xFF6C8F9B); // 锡青
        reg("i_finance",  R.drawable.ic_cc_finance,   0xFF6F9271); // 苔银
        reg("i_receipt",  R.drawable.ic_cc_receipt,   0xFF6B94A0); // 青钛
        reg("i_interest", R.drawable.ic_cc_interest,  0xFFB08D4F); // 黄铜
        reg("i_giftmoney",R.drawable.ic_cc_giftmoney, 0xFFAD6660); // 朱砂灰
        reg("i_refund",   R.drawable.ic_cc_refund,    0xFF9C7E5A); // 青铜
    }

    private static void reg(String key, int res, int color) {
        ICON_RES.put(key, res);
        ICON_COLOR.put(key, color);
    }

    public static int iconRes(String key) {
        Integer r = ICON_RES.get(key);
        return r != null ? r : R.drawable.ic_cat_other;
    }

    public static int iconColor(String key) {
        Integer c = ICON_COLOR.get(key);
        return c != null ? c : 0xFF8A8A8E;
    }

    public static String[] iconKeysFor(int type) {
        return type == 0 ? EXPENSE_ICONS : INCOME_ICONS;
    }

    /** 读取某类型的全部自定义分类（按加入顺序） */
    public static List<Cat> load(Context ctx, int type) {
        List<Cat> out = new ArrayList<>();
        for (Cat c : loadAll(ctx)) {
            if (c.type == type) out.add(c);
        }
        return out;
    }

    private static List<Cat> loadAll(Context ctx) {
        List<Cat> out = new ArrayList<>();
        String raw = prefs(ctx).getString(KEY, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Cat(o.getString("name"), o.getInt("type"), o.getString("icon")));
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static void saveAll(Context ctx, List<Cat> list) {
        JSONArray arr = new JSONArray();
        try {
            for (Cat c : list) {
                JSONObject o = new JSONObject();
                o.put("name", c.name);
                o.put("type", c.type);
                o.put("icon", c.iconKey);
                arr.put(o);
            }
        } catch (Exception ignored) {}
        prefs(ctx).edit().putString(KEY, arr.toString()).apply();
    }

    /** 新增；返回 null 成功，否则返回错误提示。 */
    public static String add(Context ctx, String name, int type, String iconKey) {
        name = name == null ? "" : name.trim();
        if (name.isEmpty()) return "请输入分类名称";
        if (name.length() > 4) return "名称最多 4 个字";
        if (isPreset(name)) return "已有同名分类";
        List<Cat> all = loadAll(ctx);
        int count = 0;
        for (Cat c : all) {
            if (c.type == type) {
                count++;
                if (c.name.equals(name)) return "已有同名分类";
            }
        }
        if (count >= MAX_PER_TYPE) return "自定义分类最多 " + MAX_PER_TYPE + " 个";
        all.add(new Cat(name, type, iconKey));
        saveAll(ctx, all);
        MerchantRules.invalidate();   // 指向该分类的商户规则要重新生效
        return null;
    }

    public static void remove(Context ctx, String name, int type) {
        List<Cat> all = loadAll(ctx);
        List<Cat> keep = new ArrayList<>();
        for (Cat c : all) {
            if (!(c.type == type && c.name.equals(name))) keep.add(c);
        }
        saveAll(ctx, keep);
        MerchantRules.invalidate();   // 指向该分类的商户规则要停止生效
    }

    private static boolean isPreset(String name) {
        for (String s : Categories.EXPENSE) if (s.equals(name)) return true;
        for (String s : Categories.INCOME) if (s.equals(name)) return true;
        return "待分类".equals(name);
    }

    /** 把所有自定义分类的颜色/图标注册进 CatStyle，供首页列表、统计页正确显示。 */
    public static void applyToCatStyle(Context ctx) {
        for (Cat c : loadAll(ctx)) {
            CatStyle.registerRuntime(c.name, iconColor(c.iconKey), iconRes(c.iconKey));
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}

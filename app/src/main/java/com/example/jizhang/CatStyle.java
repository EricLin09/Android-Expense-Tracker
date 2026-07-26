package com.example.jizhang;

import android.graphics.drawable.GradientDrawable;

import java.util.HashMap;
import java.util.Map;

/**
 * 分类的视觉样式：每个分类固定一个低饱和金属色 + 一个线性图标。
 * 图标以 tint 方式着色，圆底 = 同色淡化版（约 14% 透明度）+ 1dp 同色细描边。
 */
public class CatStyle {

    private static final int GRAY = 0xFF8A8A8E;

    private static final Map<String, Integer> COLOR = new HashMap<>();
    private static final Map<String, Integer> ICON = new HashMap<>();

    static {
        // 支出
        put("餐饮", 0xFFB07A52, R.drawable.ic_cat_food);      // 赤铜
        put("交通", 0xFF5E7A99, R.drawable.ic_cat_transport); // 钢青
        put("购物", 0xFFB76E79, R.drawable.ic_cat_shopping);  // 玫瑰金
        put("娱乐", 0xFF8E7C9E, R.drawable.ic_cat_fun);       // 紫铜灰
        put("居住", 0xFF6C8F9B, R.drawable.ic_cat_home);      // 锡青
        put("医疗", 0xFFA8625B, R.drawable.ic_cat_medical);   // 铁锈红
        put("教育", 0xFF6E71A3, R.drawable.ic_cat_edu);       // 靛青灰
        put("通讯", 0xFF5F958B, R.drawable.ic_cat_comm);      // 铜绿
        put("人情", 0xFFB57078, R.drawable.ic_cat_gift);      // 珊瑚灰
        // 收入
        put("工资", 0xFF6F9271, R.drawable.ic_cat_salary);    // 苔银
        put("奖金", 0xFFB08D4F, R.drawable.ic_cat_bonus);     // 黄铜
        put("兼职", 0xFF6B94A0, R.drawable.ic_cat_clock);     // 青钛
        put("投资", 0xFF9C7E5A, R.drawable.ic_cat_invest);    // 青铜
        put("红包", 0xFFAD6660, R.drawable.ic_cat_gift);      // 朱砂灰
        put("转账", 0xFF6B94A0, R.drawable.ic_cat_transfer);  // 青钛
        // 通用 / 自动记账占位
        put("其他", GRAY, R.drawable.ic_cat_other);
        put("待分类", GRAY, R.drawable.ic_cat_other);
    }

    private static void put(String name, int color, int icon) {
        COLOR.put(name, color);
        ICON.put(name, icon);
    }

    /** 运行时注册自定义分类的颜色/图标（由 CustomCats 从 prefs 载入时调用）。 */
    public static void registerRuntime(String name, int color, int icon) {
        if (name == null) return;
        COLOR.put(name, color);
        ICON.put(name, icon);
    }

    /** 分类主色（不透明），用于图标 tint、饼图扇区、色点。 */
    public static int color(String category) {
        Integer c = category == null ? null : COLOR.get(category);
        return c != null ? c : GRAY;
    }

    /** 分类图标资源 id。 */
    public static int icon(String category) {
        Integer i = category == null ? null : ICON.get(category);
        return i != null ? i : R.drawable.ic_cat_other;
    }

    /** 圆底用的淡色（主色 alpha≈14%），用于图标背景。 */
    public static int softColor(String category) {
        int c = color(category);
        return (0x24 << 24) | (c & 0x00FFFFFF);
    }

    /**
     * 图表专用提亮色：同色相、明度+18%、饱和度+15%。
     * 金属色做小图标高级，但大面积铺饼图会浑浊，图表用这套。
     */
    public static int chartColor(String category) {
        int c = color(category);
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(c, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.15f);
        hsv[2] = Math.min(1f, hsv[2] * 1.18f);
        return android.graphics.Color.HSVToColor(hsv);
    }

    /** 颜色向黑压暗（factor<1），用于扇区内缘 */
    public static int darken(int c, float factor) {
        int r = (int) (((c >> 16) & 0xFF) * factor);
        int g = (int) (((c >> 8) & 0xFF) * factor);
        int b = (int) ((c & 0xFF) * factor);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** 颜色向白提亮（amount 0~1），用于扇区外缘高光 */
    public static int lighten(int c, float amount) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        r = (int) (r + (255 - r) * amount);
        g = (int) (g + (255 - g) * amount);
        b = (int) (b + (255 - b) * amount);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /**
     * 图标圆底：selected=false 为淡色底 + 1dp 同色 22% 描边；selected=true 为主色实底。
     * density 传 getResources().getDisplayMetrics().density。
     */
    public static GradientDrawable circleBg(String category, float density, boolean selected) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        int c = color(category);
        if (selected) {
            d.setColor(c);
        } else {
            d.setColor(softColor(category));
            d.setStroke(Math.max(1, Math.round(density)), (0x38 << 24) | (c & 0x00FFFFFF));
        }
        return d;
    }
}

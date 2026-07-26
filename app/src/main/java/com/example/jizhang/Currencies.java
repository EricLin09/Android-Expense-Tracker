package com.example.jizhang;

import java.util.LinkedHashMap;
import java.util.Map;

/** 支持的货币及其符号 */
public class Currencies {

    // 代码 -> 符号，顺序即选择器里的顺序
    public static final LinkedHashMap<String, String> SYMBOLS = new LinkedHashMap<>();
    static {
        SYMBOLS.put("CNY", "¥");   // 人民币
        SYMBOLS.put("AUD", "A$");  // 澳元
    }

    /** 货币中文名，用于总览分块标题 */
    public static String name(String code) {
        if ("CNY".equals(code)) return "人民币";
        if ("AUD".equals(code)) return "澳元";
        return code;
    }

    public static final String DEFAULT = "CNY";

    public static String symbol(String code) {
        String s = SYMBOLS.get(code);
        return s != null ? s : code;
    }

    /**
     * 可选的货币。国内版只给人民币——一处收敛，记一笔的货币选择器、首页与统计页的
     * 货币菜单会同时变成单选项。
     *
     * 注意 {@link #symbol} 仍然认识 AUD：从双币版导过来的历史记录要能正确显示，
     * 只是不能再新建澳元记录。
     */
    public static String[] codes() {
        if (!Flavor.DUAL_CURRENCY) return new String[]{ DEFAULT };
        return SYMBOLS.keySet().toArray(new String[0]);
    }

    /** 供选择器显示，例如 "CNY  ¥" */
    public static String[] labels() {
        String[] codes = codes();
        String[] out = new String[codes.length];
        for (int i = 0; i < codes.length; i++) {
            out[i] = codes[i] + "  " + symbol(codes[i]);
        }
        return out;
    }

    public static int indexOf(String code) {
        String[] codes = codes();
        for (int i = 0; i < codes.length; i++) {
            if (codes[i].equals(code)) return i;
        }
        return 0;
    }
}

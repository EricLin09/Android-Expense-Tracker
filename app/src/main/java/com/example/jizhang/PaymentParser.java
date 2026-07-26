package com.example.jizhang;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把一条支付类通知的文字解析成一条待记录的账目。
 * 解析失败返回 null。解析成功的记录 source=1（自动），分类默认"待分类"，方便用户核对。
 */
public class PaymentParser {

    // 支出强关键词：命中则一定是支出（即使文中出现“收款方”等字样也不误判为收入）
    private static final String[] EXPENSE_WORDS = {
            "付款", "支付", "消费", "扣款", "已付", "转出", "支出", "paid", "payment"
    };
    // 收入关键词（在没有支出强关键词时，命中则记为收入）
    private static final String[] INCOME_WORDS = {
            "收到", "到账", "入账", "退款", "红包", "转入", "成功收款", "收款成功", "收入"
    };
    // 需要至少命中一个支付关键词，才认为这是一条账单通知（降低误判聊天消息）
    private static final String[] PAY_WORDS = {
            "支付", "付款", "消费", "支出", "扣款", "交易", "收款", "到账",
            "入账", "退款", "转账", "转入", "转出", "已用", "paid", "payment"
    };

    // 金额：匹配 ¥12.50 / 12.50元 / A$12.50 / $12.50 / 12.50 等
    private static final Pattern AMOUNT = Pattern.compile(
            "(?:¥|￥|A\\$|US\\$|HK\\$|\\$|€|£|人民币|澳元|美元|欧元|港币|英镑|RMB|AUD|USD|EUR|HKD|GBP)?\\s*" +
                    "([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?)\\s*(?:元)?");

    /** 解析结果 */
    public static Record parse(String pkg, String title, String text) {
        String content = ((title == null ? "" : title) + "  " + (text == null ? "" : text)).trim();
        if (content.isEmpty()) return null;

        // 必须像一条账单
        if (!containsAny(content, PAY_WORDS)) return null;

        double amount = extractAmount(content);
        if (amount <= 0) return null;

        Record r = new Record();
        // 支出强关键词优先；否则命中收入词才算收入；都没有则默认支出
        boolean isIncome = !containsAny(content, EXPENSE_WORDS) && containsAny(content, INCOME_WORDS);
        r.type = isIncome ? 1 : 0;
        r.amount = amount;
        r.currency = detectCurrency(content);
        r.category = "待分类";
        r.note = "自动记账 · " + appName(pkg) + (title != null && !title.isEmpty() ? " · " + title : "");
        r.date = today();
        r.source = 1;
        return r;
    }

    // 这些词后面跟的数字不是本次交易金额（如“余额1,234.56元”），但它确实是个钱数，
    // 所以降级为兜底：全文再没有别的候选时还能用
    private static final String[] NOT_AMOUNT_HINTS = {
            "余额", "结余", "积分", "话费", "优惠", "立减", "已减", "折", "红包剩", "额度"
    };

    // 这些词后面跟的是账号/单号，任何情况下都不是钱数，直接丢弃、连兜底都不做。
    // 必须和上面那组分开：银行通知里「尾号1234」几乎总在金额之前出现，若只降级为兜底，
    // 遇到「工商银行 尾号1234 消费25元」这种整数金额时，25 不带小数点顶不掉 1234，
    // 就会把卡号记成金额。
    private static final String[] NEVER_AMOUNT_HINTS = {
            "尾号", "卡号", "账号", "帐号", "订单号", "流水号", "交易号", "商户号", "编号", "期数"
    };

    private static double extractAmount(String s) {
        Matcher m = AMOUNT.matcher(s);
        double best = 0, fallback = 0;
        // 取文本中最“像金额”的一个：优先带小数点的、数值合理的；
        // 前面紧跟“余额/积分”等提示词的数字降级为兜底，跟“尾号/单号”的直接丢弃
        while (m.find()) {
            String num = m.group(1).replace(",", "");
            try {
                double v = Double.parseDouble(num);
                if (v <= 0 || v > 10_000_000) continue;
                boolean hasDot = num.contains(".");
                if (isHinted(s, m.start(), NEVER_AMOUNT_HINTS)) continue;
                if (isHinted(s, m.start(), NOT_AMOUNT_HINTS)) {
                    if (fallback == 0) fallback = v;
                    continue;
                }
                if (best == 0 || (hasDot && best == Math.floor(best))) {
                    best = v;
                }
            } catch (NumberFormatException ignored) {}
        }
        return best > 0 ? best : fallback;
    }

    /** 数字（含其货币前缀）前 6 个字符内出现了 hints 里的提示词 */
    private static boolean isHinted(String s, int matchStart, String[] hints) {
        int from = Math.max(0, matchStart - 6);
        String before = s.substring(from, matchStart);
        for (String w : hints) {
            if (before.contains(w)) return true;
        }
        return false;
    }

    private static String detectCurrency(String s) {
        if (contains(s, "A$") || contains(s, "AUD") || contains(s, "澳元")) return "AUD";
        if (contains(s, "US$") || contains(s, "USD") || contains(s, "美元")) return "USD";
        if (contains(s, "HK$") || contains(s, "HKD") || contains(s, "港币")) return "HKD";
        if (contains(s, "€") || contains(s, "EUR") || contains(s, "欧元")) return "EUR";
        if (contains(s, "£") || contains(s, "GBP") || contains(s, "英镑")) return "GBP";
        if (contains(s, "¥") || contains(s, "￥") || contains(s, "元")
                || contains(s, "人民币") || contains(s, "RMB")) return "CNY";
        // 只有一个孤立的 $：用户在澳洲，默认澳元（记录可手动改）
        if (contains(s, "$")) return "AUD";
        return Currencies.DEFAULT;
    }

    private static String appName(String pkg) {
        if (pkg == null) return "支付";
        if (pkg.contains("alipay")) return "支付宝";
        if (pkg.contains("tencent.mm")) return "微信";
        if (pkg.contains("unionpay")) return "云闪付";
        return "支付";
    }

    private static boolean containsAny(String s, String[] words) {
        for (String w : words) if (contains(s, w)) return true;
        return false;
    }

    private static boolean contains(String s, String sub) {
        return s.toLowerCase().contains(sub.toLowerCase());
    }

    private static String today() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
                .format(new java.util.Date());
    }
}

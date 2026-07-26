package com.example.jizhang;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 商户别名表：把通知文本里的商户名直接映射到分类，命中就不必调用本地模型。
 *
 * 两级表，格式都是「关键词 TAB 分类」：
 *  - **内置表** `assets/merchant_rules.tsv`——只收录全国性连锁与通用服务词，
 *    随应用分发，可公开；
 *  - **个人商户表** `filesDir/merchant_rules_user.tsv`——用户自己常去的单店、
 *    本地小店。存在 App 私有目录，**不进代码仓库、不随应用分发**，因为一串
 *    具体小店名足以反推出居住地和活动范围。设置页可导入。
 *
 * 匹配时**个人表优先**，因此可以用它覆盖内置规则。两张表内部各自按关键词长度
 * 降序排列，更具体的词天然先命中——"UBER EATS" 会在 "UBER" 之前被检查。
 *
 * 只认当前存在的分类（预设 + 用户自定义）；指向不存在分类的行会被忽略。
 */
public class MerchantRules {

    private static final String ASSET = "merchant_rules.tsv";
    /** 个人商户表在 App 私有目录里的文件名 */
    public  static final String USER_FILE = "merchant_rules_user.tsv";

    private static volatile List<Rule> rules;       // 内置表，懒加载
    private static volatile List<Rule> userRules;   // 个人表，懒加载

    private static class Rule {
        final String keyword;    // 已转小写，匹配时直接比
        final String category;
        final boolean latin;     // 纯 ASCII 关键词需要词边界，中文关键词不需要
        Rule(String keyword, String category) {
            this.keyword = keyword;
            this.category = category;
            this.latin = isAscii(keyword);
        }
    }

    /**
     * 按商户名猜分类。
     * @return 命中的分类；没命中返回 null（交给本地模型或留作"待分类"）
     */
    public static String match(Context ctx, String text) {
        if (text == null || text.isEmpty()) return null;
        String hay = normalize(text);
        for (Rule r : loadUser(ctx)) {      // 个人表优先，可覆盖内置规则
            if (hit(hay, r)) return r.category;
        }
        for (Rule r : load(ctx)) {
            if (hit(hay, r)) return r.category;
        }
        return null;
    }

    /**
     * 转小写，并把 '*' 当作分隔符、压缩连续空白。
     *
     * 银行流水里普遍用星号分隔商户前缀（如 "UBER *EATS"、"SQ *SOME SHOP"）。
     * 不归一化的话 "UBER *EATS" 匹配不上规则 "UBER EATS"，会退到更短的 "UBER" 上
     * 被误判成交通——这是在真实流水里抓到的。
     */
    private static String normalize(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        boolean lastSpace = false;
        for (int i = 0; i < text.length(); i++) {
            char c = Character.toLowerCase(text.charAt(i));
            if (c == '*' || Character.isWhitespace(c)) {
                if (!lastSpace && sb.length() > 0) sb.append(' ');
                lastSpace = true;
            } else {
                sb.append(c);
                lastSpace = false;
            }
        }
        return sb.toString().trim();
    }

    /**
     * 拉丁关键词必须落在词边界上，否则 "UTS" 会命中 "DONUTS"、"RENT" 会命中 "CURRENT"。
     * 中文没有词边界概念，仍用子串匹配。
     */
    private static boolean hit(String hay, Rule r) {
        if (!r.latin) return hay.contains(r.keyword);
        int from = 0;
        while (from <= hay.length() - r.keyword.length()) {
            int i = hay.indexOf(r.keyword, from);
            if (i < 0) return false;
            int end = i + r.keyword.length();
            boolean leftOk = (i == 0) || !isWordChar(hay.charAt(i - 1));
            if (leftOk && rightBoundary(hay, end, r.keyword.length())) return true;
            from = i + 1;
        }
        return false;
    }

    /** 长度达到这个值的拉丁关键词足够独特，不再强制右边界。 */
    private static final int LOOSE_RIGHT_MIN_LEN = 6;

    /**
     * 关键词右侧是否构成边界。
     *
     * 三档：
     *  1. 下一个字符本来就不是词字符 —— 直接通过；
     *  2. 品牌名的复数/所有格 —— MCDONALD 要命中 MCDONALDS、DAN MURPHY 要命中
     *     DAN MURPHYS（撇号不是词字符，DAN MURPHY'S 走第 1 档）；
     *  3. 关键词长度 >= {@link #LOOSE_RIGHT_MIN_LEN} 时放宽 —— 银行流水里商户名常常
     *     连写不带分隔（CommBank 的 "GusmanYGomezWestfiel"），而这么长的词几乎不会
     *     偶然成为别的词的前缀。短词仍然严格：正是 SHELL 和 GYM 这类短词会误命中
     *     SHELLEY、GYMEA。
     */
    private static boolean rightBoundary(String hay, int end, int keywordLen) {
        if (end >= hay.length()) return true;
        if (!isWordChar(hay.charAt(end))) return true;
        if (keywordLen >= LOOSE_RIGHT_MIN_LEN) return true;
        if (hay.charAt(end) != 's') return false;
        int p = end + 1;
        return p >= hay.length() || !isWordChar(hay.charAt(p));
    }

    private static boolean isWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) return false;
        }
        return true;
    }

    /** 内置表条数，供设置页显示。 */
    public static int size(Context ctx) {
        return load(ctx).size();
    }

    /** 个人商户表条数；没导入过就是 0。 */
    public static int userSize(Context ctx) {
        return loadUser(ctx).size();
    }

    /** 个人商户表文件（可能不存在）。 */
    public static File userFile(Context ctx) {
        return new File(ctx.getFilesDir(), USER_FILE);
    }

    /**
     * 用输入流覆盖个人商户表。
     * @return 成功解析的规则条数；失败抛异常由调用方提示
     */
    public static int importUser(Context ctx, InputStream in) throws IOException {
        File f = userFile(ctx);
        try (FileOutputStream out = new FileOutputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        synchronized (MerchantRules.class) { userRules = null; }
        return userSize(ctx);
    }

    /**
     * 往个人商户表追加一条规则。用户在编辑页改完分类后「以后都归到 X」走这里。
     * @return true 表示写入成功；关键词为空或已存在完全相同的行时返回 false
     */
    public static boolean appendUser(Context ctx, String keyword, String category) {
        if (keyword == null || category == null) return false;
        String kw = keyword.trim();
        if (kw.isEmpty() || kw.contains("\t")) return false;

        String line = kw + "\t" + category;
        File f = userFile(ctx);
        try {
            if (f.exists()) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(
                        new FileInputStream(f), StandardCharsets.UTF_8))) {
                    String l;
                    while ((l = br.readLine()) != null) {
                        if (l.trim().equalsIgnoreCase(line)) return false;   // 已有同样的规则
                    }
                }
            }
            boolean needNewline = f.exists() && f.length() > 0;
            try (FileOutputStream out = new FileOutputStream(f, true)) {
                StringBuilder sb = new StringBuilder();
                if (needNewline) sb.append('\n');
                sb.append(line).append('\n');
                out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            return false;
        }
        synchronized (MerchantRules.class) { userRules = null; }
        return true;
    }

    /**
     * 从备注里猜一个商户关键词，供「以后都归到 X」预填。
     *
     * 只是个起点，用户可以改——猜错的代价是往表里写一条没用的规则，所以宁可保守：
     * 去掉自动记账前缀和银行流水的固定噪音，取最长的一段字母/中文，太短就返回空。
     */
    public static String guessKeyword(String note) {
        if (note == null) return "";
        String s = note;
        int sep = s.lastIndexOf(" · ");           // "自动记账 · 支付宝 · 付款给星巴克"
        if (sep >= 0) s = s.substring(sep + 3);
        s = s.replace('*', ' ')                    // "UBER *EATS" 的星号是分隔符，不能当断点
             .replaceAll("(?i)Card xx\\d+|Value Date:\\s*\\d{2}/\\d{2}/\\d{4}", " ")
             .replaceAll("付款给|收款方[:：]?|商户全称[:：]?", " ")
             .replaceAll("\\s{2,}", " ")
             .trim();

        // 中文商户名没有空格，取最长的一段汉字即可
        if (s.matches(".*[\\u4e00-\\u9fa5].*")) {
            String best = "";
            for (String part : s.split("[^\\u4e00-\\u9fa5]+")) {
                if (part.length() > best.length()) best = part;
            }
            return best.length() >= 2 ? best : "";
        }

        // 英文银行描述里商户名几乎总在最前面，后面是门店号、郊区、州名、国别。按词取前缀，
        // 遇到含数字的词、公司后缀/地区码就停。
        //
        // 只取两个词是有意的：关键词只需要能「匹配上」，不需要完整——"TIAN SHUN" 照样命中
        // "TIAN SHUN HE PTY LTD"，而更短意味着能覆盖更多写法变体。短拉丁词有词边界保护，
        // 不会误伤。
        String[] tokens = s.split("\\s+");
        StringBuilder out = new StringBuilder();
        int taken = 0;
        for (String t : tokens) {
            if (t.isEmpty()) continue;
            boolean noise = t.matches(".*\\d.*")
                    || t.matches("(?i)PTY|LTD|INC|AU|AUS|USA|NS|NSW|VIC|QLD|WA|SA|TAS|ACT|CA");
            if (noise) break;
            out.append(taken > 0 ? " " : "").append(t);
            taken++;
            // 首词形如域名（APPLE.COM/BILL）时它本身就是完整商户名，后面的都是地名
            if (t.contains(".") || t.contains("/")) break;
            if (taken >= 2) break;
        }
        String best = out.toString().trim();
        if (best.length() > 24) {                     // 截断退回到词边界，不切在词中间
            best = best.substring(0, 24);
            int sp = best.lastIndexOf(' ');
            if (sp > 0) best = best.substring(0, sp);
        }
        return best.length() >= 2 ? best.trim() : "";
    }

    /** 删除个人商户表。 */
    public static void clearUser(Context ctx) {
        File f = userFile(ctx);
        if (f.exists() && !f.delete()) f.deleteOnExit();
        synchronized (MerchantRules.class) { userRules = null; }
    }

    private static List<Rule> load(Context ctx) {
        List<Rule> local = rules;
        if (local != null) return local;
        synchronized (MerchantRules.class) {
            if (rules != null) return rules;
            rules = parse(ctx);
            return rules;
        }
    }

    private static List<Rule> loadUser(Context ctx) {
        List<Rule> local = userRules;
        if (local != null) return local;
        synchronized (MerchantRules.class) {
            if (userRules != null) return userRules;
            userRules = parseUser(ctx);
            return userRules;
        }
    }

    /** 用户增删自定义分类后必须调用：指向该分类的规则要重新参与匹配。 */
    public static void invalidate() {
        synchronized (MerchantRules.class) {
            rules = null;
            userRules = null;
        }
    }

    private static List<Rule> parse(Context ctx) {
        try {
            return readRules(ctx, ctx.getAssets().open(ASSET));
        } catch (Exception e) {
            // 读不到表就当没有规则，全部走模型/待分类，不影响记账主流程
            return new ArrayList<>();
        }
    }

    private static List<Rule> parseUser(Context ctx) {
        File f = userFile(ctx);
        if (!f.exists()) return new ArrayList<>();
        try (FileInputStream in = new FileInputStream(f)) {
            return readRules(ctx, in);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** 解析「关键词 TAB 分类」，跳过注释与空行，按关键词长度降序排列。 */
    private static List<Rule> readRules(Context ctx, InputStream in) throws IOException {
        List<Rule> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("﻿")) line = line.substring(1);
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                String kw = line.substring(0, tab).trim().toLowerCase();
                String cat = line.substring(tab + 1).trim();
                if (kw.isEmpty() || !isKnownCategory(ctx, cat)) continue;
                out.add(new Rule(kw, cat));
            }
        }
        // 长关键词优先：保证 "UBER EATS" 先于 "UBER" 命中
        Collections.sort(out, new Comparator<Rule>() {
            @Override public int compare(Rule a, Rule b) {
                return b.keyword.length() - a.keyword.length();
            }
        });
        return out;
    }

    /**
     * 预设分类，或用户当前已建的自定义支出分类。
     *
     * 允许表里写自定义分类（如「医疗」）是有意的：用户没建时这些规则被跳过，
     * 建了之后自动生效。这样内置表不必预知用户会建哪些分类，也绝不会产生
     * 指向不存在分类的记录。
     */
    private static boolean isKnownCategory(Context ctx, String cat) {
        for (String s : Categories.EXPENSE) if (s.equals(cat)) return true;
        for (CustomCats.Cat c : CustomCats.load(ctx, 0)) if (c.name.equals(cat)) return true;
        return false;
    }
}

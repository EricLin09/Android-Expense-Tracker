package com.example.jizhang;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 商户别名表：把通知文本里的商户名直接映射到分类，命中就不必调用本地模型。
 *
 * 表本身在 assets/merchant_rules.tsv，格式为「关键词 TAB 分类」。加载时按关键词长度
 * 降序排序，因此更具体的词天然优先——"UBER EATS" 会在 "UBER" 之前被检查。
 *
 * 只认 Categories.EXPENSE 里存在的分类；表里写了别的值会被忽略，避免改分类体系后
 * 产生指向不存在分类的记录。
 */
public class MerchantRules {

    private static final String ASSET = "merchant_rules.tsv";

    private static volatile List<Rule> rules;   // 懒加载，进程内缓存

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
        for (Rule r : load(ctx)) {
            if (hit(hay, r)) return r.category;
        }
        return null;
    }

    /**
     * 转小写，并把 '*' 当作分隔符、压缩连续空白。
     *
     * 银行流水里普遍用星号分隔（CommBank 的 "UBER *EATS"、"SMP*SWANKY NOODLES"）。
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

    /** 表里一共多少条规则，供设置页显示。 */
    public static int size(Context ctx) {
        return load(ctx).size();
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

    /** 用户增删自定义分类后必须调用：指向该分类的规则要重新参与匹配。 */
    public static void invalidate() {
        synchronized (MerchantRules.class) {
            rules = null;
        }
    }

    private static List<Rule> parse(Context ctx) {
        List<Rule> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                ctx.getAssets().open(ASSET), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                String kw = line.substring(0, tab).trim().toLowerCase();
                String cat = line.substring(tab + 1).trim();
                if (kw.isEmpty() || !isKnownCategory(ctx, cat)) continue;
                out.add(new Rule(kw, cat));
            }
        } catch (Exception ignored) {
            // 读不到表就当没有规则，全部走模型/待分类，不影响记账主流程
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

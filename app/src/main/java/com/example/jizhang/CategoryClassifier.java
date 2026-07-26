package com.example.jizhang;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * 分类策略：先查商户别名表，没命中才问本地模型。
 *
 * 两级的分工是有意的：
 *  - 别名表纯本地、微秒级、不联网，覆盖高频商户，自动记账路径只走这一级；
 *  - 本地模型有网络开销（约 1~2 秒/条），只在用户主动做批量补分类时调用。
 *
 * 因此一条通知进来时不会因为等模型而卡住通知回调，服务不可用也只是留「待分类」。
 */
public class CategoryClassifier {

    public static final String UNCATEGORIZED = "待分类";

    private CategoryClassifier() {}

    /**
     * 仅查表。用在自动记账路径上——不联网、不阻塞。
     * @return 分类名；没命中返回 null
     */
    public static String byRules(Context ctx, String text) {
        return MerchantRules.match(ctx, text);
    }

    /**
     * 查表 + 模型兜底。会发起网络请求，必须在后台线程调用。
     * @return 分类名；两级都没结果时返回 null
     */
    public static String classify(Context ctx, String text) {
        String hit = MerchantRules.match(ctx, text);
        if (hit != null) return hit;
        return LocalLlm.classify(ctx, text, expenseCategories(ctx));
    }

    /**
     * 把库里所有「待分类」的支出记录补上分类。必须在后台线程调用。
     * @param progress 每处理完一条回调一次（已处理数, 总数），可为 null
     * @return 实际被改写的条数
     */
    public static int fillUncategorized(Context ctx, Progress progress) {
        DbHelper db = new DbHelper(ctx);
        List<Record> pending = db.queryUncategorized();
        String[] cats = expenseCategories(ctx);

        int changed = 0;
        for (int i = 0; i < pending.size(); i++) {
            Record r = pending.get(i);
            if (r.type != 0) continue;   // 只处理支出；收入分类语义不同

            String cat = MerchantRules.match(ctx, r.note);
            if (cat == null) cat = LocalLlm.classify(ctx, r.note, cats);

            if (cat != null && !cat.equals(r.category)) {
                r.category = cat;
                db.update(r);
                changed++;
            }
            if (progress != null) progress.onProgress(i + 1, pending.size());
        }
        return changed;
    }

    /** 预设支出分类 + 用户自定义的支出分类，去掉「其他」放最后以免模型偷懒。 */
    static String[] expenseCategories(Context ctx) {
        List<String> out = new ArrayList<>();
        for (String s : Categories.EXPENSE) {
            if (!"其他".equals(s)) out.add(s);
        }
        for (CustomCats.Cat c : CustomCats.load(ctx, 0)) {
            if (!out.contains(c.name)) out.add(c.name);
        }
        out.add("其他");
        return out.toArray(new String[0]);
    }

    public interface Progress {
        void onProgress(int done, int total);
    }
}

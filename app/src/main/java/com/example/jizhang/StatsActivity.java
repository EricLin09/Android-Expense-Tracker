package com.example.jizhang;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class StatsActivity extends AppCompatActivity {

    /** prefs 里代表「总览」的值；用空串而不是 null，省得和「没存过」混淆 */
    private static final String OVERVIEW = "";

    private DbHelper db;
    private PieChartView pie;
    private LinearLayout legend, trend;
    private TextView tvMonth, pillCurrency, tvTrendTitle, segMonth, segYear, tvRateNote;
    /** null = 总览（人民币 + 澳元一起统计），否则为具体币种代码。与首页 viewMode 语义一致。 */
    private String currency = Currencies.DEFAULT;
    private boolean yearMode = false;
    private final Calendar cal = Calendar.getInstance();
    private final SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM", Locale.CHINA);
    private final SimpleDateFormat fmtYear = new SimpleDateFormat("yyyy", Locale.CHINA);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stats);

        db = new DbHelper(this);
        CustomCats.applyToCatStyle(this);   // 自定义分类的图标/颜色
        // 记住上次查看的币种；空串代表总览（校验仍有效，否则回落默认）
        String saved = getSharedPreferences("jizhang_prefs", MODE_PRIVATE)
                .getString("stats_currency", Currencies.DEFAULT);
        if (OVERVIEW.equals(saved)) {
            currency = null;
        } else if (java.util.Arrays.asList(Currencies.codes()).contains(saved)) {
            currency = saved;
        }
        pie = findViewById(R.id.pie);
        legend = findViewById(R.id.legend);
        trend = findViewById(R.id.trend);
        tvMonth = findViewById(R.id.tvMonth);
        pillCurrency = findViewById(R.id.pillCurrency);
        tvTrendTitle = findViewById(R.id.tvTrendTitle);
        tvRateNote = findViewById(R.id.tvRateNote);
        segMonth = findViewById(R.id.segMonth);
        segYear = findViewById(R.id.segYear);
        segMonth.setOnClickListener(v -> setMode(false));
        segYear.setOnClickListener(v -> setMode(true));

        updateCurrencyPill();
        if (Flavor.DUAL_CURRENCY) {
            pillCurrency.setOnClickListener(this::showCurrencyMenu);
        } else {
            pillCurrency.setVisibility(View.GONE);
            currency = Currencies.DEFAULT;      // 单币种没有「总览」，固定成人民币
        }

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnPrev).setOnClickListener(v -> {
            Calendar prev = (Calendar) cal.clone();
            prev.add(yearMode ? Calendar.YEAR : Calendar.MONTH, -1);
            // 不早于账本起点：月视图比到「年-月」，年视图只比年份
            String start = db.startYm();
            String floor = yearMode ? start.substring(0, 4) : start;
            if ((yearMode ? fmtYear : fmt).format(prev.getTime()).compareTo(floor) < 0) return;
            cal.add(yearMode ? Calendar.YEAR : Calendar.MONTH, -1);
            render();
        });
        findViewById(R.id.btnNext).setOnClickListener(v -> {
            cal.add(yearMode ? Calendar.YEAR : Calendar.MONTH, 1);
            render();
        });

        setMode(false);
    }

    /** 月/年 视图切换 */
    private void setMode(boolean year) {
        yearMode = year;
        int primary = ContextCompat.getColor(this, R.color.textPrimary);
        int secondary = ContextCompat.getColor(this, R.color.textSecondary);
        segMonth.setBackgroundResource(year ? 0 : R.drawable.seg_thumb);
        segYear.setBackgroundResource(year ? R.drawable.seg_thumb : 0);
        segMonth.setTextColor(year ? secondary : primary);
        segYear.setTextColor(year ? primary : secondary);
        segMonth.setTypeface(null, year ? android.graphics.Typeface.NORMAL : android.graphics.Typeface.BOLD);
        segYear.setTypeface(null, year ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        render();
    }

    /** 当前统计区间前缀：月视图 yyyy-MM，年视图 yyyy（date LIKE 前缀匹配通吃两种） */
    private String period() {
        return (yearMode ? fmtYear : fmt).format(cal.getTime());
    }

    private void showCurrencyMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        // 与首页 showViewMenu 保持一致：总览 / 人民币 CNY / 澳元 AUD
        if (Flavor.DUAL_CURRENCY) menu.getMenu().add(0, -1, 0, "总览");
        String[] codes = Currencies.codes();
        for (int i = 0; i < codes.length; i++) {
            menu.getMenu().add(0, i, i + 1,
                    Currencies.name(codes[i]) + "  " + codes[i]);
        }
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() < 0) {
                // 总览要按汇率折算才能算占比；没有缓存汇率就先去拉，成功后自动切过来
                if (Rates.cached(this) <= 0) {
                    android.widget.Toast.makeText(this, "正在获取汇率…", android.widget.Toast.LENGTH_SHORT).show();
                    Rates.fetchAsync(this);
                    pillCurrency.postDelayed(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        if (Rates.cached(this) > 0) {
                            selectCurrency(null);
                        } else {
                            android.widget.Toast.makeText(this,
                                    "汇率获取失败，联网后再试", android.widget.Toast.LENGTH_SHORT).show();
                        }
                    }, 2500);
                    return true;
                }
                selectCurrency(null);
            } else {
                selectCurrency(codes[item.getItemId()]);
            }
            return true;
        });
        menu.show();
    }

    private void selectCurrency(String code) {
        currency = code;
        getSharedPreferences("jizhang_prefs", MODE_PRIVATE).edit()
                .putString("stats_currency", code == null ? OVERVIEW : code).apply();
        updateCurrencyPill();
        render();
    }

    private void updateCurrencyPill() {
        pillCurrency.setText(currency == null
                ? "总览"
                : currency + " " + Currencies.symbol(currency));
    }

    private void render() {
        String ym = period();
        tvMonth.setText(ym);
        // 空态文案跟着统计周期走（放在分支之前，总览模式也要生效）
        pie.setEmptyText(yearMode ? "本年暂无支出" : "本月暂无支出");
        if (currency == null) {
            renderOverview(ym);
            return;
        }
        tvRateNote.setVisibility(View.GONE);
        String sym = Currencies.symbol(currency);

        List<DbHelper.CategorySum> list = db.monthCategoryExpense(ym, currency);
        pie.setData(list, sym);
        renderTrend();

        double total = 0;
        for (DbHelper.CategorySum cs : list) total += cs.sum;

        legend.removeAllViews();
        if (list.isEmpty()) {
            legend.addView(emptyHint());
            return;
        }

        for (int i = 0; i < list.size(); i++) {
            DbHelper.CategorySum cs = list.get(i);
            double pct = total > 0 ? cs.sum / total * 100 : 0;
            legend.addView(buildRow(cs.category,
                    String.format(Locale.CHINA, "%s%.2f", sym, cs.sum), pct));
            if (i < list.size() - 1) legend.addView(divider());
        }
    }

    /** 总览模式下的一个分类：各币种原值 + 折算后的权重（只用来算占比和排序） */
    private static class OvRow {
        final String category;
        final double cny, aud, weight;
        OvRow(String category, double cny, double aud, double weight) {
            this.category = category; this.cny = cny; this.aud = aud; this.weight = weight;
        }
    }

    /**
     * 总览：把两种货币的分类支出合到一起。
     *
     * 折算**只用于几何**——扇区角度、进度条长度、排序。界面上显示的金额一律是原币种原值，
     * 不会出现任何一个折算后的数字。折算方向也不影响结果，因为占比是比值，这里统一折成人民币。
     */
    private void renderOverview(String ym) {
        double rate = Rates.cached(this);        // 1 澳元 = 多少人民币
        if (rate <= 0) {                          // 理论上进不来：菜单里已经挡过一次
            selectCurrency(Currencies.DEFAULT);
            return;
        }

        tvRateNote.setText(String.format(Locale.CHINA,
                "占比按 1 A$ = ¥%.2f 折算 · 金额为原值", rate));
        tvRateNote.setVisibility(View.VISIBLE);

        java.util.LinkedHashMap<String, double[]> merged = new java.util.LinkedHashMap<>();
        for (DbHelper.CategorySum cs : db.monthCategoryExpense(ym, "CNY")) {
            merged.computeIfAbsent(cs.category, k -> new double[2])[0] += cs.sum;
        }
        for (DbHelper.CategorySum cs : db.monthCategoryExpense(ym, "AUD")) {
            merged.computeIfAbsent(cs.category, k -> new double[2])[1] += cs.sum;
        }

        List<OvRow> rows = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, double[]> e : merged.entrySet()) {
            double cny = e.getValue()[0], aud = e.getValue()[1];
            rows.add(new OvRow(e.getKey(), cny, aud, cny + aud * rate));
        }
        java.util.Collections.sort(rows, (a, b) -> Double.compare(b.weight, a.weight));

        double totalW = 0, totalCny = 0, totalAud = 0;
        List<DbHelper.CategorySum> forPie = new java.util.ArrayList<>();
        for (OvRow r : rows) {
            totalW += r.weight; totalCny += r.cny; totalAud += r.aud;
            forPie.add(new DbHelper.CategorySum(r.category, r.weight));
        }

        pie.setData(forPie, "");
        // 中心画各币种原值，而不是折算后的合计
        if (totalW > 0) {
            List<String> lines = new java.util.ArrayList<>();
            if (totalCny > 0) lines.add(String.format(Locale.CHINA, "¥%.2f", totalCny));
            if (totalAud > 0) lines.add(String.format(Locale.CHINA, "A$%.2f", totalAud));
            pie.setCenterLines(lines.toArray(new String[0]));
        }
        renderTrend();

        legend.removeAllViews();
        if (rows.isEmpty()) {
            legend.addView(emptyHint());
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            OvRow r = rows.get(i);
            legend.addView(buildRow(r.category, amountText(r), totalW > 0 ? r.weight / totalW * 100 : 0));
            if (i < rows.size() - 1) legend.addView(divider());
        }
    }

    /** 「¥248.00 + A$186.20」；某币种为 0 时不显示那一段，避免 "+A$0.00" 的噪音 */
    private String amountText(OvRow r) {
        if (r.cny > 0 && r.aud > 0) {
            return String.format(Locale.CHINA, "¥%.2f + A$%.2f", r.cny, r.aud);
        }
        return r.cny > 0 ? String.format(Locale.CHINA, "¥%.2f", r.cny)
                         : String.format(Locale.CHINA, "A$%.2f", r.aud);
    }

    private TextView emptyHint() {
        TextView empty = new TextView(this);
        empty.setText("暂无记录");
        empty.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        empty.setTextSize(14);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(16), dp(28), dp(16), dp(28));
        return empty;
    }

    /**
     * 某月支出合计。单币种取该币种；总览模式按汇率折成人民币，仅用于决定柱子高度——
     * 柱高是比值，折算方向不影响图形。
     */
    private double monthExpense(String ym) {
        if (currency != null) return db.monthTotals(ym, currency)[0];
        double rate = Rates.cached(this);
        return db.monthTotals(ym, "CNY")[0] + db.monthTotals(ym, "AUD")[0] * (rate > 0 ? rate : 0);
    }

    /** 柱状图的柱子数：月视图近 6 月，年视图近 6 年 */
    private static final int TREND_BARS = 6;

    /**
     * 支出柱状图：月视图画近 6 个月，年视图画近 6 年。选中的那根高亮，点其它柱子跳过去。
     *
     * 两种模式共用同一套画法，差别只有步长（月/年）和 key 的格式。key 直接喂给
     * {@link #monthExpense}，底层查询是 date LIKE 'key%'，所以 "2026-07" 和 "2026"
     * 都成立——年合计不需要另写一条 SQL。
     */
    private void renderTrend() {
        trend.removeAllViews();
        int n = TREND_BARS;
        int step = yearMode ? Calendar.YEAR : Calendar.MONTH;
        SimpleDateFormat keyFmt = yearMode ? fmtYear : fmt;
        tvTrendTitle.setText(yearMode ? "近 6 年支出" : "近 6 月支出");

        Calendar c = (Calendar) cal.clone();
        c.add(step, -(n - 1));

        String[] keys = new String[n];
        String[] labels = new String[n];
        double[] sums = new double[n];
        double max = 0;
        for (int i = 0; i < n; i++) {
            keys[i] = keyFmt.format(c.getTime());
            labels[i] = yearMode ? keys[i] : (c.get(Calendar.MONTH) + 1) + "月";
            sums[i] = monthExpense(keys[i]);
            if (sums[i] > max) max = sums[i];
            c.add(step, 1);
        }

        int accent = ContextCompat.getColor(this, R.color.accent);
        String selKey = keyFmt.format(cal.getTime());
        String sym = currency == null ? null : Currencies.symbol(currency);
        int barWidth = dp(22);
        // 账本起点之前的柱子不画：年视图只比年份
        String floor = yearMode ? db.startYm().substring(0, 4) : db.startYm();

        for (int i = 0; i < n; i++) {
            if (keys[i].compareTo(floor) < 0) continue;
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            col.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            boolean selected = keys[i].equals(selKey);

            // 金额（只给选中的那根显示，避免拥挤）。总览模式下柱高是折算值，
            // 标上去就成了「界面上出现折算后金额」——那两个数已经在饼图中心，这里直接不标。
            if (selected && sums[i] > 0 && sym != null) {
                TextView amt = new TextView(this);
                amt.setText(String.format(Locale.CHINA, "%s%.0f", sym, sums[i]));
                amt.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
                amt.setTextSize(11);
                LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                alp.bottomMargin = dp(4);
                amt.setLayoutParams(alp);
                col.addView(amt);
            }

            View bar = new View(this);
            GradientDrawable gd = new GradientDrawable();
            gd.setColor(selected ? accent : ((0x55 << 24) | (accent & 0x00FFFFFF)));
            gd.setCornerRadius(dp(3));
            bar.setBackground(gd);
            int h = max > 0 ? (int) Math.max(dp(3), sums[i] / max * dp(64)) : dp(3);
            bar.setLayoutParams(new LinearLayout.LayoutParams(barWidth, h));
            col.addView(bar);

            TextView label = new TextView(this);
            label.setText(labels[i]);
            label.setTextColor(ContextCompat.getColor(this,
                    selected ? R.color.textPrimary : R.color.textSecondary));
            label.setTextSize(11);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.topMargin = dp(6);
            label.setLayoutParams(llp);
            col.addView(label);

            final int idx = i;
            // 点哪根跳哪根，停在当前模式：月视图跳到那个月，年视图跳到那一年
            col.setOnClickListener(v -> {
                cal.add(step, idx - (n - 1));
                render();
            });
            trend.addView(col);
        }
    }

    private View divider() {
        View v = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(dp(1) / 2, 1));
        lp.leftMargin = dp(16);
        v.setLayoutParams(lp);
        v.setBackgroundColor(ContextCompat.getColor(this, R.color.separator));
        return v;
    }

    /**
     * @param amountText 已经格式化好的金额文案。单币种是 "¥248.00"，总览是 "¥248.00 + A$186.20"
     * @param pct        占比。总览模式下这个值来自折算后的权重
     */
    private View buildRow(String category, String amountText, double pct) {
        int color = CatStyle.chartColor(category);   // 与饼图同一套提亮色

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(13), dp(16), dp(13));
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        row.setForeground(ContextCompat.getDrawable(this, tv.resourceId));
        row.setOnClickListener(v -> showCategoryRecords(category));

        // 顶行：色点 + 名称 + 金额(百分比)
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        View dot = new View(this);
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(color);
        dot.setBackground(gd);
        LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(dp(10), dp(10));
        dp2.rightMargin = dp(10);
        dot.setLayoutParams(dp2);
        top.addView(dot);

        TextView name = new TextView(this);
        name.setText(category);
        name.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        name.setTextSize(15);
        name.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(name);

        TextView val = new TextView(this);
        val.setText(String.format(Locale.CHINA, "%s  %.0f%%", amountText, pct));
        val.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        val.setTextSize(14);
        top.addView(val);
        row.addView(top);

        // 进度条
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundResource(R.drawable.bar_track);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(5));
        bp.topMargin = dp(8);
        bar.setLayoutParams(bp);

        View fill = new View(this);
        GradientDrawable fg = new GradientDrawable();
        fg.setColor(color);
        fg.setCornerRadius(dp(3));
        fill.setBackground(fg);
        float p = (float) Math.max(pct, 2);
        fill.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, p));
        bar.addView(fill);

        View rest = new View(this);
        rest.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, (float) (100 - Math.max(pct, 2))));
        bar.addView(rest);

        row.addView(bar);
        return row;
    }

    /** 点击分类行：底部面板展示该分类当前区间（月/年）的支出明细 */
    private void showCategoryRecords(String category) {
        String ym = period();
        List<Record> list;
        if (currency == null) {
            // 总览：两种货币的明细合到一起，按日期倒序（各自已排好，归并即可）
            list = new java.util.ArrayList<>();
            list.addAll(db.monthCategoryRecords(ym, "CNY", category));
            list.addAll(db.monthCategoryRecords(ym, "AUD", category));
            java.util.Collections.sort(list, (a, b) -> {
                int d = b.date.compareTo(a.date);
                return d != 0 ? d : Long.compare(b.id, a.id);
            });
        } else {
            list = db.monthCategoryRecords(ym, currency, category);
        }

        LinearLayout root = Sheets.container(this);
        float density = getResources().getDisplayMetrics().density;

        // 头部：分类图标 + 名称/月份 + 合计
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        android.widget.ImageView icon = new android.widget.ImageView(this);
        icon.setImageResource(CatStyle.icon(category));
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(CatStyle.color(category)));
        icon.setBackground(CatStyle.circleBg(category, density, false));
        int ip = dp(9);
        icon.setPadding(ip, ip, ip, ip);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(40), dp(40));
        ilp.rightMargin = dp(12);
        icon.setLayoutParams(ilp);
        head.addView(icon);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView name = new TextView(this);
        name.setText(category);
        name.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        name.setTextSize(16);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        titleCol.addView(name);
        TextView month = new TextView(this);
        month.setText(ym + " · " + list.size() + " 笔");
        month.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        month.setTextSize(12);
        titleCol.addView(month);
        head.addView(titleCol);

        // 合计：单币种一个数；总览按币种分别汇总，不把两种货币加在一起
        double totalCny = 0, totalAud = 0;
        for (Record r : list) {
            if ("AUD".equals(r.currency)) totalAud += r.amount; else totalCny += r.amount;
        }
        String sumText;
        if (currency != null) {
            sumText = String.format(Locale.CHINA, "-%s%.2f",
                    Currencies.symbol(currency), totalCny + totalAud);
        } else if (totalCny > 0 && totalAud > 0) {
            sumText = String.format(Locale.CHINA, "-¥%.2f\n-A$%.2f", totalCny, totalAud);
        } else {
            sumText = totalAud > 0 ? String.format(Locale.CHINA, "-A$%.2f", totalAud)
                                   : String.format(Locale.CHINA, "-¥%.2f", totalCny);
        }
        TextView sum = new TextView(this);
        sum.setGravity(Gravity.END);
        sum.setText(sumText);
        sum.setTextColor(ContextCompat.getColor(this, R.color.expense));
        sum.setTextSize(16);
        sum.setTypeface(null, android.graphics.Typeface.BOLD);
        sum.setFontFeatureSettings("tnum");
        head.addView(sum);
        root.addView(head);

        // 明细列表（多于 7 条时限高滚动）
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < list.size(); i++) {
            Record r = list.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(11), 0, dp(11));

            TextView date = new TextView(this);
            date.setText(r.date.substring(5));
            date.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
            date.setTextSize(13);
            date.setFontFeatureSettings("tnum");
            date.setLayoutParams(new LinearLayout.LayoutParams(dp(48),
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            row.addView(date);

            TextView note = new TextView(this);
            note.setText(r.note == null || r.note.isEmpty() ? "无备注" : r.note);
            note.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
            note.setTextSize(14);
            note.setMaxLines(1);
            note.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            nlp.leftMargin = dp(6);
            nlp.rightMargin = dp(10);
            note.setLayoutParams(nlp);
            row.addView(note);

            TextView amt = new TextView(this);
            // 用每条记录自己的币种符号：单币种模式下结果不变，总览下才不会串币种
            amt.setText(String.format(Locale.CHINA, "-%s%.2f",
                    Currencies.symbol(r.currency), r.amount));
            amt.setTextColor(ContextCompat.getColor(this, R.color.expense));
            amt.setTextSize(14);
            amt.setTypeface(null, android.graphics.Typeface.BOLD);
            amt.setFontFeatureSettings("tnum");
            row.addView(amt);

            rows.addView(row);
            if (i < list.size() - 1) {
                View d = new View(this);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Math.max(dp(1) / 2, 1));
                dlp.leftMargin = dp(54);
                d.setLayoutParams(dlp);
                d.setBackgroundColor(ContextCompat.getColor(this, R.color.separator));
                rows.addView(d);
            }
        }

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                list.size() > 7 ? dp(330) : ViewGroup.LayoutParams.WRAP_CONTENT);
        svLp.topMargin = dp(8);
        sv.setLayoutParams(svLp);
        sv.addView(rows);
        root.addView(sv);

        Sheets.show(this, root);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}

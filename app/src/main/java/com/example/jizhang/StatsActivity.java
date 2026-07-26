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

    private DbHelper db;
    private PieChartView pie;
    private LinearLayout legend, trend;
    private TextView tvMonth, pillCurrency, tvTrendTitle, segMonth, segYear;
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
        // 记住上次查看的币种（校验仍有效，否则回落默认）
        String saved = getSharedPreferences("jizhang_prefs", MODE_PRIVATE)
                .getString("stats_currency", Currencies.DEFAULT);
        if (java.util.Arrays.asList(Currencies.codes()).contains(saved)) {
            currency = saved;
        }
        pie = findViewById(R.id.pie);
        legend = findViewById(R.id.legend);
        trend = findViewById(R.id.trend);
        tvMonth = findViewById(R.id.tvMonth);
        pillCurrency = findViewById(R.id.pillCurrency);
        tvTrendTitle = findViewById(R.id.tvTrendTitle);
        segMonth = findViewById(R.id.segMonth);
        segYear = findViewById(R.id.segYear);
        segMonth.setOnClickListener(v -> setMode(false));
        segYear.setOnClickListener(v -> setMode(true));

        updateCurrencyPill();
        pillCurrency.setOnClickListener(this::showCurrencyMenu);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnPrev).setOnClickListener(v -> {
            Calendar prev = (Calendar) cal.clone();
            prev.add(yearMode ? Calendar.YEAR : Calendar.MONTH, -1);
            // 账本从 2026-07 开始：月视图不早于 2026-07，年视图不早于 2026
            String floor = yearMode ? DbHelper.START_YM.substring(0, 4) : DbHelper.START_YM;
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
        String[] codes = Currencies.codes();
        for (int i = 0; i < codes.length; i++) {
            menu.getMenu().add(0, i, i, codes[i] + "  " + Currencies.symbol(codes[i]));
        }
        menu.setOnMenuItemClickListener(item -> {
            currency = codes[item.getItemId()];
            getSharedPreferences("jizhang_prefs", MODE_PRIVATE).edit()
                    .putString("stats_currency", currency).apply();
            updateCurrencyPill();
            render();
            return true;
        });
        menu.show();
    }

    private void updateCurrencyPill() {
        pillCurrency.setText(currency + " " + Currencies.symbol(currency));
    }

    private void render() {
        String ym = period();
        tvMonth.setText(ym);
        String sym = Currencies.symbol(currency);

        List<DbHelper.CategorySum> list = db.monthCategoryExpense(ym, currency);
        pie.setData(list, sym);
        renderTrend();

        double total = 0;
        for (DbHelper.CategorySum cs : list) total += cs.sum;

        legend.removeAllViews();
        if (list.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无记录");
            empty.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
            empty.setTextSize(14);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(16), dp(28), dp(16), dp(28));
            legend.addView(empty);
            return;
        }

        for (int i = 0; i < list.size(); i++) {
            legend.addView(buildRow(list.get(i), total, sym));
            if (i < list.size() - 1) legend.addView(divider());
        }
    }

    /** 月视图：近 6 月支出柱状图（选中月高亮）；年视图：该年 12 个月，点柱子跳到单月 */
    private void renderTrend() {
        trend.removeAllViews();
        int n;
        String[] yms, labels;
        Calendar c = (Calendar) cal.clone();
        if (yearMode) {
            tvTrendTitle.setText("各月支出");
            n = 12;
            c.set(Calendar.MONTH, Calendar.JANUARY);
        } else {
            tvTrendTitle.setText("近 6 月支出");
            n = 6;
            c.add(Calendar.MONTH, -5);
        }
        yms = new String[n];
        labels = new String[n];
        double[] sums = new double[n];
        double max = 0;
        for (int i = 0; i < n; i++) {
            yms[i] = fmt.format(c.getTime());
            labels[i] = (c.get(Calendar.MONTH) + 1) + "月";
            sums[i] = db.monthTotals(yms[i], currency)[0];
            if (sums[i] > max) max = sums[i];
            c.add(Calendar.MONTH, 1);
        }

        int accent = ContextCompat.getColor(this, R.color.accent);
        String selYm = yearMode ? "" : fmt.format(cal.getTime());
        String sym = Currencies.symbol(currency);
        int barWidth = yearMode ? dp(13) : dp(22);

        for (int i = 0; i < n; i++) {
            if (yms[i].compareTo(DbHelper.START_YM) < 0) continue;   // 账本起点之前的月份不画
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            col.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            boolean selected = yms[i].equals(selYm);

            // 金额（只给选中月显示，避免拥挤）
            if (selected && sums[i] > 0) {
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
            label.setTextSize(yearMode ? 9 : 11);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.topMargin = dp(6);
            label.setLayoutParams(llp);
            col.addView(label);

            final int idx = i;
            col.setOnClickListener(v -> {
                if (yearMode) {
                    // 年视图点某个月：跳到该月的月视图
                    cal.set(Calendar.MONTH, idx);
                    setMode(false);
                } else {
                    cal.add(Calendar.MONTH, idx - 5);
                    render();
                }
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

    private View buildRow(DbHelper.CategorySum cs, double total, String sym) {
        int color = CatStyle.chartColor(cs.category);   // 与饼图同一套提亮色
        double pct = total > 0 ? cs.sum / total * 100 : 0;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(13), dp(16), dp(13));
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        row.setForeground(ContextCompat.getDrawable(this, tv.resourceId));
        row.setOnClickListener(v -> showCategoryRecords(cs.category));

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
        name.setText(cs.category);
        name.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        name.setTextSize(15);
        name.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(name);

        TextView val = new TextView(this);
        val.setText(String.format(Locale.CHINA, "%s%.2f  %.0f%%", sym, cs.sum, pct));
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
        String sym = Currencies.symbol(currency);
        List<Record> list = db.monthCategoryRecords(ym, currency, category);

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

        double total = 0;
        for (Record r : list) total += r.amount;
        TextView sum = new TextView(this);
        sum.setText(String.format(Locale.CHINA, "-%s%.2f", sym, total));
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
            amt.setText(String.format(Locale.CHINA, "-%s%.2f", sym, r.amount));
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

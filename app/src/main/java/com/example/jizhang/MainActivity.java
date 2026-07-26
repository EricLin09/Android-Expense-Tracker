package com.example.jizhang;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.animation.ValueAnimator;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.animation.DecelerateInterpolator;
import android.widget.GridLayout;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    /** true=总览/小组件大数字显示本月总支出（收入退到小字），false=显示结余 */
    public static final String KEY_BIG_EXPENSE = "big_expense";
    /** 首页左上角标题，用户可自定义，空则用默认「记账本」 */
    public static final String KEY_HOME_TITLE = "home_title";
    /** 首页默认标题取应用名——国内版叫「记账簿」，由 flavor 的 strings.xml 覆盖，
     *  这样启动器标签和首页标题只需要改一处。 */

    static String homeTitle(android.content.Context ctx) {
        String t = ctx.getSharedPreferences("jizhang_prefs", MODE_PRIVATE)
                .getString(KEY_HOME_TITLE, "");
        return t == null || t.trim().isEmpty() ? ctx.getString(R.string.app_name) : t.trim();
    }

    static boolean bigExpense(android.content.Context ctx) {
        return ctx.getSharedPreferences("jizhang_prefs", MODE_PRIVATE)
                .getBoolean(KEY_BIG_EXPENSE, false);
    }

    private DbHelper db;
    private RecyclerView recycler;
    private TextView tvEmpty, tvMonthLabel, pillCurrency, tvUncatBanner;
    private LinearLayout overviewBody;
    private View overviewCard, searchBar;
    private android.widget.EditText etSearch;
    private RecordAdapter adapter;
    private String viewMode = null;   // null=总览（所有货币），否则为指定货币代码
    private String searchQuery = "";  // 非空时进入搜索模式（搜全部月份）
    private final java.util.Calendar monthCal = java.util.Calendar.getInstance(); // 当前查看的月份
    private final java.util.Map<String, Double> shownBalance = new java.util.HashMap<>(); // 数字滚动动画起点

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        db = new DbHelper(this);
        CustomCats.applyToCatStyle(this);   // 自定义分类的图标/颜色
        recycler = findViewById(R.id.recycler);
        tvEmpty = findViewById(R.id.tvEmpty);
        tvMonthLabel = findViewById(R.id.tvMonthLabel);
        pillCurrency = findViewById(R.id.pillCurrency);
        overviewBody = findViewById(R.id.overviewBody);
        overviewCard = findViewById(R.id.overviewCard);
        searchBar = findViewById(R.id.searchBar);
        etSearch = findViewById(R.id.etSearch);
        tvUncatBanner = findViewById(R.id.tvUncatBanner);
        tvUncatBanner.setOnClickListener(v -> processUncategorized());

        findViewById(R.id.btnSearch).setOnClickListener(v -> openSearch());
        findViewById(R.id.btnSearchCancel).setOnClickListener(v -> closeSearch());
        etSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                if (searchBar.getVisibility() != View.VISIBLE) return;
                searchQuery = s.toString().trim();
                refresh();
            }
        });

        findViewById(R.id.btnPrevMonth).setOnClickListener(v -> {
            java.util.Calendar prev = (java.util.Calendar) monthCal.clone();
            prev.add(java.util.Calendar.MONTH, -1);
            String ym = new SimpleDateFormat("yyyy-MM", Locale.CHINA).format(prev.getTime());
            if (ym.compareTo(DbHelper.START_YM) < 0) return;   // 账本从 2026-07 开始
            monthCal.add(java.util.Calendar.MONTH, -1);
            refresh();
        });
        findViewById(R.id.btnNextMonth).setOnClickListener(v -> {
            if (!isCurrentMonth()) {
                monthCal.add(java.util.Calendar.MONTH, 1);
                refresh();
            }
        });

        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RecordAdapter();
        recycler.setAdapter(adapter);

        updatePill();
        if (Flavor.DUAL_CURRENCY) {
            pillCurrency.setOnClickListener(this::showViewMenu);
        } else {
            // 只有一种货币，没有可切的东西——留一个点了没反应的控件比没有更糟
            pillCurrency.setVisibility(View.GONE);
            viewMode = Currencies.DEFAULT;
        }

        FloatingActionButton fab = findViewById(R.id.fabAdd);
        fab.setOnClickListener(v ->
                startActivity(new Intent(this, AddActivity.class)));

        findViewById(R.id.tvAppTitle).setOnClickListener(v -> editTitle());

        findViewById(R.id.btnStats).setOnClickListener(v ->
                startActivity(new Intent(this, StatsActivity.class)));
        findViewById(R.id.btnAuto).setOnClickListener(v ->
                startActivity(new Intent(this, AutoSettingsActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 到期的周期记账先补上，再做每日自动备份
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
                .format(new java.util.Date());
        db.processRecurring(today);
        Backup.autoBackupIfDue(this);
        // 汇率只服务小组件的双币行；单币种版本没有它，也就没有任何网络请求
        if (Flavor.DUAL_CURRENCY) Rates.fetchAsync(this);
        ((TextView) findViewById(R.id.tvAppTitle)).setText(homeTitle(this));
        refresh();
        WidgetProvider.refresh(this);   // 打开 App 顺带刷新桌面小组件（数据+最新布局）
    }

    /** 点首页标题直接改名（留空恢复默认「记账本」） */
    private void editTitle() {
        LinearLayout root = Sheets.container(this);

        TextView label = new TextView(this);
        label.setText("首页标题");
        label.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        label.setTypeface(null, Typeface.BOLD);
        label.setGravity(Gravity.CENTER);
        root.addView(label);

        android.widget.EditText et = new android.widget.EditText(this);
        et.setText(homeTitle(this));
        et.setHint(getString(R.string.app_name));
        et.setSelectAllOnFocus(true);
        et.setSingleLine(true);
        et.setFilters(new android.text.InputFilter[]{
                new android.text.InputFilter.LengthFilter(10)});
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        et.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = dp(10);
        et.setLayoutParams(elp);
        root.addView(et);

        TextView hint = new TextView(this);
        hint.setText("最多 10 个字，留空恢复默认");
        hint.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setGravity(Gravity.CENTER);
        root.addView(hint);

        TextView ok = new TextView(this);
        ok.setText("保存");
        ok.setTextColor(0xFFFFFFFF);
        ok.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        ok.setTypeface(null, Typeface.BOLD);
        ok.setGravity(Gravity.CENTER);
        ok.setBackgroundResource(R.drawable.btn_primary);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        olp.topMargin = dp(16);
        ok.setLayoutParams(olp);
        root.addView(ok);

        com.google.android.material.bottomsheet.BottomSheetDialog d = Sheets.show(this, root);
        ok.setOnClickListener(v -> {
            getSharedPreferences("jizhang_prefs", MODE_PRIVATE).edit()
                    .putString(KEY_HOME_TITLE, et.getText().toString().trim()).apply();
            ((TextView) findViewById(R.id.tvAppTitle)).setText(homeTitle(this));
            d.dismiss();
        });
    }

    private void openSearch() {
        searchBar.setVisibility(View.VISIBLE);
        etSearch.requestFocus();
        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(etSearch, 0);
        searchQuery = etSearch.getText().toString().trim();
        refresh();
    }

    private void closeSearch() {
        searchBar.setVisibility(View.GONE);
        etSearch.setText("");
        searchQuery = "";
        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
        refresh();
    }

    private boolean searching() {
        return searchBar.getVisibility() == View.VISIBLE && !searchQuery.isEmpty();
    }

    /** 备注/分类/金额任一匹配（金额按文本前缀，如 "12" 命中 12.50） */
    private boolean matches(Record r, String q) {
        if (r.note != null && r.note.toLowerCase(Locale.CHINA).contains(q)) return true;
        if (r.category != null && r.category.contains(q)) return true;
        return String.format(Locale.CHINA, "%.2f", r.amount).startsWith(q);
    }

    @Override
    public void onBackPressed() {
        if (searchBar.getVisibility() == View.VISIBLE) {
            closeSearch();
            return;
        }
        super.onBackPressed();
    }

    private void showViewMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        if (Flavor.DUAL_CURRENCY) menu.getMenu().add(0, -1, 0, "总览");
        String[] codes = Currencies.codes();
        for (int i = 0; i < codes.length; i++) {
            menu.getMenu().add(0, i, i + 1,
                    Currencies.name(codes[i]) + "  " + codes[i]);
        }
        menu.setOnMenuItemClickListener(item -> {
            viewMode = item.getItemId() < 0 ? null : codes[item.getItemId()];
            updatePill();
            refresh();
            return true;
        });
        menu.show();
    }

    private void updatePill() {
        pillCurrency.setText(viewMode == null
                ? "总览"
                : viewMode + " " + Currencies.symbol(viewMode));
    }

    /** 当前查看的月份 yyyy-MM */
    private String currentMonth() {
        return new SimpleDateFormat("yyyy-MM", Locale.CHINA).format(monthCal.getTime());
    }

    private boolean isCurrentMonth() {
        String now = new SimpleDateFormat("yyyy-MM", Locale.CHINA).format(new java.util.Date());
        return now.equals(currentMonth());
    }

    private void refresh() {
        List<Record> shown = new ArrayList<>();
        if (searching()) {
            // 搜索模式：搜全部月份、全部货币，隐藏概览卡
            String q = searchQuery.toLowerCase(Locale.CHINA);
            for (Record r : db.queryAll()) {
                if (matches(r, q)) shown.add(r);
            }
            overviewCard.setVisibility(View.GONE);
            tvUncatBanner.setVisibility(View.GONE);
            tvEmpty.setText("没有找到匹配的记录");
        } else {
            String ym = currentMonth();
            for (Record r : db.queryAll()) {
                if (!r.date.startsWith(ym)) continue;
                if (viewMode == null || viewMode.equals(r.currency)) shown.add(r);
            }
            overviewCard.setVisibility(View.VISIBLE);
            tvEmpty.setText("还没有记录\n点击右下角 + 记一笔");
        }
        adapter.setData(shown);
        recycler.scheduleLayoutAnimation();
        boolean empty = shown.isEmpty();
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        recycler.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (!searching()) {
            buildOverview();
            refreshUncatBanner();
        }
    }

    private void refreshUncatBanner() {
        int n = db.queryUncategorized().size();
        tvUncatBanner.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
        if (n > 0) tvUncatBanner.setText(n + " 笔自动记账待归类 ›");
    }

    /** 逐笔弹底部面板选分类，把「待分类」归入正式分类 */
    private void processUncategorized() {
        List<Record> list = db.queryUncategorized();
        if (list.isEmpty()) {
            refresh();
            return;
        }
        Record r = list.get(0);
        String sym = Currencies.symbol(r.currency);
        LinearLayout root = Sheets.container(this);

        TextView title = new TextView(this);
        title.setText(String.format(Locale.CHINA, "%s%s%.2f",
                r.type == 0 ? "-" : "+", sym, r.amount));
        title.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title);

        TextView sub = new TextView(this);
        String note = (r.note == null || r.note.isEmpty()) ? r.date : r.note + " · " + r.date;
        sub.setText(list.size() > 1 ? note + " · 还剩 " + list.size() + " 笔" : note);
        sub.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sub.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(4);
        sub.setLayoutParams(slp);
        root.addView(sub);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(4);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.topMargin = dp(14);
        grid.setLayoutParams(glp);

        // 预设（去「其他」）+ 自定义 + 「其他」，与记一笔页顺序一致
        java.util.List<String> cats = new java.util.ArrayList<>();
        for (String c : (r.type == 0 ? Categories.EXPENSE : Categories.INCOME)) {
            if (!"其他".equals(c)) cats.add(c);
        }
        for (CustomCats.Cat cc : CustomCats.load(this, r.type)) cats.add(cc.name);
        cats.add("其他");
        final com.google.android.material.bottomsheet.BottomSheetDialog[] holder =
                new com.google.android.material.bottomsheet.BottomSheetDialog[1];
        float density = getResources().getDisplayMetrics().density;
        for (String c : cats) {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.setPadding(0, dp(10), 0, dp(10));

            ImageView icon = new ImageView(this);
            icon.setImageResource(CatStyle.icon(c));
            icon.setImageTintList(android.content.res.ColorStateList.valueOf(CatStyle.color(c)));
            icon.setBackground(CatStyle.circleBg(c, density, false));
            int p = dp(11);
            icon.setPadding(p, p, p, p);
            icon.setLayoutParams(new LinearLayout.LayoutParams(dp(46), dp(46)));
            col.addView(icon);

            TextView name = new TextView(this);
            name.setText(c);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            name.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nlp.topMargin = dp(5);
            name.setLayoutParams(nlp);
            col.addView(name);

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
            col.setLayoutParams(lp);

            col.setOnClickListener(v -> {
                r.category = c;
                db.update(r);
                WidgetProvider.refresh(this);
                if (holder[0] != null) holder[0].dismiss();
                processUncategorized();   // 继续下一笔
            });
            grid.addView(col);
        }
        root.addView(grid);

        TextView later = new TextView(this);
        later.setText("稍后再说");
        later.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        later.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        later.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        llp.topMargin = dp(6);
        later.setLayoutParams(llp);
        root.addView(later);

        holder[0] = Sheets.show(this, root);
        holder[0].setOnCancelListener(d -> refresh());
        later.setOnClickListener(v -> {
            holder[0].dismiss();
            refresh();
        });
    }

    private void buildOverview() {
        String ym = currentMonth();
        tvMonthLabel.setText(ym);
        overviewBody.removeAllViews();

        List<String> codes = new ArrayList<>();
        if (viewMode != null) {
            codes.add(viewMode);
        } else {
            List<String> present = db.monthCurrencies(ym);
            for (String c : Currencies.codes()) {
                if (present.contains(c)) codes.add(c);
            }
            if (codes.isEmpty()) codes.add(Currencies.DEFAULT);
        }

        if (codes.size() == 2) {
            // 总览：双币左右并排，中间细竖线
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.addView(buildCompactBlock(codes.get(0)));
            View vsep = new View(this);
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(1,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            vlp.leftMargin = dp(14);
            vlp.rightMargin = dp(14);
            vlp.topMargin = dp(4);
            vlp.bottomMargin = dp(2);
            vsep.setLayoutParams(vlp);
            vsep.setBackgroundColor(ContextCompat.getColor(this, R.color.separator));
            row.addView(vsep);
            row.addView(buildCompactBlock(codes.get(1)));
            overviewBody.addView(row);
        } else {
            overviewBody.addView(buildFullBlock(codes.get(0), viewMode == null));
        }
    }

    /** 金额文案：货币符号缩小到 0.6 倍，更精致 */
    private CharSequence money(String sym, double v) {
        String neg = v < 0 ? "-" : "";
        String s = neg + sym + String.format(Locale.CHINA, "%.2f", Math.abs(v));
        SpannableString sp = new SpannableString(s);
        sp.setSpan(new RelativeSizeSpan(0.6f), neg.length(), neg.length() + sym.length(), 0);
        return sp;
    }

    /** 结余数字滚动动画：从上次显示的值滚到新值 */
    private void animateBalance(TextView tv, String code, String sym, double target) {
        Double prev = shownBalance.get(code);
        shownBalance.put(code, target);
        if (prev == null || Math.abs(prev - target) < 0.005) {
            tv.setText(money(sym, target));
            return;
        }
        ValueAnimator va = ValueAnimator.ofFloat(prev.floatValue(), (float) target);
        va.setDuration(450);
        va.setInterpolator(new DecelerateInterpolator());
        va.addUpdateListener(a -> tv.setText(money(sym, (Float) a.getAnimatedValue())));
        va.start();
    }

    /** 并排小块：货币名 + 大数字（结余或总支出）+ 小字行 */
    private View buildCompactBlock(String code) {
        double[] t = db.monthTotals(currentMonth(), code);
        String sym = Currencies.symbol(code);
        boolean be = bigExpense(this);

        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(this);
        name.setText(Currencies.name(code) + (be ? " · 支出" : ""));
        name.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        block.addView(name);

        TextView bal = new TextView(this);
        bal.setTextColor(ContextCompat.getColor(this,
                be ? R.color.expense : R.color.textPrimary));
        bal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 23);
        bal.setTypeface(null, Typeface.BOLD);
        bal.setFontFeatureSettings("tnum");
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(2);
        bal.setLayoutParams(blp);
        animateBalance(bal, code, sym, be ? t[0] : t[1] - t[0]);
        block.addView(bal);

        if (be) {
            block.addView(miniRow("收入", sym, t[1], R.color.income, dp(10)));
        } else {
            block.addView(miniRow("支出", sym, t[0], R.color.expense, dp(10)));
            block.addView(miniRow("收入", sym, t[1], R.color.income, dp(3)));
        }
        return block;
    }

    private View miniRow(String label, String sym, double v, int colorRes, int topMargin) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = topMargin;
        row.setLayoutParams(rlp);

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        l.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(l);

        TextView val = new TextView(this);
        val.setText(String.format(Locale.CHINA, "%s%.2f", sym, v));
        val.setTextColor(ContextCompat.getColor(this, colorRes));
        val.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        val.setTypeface(null, Typeface.BOLD);
        val.setFontFeatureSettings("tnum");
        row.addView(val);
        return row;
    }

    /** 单币块：与双币块同高的紧凑全宽布局（大数字 + 底部两列小字，中间竖线铺满） */
    private View buildFullBlock(String code, boolean showName) {
        double[] t = db.monthTotals(currentMonth(), code);
        String sym = Currencies.symbol(code);
        boolean be = bigExpense(this);

        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 字号/间距与双币块 buildCompactBlock 逐项一致，保证两种视图卡片高度完全相同
        TextView name = new TextView(this);
        name.setText(showName
                ? Currencies.name(code) + " · " + code + (be ? " · 支出" : "")
                : (be ? "本月支出" : "本月结余"));
        name.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        block.addView(name);

        TextView bal = new TextView(this);
        bal.setTextColor(ContextCompat.getColor(this,
                be ? R.color.expense : R.color.textPrimary));
        bal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 23);
        bal.setTypeface(null, Typeface.BOLD);
        bal.setFontFeatureSettings("tnum");
        LinearLayout.LayoutParams balLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        balLp.topMargin = dp(2);
        bal.setLayoutParams(balLp);
        animateBalance(bal, code, sym, be ? t[0] : t[1] - t[0]);
        block.addView(bal);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(10);
        row.setLayoutParams(rowLp);
        if (!be) {
            row.addView(pairCell("支出", sym + String.format(Locale.CHINA, "%.2f", t[0]), R.color.expense));
            row.addView(vDivider());
            row.addView(pairCell("收入", sym + String.format(Locale.CHINA, "%.2f", t[1]), R.color.income));
        } else {
            row.addView(pairCell("收入", sym + String.format(Locale.CHINA, "%.2f", t[1]), R.color.income));
            row.addView(vDivider());
            double balance = t[1] - t[0];
            row.addView(pairCell("结余",
                    (balance < 0 ? "-" : "") + sym + String.format(Locale.CHINA, "%.2f", Math.abs(balance)),
                    R.color.textSecondary));
        }
        block.addView(row);

        return block;
    }

    /** 底部两列之间的竖分隔线（与双币总览的竖线呼应） */
    private View vDivider() {
        View v = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(1,
                ViewGroup.LayoutParams.MATCH_PARENT);
        lp.leftMargin = dp(12);
        lp.rightMargin = dp(12);
        v.setLayoutParams(lp);
        v.setBackgroundColor(ContextCompat.getColor(this, R.color.separator));
        return v;
    }

    /** 底部一格：左标签 + 右金额，占半宽（两格 + 竖线铺满整行，与双币小字行同高） */
    private View pairCell(String label, String value, int valueColorRes) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.HORIZONTAL);
        cell.setGravity(Gravity.CENTER_VERTICAL);
        cell.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        l.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        cell.addView(l);

        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(ContextCompat.getColor(this, valueColorRes));
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        v.setTypeface(null, Typeface.BOLD);
        v.setFontFeatureSettings("tnum");
        cell.addView(v);

        return cell;
    }

    private int dp(int val) {
        return Math.round(val * getResources().getDisplayMetrics().density);
    }

    /** 日期分组头文案：今天 / 昨天 / M月d日（跨年补年份）。 */
    private String dateLabel(String date) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
        String today = f.format(new java.util.Date());
        String yesterday = f.format(new java.util.Date(System.currentTimeMillis() - 86400000L));
        if (date.equals(today)) return "今天";
        if (date.equals(yesterday)) return "昨天";
        try {
            java.util.Date d = f.parse(date);
            String pattern = date.startsWith(today.substring(0, 4)) ? "M月d日" : "yyyy年M月d日";
            return new SimpleDateFormat(pattern, Locale.CHINA).format(d);
        } catch (Exception e) {
            return date;
        }
    }

    // ---------- RecyclerView Adapter（记录按日期分组，穿插日期头） ----------
    class RecordAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int TYPE_HEADER = 0;
        private static final int TYPE_RECORD = 1;
        private final List<Object> items = new ArrayList<>();

        void setData(List<Record> d) {
            items.clear();
            String lastDate = null;
            for (Record r : d) {
                if (!r.date.equals(lastDate)) {
                    items.add(dateLabel(r.date));
                    lastDate = r.date;
                }
                items.add(r);
            }
            notifyDataSetChanged();
        }

        @Override
        public int getItemViewType(int position) {
            return items.get(position) instanceof String ? TYPE_HEADER : TYPE_RECORD;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater in = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_HEADER) {
                return new HeaderVH(in.inflate(R.layout.item_date_header, parent, false));
            }
            return new VH(in.inflate(R.layout.item_record, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder instanceof HeaderVH) {
                ((HeaderVH) holder).tvHeader.setText((String) items.get(position));
                return;
            }
            VH h = (VH) holder;
            Record r = (Record) items.get(position);
            h.tvCategory.setText(r.category);
            h.tvNote.setText(r.note == null || r.note.isEmpty() ? "无备注" : r.note);
            if (r.source == 1 || r.source == 2) {
                h.tvAutoTag.setText(r.source == 1 ? "自动" : "周期");
                h.tvAutoTag.setVisibility(View.VISIBLE);
            } else {
                h.tvAutoTag.setVisibility(View.GONE);
            }

            int color = CatStyle.color(r.category);
            float density = getResources().getDisplayMetrics().density;
            h.ivCatIcon.setImageResource(CatStyle.icon(r.category));
            h.ivCatIcon.setImageTintList(ColorStateList.valueOf(color));
            h.ivCatIcon.setBackground(CatStyle.circleBg(r.category, density, false));

            String sym = Currencies.symbol(r.currency);
            if (r.type == 0) {
                h.tvAmount.setText(String.format(Locale.CHINA, "-%s%.2f", sym, r.amount));
                h.tvAmount.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.expense));
            } else if ("转账".equals(r.category)) {
                // 转账不算收入，用中性色显示，不带 +
                h.tvAmount.setText(String.format(Locale.CHINA, "%s%.2f", sym, r.amount));
                h.tvAmount.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.textPrimary));
            } else {
                h.tvAmount.setText(String.format(Locale.CHINA, "+%s%.2f", sym, r.amount));
                h.tvAmount.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.income));
            }

            h.itemView.setOnClickListener(v -> {
                Intent it = new Intent(MainActivity.this, AddActivity.class);
                it.putExtra(AddActivity.EXTRA_ID, r.id);
                startActivity(it);
            });

            h.itemView.setOnLongClickListener(v -> {
                Sheets.confirm(MainActivity.this, "删除这条记录？", "删除", () -> {
                    db.delete(r.id);
                    WidgetProvider.refresh(MainActivity.this);
                    refresh();
                });
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class HeaderVH extends RecyclerView.ViewHolder {
            TextView tvHeader;
            HeaderVH(View v) {
                super(v);
                tvHeader = v.findViewById(R.id.tvHeader);
            }
        }

        class VH extends RecyclerView.ViewHolder {
            TextView tvCategory, tvNote, tvAmount, tvAutoTag;
            ImageView ivCatIcon;
            VH(View v) {
                super(v);
                tvCategory = v.findViewById(R.id.tvCategory);
                tvNote = v.findViewById(R.id.tvNote);
                tvAmount = v.findViewById(R.id.tvAmount);
                tvAutoTag = v.findViewById(R.id.tvAutoTag);
                ivCatIcon = v.findViewById(R.id.ivCatIcon);
            }
        }
    }
}

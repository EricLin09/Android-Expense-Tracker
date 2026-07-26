package com.example.jizhang;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

public class AddActivity extends AppCompatActivity {

    public static final String EXTRA_ID = "record_id";
    public static final String EXTRA_RECURRING = "recurring";          // true=新建周期记账规则
    public static final String EXTRA_RECURRING_ID = "recurring_id";    // >=0 编辑已有规则

    private DbHelper db;
    private TextView segExpense, segIncome, tvDate, pillCurrency, tvPeriod;
    private EditText etAmount, etNote;
    private GridLayout grid;
    private static final String PREFS = "jizhang_prefs";
    private static final String KEY_CURRENCY = "last_currency";

    private long editId = -1;        // >=0 表示编辑已有记录
    private String originalCategory = null;   // 编辑前的分类，用来判断用户是否改过
    private boolean expense = true;
    private String selectedCategory = null;
    private String selectedCurrency = Currencies.DEFAULT;
    private final Calendar cal = Calendar.getInstance();
    private final SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);

    // 周期记账模式
    private boolean recurringMode = false;
    private long recurringId = -1;
    private int periodType = Recurring.PERIOD_MONTHLY;
    private int periodDays = 30;     // PERIOD_DAYS 模式下的间隔天数

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add);

        db = new DbHelper(this);
        CustomCats.applyToCatStyle(this);   // 让自定义分类的图标/颜色生效
        segExpense = findViewById(R.id.segExpense);
        segIncome = findViewById(R.id.segIncome);
        etAmount = findViewById(R.id.etAmount);
        etNote = findViewById(R.id.etNote);
        grid = findViewById(R.id.gridCategory);
        tvDate = findViewById(R.id.tvDate);
        pillCurrency = findViewById(R.id.pillCurrency);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        selectedCurrency = prefs.getString(KEY_CURRENCY, Currencies.DEFAULT);
        updateCurrencyPill();
        pillCurrency.setOnClickListener(this::showCurrencyMenu);

        tvDate.setText(fmt.format(cal.getTime()));
        findViewById(R.id.rowDate).setOnClickListener(v -> pickDate());

        segExpense.setOnClickListener(v -> setType(true));
        segIncome.setOnClickListener(v -> setType(false));

        tvPeriod = findViewById(R.id.tvPeriod);
        recurringMode = getIntent().getBooleanExtra(EXTRA_RECURRING, false)
                || getIntent().hasExtra(EXTRA_RECURRING_ID);
        recurringId = getIntent().getLongExtra(EXTRA_RECURRING_ID, -1);

        editId = getIntent().getLongExtra(EXTRA_ID, -1);
        Record editing = !recurringMode && editId >= 0 ? db.queryById(editId) : null;
        if (editing != null) {
            originalCategory = editing.category;
            loadForEdit(editing);
        } else {
            editId = -1;
            setType(true);
        }

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnSave).setOnClickListener(v -> save(false));

        View btnSaveAgain = findViewById(R.id.btnSaveAgain);
        btnSaveAgain.setVisibility(editId >= 0 || recurringMode ? View.GONE : View.VISIBLE);
        btnSaveAgain.setOnClickListener(v -> save(true));

        View btnDelete = findViewById(R.id.btnDelete);
        btnDelete.setVisibility(editId >= 0 ? View.VISIBLE : View.GONE);
        btnDelete.setOnClickListener(v -> confirmDelete());

        if (recurringMode) setupRecurring();

        buildNoteChips();
    }

    /** 周期记账模式：日期变「下次记账日」，多一行周期设置，保存写入规则表 */
    private void setupRecurring() {
        ((TextView) findViewById(R.id.tvNavTitle)).setText("周期记账");
        ((TextView) findViewById(R.id.tvDateLabel)).setText("下次记账日");
        findViewById(R.id.dividerPeriod).setVisibility(View.VISIBLE);
        View row = findViewById(R.id.rowPeriod);
        row.setVisibility(View.VISIBLE);
        row.setOnClickListener(this::showPeriodMenu);

        Recurring rule = recurringId >= 0 ? db.queryRecurringById(recurringId) : null;
        if (rule != null) {
            selectedCurrency = rule.currency;
            updateCurrencyPill();
            etAmount.setText(String.format(Locale.CHINA, "%.2f", rule.amount));
            etNote.setText(rule.note == null ? "" : rule.note);
            tvDate.setText(rule.nextDate);
            try {
                cal.setTime(fmt.parse(rule.nextDate));
            } catch (Exception ignored) {
            }
            setType(rule.type == 0);
            selectedCategory = rule.category;
            highlight();
            periodType = rule.periodType;
            if (periodType == Recurring.PERIOD_DAYS) periodDays = rule.periodValue;

            View btnDelete = findViewById(R.id.btnDelete);
            btnDelete.setVisibility(View.VISIBLE);
            btnDelete.setOnClickListener(v ->
                    Sheets.confirm(this, "删除这条周期记账？\n已生成的记录会保留。", "删除", () -> {
                        db.deleteRecurring(recurringId);
                        Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                        finish();
                    }));
        } else {
            recurringId = -1;
        }
        updatePeriodLabel();
    }

    private void showPeriodMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 0, 0, "每月（记账日几号就每月几号）");
        menu.getMenu().add(0, 1, 1, "每周（7 天）");
        menu.getMenu().add(0, 2, 2, "每两周（14 天）");
        menu.getMenu().add(0, 3, 3, "每 28 天");
        menu.getMenu().add(0, 4, 4, "自定义间隔天数…");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 0: periodType = Recurring.PERIOD_MONTHLY; break;
                case 1: periodType = Recurring.PERIOD_DAYS; periodDays = 7; break;
                case 2: periodType = Recurring.PERIOD_DAYS; periodDays = 14; break;
                case 3: periodType = Recurring.PERIOD_DAYS; periodDays = 28; break;
                case 4: askCustomDays(); return true;
            }
            updatePeriodLabel();
            return true;
        });
        menu.show();
    }

    /** 自定义间隔天数的输入面板 */
    private void askCustomDays() {
        LinearLayout root = Sheets.container(this);

        TextView title = new TextView(this);
        title.setText("每隔多少天记一笔？");
        title.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        title.setTextSize(16);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        EditText et = new EditText(this);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        et.setHint("如 28");
        et.setText(String.valueOf(periodDays));
        et.setSelectAllOnFocus(true);
        et.setTextSize(22);
        et.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = dp(10);
        et.setLayoutParams(elp);
        root.addView(et);

        TextView ok = new TextView(this);
        ok.setText("确定");
        ok.setTextColor(0xFFFFFFFF);
        ok.setTextSize(16);
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
            int n;
            try {
                n = Integer.parseInt(et.getText().toString().trim());
            } catch (NumberFormatException e) {
                n = 0;
            }
            if (n < 1 || n > 365) {
                Toast.makeText(this, "请输入 1~365 的天数", Toast.LENGTH_SHORT).show();
                return;
            }
            periodType = Recurring.PERIOD_DAYS;
            periodDays = n;
            updatePeriodLabel();
            d.dismiss();
        });
    }

    private void updatePeriodLabel() {
        if (tvPeriod == null) return;
        tvPeriod.setText(periodType == Recurring.PERIOD_MONTHLY
                ? "每月 " + cal.get(Calendar.DAY_OF_MONTH) + " 日"
                : "每 " + periodDays + " 天");
    }

    /** 最近备注快捷 chips */
    private void buildNoteChips() {
        LinearLayout box = findViewById(R.id.noteSuggest);
        java.util.List<String> notes = db.recentNotes(6);
        if (notes.isEmpty()) return;
        findViewById(R.id.noteSuggestScroll).setVisibility(View.VISIBLE);
        for (String n : notes) {
            TextView chip = new TextView(this);
            chip.setText(n);
            chip.setTextSize(13);
            chip.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
            chip.setBackgroundResource(R.drawable.chip_bg);
            chip.setPadding(dp(12), dp(6), dp(12), dp(6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> etNote.setText(n));
            box.addView(chip);
        }
    }

    private void loadForEdit(Record r) {
        ((TextView) findViewById(R.id.tvNavTitle)).setText("编辑");
        selectedCurrency = r.currency == null ? Currencies.DEFAULT : r.currency;
        updateCurrencyPill();
        etAmount.setText(String.format(Locale.CHINA, "%.2f", r.amount));
        etNote.setText(r.note == null ? "" : r.note);
        tvDate.setText(r.date);
        setType(r.type == 0);
        selectedCategory = r.category;
        highlight();
    }

    private void confirmDelete() {
        Sheets.confirm(this, "删除这条记录？", "删除", () -> {
            db.delete(editId);
            WidgetProvider.refresh(this);
            Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    private void setType(boolean isExpense) {
        expense = isExpense;
        int primary = ContextCompat.getColor(this, R.color.textPrimary);
        int secondary = ContextCompat.getColor(this, R.color.textSecondary);
        int vpad = dp(8);
        segExpense.setBackgroundResource(expense ? R.drawable.seg_thumb : 0);
        segIncome.setBackgroundResource(expense ? 0 : R.drawable.seg_thumb);
        segExpense.setPadding(0, vpad, 0, vpad);
        segIncome.setPadding(0, vpad, 0, vpad);
        segExpense.setTextColor(expense ? primary : secondary);
        segIncome.setTextColor(expense ? secondary : primary);
        segExpense.setTypeface(null, expense ? Typeface.BOLD : Typeface.NORMAL);
        segIncome.setTypeface(null, expense ? Typeface.NORMAL : Typeface.BOLD);
        buildCategories();
    }

    private void showCurrencyMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        String[] codes = Currencies.codes();
        for (int i = 0; i < codes.length; i++) {
            menu.getMenu().add(0, i, i, codes[i] + "  " + Currencies.symbol(codes[i]));
        }
        menu.setOnMenuItemClickListener(item -> {
            selectedCurrency = codes[item.getItemId()];
            updateCurrencyPill();
            return true;
        });
        menu.show();
    }

    private void updateCurrencyPill() {
        pillCurrency.setText(selectedCurrency + " " + Currencies.symbol(selectedCurrency));
    }

    private static final String ADD_TAG = "__add__";

    private void buildCategories() {
        grid.removeAllViews();
        selectedCategory = null;
        int type = expense ? 0 : 1;
        String[] presets = expense ? Categories.EXPENSE : Categories.INCOME;  // 末尾是「其他」
        java.util.List<CustomCats.Cat> customs = CustomCats.load(this, type);

        // 顺序：预设（去掉「其他」）+ 自定义 + 「其他」，再加「+ 自定义」瓦片
        for (String c : presets) {
            if ("其他".equals(c)) continue;
            grid.addView(buildCatItem(c, false));
        }
        for (CustomCats.Cat cc : customs) {
            grid.addView(buildCatItem(cc.name, true));
        }
        grid.addView(buildCatItem("其他", false));

        if (customs.size() < CustomCats.MAX_PER_TYPE) {
            grid.addView(buildAddTile());
        }
    }

    private View buildCatItem(final String c, final boolean custom) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(0, dp(10), 0, dp(10));
        col.setTag(c);

        ImageView icon = new ImageView(this);
        icon.setImageResource(CatStyle.icon(c));
        icon.setBackground(CatStyle.circleBg(c, getResources().getDisplayMetrics().density, false));
        int p = dp(12);
        icon.setPadding(p, p, p, p);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(50), dp(50));
        icon.setLayoutParams(ip);
        col.addView(icon);

        TextView name = new TextView(this);
        name.setText(c);
        name.setTextSize(12);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        np.topMargin = dp(6);
        name.setLayoutParams(np);
        col.addView(name);

        col.setLayoutParams(gridCell());

        col.setOnClickListener(v -> {
            selectedCategory = c;
            highlight();
        });
        if (custom) {
            col.setOnLongClickListener(v -> {
                Sheets.confirm(this, "删除自定义分类「" + c + "」？\n已记录的这类账目会保留。", "删除", () -> {
                    CustomCats.remove(this, c, expense ? 0 : 1);
                    buildCategories();
                });
                return true;
            });
        }
        applyCatItem(col, c, false);
        return col;
    }

    /** 「+ 自定义」瓦片：虚线圆 + 加号 */
    private View buildAddTile() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(0, dp(10), 0, dp(10));
        col.setTag(ADD_TAG);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_cat_add);
        int gray = 0xFF8A8A8E;
        icon.setImageTintList(ColorStateList.valueOf(gray));
        android.graphics.drawable.GradientDrawable dash = new android.graphics.drawable.GradientDrawable();
        dash.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dash.setColor(0x00000000);
        float d = getResources().getDisplayMetrics().density;
        dash.setStroke(Math.max(1, Math.round(d)), (0x55 << 24) | (gray & 0x00FFFFFF), 4 * d, 3 * d);
        icon.setBackground(dash);
        int p = dp(12);
        icon.setPadding(p, p, p, p);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(50), dp(50)));
        col.addView(icon);

        TextView name = new TextView(this);
        name.setText("自定义");
        name.setTextSize(12);
        name.setGravity(Gravity.CENTER);
        name.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        np.topMargin = dp(6);
        name.setLayoutParams(np);
        col.addView(name);

        col.setLayoutParams(gridCell());
        col.setOnClickListener(v -> showAddCategorySheet(expense ? 0 : 1));
        return col;
    }

    private GridLayout.LayoutParams gridCell() {
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = 0;
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
        return lp;
    }

    private void highlight() {
        for (int i = 0; i < grid.getChildCount(); i++) {
            LinearLayout col = (LinearLayout) grid.getChildAt(i);
            if (ADD_TAG.equals(col.getTag())) continue;
            String c = String.valueOf(col.getTag());
            applyCatItem(col, c, c.equals(selectedCategory));
        }
    }

    private void applyCatItem(LinearLayout col, String c, boolean sel) {
        ImageView icon = (ImageView) col.getChildAt(0);
        TextView name = (TextView) col.getChildAt(1);
        int color = CatStyle.color(c);
        float density = getResources().getDisplayMetrics().density;
        if (sel) {
            icon.setBackground(CatStyle.circleBg(c, density, true));
            icon.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
            name.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
            name.setTypeface(null, Typeface.BOLD);
        } else {
            icon.setBackground(CatStyle.circleBg(c, density, false));
            icon.setImageTintList(ColorStateList.valueOf(color));
            name.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
            name.setTypeface(null, Typeface.NORMAL);
        }
    }

    /** 新建自定义分类的底部面板：输入名称 + 从 5 个图标里选一个 */
    private void showAddCategorySheet(int type) {
        LinearLayout root = Sheets.container(this);

        TextView title = new TextView(this);
        title.setText(type == 0 ? "新建支出分类" : "新建收入分类");
        title.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        title.setTextSize(16);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        EditText et = new EditText(this);
        et.setHint("分类名称（最多 4 字）");
        et.setSingleLine(true);
        et.setTextSize(16);
        et.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4)});
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = dp(14);
        et.setLayoutParams(elp);
        root.addView(et);

        TextView pick = new TextView(this);
        pick.setText("选择图标");
        pick.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        pick.setTextSize(13);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = dp(18);
        plp.bottomMargin = dp(10);
        pick.setLayoutParams(plp);
        root.addView(pick);

        LinearLayout iconRow = new LinearLayout(this);
        iconRow.setOrientation(LinearLayout.HORIZONTAL);
        iconRow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(iconRow);

        final String[] keys = CustomCats.iconKeysFor(type);
        final String[] selKey = {keys[0]};
        final ImageView[] opts = new ImageView[keys.length];
        for (int i = 0; i < keys.length; i++) {
            final String key = keys[i];
            LinearLayout cell = new LinearLayout(this);
            cell.setGravity(Gravity.CENTER);
            cell.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            ImageView iv = new ImageView(this);
            iv.setImageResource(CustomCats.iconRes(key));
            int p = dp(12);
            iv.setPadding(p, p, p, p);
            iv.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
            opts[i] = iv;
            cell.addView(iv);
            iconRow.addView(cell);

            iv.setOnClickListener(v -> {
                selKey[0] = key;
                for (int j = 0; j < keys.length; j++) {
                    styleOption(opts[j], CustomCats.iconColor(keys[j]), keys[j].equals(selKey[0]));
                }
            });
        }
        for (int i = 0; i < keys.length; i++) {
            styleOption(opts[i], CustomCats.iconColor(keys[i]), keys[i].equals(selKey[0]));
        }

        TextView ok = new TextView(this);
        ok.setText("保存");
        ok.setTextColor(0xFFFFFFFF);
        ok.setTextSize(16);
        ok.setTypeface(null, Typeface.BOLD);
        ok.setGravity(Gravity.CENTER);
        ok.setBackgroundResource(R.drawable.btn_primary);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        olp.topMargin = dp(20);
        ok.setLayoutParams(olp);
        root.addView(ok);

        com.google.android.material.bottomsheet.BottomSheetDialog dlg = Sheets.show(this, root);
        ok.setOnClickListener(v -> {
            String err = CustomCats.add(this, et.getText().toString(), type, selKey[0]);
            if (err != null) {
                Toast.makeText(this, err, Toast.LENGTH_SHORT).show();
                return;
            }
            CustomCats.applyToCatStyle(this);
            dlg.dismiss();
            String newName = et.getText().toString().trim();
            buildCategories();
            selectedCategory = newName;
            highlight();
        });
    }

    /** 图标选项样式：选中=实心主色底 + 白图标；未选=淡色底 + 同色描边 + 同色图标 */
    private void styleOption(ImageView iv, int color, boolean sel) {
        float density = getResources().getDisplayMetrics().density;
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        if (sel) {
            g.setColor(color);
            iv.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
        } else {
            g.setColor((0x24 << 24) | (color & 0x00FFFFFF));
            g.setStroke(Math.max(1, Math.round(density)), (0x38 << 24) | (color & 0x00FFFFFF));
            iv.setImageTintList(ColorStateList.valueOf(color));
        }
        iv.setBackground(g);
    }

    private void pickDate() {
        DatePickerDialog d = new DatePickerDialog(this, (view, year, month, day) -> {
            cal.set(year, month, day);
            tvDate.setText(fmt.format(cal.getTime()));
            if (recurringMode) updatePeriodLabel();
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH));
        // 账本从 2026-07 开始，不能选更早的日期
        Calendar min = Calendar.getInstance();
        min.clear();
        min.set(2026, Calendar.JULY, 1);
        d.getDatePicker().setMinDate(min.getTimeInMillis());
        d.show();
    }

    private void save(boolean stay) {
        String amtStr = etAmount.getText().toString().trim();
        if (amtStr.isEmpty()) {
            Toast.makeText(this, "请输入金额", Toast.LENGTH_SHORT).show();
            return;
        }
        double amount;
        try {
            amount = Double.parseDouble(amtStr);
        } catch (NumberFormatException e) {
            Toast.makeText(this, "金额格式不对", Toast.LENGTH_SHORT).show();
            return;
        }
        if (amount <= 0) {
            Toast.makeText(this, "金额要大于 0", Toast.LENGTH_SHORT).show();
            return;
        }
        if (selectedCategory == null) {
            Toast.makeText(this, "请选择一个分类", Toast.LENGTH_SHORT).show();
            return;
        }

        if (recurringMode) {
            saveRecurring(amount);
            return;
        }

        Record r = new Record();
        r.id = editId;
        r.type = expense ? 0 : 1;
        r.amount = amount;
        r.category = selectedCategory;
        r.note = etNote.getText().toString().trim();
        r.date = tvDate.getText().toString();
        r.currency = selectedCurrency;
        r.source = 0;
        if (editId >= 0) {
            db.update(r);
        } else {
            db.insert(r);
        }

        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_CURRENCY, selectedCurrency).apply();
        WidgetProvider.refresh(this);

        if (stay) {
            // 清空金额/备注/分类，保留类型、货币、日期，直接记下一笔
            etAmount.setText("");
            etNote.setText("");
            selectedCategory = null;
            highlight();
            etAmount.requestFocus();
            Toast.makeText(this, "已保存，继续记", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
            // 用户手动纠正了某条支出的分类 —— 这是唯一能确定「这个商户该归哪类」的时刻，
            // 顺手问一句要不要记进个人商户表，下次同一商户就不会再错。
            if (offerRememberRule(r)) return;    // 面板关闭时再 finish
            finish();
        }
    }

    /**
     * 编辑支出记录且改动了分类时，弹面板问是否记成商户规则。
     * @return true 表示弹了面板（调用方不要立刻 finish）
     */
    private boolean offerRememberRule(Record r) {
        if (editId < 0 || r.type != 0) return false;
        if (originalCategory == null || originalCategory.equals(r.category)) return false;
        String guess = MerchantRules.guessKeyword(r.note);
        if (guess.isEmpty()) return false;

        LinearLayout root = Sheets.container(this);

        TextView title = new TextView(this);
        title.setText("以后都归到「" + r.category + "」？");
        title.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        title.setTextSize(17);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(6), 0, dp(6));
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("备注里含有下面这个词的记录会自动归类。可以改成更短、更准的写法。");
        hint.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(4), 0, dp(4), dp(14));
        root.addView(hint);

        EditText et = new EditText(this);
        et.setText(guess);
        et.setSingleLine(true);
        et.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        et.setTextSize(16);
        et.setGravity(Gravity.CENTER);
        et.setBackgroundResource(R.drawable.bg_card);
        et.setPadding(dp(16), dp(13), dp(16), dp(13));
        et.setSelection(guess.length());
        root.addView(et);

        TextView ok = new TextView(this);
        ok.setText("记住");
        ok.setTextColor(0xFFFFFFFF);
        ok.setTextSize(16);
        ok.setTypeface(null, android.graphics.Typeface.BOLD);
        ok.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(ContextCompat.getColor(this, R.color.accent));
        bg.setCornerRadius(dp(14));
        ok.setBackground(bg);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        olp.topMargin = dp(14);
        ok.setLayoutParams(olp);
        root.addView(ok);

        TextView skip = new TextView(this);
        skip.setText("这次不用");
        skip.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        skip.setTextSize(15);
        skip.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        slp.topMargin = dp(6);
        skip.setLayoutParams(slp);
        root.addView(skip);

        com.google.android.material.bottomsheet.BottomSheetDialog d = Sheets.show(this, root);
        d.setOnDismissListener(x -> finish());
        ok.setOnClickListener(v -> {
            String kw = et.getText().toString().trim();
            boolean saved = MerchantRules.appendUser(this, kw, r.category);
            Toast.makeText(this,
                    saved ? "已记住：" + kw + " → " + r.category : "没有写入（关键词为空或已存在）",
                    Toast.LENGTH_SHORT).show();
            d.dismiss();
        });
        skip.setOnClickListener(v -> d.dismiss());
        return true;
    }

    /** 保存周期记账规则；到期的记录由首页打开时统一生成 */
    private void saveRecurring(double amount) {
        Recurring r = new Recurring();
        r.id = recurringId;
        r.type = expense ? 0 : 1;
        r.amount = amount;
        r.currency = selectedCurrency;
        r.category = selectedCategory;
        r.note = etNote.getText().toString().trim();
        r.periodType = periodType;
        r.periodValue = periodType == Recurring.PERIOD_MONTHLY
                ? cal.get(Calendar.DAY_OF_MONTH) : periodDays;
        r.nextDate = tvDate.getText().toString();
        r.enabled = true;
        if (recurringId >= 0) {
            db.updateRecurring(r);
        } else {
            db.insertRecurring(r);
        }
        Toast.makeText(this, "已保存，" + r.periodLabel() + "自动记一笔", Toast.LENGTH_SHORT).show();
        finish();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}

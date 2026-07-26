package com.example.jizhang;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class AutoSettingsActivity extends AppCompatActivity {

    private static final int REQ_EXPORT = 11;
    private static final int REQ_IMPORT = 12;
    private static final int REQ_BACKUP_DIR = 13;

    private TextView tvPermStatus, tvBackupInfo;
    private Switch swEnabled, swBackup;
    private LinearLayout sourceList, recurringList;
    private SharedPreferences prefs;
    private DbHelper db;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auto);

        prefs = getSharedPreferences(PaymentNotificationListener.PREFS, MODE_PRIVATE);
        db = new DbHelper(this);
        tvPermStatus = findViewById(R.id.tvPermStatus);
        tvBackupInfo = findViewById(R.id.tvBackupInfo);
        swEnabled = findViewById(R.id.swEnabled);
        swBackup = findViewById(R.id.swBackup);
        sourceList = findViewById(R.id.sourceList);
        recurringList = findViewById(R.id.recurringList);

        setupSmartCategory();

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnAddRecurring).setOnClickListener(v -> {
            Intent it = new Intent(this, AddActivity.class);
            it.putExtra(AddActivity.EXTRA_RECURRING, true);
            startActivity(it);
        });

        // 总览大数字：结余 / 总支出（同时作用于首页和小组件）
        TextView tvMetric = findViewById(R.id.tvMetricValue);
        tvMetric.setText(prefs.getBoolean(MainActivity.KEY_BIG_EXPENSE, false) ? "总支出" : "结余");
        findViewById(R.id.rowMetric).setOnClickListener(v -> {
            android.widget.PopupMenu menu = new android.widget.PopupMenu(this, tvMetric);
            menu.getMenu().add(0, 0, 0, "结余（收入−支出）");
            menu.getMenu().add(0, 1, 1, "总支出");
            menu.setOnMenuItemClickListener(item -> {
                boolean be = item.getItemId() == 1;
                prefs.edit().putBoolean(MainActivity.KEY_BIG_EXPENSE, be).apply();
                tvMetric.setText(be ? "总支出" : "结余");
                WidgetProvider.refresh(this);
                return true;
            });
            menu.show();
        });

        // 主题：跟随系统 / 浅色 / 深色
        TextView tvTheme = findViewById(R.id.tvThemeValue);
        tvTheme.setText(Theme.label(Theme.saved(this)));
        findViewById(R.id.rowTheme).setOnClickListener(v -> {
            android.widget.PopupMenu menu = new android.widget.PopupMenu(this, tvTheme);
            menu.getMenu().add(0, Theme.FOLLOW, 0, "跟随系统");
            menu.getMenu().add(0, Theme.LIGHT, 1, "浅色");
            menu.getMenu().add(0, Theme.DARK, 2, "深色");
            menu.setOnMenuItemClickListener(item -> {
                int m = item.getItemId();
                prefs.edit().putInt(Theme.KEY, m).apply();
                tvTheme.setText(Theme.label(m));
                Theme.apply(m);   // 触发界面重建切换明暗
                return true;
            });
            menu.show();
        });

        iosSwitch(swBackup);
        swBackup.setChecked(Backup.backupDir(this) != null);
        swBackup.setOnCheckedChangeListener((b, checked) -> {
            if (!b.isPressed()) return;   // 只响应用户手动切换
            if (checked) {
                startActivityForResult(
                        new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_BACKUP_DIR);
            } else {
                prefs.edit().remove(Backup.KEY_BACKUP_URI)
                        .remove(Backup.KEY_BACKUP_LAST).apply();
                updateBackupInfo();
            }
        });

        findViewById(R.id.btnExport).setOnClickListener(v -> {
            Intent it = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            it.addCategory(Intent.CATEGORY_OPENABLE);
            it.setType("text/csv");
            String today = new java.text.SimpleDateFormat("yyyyMMdd",
                    java.util.Locale.CHINA).format(new java.util.Date());
            it.putExtra(Intent.EXTRA_TITLE, "记账本-" + today + ".csv");
            startActivityForResult(it, REQ_EXPORT);
        });
        findViewById(R.id.btnImport).setOnClickListener(v -> {
            Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            it.addCategory(Intent.CATEGORY_OPENABLE);
            it.setType("*/*");
            startActivityForResult(it, REQ_IMPORT);
        });

        findViewById(R.id.btnGrant).setOnClickListener(v -> {
            try {
                startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        });

        iosSwitch(swEnabled);
        swEnabled.setChecked(prefs.getBoolean(PaymentNotificationListener.KEY_ENABLED, false));
        swEnabled.setOnCheckedChangeListener((b, checked) -> {
            if (checked && !hasNotificationAccess()) {
                Toast.makeText(this, "请先完成第一步：授予通知使用权", Toast.LENGTH_LONG).show();
                swEnabled.setChecked(false);
                return;
            }
            prefs.edit().putBoolean(PaymentNotificationListener.KEY_ENABLED, checked).apply();
        });

        buildSourceToggles();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSmartSummary();
        boolean granted = hasNotificationAccess();
        tvPermStatus.setText(granted ? "状态：已授权 ✓" : "状态：未授权");
        tvPermStatus.setTextColor(ContextCompat.getColor(this,
                granted ? R.color.income : R.color.expense));
        buildRecurringRows();
        updateBackupInfo();
    }

    /** 周期记账规则列表：周期+分类+金额，开关暂停，点击编辑 */
    private void buildRecurringRows() {
        recurringList.removeAllViews();
        java.util.List<Recurring> rules = db.queryRecurring();
        for (Recurring r : rules) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(10), dp(16), dp(10));
            android.util.TypedValue tv = new android.util.TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
            row.setBackgroundResource(tv.resourceId);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView title = new TextView(this);
            title.setText(String.format(java.util.Locale.CHINA, "%s · %s%s%.2f",
                    r.category, r.type == 0 ? "-" : "+",
                    Currencies.symbol(r.currency), r.amount));
            title.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
            title.setTextSize(16);
            col.addView(title);

            TextView sub = new TextView(this);
            String note = r.note == null || r.note.isEmpty() ? "" : " · " + r.note;
            sub.setText(r.periodLabel() + " · 下次 " + r.nextDate + note);
            sub.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
            sub.setTextSize(12);
            col.addView(sub);
            row.addView(col);

            Switch sw = new Switch(this);
            iosSwitch(sw);
            sw.setChecked(r.enabled);
            sw.setOnCheckedChangeListener((b, checked) -> {
                if (!b.isPressed()) return;
                r.enabled = checked;
                if (checked) {
                    // 重新启用时只把到期日推到未来，不把停用期间的都补上
                    String today = new java.text.SimpleDateFormat("yyyy-MM-dd",
                            java.util.Locale.CHINA).format(new java.util.Date());
                    db.fastForwardRecurring(r, today);
                } else {
                    db.updateRecurring(r);
                }
                buildRecurringRows();
            });
            row.addView(sw);

            row.setOnClickListener(v -> {
                Intent it = new Intent(this, AddActivity.class);
                it.putExtra(AddActivity.EXTRA_RECURRING_ID, r.id);
                startActivity(it);
            });

            recurringList.addView(row);
            recurringList.addView(divider());
        }
    }

    private void updateBackupInfo() {
        boolean on = Backup.backupDir(this) != null;
        swBackup.setChecked(on);
        if (!on) {
            tvBackupInfo.setText("每天自动导出到所选文件夹");
        } else {
            String last = Backup.lastBackupDay(this);
            tvBackupInfo.setText(last == null ? "已开启，还未备份过" : "上次备份：" + last);
        }
    }

    private void buildSourceToggles() {
        sourceList.removeAllViews();
        String[] names = {"支付宝", "微信", "云闪付"};
        String[] pkgs = PaymentNotificationListener.SUPPORTED_PKGS;
        int n = Math.min(names.length, pkgs.length);
        for (int i = 0; i < n; i++) {
            final String pkg = pkgs[i];
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(10), dp(16), dp(10));
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView tv = new TextView(this);
            tv.setText(names[i]);
            tv.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
            tv.setTextSize(16);
            tv.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(tv);

            Switch sw = new Switch(this);
            iosSwitch(sw);
            String key = PaymentNotificationListener.KEY_PKG_PREFIX + pkg;
            sw.setChecked(prefs.getBoolean(key, true));
            sw.setOnCheckedChangeListener((b, checked) ->
                    prefs.edit().putBoolean(key, checked).apply());
            row.addView(sw);

            sourceList.addView(row);
            if (i < n - 1) sourceList.addView(divider());
        }

        // 调试构建才有的入口：真实通知只能由支付 App 自己发出，没法在开发机上按需复现
        // （NotificationListenerService 按来源包名过滤，adb 发的通知归 shell 包）。
        // 这一项把一条固定的通知文本喂给 PaymentNotificationListener.ingest——和真实
        // 回调同一个方法，所以它演示的就是线上行为。release 构建里 BuildConfig.DEBUG
        // 是编译期常量 false，R8 会把整个分支连同下面的方法一起删掉。
        if (BuildConfig.DEBUG) {
            sourceList.addView(divider());
            sourceList.addView(debugSimulateRow());
        }
    }

    /** 见上：仅调试构建可见。 */
    private View debugSimulateRow() {
        TextView tv = new TextView(this);
        tv.setText("模拟一条支付通知（调试）");
        tv.setTextColor(ContextCompat.getColor(this, R.color.accent));
        tv.setTextSize(16);
        tv.setPadding(dp(16), dp(12), dp(16), dp(12));
        tv.setOnClickListener(v -> {
            // 支付宝付款通知的真实形状。金额 26.80 后面故意跟一个「账户余额 3120.55」：
            // PaymentParser 应当记 26.80，而不是把余额当成本次消费。
            Record r = PaymentNotificationListener.ingest(
                    this,
                    "com.eg.android.AlipayGphone",
                    "付款成功",
                    "你已成功付款 26.80元，账户余额 3120.55元。商家：星巴克咖啡（人民广场店）");
            if (r == null) {
                Toast.makeText(this, "解析失败", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this,
                        "已生成：" + r.category + " " + r.currency + " " + r.amount,
                        Toast.LENGTH_LONG).show();
            }
        });
        return tv;
    }

    /** iOS 风格开关：开=苔绿轨道，关=浅灰轨道，白色圆钮。 */
    private void iosSwitch(Switch sw) {
        int[][] states = {{android.R.attr.state_checked}, {}};
        sw.setTrackTintList(new ColorStateList(states,
                new int[]{ContextCompat.getColor(this, R.color.income), 0x33787880}));
        sw.setThumbTintList(new ColorStateList(states,
                new int[]{0xFFFFFFFF, 0xFFFFFFFF}));
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

    // ---------- CSV 导出 / 导入 ----------

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            if (requestCode == REQ_BACKUP_DIR) updateBackupInfo();   // 取消选择，开关弹回
            return;
        }
        android.net.Uri uri = data.getData();
        if (requestCode == REQ_EXPORT) {
            exportCsv(uri);
        } else if (requestCode == REQ_IMPORT) {
            importCsv(uri);
        } else if (requestCode == REQ_BACKUP_DIR) {
            setupBackupDir(uri);
        }
    }

    /** 记住备份文件夹并立即备份一份 */
    private void setupBackupDir(android.net.Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            prefs.edit().putString(Backup.KEY_BACKUP_URI, uri.toString()).apply();
            Backup.backupNow(this, uri);
            String today = new java.text.SimpleDateFormat("yyyy-MM-dd",
                    java.util.Locale.CHINA).format(new java.util.Date());
            prefs.edit().putString(Backup.KEY_BACKUP_LAST, today).apply();
            Toast.makeText(this, "自动备份已开启，刚备份了一份", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            prefs.edit().remove(Backup.KEY_BACKUP_URI).apply();
            Toast.makeText(this, "开启失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        updateBackupInfo();
    }

    private void exportCsv(android.net.Uri uri) {
        try (java.io.OutputStream os = getContentResolver().openOutputStream(uri, "wt")) {
            int n = Backup.writeCsv(db, os);
            Toast.makeText(this, "已导出 " + n + " 笔", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "导出失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void importCsv(android.net.Uri uri) {
        int ok = 0, bad = 0, dup = 0, autoCat = 0, bank = 0;
        java.util.Set<String> existing = db.allDedupeKeys();
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(getContentResolver().openInputStream(uri),
                        java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("\ufeff")) line = line.substring(1);
                line = line.trim();
                if (line.isEmpty() || line.startsWith("类型")) continue;
                java.util.List<String> f = parseCsvLine(line);
                try {
                    Record r = isBankRow(f) ? parseBankRow(f) : parseOwnRow(f);
                    if (r == null) { bad++; continue; }
                    if (isBankRow(f)) bank++;

                    // 分类为空或「待分类」时查一次商户表。银行导出的流水没有分类列，而商户表
                    // 恰恰是拿这类英文商户描述调出来的——不在这里接上，它就只能服务通知路径。
                    // 只查表不调模型：导入是批量的，逐条走网络不合适。收入不查，商户表只有支出分类。
                    if (r.type == 0 && (r.category == null || r.category.trim().isEmpty()
                            || CategoryClassifier.UNCATEGORIZED.equals(r.category))) {
                        String hit = CategoryClassifier.byRules(this, r.note);
                        r.category = hit != null ? hit : CategoryClassifier.UNCATEGORIZED;
                        if (hit != null) autoCat++;
                    }
                    if (r.category == null || r.category.trim().isEmpty()) {
                        r.category = CategoryClassifier.UNCATEGORIZED;
                    }
                    if (r.amount <= 0 || !r.date.matches("\\d{4}-\\d{2}-\\d{2}")) { bad++; continue; }
                    // 已有完全相同的记录（重复导入同一份备份/有重叠的流水）就跳过，不追加
                    if (existing.contains(DbHelper.dedupeKey(r))) { dup++; continue; }
                    db.insert(r);
                    existing.add(DbHelper.dedupeKey(r));   // 同一份文件里的重复行也要挡掉
                    ok++;
                } catch (Exception e) {
                    bad++;
                }
            }
            WidgetProvider.refresh(this);
            Toast.makeText(this, "已导入 " + ok + " 笔"
                            + (bank > 0 ? "（识别为银行流水）" : "")
                            + (autoCat > 0 ? "，其中 " + autoCat + " 笔按商户表自动分类" : "")
                            + (dup > 0 ? "，跳过重复 " + dup + " 笔" : "")
                            + (bad > 0 ? "，坏行 " + bad : ""),
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "导入失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 解析一行 CSV（支持引号字段） */
    private static java.util.List<String> parseCsvLine(String line) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQuote) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuote = false;
                    }
                } else {
                    cur.append(ch);
                }
            } else {
                if (ch == '"') {
                    inQuote = true;
                } else if (ch == ',') {
                    out.add(cur.toString());
                    cur.setLength(0);
                } else {
                    cur.append(ch);
                }
            }
        }
        out.add(cur.toString());
        return out;
    }

    private boolean hasNotificationAccess() {
        String flat = Settings.Secure.getString(
                getContentResolver(), "enabled_notification_listeners");
        if (TextUtils.isEmpty(flat)) return false;
        String me = getPackageName();
        for (String name : flat.split(":")) {
            if (name != null && name.contains(me)) return true;
        }
        return false;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ==================== 智能分类 ====================

    /** 详情在 SmartCategoryActivity，这里只显示摘要并跳转 */
    /**
     * 是否是银行导出的流水行。CommBank 的格式是 4 列、无表头：
     * {@code 22/07/2026,"-3.00","商户描述 Card xx8782 Value Date: 18/07/2026","+79.95"}
     * 判别特征取「4 列 + 首列是 dd/MM/yyyy」，与本 App 自己导出的 7 列格式不会混淆。
     */
    private boolean isBankRow(java.util.List<String> f) {
        return f.size() == 4 && f.get(0).trim().matches("\\d{2}/\\d{2}/\\d{4}");
    }

    /**
     * 解析银行流水行。金额带正负号：负数是支出，正数是收入。
     *
     * 备注里会去掉 "Card xx8782"、"Value Date: dd/MM/yyyy" 这类固定噪音——它们对分类
     * 和阅读都没有价值，留着只会让列表里的备注又长又乱。商户名本身一个字不动。
     */
    private Record parseBankRow(java.util.List<String> f) {
        String[] d = f.get(0).trim().split("/");
        if (d.length != 3) return null;
        double amt = Double.parseDouble(f.get(1).replace("+", "").replace(",", "").trim());

        Record r = new Record();
        r.type = amt < 0 ? 0 : 1;
        r.amount = Math.abs(amt);
        r.date = d[2] + "-" + d[1] + "-" + d[0];      // dd/MM/yyyy -> yyyy-MM-dd
        r.note = f.get(2)
                .replaceAll("(?i)Card xx\\d+", "")
                .replaceAll("(?i)Value Date:\\s*\\d{2}/\\d{2}/\\d{4}", "")
                .replaceAll("\\s{2,}", " ")
                .trim();
        r.currency = "AUD";                            // CommBank 账户是澳元户口
        r.category = CategoryClassifier.UNCATEGORIZED; // 交给下面的商户表；收入保持待分类
        r.source = 0;
        return r;
    }

    /** 解析本 App 自己导出的 7 列格式 */
    private Record parseOwnRow(java.util.List<String> f) {
        if (f.size() < 6) return null;
        Record r = new Record();
        r.type = "收入".equals(f.get(0)) ? 1 : 0;
        r.amount = Double.parseDouble(f.get(1));
        r.category = f.get(2);
        r.note = f.get(3);
        r.date = f.get(4);
        r.currency = f.get(5);
        r.source = f.size() > 6 ? Backup.sourceOf(f.get(6)) : 0;
        return r;
    }

    private void setupSmartCategory() {
        // 二级页里只有「导入商户表」和「本地模型」两件事。国内版两者都没有，
        // 那个页面就是空的——整段隐藏，商户表在背后默默工作即可。
        boolean useful = Flavor.LOCAL_LLM || Flavor.IMPORT_MERCHANT_FILE;
        findViewById(R.id.secSmartCategory).setVisibility(useful ? View.VISIBLE : View.GONE);
        findViewById(R.id.rowSmartCategory).setVisibility(useful ? View.VISIBLE : View.GONE);
        if (!useful) return;
        findViewById(R.id.rowSmartCategory).setOnClickListener(
                v -> startActivity(new Intent(this, SmartCategoryActivity.class)));
    }

    /** 摘要要在从二级页返回后刷新，所以放在 onResume 里调 */
    private void refreshSmartSummary() {
        if (!Flavor.LOCAL_LLM && !Flavor.IMPORT_MERCHANT_FILE) return;
        TextView tv = findViewById(R.id.tvSmartSummary);
        int user = MerchantRules.userSize(this);
        tv.setText(user > 0
                ? "内置 " + MerchantRules.size(this) + " · 个人 " + user
                : "内置 " + MerchantRules.size(this) + " 条");
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}

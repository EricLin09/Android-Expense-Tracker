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
    private static final int REQ_MERCHANT = 14;

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
        } else if (requestCode == REQ_MERCHANT) {
            importMerchantRules(uri);
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
        int ok = 0, bad = 0, dup = 0;
        java.util.Set<String> existing = db.allDedupeKeys();
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(getContentResolver().openInputStream(uri),
                        java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("﻿")) line = line.substring(1);
                line = line.trim();
                if (line.isEmpty() || line.startsWith("类型")) continue;
                java.util.List<String> f = parseCsvLine(line);
                if (f.size() < 6) { bad++; continue; }
                try {
                    Record r = new Record();
                    r.type = "收入".equals(f.get(0)) ? 1 : 0;
                    r.amount = Double.parseDouble(f.get(1));
                    r.category = f.get(2);
                    r.note = f.get(3);
                    r.date = f.get(4);
                    r.currency = f.get(5);
                    r.source = f.size() > 6 ? Backup.sourceOf(f.get(6)) : 0;
                    if (r.amount <= 0 || !r.date.matches("\\d{4}-\\d{2}-\\d{2}")) { bad++; continue; }
                    // 已有完全相同的记录（重复导入同一份备份）就跳过，不追加
                    if (existing.contains(DbHelper.dedupeKey(r))) { dup++; continue; }
                    db.insert(r);
                    ok++;
                } catch (Exception e) {
                    bad++;
                }
            }
            WidgetProvider.refresh(this);
            Toast.makeText(this, "已导入 " + ok + " 笔"
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

    /**
     * 商户别名表 + 本地模型的设置区。
     * 别名表是只读的内置资源，这里只显示条数；可配置的只有模型服务地址和开关。
     */
    private void setupSmartCategory() {
        TextView tvRulesInfo = findViewById(R.id.tvRulesInfo);
        Switch swLlm = findViewById(R.id.swLlm);
        EditText etHost = findViewById(R.id.etLlmHost);
        EditText etKey = findViewById(R.id.etLlmKey);
        TextView btnTest = findViewById(R.id.btnLlmTest);
        TextView btnFill = findViewById(R.id.btnLlmFill);

        refreshRulesInfo(tvRulesInfo);

        TextView btnMerchant = findViewById(R.id.btnMerchantImport);
        btnMerchant.setOnClickListener(v -> {
            Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            it.addCategory(Intent.CATEGORY_OPENABLE);
            it.setType("*/*");
            startActivityForResult(it, REQ_MERCHANT);
        });
        btnMerchant.setOnLongClickListener(v -> {
            if (MerchantRules.userSize(this) == 0) { toast("还没有个人商户表"); return true; }
            Sheets.confirm(this, "清除个人商户表？", "清除", () -> {
                MerchantRules.clearUser(this);
                refreshRulesInfo(tvRulesInfo);
                toast("已清除");
            });
            return true;
        });

        swLlm.setChecked(prefs.getBoolean(LocalLlm.KEY_ENABLED, false));
        etHost.setText(prefs.getString(LocalLlm.KEY_HOST, ""));
        etKey.setText(prefs.getString(LocalLlm.KEY_APIKEY, ""));

        // 三个控件任意变化都立刻落盘，省得用户还要找“保存”按钮
        Runnable save = () -> LocalLlm.save(this,
                swLlm.isChecked(),
                etHost.getText().toString(),
                etKey.getText().toString());

        swLlm.setOnCheckedChangeListener((b, checked) -> save.run());
        // 随输入即时落盘：靠失焦保存会漏掉「输完直接按返回键」这种常见操作
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) { save.run(); }
        };
        etHost.addTextChangedListener(watcher);
        etKey.addTextChangedListener(watcher);

        btnTest.setOnClickListener(v -> {
            save.run();
            if (LocalLlm.host(this).isEmpty()) {
                toast("请先填服务地址");
                return;
            }
            btnTest.setEnabled(false);
            btnTest.setText("连接中…");
            new Thread(() -> {
                boolean ok = LocalLlm.ping(this);
                runOnUiThread(() -> {
                    btnTest.setEnabled(true);
                    btnTest.setText("测试连接");
                    toast(ok ? "连接成功" : "连不上，检查地址和备用机上的服务是否在跑");
                });
            }).start();
        });

        btnFill.setOnClickListener(v -> {
            save.run();
            int pending = db.queryUncategorized().size();
            if (pending == 0) {
                toast("没有待分类的记录");
                return;
            }
            btnFill.setEnabled(false);
            new Thread(() -> {
                int changed = CategoryClassifier.fillUncategorized(this,
                        (done, total) -> runOnUiThread(
                                () -> btnFill.setText("分类中 " + done + "/" + total)));
                runOnUiThread(() -> {
                    btnFill.setEnabled(true);
                    btnFill.setText("批量补分类");
                    toast("已补分类 " + changed + " 条，剩余 "
                            + db.queryUncategorized().size() + " 条待分类");
                });
            }).start();
        });
    }

    /** 内置表 + 个人表的条数展示 */
    private void refreshRulesInfo(TextView tv) {
        int builtin = MerchantRules.size(this);
        int user = MerchantRules.userSize(this);
        tv.setText("内置商户表 " + builtin + " 条"
                + (user > 0 ? "  ·  个人商户表 " + user + " 条" : "  ·  未导入个人商户表"));
    }

    /** 导入个人商户表：整份覆盖，存在 App 私有目录，不随应用分发 */
    private void importMerchantRules(android.net.Uri uri) {
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            int n = MerchantRules.importUser(this, in);
            refreshRulesInfo(findViewById(R.id.tvRulesInfo));
            toast(n > 0 ? "已导入 " + n + " 条个人商户规则"
                        : "文件里没有可用规则（格式：关键词 TAB 分类）");
        } catch (Exception e) {
            toast("导入失败：" + e.getMessage());
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}

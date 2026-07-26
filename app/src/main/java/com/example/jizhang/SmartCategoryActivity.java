package com.example.jizhang;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 智能分类的详情页：商户表导入 + 本地模型兜底的配置。
 *
 * 这些控件原本挤在设置页里（7 个控件 + 200 多字说明），但其中 5 个是本地模型的
 * 配置——配一次就再也不碰。收进二级页后，设置页只留一行摘要。
 */
public class SmartCategoryActivity extends AppCompatActivity {

    private static final int REQ_MERCHANT = 14;

    private TextView tvRulesInfo;
    private DbHelper db;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_smart_category);
        db = new DbHelper(this);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        tvRulesInfo = findViewById(R.id.tvRulesInfo);
        Switch swLlm = findViewById(R.id.swLlm);
        EditText etHost = findViewById(R.id.etLlmHost);
        EditText etKey = findViewById(R.id.etLlmKey);
        TextView btnTest = findViewById(R.id.btnLlmTest);
        TextView btnFill = findViewById(R.id.btnLlmFill);
        TextView btnMerchant = findViewById(R.id.btnMerchantImport);

        refreshRulesInfo();

        swLlm.setChecked(LocalLlm.isEnabled(this) || prefsBool(LocalLlm.KEY_ENABLED));
        etHost.setText(getSharedPreferences("jizhang_prefs", MODE_PRIVATE)
                .getString(LocalLlm.KEY_HOST, ""));
        etKey.setText(getSharedPreferences("jizhang_prefs", MODE_PRIVATE)
                .getString(LocalLlm.KEY_APIKEY, ""));

        Runnable save = () -> LocalLlm.save(this,
                swLlm.isChecked(), etHost.getText().toString(), etKey.getText().toString());

        swLlm.setOnCheckedChangeListener((b, checked) -> save.run());
        // 随输入即时落盘：靠失焦保存会漏掉「输完直接按返回键」这种常见操作
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) { save.run(); }
        };
        etHost.addTextChangedListener(watcher);
        etKey.addTextChangedListener(watcher);

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
                refreshRulesInfo();
                toast("已清除");
            });
            return true;
        });

        btnTest.setOnClickListener(v -> {
            save.run();
            if (LocalLlm.host(this).isEmpty()) { toast("请先填服务地址"); return; }
            btnTest.setEnabled(false);
            btnTest.setText("连接中…");
            new Thread(() -> {
                boolean ok = LocalLlm.ping(this);
                runOnUiThread(() -> {
                    btnTest.setEnabled(true);
                    btnTest.setText("测试连接");
                    toast(ok ? "连接成功" : "连不上，检查地址和服务是否在跑");
                });
            }).start();
        });

        btnFill.setOnClickListener(v -> {
            save.run();
            if (db.queryUncategorized().isEmpty()) { toast("没有待分类的记录"); return; }
            btnFill.setEnabled(false);
            new Thread(() -> {
                int changed = CategoryClassifier.fillUncategorized(this,
                        (done, total) -> runOnUiThread(() -> btnFill.setText("分类中 " + done + "/" + total)));
                runOnUiThread(() -> {
                    btnFill.setEnabled(true);
                    btnFill.setText("批量补分类");
                    toast("已补分类 " + changed + " 条，剩余 "
                            + db.queryUncategorized().size() + " 条待分类");
                });
            }).start();
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode != REQ_MERCHANT) return;
        try (java.io.InputStream in = getContentResolver().openInputStream(data.getData())) {
            int n = MerchantRules.importUser(this, in);
            refreshRulesInfo();
            toast(n > 0 ? "已导入 " + n + " 条个人商户规则"
                        : "文件里没有可用规则（格式：关键词 TAB 分类）");
        } catch (Exception e) {
            toast("导入失败：" + e.getMessage());
        }
    }

    private void refreshRulesInfo() {
        int builtin = MerchantRules.size(this);
        int user = MerchantRules.userSize(this);
        tvRulesInfo.setText("内置商户表 " + builtin + " 条"
                + (user > 0 ? "  ·  个人商户表 " + user + " 条" : "  ·  未导入个人商户表"));
    }

    private boolean prefsBool(String key) {
        return getSharedPreferences("jizhang_prefs", MODE_PRIVATE).getBoolean(key, false);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}

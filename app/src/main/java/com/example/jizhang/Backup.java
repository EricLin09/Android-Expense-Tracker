package com.example.jizhang;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** CSV 导出与每日自动备份（备份到用户选定的文件夹，保留最近几份）。 */
public class Backup {

    static final String PREFS = "jizhang_prefs";
    static final String KEY_BACKUP_URI = "backup_tree_uri";     // 备份文件夹（SAF tree）
    static final String KEY_BACKUP_LAST = "backup_last_day";    // 上次备份日期 yyyy-MM-dd
    private static final String FILE_PREFIX = "记账备份-";
    private static final int KEEP = 3;

    /** 把全部记录写成 CSV（UTF-8 带 BOM，Excel 打开中文不乱码）。返回笔数。 */
    public static int writeCsv(DbHelper db, OutputStream os) throws Exception {
        try (Writer w = new OutputStreamWriter(os, StandardCharsets.UTF_8)) {
            w.write('\uFEFF');   // BOM
            w.write("类型,金额,分类,备注,日期,货币,来源\n");
            List<Record> all = db.queryAll();
            for (Record r : all) {
                w.write((r.type == 0 ? "支出" : "收入") + ","
                        + String.format(Locale.CHINA, "%.2f", r.amount) + ","
                        + esc(r.category) + ","
                        + esc(r.note == null ? "" : r.note) + ","
                        + r.date + ","
                        + r.currency + ","
                        + sourceName(r.source) + "\n");
            }
            return all.size();
        }
    }

    public static String sourceName(int source) {
        if (source == 1) return "自动";
        if (source == 2) return "周期";
        return "手动";
    }

    public static int sourceOf(String name) {
        if ("自动".equals(name)) return 1;
        if ("周期".equals(name)) return 2;
        return 0;
    }

    /** 字段含逗号/引号时加引号转义 */
    static String esc(String s) {
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    /** 已设置备份文件夹则返回其 Uri，否则 null */
    public static Uri backupDir(Context ctx) {
        String s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_BACKUP_URI, null);
        return s == null ? null : Uri.parse(s);
    }

    public static String lastBackupDay(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_BACKUP_LAST, null);
    }

    /** 每天最多备份一次；在后台线程执行，失败静默（下次打开再试）。 */
    public static void autoBackupIfDue(Context ctx) {
        final Context app = ctx.getApplicationContext();
        Uri dir = backupDir(app);
        if (dir == null) return;
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date());
        if (today.equals(lastBackupDay(app))) return;
        new Thread(() -> {
            try {
                backupNow(app, dir);
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putString(KEY_BACKUP_LAST, today).apply();
            } catch (Exception ignored) {
                // 文件夹被删/权限失效等，静默跳过
            }
        }).start();
    }

    /** 立即写一份备份到指定文件夹，并清掉最旧的多余备份 */
    public static void backupNow(Context ctx, Uri treeUri) throws Exception {
        String day = new SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(new Date());
        String name = FILE_PREFIX + day + ".csv";
        Uri dirDoc = DocumentsContract.buildDocumentUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri));

        // 同名旧文件（当天重复备份）先删掉
        for (String[] f : listBackups(ctx, treeUri)) {
            if (f[1].equals(name)) {
                DocumentsContract.deleteDocument(ctx.getContentResolver(),
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, f[0]));
            }
        }

        Uri file = DocumentsContract.createDocument(
                ctx.getContentResolver(), dirDoc, "text/csv", name);
        if (file == null) throw new Exception("无法创建备份文件");
        try (OutputStream os = ctx.getContentResolver().openOutputStream(file, "wt")) {
            writeCsv(new DbHelper(ctx), os);
        }
        prune(ctx, treeUri);
    }

    /** 备份文件夹里的备份文件：[documentId, displayName] */
    private static List<String[]> listBackups(Context ctx, Uri treeUri) {
        List<String[]> out = new ArrayList<>();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri));
        try (Cursor c = ctx.getContentResolver().query(children, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String docName = c.getString(1);
                if (docName != null && docName.startsWith(FILE_PREFIX) && docName.endsWith(".csv")) {
                    out.add(new String[]{c.getString(0), docName});
                }
            }
        }
        return out;
    }

    /** 只保留最近 KEEP 份（文件名含日期，按名字排序即按日期排序） */
    private static void prune(Context ctx, Uri treeUri) {
        List<String[]> files = listBackups(ctx, treeUri);
        Collections.sort(files, (a, b) -> b[1].compareTo(a[1]));
        for (int i = KEEP; i < files.size(); i++) {
            try {
                DocumentsContract.deleteDocument(ctx.getContentResolver(),
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, files.get(i)[0]));
            } catch (Exception ignored) {
            }
        }
    }
}

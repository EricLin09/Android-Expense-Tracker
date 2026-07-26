package com.example.jizhang;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** 本地 SQLite 数据库，所有账目都存在手机上，不联网。 */
public class DbHelper extends SQLiteOpenHelper {

    /** 账本起点：2026 年 7 月开始记，界面不往前翻 */
    public static final String START_YM = "2026-07";

    private static final String DB_NAME = "jizhang.db";
    private static final int DB_VERSION = 4;   // v4: 周期记账规则表

    public DbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE records (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "type INTEGER NOT NULL, " +        // 0=支出 1=收入
                "amount REAL NOT NULL, " +
                "category TEXT NOT NULL, " +
                "note TEXT, " +
                "date TEXT NOT NULL, " +           // yyyy-MM-dd
                "currency TEXT NOT NULL DEFAULT 'CNY', " +
                "source INTEGER NOT NULL DEFAULT 0)"); // 0=手动 1=自动 2=周期
        createRecurring(db);
    }

    private void createRecurring(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE recurring (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "type INTEGER NOT NULL, " +
                "amount REAL NOT NULL, " +
                "currency TEXT NOT NULL, " +
                "category TEXT NOT NULL, " +
                "note TEXT, " +
                "period_type INTEGER NOT NULL, " + // 0=每月固定日 1=每隔 N 天
                "period_value INTEGER NOT NULL, " +
                "next_date TEXT NOT NULL, " +      // yyyy-MM-dd
                "enabled INTEGER NOT NULL DEFAULT 1)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            // 保留旧数据，补上新列
            db.execSQL("ALTER TABLE records ADD COLUMN currency TEXT NOT NULL DEFAULT 'CNY'");
            db.execSQL("ALTER TABLE records ADD COLUMN source INTEGER NOT NULL DEFAULT 0");
        }
        if (oldVersion < 3) {
            // 已删除的支出分类归并到「其他」
            db.execSQL("UPDATE records SET category='其他' " +
                    "WHERE type=0 AND category IN ('医疗','通讯','人情')");
        }
        if (oldVersion < 4) {
            createRecurring(db);
        }
    }

    public long insert(Record r) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("type", r.type);
        cv.put("amount", r.amount);
        cv.put("category", r.category);
        cv.put("note", r.note);
        cv.put("date", r.date);
        cv.put("currency", r.currency == null ? Currencies.DEFAULT : r.currency);
        cv.put("source", r.source);
        return db.insert("records", null, cv);
    }

    public void update(Record r) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("type", r.type);
        cv.put("amount", r.amount);
        cv.put("category", r.category);
        cv.put("note", r.note);
        cv.put("date", r.date);
        cv.put("currency", r.currency == null ? Currencies.DEFAULT : r.currency);
        db.update("records", cv, "id=?", new String[]{String.valueOf(r.id)});
    }

    public void delete(long id) {
        getWritableDatabase().delete("records", "id=?", new String[]{String.valueOf(id)});
    }

    /** 按 id 查单条记录，找不到返回 null */
    public Record queryById(long id) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,type,amount,category,note,date,currency,source FROM records WHERE id=?",
                new String[]{String.valueOf(id)});
        Record r = c.moveToFirst() ? readRow(c) : null;
        c.close();
        return r;
    }

    /** 按日期倒序返回所有记录 */
    public List<Record> queryAll() {
        List<Record> list = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,type,amount,category,note,date,currency,source FROM records " +
                        "ORDER BY date DESC, id DESC", null);
        while (c.moveToNext()) {
            list.add(readRow(c));
        }
        c.close();
        return list;
    }

    private Record readRow(Cursor c) {
        Record r = new Record();
        r.id = c.getLong(0);
        r.type = c.getInt(1);
        r.amount = c.getDouble(2);
        r.category = c.getString(3);
        r.note = c.getString(4);
        r.date = c.getString(5);
        r.currency = c.getString(6);
        r.source = c.getInt(7);
        return r;
    }

    /** 当月出现过的所有货币代码（按该货币支出多少排序） */
    public List<String> monthCurrencies(String yearMonth) {
        List<String> list = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT currency, SUM(CASE WHEN type=0 THEN amount ELSE 0 END) s " +
                        "FROM records WHERE date LIKE ? GROUP BY currency ORDER BY s DESC",
                new String[]{yearMonth + "%"});
        while (c.moveToNext()) {
            list.add(c.getString(0));
        }
        c.close();
        return list;
    }

    /** 某月某货币的合计：index 0=支出合计 1=收入合计。「转账」不算收入（自己转给自己），不计入。 */
    public double[] monthTotals(String yearMonth, String currency) {
        double[] out = new double[2];
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT type, SUM(amount) FROM records WHERE date LIKE ? AND currency=? " +
                        "AND NOT (type=1 AND category='转账') GROUP BY type",
                new String[]{yearMonth + "%", currency});
        while (c.moveToNext()) {
            out[c.getInt(0)] = c.getDouble(1);
        }
        c.close();
        return out;
    }

    /** 某月某货币按分类的支出合计，用于饼图 */
    public List<CategorySum> monthCategoryExpense(String yearMonth, String currency) {
        List<CategorySum> list = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT category, SUM(amount) FROM records " +
                        "WHERE type=0 AND date LIKE ? AND currency=? " +
                        "GROUP BY category ORDER BY SUM(amount) DESC",
                new String[]{yearMonth + "%", currency});
        while (c.moveToNext()) {
            list.add(new CategorySum(c.getString(0), c.getDouble(1)));
        }
        c.close();
        return list;
    }

    /** 待分类（自动记账未归类）的记录，按日期倒序 */
    public List<Record> queryUncategorized() {
        List<Record> list = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,type,amount,category,note,date,currency,source FROM records " +
                        "WHERE category='待分类' ORDER BY date DESC, id DESC", null);
        while (c.moveToNext()) {
            list.add(readRow(c));
        }
        c.close();
        return list;
    }

    /** 最近用过的备注（去重、非空），用于记一笔快捷填入 */
    public List<String> recentNotes(int limit) {
        List<String> list = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT note, MAX(id) m FROM records WHERE note IS NOT NULL AND note<>'' " +
                        "GROUP BY note ORDER BY m DESC LIMIT ?",
                new String[]{String.valueOf(limit)});
        while (c.moveToNext()) {
            list.add(c.getString(0));
        }
        c.close();
        return list;
    }

    /** 某月某货币某分类的支出明细，用于统计页点击分类下钻 */
    public List<Record> monthCategoryRecords(String yearMonth, String currency, String category) {
        List<Record> list = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,type,amount,category,note,date,currency,source FROM records " +
                        "WHERE type=0 AND date LIKE ? AND currency=? AND category=? " +
                        "ORDER BY date DESC, id DESC",
                new String[]{yearMonth + "%", currency, category});
        while (c.moveToNext()) {
            list.add(readRow(c));
        }
        c.close();
        return list;
    }

    /** 记录的去重指纹（导入 CSV 时跳过已存在的记录用） */
    public static String dedupeKey(Record r) {
        return r.type + "|" + String.format(java.util.Locale.CHINA, "%.2f", r.amount)
                + "|" + r.category + "|" + (r.note == null ? "" : r.note)
                + "|" + r.date + "|" + r.currency;
    }

    /** 现有全部记录的去重指纹集合 */
    public java.util.Set<String> allDedupeKeys() {
        java.util.Set<String> set = new java.util.HashSet<>();
        for (Record r : queryAll()) set.add(dedupeKey(r));
        return set;
    }

    // ---------- 周期记账 ----------

    public long insertRecurring(Recurring r) {
        return getWritableDatabase().insert("recurring", null, recurringValues(r));
    }

    public void updateRecurring(Recurring r) {
        getWritableDatabase().update("recurring", recurringValues(r),
                "id=?", new String[]{String.valueOf(r.id)});
    }

    public void deleteRecurring(long id) {
        getWritableDatabase().delete("recurring", "id=?", new String[]{String.valueOf(id)});
    }

    private ContentValues recurringValues(Recurring r) {
        ContentValues cv = new ContentValues();
        cv.put("type", r.type);
        cv.put("amount", r.amount);
        cv.put("currency", r.currency);
        cv.put("category", r.category);
        cv.put("note", r.note);
        cv.put("period_type", r.periodType);
        cv.put("period_value", r.periodValue);
        cv.put("next_date", r.nextDate);
        cv.put("enabled", r.enabled ? 1 : 0);
        return cv;
    }

    public List<Recurring> queryRecurring() {
        List<Recurring> list = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,type,amount,currency,category,note,period_type,period_value,next_date,enabled " +
                        "FROM recurring ORDER BY id", null);
        while (c.moveToNext()) {
            Recurring r = new Recurring();
            r.id = c.getLong(0);
            r.type = c.getInt(1);
            r.amount = c.getDouble(2);
            r.currency = c.getString(3);
            r.category = c.getString(4);
            r.note = c.getString(5);
            r.periodType = c.getInt(6);
            r.periodValue = c.getInt(7);
            r.nextDate = c.getString(8);
            r.enabled = c.getInt(9) == 1;
            list.add(r);
        }
        c.close();
        return list;
    }

    public Recurring queryRecurringById(long id) {
        for (Recurring r : queryRecurring()) {
            if (r.id == id) return r;
        }
        return null;
    }

    /** 到期日推进一个周期：每月固定日跨月时不足取当月最后一天 */
    public static String advance(Recurring r, String date) {
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA);
        java.util.Calendar c = java.util.Calendar.getInstance();
        try {
            c.setTime(f.parse(date));
        } catch (Exception e) {
            return date;
        }
        if (r.periodType == Recurring.PERIOD_MONTHLY) {
            c.set(java.util.Calendar.DAY_OF_MONTH, 1);
            c.add(java.util.Calendar.MONTH, 1);
            c.set(java.util.Calendar.DAY_OF_MONTH,
                    Math.min(r.periodValue, c.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)));
        } else {
            c.add(java.util.Calendar.DAY_OF_MONTH, Math.max(1, r.periodValue));
        }
        return f.format(c.getTime());
    }

    /** 只把到期日推进到今天之后，不补记（规则重新启用时用，避免把停用期间全部补上） */
    public void fastForwardRecurring(Recurring r, String today) {
        int guard = 0;
        while (r.nextDate.compareTo(today) < 0 && guard++ < 2000) {
            r.nextDate = advance(r, r.nextDate);
        }
        updateRecurring(r);
    }

    /** 处理所有到期的周期规则，生成记录并推进到期日。返回新生成的笔数。 */
    public int processRecurring(String today) {
        int made = 0;
        for (Recurring rule : queryRecurring()) {
            if (!rule.enabled) continue;
            int guard = 0;   // 防御坏数据死循环
            while (rule.nextDate.compareTo(today) <= 0 && guard++ < 2000) {
                Record rec = new Record();
                rec.type = rule.type;
                rec.amount = rule.amount;
                rec.currency = rule.currency;
                rec.category = rule.category;
                rec.note = rule.note;
                rec.date = rule.nextDate;
                rec.source = 2;
                insert(rec);
                made++;
                rule.nextDate = advance(rule, rule.nextDate);
            }
            if (guard > 0) updateRecurring(rule);
        }
        return made;
    }

    public static class CategorySum {
        public final String category;
        public final double sum;
        public CategorySum(String category, double sum) {
            this.category = category;
            this.sum = sum;
        }
    }
}

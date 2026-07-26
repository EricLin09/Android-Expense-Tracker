package com.example.jizhang;

/** 一条周期记账规则：到期自动生成记录 */
public class Recurring {
    public static final int PERIOD_MONTHLY = 0;   // 每月固定日（periodValue=几号，月底不足取最后一天）
    public static final int PERIOD_DAYS = 1;      // 每隔 N 天（periodValue=N）

    public long id;
    public int type;          // 0=支出 1=收入
    public double amount;
    public String currency;
    public String category;
    public String note;
    public int periodType;
    public int periodValue;
    public String nextDate;   // 下次记账日 yyyy-MM-dd
    public boolean enabled;

    /** 周期文案，如「每月 1 日」「每 28 天」 */
    public String periodLabel() {
        return periodType == PERIOD_MONTHLY
                ? "每月 " + periodValue + " 日"
                : "每 " + periodValue + " 天";
    }
}

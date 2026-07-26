package com.example.jizhang;

/** 一条账目记录 */
public class Record {
    public long id;
    public int type;        // 0=支出 1=收入
    public double amount;
    public String category;
    public String note;
    public String date;     // yyyy-MM-dd
    public String currency; // 货币代码，如 CNY / AUD / USD
    public int source;      // 0=手动 1=自动记账 2=周期记账
}

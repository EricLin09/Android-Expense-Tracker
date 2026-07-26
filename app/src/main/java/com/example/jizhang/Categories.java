package com.example.jizhang;

/** 预设分类 */
public class Categories {
    public static final String[] EXPENSE = {
            "餐饮", "交通", "购物", "娱乐", "居住", "教育", "其他"
    };
    public static final String[] INCOME = {
            "工资", "奖金", "兼职", "投资", "红包", "转账", "其他"
    };

    /** 给每个支出分类一个稳定的颜色，用于饼图 */
    public static int colorFor(String category) {
        int[] palette = {
                0xFF00897B, 0xFFEF6C00, 0xFF5E35B1, 0xFF43A047, 0xFFE53935,
                0xFF1E88E5, 0xFFFDD835, 0xFF8E24AA, 0xFF6D4C41, 0xFF546E7A,
                0xFF00ACC1, 0xFFF4511E
        };
        int h = Math.abs(category.hashCode());
        return palette[h % palette.length];
    }
}

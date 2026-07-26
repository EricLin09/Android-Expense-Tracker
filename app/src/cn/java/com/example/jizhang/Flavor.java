package com.example.jizhang;

/**
 * 编译期的版本差异开关。纯国内版——分享给没有计算机基础的用户。
 *
 * 去掉双币与汇率：只有人民币，也就没有总览、没有折算、没有任何网络请求。
 * 去掉本地模型：它要求备用手机 + Termux + 编译 llama.cpp，这批用户做不到，
 * 留着只会在设置里显示看不懂也填不了的字段。
 * 去掉商户表的文件导入：要在电脑上编 TSV。但**保留**「以后都归到 X」——
 * 那是零文件操作的，也是商户表自我增长的唯一途径。
 *
 * 商户表本身不去掉：国内版的自动记账路径恰好是能工作的那条（支付宝/微信/
 * 云闪付都发通知），商户表是"通知进来就自动分好类"的唯一保障。
 */
public final class Flavor {
    public static final boolean DUAL_CURRENCY = false;
    public static final boolean LOCAL_LLM = false;
    public static final boolean IMPORT_MERCHANT_FILE = false;

    /** 小组件是紧凑尺寸（2×1）：副行只放得下一项。2×1 只放得下一项——月份+支+收+大数字四项挤不下，
     *  与其截成省略号，不如明确只显示支出：收入不是这个尺寸上会瞥一眼的东西 */
    public static final boolean COMPACT_WIDGET = true;

    private Flavor() {}
}

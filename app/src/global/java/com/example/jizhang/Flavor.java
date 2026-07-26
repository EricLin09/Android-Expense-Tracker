package com.example.jizhang;

/**
 * 编译期的版本差异开关。双币原版。
 *
 * 这些是 static final 常量，不是运行时设置——每个 APK 的行为是固定的，死分支
 * 会被编译器裁掉，也就不存在"用户切到某个组合导致没测过的路径"这种问题。
 * 需要整体不同的东西（商户表、Flavor 本身）走各自的 source set，不走分支。
 */
public final class Flavor {
    /** 双币（人民币 + 澳元）：货币选择器、总览模式、汇率、小组件双币块 */
    public static final boolean DUAL_CURRENCY = true;
    /** 局域网自建模型兜底：设置里的地址/密钥/测试连接/批量补分类 */
    public static final boolean LOCAL_LLM = true;
    /** 从文件导入个人商户表（需要在电脑上编 TSV） */
    public static final boolean IMPORT_MERCHANT_FILE = true;

    /** 小组件是紧凑尺寸（2×1）：副行只放得下一项。2×2 放得下支出和收入两项 */
    public static final boolean COMPACT_WIDGET = false;

    private Flavor() {}
}

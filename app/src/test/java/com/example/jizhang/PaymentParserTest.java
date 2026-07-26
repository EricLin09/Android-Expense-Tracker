package com.example.jizhang;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

/**
 * {@link PaymentParser} 的解析规则。
 *
 * 这些用例都是「写错一个字就会把钱记错」的地方：金额取哪一个数、收支方向怎么判、
 * 币种从哪个符号推。构造的通知文本按真实支付宝/微信/银行通知的措辞写。
 */
public class PaymentParserTest {

    private static final String ALIPAY = "com.eg.android.AlipayGphone";
    private static final String WECHAT = "com.tencent.mm";

    private static Record parse(String text) {
        return PaymentParser.parse(ALIPAY, "", text);
    }

    // ---------- 不像账单的通知必须被挡掉 ----------

    @Test public void 聊天消息不解析() {
        assertNull(parse("小王：晚上吃饭吗，人均 50"));
    }

    @Test public void 没有金额不解析() {
        assertNull(parse("您有一笔支付待确认"));
    }

    @Test public void 空文本不解析() {
        assertNull(PaymentParser.parse(ALIPAY, null, null));
    }

    // ---------- 金额：取对那一个数 ----------

    @Test public void 普通支付取金额() {
        assertEquals(25.50, parse("支付宝支付成功 ¥25.50").amount, 0.001);
    }

    @Test public void 千分位逗号() {
        assertEquals(1234.56, parse("支付成功 ¥1,234.56").amount, 0.001);
    }

    @Test public void 金额写在元字前面() {
        assertEquals(38.0, parse("消费 38元").amount, 0.001);
    }

    @Test public void 余额降级为兜底不当成金额() {
        Record r = parse("支付成功 ¥25.50，账户余额 1,234.56 元");
        assertEquals(25.50, r.amount, 0.001);
    }

    @Test public void 全文只有余额时兜底仍可用() {
        // 没有更好的候选，宁可记下这个数让用户改，也好过丢掉整条通知
        assertEquals(1234.56, parse("账户余额 1,234.56 元，交易成功").amount, 0.001);
    }

    @Test public void 优惠立减不当成金额() {
        assertEquals(30.0, parse("支付成功 30元，已减 5.00 元").amount, 0.001);
    }

    /**
     * 回归：卡号尾号曾被当成金额。
     *
     * 「尾号」原先只降级为兜底，而兜底在没有别的候选时会被采用；更糟的是金额为整数时
     * 顶不掉先出现的尾号（替换条件要求新值带小数点），于是 1234 被记成了金额。
     */
    @Test public void 卡号尾号不当成金额_整数金额() {
        assertEquals(25.0, parse("工商银行 尾号1234 消费25元").amount, 0.001);
    }

    @Test public void 卡号尾号不当成金额_小数金额() {
        assertEquals(25.50, parse("招商银行 尾号8888 支付 ¥25.50").amount, 0.001);
    }

    @Test public void 只有卡号没有金额则不解析() {
        // 丢弃而不是兜底：卡号绝不是钱数，记下来一定是错的
        assertNull(parse("您尾号1234的卡片交易成功"));
    }

    @Test public void 订单号不当成金额() {
        assertEquals(99.0, parse("订单号20260726001 支付成功 99元").amount, 0.001);
    }

    @Test public void 金额上限外的数被忽略() {
        assertEquals(12.0, parse("支付 12元 流水 99999999999").amount, 0.001);
    }

    // ---------- 收支方向 ----------

    @Test public void 支付记为支出() {
        assertEquals(0, parse("支付成功 ¥25.50").type);
    }

    @Test public void 到账记为收入() {
        assertEquals(1, parse("您有一笔转账到账 ¥500.00").type);
    }

    @Test public void 退款记为收入() {
        assertEquals(1, parse("退款 ¥68.00 已原路返回").type);
    }

    @Test public void 支出强关键词压过收入词() {
        // “付款给收款方”里同时有支付词和收款词，必须判成支出
        assertEquals(0, parse("付款给收款方 张三 ¥100.00").type);
    }

    @Test public void 方向不明时默认支出() {
        // 记错方向的代价不对称：漏记一笔支出比凭空多一笔收入更容易被发现
        assertEquals(0, parse("交易成功 ¥88.00").type);
    }

    // ---------- 币种 ----------

    @Test public void 人民币符号() {
        assertEquals("CNY", parse("支付成功 ¥25.50").currency);
    }

    @Test public void 元字也判人民币() {
        assertEquals("CNY", parse("消费 38元").currency);
    }

    @Test public void 澳元符号() {
        assertEquals("AUD", parse("payment of A$12.50").currency);
    }

    @Test public void 孤立美元符号按澳元处理() {
        assertEquals("AUD", parse("payment $12.50").currency);
    }

    @Test public void 美元需要明确前缀() {
        assertEquals("USD", parse("payment US$12.50").currency);
    }

    // ---------- 记录的其余字段 ----------

    @Test public void 自动记账的记录标记来源与待分类() {
        Record r = PaymentParser.parse(WECHAT, "微信支付", "付款给星巴克 ¥32.00");
        assertNotNull(r);
        assertEquals(1, r.source);          // 1=自动
        assertEquals("待分类", r.category);
        assertEquals("2026-07-26".length(), r.date.length());
    }

    @Test public void 备注带上来源应用() {
        Record r = PaymentParser.parse(WECHAT, "微信支付", "付款给星巴克 ¥32.00");
        org.junit.Assert.assertTrue(r.note.contains("微信"));
    }
}

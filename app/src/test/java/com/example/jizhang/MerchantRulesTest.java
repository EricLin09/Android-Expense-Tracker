package com.example.jizhang;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link MerchantRules} 的关键词匹配边界。
 *
 * 这里的每一条几乎都对应一个在真实银行流水里抓到过的误判——注释写明了是哪一个。
 * 边界规则是三档妥协（词边界 / 复数 / 长词放宽），改动其中任何一档都很容易在别处
 * 破功，所以正例反例成对写。
 */
public class MerchantRulesTest {

    private static void hit(String text, String keyword) {
        assertTrue("「" + keyword + "」应命中「" + text + "」",
                MerchantRules.matches(text, keyword));
    }

    private static void miss(String text, String keyword) {
        assertFalse("「" + keyword + "」不应命中「" + text + "」",
                MerchantRules.matches(text, keyword));
    }

    // ---------- 拉丁关键词要词边界 ----------

    @Test public void 完整单词命中() {
        hit("WOOLWORTHS 1234 SYDNEY", "woolworths");
        hit("COLES EXPRESS", "coles");
    }

    @Test public void 短词不得命中更长的词_左边界() {
        // UTS 曾命中 KRISPY KREME DONUTS，把甜甜圈记成了教育支出
        miss("KRISPY KREME DONUTS", "uts");
        // RENT 曾命中 CURRENT ACCOUNT FEE，把账户管理费记成了房租
        miss("CURRENT ACCOUNT FEE", "rent");
    }

    @Test public void 短词不得命中更长的词_右边界() {
        miss("SHELLEY BEACH KIOSK", "shell");
        miss("GYMEA VILLAGE BAKERY", "gym");
    }

    @Test public void 短词在真正的词边界上仍要命中() {
        hit("SHELL COLES EXPRESS", "shell");
        hit("UTS UNION LTD", "uts");
        hit("RENT PAYMENT JULY", "rent");
    }

    // ---------- 复数与所有格 ----------

    @Test public void 允许单个尾随s() {
        hit("MCDONALDS SYDNEY", "mcdonald");
        hit("DAN MURPHYS BONDI", "dan murphy");
    }

    @Test public void 撇号本身就是边界() {
        hit("DAN MURPHY'S BONDI", "dan murphy");
    }

    /**
     * 复数那一档只对短词可达——长词在它之前就被放宽了（见下面「长词放宽」一组）。
     * 所以这里必须用短关键词，才真的在测「只放行一个 s」。
     */
    @Test public void 短词的尾随s之后还得是边界() {
        hit("GYMS SYDNEY", "gym");            // s 后面是空格，通过
        miss("GYMSON STREET CAFE", "gym");    // s 后面还是字母，不通过
    }

    // ---------- 长词放宽右边界 ----------

    @Test public void 长关键词允许连写() {
        // CommBank 的流水会把商户名和商场名连在一起写
        hit("GusmanYGomezWestfiel", "gusman");
        hit("WOOLWORTHSMETRO123", "woolworths");
    }

    @Test public void 短关键词不享受放宽() {
        // 放宽只对长度 >= 6 的关键词生效，短词仍然严格
        miss("SHELLEY BEACH KIOSK", "shell");
        miss("COLESLAW DELI", "coles");
    }

    /**
     * 放宽的代价，明确记录下来：长关键词右边接什么都算命中，所以它也会命中
     * 以自己为前缀的别的词。这是有意接受的——真实流水里连写商户名远比
     * 「另一个词恰好以某个 6 字以上商户名开头」常见。
     *
     * 若哪天这条造成了误判，改的是 LOOSE_RIGHT_MIN_LEN 或换成更聪明的边界，
     * 而不是把这条用例删掉。
     */
    @Test public void 长词放宽会顺带命中前缀词_已知取舍() {
        hit("MCDONALDSON STREET CAFE", "mcdonald");
    }

    // ---------- 星号归一化 ----------

    @Test public void 星号当作分隔符() {
        // 不归一化的话 UBER *EATS 匹配不上 UBER EATS，会退到更短的 UBER 规则上
        // 被记成交通——外卖全部错分类就是这么来的
        hit("UBER *EATS SYDNEY", "uber eats");
        hit("SQ *SOME COFFEE SHOP", "some coffee");
    }

    @Test public void 星号归一化后uber仍能单独命中() {
        hit("UBER *TRIP HELP.UBER.COM", "uber");
    }

    // ---------- 大小写与空白 ----------

    @Test public void 大小写不敏感() {
        hit("woolworths metro", "WOOLWORTHS");
        hit("WOOLWORTHS METRO", "woolworths");
    }

    @Test public void 连续空白压缩() {
        hit("UBER    EATS", "uber eats");
    }

    // ---------- 中文关键词不套词边界 ----------

    @Test public void 中文用子串匹配() {
        hit("付款给星巴克咖啡", "星巴克");
        hit("美团外卖订单", "美团");
    }

    @Test public void 中文夹在词中间也算命中() {
        // 中文没有词边界概念，这是有意为之
        hit("上海星巴克咖啡有限公司", "星巴克");
    }

    // ---------- 边界输入 ----------

    @Test public void 空输入不命中() {
        miss("WOOLWORTHS", "");
        miss("", "woolworths");
        assertFalse(MerchantRules.matches(null, "woolworths"));
        assertFalse(MerchantRules.matches("WOOLWORTHS", null));
    }

    // ---------- guessKeyword：给「以后都归到 X」预填 ----------

    @Test public void 猜关键词_取英文商户名前两词() {
        assertEquals("TIAN SHUN", MerchantRules.guessKeyword("TIAN SHUN HE PTY LTD"));
    }

    @Test public void 猜关键词_剥掉自动记账前缀() {
        assertEquals("星巴克", MerchantRules.guessKeyword("自动记账 · 支付宝 · 星巴克"));
    }

    @Test public void 猜关键词_中文取最长汉字段() {
        assertEquals("星巴克咖啡", MerchantRules.guessKeyword("付款给星巴克咖啡 32.00元"));
    }

    @Test public void 猜关键词_遇到带数字的词就停() {
        assertEquals("COLES EXPRESS", MerchantRules.guessKeyword("COLES EXPRESS 1234 SYDNEY"));
    }

    @Test public void 猜关键词_域名形式的首词自成一体() {
        assertEquals("APPLE.COM/BILL", MerchantRules.guessKeyword("APPLE.COM/BILL SYDNEY AU"));
    }

    @Test public void 猜关键词_太短则放弃() {
        assertEquals("", MerchantRules.guessKeyword("A 1"));
    }

    @Test public void 猜关键词_星号不是断点() {
        assertEquals("UBER EATS", MerchantRules.guessKeyword("UBER *EATS"));
    }

    @Test public void 猜关键词_空输入() {
        assertEquals("", MerchantRules.guessKeyword(null));
        assertEquals("", MerchantRules.guessKeyword(""));
    }
}

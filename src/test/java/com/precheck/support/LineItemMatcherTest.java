package com.precheck.support;

import com.precheck.facts.Facts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 逐条金额配对的测试。
 *
 * <p>这里测的不是"能不能配上"，而是<b>"什么时候该停嘴"</b>。
 * 配对结果会被拿去对员工说"你这条填错了"，说错了比不说更糟，
 * 所以"配不上就老实说配不上"这几个用例比"配对成功"更要紧。
 */
class LineItemMatcherTest {

    private static final BigDecimal TOL = new BigDecimal("0.01");

    private Facts.LineItem item(String desc, String amount) {
        return new Facts.LineItem(desc, amount == null ? null : new BigDecimal(amount));
    }

    private Facts.Attachment bill(String fileName, String amount) {
        return new Facts.Attachment("a-" + fileName, Facts.Attachment.KIND_INVOICE,
                amount == null ? null : new BigDecimal(amount),
                null, null, null, fileName, null);
    }

    @Test
    @DisplayName("金额全对得上：一条都不报")
    void allExactShouldMatch() {
        var r = LineItemMatcher.match(
                List.of(item("机票", "450.00"), item("住宿", "700.00")),
                List.of(bill("机票.pdf", "450.00"), bill("住宿.pdf", "700.00")),
                TOL);

        assertEquals(2, r.matched().size());
        assertTrue(r.suspected().isEmpty(), "全都对得上，不该指认任何一条");
        assertTrue(r.orphanItems().isEmpty());
        assertTrue(r.orphanInvoices().isEmpty());
    }

    @Test
    @DisplayName("各剩一条：才敢说「差在住宿这一条」")
    void singleLeftoverEachSideShouldBeSuspectable() {
        // 明细 450 / 680，发票 450 / 700 —— 450 配上，剩下正好各一条
        var r = LineItemMatcher.match(
                List.of(item("机票", "450.00"), item("住宿", "680.00")),
                List.of(bill("机票.pdf", "450.00"), bill("住宿.pdf", "700.00")),
                TOL);

        assertEquals(1, r.matched().size());
        assertEquals(1, r.suspected().size());
        assertEquals("住宿", r.suspected().get(0).item().desc());
        assertEquals(0, new BigDecimal("20.00").compareTo(r.suspected().get(0).gap()));
        assertTrue(r.orphanItems().isEmpty(), "能配成对就不要重复列进孤儿");
        assertTrue(r.orphanInvoices().isEmpty());
    }

    @Test
    @DisplayName("各剩两条：不敢指认，如实列出来")
    void multipleLeftoversShouldNotGuess() {
        var r = LineItemMatcher.match(
                List.of(item("A", "100.00"), item("B", "200.00")),
                List.of(bill("a.pdf", "110.00"), bill("b.pdf", "230.00")),
                TOL);

        assertTrue(r.suspected().isEmpty(), "剩两条以上时配对只是巧合，不能指认");
        assertEquals(2, r.orphanItems().size());
        assertEquals(2, r.orphanInvoices().size());
    }

    @Test
    @DisplayName("明细多、发票少：多出来的明细找不到票")
    void extraLineItemsShouldBeOrphans() {
        var r = LineItemMatcher.match(
                List.of(item("机票", "450.00"), item("住宿", "700.00"), item("打车", "60.00")),
                List.of(bill("机票.pdf", "450.00"), bill("住宿.pdf", "700.00")),
                TOL);

        assertEquals(2, r.matched().size());
        assertEquals(1, r.orphanItems().size());
        assertEquals("打车", r.orphanItems().get(0).desc());
        assertTrue(r.orphanInvoices().isEmpty());
    }

    @Test
    @DisplayName("发票多、明细少：多出来的发票找不到明细")
    void extraInvoicesShouldBeOrphans() {
        var r = LineItemMatcher.match(
                List.of(item("机票", "450.00")),
                List.of(bill("机票.pdf", "450.00"), bill("打车.pdf", "60.00")),
                TOL);

        assertEquals(1, r.matched().size());
        assertTrue(r.orphanItems().isEmpty());
        assertEquals(1, r.orphanInvoices().size());
        assertEquals("打车.pdf", r.orphanInvoices().get(0).fileName());
    }

    @Test
    @DisplayName("一张综合票对多条明细：各剩多条，不乱指认")
    void singleCombinedInvoiceShouldNotBeGuessed() {
        // 员工把一张 1130 的综合票拆成两条明细，这是完全合法的做法
        var r = LineItemMatcher.match(
                List.of(item("机票", "450.00"), item("住宿", "680.00")),
                List.of(bill("综合.pdf", "1130.00")),
                TOL);

        assertTrue(r.suspected().isEmpty());
        assertEquals(2, r.orphanItems().size());
        assertEquals(1, r.orphanInvoices().size());
    }

    @Test
    @DisplayName("金额未知的票不参与配对，但会被列出来")
    void unknownAmountShouldNotBeMatched() {
        var r = LineItemMatcher.match(
                List.of(item("机票", "450.00"), item("住宿", "680.00")),
                List.of(bill("机票.pdf", "450.00"), bill("住宿.pdf", null)),
                TOL);

        assertEquals(1, r.matched().size());
        assertTrue(r.suspected().isEmpty(), "金额未知时不能说「票面多/少多少」");
        assertEquals(1, r.orphanItems().size());
        assertEquals(1, r.orphanInvoices().size());
        assertNull(r.orphanInvoices().get(0).amount());
    }

    @Test
    @DisplayName("容差内的差额算对上，不产生噪音")
    void withinToleranceShouldCountAsMatched() {
        var r = LineItemMatcher.match(
                List.of(item("机票", "450.00")),
                List.of(bill("机票.pdf", "450.01")),
                TOL);

        assertEquals(1, r.matched().size());
        assertTrue(r.suspected().isEmpty(), "1 分钱容差是给人留的，不该拿来报错");
    }

    @Test
    @DisplayName("一边为空：什么也别说")
    void emptySideShouldReturnNothing() {
        var r = LineItemMatcher.match(List.of(), List.of(bill("机票.pdf", "450.00")), TOL);
        assertTrue(r.hasNothing());

        var r2 = LineItemMatcher.match(List.of(item("机票", "450.00")), List.of(), TOL);
        assertTrue(r2.hasNothing());
    }

    @Test
    @DisplayName("同样金额的两条明细，不会抢同一张发票")
    void sameAmountItemsShouldNotShareOneInvoice() {
        var r = LineItemMatcher.match(
                List.of(item("机票去程", "450.00"), item("机票返程", "450.00")),
                List.of(bill("去程.pdf", "450.00")),
                TOL);

        assertEquals(1, r.matched().size(), "一张票只能配一次");
        assertEquals(1, r.orphanItems().size());
    }
}

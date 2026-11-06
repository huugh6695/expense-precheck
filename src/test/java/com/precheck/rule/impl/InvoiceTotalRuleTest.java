package com.precheck.rule.impl;

import com.precheck.config.PrecheckRuleProperties;
import com.precheck.facts.Facts;
import com.precheck.rule.Finding;
import com.precheck.rule.Level;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R4 金额一致性规则的测试。
 *
 * <p>重点在两条边界：
 * <ol>
 *   <li><b>总额一致就不许说话。</b>一张综合票拆成几条明细是合法操作，逐条必然对不上，
 *       拿这个拦人就是机械卡人；</li>
 *   <li><b>能定位才定位。</b>配不出确定关系时必须退回"相差 N 元"，不能编一个"最接近的"。</li>
 * </ol>
 */
class InvoiceTotalRuleTest {

    private final InvoiceTotalRule rule = new InvoiceTotalRule(new PrecheckRuleProperties());

    private Facts.Attachment bill(String fileName, String kind, String amount, String invoiceNo) {
        return new Facts.Attachment("a-" + fileName, kind,
                amount == null ? null : new BigDecimal(amount),
                LocalDate.of(2026, 10, 12), invoiceNo, "某公司", fileName, "*住宿服务*住宿费");
    }

    private Facts facts(List<Facts.LineItem> items, List<Facts.Attachment> attachments) {
        return new Facts(
                new Facts.ApplicationInfo("REIMBURSE", "张三", new BigDecimal("1130.00"),
                        "赴杭州参加供应商年度评审会议",
                        LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 14), "差旅"),
                attachments, items);
    }

    @Test
    @DisplayName("总额一致就通过，哪怕逐条一条都对不上")
    void matchingTotalShouldPassEvenIfNoLineMatches() {
        // 员工把一张 1130 的综合票拆成「机票450 + 住宿680」两条明细 —— 合法，不该报
        Facts facts = facts(
                List.of(new Facts.LineItem("机票", new BigDecimal("450.00")),
                        new Facts.LineItem("住宿", new BigDecimal("680.00"))),
                List.of(bill("综合.pdf", Facts.Attachment.KIND_INVOICE, "1130.00", "NO1")));

        assertTrue(rule.check(facts).isEmpty(), "总额一致却报错，就是把拆票的正常做法拦死了");
    }

    @Test
    @DisplayName("差额能定位到具体一条明细")
    void shouldLocateTheGapOnOneLineItem() {
        // 明细 机票450 + 住宿680 = 1130；发票 机票450 + 住宿700 = 1150，差 20
        Facts facts = facts(
                List.of(new Facts.LineItem("机票", new BigDecimal("450.00")),
                        new Facts.LineItem("住宿", new BigDecimal("680.00"))),
                List.of(bill("机票.pdf", Facts.Attachment.KIND_TRAVEL, "450.00", "NO1"),
                        bill("住宿.pdf", Facts.Attachment.KIND_HOTEL, "700.00", "NO2")));

        List<Finding> findings = rule.check(facts);
        assertEquals(1, findings.size());
        Finding f = findings.get(0);

        assertEquals(Level.BLOCK, f.level());
        assertTrue(f.message().contains("1150.00") && f.message().contains("1130.00"),
                "实际消息：" + f.message());
        // 最关键的一句：不只是"差 20 元"，而是"差在住宿这一条"
        assertTrue(f.suggestion().contains("住宿"), "实际建议：" + f.suggestion());
        assertTrue(f.suggestion().contains("700.00") && f.suggestion().contains("680.00"),
                "实际建议：" + f.suggestion());
        assertTrue(f.suggestion().contains("20.00"), "实际建议：" + f.suggestion());
        assertTrue(f.evidence().contains("一致 1 对"), "实际证据：" + f.evidence());
    }

    @Test
    @DisplayName("差额落在发票一侧时，说「票面少」而不是「票面多」")
    void shouldReportDirectionCorrectly() {
        // 明细 住宿 700，发票 住宿 680 —— 票面比明细少 20
        Facts facts = facts(
                List.of(new Facts.LineItem("住宿", new BigDecimal("700.00"))),
                List.of(bill("住宿.pdf", Facts.Attachment.KIND_HOTEL, "680.00", "NO1")));

        List<Finding> findings = rule.check(facts);
        assertEquals(1, findings.size());
        assertTrue(findings.get(0).suggestion().contains("票面少"),
                "实际建议：" + findings.get(0).suggestion());
    }

    @Test
    @DisplayName("配不出确定关系时，只列出落单的，不硬凑")
    void shouldListOrphansInsteadOfGuessing() {
        // 明细三条，发票两张，680 那条没票
        Facts facts = facts(
                List.of(new Facts.LineItem("机票", new BigDecimal("450.00")),
                        new Facts.LineItem("住宿", new BigDecimal("680.00")),
                        new Facts.LineItem("打车", new BigDecimal("60.00"))),
                List.of(bill("机票.pdf", Facts.Attachment.KIND_TRAVEL, "450.00", "NO1"),
                        bill("住宿.pdf", Facts.Attachment.KIND_HOTEL, "700.00", "NO2")));

        List<Finding> findings = rule.check(facts);
        assertEquals(1, findings.size());
        Finding f = findings.get(0);

        // 剩两条明细（住宿680、打车60）、零张发票 → 不敢配对，只能列出来
        assertTrue(f.suggestion().contains("找不到对应发票"), "实际建议：" + f.suggestion());
        assertTrue(f.suggestion().contains("住宿") || f.suggestion().contains("打车"),
                "实际建议：" + f.suggestion());
        assertTrue(f.evidence().contains("2 条明细找不到对应发票"), "实际证据：" + f.evidence());
    }

    @Test
    @DisplayName("发票金额没识别出来时，不说「票面多少」，如实说未识别")
    void unknownAmountShouldNotBeBlamedWithANumber() {
        Facts facts = facts(
                List.of(new Facts.LineItem("机票", new BigDecimal("450.00")),
                        new Facts.LineItem("住宿", new BigDecimal("680.00"))),
                List.of(bill("机票.pdf", Facts.Attachment.KIND_TRAVEL, "450.00", "NO1"),
                        bill("住宿.pdf", Facts.Attachment.KIND_HOTEL, null, "NO2")));

        List<Finding> findings = rule.check(facts);
        assertEquals(1, findings.size());
        Finding f = findings.get(0);
        assertFalse(f.suggestion().contains("票面多") || f.suggestion().contains("票面少"),
                "金额未知就不能谈方向，实际建议：" + f.suggestion());
        assertTrue(f.suggestion().contains("金额未识别"), "实际建议：" + f.suggestion());
    }

    @Test
    @DisplayName("重复上传的发票先排除，不让一个错误变两个")
    void duplicateInvoiceShouldBeExcludedBeforeComparing() {
        // 机票 450 传了两遍 + 住宿 700，明细 1130 → 去重后正好差 20
        Facts facts = facts(
                List.of(new Facts.LineItem("机票", new BigDecimal("450.00")),
                        new Facts.LineItem("住宿", new BigDecimal("680.00"))),
                List.of(bill("机票.pdf", Facts.Attachment.KIND_TRAVEL, "450.00", "NO1"),
                        bill("机票_重复.pdf", Facts.Attachment.KIND_TRAVEL, "450.00", "NO1"),
                        bill("住宿.pdf", Facts.Attachment.KIND_HOTEL, "700.00", "NO2")));

        List<Finding> findings = rule.check(facts);
        assertEquals(1, findings.size());
        assertTrue(findings.get(0).message().contains("20.00"),
                "去重后差额应回到真实的 20 元，实际：" + findings.get(0).message());
        assertTrue(findings.get(0).evidence().contains("重复上传已排除 1 张"),
                "实际证据：" + findings.get(0).evidence());
    }

    @Test
    @DisplayName("没有发票或没有明细：如实说明，不报错")
    void missingSideShouldBeInformational() {
        Facts noInvoice = facts(List.of(new Facts.LineItem("机票", new BigDecimal("450.00"))), List.of());
        assertEquals(Level.INFO, rule.check(noInvoice).get(0).level());

        Facts noItem = facts(List.of(),
                List.of(bill("机票.pdf", Facts.Attachment.KIND_TRAVEL, "450.00", "NO1")));
        assertEquals(Level.INFO, rule.check(noItem).get(0).level());
    }
}

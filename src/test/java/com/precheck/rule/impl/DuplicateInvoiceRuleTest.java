package com.precheck.rule.impl;

import com.precheck.facts.Facts;
import com.precheck.ledger.InvoiceHistory;
import com.precheck.rule.Finding;
import com.precheck.rule.Level;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R5 跨申请查重的测试。
 *
 * <p>这里用假台账而不是真文件：这条规则要验证的是"查到历史之后怎么判"，不是文件读写。
 * 文件读写由 {@link com.precheck.ledger.FileInvoiceHistoryTest} 单独覆盖。
 */
class DuplicateInvoiceRuleTest {

    private static final String NO = "26942000000996000001";

    /** 造一个只认识指定几条历史的假台账。 */
    private InvoiceHistory stub(Map<String, InvoiceHistory.Entry> hits) {
        return new InvoiceHistory() {
            @Override
            public Map<String, Entry> lookup(Collection<String> invoiceNos) {
                Map<String, Entry> out = new HashMap<>();
                for (String no : invoiceNos) {
                    if (hits.containsKey(no)) {
                        out.put(no, hits.get(no));
                    }
                }
                return out;
            }

            @Override
            public boolean record(String invoiceNo, String applicant, BigDecimal amount, LocalDate invoiceDate) {
                return false;
            }
        };
    }

    private InvoiceHistory.Entry entry(String applicant) {
        return new InvoiceHistory.Entry("hash-" + NO, "…0001", applicant,
                new BigDecimal("149.00"), LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 14));
    }

    private Facts.Attachment invoice(String no) {
        return new Facts.Attachment("a1", Facts.Attachment.KIND_INVOICE, new BigDecimal("149.00"),
                LocalDate.of(2026, 9, 5), no, "某某电子商务", "发票.pdf", "*鞋*运动鞋");
    }

    private Facts facts(String applicant, List<Facts.Attachment> attachments) {
        return new Facts(
                new Facts.ApplicationInfo("REIMBURSE", applicant, new BigDecimal("149.00"),
                        "办公用品采购", LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 5), "办公"),
                attachments,
                List.of(new Facts.LineItem("费用", new BigDecimal("149.00"))));
    }

    @Test
    @DisplayName("别人报过的票又拿来报：阻断")
    void otherApplicantDuplicateShouldBlock() {
        DuplicateInvoiceRule rule = new DuplicateInvoiceRule(stub(Map.of(NO, entry("李四"))));

        List<Finding> findings = rule.check(facts("张三", List.of(invoice(NO))));

        assertEquals(1, findings.size(), "实际：" + findings);
        assertEquals(Level.BLOCK, findings.get(0).level(), "跨人重复报销必须阻断");
        assertTrue(findings.get(0).message().contains("李四"), "要点名是谁报过，实际：" + findings.get(0).message());
        assertTrue(findings.get(0).evidence().contains("2026-09-14"),
                "证据里要有台账的日期，实际：" + findings.get(0).evidence());
    }

    @Test
    @DisplayName("自己以前报过同一张票：只提醒，不阻断（可能是退回后重提）")
    void sameApplicantDuplicateShouldWarn() {
        DuplicateInvoiceRule rule = new DuplicateInvoiceRule(stub(Map.of(NO, entry("张三"))));

        List<Finding> findings = rule.check(facts("张三", List.of(invoice(NO))));

        assertEquals(1, findings.size(), "实际：" + findings);
        assertEquals(Level.WARN, findings.get(0).level(),
                "同一个人重提很可能是申请被退回，直接阻断会让人寸步难行");
        assertTrue(findings.get(0).suggestion().contains("退回"),
                "要告诉员工这可能是什么情况、该怎么办，实际：" + findings.get(0).suggestion());
    }

    @Test
    @DisplayName("台账里没有这张票：不报任何问题")
    void newInvoiceShouldPass() {
        DuplicateInvoiceRule rule = new DuplicateInvoiceRule(stub(Map.of()));

        assertTrue(rule.check(facts("张三", List.of(invoice(NO)))).isEmpty());
    }

    @Test
    @DisplayName("本次内已经重复了：只报一次，不再拿历史重复说一遍")
    void withinSubmissionTakesPrecedence() {
        DuplicateInvoiceRule rule = new DuplicateInvoiceRule(stub(Map.of(NO, entry("李四"))));

        Facts.Attachment second = new Facts.Attachment("a2", Facts.Attachment.KIND_INVOICE,
                new BigDecimal("149.00"), LocalDate.of(2026, 9, 5), NO, "某某电子商务", "发票-重传.pdf", "*鞋*运动鞋");

        List<Finding> findings = rule.check(facts("张三", List.of(invoice(NO), second)));

        assertEquals(1, findings.size(), "同一件事说两遍只会显得系统啰嗦，实际：" + findings);
        assertTrue(findings.get(0).message().contains("重复提交"), "实际：" + findings.get(0).message());
    }
}

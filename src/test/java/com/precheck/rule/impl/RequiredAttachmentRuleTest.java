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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R2 附件齐全规则的测试。
 *
 * <p>每个用例都在回答财务最常问的一句话：<b>"这种材料你会不会退回？"</b>
 */
class RequiredAttachmentRuleTest {

    private final RequiredAttachmentRule rule = new RequiredAttachmentRule(new PrecheckRuleProperties());

    private Facts facts(String category, List<Facts.Attachment> attachments) {
        return new Facts(
                new Facts.ApplicationInfo("REIMBURSE", "张三", new BigDecimal("1000.00"),
                        "出差", LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 14), category),
                attachments,
                List.of(new Facts.LineItem("费用", new BigDecimal("1000.00"))));
    }

    private Facts.Attachment travel() {
        return new Facts.Attachment("a1", Facts.Attachment.KIND_TRAVEL, new BigDecimal("450.00"),
                LocalDate.of(2026, 10, 12), "NO001", "东方航空", "机票.pdf", "*运输服务*客运服务费");
    }

    private Facts.Attachment hotel() {
        return new Facts.Attachment("a2", Facts.Attachment.KIND_HOTEL, new BigDecimal("680.00"),
                LocalDate.of(2026, 10, 13), "NO002", "某某酒店", "住宿.pdf", "*住宿服务*住宿费");
    }

    @Test
    @DisplayName("差旅只有机票没住宿票：必须补")
    void travelWithoutHotelShouldBlock() {
        List<Finding> findings = rule.check(facts("差旅", List.of(travel())));

        assertEquals(1, findings.size());
        assertEquals(Level.BLOCK, findings.get(0).level());
        assertTrue(findings.get(0).message().contains("住宿"), "实际：" + findings.get(0).message());
        assertTrue(findings.get(0).suggestion().contains("当天往返"),
                "要给出例外情况的做法，实际：" + findings.get(0).suggestion());
    }

    @Test
    @DisplayName("差旅只有住宿票没交通票：必须补")
    void travelWithoutTicketShouldBlock() {
        List<Finding> findings = rule.check(facts("差旅", List.of(hotel())));

        assertEquals(1, findings.size());
        assertTrue(findings.get(0).message().contains("交通"));
    }

    @Test
    @DisplayName("差旅交通住宿都有：通过")
    void completeTravelShouldPass() {
        assertTrue(rule.check(facts("差旅", List.of(travel(), hotel()))).isEmpty());
    }

    @Test
    @DisplayName("一份附件都没有：必须补")
    void noAttachmentShouldBlock() {
        List<Finding> findings = rule.check(facts("办公", List.of()));

        assertEquals(1, findings.size());
        assertEquals(Level.BLOCK, findings.get(0).level());
        assertTrue(findings.get(0).message().contains("一份附件都没有"));
    }

    @Test
    @DisplayName("非差旅类别：有发票就通过，没发票就阻断 + 提示有读不出的附件")
    void officeNeedsAtLeastOneInvoice() {
        Facts.Attachment invoice = new Facts.Attachment("a1", Facts.Attachment.KIND_INVOICE,
                new BigDecimal("120.00"), LocalDate.of(2026, 9, 4), "NO009", "某某商贸", "发票.pdf", "*办公用品*打印纸");
        assertTrue(rule.check(facts("办公", List.of(invoice))).isEmpty());

        Facts.Attachment unknown = new Facts.Attachment("a9", Facts.Attachment.KIND_OTHER,
                null, null, null, null, "看不懂.pdf", null);
        List<Finding> findings = rule.check(facts("办公", List.of(unknown)));

        // 一条阻断（没有可用发票）+ 一条提醒（有附件没识别出类型）
        assertEquals(2, findings.size());
        assertTrue(findings.stream().anyMatch(f -> f.level() == Level.BLOCK));
        assertTrue(findings.stream().anyMatch(f -> f.level() == Level.WARN
                && f.message().contains("没能识别出票据类型")));
    }

    @Test
    @DisplayName("规则可配置：把差旅改成只要发票，就不再要求住宿票")
    void shouldFollowConfiguredRequirement() {
        Facts.Attachment invoice = new Facts.Attachment("a1", Facts.Attachment.KIND_INVOICE,
                new BigDecimal("120.00"), LocalDate.of(2026, 9, 4), "NO009", "某某商贸", "发票.pdf", "*办公用品*打印纸");

        // 默认配置：差旅缺交通票和住宿票，会被退回
        assertEquals(2, rule.check(facts("差旅", List.of(invoice))).size());

        // 改配置（相当于改 rules.yml）：差旅只需要发票
        PrecheckRuleProperties custom = new PrecheckRuleProperties();
        custom.setRequiredAttachments(List.of(
                new PrecheckRuleProperties.Requirement("差旅", List.of(Facts.Attachment.KIND_INVOICE)),
                new PrecheckRuleProperties.Requirement("默认", List.of(Facts.Attachment.KIND_INVOICE))));
        RequiredAttachmentRule configured = new RequiredAttachmentRule(custom);

        assertTrue(configured.check(facts("差旅", List.of(invoice))).isEmpty(),
                "改了配置就不该再要求交通票和住宿票");
    }
}

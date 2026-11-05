package com.precheck.rule;

import com.precheck.config.PrecheckRuleProperties;
import com.precheck.facts.Facts;
import com.precheck.rule.impl.DeclaredAmountRule;
import com.precheck.rule.impl.DuplicateInvoiceRule;
import com.precheck.rule.impl.InvoiceTotalRule;
import com.precheck.rule.impl.RequiredAttachmentRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则引擎的测试。
 *
 * <p>注意这里<b>没有启动 Spring</b>：直接 new 出规则对象塞给引擎。
 * 这就是"解析与校验解耦"带来的好处——测规则不需要数据库、不需要 web 容器、不需要真实 PDF。
 */
class PrecheckEngineTest {

    private final PrecheckRuleProperties props = new PrecheckRuleProperties();
    private final PrecheckEngine engine = new PrecheckEngine(List.of(
            new RequiredAttachmentRule(props),
            new DeclaredAmountRule(props),
            new InvoiceTotalRule(props),
            new DuplicateInvoiceRule()
    ));

    private Facts loadSample() {
        try (InputStream in = getClass().getResourceAsStream("/samples/sample-reimburse.json")) {
            assertNotNull(in);
            return JsonMapper.builder().build().readValue(in, Facts.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("问题样例：应报出重复发票 + 金额差 20 元")
    void shouldFlagSampleProblems() {
        PrecheckReport report = engine.run(loadSample());

        System.out.println("=== 预检结果 ===");
        report.findings().forEach(f -> System.out.println(
                "[" + f.level() + "][" + f.ruleId() + "] " + f.message() + "  ←  " + f.evidence()));

        // 两个阻断项：发票重复、发票与明细差 20 元；申报金额与明细是一致的，不该报
        assertEquals(2, report.blockCount());
        assertFalse(report.passed());

        assertTrue(report.findings().stream().anyMatch(f -> f.ruleId().equals("R5_DUPLICATE_INVOICE")));
        Finding amountIssue = report.findings().stream()
                .filter(f -> f.ruleId().equals("R4_INVOICE_TOTAL"))
                .findFirst().orElseThrow();
        // 去重后 450 + 700 = 1150，明细 1130，正好差 20
        assertTrue(amountIssue.message().contains("20.00"), "实际消息：" + amountIssue.message());

        assertTrue(report.findings().stream().noneMatch(f -> f.ruleId().equals("R3_DECLARED_AMOUNT")));
    }

    @Test
    @DisplayName("干净样例：没问题时应通过")
    void shouldPassWhenEverythingMatches() {
        Facts clean = new Facts(
                new Facts.ApplicationInfo("REIMBURSE", "张三", new BigDecimal("1130.00"),
                        "赴杭州参加供应商年度评审会议",
                        LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 14), "差旅"),
                List.of(
                        new Facts.Attachment("a1", Facts.Attachment.KIND_TRAVEL, new BigDecimal("450.00"),
                                LocalDate.of(2026, 10, 12), "NO001", "东方航空", "机票.pdf", "*运输服务*客运服务费"),
                        new Facts.Attachment("a2", Facts.Attachment.KIND_HOTEL, new BigDecimal("680.00"),
                                LocalDate.of(2026, 10, 13), "NO002", "某酒店", "住宿.pdf", "*住宿服务*住宿费")
                ),
                List.of(
                        new Facts.LineItem("机票", new BigDecimal("450.00")),
                        new Facts.LineItem("住宿", new BigDecimal("680.00"))
                ));

        PrecheckReport report = engine.run(clean);

        assertTrue(report.passed(), "不该有阻断项，实际：" + report.findings());
        assertEquals(0, report.blockCount());
    }

    @Test
    @DisplayName("容差内的 1 分钱差异不应报错")
    void shouldTolerateOneCentDifference() {
        Facts facts = new Facts(
                new Facts.ApplicationInfo("REIMBURSE", "张三", new BigDecimal("1110.00"), "办公用品",
                        null, null, "办公"),
                List.of(new Facts.Attachment("a1", Facts.Attachment.KIND_INVOICE, new BigDecimal("1109.99"),
                        null, "NO001", null, "合计.pdf", null)),
                List.of(new Facts.LineItem("合并报销", new BigDecimal("1110.00"))));

        PrecheckReport report = engine.run(facts);
        assertTrue(report.passed(), "差 1 分钱应落在容差内，实际：" + report.findings());
    }
}

package com.precheck.rule.impl;

import com.precheck.ai.AiClient;
import com.precheck.facts.Facts;
import com.precheck.rule.Finding;
import com.precheck.rule.Level;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 测试 AI 规则时<b>不联网</b>：塞一个假模型进去，专门验证"各种模型返回"下规则的应对。
 *
 * <p>这才是能天天跑的测试。真连模型的测试又慢又不稳定，只适合手动验证一次。
 */
class ReasonCategoryRuleTest {

    /** 假模型：返回什么由测试指定。 */
    private static class StubAi implements AiClient {
        private final Optional<String> reply;
        private final boolean available;

        StubAi(Optional<String> reply, boolean available) {
            this.reply = reply;
            this.available = available;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public Optional<String> complete(String systemPrompt, String userPrompt) {
            return reply;
        }
    }

    private Facts facts(String reason, String category, String itemDesc, String seller) {
        return new Facts(
                new Facts.ApplicationInfo("REIMBURSE", "张三", new BigDecimal("149.00"),
                        reason, LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 5), category),
                List.of(new Facts.Attachment("a1", Facts.Attachment.KIND_INVOICE, new BigDecimal("149.00"),
                        LocalDate.of(2026, 9, 5), "NO001", seller, "发票.pdf", "*鞋*运动鞋")),
                List.of(new Facts.LineItem(itemDesc, new BigDecimal("149.00"))));
    }

    @Test
    @DisplayName("模型说可疑：给一条 WARN，并标记来源为 AI")
    void shouldRaiseWarnWhenSuspect() {
        StubAi ai = new StubAi(Optional.of(
                "{\"verdict\":\"SUSPECT\",\"reason\":\"事由是办公用品，但开票方为化妆品公司\",\"suggestion\":\"请补充说明用途或调整费用类别\"}"),
                true);
        List<Finding> findings = new ReasonCategoryRule(ai)
                .check(facts("办公用品采购", "办公", "护肤品", "雅诗兰黛（上海）商贸有限公司"));

        assertEquals(1, findings.size());
        Finding f = findings.get(0);
        assertEquals(Level.WARN, f.level());
        assertEquals(Finding.Source.AI, f.source());
        assertTrue(f.message().contains("化妆品公司"), "实际：" + f.message());
        assertTrue(f.suggestion().contains("调整费用类别"));
    }

    @Test
    @DisplayName("模型说没问题：不产生任何提示")
    void shouldStaySilentWhenOk() {
        StubAi ai = new StubAi(Optional.of("{\"verdict\":\"OK\",\"reason\":\"\",\"suggestion\":\"\"}"), true);
        List<Finding> findings = new ReasonCategoryRule(ai).check(facts("办公用品采购", "办公", "打印纸", "某某文具"));
        assertTrue(findings.isEmpty());
    }

    @Test
    @DisplayName("模型返回带代码块或乱码：不能崩，只能如实说没解析成功")
    void shouldHandleBadFormat() {
        StubAi fenced = new StubAi(Optional.of(
                "```json\n{\"verdict\":\"SUSPECT\",\"reason\":\"事由与开票方不相关\",\"suggestion\":\"请确认\"}\n```"), true);
        assertEquals(1, new ReasonCategoryRule(fenced)
                .check(facts("办公用品采购", "办公", "护肤品", "某酒店")).size());

        StubAi garbage = new StubAi(Optional.of("我觉得这项报销有点奇怪，建议人工看看。"), true);
        List<Finding> findings = new ReasonCategoryRule(garbage)
                .check(facts("办公用品采购", "办公", "护肤品", "某酒店"));
        assertEquals(1, findings.size());
        assertEquals(Level.INFO, findings.get(0).level());
        assertTrue(findings.get(0).message().contains("无法解析"));
    }

    @Test
    @DisplayName("模型没响应：明确说这一项没查，而不是假装通过")
    void shouldReportSkippedWhenNoReply() {
        StubAi ai = new StubAi(Optional.empty(), true);
        List<Finding> findings = new ReasonCategoryRule(ai).check(facts("办公用品采购", "办公", "护肤品", "某酒店"));

        assertEquals(1, findings.size());
        assertEquals(Level.INFO, findings.get(0).level());
        assertTrue(findings.get(0).message().contains("未完成"));
    }

    @Test
    @DisplayName("没配 Key：静默跳过，不打扰用户")
    void shouldBeSilentWhenNotConfigured() {
        StubAi ai = new StubAi(Optional.empty(), false);
        assertTrue(new ReasonCategoryRule(ai)
                .check(facts("办公用品采购", "办公", "护肤品", "某酒店")).isEmpty());
    }
}

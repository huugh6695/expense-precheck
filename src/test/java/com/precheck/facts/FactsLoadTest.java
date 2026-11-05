package com.precheck.facts;

import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 事实集冒烟测试：证明 JSON → Facts 这条路是通的，模型立住了。
 *
 * <p>这一步不涉及任何检查逻辑，只验证"能读进来、字段对得上"。
 * 后面写规则时，测试里不用再碰 JSON 文件，直接 new Facts(...) 造数据就行。
 */
class FactsLoadTest {

    @Test
    @DisplayName("样例报销单能完整反序列化成 Facts")
    void shouldLoadSampleFacts() throws Exception {
        JsonMapper mapper = JsonMapper.builder().build();

        Facts facts;
        try (InputStream in = getClass().getResourceAsStream("/samples/sample-reimburse.json")) {
            assertNotNull(in, "样例文件没打包进 classpath，检查 src/main/resources/samples");
            facts = mapper.readValue(in, Facts.class);
        }

        // 1. 申请单基本信息
        Facts.ApplicationInfo app = facts.application();
        assertEquals("REIMBURSE", app.type());
        assertEquals(0, new BigDecimal("1130.00").compareTo(app.declaredAmount()));
        assertEquals(LocalDate.of(2026, 10, 12), app.startDate());

        // 2. 附件：3 份，其中 a1/a3 是同一张机票发票
        assertEquals(3, facts.attachments().size());
        Facts.Attachment first = facts.attachments().get(0);
        assertEquals(Facts.Attachment.KIND_TRAVEL, first.kind());
        assertEquals("*运输服务*客运服务费", first.itemSummary());
        assertEquals(0, new BigDecimal("450.00").compareTo(first.amount()));

        // 3. 明细：450 + 680 = 1130，正好等于申报金额
        BigDecimal itemSum = facts.lineItems().stream()
                .map(Facts.LineItem::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal("1130.00").compareTo(itemSum));

        // 4. 发票合计 450 + 700 + 450 = 1600，跟申报的 1130 对不上 —— 这就是后面 R4 要抓的问题
        BigDecimal invoiceSum = facts.attachments().stream()
                .map(Facts.Attachment::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal("1600.00").compareTo(invoiceSum));

        System.out.println("读取成功：申报 " + app.declaredAmount()
                + " 元，附件 " + facts.attachments().size()
                + " 份，发票合计 " + invoiceSum + " 元，明细合计 " + itemSum + " 元");
    }
}

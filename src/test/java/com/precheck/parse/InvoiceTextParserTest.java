package com.precheck.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用"真发票抽出来的文本"测抽取规则。
 *
 * <p>这个测试不碰 PDF 文件——文本是固定的，所以结果必须稳定。
 * 以后遇到新的票种，把它的文本存成新 fixture 再加一个用例，
 * 这样每改一次正则，就能知道有没有把老票种改坏。
 */
class InvoiceTextParserTest {

    private final InvoiceTextParser parser = new InvoiceTextParser();

    private String read(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/samples/" + name)) {
            assertNotNull(in, "找不到 fixture: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("全电发票：号码、日期、价税合计、购销方都要抽对")
    void shouldParseRealInvoiceText() throws IOException {
        ParsedInvoiceFields f = parser.parse(read("invoice-text-1.txt"));

        assertEquals("26942000000996000001", f.invoiceNo());
        assertEquals(LocalDate.of(2026, 9, 5), f.date());
        // 关键：不能抽成 131.86（不含税金额）或 17.14（税额），必须是价税合计 149.00
        assertEquals(0, new BigDecimal("149.00").compareTo(f.amount()));
        assertEquals("某某大学", f.buyer());
        assertEquals("某某电子商务有限公司", f.seller());
        // 开票项目：税务局规范写法 *类别*品名
        assertEquals("*鞋*运动鞋", f.itemSummary());
        assertTrue(f.warnings().isEmpty(), "不该有警告，实际：" + f.warnings());
    }

    @Test
    @DisplayName("没有文字层的 PDF：返回警告而不是瞎猜")
    void shouldWarnWhenTextIsEmpty() {
        ParsedInvoiceFields f = parser.parse("   ");

        assertEquals(null, f.amount());
        assertEquals(null, f.invoiceNo());
        assertTrue(f.warnings().get(0).contains("没有文字层"),
                "警告要说明原因，实际：" + f.warnings());
    }

    @Test
    @DisplayName("没有大写金额时，兜底取最大的 ¥ 金额")
    void shouldFallbackToLargestMoney() {
        String text = """
                电子发票（普通发票） 发票号码：
                26942000000996000001
                ¥131.86 ¥17.14
                合 计 ¥131.86 ¥17.14
                价税合计 ¥149.00
                """;
        ParsedInvoiceFields f = parser.parse(text);

        assertEquals("26942000000996000001", f.invoiceNo());
        assertEquals(0, new BigDecimal("149.00").compareTo(f.amount()));
        assertTrue(f.warnings().stream().anyMatch(w -> w.contains("开票日期")),
                "日期缺失应给警告，实际：" + f.warnings());
    }

    @Test
    @DisplayName("CRLF 换行：\\r 不能粘在字段尾巴上")
    void shouldNormalizeCrLf() throws IOException {
        // Windows 上 Git 会把 fixture 检出成 CRLF，不归一的话抽出来会是 "某某大学\r"
        String crlf = read("invoice-text-1.txt").replace("\r\n", "\n").replace("\n", "\r\n");
        ParsedInvoiceFields f = parser.parse(crlf);

        assertEquals("某某大学", f.buyer());
        assertEquals("某某电子商务有限公司", f.seller());
        assertEquals("26942000000996000001", f.invoiceNo());
        assertEquals(0, new BigDecimal("149.00").compareTo(f.amount()));
    }
}

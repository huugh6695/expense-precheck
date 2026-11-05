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
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    @DisplayName("逐字符排版：每个字一行时，拼回一行照样能抽到号码/日期/金额")
    void shouldRepairVerticalLayout() {
        // 真实遇到的排版：开票系统把每个字符输出成独立文本块，
        // 20 位发票号被打散成 20 行单个数字，\d{20} 永远匹配不到
        String vertical = String.join("\n",
                "发", "票", "号", "码", "：",
                "2", "6", "9", "4", "2", "0", "0", "0", "0", "0",
                "0", "9", "9", "6", "0", "0", "0", "0", "0", "1",
                "2", "0", "2", "6", "年", "0", "9", "月", "0", "5", "日",
                "壹", "佰", "肆", "拾", "玖", "圆", "整", "¥", "1", "4", "9", ".", "0", "0");

        ParsedInvoiceFields f = parser.parse(vertical);

        assertEquals("26942000000996000001", f.invoiceNo(), "警告：" + f.warnings());
        assertEquals(LocalDate.of(2026, 9, 5), f.date(), "号码后面的年份不能被当成日期的一部分");
        assertEquals(0, new BigDecimal("149.00").compareTo(f.amount()), "警告：" + f.warnings());
    }

    @Test
    @DisplayName("纳税人识别号的前 8 位不能被当成 8 位发票号")
    void taxIdPrefixShouldNotBeInvoiceNo() {
        // 91370100MA3C… 是统一社会信用代码：前 8 位纯数字，后面跟字母。
        // 边界写成"非数字"就会把它认成老版 8 位发票号——号码一错，查重和分组合并全乱。
        String text = """
                购买方信息
                某某大学
                91370100MA3C8XXXXX
                价税合计 壹佰肆拾玖圆整 ¥149.00
                """;

        ParsedInvoiceFields f = parser.parse(text);

        assertNull(f.invoiceNo(), "税号前缀不该被当成发票号，实际抽到：" + f.invoiceNo());
    }
}

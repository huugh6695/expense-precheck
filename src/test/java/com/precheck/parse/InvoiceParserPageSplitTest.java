package com.precheck.parse;

import com.precheck.config.PrecheckRuleProperties;
import com.precheck.facts.Facts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 按页切分的测试。
 *
 * <p>用<b>假的抽取器</b>喂固定页文本，而不是造真 PDF：这里要验证的是
 * "多页怎么合并"，不是 PDFBox 能不能读文件。把抽取器换掉，用例就变得可重复、可读。
 *
 * <p>这些用例守的是一件事：<b>不能把一张跨页的发票拆成两份</b>，
 * 也<b>不能把两张不同的发票并成一份</b>。两个方向都会让金额核对得出错误结论。
 */
class InvoiceParserPageSplitTest {

    private static final String NO_A = "26942000000996000001";
    private static final String NO_B = "26942000000996000002";

    /** 造一个"只返回固定页文本"的抽取器。 */
    private InvoiceParser parserWith(List<String> pages) {
        PdfTextExtractor stub = new PdfTextExtractor() {
            @Override
            public PdfPages extractPages(Path pdf) {
                return new PdfPages(pages, pages.size());
            }
        };
        return new InvoiceParser(stub, new InvoiceTextParser(), new PrecheckRuleProperties());
    }

    /** 一页完整发票：号码 + 日期 + 开票方 + 价税合计 + 项目。 */
    private String invoicePage(String no, String amount) {
        return """
                电子发票（普通发票） 发票号码：
                开票日期：
                """
                + no + "\n2026年09月05日\n某某电子商务有限公司\n9135000000000000XY\n"
                + "壹佰肆拾玖圆整 ¥" + amount + "\n*鞋*运动鞋\n";
    }

    /** 一张发票的第二页：只有明细和合计，没有发票号（真实发票跨页就长这样）。 */
    private String continuationPage() {
        return """
                项目名称 规格型号 单 位 数 量 单 价 金 额
                合 计 ¥149.00
                *鞋*运动鞋 双 1 149.00 149.00
                """;
    }

    @Test
    @DisplayName("两页两张不同发票：切成两份，文件名带页码")
    void twoInvoicesShouldSplit() {
        List<InvoiceParseResult> results = parserWith(List.of(
                invoicePage(NO_A, "149.00"),
                invoicePage(NO_B, "120.00"))).parseAll(Path.of("x.pdf"), "a1", "整月发票.pdf");

        assertEquals(2, results.size(), "实际：" + results);
        assertEquals(NO_A, results.get(0).attachment().invoiceNo());
        assertEquals(NO_B, results.get(1).attachment().invoiceNo());
        assertEquals("整月发票.pdf 第1页", results.get(0).attachment().fileName());
        assertEquals("整月发票.pdf 第2页", results.get(1).attachment().fileName());
        assertEquals(0, new BigDecimal("120.00").compareTo(results.get(1).attachment().amount()));
    }

    @Test
    @DisplayName("一张发票跨两页：合并成一份，不能拆开（第二页没有号码）")
    void crossPageInvoiceShouldMerge() {
        List<InvoiceParseResult> results = parserWith(List.of(
                invoicePage(NO_A, "149.00"),
                continuationPage())).parseAll(Path.of("x.pdf"), "a1", "发票.pdf");

        assertEquals(1, results.size(), "第二页是上一张的续页，不该单独成一份：" + results);
        assertEquals(NO_A, results.get(0).attachment().invoiceNo());
        assertEquals(0, new BigDecimal("149.00").compareTo(results.get(0).attachment().amount()));
        assertEquals("发票.pdf", results.get(0).attachment().fileName(),
                "只切出一份时不该加「第1页」这种噪音");
    }

    @Test
    @DisplayName("三页：发票A + A的续页 + 发票B，文件名要标出 第1-2页")
    void continuationThenNextInvoice() {
        List<InvoiceParseResult> results = parserWith(List.of(
                invoicePage(NO_A, "149.00"),
                continuationPage(),
                invoicePage(NO_B, "120.00"))).parseAll(Path.of("x.pdf"), "a1", "整月发票.pdf");

        assertEquals(2, results.size(), "实际：" + results);
        assertEquals("整月发票.pdf 第1-2页", results.get(0).attachment().fileName(),
                "跨页的发票要标出页码范围，员工才对得上");
        assertEquals("整月发票.pdf 第3页", results.get(1).attachment().fileName());
    }

    @Test
    @DisplayName("同一发票号两页读出不同金额：合成一份，但给一条冲突警告")
    void amountConflictShouldWarn() {
        List<InvoiceParseResult> results = parserWith(List.of(
                invoicePage(NO_A, "149.00"),
                invoicePage(NO_A, "131.86"))).parseAll(Path.of("x.pdf"), "a1", "发票.pdf");

        assertEquals(1, results.size(),
                "同一张票必须合成一份：留两份会让金额被重复计入，反而制造两个假警报");
        assertEquals(0, new BigDecimal("149.00").compareTo(results.get(0).attachment().amount()),
                "取第一页的金额（通常是价税合计所在页）");
        assertTrue(results.get(0).warnings().stream().anyMatch(w -> w.contains("不同金额")),
                "冲突必须看得见，实际警告：" + results.get(0).warnings());
    }

    @Test
    @DisplayName("空白页直接跳过，不当成一份「读不出的发票」")
    void blankPageShouldBeSkipped() {
        List<InvoiceParseResult> results = parserWith(List.of(
                invoicePage(NO_A, "149.00"),
                "   ",
                invoicePage(NO_B, "120.00"))).parseAll(Path.of("x.pdf"), "a1", "整月发票.pdf");

        assertEquals(2, results.size(), "空白页不该变成附件，实际：" + results);
        assertEquals("整月发票.pdf 第3页", results.get(1).attachment().fileName(),
                "页码要按真实页序算，不能因为跳过空白页就错位");
    }

    @Test
    @DisplayName("整份没有文字层：返回一条 OTHER + 警告，不抛异常")
    void noTextLayerShouldWarn() {
        List<InvoiceParseResult> results = parserWith(List.of("   ", "  ")).parseAll(Path.of("x.pdf"), "a1", "扫描件.pdf");

        assertEquals(1, results.size());
        assertEquals(Facts.Attachment.KIND_OTHER, results.get(0).attachment().kind());
        assertTrue(results.get(0).warnings().stream().anyMatch(w -> w.contains("没有文字层")),
                "实际：" + results.get(0).warnings());
        assertFalse(results.get(0).warnings().isEmpty());
    }
}

package com.precheck.parse;

import com.precheck.config.PrecheckRuleProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 端到端：真的去读一张真发票 PDF。
 *
 * <p>用 assumeTrue 而不是断言路径存在——真发票不可能放进仓库（含个人信息），
 * 所以这个用例默认"跳过"，想跑的时候自己指定路径：
 * <pre>mvn test -Dinvoice.sample="C:/path/to/发票.pdf"</pre>
 * 换台机器跑测试时应该跳过而不是失败，这是处理本地样本的正确姿势。
 */
class InvoiceParserRealPdfTest {

    private final InvoiceParser parser = new InvoiceParser(
            new PdfTextExtractor(), new InvoiceTextParser(), new PrecheckRuleProperties());

    @Test
    @DisplayName("真实电子发票 PDF：能读出号码、日期、149.00 元")
    void shouldParseRealPdf() {
        Path pdf = Path.of(System.getProperty("invoice.sample", "samples/real-invoice.pdf"));
        assumeTrue(Files.exists(pdf), "没有指定真发票样本，跳过。用法：mvn test -Dinvoice.sample=发票.pdf");

        InvoiceParseResult result = parser.parse(pdf, "a1");

        System.out.println("解析结果：" + result.attachment());
        System.out.println("警告：" + result.warnings());

        assertTrue(result.complete(), "关键字段应当都解析出来，实际警告：" + result.warnings());
        assertEquals("26942000000996952231", result.attachment().invoiceNo());
        assertEquals(0, new BigDecimal("149.00").compareTo(result.attachment().amount()));
        assertEquals("厦门天邻缘电子商务有限公司", result.attachment().seller());
    }

    @Test
    @DisplayName("不存在的文件：返回警告而不是抛异常")
    void shouldNotThrowOnMissingFile() {
        InvoiceParseResult result = parser.parse(Path.of("C:/不存在/的/文件.pdf"), "a9");
        assertTrue(!result.warnings().isEmpty());
        assertEquals(null, result.attachment().amount());
    }
}

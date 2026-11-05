package com.precheck.parse;

import com.precheck.config.PrecheckRuleProperties;
import com.precheck.facts.Facts;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 发票解析的入口：PDF → 文本 → 字段 → 附件事实。
 *
 * <p>设计原则：<b>解析失败绝不抛异常给调用方</b>，而是返回一个字段为 null、
 * 带警告说明的结果。因为"这份发票读不出来"本身就是需要告诉员工的信息，
 * 而不是让整个预检崩掉——前者是产品能力，后者是故障。
 */
@Component
public class InvoiceParser {

    private final PdfTextExtractor extractor;
    private final InvoiceTextParser textParser;
    private final PrecheckRuleProperties props;

    public InvoiceParser(PdfTextExtractor extractor, InvoiceTextParser textParser, PrecheckRuleProperties props) {
        this.extractor = extractor;
        this.textParser = textParser;
        this.props = props;
    }

    public InvoiceParseResult parse(Path pdf, String id) {
        String fileName = pdf.getFileName().toString();
        try {
            String text = extractor.extract(pdf);
            ParsedInvoiceFields fields = textParser.parse(text);
            Facts.Attachment attachment = new Facts.Attachment(
                    id, classify(fields), fields.amount(), fields.date(),
                    fields.invoiceNo(), fields.seller(), fileName, fields.itemSummary());
            return new InvoiceParseResult(attachment, fields.warnings());
        } catch (IOException | RuntimeException e) {
            Facts.Attachment attachment = new Facts.Attachment(
                    id, Facts.Attachment.KIND_OTHER, null, null, null, null, fileName, null);
            return new InvoiceParseResult(attachment,
                    List.of("读取文件失败（" + e.getMessage() + "），请手工填写这张发票的信息"));
        }
    }

    /**
     * 判断这张票是交通、住宿还是其他发票。
     *
     * <p>关键词表来自 rules.yml（precheck.rules.ticket-keywords），
     * 加新票种（加油费、通行费）只改配置，不用动代码。
     *
     * <p><b>只看项目名称和开票方，不看全文</b>：备注里常出现"住宿""机票"这类字样
     * （比如"附住宿发票一张"），扫全文会误判。
     *
     * <p>Map 的顺序就是判定优先级（YAML 里先写的先判），所以 rules.yml 里
     * 特意把 HOTEL 放在 TRAVEL 前面。
     */
    public String classify(ParsedInvoiceFields fields) {
        String haystack = lower(fields.itemSummary()) + "|" + lower(fields.seller());

        for (Map.Entry<String, List<String>> entry : props.effectiveKeywords().entrySet()) {
            for (String keyword : entry.getValue()) {
                if (haystack.contains(lower(keyword))) {
                    return entry.getKey();
                }
            }
        }
        // 有发票号但认不出类别，仍然算一张有效发票（能参与金额核对和查重）
        return fields.invoiceNo() != null ? Facts.Attachment.KIND_INVOICE : Facts.Attachment.KIND_OTHER;
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase();
    }
}

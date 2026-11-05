package com.precheck.parse;

import com.precheck.config.PrecheckRuleProperties;
import com.precheck.facts.Facts;
import com.precheck.support.Money;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 发票解析的入口：PDF → 逐页文本 → 每页抽字段 → 合并成一份份发票事实。
 *
 * <p>设计原则：<b>解析失败绝不抛异常给调用方</b>，而是返回字段为 null、
 * 带警告说明的结果。因为"这份发票读不出来"本身就是需要告诉员工的信息，
 * 而不是让整个预检崩掉——前者是产品能力，后者是故障。
 *
 * <h2>为什么要按页切分</h2>
 * 财务经常把一整个月的发票打成一个 PDF，一次报销二十几页。
 * 整份读成一个字符串就只能认出第一张，其余全丢——那不是精度问题，是材料白传了。
 *
 * <h2>切页之后最难的一件事：合并</h2>
 * 从文本上看，<b>"一张发票跨两页"和"两页两张发票"长得一模一样</b>。
 * 本类的判断依据只有两条：
 * <ol>
 *   <li><b>看发票号</b>：有号就是一张独立的票；同一个号又出现，是同一张票的另一页。</li>
 *   <li><b>看有没有号</b>：没号的页当作<b>上一张的续页</b>——发票明细跨页是常态，
 *       第二页往往只有明细和合计，没有号码。</li>
 * </ol>
 *
 * <p>这个策略的代价要说清楚：真有一张独立的发票、偏偏号码没抽出来，
 * 它会被并进上一张。相比"把一张跨页发票拆成两份导致金额对不上"，
 * 这个错误更容易被人发现（金额差会被 R4 揪出来），所以选它。
 *
 * <p>另外，同一发票号的两页读出不同金额时，只保留第一页的金额并给一条警告，
 * <b>而不是把两张都留下</b>：留下两张会让金额被重复计入，R4 报一个假差额、
 * R5 报一个假的"重复提交"，两个假警报比一个真问题更误导人。
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

    /**
     * 解析一份 PDF，返回里面所有的发票。
     *
     * @param pdf         要解析的文件
     * @param idPrefix    附件 id 前缀（同一份 PDF 的多个附件共用，后面拼页码保证唯一）
     * @param displayName 给员工看的文件名（上传时的原始名，不是临时文件名）
     * @return 至少一条：解析失败时返回一条 OTHER + 警告，调用方不用判空
     */
    public List<InvoiceParseResult> parseAll(Path pdf, String idPrefix, String displayName) {
        String name = (displayName == null || displayName.isBlank())
                ? pdf.getFileName().toString()
                : displayName;
        try {
            PdfTextExtractor.PdfPages doc = extractor.extractPages(pdf);
            return build(name, idPrefix, doc);
        } catch (IOException | RuntimeException e) {
            Facts.Attachment attachment = new Facts.Attachment(
                    idPrefix, Facts.Attachment.KIND_OTHER, null, null, null, null, name, null);
            return List.of(new InvoiceParseResult(attachment,
                    List.of("读取文件失败（" + e.getMessage() + "），请手工填写这张发票的信息")));
        }
    }

    /** 把逐页文本合并成一份份发票。 */
    private List<InvoiceParseResult> build(String name, String idPrefix, PdfTextExtractor.PdfPages doc) {
        List<Acc> order = new ArrayList<>();
        Map<String, Acc> byNo = new LinkedHashMap<>();
        Acc last = null;

        for (int i = 0; i < doc.pages().size(); i++) {
            int pageNo = i + 1;
            String text = doc.pages().get(i);
            if (text == null || text.isBlank()) {
                // 空白页不是一个"附件"，跳过——但顺序里的 last 不变，后续续页仍然接得上
                continue;
            }

            ParsedInvoiceFields fields = textParser.parse(text);
            String no = blankToNull(fields.invoiceNo());

            if (no != null) {
                Acc existing = byNo.get(no);
                if (existing == null) {
                    Acc acc = new Acc(no, pageNo);
                    acc.absorb(fields, pageNo);
                    byNo.put(no, acc);
                    order.add(acc);
                    last = acc;
                } else {
                    existing.mergeSameInvoice(fields, pageNo);
                    last = existing;
                }
            } else if (last != null) {
                // 没号码的页：当作上一张的续页（第二页只有明细和合计，是常态）
                last.continueWith(fields, pageNo);
            } else {
                Acc acc = new Acc(null, pageNo);
                acc.absorb(fields, pageNo);
                order.add(acc);
                last = acc;
            }
        }

        if (order.isEmpty()) {
            Facts.Attachment attachment = new Facts.Attachment(
                    idPrefix, Facts.Attachment.KIND_OTHER, null, null, null, null, name, null);
            return List.of(new InvoiceParseResult(attachment,
                    List.of("这份 PDF 里没有文字层（可能是拍的照片或扫描件），读不出内容，请手工填写金额")));
        }

        boolean multi = order.size() > 1;
        List<InvoiceParseResult> results = new ArrayList<>(order.size());
        for (Acc acc : order) {
            results.add(acc.toResult(name, multi, idPrefix));
        }

        if (doc.truncated()) {
            // 页数被截断这件事必须让员工知道，挂在最后一条上
            InvoiceParseResult lastOne = results.get(results.size() - 1);
            List<String> warnings = new ArrayList<>(lastOne.warnings());
            warnings.add("这份 PDF 共 " + doc.totalPages() + " 页，只解析了前 "
                    + doc.pages().size() + " 页，其余请拆分后单独上传");
            results.set(results.size() - 1, new InvoiceParseResult(lastOne.attachment(), warnings));
        }
        return results;
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

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase();
    }

    /**
     * 一张发票在合并过程中的草稿。
     *
     * <p>为什么要这个中间态：一张发票的信息可能分散在多页（第 1 页有号码和金额、
     * 第 2 页有开票方），必须边读边补，最后才拼成 Attachment。
     */
    private final class Acc {

        /** 发票号；null 表示这一组是"没有号码的页"。 */
        private final String invoiceNo;
        private final int firstPage;
        private int lastPage;
        private BigDecimal amount;
        private LocalDate date;
        private String seller;
        private String itemSummary;
        private final List<String> warnings = new ArrayList<>();

        Acc(String invoiceNo, int page) {
            this.invoiceNo = invoiceNo;
            this.firstPage = page;
            this.lastPage = page;
        }

        /** 第一次见到这张票：字段能填就填，警告照收。 */
        void absorb(ParsedInvoiceFields f, int page) {
            lastPage = page;
            fillMissing(f);
            if (f.warnings() != null) {
                warnings.addAll(f.warnings());
            }
        }

        /** 同一个发票号又出现了：可能是同一张票的第二页，也可能是页脚重复打印。 */
        void mergeSameInvoice(ParsedInvoiceFields f, int page) {
            lastPage = page;
            if (f.amount() != null && amount != null && !Money.same(amount, f.amount())) {
                warnings.add("同一发票号在第 " + firstPage + " 页和第 " + page + " 页读出不同金额（"
                        + Money.fmt(amount) + " 元 / " + Money.fmt(f.amount()) + " 元），已按第 "
                        + firstPage + " 页的 " + Money.fmt(amount) + " 元计入，请核对这两页");
                // 金额不覆盖：第一页通常是价税合计所在页，更可信
            } else {
                fillMissing(f);
            }
        }

        /** 没号码的页，接在上一张后面。 */
        void continueWith(ParsedInvoiceFields f, int page) {
            lastPage = page;
            fillMissing(f);
            // 刻意不收 f.warnings()：续页本来就没有发票号，
            // 把它转成"没识别出发票号"的警告会制造噪音
        }

        private void fillMissing(ParsedInvoiceFields f) {
            if (date == null) {
                date = f.date();
            }
            if (amount == null) {
                amount = f.amount();
            }
            if (seller == null || seller.isBlank()) {
                seller = f.seller();
            }
            if (itemSummary == null || itemSummary.isBlank()) {
                itemSummary = f.itemSummary();
            }
        }

        InvoiceParseResult toResult(String name, boolean multi, String idPrefix) {
            List<String> all = new ArrayList<>(warnings);
            if (invoiceNo == null) {
                all.add("这一页没有识别出发票号码（可能是封面、明细页或扫描件），"
                        + "无法参与金额核对和查重，请确认是否需要手工补录");
            }

            ParsedInvoiceFields fields = new ParsedInvoiceFields(
                    invoiceNo, date, amount, seller, null, itemSummary, all);

            // 文件名带页码：报告里必须能指到"就是第 3 页那张"，否则二十几页没法对
            String suffix = "";
            if (multi) {
                suffix = (lastPage > firstPage)
                        ? " 第" + firstPage + "-" + lastPage + "页"
                        : " 第" + firstPage + "页";
            }
            String id = multi ? idPrefix + "p" + firstPage : idPrefix;

            Facts.Attachment attachment = new Facts.Attachment(
                    id, classify(fields), amount, date, invoiceNo, seller, name + suffix, itemSummary);
            return new InvoiceParseResult(attachment, all);
        }
    }
}

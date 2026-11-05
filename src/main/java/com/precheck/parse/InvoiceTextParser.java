package com.precheck.parse;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从发票文本里抽字段。纯字符串处理，不碰 PDF、不碰 AI。
 *
 * <p>为什么单独拆出来：抽取规则会一直调整（每遇到新票种就要改），
 * 单独一个类才能用固定文本做单元测试，改一次跑一次，不怕改坏。
 *
 * <p><b>为什么不能"按位置取"</b>：PDF 抽出来的文字流不等于视觉上的左右顺序。
 * 真实发票抽出来长这样（标签在前、值全挤在后面）：
 * <pre>
 * 电子发票（普通发票） 发票号码：
 * 开票日期：
 * ...
 * 26942000000996952231
 * 2026年09月05日
 * </pre>
 * 所以只能靠"值的形状"去认：20 位纯数字=发票号、年月日的格式=日期、
 * 大写金额后面跟的 ¥ 数字=价税合计。这比找"发票号码："后面的内容可靠得多。
 */
@Component
public class InvoiceTextParser {

    /** 全电发票号码是 20 位。 */
    private static final Pattern INVOICE_NO_20 = Pattern.compile("(?<!\\d)(\\d{20})(?!\\d)");
    /** 老版发票号码是 8 位。 */
    private static final Pattern INVOICE_NO_8 = Pattern.compile("(?<!\\d)(\\d{8})(?!\\d)");
    private static final Pattern DATE_CN =
            Pattern.compile("(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日");
    private static final Pattern DATE_ISO =
            Pattern.compile("(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})");
    /**
     * 大写金额紧跟小写金额 —— 价税合计这一行是最可靠的锚点：
     * "壹佰肆拾玖圆整 ¥149.00"。因为"金额""税额"那些格子没有大写，只有合计行有。
     */
    private static final Pattern UPPER_WITH_AMOUNT = Pattern.compile(
            "[零壹贰叁肆伍陆柒捌玖拾佰仟万亿]{2,}[圆元][整角分]?[^\\d¥￥]{0,4}[¥￥]?\\s*([0-9][0-9,]*\\.[0-9]{2})");
    /** 兜底：页面上所有 ¥ 金额。 */
    private static final Pattern MONEY = Pattern.compile("[¥￥]\\s*([0-9][0-9,]*\\.[0-9]{2})");
    /** 名称后面紧跟 18 位纳税人识别号 —— 用来区分购买方和销售方。
     *
     * <p>末尾的 (?![0-9A-Z]) 必不可少：不然 20 位的发票号会被当成"18 位识别号"的前 18 位，
     * 把上一行的"开票人："错认成单位名称。这个坑是靠测试才发现的。
     */
    private static final Pattern NAME_THEN_CODE =
            Pattern.compile("([^\\n]{2,40})[ \\t]*\\n\\s*([0-9A-Z]{18})(?![0-9A-Z])");
    /** 开票项目：税务局规定的写法是 *类别*品名，例如 *运输服务*客运服务费、*鞋*运动鞋。 */
    private static final Pattern ITEM = Pattern.compile("\\*([^*\\n]{1,20})\\*([^\\s\\n]{1,24})");

    public ParsedInvoiceFields parse(String text) {
        List<String> warnings = new ArrayList<>();
        String normalized = (text == null) ? "" : text.replace('\u00A0', ' ');

        if (normalized.isBlank()) {
            return new ParsedInvoiceFields(null, null, null, null, null, null,
                    List.of("这份 PDF 里没有文字层（可能是拍的照片或扫描件），读不出内容，请手工填写金额"));
        }

        String invoiceNo = match(INVOICE_NO_20, normalized)
                .or(() -> match(INVOICE_NO_8, normalized))
                .orElse(null);
        if (invoiceNo == null) {
            warnings.add("未识别到发票号码，无法参与查重");
        }

        LocalDate date = matchDate(normalized);
        if (date == null) {
            warnings.add("未识别到开票日期");
        }

        BigDecimal amount = matchAmount(normalized);
        if (amount == null) {
            warnings.add("未识别到价税合计金额，无法参与金额核对");
        }

        String buyer = null;
        String seller = null;
        List<String> names = new ArrayList<>();
        Matcher nm = NAME_THEN_CODE.matcher(normalized);
        while (nm.find()) {
            names.add(nm.group(1).trim());
        }
        if (names.size() >= 2) {
            buyer = names.get(0);
            seller = names.get(1);
        } else if (names.size() == 1) {
            seller = names.get(0);
            warnings.add("只识别到一个单位名称，无法确认是购买方还是销售方");
        } else {
            warnings.add("未识别到开票方名称");
        }

        String itemSummary = matchItem(normalized);

        return new ParsedInvoiceFields(invoiceNo, date, amount, seller, buyer, itemSummary, warnings);
    }

    /**
     * 抽开票项目名称。
     *
     * <p>形如 "*运输服务*客运服务费" 的写法是税务局的规范格式，非常稳定，
     * 所以这里直接还原成同样的形式保存，方便人看、也方便按关键词判断票据类型。
     */
    private String matchItem(String text) {
        Matcher m = ITEM.matcher(text);
        if (m.find()) {
            return "*" + m.group(1).trim() + "*" + m.group(2).trim();
        }
        return null;
    }

    private LocalDate matchDate(String text) {
        Matcher cn = DATE_CN.matcher(text);
        if (cn.find()) {
            return LocalDate.of(Integer.parseInt(cn.group(1)),
                    Integer.parseInt(cn.group(2)), Integer.parseInt(cn.group(3)));
        }
        Matcher iso = DATE_ISO.matcher(text);
        if (iso.find()) {
            return LocalDate.of(Integer.parseInt(iso.group(1)),
                    Integer.parseInt(iso.group(2)), Integer.parseInt(iso.group(3)));
        }
        return null;
    }

    private BigDecimal matchAmount(String text) {
        Matcher upper = UPPER_WITH_AMOUNT.matcher(text);
        if (upper.find()) {
            return toAmount(upper.group(1));
        }
        // 兜底：取页面上最大的 ¥ 金额。价税合计一定是所有金额里最大的那个
        // （它等于金额合计 + 税额），所以这个兜底在单张发票上是合理的。
        List<BigDecimal> all = new ArrayList<>();
        Matcher m = MONEY.matcher(text);
        while (m.find()) {
            all.add(toAmount(m.group(1)));
        }
        return all.stream().max(Comparator.naturalOrder()).orElse(null);
    }

    private BigDecimal toAmount(String raw) {
        return new BigDecimal(raw.replace(",", ""));
    }

    private Optional<String> match(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }
}

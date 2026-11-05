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
 *
 * <p><b>还有一种更恶劣的排版：每个字符占一行。</b>某些开票系统（或"打印成 PDF"）
 * 会把每个字都输出成独立的文本块，抽出来是这样：
 * <pre>
 * 发
 * 票
 * 号
 * 码
 * ：
 * 2
 * 6
 * 3
 * 7
 * ...
 * </pre>
 * 这种文本里 {@code \d{20}} 永远匹配不到，日期、金额也全完蛋。
 * 所以解析前先做一次<b>版面修复</b>：把连续的单字符行拼回一行。
 * 这不是"锦上添花"——二十页发票里有十九页是这种排版时，它就是能不能用的分界线。
 */
@Component
public class InvoiceTextParser {

    /** 全电发票号码是 20 位。
     *
     * <p>边界必须是"非字母数字"而不是"非数字"：纳税人识别号常常是
     * {@code 91370100MA3C…}，前 8 位纯数字后面跟个字母，用非数字边界会让它
     * 通过 8 位发票号的校验，把税号前缀当成发票号——号码一错，查重和合并全乱。
     */
    private static final Pattern INVOICE_NO_20 =
            Pattern.compile("(?<![0-9A-Za-z])(\\d{20})(?![0-9A-Za-z])");
    /** 老版发票号码是 8 位。边界理由同上。 */
    private static final Pattern INVOICE_NO_8 =
            Pattern.compile("(?<![0-9A-Za-z])(\\d{8})(?![0-9A-Za-z])");
    /**
     * 20 位发票号后面紧贴着 4 位开票年份。
     *
     * <p>逐字符排版的 PDF 拼接后，发票号和"2026"会粘成 24 位数字，
     * 这时 20 位的正则就匹配不上了。中间补一个换行把它拆开。
     * 正常排版的文本里号码和日期之间有换行，不会命中。
     */
    private static final Pattern GLUED_INVOICE_YEAR = Pattern.compile("(\\d{20})(20[2-9]\\d)");
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
        // 换行与不换行空格统一：不同 PDF、不同平台抽出来的文本可能带 \r\n 或 \u00A0，
        // 不归一的话 \r 会粘在抽出来的字段尾巴上（"某某大学\r" != "某某大学"）
        String raw = (text == null) ? ""
                : text.replace("\r\n", "\n").replace('\r', '\n').replace('\u00A0', ' ');

        if (raw.isBlank()) {
            return new ParsedInvoiceFields(null, null, null, null, null, null,
                    List.of("这份 PDF 里没有文字层（可能是拍的照片或扫描件），读不出内容，请手工填写金额"));
        }

        // 先修版面，再拆粘连，最后才拿去匹配
        final String normalized = GLUED_INVOICE_YEAR
                .matcher(repairVerticalLayout(raw))
                .replaceAll("$1\n$2");

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
     * 版面修复：把"连续的单字符行"拼回一行。
     *
     * <p>针对每个字都独占一行的排版（见类注释）。只合并<b>长度为 1 的行</b>，
     * 其余原样保留——正常排版的文本走一遍等于没动，所以不会影响已经能解析的票。
     *
     * <p>为什么敢这么合：单个字符单独成行本身没有语义，按顺序拼起来才恢复成
     * "{@code 发票号码：}"、"{@code 壹佰肆拾玖圆整¥149.00}" 这些有意义的串。
     *
     * <p>副作用要说清楚：如果两列内容都被打散成单字符行，会被拼进同一行。
     * 对"按形状取值"的抽取方式没有影响（我们不做位置判断），但会让人读着别扭——
     * 反正这段文本只给正则用，不给人看。
     */
    private String repairVerticalLayout(String text) {
        StringBuilder out = new StringBuilder(text.length());
        StringBuilder run = new StringBuilder();

        for (String line : text.split("\n", -1)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && trimmed.codePointCount(0, trimmed.length()) == 1) {
                run.append(trimmed);
                continue;
            }
            if (run.length() > 0) {
                out.append(run).append('\n');
                run.setLength(0);
            }
            out.append(line).append('\n');
        }
        if (run.length() > 0) {
            out.append(run).append('\n');
        }
        return out.toString();
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

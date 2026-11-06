package com.precheck.rule.impl;

import com.precheck.config.PrecheckRuleProperties;
import com.precheck.facts.Facts;
import com.precheck.rule.CheckRule;
import com.precheck.rule.Finding;
import com.precheck.support.LineItemMatcher;
import com.precheck.support.Money;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * R4：发票合计 = 明细合计。
 *
 * <p>和 R3 的区别：R3 查的是"员工填的数"，R4 查的是"票面证据"与"业务明细"之间对不对得上。
 * 少一张发票、多报一张发票、发票金额与明细金额不符，都会在这条里露出来。
 *
 * <p><b>关键设计：比对前先按发票号去重。</b>
 * 如果同一张发票传了两遍，直接相加会得到虚高的合计，然后你报一个"差 470 元"，
 * 而真正的问题只有一个——重复上传。同一个错误被放大成两个错误，员工会觉得系统在找茬。
 * 所以这里先把重复的排除，让金额差额反映真实差异（样例里就变成了正好差 20 元）。
 *
 * <p><b>第二个关键设计：总额对不上之后，逐条配对去定位。</b>
 * 只说"相差 20 元"，员工得自己对着一堆票和一堆明细挨个找；配上对之后可以说
 * "差在「住宿（两晚）」这一条：明细 680，票面 700"——同一个阻断，可操作性完全不同。
 * 但顺序不能反：<b>先判总额，再定位</b>。反了就变成"逐条对不上就卡人"，
 * 而一张综合票被拆成几条明细是完全合法的做法，那样会被全拦下。
 */
@Component
public class InvoiceTotalRule implements CheckRule {

    private final PrecheckRuleProperties props;

    public InvoiceTotalRule(PrecheckRuleProperties props) {
        this.props = props;
    }

    @Override
    public String id() {
        return "R4_INVOICE_TOTAL";
    }

    @Override
    public String title() {
        return "发票合计与明细合计一致";
    }

    @Override
    public List<Finding> check(Facts facts) {
        List<Facts.Attachment> invoices = facts.attachments().stream()
                .filter(Facts.Attachment::isInvoice)
                .toList();

        if (invoices.isEmpty()) {
            return List.of(Finding.info(id(), "attachments",
                    "没有找到发票附件，无法核对金额", "附件中没有 kind=INVOICE 的票据"));
        }
        if (facts.lineItems().isEmpty()) {
            return List.of(Finding.info(id(), "lineItems",
                    "没有费用明细，无法与发票核对", "Facts.lineItems 为空"));
        }

        // 按发票号去重（没有发票号的保留，后面查重规则会单独报）
        Set<String> seen = new HashSet<>();
        List<Facts.Attachment> counted = new ArrayList<>();
        List<Facts.Attachment> dropped = new ArrayList<>();
        int unparsed = 0;

        for (Facts.Attachment a : invoices) {
            String no = a.invoiceNo();
            boolean hasNo = no != null && !no.isBlank();
            if (hasNo && !seen.add(no)) {
                dropped.add(a);
                continue;
            }
            if (a.amount() == null) {
                unparsed++;
            }
            counted.add(a);
        }

        BigDecimal invoiceSum = Money.sum(counted.stream().map(Facts.Attachment::amount).toList());
        BigDecimal itemSum = Money.sum(facts.lineItems().stream().map(Facts.LineItem::amount).toList());

        if (counted.stream().allMatch(a -> a.amount() == null)) {
            return List.of(Finding.info(id(), "attachments",
                    "发票金额都没能识别出来，无法核对", "全部发票的 amount 为空"));
        }

        String evidence = buildEvidence(counted, dropped, unparsed);

        if (Money.same(invoiceSum, itemSum, props.getAmountTolerance())) {
            return List.of();
        }

        // 总量已经对不上了，这时候才轮到"逐条配对"出场——它的任务是把差额归到具体某一条明细。
        // 注意顺序：先判总额，再定位。反过来就成了"逐条对不上就卡人"，
        // 一张综合票被拆成几条明细的合法操作会被全拦下。
        LineItemMatcher.Result pairing =
                LineItemMatcher.match(facts.lineItems(), counted, props.getAmountTolerance());

        BigDecimal gap = Money.diff(invoiceSum, itemSum);
        return List.of(Finding.block(id(), "attachments",
                "发票合计 " + Money.fmt(invoiceSum) + " 元，与明细合计 " + Money.fmt(itemSum)
                        + " 元不一致，相差 " + Money.fmt(gap) + " 元",
                evidence + describePairing(pairing),
                buildSuggestion(invoiceSum, itemSum, pairing)));
    }

    private String buildEvidence(List<Facts.Attachment> counted, List<Facts.Attachment> dropped, int unparsed) {
        StringBuilder sb = new StringBuilder("计入 ").append(counted.size()).append(" 张发票：");
        for (int i = 0; i < counted.size(); i++) {
            Facts.Attachment a = counted.get(i);
            if (i > 0) {
                sb.append("、");
            }
            sb.append(label(a)).append(" ").append(Money.fmt(a.amount())).append(" 元");
        }
        if (!dropped.isEmpty()) {
            sb.append("；重复上传已排除 ").append(dropped.size()).append(" 张");
        }
        if (unparsed > 0) {
            sb.append("；另有 ").append(unparsed).append(" 张未识别出金额，未计入");
        }
        return sb.toString();
    }

    /** 在证据后面补一句配对结果，让员工知道系统是怎么比的。 */
    private String describePairing(LineItemMatcher.Result pairing) {
        if (pairing.hasNothing()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (!pairing.matched().isEmpty()) {
            parts.add("一致 " + pairing.matched().size() + " 对");
        }
        if (!pairing.suspected().isEmpty()) {
            parts.add("1 条明细与 1 张发票金额不符");
        }
        if (!pairing.orphanItems().isEmpty()) {
            parts.add(pairing.orphanItems().size() + " 条明细找不到对应发票");
        }
        if (!pairing.orphanInvoices().isEmpty()) {
            parts.add(pairing.orphanInvoices().size() + " 张发票找不到对应明细");
        }
        return parts.isEmpty() ? "" : "；按金额逐条配对：" + String.join("，", parts);
    }

    /**
     * 怎么改：能定位到某一条就说那一条，找不到就如实说"找不到"。
     *
     * <p>这三种输出对应三种确定性，绝不能混：
     * <ul>
     *   <li>能定位 —— 对应关系是被迫的（各剩一条），可以指着说；</li>
     *   <li>配不上 —— 但知道哪些明细/发票落单了，把它们列出来；</li>
     *   <li>什么都不知道 —— 退回"可能漏记明细/可能缺票"这种通用说法，不硬编。</li>
     * </ul>
     */
    private String buildSuggestion(BigDecimal invoiceSum, BigDecimal itemSum, LineItemMatcher.Result pairing) {

        if (!pairing.suspected().isEmpty()) {
            LineItemMatcher.Pair p = pairing.suspected().get(0);
            BigDecimal gap = p.gap();
            String direction = p.invoice().amount().compareTo(p.item().amount()) > 0 ? "票面多" : "票面少";
            // 这句话会被前端接在「怎么改：」后面，所以先给位置和两个数字，再给动作。
            return "差额在「" + text(p.item().desc()) + "」这一条：明细填 " + Money.fmt(p.item().amount())
                    + " 元，发票「" + label(p.invoice()) + "」票面 " + Money.fmt(p.invoice().amount())
                    + " 元，" + direction + " " + Money.fmt(gap) + " 元。"
                    + "请把这条明细改成 " + Money.fmt(p.invoice().amount())
                    + " 元，或说明这 " + Money.fmt(gap) + " 元怎么处理。";
        }

        if (pairing.hasOrphans()) {
            List<String> parts = new ArrayList<>();
            for (Facts.LineItem it : pairing.orphanItems()) {
                parts.add("明细「" + text(it.desc()) + "」" + amountOrUnknown(it.amount()) + "找不到对应发票");
            }
            for (Facts.Attachment a : pairing.orphanInvoices()) {
                parts.add("发票「" + label(a) + "」" + amountOrUnknown(a.amount()) + "找不到对应明细");
            }
            return "差额没能归到某一条明细上，请逐条核对：" + String.join("；", parts)
                    + "。可能是漏交了某张发票，也可能是某条明细的金额填错了。";
        }

        return "请逐项核对：" + (invoiceSum.compareTo(itemSum) > 0
                ? "发票金额多于明细，可能漏记了某笔费用的明细"
                : "发票金额少于明细，可能缺少某张发票");
    }

    /** 金额未知时不要说成 0 元，如实说"没识别出来"。 */
    private String amountOrUnknown(BigDecimal amount) {
        return amount == null ? "（金额未识别）" : Money.fmt(amount) + " 元";
    }

    private String label(Facts.Attachment a) {
        return (a.fileName() == null || a.fileName().isBlank()) ? a.id() : a.fileName();
    }

    private String text(String s) {
        return (s == null || s.isBlank()) ? "（未填写明细说明）" : s;
    }
}

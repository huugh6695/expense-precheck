package com.precheck.rule.impl;

import com.precheck.config.PrecheckRuleProperties;
import com.precheck.facts.Facts;
import com.precheck.rule.CheckRule;
import com.precheck.rule.Finding;
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

        BigDecimal gap = Money.diff(invoiceSum, itemSum);
        return List.of(Finding.block(id(), "attachments",
                "发票合计 " + Money.fmt(invoiceSum) + " 元，与明细合计 " + Money.fmt(itemSum)
                        + " 元不一致，相差 " + Money.fmt(gap) + " 元",
                evidence,
                "请逐项核对：" + (invoiceSum.compareTo(itemSum) > 0
                        ? "发票金额多于明细，可能漏记了某笔费用的明细"
                        : "发票金额少于明细，可能缺少某张发票")));
    }

    private String buildEvidence(List<Facts.Attachment> counted, List<Facts.Attachment> dropped, int unparsed) {
        StringBuilder sb = new StringBuilder("计入 ").append(counted.size()).append(" 张发票：");
        for (int i = 0; i < counted.size(); i++) {
            Facts.Attachment a = counted.get(i);
            if (i > 0) {
                sb.append("、");
            }
            sb.append(a.fileName() == null ? a.id() : a.fileName())
                    .append(" ").append(Money.fmt(a.amount())).append(" 元");
        }
        if (!dropped.isEmpty()) {
            sb.append("；重复上传已排除 ").append(dropped.size()).append(" 张");
        }
        if (unparsed > 0) {
            sb.append("；另有 ").append(unparsed).append(" 张未识别出金额，未计入");
        }
        return sb.toString();
    }
}

package com.precheck.rule.impl;

import com.precheck.facts.Facts;
import com.precheck.ledger.InvoiceHistory;
import com.precheck.rule.CheckRule;
import com.precheck.rule.Finding;
import com.precheck.support.Money;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * R5：同一张发票只能报销一次。
 *
 * <p>查两个方向，缺一个都不算真的查重：
 * <ol>
 *   <li><b>本次申请内部</b>：同一个发票号在这次上传里出现了两次 —— 传重了。</li>
 *   <li><b>历史台账</b>：这张票以前已经报销过 —— 重复报销。</li>
 * </ol>
 *
 * <p>为什么按发票号分组而不是按文件名：文件名可以随便改，
 * <b>发票号是国家税局给的唯一标识</b>，改了文件名也躲不过去。
 *
 * <p><b>历史命中为什么分两种级别：</b>如果上次也是<b>同一个人</b>报的，
 * 很可能是"申请被财务退回后重新提交"，这种情况直接阻断会让人寸步难行，
 * 所以降成「建议核对」；如果是<b>别人</b>报的，那就是典型的重复报销，必须阻断。
 *
 * <p><b>⚠️ 台账记录的是"提交过"，不是"报销成功"：</b>被退回的申请不会自动从台账里消失，
 * 所以同一人重提时会看到一条提醒，需要财务人工解除。这是本版明确的取舍——
 * 宁可多提醒一次，不可漏掉一次真正的重复报销。
 */
@Component
public class DuplicateInvoiceRule implements CheckRule {

    private final InvoiceHistory history;

    public DuplicateInvoiceRule(InvoiceHistory history) {
        this.history = history;
    }

    @Override
    public String id() {
        return "R5_DUPLICATE_INVOICE";
    }

    @Override
    public String title() {
        return "发票不重复报销";
    }

    @Override
    public List<Finding> check(Facts facts) {
        Map<String, List<Facts.Attachment>> byNo = groupByInvoiceNo(facts);

        List<Finding> findings = new ArrayList<>();
        checkWithinThisSubmission(byNo, findings);
        checkAgainstHistory(facts, byNo, findings);
        return findings;
    }

    /** 按发票号分组，保持上传顺序（报告里"涉及附件"的排列才自然）。 */
    private Map<String, List<Facts.Attachment>> groupByInvoiceNo(Facts facts) {
        Map<String, List<Facts.Attachment>> byNo = new LinkedHashMap<>();
        for (Facts.Attachment a : facts.attachments()) {
            String no = a.invoiceNo();
            if (no == null || no.isBlank()) {
                continue;
            }
            byNo.computeIfAbsent(no.trim(), k -> new ArrayList<>()).add(a);
        }
        return byNo;
    }

    /** 方向一：本次上传里就重复了。 */
    private void checkWithinThisSubmission(Map<String, List<Facts.Attachment>> byNo, List<Finding> findings) {
        byNo.forEach((no, group) -> {
            if (group.size() < 2) {
                return;
            }
            findings.add(Finding.block(id(), "attachments",
                    "发票号 " + no + " 重复提交 " + group.size() + " 次（" + Money.fmt(group.get(0).amount()) + " 元）",
                    "涉及附件：" + fileNames(group),
                    "同一张发票只能报销一次，请删除重复上传的附件；若是不同笔业务，请确认发票号是否填错"));
        });
    }

    /** 方向二：和历史台账撞了。 */
    private void checkAgainstHistory(Facts facts, Map<String, List<Facts.Attachment>> byNo,
                                     List<Finding> findings) {
        if (byNo.isEmpty()) {
            return;
        }
        // 本次内已经重复报过的，不再拿历史重复说一遍——同一件事说两遍只会显得系统啰嗦
        Set<String> numbers = new LinkedHashSet<>();
        byNo.forEach((no, group) -> {
            if (group.size() < 2) {
                numbers.add(no);
            }
        });
        if (numbers.isEmpty()) {
            return;
        }

        String applicant = (facts.application() == null) ? null : facts.application().applicant();
        history.lookup(numbers).forEach((no, entry) -> {
            boolean sameApplicant = applicant != null && !applicant.isBlank()
                    && entry.applicant() != null
                    && applicant.trim().equalsIgnoreCase(entry.applicant().trim());

            String tail = entry.tail().isEmpty() ? "" : "（" + entry.tail() + "）";
            String evidence = "台账记录：" + entry.describe()
                    + (entry.amount() == null ? "" : "，金额 " + Money.fmt(entry.amount()) + " 元")
                    + (entry.invoiceDate() == null ? "" : "，开票日期 " + entry.invoiceDate());

            if (sameApplicant) {
                findings.add(Finding.warn(id(), "attachments",
                        "这张发票你以前报过" + tail + " —— " + entry.describe(),
                        evidence,
                        "如果上次申请被退回后重新提交，请联系财务在台账里解除这条记录；"
                                + "如果确实是新的一笔，请核对手里的发票号"));
            } else {
                findings.add(Finding.block(id(), "attachments",
                        "这张发票已经在台账里" + tail + " —— " + entry.describe(),
                        evidence,
                        "同一张发票不能报销两次，请核实是否重复报销；"
                                + "如果确认不是同一张，请检查发票号是否识别错误"));
            }
        });
    }

    private String fileNames(List<Facts.Attachment> group) {
        return group.stream()
                .map(a -> a.fileName() == null ? a.id() : a.fileName())
                .reduce((x, y) -> x + "、" + y)
                .orElse("");
    }
}

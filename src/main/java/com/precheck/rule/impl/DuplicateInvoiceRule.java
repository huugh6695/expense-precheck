package com.precheck.rule.impl;

import com.precheck.facts.Facts;
import com.precheck.rule.CheckRule;
import com.precheck.rule.Finding;
import com.precheck.support.Money;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * R5：同一张发票不能报两次。
 *
 * <p>这是整套检查里"最硬"的一条——不涉及任何判断，纯粹是事实：
 * 同一个发票号出现两次，就是重复报销（财务最忌讳的事，严重的属于违规）。
 *
 * <p>也是最适合演示的一条：结果无可争辩，而且一句话就能让人明白系统的价值。
 *
 * <p>为什么按发票号分组而不是按文件名：文件名可以随便改，
 * <b>发票号是国家税局给的唯一标识</b>，改了文件名也躲不过去。
 */
@Component
public class DuplicateInvoiceRule implements CheckRule {

    @Override
    public String id() {
        return "R5_DUPLICATE_INVOICE";
    }

    @Override
    public String title() {
        return "发票不重复提交";
    }

    @Override
    public List<Finding> check(Facts facts) {
        Map<String, List<Facts.Attachment>> byNo = new LinkedHashMap<>();
        for (Facts.Attachment a : facts.attachments()) {
            String no = a.invoiceNo();
            if (no == null || no.isBlank()) {
                continue;
            }
            byNo.computeIfAbsent(no.trim(), k -> new ArrayList<>()).add(a);
        }

        List<Finding> findings = new ArrayList<>();
        byNo.forEach((no, group) -> {
            if (group.size() < 2) {
                return;
            }
            String files = group.stream()
                    .map(a -> a.fileName() == null ? a.id() : a.fileName())
                    .reduce((x, y) -> x + "、" + y)
                    .orElse("");
            String amount = Money.fmt(group.get(0).amount());

            findings.add(Finding.block(id(), "attachments",
                    "发票号 " + no + " 重复提交 " + group.size() + " 次（" + amount + " 元）",
                    "涉及附件：" + files,
                    "同一张发票只能报销一次，请删除重复上传的附件；若是不同笔业务，请确认发票号是否填错"));
        });
        return findings;
    }
}

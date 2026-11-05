package com.precheck.rule.impl;

import com.precheck.config.PrecheckRuleProperties;
import com.precheck.facts.Facts;
import com.precheck.rule.CheckRule;
import com.precheck.rule.Finding;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * R2：必备附件类型是否齐全。
 *
 * <p>这是最贴近真实痛点的一条规则。财务最常退回的不是金额算错，而是
 * <b>"差旅只交了机票没有住宿票"、"报销单一张票都没附"</b>——
 * 这种事审批人一眼就能看出来，却要等一两天轮到他才说，员工就得重来一轮。
 *
 * <p><b>"哪类申请要哪些票"不写在这个类里</b>，而是从 rules.yml 读
 * （precheck.rules.required-attachments）。这样财务说"我们招待费也要口味单据"，
 * 改配置就行，不用碰代码——这条规则本身只负责"按清单点名，缺什么报什么"。
 */
@Component
public class RequiredAttachmentRule implements CheckRule {

    private final PrecheckRuleProperties props;

    public RequiredAttachmentRule(PrecheckRuleProperties props) {
        this.props = props;
    }

    @Override
    public String id() {
        return "R2_REQUIRED_ATTACHMENT";
    }

    @Override
    public String title() {
        return "必备附件类型齐全";
    }

    @Override
    public List<Finding> check(Facts facts) {
        List<Facts.Attachment> attachments = facts.attachments();

        if (attachments.isEmpty()) {
            return List.of(Finding.block(id(), "attachments",
                    "这份申请一份附件都没有",
                    "附件数量为 0",
                    "请先把发票/票据上传上来；没有任何票据的支出无法报销"));
        }

        String category = (facts.application() == null) ? null : facts.application().category();
        List<String> required = props.requiredFor(category);

        List<Finding> findings = new ArrayList<>();
        for (String kind : required) {
            boolean present = attachments.stream()
                    .anyMatch(a -> kind.equalsIgnoreCase(a.kind()));
            if (!present) {
                findings.add(Finding.block(id(), "attachments",
                        missingMessage(kind, category),
                        describe(attachments),
                        missingSuggestion(kind)));
            }
        }

        long unknown = attachments.stream()
                .filter(a -> Facts.Attachment.KIND_OTHER.equalsIgnoreCase(a.kind()))
                .count();
        if (unknown > 0) {
            // 不阻断：识别不出类型是系统的问题，不是员工的错，但必须说清楚
            findings.add(Finding.warn(id(), "attachments",
                    "有 " + unknown + " 份附件没能识别出票据类型，附件是否齐全无法确认",
                    describe(attachments),
                    "如果这些附件里含住宿票或交通票，请手工填写金额后重试，或联系管理员"));
        }

        return findings;
    }

    private String missingMessage(String kind, String category) {
        if (Facts.Attachment.KIND_INVOICE.equalsIgnoreCase(kind)) {
            return "没有可用的发票，无法报销";
        }
        String prefix = (category == null) ? "本次申请" : "「" + category + "」申请";
        return prefix + "缺少" + label(kind) + "票据";
    }

    private String missingSuggestion(String kind) {
        if (Facts.Attachment.KIND_TRAVEL.equalsIgnoreCase(kind)) {
            return "请补上往返的交通票据；如果是自驾或他人代订，请在事由里说明";
        }
        if (Facts.Attachment.KIND_HOTEL.equalsIgnoreCase(kind)) {
            return "请补上住宿发票；如果是当天往返不需要住宿，请在事由里说明";
        }
        if (Facts.Attachment.KIND_INVOICE.equalsIgnoreCase(kind)) {
            return "请上传本次报销对应的发票";
        }
        return "请补上「" + label(kind) + "」类票据";
    }

    /** 把附件列出来当证据：类型 + 文件名，员工一眼能对上自己传了什么。 */
    private String describe(List<Facts.Attachment> attachments) {
        return "已上传 " + attachments.size() + " 份：" + attachments.stream()
                .map(a -> label(a.kind()) + "·" + (a.fileName() == null ? a.id() : a.fileName()))
                .collect(Collectors.joining("、"));
    }

    private String label(String kind) {
        if (Facts.Attachment.KIND_TRAVEL.equalsIgnoreCase(kind)) {
            return "交通";
        }
        if (Facts.Attachment.KIND_HOTEL.equalsIgnoreCase(kind)) {
            return "住宿";
        }
        if (Facts.Attachment.KIND_INVOICE.equalsIgnoreCase(kind)) {
            return "发票";
        }
        return kind == null ? "未识别" : kind;
    }
}

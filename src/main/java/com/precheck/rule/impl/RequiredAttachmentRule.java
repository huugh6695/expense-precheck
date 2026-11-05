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
 *
 * <p><b>缺票不等于一定阻断</b>，这里分三种情形，故意的：
 * <ol>
 *   <li><b>算得出来的直接放行</b>：差旅缺住宿票，但起止日期是同一天 ⇒ 当天往返，
 *       根本不该有住宿票。不问员工，因为日期是表单里已有的硬事实。</li>
 *   <li><b>算不出来的降级</b>：跨天却缺住宿票，员工在「票据例外说明」里选了原因并写了情况
 *       （客户安排住宿 / 自驾 / 其他）⇒ 降为「建议核对」，交给人核实，不机械卡死。</li>
 *   <li><b>其余阻断</b>：缺票且没有任何说明 ⇒ 阻断，并明确告诉员工怎么把这一项消掉。</li>
 * </ol>
 *
 * <p>为什么情形二必须"选原因 + 写说明"两件事都做：只勾下拉不写字，等于"我不解释"，
 * 那不能拿来换掉一条阻断项；而<b>只靠读事由里的关键词</b>更糟——那等于教员工
 * 写一句万金油话术绕过检查，正是这个工具最想避免的事。
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

        Facts.ApplicationInfo app = facts.application();
        String category = (app == null) ? null : app.category();
        List<String> required = props.requiredFor(category);

        List<Finding> findings = new ArrayList<>();
        for (String kind : required) {
            boolean present = attachments.stream()
                    .anyMatch(a -> kind.equalsIgnoreCase(a.kind()));
            if (present) {
                continue;
            }

            // 情形一：起止日期同一天 ⇒ 当天往返是不会算错的硬事实，直接放行，不问员工。
            if (Facts.Attachment.KIND_HOTEL.equalsIgnoreCase(kind) && isSameDayTrip(app)) {
                findings.add(Finding.info(id(), "attachments",
                        "起止日期都是 " + app.startDate() + "，按当天往返处理，不要求住宿票",
                        "费用发生日期：" + app.startDate() + " 至 " + app.endDate(),
                        "如果实际是跨天出差，请先改正起止日期再重新预检"));
                continue;
            }

            // 情形二：员工声明了例外（选原因 + 写说明）⇒ 降为"建议核对"，交给人判断。
            Facts.Exemption exemption = exemptionFor(facts, kind);
            if (exemption != null && exemption.usable()) {
                findings.add(Finding.warn(id(), "attachments",
                        "缺少" + label(kind) + "票据 —— 员工声明：" + exemption.label(),
                        describe(attachments) + "；声明说明：" + exemption.note().trim(),
                        "请审批人核实该声明是否属实（必要时要求补充证明）；核实属实即可放行"));
                continue;
            }

            // 情形三：缺票且没有可用声明 ⇒ 阻断。
            String suggestion = (exemption == null)
                    ? missingSuggestion(kind)
                    : "你选择了「" + (exemption.label() == null ? exemption.code() : exemption.label())
                    + "」，但没填写说明，这条声明暂不生效。补上说明后，这一项会降为「建议核对」，不再阻断。";

            findings.add(Finding.block(id(), "attachments",
                    missingMessage(kind, category),
                    describe(attachments),
                    suggestion));
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

    /**
     * 是不是当天往返。
     *
     * <p>这是这条规则里唯一"不用问人"的判断：起止日期是同一天，就不存在住宿需求。
     * 让员工自己声明"我当天往返"是多余的一步，而且给了撒谎的空间；
     * 日期是表单里已经填过的东西，直接算就行。
     *
     * <p>缺任一日期时返回 false——不知道就不放行，宁可让他补个日期。
     */
    private boolean isSameDayTrip(Facts.ApplicationInfo app) {
        return app != null
                && app.startDate() != null
                && app.endDate() != null
                && app.startDate().equals(app.endDate());
    }

    /** 找出针对某类票据的例外声明；没有就返回 null。 */
    private Facts.Exemption exemptionFor(Facts facts, String kind) {
        return facts.exemptions().stream()
                .filter(e -> e != null && e.kind() != null && kind.equalsIgnoreCase(e.kind()))
                .findFirst()
                .orElse(null);
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
            // 注意：这里不能写"请在事由里说明"——没有任何代码读事由，
            // 让员工写一句没人看的话，比不提示更糟。
            return "请补上往返的交通票据；如果是自驾或他人代订，请补交相应证明（油费/过路费票据，或对方出具的情况说明）";
        }
        if (Facts.Attachment.KIND_HOTEL.equalsIgnoreCase(kind)) {
            return "请补上住宿发票；如果确实没有住宿票（当天往返 / 客户已安排 / 自驾），"
                    + "请在「票据例外说明」里选择原因并填写情况，这一项会降为「建议核对」，不再阻断";
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

package com.precheck.rule.impl;

import com.precheck.config.PrecheckRuleProperties;
import com.precheck.facts.Facts;
import com.precheck.rule.CheckRule;
import com.precheck.rule.Finding;
import com.precheck.support.Money;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * R3：申报金额 = 明细合计。
 *
 * <p>这条查的是员工自己填的数对不对：他在"申报金额"栏写 1130，
 * 但明细加起来只有 1080，说明有一笔漏写了，或者金额敲错了。
 *
 * <p>这类错误在真实场景里非常常见，而且是最容易被财务一次退回的原因——
 * 因为审批人看到的是一笔对不上的账，没法判断该信哪个数。
 */
@Component
public class DeclaredAmountRule implements CheckRule {

    private final PrecheckRuleProperties props;

    public DeclaredAmountRule(PrecheckRuleProperties props) {
        this.props = props;
    }

    @Override
    public String id() {
        return "R3_DECLARED_AMOUNT";
    }

    @Override
    public String title() {
        return "申报金额与明细合计一致";
    }

    @Override
    public List<Finding> check(Facts facts) {
        Facts.ApplicationInfo app = facts.application();
        if (app == null) {
            return List.of(Finding.info(id(), "application", "申请单信息为空，无法核对金额", "Facts.application 为 null"));
        }

        BigDecimal declared = app.declaredAmount();
        if (declared == null) {
            return List.of(Finding.block(id(), "declaredAmount",
                    "申报金额未填写", "application.declaredAmount 为空",
                    "请填写本次申请的申报总金额"));
        }

        if (facts.lineItems().isEmpty()) {
            // 明细都没有，谈不上"不一致"。这里如实说明，交给完整性规则去报缺明细，
            // 避免同一条问题被两条规则各报一次。
            return List.of(Finding.info(id(), "lineItems", "没有费用明细，无法与申报金额核对", "Facts.lineItems 为空"));
        }

        BigDecimal itemSum = Money.sum(facts.lineItems().stream().map(Facts.LineItem::amount).toList());

        if (Money.same(declared, itemSum, props.getAmountTolerance())) {
            return List.of();
        }

        BigDecimal gap = Money.diff(declared, itemSum);
        return List.of(Finding.block(id(), "declaredAmount",
                "申报金额 " + Money.fmt(declared) + " 元，与明细合计 " + Money.fmt(itemSum)
                        + " 元不一致，相差 " + Money.fmt(gap) + " 元",
                "明细 " + facts.lineItems().size() + " 条，合计 " + Money.fmt(itemSum) + " 元",
                "请核对：要么补上遗漏的明细，要么把申报金额改为 " + Money.fmt(itemSum) + " 元"));
    }
}

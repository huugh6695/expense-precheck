package com.precheck.rule;

import com.precheck.facts.Facts;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 预检引擎：把所有规则跑一遍，汇总成一份报告。
 *
 * <p>它自己不认识任何具体规则——构造时由 Spring 把所有 {@link CheckRule} 的实现注入进来。
 * 所以你以后加规则时，这个类一个字都不用改。
 */
@Service
public class PrecheckEngine {

    private final List<CheckRule> rules;

    public PrecheckEngine(List<CheckRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public PrecheckReport run(Facts facts) {
        if (facts == null) {
            throw new IllegalArgumentException("facts 不能为空");
        }

        List<Finding> all = new ArrayList<>();
        for (CheckRule rule : rules) {
            try {
                List<Finding> findings = rule.check(facts);
                if (findings != null) {
                    all.addAll(findings);
                }
            } catch (RuntimeException e) {
                // 一条规则写错，不能拖垮整个预检。
                // 这里如实告诉用户"这项没查成"，而不是假装通过——假装通过比报错更危险。
                all.add(Finding.warn(rule.id(), null,
                        "规则「" + rule.title() + "」执行失败，本次未完成检查",
                        e.getClass().getSimpleName() + ": " + e.getMessage(),
                        "请稍后重试；若持续失败请联系管理员"));
            }
        }
        return PrecheckReport.of(all);
    }

    /** 当前加载了多少条规则，方便启动时自检。 */
    public int ruleCount() {
        return rules.size();
    }
}

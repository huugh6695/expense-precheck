package com.precheck.rule;

import com.precheck.facts.Facts;

import java.util.List;

/**
 * 一条检查规则。
 *
 * <p>这是整套系统的骨架：加一条新规则 = 新增一个实现类 + 打上 @Component，
 * <b>不需要改引擎、不需要改任何已有代码</b>。Spring 会把它自动收进引擎的规则列表里。
 *
 * <p>写规则的纪律：
 * <ul>
 *   <li>只读 facts，绝不修改；</li>
 *   <li>没问题就返回空列表，别返回 null；</li>
 *   <li>数据不全时不要瞎猜——返回一条 INFO 说明"无法判断"，比给出错误结论好得多。</li>
 * </ul>
 */
public interface CheckRule {

    /** 规则编号，出现在报告的每一条里，方便回溯是哪个规则报的。 */
    String id();

    /** 规则名称，给人看的。 */
    String title();

    /** 执行检查。 */
    List<Finding> check(Facts facts);
}

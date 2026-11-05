package com.precheck.rule;

/**
 * 检查发现的一条问题。所有规则都只产出它，输出层拿到就能直接渲染清单。
 *
 * <p>六个字段各有用处，少一个都会让报告变糊：
 * <ul>
 *   <li>ruleId —— 哪条规则报的，方便以后统计"哪种退回最常见"；</li>
 *   <li>level  —— 阻断还是提醒，决定员工能不能直接提交；</li>
 *   <li>field  —— 问题出在哪个字段，前端可以高亮那一栏；</li>
 *   <li>message —— 一句话说清"哪里错了、差多少"；</li>
 *   <li>evidence —— 证据，必须能指回具体附件/数字，否则员工不服；</li>
 *   <li>suggestion —— 怎么改，这句是产品价值感所在，不能省。</li>
 * </ul>
 *
 * @param source 这条结论是谁给的：RULE（代码判定）/ AI（模型判定）。将来报告里要区分，
 *               因为 AI 的判断要允许员工申诉，代码判定的金额差异则是硬事实。
 */
public record Finding(
        String ruleId,
        Level level,
        String field,
        String message,
        String evidence,
        String suggestion,
        Source source
) {

    public enum Source { RULE, AI }

    public static Finding block(String ruleId, String field, String message, String evidence, String suggestion) {
        return new Finding(ruleId, Level.BLOCK, field, message, evidence, suggestion, Source.RULE);
    }

    public static Finding warn(String ruleId, String field, String message, String evidence, String suggestion) {
        return new Finding(ruleId, Level.WARN, field, message, evidence, suggestion, Source.RULE);
    }

    public static Finding info(String ruleId, String field, String message, String evidence) {
        return new Finding(ruleId, Level.INFO, field, message, evidence, null, Source.RULE);
    }
}

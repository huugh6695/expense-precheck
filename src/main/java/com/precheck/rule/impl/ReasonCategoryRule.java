package com.precheck.rule.impl;

import com.precheck.ai.AiClient;
import com.precheck.facts.Facts;
import com.precheck.rule.CheckRule;
import com.precheck.rule.Finding;
import com.precheck.rule.Level;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * A1：事由与费用类别/明细的语义矛盾（唯一一条用大模型的规则）。
 *
 * <p>这条规则要解决的问题，代码写不出来：申请事由写"办公用品采购"，
 * 但明细里是护肤品、开票方是化妆品公司——这不违反任何一条金额规则，
 * 却明显不符合科目。只有理解语义才发现得了。
 *
 * <p><b>给模型划的三条线</b>（这里的设计比提示词本身重要）：
 * <ol>
 *   <li>不许算钱、不许判重复——那些是硬规则的活。让模型算数只会引入新错误。</li>
 *   <li>不许说"批准"或"拒绝"——它只能指出疑点，决定权留给人。</li>
 *   <li>没把握就给 OK——宁可漏报也不能误报，员工被冤枉两次就不信这个工具了。</li>
 * </ol>
 *
 * <p>结果是 {@link Finding.Source#AI}，页面上会打"AI 判断"标签，
 * 因为这种判断允许员工解释和申诉，跟"金额差 31 元"这种硬事实不是一个性质。
 */
@Component
public class ReasonCategoryRule implements CheckRule {

    private static final String SYSTEM_PROMPT = """
            你是一名企业费用报销的初审员，只负责指出材料中的语义疑点。
            你不做批准或拒绝的决定，也不判断金额是否一致、发票是否重复（那些由程序完成）。

            判断标准：
            - 申请事由与"费用类别"或"费用明细"明显对不上（例如事由写办公用品，明细却是化妆品）；
            - 事由与开票方业务明显不相关（例如事由写办公用品，开票方是酒店或航空公司）；
            - 事由过于笼统，无法看出与这些费用有什么关系。

            输出要求（必须严格遵守）：
            1. 只输出一个 JSON 对象，不要任何解释文字、不要 markdown 代码块；
            2. 格式：{"verdict":"OK","reason":"...","suggestion":"..."}
               或 {"verdict":"SUSPECT","reason":"...","suggestion":"..."}
            3. reason 和 suggestion 都用中文，各不超过 60 字；verdict 为 OK 时 suggestion 给空字符串；
            4. 没有把握时一律输出 OK —— 宁可漏报，不要误报。
            """;

    private final AiClient ai;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public ReasonCategoryRule(AiClient ai) {
        this.ai = ai;
    }

    @Override
    public String id() {
        return "A1_REASON_SEMANTIC";
    }

    @Override
    public String title() {
        return "事由与费用类别是否相符（AI）";
    }

    @Override
    public List<Finding> check(Facts facts) {
        // 没配 Key 或关了开关：静默跳过，页面上不会多出一条"没检查"的噪音
        if (!ai.available()) {
            return List.of();
        }
        if (facts.application() == null || isBlank(facts.application().reason())) {
            return List.of();
        }

        Optional<String> raw = ai.complete(SYSTEM_PROMPT, buildUserPrompt(facts));
        if (raw.isEmpty()) {
            // 模型没响应：如实说明这一项没查，而不是假装通过
            return List.of(Finding.info(id(), "reason",
                    "AI 语义检查本次未完成（模型无响应或超时）",
                    "金额与附件等代码规则已正常执行，这一项请人工留意"));
        }

        return parse(raw.get(), facts);
    }

    private List<Finding> parse(String content, Facts facts) {
        Map<?, ?> json;
        try {
            json = mapper.readValue(stripCodeFence(content), Map.class);
        } catch (Exception e) {
            return List.of(Finding.info(id(), "reason",
                    "AI 返回的内容无法解析，本次语义检查未生效",
                    "原始片段：" + cut(content, 80)));
        }

        Object verdict = json.get("verdict");
        if (!(verdict instanceof String v) || !"SUSPECT".equalsIgnoreCase(v.trim())) {
            return List.of();
        }

        String reason = asText(json.get("reason"));
        String suggestion = asText(json.get("suggestion"));
        if (isBlank(reason)) {
            reason = "事由与费用类别或明细可能对不上，建议人工确认";
        }

        Finding f = new Finding(
                id(),
                Level.WARN,               // 只提醒，不阻断：语义判断有误判可能，不能挡住员工提交
                "reason",
                "AI 指出：" + cut(reason, 120),
                "依据：" + evidence(facts),
                isBlank(suggestion) ? "请确认事由描述是否与这些费用相符，必要时补充说明" : cut(suggestion, 120),
                Finding.Source.AI
        );
        return List.of(f);
    }

    private String buildUserPrompt(Facts facts) {
        Facts.ApplicationInfo app = facts.application();
        String items = facts.lineItems().stream()
                .map(li -> li.desc() + "（" + li.amount() + " 元）")
                .collect(Collectors.joining("；"));
        String sellers = facts.attachments().stream()
                .map(Facts.Attachment::seller)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.joining("；"));

        return """
                申请事由：%s
                费用类别：%s
                费用明细：%s
                发票开票方：%s

                请判断：这份申请的事由，与费用类别、明细内容、开票方之间是否存在明显矛盾？
                """.formatted(
                app.reason(),
                isBlank(app.category()) ? "未填写" : app.category(),
                isBlank(items) ? "未填写" : items,
                isBlank(sellers) ? "无发票" : sellers);
    }

    private String evidence(Facts facts) {
        String items = facts.lineItems().stream()
                .map(Facts.LineItem::desc)
                .collect(Collectors.joining("、"));
        return "事由「" + facts.application().reason() + "」"
                + "，类别「" + facts.application().category() + "」"
                + (isBlank(items) ? "" : "，明细「" + items + "」");
    }

    /** 有些模型会习惯性套 ```json 代码块，去掉再解析。 */
    private String stripCodeFence(String content) {
        String s = content.trim();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline > 0) {
                s = s.substring(firstNewline + 1);
            }
            int end = s.lastIndexOf("```");
            if (end > 0) {
                s = s.substring(0, end);
            }
        }
        return s.trim();
    }

    private String asText(Object value) {
        return (value instanceof String s) ? s.trim() : "";
    }

    private String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

package com.precheck.config;

import com.precheck.facts.Facts;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可由 YAML 决定的规则参数（对应 rules.yml 的 precheck.rules.*）。
 *
 * <p>哪些东西该外置、哪些该留在代码里，我按一条标准判断：
 * <b>"每家企业不一样"的放配置，"逻辑本身"留代码。</b>
 * 容差、必备票据、票据关键词属于前者；"拿发票合计跟明细合计比"这种玩法属于后者。
 *
 * <p>字段默认都是 null/空，取值时再退回内置默认。这样"配了就用配置、没配就用默认"，
 * 不会出现"配置文件只写了一半、另一半悄悄消失"的意外。
 *
 * <p><b>为什么必备附件用 List 而不是 Map</b>：一开始写的是
 * <pre>required-attachments: {差旅: [TRAVEL, HOTEL]}</pre>
 * 结果中文 key 压根没绑定上（Spring 的配置名规范化不接受非 ASCII 的元素名），
 * 表现得像"配置没生效"。改成"列表 + category 字段"后，中文只出现在值里，绑定就正常了。
 * 这个坑值得记住：<b>YAML 里中文可以当值，别当 key。</b>
 */
@Component
@ConfigurationProperties(prefix = "precheck.rules")
public class PrecheckRuleProperties {

    /** 没配 required-attachments 时用的内置默认。 */
    private static final List<Requirement> DEFAULT_REQUIREMENTS = List.of(
            new Requirement("差旅", List.of(Facts.Attachment.KIND_TRAVEL, Facts.Attachment.KIND_HOTEL)),
            new Requirement("招待", List.of(Facts.Attachment.KIND_INVOICE)),
            new Requirement("办公", List.of(Facts.Attachment.KIND_INVOICE)),
            new Requirement("默认", List.of(Facts.Attachment.KIND_INVOICE))
    );

    /** 内置的票据关键词（顺序有意义：先判住宿再判交通）。 */
    private static final Map<String, List<String>> DEFAULT_KEYWORDS = new LinkedHashMap<>();

    static {
        DEFAULT_KEYWORDS.put(Facts.Attachment.KIND_HOTEL, List.of(
                "住宿服务", "住宿费", "客房", "酒店", "宾馆", "旅馆", "民宿", "公寓"));
        DEFAULT_KEYWORDS.put(Facts.Attachment.KIND_TRAVEL, List.of(
                "运输服务", "客运", "航空", "机票", "铁路", "火车", "动车", "高铁",
                "出租车", "网约车", "滴滴", "出行", "航旅", "机场", "车费"));
    }

    /** 金额比较容差，默认 1 分钱。 */
    private BigDecimal amountTolerance = new BigDecimal("0.01");

    /** 类别 → 必须齐备的票据类型；null/空表示用内置默认。 */
    private List<Requirement> requiredAttachments;

    /** 票据类型 → 判定关键词；null/空表示用内置默认，配了则按 key 覆盖默认。 */
    private Map<String, List<String>> ticketKeywords;

    /**
     * 查某个费用类别需要哪些票据。
     *
     * <p>匹配顺序：先精确匹配（"差旅"），再包含匹配（"差旅报销"也能命中"差旅"），
     * 最后落到「默认」。这样客户把类别写成"差旅费"也不会漏掉规则。
     */
    public List<String> requiredFor(String category) {
        List<Requirement> rules = hasRequirements()
                ? requiredAttachments
                : DEFAULT_REQUIREMENTS;

        if (category != null) {
            String key = category.trim();
            for (Requirement r : rules) {
                if (key.equals(r.category())) {
                    return r.required();
                }
            }
            for (Requirement r : rules) {
                if (!"默认".equals(r.category()) && r.category() != null && key.contains(r.category())) {
                    return r.required();
                }
            }
        }
        for (Requirement r : rules) {
            if ("默认".equals(r.category())) {
                return r.required();
            }
        }
        return List.of(Facts.Attachment.KIND_INVOICE);
    }

    /** 某种票据类型的判定关键词。 */
    public List<String> keywordsFor(String kind) {
        return effectiveKeywords().getOrDefault(kind, List.of());
    }

    /** 供 InvoiceParser 按优先级遍历（顺序 = 判定优先级）。 */
    public Map<String, List<String>> effectiveKeywords() {
        Map<String, List<String>> merged = new LinkedHashMap<>(DEFAULT_KEYWORDS);
        if (ticketKeywords != null && !ticketKeywords.isEmpty()) {
            // putAll 不会打乱已有 key 的位置，所以 HOTEL 仍然排在 TRAVEL 前面
            merged.putAll(ticketKeywords);
        }
        return merged;
    }

    private boolean hasRequirements() {
        return requiredAttachments != null && !requiredAttachments.isEmpty();
    }

    public BigDecimal getAmountTolerance() {
        return amountTolerance;
    }

    public void setAmountTolerance(BigDecimal amountTolerance) {
        this.amountTolerance = amountTolerance;
    }

    public List<Requirement> getRequiredAttachments() {
        return requiredAttachments;
    }

    public void setRequiredAttachments(List<Requirement> requiredAttachments) {
        this.requiredAttachments = (requiredAttachments == null) ? null : new ArrayList<>(requiredAttachments);
    }

    public Map<String, List<String>> getTicketKeywords() {
        return ticketKeywords;
    }

    public void setTicketKeywords(Map<String, List<String>> ticketKeywords) {
        this.ticketKeywords = ticketKeywords;
    }

    /**
     * 一条必备附件要求：某类申请必须有哪几种票据。
     *
     * @param category 费用类别，写「默认」表示兜底
     * @param required 必须齐备的票据类型列表
     */
    public record Requirement(String category, List<String> required) {
    }
}

package com.precheck.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 前端提交的申请单。
 *
 * <p>注意它<b>不是</b> Facts：这是"用户填的东西"，Facts 是"系统认定的事实"。
 * 中间差的那一步就是解析（把 PDF 变成结构化的发票信息），所以两者不能混用一个类。
 *
 * @param overrides 手工修正的金额。员工看到某张发票没识别出来，可以在页面上直接填，
 *                  这是"解析失败也不能堵死流程"的兜底通道。
 */
public record PrecheckRequest(
        String type,
        String applicant,
        BigDecimal declaredAmount,
        String reason,
        String category,
        LocalDate startDate,
        LocalDate endDate,
        List<LineItemReq> lineItems,
        List<FileOverride> overrides
) {

    public PrecheckRequest {
        lineItems = (lineItems == null) ? List.of() : List.copyOf(lineItems);
        overrides = (overrides == null) ? List.of() : List.copyOf(overrides);
    }

    /** 一条费用明细。 */
    public record LineItemReq(String desc, BigDecimal amount) {
    }

    /** 手工修正：文件名 → 金额。 */
    public record FileOverride(String fileName, BigDecimal amount) {
    }
}

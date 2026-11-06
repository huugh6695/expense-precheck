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
 * @param exemptions 例外声明。目前只用于"差旅缺住宿票"：员工选了原因并写明情况，
 *                   这条必票要求就从"阻断"降为"建议核对"。见 Facts.Exemption。
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
        List<FileOverride> overrides,
        List<ExemptionReq> exemptions
) {

    public PrecheckRequest {
        lineItems = (lineItems == null) ? List.of() : List.copyOf(lineItems);
        overrides = (overrides == null) ? List.of() : List.copyOf(overrides);
        exemptions = (exemptions == null) ? List.of() : List.copyOf(exemptions);
    }

    /**
     * 换一个申请人，其他字段不变。
     *
     * <p>用来把"请求体里自称的申请人"替换成"验签得到的可信申请人"。
     * 为什么不直接信请求体：那是员工在自己电脑上填的，改个名字就能让 R5 的
     * "这张票别人报过"失效。接入 OA 之后，姓名只能从签名里来。
     */
    public PrecheckRequest withApplicant(String verifiedApplicant) {
        return new PrecheckRequest(type, verifiedApplicant, declaredAmount, reason, category,
                startDate, endDate, lineItems, overrides, exemptions);
    }

    /** 一条费用明细。 */
    public record LineItemReq(String desc, BigDecimal amount) {
    }

    /** 手工修正：文件名 → 金额。 */
    public record FileOverride(String fileName, BigDecimal amount) {
    }

    /** 例外声明：对哪类票据（kind）、什么原因（code）、怎么解释（note）。 */
    public record ExemptionReq(String kind, String code, String note) {
    }
}

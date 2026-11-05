package com.precheck.parse;

import com.precheck.facts.Facts;

import java.util.List;

/**
 * 解析结果：解析出来的附件 + 这次解析遇到的问题。
 *
 * @param attachment 可以直接放进 Facts 的附件（字段可能为 null）
 * @param warnings   给员工看的提示，空列表表示一切正常
 */
public record InvoiceParseResult(
        Facts.Attachment attachment,
        List<String> warnings
) {

    public InvoiceParseResult {
        warnings = (warnings == null) ? List.of() : List.copyOf(warnings);
    }

    /** 是否所有关键字段都拿到了（金额和发票号缺失会直接影响后面的检查）。 */
    public boolean complete() {
        return attachment.amount() != null && attachment.invoiceNo() != null;
    }
}

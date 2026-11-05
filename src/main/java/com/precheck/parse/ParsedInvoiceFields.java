package com.precheck.parse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 从发票文本里抽出来的字段。
 *
 * <p>任何一个字段都可能是 null —— 抽不出来就是抽不出来，绝不猜。
 * 抽不出的原因记在 warnings 里，最终会变成给员工看的一句"哪项没识别出来，请手工补录"。
 *
 * @param buyer       购买方名称（用来核对是不是本公司，将来有用）
 * @param itemSummary 开票项目名称，如"*运输服务*客运服务费"。判断票据属于交通/住宿/其他靠它。
 * @param warnings    解析过程中的问题说明，空列表表示全部字段都拿到了
 */
public record ParsedInvoiceFields(
        String invoiceNo,
        LocalDate date,
        BigDecimal amount,
        String seller,
        String buyer,
        String itemSummary,
        List<String> warnings
) {

    public ParsedInvoiceFields {
        warnings = (warnings == null) ? List.of() : List.copyOf(warnings);
    }
}

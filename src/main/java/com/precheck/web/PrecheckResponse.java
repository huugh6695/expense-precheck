package com.precheck.web;

import com.precheck.rule.Finding;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 返回给页面的预检结果。
 *
 * <p>特意把"检查发现的问题"和"解析时的问题"分开两组：
 * 前者是申请材料本身的毛病，后者是"这张发票我没读懂"。
 * 混在一起会让人分不清是"你填错了"还是"系统没看懂"，而这两种情况该做的事完全不同。
 */
public record PrecheckResponse(
        boolean passed,
        int blockCount,
        int warnCount,
        int infoCount,
        String summary,
        List<FindingView> findings,
        List<AttachmentView> attachments,
        List<String> parseWarnings
) {

    /** 一条检查发现（直接对应 Finding，给页面用的扁平结构）。 */
    public record FindingView(
            String ruleId,
            String level,
            String message,
            String evidence,
            String suggestion,
            String source
    ) {
        public static FindingView of(Finding f) {
            return new FindingView(f.ruleId(), f.level().name(), f.message(),
                    f.evidence(), f.suggestion(), f.source().name());
        }
    }

    /** 一张附件的解析情况，让员工能核对"系统理解的和我想的是否一致"。 */
    public record AttachmentView(
            String fileName,
            String kind,
            String invoiceNo,
            BigDecimal amount,
            LocalDate date,
            String seller,
            List<String> warnings
    ) {
    }
}

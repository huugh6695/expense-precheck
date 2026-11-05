package com.precheck.facts;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 事实集（Facts）—— 整套系统的唯一内部契约。
 *
 * <p>为什么要有它：
 * <ol>
 *   <li>解析层负责把"五花八门的发票/表单"压成这一份结构，之后所有检查只看 Facts，
 *       不再碰 PDF、图片、HTML 这些原始材料；</li>
 *   <li>有了它，检查规则可以脱离真实文件做单元测试——手写一份 Facts 就能断言结果；</li>
 *   <li>将来换 PDF 解析方案、接电子发票查验接口，检查层一行都不用改。</li>
 * </ol>
 *
 * <p>用 record 是因为它天生不可变：检查规则只读不写，谁也别想偷偷改事实。
 * 三个 List 在构造时兜底成空集合，后面的规则就不用到处判 null 了。
 */
public record Facts(
        ApplicationInfo application,
        List<Attachment> attachments,
        List<LineItem> lineItems
) {

    public Facts {
        attachments = (attachments == null) ? List.of() : List.copyOf(attachments);
        lineItems = (lineItems == null) ? List.of() : List.copyOf(lineItems);
    }

    /**
     * 申请单本身的信息。
     *
     * @param type           申请类型，暂用字符串（REIMBURSE=报销）。先不收敛成枚举，
     *                       免得将来 OCR / LLM 给出没预料到的值时直接反序列化失败。
     * @param applicant      申请人
     * @param declaredAmount 员工自己填的申报金额（后面要跟明细、发票比对的就是它）
     * @param reason         申请事由（软规则检查它是否符合模板）
     * @param startDate      费用发生起始日
     * @param endDate        费用发生截止日
     * @param category       费用类别：差旅 / 招待 / 办公 …
     */
    public record ApplicationInfo(
            String type,
            String applicant,
            BigDecimal declaredAmount,
            String reason,
            LocalDate startDate,
            LocalDate endDate,
            String category
    ) {
    }

    /**
     * 一份附件（一张发票、一张行程单…）。
     *
     * <p>注意 amount 可能是 null：解析失败时宁可"承认不知道"，
     * 也不能填个 0 进去——那会让"金额一致性"检查得出错误结论。
     *
     * @param id          附件唯一标识，用于报告里指证"就是这张"
     * @param kind        票据类型：TRAVEL（交通）/ HOTEL（住宿）/ INVOICE（其他发票）/ OTHER（读不出来）。
     *                    这个字段是给"附件是否齐全"（R2）用的：差旅报销必须同时有交通票和住宿票，
     *                    分不清类型就没法判断缺了什么。
     * @param amount      从票据里抽出来的金额（含税总额）
     * @param date        开票日期
     * @param invoiceNo   发票号码，查重用
     * @param seller      开票方名称
     * @param fileName    原始文件名，方便员工对照
     * @param itemSummary 开票项目名称，例如"*鞋*运动鞋"、"*运输服务*客运服务费"。
     *                    财务判断这笔钱花在哪儿看的就是它，也是判断票据类型最可靠的依据。
     */
    public record Attachment(
            String id,
            String kind,
            BigDecimal amount,
            LocalDate date,
            String invoiceNo,
            String seller,
            String fileName,
            String itemSummary
    ) {

        /** 交通票 */
        public static final String KIND_TRAVEL = "TRAVEL";
        /** 住宿票 */
        public static final String KIND_HOTEL = "HOTEL";
        /** 其他发票（办公用品、餐饮…） */
        public static final String KIND_INVOICE = "INVOICE";
        /** 读不出类型的附件 */
        public static final String KIND_OTHER = "OTHER";

        /**
         * 是不是一张"能算钱的发票"。
         *
         * <p>金额核对（R4）和查重（R5）都必须用它来判断，而不是写死 equals("INVOICE")——
         * 否则交通票、住宿票会被漏掉，那才是最该核对的两类。
         */
        public boolean isInvoice() {
            return KIND_INVOICE.equalsIgnoreCase(kind)
                    || KIND_TRAVEL.equalsIgnoreCase(kind)
                    || KIND_HOTEL.equalsIgnoreCase(kind);
        }

        /** 换一个金额，保持其他字段不变（手工修正金额时用）。 */
        public Attachment withAmount(BigDecimal newAmount) {
            return new Attachment(id, kind, newAmount, date, invoiceNo, seller, fileName, itemSummary);
        }

        /** 换一个文件名（解析时用的是临时文件名，最后要换回用户上传的名字）。 */
        public Attachment withFileName(String newFileName) {
            return new Attachment(id, kind, amount, date, invoiceNo, seller, newFileName, itemSummary);
        }
    }

    /**
     * 报销单里的一条明细（机票 450、住宿 680…）。
     */
    public record LineItem(
            String desc,
            BigDecimal amount
    ) {
    }
}

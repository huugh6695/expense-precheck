package com.precheck.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 「确认提交」时送给台账的东西。
 *
 * <p>只有预检通过（没有必须补的问题）之后，页面才会给出这个入口——
 * 预检本身是只读的，不能因为员工点了一下"开始预检"就往台账里写东西，
 * 那会让台账被没提交成功的材料污染，之后所有人查重都查到假历史。
 *
 * @param applicant 申请人，记在台账里，用于判断"是自己重提还是别人重复报销"
 * @param items     本次要记入的发票（号码 + 金额 + 开票日期）
 */
public record LedgerCommitRequest(String applicant, List<Item> items) {

    public LedgerCommitRequest {
        items = (items == null) ? List.of() : List.copyOf(items);
    }

    public record Item(String invoiceNo, BigDecimal amount, LocalDate invoiceDate) {
    }
}

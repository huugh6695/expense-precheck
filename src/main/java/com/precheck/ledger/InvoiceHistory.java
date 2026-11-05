package com.precheck.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * 发票历史台账 —— 回答"这张票以前是不是已经报过"。
 *
 * <p><b>为什么需要它：</b>R5 原本只能看出"这一次申请里同一张票传了两遍"，
 * 看不出"张三上个月已经用这张票报过一次"。没有历史，就谈不上真的查重，
 * 所以以前 README 里那句"同一张发票不能报两次"是夸大了能力。
 *
 * <p><b>为什么不存明文发票号：</b>台账一旦落盘，就成了一份"谁在哪天报了哪张票"的长期记录。
 * 明文号泄露等于把发票信息白送出去。所以只存<b>加了固定前缀的 SHA-256</b>，
 * 另外留号码后四位，方便人工核对时对得上。
 *
 * <p><b>⚠️ 诚实说明边界：</b>哈希只能证明"同一个号码又出现了"，防不住"改掉号码重新开票"。
 * 真要防那个，得对接税局发票查验接口。这一层先做到"能落盘、能查、能说清依据"。
 */
public interface InvoiceHistory {

    /**
     * 批量查：这批发票号里哪些以前报过。
     *
     * <p>做成批量而不是单条，是因为台账是文件，逐条查会重复读同一个文件 N 次。
     *
     * @return key = 传入的发票号原文，value = 台账里的那一行
     */
    Map<String, Entry> lookup(Collection<String> invoiceNos);

    /**
     * 记一笔（幂等）。
     *
     * @return true = 新记入；false = 台账里已经有这个哈希了（重复提交不会写第二条）
     */
    boolean record(String invoiceNo, String applicant, BigDecimal amount, LocalDate invoiceDate);

    /** 一个不记任何东西的空台账：单测、或明确关掉台账时用它。 */
    static InvoiceHistory none() {
        return new InvoiceHistory() {
            @Override
            public Map<String, Entry> lookup(Collection<String> invoiceNos) {
                return Map.of();
            }

            @Override
            public boolean record(String invoiceNo, String applicant, BigDecimal amount, LocalDate invoiceDate) {
                return false;
            }
        };
    }

    /**
     * 台账里的一行。
     *
     * @param hash        发票号的 SHA-256（带固定前缀），查重只认它
     * @param maskedNo    脱敏号（只留后四位），给人工核对用
     * @param applicant   上次报销的申请人
     * @param amount      上次报销的金额
     * @param invoiceDate 开票日期
     * @param recordedOn  什么时候记进来的
     */
    record Entry(String hash, String maskedNo, String applicant, BigDecimal amount,
                 LocalDate invoiceDate, LocalDate recordedOn) {

        /** 给报告用的一句话：谁在什么时候报过。 */
        public String describe() {
            String when = (recordedOn == null) ? "（日期不详）" : recordedOn.toString();
            String who = (applicant == null || applicant.isBlank()) ? "申请人不详" : applicant;
            return when + " 由 " + who + " 报销过";
        }

        /** 尾号，放在提示里让人对得上手里那张票。 */
        public String tail() {
            return (maskedNo == null || maskedNo.isBlank()) ? "" : "尾号 " + maskedNo.replace("…", "");
        }
    }

    /** 便于调用方判断"查不到"的便捷方法。 */
    default Optional<Entry> findOne(String invoiceNo) {
        return Optional.ofNullable(lookup(java.util.List.of(invoiceNo)).get(invoiceNo));
    }
}

package com.precheck.support;

import com.precheck.facts.Facts;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 把「费用明细」和「发票」按金额配成对——用来回答"这 20 元到底差在哪一行"。
 *
 * <p><b>它不判对错，只做定位。</b>是否算通过由 R4 决定；这里只负责在总量已经对不上之后，
 * 把差额尽可能归到具体某一条明细上。分成两件事，是因为判对错要有权威（票面证据），
 * 而定位只是"帮你找"，找不准的时候必须敢说找不到。
 *
 * <p><b>为什么只按金额配，不看文字。</b>明细写"住宿（两晚）"、发票写"*住宿服务*住宿费"，
 * 想让机器判断这俩是一回事，只能靠关键词表（等于把业务规则硬编码进字符串）
 * 或者靠模型（有误判、有成本、不可单测）。而金额是发票上印着的确定数字，
 * 一分不差地对上就是最硬的证据——能算的用代码算，不猜。
 *
 * <p><b>什么时候敢说"就是这一条"。</b>只有一种情况：精确配对之后，明细侧和发票侧
 * 各剩正好一条。这时候对应关系是被迫的，不存在第二种可能。剩下两条以上就不敢说了——
 * 那时候"最接近的一对"多半是巧合，报出来会把员工引到错的地方去查，
 * 比只说"相差 20 元"更糟。宁可少说，不说错。
 */
public final class LineItemMatcher {

    private LineItemMatcher() {
    }

    /** 一条明细与一张发票的对应关系。 */
    public record Pair(Facts.LineItem item, Facts.Attachment invoice) {

        /** 两者相差多少；任一方金额未知时返回 null（表示"无法比较"，不是 0）。 */
        public BigDecimal gap() {
            return Money.diff(item.amount(), invoice.amount());
        }
    }

    /**
     * 配对结果。
     *
     * @param matched        金额一致（容差内）的配对，这些不是问题
     * @param suspected      一对一剩下、金额不一致的配对——可以指着鼻子说"差在这儿"
     * @param orphanItems    找不着发票的明细
     * @param orphanInvoices 找不着明细的发票
     */
    public record Result(
            List<Pair> matched,
            List<Pair> suspected,
            List<Facts.LineItem> orphanItems,
            List<Facts.Attachment> orphanInvoices
    ) {

        public static Result empty() {
            return new Result(List.of(), List.of(), List.of(), List.of());
        }

        /** 一条线索都没有：说明这次压根没配上（比如明细或发票根本没金额）。 */
        public boolean hasNothing() {
            return matched.isEmpty() && suspected.isEmpty()
                    && orphanItems.isEmpty() && orphanInvoices.isEmpty();
        }

        public boolean hasOrphans() {
            return !orphanItems.isEmpty() || !orphanInvoices.isEmpty();
        }
    }

    /**
     * 按金额配对。
     *
     * <p>先做一轮"金额完全相等"的配对（贪心：每条明细找第一张还没被用掉的、金额相等的发票），
     * 剩下的再判断能不能确定成一 对一。
     *
     * <p>金额为 null 的一律不参与配对：解析失败的票据"不知道多少钱"，
     * 把它当成 0 元去配会得出完全错误的结论，只能单列出来让人手工看。
     *
     * @param tolerance 金额容差，从规则配置来（有的公司允许 1 元凑整）
     */
    public static Result match(List<Facts.LineItem> lineItems,
                               List<Facts.Attachment> invoices,
                               BigDecimal tolerance) {

        List<Facts.LineItem> items = (lineItems == null) ? List.of() : lineItems;
        List<Facts.Attachment> bills = (invoices == null) ? List.of() : invoices;
        if (items.isEmpty() || bills.isEmpty()) {
            return Result.empty();
        }

        boolean[] taken = new boolean[bills.size()];
        List<Pair> matched = new ArrayList<>();
        List<Facts.LineItem> restItems = new ArrayList<>();

        for (Facts.LineItem item : items) {
            if (item == null) {
                continue;
            }
            int hit = -1;
            if (item.amount() != null) {
                for (int i = 0; i < bills.size(); i++) {
                    Facts.Attachment bill = bills.get(i);
                    if (taken[i] || bill == null || bill.amount() == null) {
                        continue;
                    }
                    if (Money.same(item.amount(), bill.amount(), tolerance)) {
                        hit = i;
                        break;
                    }
                }
            }
            if (hit >= 0) {
                taken[hit] = true;
                matched.add(new Pair(item, bills.get(hit)));
            } else {
                restItems.add(item);
            }
        }

        List<Facts.Attachment> restBills = new ArrayList<>();
        for (int i = 0; i < bills.size(); i++) {
            if (!taken[i]) {
                restBills.add(bills.get(i));
            }
        }

        // 各剩一条，且两边金额都已知 —— 对应关系是被迫的，这时候才敢"指认"。
        if (restItems.size() == 1 && restBills.size() == 1
                && restItems.get(0).amount() != null && restBills.get(0).amount() != null) {
            return new Result(List.copyOf(matched),
                    List.of(new Pair(restItems.get(0), restBills.get(0))),
                    List.of(), List.of());
        }

        return new Result(List.copyOf(matched), List.of(),
                List.copyOf(restItems), List.copyOf(restBills));
    }
}

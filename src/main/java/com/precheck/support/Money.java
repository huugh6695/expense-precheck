package com.precheck.support;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 金额工具。全项目的金额运算只能从这里走，不许在各处自己写。
 *
 * <p>为什么必须这样：
 * <ol>
 *   <li><b>绝不用 double。</b> double 存 0.1 其实是 0.1000000000000000055…，
 *       一份 1130 元的单子做几轮加减就会变成 1129.9999999，导致天天误报"差 1 分钱"。
 *       BigDecimal 是十进制精确运算，不会有这个问题。</li>
 *   <li><b>比较必须带容差。</b> 真实发票的合计常常是四舍五入过的，
 *       硬比 0.01 元的差也会报错。留 1 分钱容差，员工才会信这个工具。</li>
 *   <li><b>永远用 compareTo，不用 equals。</b> BigDecimal 的 equals 连小数位数一起比，
 *       new BigDecimal("1130.0").equals(new BigDecimal("1130.00")) 结果是 <b>false</b>——
 *       这个坑很多人踩过。</li>
 * </ol>
 */
public final class Money {

    /** 比较容差：1 分钱。将来会挪到配置文件里，让每家企业自己定。 */
    public static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    private Money() {
    }

    /** 两个金额是否"算一样"（差值在默认容差内）。任一为 null 一律视为无法比较。 */
    public static boolean same(BigDecimal a, BigDecimal b) {
        return same(a, b, TOLERANCE);
    }

    /**
     * 指定容差版本。规则里应该用这个，容差从配置文件读——
     * 有的公司能容忍 1 元差异（零钱凑整），有的要求分毫不差，这不该写死在代码里。
     */
    public static boolean same(BigDecimal a, BigDecimal b, BigDecimal tolerance) {
        BigDecimal d = diff(a, b);
        if (d == null) {
            return false;
        }
        BigDecimal t = (tolerance == null) ? TOLERANCE : tolerance;
        return d.compareTo(t) <= 0;
    }

    /** 差值绝对值；任一为 null 返回 null（表示"无法比较"，而不是 0）。 */
    public static BigDecimal diff(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return null;
        }
        return a.subtract(b).abs();
    }

    /** 求和，跳过 null（解析失败的票据不计入，避免把它当成 0 元）。 */
    public static BigDecimal sum(List<BigDecimal> amounts) {
        return amounts.stream()
                .filter(a -> a != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 格式化成两位小数，报告里统一用它，免得出现 1130.0 / 1130.000 这种不一致。 */
    public static String fmt(BigDecimal amount) {
        if (amount == null) {
            return "（未知）";
        }
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}

package com.precheck.rule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 一次预检的完整结果。
 *
 * <p>passed 的含义要说清：<b>它只代表"没有阻断项"</b>，不代表"这笔钱该批"。
 * 这是产品定位在代码里的体现——我们只保证材料齐整，批不批由人定。
 *
 * @param findings 按严重程度排好序的问题清单（阻断在前）
 */
public record PrecheckReport(
        List<Finding> findings,
        int blockCount,
        int warnCount,
        int infoCount,
        boolean passed
) {

    public static PrecheckReport of(List<Finding> raw) {
        List<Finding> sorted = new ArrayList<>(raw);
        sorted.sort(Comparator.comparingInt(f -> f.level().ordinal()));

        int block = (int) sorted.stream().filter(f -> f.level() == Level.BLOCK).count();
        int warn = (int) sorted.stream().filter(f -> f.level() == Level.WARN).count();
        int info = (int) sorted.stream().filter(f -> f.level() == Level.INFO).count();

        return new PrecheckReport(List.copyOf(sorted), block, warn, info, block == 0);
    }
}

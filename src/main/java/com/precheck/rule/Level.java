package com.precheck.rule;

/**
 * 发现问题后的严重程度。
 *
 * <p>只有三级，故意不再细分：
 * <ul>
 *   <li>{@link #BLOCK} —— 必须补，不补就没法审（缺发票、金额对不上）。积累太多会招人烦，
 *       所以判定标准要严：<b>确实无法继续审核</b>才用它；</li>
 *   <li>{@link #WARN}  —— 建议核对，可能有问题也可能是合理的，员工自己决定；</li>
 *   <li>{@link #INFO}  —— 只是说明，比如"金额接近单笔限额"。</li>
 * </ul>
 */
public enum Level {
    BLOCK,
    WARN,
    INFO
}

package com.precheck.ai;

import java.util.Optional;

/**
 * 大模型调用接口。
 *
 * <p>之所以抽成接口：规则只依赖这个接口，测试时塞一个"假模型"就能验证规则逻辑，
 * 不用真的联网、也不用花 token。将来换模型供应商，只换实现类。
 *
 * <p>约定：<b>永远不抛异常</b>。调用失败、超时、Key 不存在，一律返回 Optional.empty()，
 * 由调用方决定"没拿到结果时怎么办"（本项目的做法是如实说明没查，而不是假装通过）。
 */
public interface AiClient {

    boolean available();

    Optional<String> complete(String systemPrompt, String userPrompt);
}

package com.carbon.platform.ai.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 空实现（{@code carbon.llm.provider=none} 或未配置任何 provider 时生效）。
 *
 * <p>作用：保证容器里<b>始终存在</b>一个 {@link LlmClient} Bean，
 * 使 {@code FieldMappingPlanner} 可以无条件注入。
 * {@link #available()} 返回 false，规划器据此直接走规则引擎。
 *
 * <p>为什么需要它：如果只用 {@code @ConditionalOnProperty(havingValue=...)} 区分
 * modelscope / mock，那么当 provider 配成 none 或拼错时，两个 Bean 都不注册，
 * 应用会因缺少依赖而<b>启动失败</b>（实测踩过）。
 * 用 {@code matchIfMissing=true} 兜底可以避免这种"配置写错就起不来"的脆弱性。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "carbon.llm.provider", havingValue = "none", matchIfMissing = true)
public class NoOpLlmClient implements LlmClient {

    public NoOpLlmClient() {
        log.info("大模型未启用（carbon.llm.provider=none 或未配置），"
                + "AI 字段映射将使用规则引擎");
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public String provider() {
        return "none";
    }

    @Override
    public String model() {
        return "-";
    }

    @Override
    public String chat(String systemPrompt, String userPrompt, double temperature) {
        return null;
    }
}

package com.carbon.platform.ai.llm;

/**
 * 轻量大模型客户端抽象。
 *
 * <p>用于"AI 批量规划导入"的字段语义映射。设计原则：
 * <ol>
 *   <li><b>可插拔</b>：ModelScope（魔搭云）/ DashScope / Mock 三种实现，
 *       按 {@code carbon.llm.provider} 选择；</li>
 *   <li><b>可降级</b>：{@link #available()} 返回 false 时，上层的字段映射规划器
 *       自动回退到规则引擎，保证演示不中断；</li>
 *   <li><b>不依赖 Spring AI / LangChain</b>：只需一个 OpenAI 兼容的
 *       chat/completions 端点，减少依赖与版本兼容风险。</li>
 * </ol>
 */
public interface LlmClient {

    /** 是否可用（已配置密钥且 provider 非 none） */
    boolean available();

    /** 当前 provider 名称，用于审计与前端展示 */
    String provider();

    /** 使用的模型名 */
    String model();

    /**
     * 单轮对话补全。
     *
     * @param systemPrompt 系统提示词（角色与输出约束）
     * @param userPrompt   用户提示词（具体任务与数据）
     * @param temperature  采样温度，结构化任务建议 0
     * @return 模型回复正文；失败返回 null（由上层决定降级）
     */
    String chat(String systemPrompt, String userPrompt, double temperature);

    /**
     * 按指定输出长度上限调用。
     *
     * <p><b>为什么需要</b>：LLM 延迟与生成长度近似线性。全局 max-tokens 默认 2000，
     * 对「看板一句话解读」这种 200 字以内的任务来说太长，模型可能写 600 字，
     * 白等 1 秒多。此重载允许按任务收紧上限。
     *
     * <p>默认实现忽略 maxTokens，保证既有实现类不受影响。
     */
    default String chat(String systemPrompt, String userPrompt, double temperature, int maxTokens) {
        return chat(systemPrompt, userPrompt, temperature);
    }

    /**
     * 带工具（function calling）的对话。
     *
     * <p>默认实现返回 null 表示「不支持工具调用」，上层据此降级。
     * 只有 OpenAI 兼容客户端（OpenAiCompatibleLlmClient）覆盖本方法。
     *
     * @param messages  完整对话历史，每项含 role/content/tool_call_id/tool_calls
     * @param tools     可调用工具定义（DeepSeek function calling 格式）
     * @param maxTokens 输出上限
     */
    default ToolChatResult chatWithTools(java.util.List<java.util.Map<String, Object>> messages,
                                          java.util.List<java.util.Map<String, Object>> tools,
                                          int maxTokens) {
        return null;
    }
}

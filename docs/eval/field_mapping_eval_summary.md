# 字段映射策略对比：规则引擎 vs AI(LLM)

> 生成方式：先分别以 `carbon.llm.provider=none`（纯规则）与 `=mock`（LLM 路径）运行 `scripts/eval_field_mapping.py`，再汇总本文件。

## 一、核心结论

| 策略 | 调优集 (A–D) | **留出集 (E–F)** | 响应耗时 | 依赖 |
|---|---|---|---|---|
| 规则引擎（别名表） | ?% | **?%** | <2 ms | 无（离线可用） |
| LLM 路径（当前 mock） | ?% | **?%** | <5 ms | 需模型服务 |

## 二、必须说明的方法论问题（避免夸大）

1. **调优集分数不可作为能力证据。** A–D 组在评测过程中被用于扩充规则引擎别名表，
   因此其 100% 是过拟合结果（扩充前为 87.5%）。凡是用同一批数据调参又用同一批数据
   报分，数字都不可信。
2. **留出集才是无偏估计。** E–F 组在别名表定稿后才构造，列名风格不同，
   结果不受调优影响。**应引用留出集分数。**
3. **当前 mock 不能代表真实 LLM 能力。** `MockLlmClient` 本质仍是关键词匹配
   （只是顺带验证了 Prompt 组装、JSON 容错、降级、审计链路），
   所以它在留出集上同样失手。（房子编号」「什么时候采的」「用了多少电」这类
   纯口语化表达，
   **只有真实大模型才能靠语义理解命中**。

## 三、当前留出集失手清单（两种策略都答不出）

| 源列名 | 期望字段 | 说明 |
|---|---|---|
| 数据时点 | timestamp | “时点”是时间点的书面语，两边词表都没覆盖 |
| 冷站能耗 | hvac_kwh | “冷站”是暖通专业术语，需领域知识 |
| 房子编号 | building_id | 口语化“房子” |
| 什么时候采的 | timestamp | 完整口语句式 |
| 用了多少电 | electricity_kwh | 完整口语句式 |

> 这 5 条正是**接入真实大模型后的预期增益点**。拿到 ModelScope Key 后重跑本评测，
> 即可量化 LLM 相对规则引擎的真实提升。

## 四、工程决策：LLM 优先 + 规则兜底

```
上传文件 → 列画像 ──┬─ LLM 语义映射（可用时）→ 解析 JSON → 冲突消解 → 必填补齐
                    └─ 规则引擎（兜底）→ 别名表 + 类型推断 + 取值域校验
```

- **为什么保留两者**：演示现场网络不可控。规则引擎毫秒级、永不失败；
  LLM 泛化能力强但依赖网络。组合后既能体现 AI 能力，又能保证演示不中断。
- **必填字段自动补齐**：即使 LLM 漏给 `building_id`/`timestamp`，
  规划器也会用规则结果补齐，避免因模型遗漏导致整批数据无法导入。
- **策略透出**：上传接口返回 `mappingPlan`（strategy/provider/model/耗时/降级原因），
  前端据此提示用户「本次建议由 AI 生成」还是「本次为规则兜底」，
  并写入审计留痕，满足可追溯要求。

## 五、复现命令

```bash
# 纯规则引擎
cd backend && mvn -s ../scripts/maven-settings.xml spring-boot:run \
  -Dspring-boot.run.arguments="--carbon.llm.provider=none"
.venv/bin/python scripts/eval_field_mapping.py --out docs/eval/field_mapping_eval_rule.md

# LLM 路径（当前为 mock；接入真实模型后改为 modelscope）
cd backend && mvn -s ../scripts/maven-settings.xml spring-boot:run \
  -Dspring-boot.run.arguments="--carbon.llm.provider=modelscope"
export CARBON_LLM_KEY=你的魔搭云访问令牌
.venv/bin/python scripts/eval_field_mapping.py --out docs/eval/field_mapping_eval_llm.md
```

# MedNav 技术验证报告 v0.1

| 项目 | 内容 |
|---|---|
| 验证日期 | 2026-09-22 |
| 实现范围 | M1–M8 技术验证基线 |
| 验证环境 | Windows，JDK 19，Spring Boot 3.5.7，PostgreSQL 16，Redis 7，Node/Vite |
| 外部付费请求 | 已执行受控小样本：Google、GPT-5.6 Terra、GPT-5.6 Luna 均成功 |
| 总体结论 | 核心链路、M7 数据一致性、M8 多 Session/历史消息、真实 Google 地图、主模型、Summary 和条件式 Verification 小样本均通过 |

## 1. 验证范围

| 里程碑 | 主要能力 | 当前状态 |
|---|---|---|
| M1 | Conversation、Run、SSE、Session 内串行/间并行、前端对话壳 | 通过 |
| M2 | 对话式注册/登录、安全密码通道、三轮画像采集、MyBatis 持久化 | 通过 |
| M3 | 症状合并、readiness、最多三个初诊疾病、新消息重新评估 | 通过（含真实 GPT-5.6 Terra 小样本） |
| M4 | CMS 三文件下载/导入/原子快照，Facility ID/NPI 与 affiliation 关联 | 通过 |
| M5 | 定位、同城医院、多交通路线、revision Join | 通过（含真实 Google 小样本） |
| M6 | 疾病—医院—医生三级树、硬规则、局部修复、条件语义验证 | 通过（含真实 GPT-5.6 Luna 强制触发样本） |
| M7 | Redis 工作记忆、PostgreSQL 长期记忆、Session Finalizer、历史 API | 通过（含本地真实 Redis/PostgreSQL） |
| M8 | 同用户多 Session、Transcript、恢复/只读边界和前端历史侧栏 | 通过 |

## 2. 自动化结果

- 后端：74 项 JUnit/Spring 测试通过，0 失败，0 错误。
- 前端：3 项 Vitest 测试通过；TypeScript 检查和 Vite 生产构建通过。
- 契约：OpenAPI 0.8.2 可解析，M8 JSONL 6 条可解析。
- 评测集：M1–M8 共 95 条版本化样本。

M7 自动化覆盖的关键断言：

1. Session 期间画像和临床更新不直接写长期表。
2. Finalizer 成功提交后才删除 Session 工作缓存。
3. 数据库失败时保留 Session 工作缓存。
4. 重复收口幂等，不产生重复历史行。
5. Agent Memory 只保存最终症状、Top 3 初诊疾病和 Session 摘要；产品 Transcript 使用独立关系表，不参与 Memory 事实合并。
6. 运行中 Session 的历史删除请求被拒绝。
7. 空闲超时走与显式结束相同的 Session 串行收口路径。
8. 只有明确的既往诊断或白名单约束表达生成 Session patch，普通症状和偏好不会写入这些字段。
9. 主模型失败时仍保留本轮原始症状，清除过期初诊并使后续 Run 可继续恢复。

M8 自动化额外覆盖：同用户创建两个独立 Session、按所有权列出历史、按序回放消息、未结束且未超时 Session 恢复，以及显式结束 Session 返回 `409 SESSION_NOT_RESUMABLE` 但仍可读取历史。前端覆盖持久化消息与 SSE 重放去重。

## 3. 本地集成冒烟

使用项目 Docker Compose 中健康的 PostgreSQL 16 和 Redis 7，以 `local` Stub Profile 在独立端口启动后端，执行：

```text
创建 Conversation
  → 对话式注册
  → 提交画像
  → 提交症状并得到 3 个初诊疾病
  → 显式结束 Session
  → 查询历史 Session Memory
```

观测结果：

- Actuator 状态为 `UP`。
- 提交前 `memory:session:{sessionId}` 存在。
- 提交后 Session Key 已删除，`memory:user:{userId}` 存在。
- 历史 API 返回 1 条记录，包含 3 个初诊疾病和非空摘要。
- 启动时 Flyway 确认 5 个迁移已校验，当前 Schema 版本为 5。

## 4. 真实外部 API 小样本

使用 `openai` Profile、去标识症状文本和测试地址 `1108 Ross Clark Circle, Dothan, AL 36301` 执行一次端到端联调，CMS 定时刷新在本次运行中关闭，使用已发布 ACTIVE snapshot `65bcc505-14a1-451e-99a9-d5f957850ec7`。

观测结果：

- Google Geocoding 成功解析为 Dothan, Alabama，并满足美国 city/state 位置门禁。
- 本地 CMS 数据库返回 2 家同城医院；Google Routes 为两家医院分别计算 DRIVE、TRANSIT、WALK，共 3 个批次、6 个成功路线元素，定位任务进入 `READY`。
- 首轮 OpenAI 请求在额度不足时返回 HTTP 429；系统正确产生可恢复错误，并由确定性 Summary 完成 Session 收口。补充额度后，Terra 和 Luna 最小直连请求均返回 HTTP 200。
- GPT-5.6 Terra 项目级临床调用成功返回 `READY`、3 个结构化疾病假设；成功样本记录 1,897 input tokens、381 output tokens，随后生成 3 层推荐树。
- GPT-5.6 Terra Summary Model 成功生成 336 字符 Session 摘要，Finalizer 以 `summaryDegraded=false` 提交。
- 使用仅限验收的阈值 101 强制触发 GPT-5.6 Luna；日志确认条件式 Semantic Verification 已执行，推荐以 `READY_WITH_WARNINGS`、`semanticTriggered=true` 提交。生产默认阈值 65 未修改。
- 真实验收暴露并修复三项 AgentScope 2.0.1 兼容问题：reasoning effort 与 function tools 冲突、原生 response schema 不满足严格 Schema、JSON 文本未自动转为 `structuredData`。修复后新增两个自动化测试。
- 本次联调暴露出模型失败路径未实际保存用户症状的问题。修复后增加回归测试和评测样本，确保错误返回前把原始症状保存在当前 Session 工作内存中。
- 首次人工对话回归暴露出中文出生日期格式重复追问，以及模型异常时只返回通用重试文案的问题；现已支持常用中文日期格式，并在模型不可用或输出无效时通过确定性临床降级继续提出具体追问。

## 5. 已知缺口与下一步

| 缺口 | 影响 | 下一步 |
|---|---|---|
| OpenAI 成功样本数量有限 | 主模型、Summary、Verification 各有成功证据，但尚未覆盖追问、三轮降级和多类 semantic findings | 扩充去标识在线评测集，并设置每次部署前的受控抽样预算 |
| 真实 Google 地理覆盖有限 | Dothan 单地址、2 家医院和三种交通方式已通过，但尚不足以代表全美地址/无路线边界 | 后续加入至少两个不同州、无 TRANSIT 路线和批量医院场景 |
| Redis 真实容器断开/恢复尚未演练 | 单元故障注入已确认不落库/不删工作缓存，API 返回可重试 `503 MEMORY_CACHE_UNAVAILABLE` | 在不影响其他开发任务的环境中执行真实容器断开/恢复演练 |
| 长期事实的删除/修正语义未完成 | 当前可安全追加明确疾病史和白名单约束，但不解释“我已不再对 X 过敏”等删除语义 | 设计显式 `ADD/REMOVE/REPLACE` patch，对 REMOVE 要求精确目标和更严格的确认 Hook |

## 6. 结论

M1–M8 已经具备可重现的本地技术验证基线，真实 Google 地图、GPT-5.6 Terra 主模型与 Summary、GPT-5.6 Luna 条件式 Verification 以及外部服务失败降级均已有小样本证据。当前不应宣称为生产医疗系统；下一步应继续 Redis 故障恢复、长期事实删除/修正语义、服务重启后的 ACTIVE Session 状态恢复，以及更广的模型和 Google 地理边界回归。

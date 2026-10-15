# Changelog

## 0.8.2 - 2026-09-23

- 推荐理由改为面向患者的自然语言，不再在可见段落中直接暴露 `overall rating`、`Facility Affiliation`、`MILD`、`miles` 等内部字段或枚举。
- 临床请求新增 `responseLanguage`，推荐结果新增 `displayLanguage`；中文和英文的疾病名、症状事实、追问、严重程度、评分、距离单位及提示语保持一致。
- 增加临床语言 Hook：模型返回的患者可见文本与请求语言不一致时视为无效并进入确定性降级。
- 医院、医生姓名和美国地址保留官方原文；医生专业在中文说明中使用本地化描述，未映射专业降级为不暴露英文内部值的通用说明。
- OpenAPI 更新为 0.8.2，M1 至 M8 评测集更新为 95 条。

## 0.8.1 - 2026-09-23

- 疾病、医院和医生节点新增基于已验证事实生成的一段式 `recommendationReason`；继续保留原始关键事实和 Evidence，不引入医生质量评分或无来源结论。
- 每个疾病分支的医院上限由 3 家调整为 2 家，最终树上限改为 `3 × 2 × 2`。
- Flyway V5 为会话消息增加 `message_type` 和 `structured_payload`；最终推荐在发布 SSE 前先持久化结构化快照。
- 历史 Session 回放时恢复最新疾病—医院—医生推荐树；旧版未保存的推荐快照无法反向回填。
- OpenAPI 更新为 0.8.1，M1 至 M8 评测集更新为 93 条。

## 0.8.0 - 2026-09-22

- 修复三级推荐内容较长时 Grid 被上下文面板撑高、聊天区下方出现大块空白的问题；桌面端改为受视口约束的三栏容器，各栏独立滚动，移动端保持自然页面流。
- 修复新建 Session 在 WebFlux 事件线程同步等待 Redis、被误报为 `MEMORY_CACHE_UNAVAILABLE` 的问题；Session 历史相关阻塞操作统一调度到 bounded-elastic 工作线程。
- 新增同一用户创建多个独立 Session，以及按用户所有权列出 Session 的接口。
- 新增 `conversation_session`、`conversation_message` 关系表；用户与 Assistant 可见消息按 Turn 增量持久化，密码不进入 Transcript。
- 新增历史消息读取和 ACTIVE Session 恢复 API；显式结束或空闲满 10 分钟的 Session 保持可读但禁止继续对话。
- 前端新增 ChatGPT 风格 Session 侧栏、新建会话、历史回放、可恢复/只读状态和已结束会话输入门禁。
- 增加持久化消息与 SSE 重放去重，避免恢复后重复显示 Assistant 消息。
- OpenAPI 更新为 0.8.0，Flyway Schema 更新为 4；新增 5 条 M8 评测样本和多 Session API 集成测试。
- 后端回归基线更新为 72 项测试，前端更新为 2 项测试，M1 至 M8 评测集共 91 条。

## 0.7.0 - 2026-09-22

- 新增 Redis 用户长期记忆快照和当前 Session 增量工作缓存，PostgreSQL 保持为唯一长期事实源。
- 新增长期画像、疾病史、核心约束、历史 Session 与当前 Session 增量的动态合并视图。
- 新增 AgentScope Summary Model Session 摘要生成器，以及模型失败时的确定性摘要降级。
- 新增显式结束和空闲 10 分钟自动收口，两者都经过同一 Session 串行调度器与幂等 Finalizer。
- 在同一事务中合并 `user_profile`、写入 `session_clinical` 与 `session_summary`；数据库提交成功后才刷新用户缓存并删除 Session 工作缓存。
- 新增历史 Session Memory 查询/删除 API，并阻止对当前未收口 Session 的删除。
- 新增 Finalizer 成功/失败、Summary 降级和收口耗时 Micrometer 指标。
- Redis 读写不可用时统一返回可重试 `503 MEMORY_CACHE_UNAVAILABLE`，Finalizer 不会落库或删除工作记忆。
- 新增显式疾病史/核心约束的保守白名单提取，普通当前症状和偏好不会写入长期事实。
- 修复主模型失败路径未实际保留用户症状的问题：错误返回前先写入 Session 工作内存并清除可能过期的初诊结果。
- 新增 17 条 M7 评测样本、Memory 事务/故障单元测试和本地 PostgreSQL/Redis 收口冒烟；OpenAPI 更新为 0.7.0。
- 完成真实外部 API 小样本：Google Geocoding/Routes、GPT-5.6 Terra 临床推理与 Session Summary、GPT-5.6 Luna 条件式 Verification 均成功。
- 修复 AgentScope 2.0.1 与 GPT-5.6 Chat Completions 的兼容问题：显式使用 `reasoning_effort=none`，禁用不兼容的原生 Schema 路径，并增加统一 JSON 文本回退解析和严格 Prompt 输出契约。
- 修复画像采集只接受严格 `YYYY-MM-DD` 的问题，新增点号、斜杠及中文年月日格式并统一存为 ISO 日期。
- 主临床模型不可用或输出校验失败时改用确定性临床降级推理，继续给出具体且不重复的追问，不再只返回通用重试文案；补充中文腹痛、中文时长、反酸和便血识别。
- 后端回归基线更新为 71 项测试，M1 至 M7 评测集更新为 86 条。

## 0.6.0 - 2026-09-14

- 新增绑定 `locationRevision`、`clinicalRevision` 与 CMS snapshot 的疾病—医院—医生三级推荐管线。
- 按疾病分支分别执行 SEVERE 全市、MODERATE 5 miles、MILD 10 miles 驾车范围硬规则。
- 医院按 CMS Overall Rating 降序、DRIVE distance 升序和 Facility ID 稳定排序，每个疾病最多 3 家。
- 医生按同快照 CMS 专业与 Facility Affiliation 硬过滤、NPI 去重和稳定截断，每家医院最多 2 名，不使用医生评分。
- 新增用户事实、CMS 医院/医生/任职及路线 Evidence，以及 `confidence-v1` 疾病、医院、医生、解释和整体链路置信度。
- 新增确定性 Recommendation Gate、最多三轮失败条目局部修复和额度耗尽降级。
- 新增 AgentScope Semantic Verification 辅助模型；仅在低置信度或语义风险时调用，findings 不拥有最终 PASS 权限。
- 新增 Recommendation REST/SSE 契约和前端三级树展示；公开疾病结果继续隐藏内部目标专业。
- 修正同城医院为零时的 snapshot 绑定，空结果也保留 ACTIVE CMS 版本并返回疾病级降级结果。
- 新增 15 条 M6 评测样本及排序、距离边界、医生截断、空医生、局部修复和端到端测试；OpenAPI 更新为 0.6.0。

## 0.5.0 - 2026-09-14

- 新增浏览器定位授权与手动地址/City+State/ZIP 降级接口，定位拒绝不影响认证状态。
- 新增 Google Geocoding 客户端，支持地址正向解析和浏览器坐标反向解析，并强制返回美国 city/state 结构。
- 新增 Google Routes Compute Route Matrix 客户端，按 DRIVE、TRANSIT、WALK 请求距离与预计时间。
- 新增同 city/state 全量 CMS 医院覆盖、Facility ID 去重、每批最多 100 个路线元素及多批受控并行；不增加候选医院上限。
- 新增 10 分钟元素级路线缓存，缓存键包含规范化用户位置、CMS snapshot、Facility ID、交通方式和参数版本。
- 路线单批失败按对应元素降级，不使其他交通方式和批次整体失败；TRANSIT/WALK 无路线允许为空。
- 新增 `contextVersion`、`locationRevision`、`clinicalRevision` 门禁；新位置取消旧任务且旧异步结果不得提交。
- LocationTask 独立于 Session Run 队列运行，临床与定位均 READY 后按 revision 对只发布一次 `NAVIGATION_CONTEXT_READY`。
- 新增定位/路线 SSE 事件、导航任务查询 API，以及前端浏览器授权、手动地址卡和并行状态面板。
- 新增 Location/Route JSON Schema 和 14 条 M5 评测样本；OpenAPI 契约更新为 0.5.0。
- 新增 101 家医院跨批次覆盖、并发执行、缓存命中/过期、Google HTTP 契约、认证门禁、手动降级、Join 和旧 revision 淘汰测试。

## 0.4.0 - 2026-09-14

- 新增 Flyway v3：CMS snapshot/source、四类 staging、医院/医生/专业/执业地点/任职关系正式表、ACTIVE 指针和刷新租约。
- 新增 CMS Metastore 客户端，以三个稳定 Dataset ID 动态解析下载分发地址，支持 ETag、Last-Modified、SHA-256 内容寻址和 304 本地复用。
- 新增三文件流式 CSV 导入及 JDBC 批写，严格校验列名、列数、顺序、字段类型、空数据和预期校验和。
- 新增医院 Facility ID、医生 NPI、DAC 专业和 Facility Affiliation 标准化；Facility ID 按六位字母数字标识保存，不丢失前导零。
- 新增三文件同批次事务发布、最低 Hospital affiliation Join 比率门禁和 ACTIVE 快照原子切换；失败时保留上一快照。
- 新增数据库租约防止多实例并发刷新，以及 `04:00 America/Los_Angeles` 定时任务。
- 新增医院按 state/city 查询、专业词表和医院内医生候选查询；候选按 NPI 去重并汇总命中专业，不做医生评分排序。
- 新增 CMS Actuator 健康组件、关键导入日志和完成/失败快照 staging 清理。
- 新增固定 CMS fixture、Schema 漂移原子回退、HTTP 304、刷新租约、字母型 Facility ID 与多专业去重测试。
- 新增 CMS snapshot/provider JSON Schema 和 13 条 M4 评测样本；OpenAPI 契约版本更新为 0.4.0。

## 0.3.0 - 2026-09-14

- 新增 M3 临床请求、症状、readiness、疾病假设与严重程度强类型契约。
- 新增 AgentScope Java `MedicalNavigationAgentFactory` 及 OpenAI 主模型适配；未引入 OpenAI 官方 Java SDK。
- 新增主模型、Summary 模型与 Semantic Verification 模型的延迟加载 Bean，配置缺失时保持应用可启动并在 Run 内安全降级。
- 新增 SymptomExtraction、ClinicalReadiness、DiseaseInference 三个可独立评测的 AgentScope Skill。
- 新增单问题 Clarification/JSON Gate、三轮上限、0.60 阈值和四态临床就绪决策。
- 新增症状上下文合并、clinical revision，以及 READY 后新消息继续生成 Run 并重新评估。
- 新增 `PIPELINE_PROGRESS`、`CLINICAL_QUESTION`、`CLINICAL_RESULT` 与 `VALIDATION_FINDING` 事件。
- 对外疾病结果移除内部 CMS 专业匹配字段，并在话术中包含事实依据与信息完整度。
- 前端更新为 M3 症状分析状态面板；新增临床输入/输出 Schema 和 10 条 M3 评测样本。

## 0.2.0 - 2026-09-13

- 新增 Flyway v2：`user_account`、`user_profile`、`session_clinical` 和 `session_summary`。
- 新增 MyBatis 账户与画像 Mapper，以及 BCrypt 注册/登录服务。
- 新增基于一次性认证请求的登录/注册接口，密码与聊天、事件及日志隔离。
- 新增用户名规范化与数据库唯一约束，覆盖并发重复注册冲突。
- 新增画像抽取与完整性关卡，支持公制/美国常用单位、字段范围校验和三轮降级。
- 新增 Session 画像暂存、有效画像查询及 Session 结束时持久化。
- 前端新增对话式门禁、安全密码卡和结束 Session 操作。
- OpenAPI 与事件 Schema 升级至 M2，并新增认证画像评测集。

## 0.1.0 - 2026-09-13

- 建立模块化单体仓库骨架。
- 新增 Spring Boot WebFlux、MyBatis、Flyway、PostgreSQL 和 Redis 基础设施。
- 新增 Conversation、Run 和 SSE/AG-UI 风格事件链路。
- 新增同 Session 串行、跨 Session 并行的 Run 调度器。
- 新增本地 Stub Medical Agent 和 React 聊天壳。
- 固定 AgentScope Java 2.0.1 Core、OpenAI 模型扩展与 AG-UI 扩展依赖，不引入 OpenAI 官方 Java SDK。
- 新增初始 OpenAPI、事件 Schema 和 M1 评测样本。
- 修复 Session 队列回收与新 Run 入队的竞争窗口，并加入重复并发回归测试。

# Medical Navigation Agent

面向美国场景的医疗导诊 Agent 技术验证项目。当前实现里程碑为 **M8：多 Session 与会话历史**。

系统设计见 [`doc/MedNav_Technical_Design_v0.4.md`](doc/MedNav_Technical_Design_v0.4.md)，开发阶段与目录约定见 [`doc/MedNav_Development_Guide_v0.1.md`](doc/MedNav_Development_Guide_v0.1.md)，阶段进展见 [`doc/development_log.md`](doc/development_log.md)，当前验收结论见 [`doc/MedNav_Technical_Validation_Report_v0.1.md`](doc/MedNav_Technical_Validation_Report_v0.1.md)。

## 当前已覆盖

- Spring Boot WebFlux、Actuator、MyBatis、Flyway；
- AgentScope Java 2.0.1 Core、OpenAI 模型扩展与 AG-UI 扩展；
- PostgreSQL 与 Redis 本地 Compose；
- Conversation、Run、SSE 事件契约；
- 同一 Session Run 串行、不同 Session 并行的调度器；
- 可确定输出的 Stub Medical Agent；
- React + TypeScript + Vite 聊天界面；
- 对话式用户名识别、独立安全密码控件和 BCrypt 密码散列；
- 新用户画像合并提问、已知字段排除、最多三轮降级；
- Session 内画像暂存，以及结束 Session 时的 MyBatis JSONB 持久化；
- AgentScope Java 的 OpenAI 模型适配与 `primaryModel`、`summaryModel`、`semanticVerificationModel` 延迟加载 Bean；
- SymptomExtraction、ClinicalReadiness、DiseaseInference 三个 AgentScope Skill；
- 症状 Working Context、单问题追问、最多三轮和 0.60 降级门禁；
- 最多三个疾病假设、严重程度、估计概率、用户事实依据和公开字段过滤；
- `ASK_USER | READY | READY_LIMITED | INSUFFICIENT_INFORMATION` 四态契约；
- CMS 三数据集稳定 ID 解析、HTTP 条件下载、SHA-256 内容寻址留档；
- 三份 CSV 严格 Schema/类型校验、批量 staging、标准化和原子快照发布；
- 基于 Facility ID、NPI、affiliation 与专业的医院—医生事实查询，医生候选不做评分排序；
- 每日 `04:00 America/Los_Angeles` 更新、数据库租约防重入、失败保留上一 ACTIVE 快照；
- 浏览器定位授权、拒绝后手动地址/ZIP 降级，以及 Google Geocoding 客户端；
- 同 city/state 全量医院的 DRIVE/TRANSIT/WALK Route Matrix、100 元素拆批和 10 分钟缓存；
- `contextVersion`、`locationRevision`、`clinicalRevision` 及过期定位结果丢弃；
- LocationTask 与临床 Run 独立执行，双方 READY 后发布一次 revision-bound Join 事件；
- 严重/中度/轻度疾病分支分别执行全市、5 miles、10 miles 医院范围规则；
- 医院按 CMS Overall Rating 降序、驾车距离升序，医生按专业与 affiliation 硬过滤后以 NPI 稳定截断；
- 最多 `3 × 2 × 2` 的疾病—医院—医生树、Evidence、版本化加权置信度和疾病级降级；
- 最多三轮的失败条目局部修复，以及按风险条件触发的 AgentScope Semantic Verification；
- Redis 用户快照与 Session 工作内存，长期数据始终以 PostgreSQL 为唯一事实源；
- 显式结束或空闲 10 分钟触发的幂等 Session Finalizer，以及 Summary Model 失败时的确定性摘要降级；
- `user_profile`、`session_clinical`、`session_summary` 事务提交，成功提交后才删除 Session 工作缓存；
- 明确既往诊断与药物过敏/食物过敏/用药禁忌/饮食限制的保守白名单提取，不将普通症状或偏好当作长期事实；
- Redis 暂时不可用时的可重试 `503 MEMORY_CACHE_UNAVAILABLE` 降级；
- 历史 Session 症状摘要查询/删除 API、Memory 指标埋点、OpenAPI 0.7.0 和 M1 至 M7 评测样本。
- 同一用户多 Session、关系数据库增量 Transcript、结构化推荐快照回放、ChatGPT 风格历史侧栏、10 分钟内恢复和已结束 Session 只读；OpenAPI 0.8.2 与 M8 评测样本。

默认 `local` Profile 不会在启动时导入 CMS 文件，症状、手动定位和路线均由确定性 Stub 驱动；CMS 定时刷新默认启用，并只会在洛杉矶时间凌晨 4 点触发。`openai` Profile 使用 AgentScope OpenAI 扩展和真实 Google 客户端，配置为空时按可恢复错误降级。

## 环境要求

- JDK 17+
- Node.js 20+
- Docker Desktop 或兼容的 Docker Compose

项目自带 Maven 启动脚本，第一次运行会把 Maven 3.9.11 下载到 `backend/.mvn`。

## 数据库策略

默认使用项目独立的 PostgreSQL 数据库 `mednav`、Compose 项目 `mednav` 和命名 volume `mednav-postgres`，不会自动连接或修改机器上已有的数据库。需要复用已有 PostgreSQL 时，通过 `POSTGRES_URL`、`POSTGRES_USER`、`POSTGRES_PASSWORD` 切换；该目标数据库应由本项目 Flyway 独占管理。

## 启动

在项目根目录执行：

```powershell
Copy-Item .env.example .env
docker compose -f .\infra\compose.yml up -d
```

启动后端：

```powershell
cd .\backend
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```

启动前端：

```powershell
cd .\frontend
npm install
npm run dev
```

打开 `http://localhost:5173`。后端健康检查为 `http://localhost:8080/actuator/health`。

## 测试

```powershell
cd .\backend
.\mvnw.cmd test

cd ..\frontend
npm test
npm run build
```

当前 M8 基线验证结果：后端 74 项自动化测试、前端 3 项测试和生产构建通过；6 条 M8 评测样本（M1 至 M8 共 95 条）及 OpenAPI/JSON 契约通过格式校验。项目提供的三份真实 CMS CSV 已在 PostgreSQL 完成全量导入，推荐管线只查询与路线结果相同的 CMS snapshot。

真实外部 API 小样本验收已执行：Google Geocoding 与 Routes 在 Dothan, AL 测试地址上成功返回同城医院及 DRIVE/TRANSIT/WALK 路线；GPT-5.6 Terra 主模型成功返回 `READY` 和 3 个结构化疾病假设，Summary Model 成功生成 Session 摘要；通过临时验收阈值强制触发 GPT-5.6 Luna Verification Model，最终推荐以 `READY_WITH_WARNINGS` 提交。生产默认 Verification 阈值未修改。

## 对话式认证与画像链路

1. `POST /api/conversations` 创建会话；
2. `GET /api/conversations/{conversationId}/events` 建立 SSE；
3. 用户把用户名作为普通消息提交到 `POST /api/conversations/{conversationId}/runs`；
4. `AUTH_SECRET_REQUEST` 事件触发前端安全密码框；
5. 密码仅提交到 `/api/auth/conversation/login` 或 `/api/auth/conversation/register`；
6. 新用户在同一对话内补充出生日期、性别、身高和体重，最多三轮；
7. `POST /api/conversations/{conversationId}/complete` 结束 Session，并将有效画像更新写入长期数据库。

密码不属于聊天消息，不进入 SSE 事件、Agent 输入、Memory 或日志。注册账户会立即持久化；画像遵循 Session 结束时统一提交规则。

## Profile

- `local`：确定性 Stub Clinical/Maps + 本地 PostgreSQL/Redis，可用 `Dothan, AL 36301` 和 severe chest pain 演示 M6；浏览器坐标不会被伪装成测试城市；
- `test`：自动化测试，不要求外部服务；
- `openai`：启用 AgentScope OpenAI 模型扩展和 Google Geocoding/Routes；需要模型配置及 `GOOGLE_MAPS_API_KEY`。

`SUMMARY_MODEL` 用于 Session 结束时生成结构稳定的历史摘要；失败时使用确定性摘要并记录降级指标。`VERIFICATION_MODEL` 只在新推荐存在低置信度或语义一致性风险时调用；认证、画像、普通追问和无风险推荐不会调用。

## M3 症状链路

1. 认证和新用户画像采集结束后，每条用户消息仍创建独立 Run；
2. 同一 Session 的 Run 依次执行，新的症状输入永不被忽略，并增加 clinical revision；
3. Agent 合并当前消息与已有 `SymptomCase`，判断是否继续追问；
4. 信息不足时一次只问一个未问过的问题，最多三轮；
5. 三轮结束时，信息完整度不低于 0.60 才返回 `READY_LIMITED`，否则返回无疾病假设的 `INSUFFICIENT_INFORMATION`；
6. `READY` / `READY_LIMITED` 最多给出三个疾病假设，并附严重程度、估计概率和用户已陈述事实；
7. 内部 `targetSpecialties` 只留给后续 CMS 匹配，不会出现在公开 `CLINICAL_RESULT` 事件中。

如果主模型不可用或结构校验失败，系统仍会先把本轮原始症状叙述写入当前 Session 工作内存，再返回可恢复错误；不会把“已保留信息”仅停留在响应文案中。

真实 Secret 不得提交仓库。`.env.example` 只包含开发占位值。

## M4 CMS 数据链路

三个数据集以 CMS 稳定 Dataset ID 定位，下载 URL 每次从 CMS Metastore 元数据解析：

- Hospital General Information：`xubh-q36u`；
- Doctors and Clinicians National Downloadable File：`mj5m-pzi6`；
- Facility Affiliation：`27ea-46a8`。

每天 `04:00 America/Los_Angeles` 由调度器尝试刷新。刷新使用数据库租约避免多实例同时导入，并通过 ETag / Last-Modified 条件下载；只有三份文件都通过严格列名、列数、顺序、类型和 Join 完整性关卡时，才在同一事务中切换 ACTIVE 快照。失败快照保留审计元数据，但不影响上一完整快照。

开发时如需将 `CMS_DATA_DIR` 下三个固定文件导入一次，可在启动后端前设置：

```powershell
$env:CMS_IMPORT_LOCAL_ON_STARTUP = "true"
cd .\backend
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```

全量文件采用流式解析和 JDBC 批写，不会整体载入内存。医生候选按医院 affiliation 与疾病对应的 CMS 专业硬匹配；相同 NPI 即使命中多个专业也只返回一个候选，并携带所有命中的专业事实，不引入医生评分或 Healthgrades 排序。

## M5 定位、路线与 Join

认证成功后前端异步请求浏览器定位权限。授权成功时提交经纬度并执行 Google reverse geocoding；拒绝、超时或解析失败时显示手动地址卡，可提交美国地址、`City + State` 或 ZIP Code。拒绝位置不会使登录失败，但在位置 READY 前不得生成附近医院结果。

位置解析成功后，后端从当前 CMS ACTIVE snapshot 查询相同 city/state 的全部医院，不做候选数量截断或直线距离预筛。路线层对每家医院计算：

- `DRIVE`：距离和预计时间，供 M6 距离门限与医院排序使用；
- `TRANSIT`、`WALK`：距离和预计时间，仅供展示，允许单项没有路线；
- 单批最多 100 个“用户—医院—交通方式”元素，多批受控并行；
- 单元素缓存 10 分钟，Key 包含规范化位置、Facility ID、交通方式、CMS snapshot 和路线参数版本。

定位任务不占用 Session Run 队列，可与症状分析重叠执行。同一 Session 的对话 Run 仍严格串行；新位置递增 `locationRevision` 并取消旧任务，新症状递增 `clinicalRevision`。只有两个 revision 均为当前值且 Location/Clinical 都 READY 时才发布 `NAVIGATION_CONTEXT_READY`。

相关接口：

```text
POST /api/location/consent
POST /api/location/manual
GET  /api/navigation/runs/{taskId}  (X-Conversation-Id header)
```

真实 Google 联调使用 `MAPS_MODE=google` 和 `GOOGLE_MAPS_API_KEY`。默认 Stub 不会把真实浏览器坐标映射成虚构地址；本地演示应在降级卡中输入上述 fixture 地址。

## M6 三级推荐与 Verification

Location 与 Clinical Join-ready 后，系统异步生成最终三级树：疾病最多 3 个，每个疾病最多 2 家医院，每家医院最多 2 名医生。每个疾病分支独立应用范围规则：`SEVERE` 保留当前城市医院，`MODERATE` 要求 DRIVE ≤ 5 miles，`MILD` 要求 DRIVE ≤ 10 miles。

同一疾病下的医院依次按 CMS Overall Rating 降序、DRIVE distance 升序、Facility ID 升序排列。医生只接受相同 CMS snapshot 中同时满足目标专业和 Facility Affiliation 的记录，按 NPI 升序稳定截断，不表达质量排名；没有匹配医生时保留医院并显示数据限制。公开疾病节点不包含内部 `targetSpecialties`。

推荐事实分别引用用户陈述、CMS Hospital/DAC/Facility Affiliation 和路线结果，并使用 `confidence-v1` 计算链路置信度。硬门禁优先；失败时最多三轮只清理或重建失败条目，不整体重跑。Semantic Verification 没有 Tool、Memory 或最终 PASS 权限，仅在风险策略命中时由独立 AgentScope 模型返回 findings。

患者可见内容按当前症状对话识别为 `zh-CN` 或 `en-US`。内部枚举、CMS 字段名和 Evidence 保持稳定英文契约，但疾病名称、症状事实、严重程度、评分、距离单位、推荐理由和提示语必须使用用户语言；美国医院、医生和地址的官方专名保持原文。

结果入口：

```text
GET /api/navigation/runs/{taskId}/results  (X-Conversation-Id header)
SSE RECOMMENDATION_RESULT
SSE LOCAL_REPAIR_PROGRESS
```

## M7 Memory 与 Session Finalizer

用户登录或注册后，系统先从 PostgreSQL 加载长期画像和历史 Session 摘要，再在 Redis 创建当前 Session 的工作副本。用户画像补充、当前症状和初诊疾病只实时更新该工作副本，不在每个 Turn 重复写长期表。

`POST /api/conversations/{conversationId}/complete` 或 Session 空闲 10 分钟时启动 Finalizer：生成摘要，在同一数据库事务中幂等写入画像、症状、最多 3 个初诊疾病和摘要，然后刷新用户缓存并删除 Session 工作副本。数据库提交失败时保留工作副本，便于重试。不引入偏好表或向量 Memory。

历史记忆接口：

```text
GET    /api/users/me/session-memories
DELETE /api/users/me/session-memories/{sessionId}
```

删除操作只允许针对已收口的历史 Session，不允许删除仍在运行的工作内存。

## M8 多 Session 与历史消息

同一用户可以创建多个 Session。用户和 Assistant 的可见消息按 Turn 增量写入 PostgreSQL 的 `conversation_session`、`conversation_message` 表；它们是产品会话历史，不属于 Agent Memory，也不依赖网页 DOM 或浏览器本地存储。密码继续只走 Secret API，不会进入 Transcript。

最终 `RECOMMENDATION_RESULT` 也以结构化快照写入 `conversation_message.structured_payload`。重新打开历史 Session 时，前端会恢复疾病—医院—医生推荐树；迁移前已经生成但未保存结构化快照的旧推荐无法回填。

前端左侧栏列出当前用户的 Session：

- 点击“新建会话”会创建独立 Session，不要求重新登录；
- 页面关闭、刷新或切换后，ACTIVE Session 在空闲未满 10 分钟且后端运行态仍存在时可以恢复；
- 显式结束或空闲达到 10 分钟的 Session 保留消息但永久只读；
- 服务重启后历史仍可查看，当前版本不从 Transcript 重建 Agent 运行态。

接口：

```text
POST /api/users/me/sessions
GET  /api/users/me/sessions
GET  /api/users/me/sessions/{sessionId}/messages
POST /api/users/me/sessions/{sessionId}/resume
```

## 本地并发性能基线

项目提供固定速率压测工具 `performance/mednav-load.mjs`，同时测量 Run 的 HTTP 接入延迟和 SSE 完成延迟。在真实本地 PostgreSQL/Redis、Stub 临床模型与 Stub 地图条件下，使用 100 个并行 Session 连续执行三轮 60 秒测试，共 36,000 个 Run：200 QPS 下三轮最差完成 P99 为 478.05 ms，观测错误率为 0%。

该结果用于验证应用自身的调度、持久化和事件链路，不代表真实 OpenAI 或 Google Maps 的端到端容量。完整环境、指标口径、阶梯结果和复现命令见 `doc/MedNav_Performance_Test_Report_v0.1.md`。

# Medical Navigation Agent 开发实施指南 v0.1

> 本文档描述如何把 `MedNav_Technical_Design_v0.4.md` 落地为一个可启动、可测试、可持续迭代的工程。

| 项目 | 内容 |
|---|---|
| 状态 | M7 核心实现已完成，进入本地集成验证与技术验证报告阶段 |
| 日期 | 2026-09-22 |
| 上位设计 | `MedNav_Technical_Design_v0.4.md` |
| 适用范围 | 当前技术验证版本，不包含法律法规落地工作 |
| 工程策略 | 模块化单体、契约先行、Stub 先跑通、逐段替换真实依赖 |

## 1. 文档边界

V4 技术设计负责回答“系统要做什么、业务规则和数据约束是什么”；本文档负责回答“按什么顺序开发、代码放在哪里、每个阶段怎样运行和验收”。

若两份文档出现实现层冲突，采用以下规则：

1. 产品行为、排序规则、Memory 语义、并发规则和数据 Schema 以 V4 为准。
2. 工程目录、依赖接入、开发阶段和本地运行方式以本文档为准。
3. 本文档明确覆盖 V4 中“必须使用 OpenAI 官方 Java SDK 和自定义 `OpenAiModelAdapter`”的旧实现描述：本项目直接使用 AgentScope Java 2.x 的 OpenAI 模型扩展。
4. 模型名称和 API 凭证仍由部署者配置，不在代码或文档中写死。

## 2. 当前实施决策

### 2.1 采用模块化单体

技术验证阶段先使用一个 Spring Boot 后端和一个 React 前端，不拆微服务。认证、Agent Runtime、CMS 导入、路线计算、推荐、Memory、验证和评测在同一后端工程内按 package 隔离。

这样可以先验证完整业务链路，同时保留未来按边界拆分服务的能力，避免早期引入分布式事务、跨服务追踪和部署编排成本。

### 2.2 AgentScope 与 OpenAI 的接入方式

后端采用：

- `agentscope-core`：使用 `ReActAgent`、Tool、Hook、消息及模型抽象；
- `agentscope-extensions-model-openai`：直接接入 OpenAI GPT 模型；
- `agentscope-extensions-agui`：若当前选定版本的接口满足需求，则用于 AG-UI 协议适配；不足部分由项目内 `agui` 模块补齐；
- 不引入 `com.openai:openai-java`；
- 不创建 `OpenAiModelAdapter`；
- 不使用 `HarnessAgent` 自带的长期记忆作为项目事实源，因为 V4 已定义 PostgreSQL + Redis 的 Memory 生命周期、事务和一致性规则。

AgentScope Java 2.x 将模型提供商拆成独立扩展；OpenAI 扩展可通过 `openai:<model>` 注册名或 `OpenAIChatModel.builder()` 创建模型。官方说明见 [AgentScope OpenAI Model](https://github.com/agentscope-ai/agentscope-java/blob/main/docs/v2/en/integration/model/openai.md) 和 [AgentScope Java Quickstart](https://github.com/agentscope-ai/agentscope-java/blob/main/docs/v2/en/docs/quickstart.md)。

Maven 依赖形态如下，版本在创建工程时统一固定在一个属性中：

```xml
<properties>
    <java.version>17</java.version>
    <agentscope.version>${AGENTSCOPE_VERSION}</agentscope.version>
</properties>

<dependencies>
    <dependency>
        <groupId>io.agentscope</groupId>
        <artifactId>agentscope-core</artifactId>
        <version>${agentscope.version}</version>
    </dependency>
    <dependency>
        <groupId>io.agentscope</groupId>
        <artifactId>agentscope-extensions-model-openai</artifactId>
        <version>${agentscope.version}</version>
    </dependency>
    <dependency>
        <groupId>io.agentscope</groupId>
        <artifactId>agentscope-extensions-agui</artifactId>
        <version>${agentscope.version}</version>
    </dependency>
</dependencies>
```

说明：上面的 `${AGENTSCOPE_VERSION}` 表示建项时要明确选择并锁定的 2.x 版本，不是实际 Maven 环境变量写法。正式 `pom.xml` 应使用例如 `<agentscope.version>x.y.z</agentscope.version>` 的确定值，并通过构建和依赖树验证三个模块版本完全一致后提交。

### 2.3 三个模型槽位

使用同一个 AgentScope OpenAI 模型扩展创建三个独立 Spring Bean：

```text
primaryModel              核心 MedicalNavigationAgent 推理
summaryModel              SessionSummarySkill
semanticVerificationModel 条件触发的语义验证
```

对应配置：

```yaml
mednav:
  model:
    primary: ${PRIMARY_MODEL:}
    summary: ${SUMMARY_MODEL:}
    semantic-verification: ${VERIFICATION_MODEL:}
    semantic-verification-enabled: ${SEMANTIC_VERIFICATION_ENABLED:true}
```

模型名称为空时，`local` Profile 必须能自动切换到 Stub Model，使项目在没有 API Key 时也能启动和执行基本 E2E。

## 3. 建议的仓库目录

```text
agent-medical/
├── backend/
│   ├── pom.xml
│   ├── mvnw
│   ├── mvnw.cmd
│   └── src/
│       ├── main/
│       │   ├── java/com/example/mednav/
│       │   │   ├── MedNavApplication.java
│       │   │   ├── api/
│       │   │   ├── agui/
│       │   │   ├── agent/
│       │   │   ├── skill/
│       │   │   ├── tool/
│       │   │   ├── hook/
│       │   │   ├── gate/
│       │   │   ├── runtime/
│       │   │   ├── context/
│       │   │   ├── auth/
│       │   │   ├── profile/
│       │   │   ├── clinical/
│       │   │   ├── location/
│       │   │   ├── hospital/
│       │   │   ├── doctor/
│       │   │   ├── provider/cmsimport/
│       │   │   ├── recommendation/
│       │   │   ├── verification/
│       │   │   ├── memory/
│       │   │   ├── mapper/
│       │   │   ├── evaluation/
│       │   │   ├── observability/
│       │   │   └── config/
│       │   └── resources/
│       │       ├── application.yml
│       │       ├── application-local.yml
│       │       ├── application-openai.yml
│       │       ├── db/migration/
│       │       ├── mapper/
│       │       ├── prompts/
│       │       └── fixtures/
│       └── test/
├── frontend/
│   ├── package.json
│   ├── vite.config.ts
│   └── src/
│       ├── api/
│       ├── agui/
│       ├── components/
│       ├── features/
│       │   ├── conversation/
│       │   ├── auth/
│       │   ├── location/
│       │   └── recommendation/
│       ├── pages/
│       └── types/
├── contracts/
│   ├── openapi/
│   ├── events/
│   └── json-schema/
├── evaluation/
│   ├── sets/
│   ├── labels/
│   ├── runners/
│   └── reports/
├── infra/
│   ├── compose.yml
│   ├── postgres/
│   └── redis/
├── scripts/
├── dataset/
├── doc/
└── README.md
```

目录原则：

1. `backend` 是单 Maven 模块，先不建立多模块 Maven 父子工程。
2. Java package 按业务能力组织，不按 Controller/Service/Repository 做全局横切目录。
3. AgentScope 相关代码只放在 `agent`、`skill`、`tool`、`hook` 和 `runtime`；确定性业务规则不得藏在 prompt 中。
4. API、AG-UI 事件和模型结构化输出的 JSON Schema 放在 `contracts`，作为前后端及评测的共同契约。
5. `dataset` 保存用户已提供的数据或开发样本；CMS 下载的运行时快照放到可配置数据目录，不直接提交仓库。
6. `evaluation` 与业务代码同级，确保评测集从第一阶段开始建设，而不是上线前补做。

## 4. 运行 Profile 与替身边界

| Profile | 模型 | 地图/路线 | CMS | PostgreSQL/Redis | 用途 |
|---|---|---|---|---|---|
| `test` | Fake Model | Fake | Fixture | Testcontainers | 自动化测试 |
| `local` | Stub Model | Stub | Fixture | Docker Compose | 无外部密钥本地开发 |
| `integration` | 可选真实模型 | MockWebServer 或测试 API | 固定快照 | Docker Compose | 模块联调 |
| `openai` | AgentScope OpenAI 扩展 | 真实 Google API | 本地已发布快照 | 部署环境 | 真实链路技术验证 |

Stub 必须模拟接口行为而不是绕开业务层：它仍返回与真实模型相同的结构化结果，仍通过相同 Hook、Gate、排序和 SSE/AG-UI 输出路径。

## 5. 分阶段实施

### 阶段 0：工程与评测基线

目标：先固定契约和可重复验证方式。

交付物：

- 根目录 `README.md`、`.gitignore`、环境变量示例文件；
- 后端和前端基础 lint/test 配置；
- `contracts` 中的 Run、事件、症状、疾病假设、路线、推荐树、Evidence Schema；
- Evaluation Set v0：认证、画像抽取、症状抽取、readiness、疾病输出格式、CMS 专业映射、推荐排序、Memory 合并；
- 初始标签规范、数据集版本号和 prompt 版本规则；
- CI 最小流程：后端单测、前端单测、契约校验。

退出条件：所有核心 DTO/事件结构有版本号，至少每个核心模块有少量正常样本、边界样本和失败样本。

### 阶段 1：可运行的项目骨架

目标：从空目录得到一个不依赖外部 API Key 的可启动系统。

交付物：

- Spring Boot WebFlux、Actuator、MyBatis、Flyway；
- PostgreSQL 和 Redis 的 Docker Compose；
- `GET /actuator/health`；
- `POST /api/conversations`、`POST /api/conversations/{id}/runs`、SSE 事件流骨架；
- AgentScope Core + Stub Model；
- `SessionRunScheduler` 最小实现，保证同 Session Run 串行、跨 Session 并行；
- React + TypeScript + Vite 聊天页；
- 前端可提交消息并看到 Stub Agent 的流式事件；
- 一个贯穿前后端的 smoke test。

退出条件：新机器按 README 启动 PostgreSQL/Redis、后端和前端后，可以完成一次 Stub 对话；没有 OpenAI 和 Google 密钥也不会启动失败。

### 阶段 2：对话式认证与画像

目标：打通用户门禁和新用户画像三轮降级。

交付物：

- `user_account`、`user_profile` 等初始 migration；
- MyBatis Mapper、认证 Service、密码 hash；
- 用户名唯一约束和并发注册冲突处理；
- 登录/注册 Tool Group 和认证前 Tool 白名单；
- 密码字段通过受控前端事件进入认证接口，不进入模型消息、日志或 Memory；
- ProfileExtractionSkill、Profile Hook、最多三轮追问；
- 老用户的“新症状或资料补充”合并询问。

退出条件：注册、重复用户名、登录成功、密码失败、新/老用户流程及三轮降级全部有集成测试。

### 阶段 3：核心 Agent 与临床就绪判断

目标：使用真实 AgentScope OpenAI 扩展替换 Stub，同时保持可回退。

交付物：

- `MedicalNavigationAgentFactory`；
- `primaryModel`、`summaryModel`、`semanticVerificationModel` 三个模型 Bean；
- 模型配置、超时、重试、预算和脱敏日志；
- SymptomExtraction、ClinicalReadiness、DiseaseInference Skills；
- ClarificationHook 和 JSON Schema Gate；
- `ASK_USER | READY | READY_LIMITED | INSUFFICIENT_INFORMATION` 四态契约；
- 最多三轮症状追问和 0.60 降级阈值；
- Stub 与真实模型使用同一组契约测试。

退出条件：模型不可用时能给出明确可恢复错误；输出不合法时由 Hook/Gate 拦截；四种 readiness 路径均可稳定复现。

### 阶段 4：CMS 本地数据链路

目标：形成可查询、可回滚的医院和医生本地事实库。

交付物：

- 三个 CMS CSV 的下载器、校验器、staging 表和正式表；
- 每日 `04:00 America/Los_Angeles` 定时任务；
- HTTP 条件下载、校验和、数据源时间、导入日志；
- Schema 列名、数量、顺序和类型的零漂移 Gate；
- 三文件同批次原子发布，失败继续使用上一完整 snapshot；
- Facility ID、NPI、affiliation 和 DAC 专业关联查询；
- 固定 CMS fixture 的导入及 Join 测试。

退出条件：可用 fixture 完成医院—医生查询；破坏任一 CSV Schema 会阻止发布且不影响上一快照。

### 阶段 5：定位、路线与并行 Join

目标：实现定位流与初诊流并行，并在推荐点汇合。

交付物：

- 浏览器定位授权和手动地址降级；
- Google Geocoding 与 Routes Compute Route Matrix 客户端；
- 同 city/state 全量医院查询；
- 所有医院的多交通方式路线请求，最多 100 个路线元素/批；
- 路线缓存 TTL 10 分钟；
- `locationRevision`、`clinicalRevision`、`contextVersion`；
- 新用户消息创建新 Run，旧 revision 结果不可提交；
- 独立 Tool Call 并行及 Recommendation Join。

退出条件：可证明 Session 内 Run 串行、Session 间并行、Run 内定位与症状处理并行；定位拒绝时不伪造“附近”推荐，并能提示用户提供手动地址。

### 阶段 6：三级推荐树与验证

目标：产出最终疾病—医院—医生结果。

交付物：

- 疾病最多 3 个；
- 每个疾病按严重程度执行全市、5 miles 或 10 miles 范围规则；
- 每个疾病下医院按 CMS Overall Rating 降序、驾驶路线距离升序，最多 2 家；
- 每家医院按 CMS 专业与 affiliation 硬条件选医生，不做医生评分，稳定截断最多 2 名；
- Evidence、推荐理由和用户可见置信度；
- 确定性 Gate；
- 条件触发的 Semantic Verification；
- 最多三轮、只重跑失败条目的局部修复和额度耗尽降级。

退出条件：排序、数量上限、去重、距离规则和 Evidence 均通过确定性测试；验证失败不会导致全链路无条件重跑。

### 阶段 7：Memory、系统评测与硬化

目标：补齐 V4 Memory 生命周期并建立可复验的技术验证结论。

交付物：

- PostgreSQL 长期表 `user_profile`、`session_clinical`、`session_summary`；
- Redis 用户长期数据缓存和 Session 工作缓存；
- Session 期间只更新工作缓存；
- Session 正常结束或空闲 10 分钟后，由 Finalizer 在同一事务中写长期 Memory；
- 提交成功后刷新用户缓存并删除 Session 缓存；失败保留缓存并幂等重试；
- SessionSummarySkill；
- Agent Memory 不保存 Transcript，不实现偏好表和向量 Memory；产品会话历史在独立关系表中增量持久化；
- 模块评测、端到端评测、并发测试、故障注入、成本和延迟报告；
- OpenTelemetry/Micrometer 指标及关键日志埋点。

退出条件：Memory 一致性与幂等测试通过；评测报告可以按模型版本、prompt 版本、CMS snapshot 和代码版本复现。

### 阶段 8：多 Session 与历史消息

目标：让同一用户可创建和管理多个独立 Session，并提供可恢复/只读边界明确的历史会话体验。

交付物：

- PostgreSQL `conversation_session` 与 `conversation_message`，每个 Turn 增量持久化；
- 创建、列表、消息回放和恢复 API；
- ChatGPT 风格 Session 侧栏；
- ACTIVE 且空闲未满 10 分钟的 Session 可恢复；
- 显式结束或空闲满 10 分钟的 Session 永久只读；
- 密码、Token 和模型隐藏推理不进入 Transcript；
- M8 接口集成测试、前端回放去重测试及独立评测样本。

退出条件：同用户多 Session 隔离、顺序回放、所有权、恢复边界和只读门禁全部通过自动化测试。

## 6. 第一轮实际开发范围

第一次提交只做阶段 0 和阶段 1，不直接实现全部业务。建议将范围冻结为：

1. 创建上述仓库骨架。
2. 配置 Maven Wrapper 和前端包管理锁文件。
3. 创建 PostgreSQL/Redis Compose。
4. 建立 Spring Boot、Flyway、MyBatis、Actuator。
5. 接入 AgentScope Core 和可确定输出的 Stub Model。
6. 实现 Conversation、Run、SSE/AG-UI 最小契约。
7. 建立 `SessionRunScheduler` 的单 Session 串行测试。
8. 建立 React 聊天壳和最小推荐树占位组件。
9. 添加后端、前端 smoke test 和启动 README。

这一轮明确不做：真实 OpenAI 调用、真实 Google 调用、CMS 全量导入、完整认证、最终推荐算法和正式 Memory Finalizer。它们应在骨架稳定后按阶段逐一替换 Stub。

## 7. 本地启动目标

预期开发命令：

```powershell
docker compose -f .\infra\compose.yml up -d

cd .\backend
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local

cd .\frontend
npm install
npm run dev
```

首个可运行版本应满足：

- 后端健康检查为 `UP`，并分别暴露 PostgreSQL 和 Redis 健康状态；
- Flyway 自动建表，MyBatis 能执行一条真实数据库查询；
- 浏览器能创建 Conversation、提交消息、订阅事件并显示 Stub 回复；
- 同一 Session 同时提交两条 Run 时严格按序执行；
- 不同 Session 的 Run 可并发执行；
- 停止 OpenAI/Google 配置不会影响 `local` Profile 启动。

## 8. 配置与 Secret 约定

仓库只提交 `.env.example`，不得提交实际 `.env`、API Key、数据库密码或用户数据。

建议变量：

```text
SPRING_PROFILES_ACTIVE
POSTGRES_URL
POSTGRES_USER
POSTGRES_PASSWORD
REDIS_HOST
REDIS_PORT
OPENAI_API_KEY
PRIMARY_MODEL
SUMMARY_MODEL
VERIFICATION_MODEL
SEMANTIC_VERIFICATION_ENABLED
GOOGLE_MAPS_API_KEY
MAPS_MODE
GOOGLE_ROUTE_BATCH_SIZE
GOOGLE_ROUTE_MAX_CONCURRENCY
GOOGLE_ROUTE_CACHE_TTL
GOOGLE_ROUTE_PARAMETERS_VERSION
CMS_DATA_DIR
CMS_REFRESH_ENABLED
CMS_IMPORT_LOCAL_ON_STARTUP
CMS_BATCH_SIZE
CMS_MIN_AFFILIATION_JOIN_RATE
```

日志对 `userId` 做不可逆 hash，不记录密码、精确位置原始请求、模型隐藏推理、Secret 或完整 Tool payload。

## 9. 契约先行规则

每个 Skill、Tool、Hook 和 Service 在实现前先定义：

1. 输入 DTO/JSON Schema；
2. 输出 DTO/JSON Schema；
3. 错误码和可重试性；
4. 超时和预算；
5. 日志字段；
6. 正常、边界、非法和降级样本；
7. 对应的确定性断言或模型评测指标。

模型只能输出“候选判断”；以下行为必须由确定性代码实现：认证、数据库唯一性、CMS Join、距离阈值、医院排序、数量截断、revision 提交门禁、Memory 事务和缓存删除时机。

## 10. 测试分层

| 层级 | 目标 | 主要工具 |
|---|---|---|
| Unit | 排序、过滤、合并、revision、预算 | JUnit 5 |
| Contract | JSON Schema、AG-UI 事件、模型结构输出 | 固定 fixture |
| Integration | PostgreSQL、Redis、MyBatis、CMS 导入 | Testcontainers |
| External client | OpenAI/Google/CMS HTTP 行为 | MockWebServer |
| Agent evaluation | Skill、readiness、疾病假设、Explanation | 版本化 Evaluation Set |
| E2E | 注册到推荐树的完整路径 | Stub 优先，真实模型抽样 |
| Concurrency | Session 内串行、Session 间并行、Run 内并行 | Reactor Test + 并发集成测试 |
| Fault injection | 模型、路线、导入、事务和 Redis 失败 | 可控 Fake/Proxy |

真实模型测试不得成为普通单元测试的前置条件；应单独标记并按需运行，避免每次构建产生不稳定性和 API 成本。

## 11. 里程碑与完成定义

| 里程碑 | 可演示结果 | 是否需要真实外部 API |
|---|---|---|
| M0 | 契约和 Evaluation Set 基线 | 否 |
| M1 | Stub 全栈对话可运行 | 否 |
| M2 | 对话式注册、登录和画像 | 否 |
| M3 | AgentScope OpenAI 症状理解与 readiness | OpenAI |
| M4 | CMS 本地医院/医生关联查询 | CMS 下载或固定快照 |
| M5 | 并行定位与路线 Join | Google |
| M6 | 三级推荐树、验证和降级 | OpenAI + Google |
| M7 | Memory 一致性、完整评测和技术验证报告 | 按评测配置 |
| M8 | 多 Session、历史消息、恢复与只读侧栏 | 无新增外部 API |

“能够运行”不是仅指应用进程启动，而是指该里程碑的入口、核心路径、输出、失败路径和自动化测试都可复现。

## 12. 开发期间需持续维护的记录

- `README.md`：当前能运行到哪个里程碑及准确启动命令；
- `CHANGELOG.md`：重要行为和契约变更；
- `contracts`：API、事件和结构化输出的版本；
- `evaluation/sets`：新发现的失败样本必须回填；
- prompt 版本：每次影响模型行为的修改必须可追踪；
- 数据版本：CMS snapshot ID、源文件时间和校验和；
- ADR：只记录会影响长期工程边界的决策，例如 AgentScope 版本升级、Memory 一致性模型或部署拆分。

## 13. 当前实现状态与下一步

项目已完成 M1 至 M8 核心链路：全栈对话、认证画像、临床就绪判断、CMS 本地事实库、定位/多交通路线/revision Join、最多 `3 × 2 × 2` 的三级推荐树、Redis Session 工作内存与 PostgreSQL 长期记忆收口，以及同用户多 Session 和历史消息侧栏。

M7 已落地用户快照缓存、Session 增量缓存、显式/空闲收口、Summary Model 及确定性降级、`user_profile/session_clinical/session_summary` 幂等事务、提交后删缓存、历史查询/删除 API、显式疾病史/核心约束白名单提取、模型失败时的原始症状保留、Micrometer 指标和 17 条 M7 评测样本。M8 将可见 Transcript 独立写入 `conversation_session/conversation_message`，不把它当作 Agent Memory 或事实源；同一用户可新建多个 Session，并在前端恢复未超时的 ACTIVE Session 或只读查看已结束历史。偏好表、向量 Memory 和服务重启后的运行态重建仍不在当前范围。

本地 PostgreSQL/Redis 完整收口冒烟、显式疾病史/核心约束的白名单提取，以及 Redis 不可用时的单元故障注入已通过。真实 Google Geocoding/Routes、GPT-5.6 Terra 临床推理与 Session Summary、GPT-5.6 Luna 条件式 Verification 小样本均已通过。AgentScope 2.0.1 的 Chat Completions 接入固定使用 `reasoning_effort=none`，并通过明确 JSON 契约与统一回退解析规避原生 Schema 兼容问题。下一步是补充长期事实删除/修正语义、执行 Redis 容器断开/恢复演练，并扩大外部 API 回归样本。

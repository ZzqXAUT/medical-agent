# Medical Navigation Agent 技术设计 v0.3

> 基于 AgentScope Java、OpenAI GPT、AG-UI、MyBatis 的门禁式单 Agent 技术验证系统

| 项目 | 内容 |
|---|---|
| 状态 | 第三版实现基线 |
| 日期 | 2026-09-02 |
| 继承 | `MedNav_Technical_Design_v0.2.md` |
| 目标市场 | 美国 |
| 产品阶段 | 对标真实应用场景的技术验证，不作为诊断或生产医疗系统 |
| 核心变化 | 对话式身份门禁、单 Agent + Skills、并行位置链路、3×3×3 推荐、局部门禁重试、精简分层 Memory、评测前置 |

## 1. 系统目标与输出

系统通过自然语言对话完成注册、登录、画像补全、症状理解和导诊推荐，不设置独立的固定登录/注册页面。通过身份门禁后，位置数据链路与疾病假设链路并行执行，最终输出最多 3 个疾病假设，每个疾病假设最多 3 家医院，每家医院最多 3 名医生，即最多 27 条“疾病—医院—医生”组合推荐。

每条结果必须包含：

- 疾病假设、严重程度、模型估计概率及支持/反对事实；
- 医院名称、地址、距离、数据来源；
- 科室及匹配理由；
- 医生名称、专业、评分、执业地点和推荐理由；
- 条目置信度及其主要构成；
- 数据缺失或无法验证的明确说明。

`27` 是上限而不是补齐目标。若真实数据不足、医生与医院关系无法验证或条目未通过门禁，允许返回少于 27 条，禁止复制、拼凑或生成不存在的数据。

## 2. 第三版核心原则

1. Java 17+、Spring Boot、AgentScope Java 为主体开发栈。
2. OpenAI GPT 模型作为 Agent LLM，通过 OpenAI 官方 Java SDK/Responses API 接入。
3. 只使用一个面向用户的 `MedicalNavigationAgent`；模块化模型能力封装为 Skill，确定性能力放入 Tool、Service 和 Hook，避免 Agent 间上下文传递。
4. 登录、注册和画像收集由 Agent 对话驱动，但认证成功由确定性 Auth Service 决定。
5. 密码永不进入 LLM、AgentState、对话历史、Memory、日志、Trace 或 Evaluation 数据。
6. Location Pipeline 无模型参与，并与症状—疾病假设链路并行。
7. 核心推理模型由 system prompt 自主决定何时追问、何时输出；不使用固定业务状态机。
8. Clarification 不再作为 Skill，由 Hook 验证追问合法性、重复性、轮数和输出就绪条件。
9. 去掉 v0.2 的 `SafetyEngine` 和 Urgency/Care-Level Routing；第三版仅保留疾病严重程度 `SEVERE | MODERATE | MILD`。
10. 质量门禁位于 Agent、Tool 和模块边界；最终 Verification 最多进行 3 轮局部修复，不整体重跑。
11. Provider 必须来自本地真实数据，推荐采用“硬过滤 + 软排序”。
12. Evaluation Set 在业务模块实现前确定首版 schema 和标注规范，随后逐模块评测。

## 3. 总体架构

```text
React Web Client
  └── AG-UI Client
        ↕ SSE / bidirectional events
Spring Boot API / AG-UI Adapter
  ├── Conversation Session
  ├── Authentication Gate
  ├── AgentScope Runtime
  ├── OpenAI Model Adapter
  ├── NavigationContext Store
  └── Logging / Trace / Metrics
        ↓
┌────────────── Single Agent Layer ─────────────────┐
│ MedicalNavigationAgent                            │
│   ├── conversational auth/profile behavior        │
│   ├── SymptomExtractionSkill                      │
│   ├── DiseaseInferenceSkill                       │
│   ├── DepartmentMappingSkill                      │
│   └── RecommendationExplanationSkill              │
│                                                   │
│ Auxiliary model calls (not agents):               │
│   ├── Symptom Summary Model                       │
│   └── Semantic Verification Model (optional)      │
└───────────────────────────────────────────────────┘
        ↕ Hooks / typed contracts
┌──────────────── Deterministic Services ───────────┐
│ AuthService / ProfileService / DistanceService    │
│ HospitalService / DoctorService / RankingService  │
│ ConfidenceService / ValidationService             │
└───────────────────────────────────────────────────┘
        ↕
MyBatis → PostgreSQL + pgvector

Independent parallel path after authentication:
Browser Geolocation → Google Geocoding → Hospital DB/Google source
→ same city/state filter → distance calculation → distance ascending

Scheduled Provider ingestion:
Healthgrades Browser Automation → staging → validation/dedup → local DB
```

## 4. 技术栈

| 层次 | 选型 |
|---|---|
| Backend | Java 17+、Spring Boot、WebFlux/SSE |
| Agent | AgentScope Java `ReActAgent` / multi-agent routing |
| Model | GPT 系列模型，具体 snapshot 通过配置固定 |
| Model SDK | OpenAI 官方 Java SDK，优先 Responses API |
| Structured Output | JSON Schema Structured Outputs |
| Agent Tools | AgentScope `Toolkit` + `@Tool` |
| Frontend | React + TypeScript + AG-UI client |
| Optional UI | A2UI，用于后续生成式推荐卡片，不作为 MVP 主协议 |
| Persistence | PostgreSQL + pgvector |
| ORM/Data Access | MyBatis / MyBatis-Spring-Boot |
| Cache/Session | Redis（本地验证可先使用内存或 PostgreSQL） |
| Maps | Google Maps Geolocation/Geocoding/Places 或受控适配器 |
| Automation | Playwright for Java，定时采集适配器 |
| Scheduling | Spring Scheduling + ShedLock/数据库锁 |
| Observability | OpenTelemetry + Micrometer |
| Test | JUnit 5、Testcontainers、MockWebServer、Playwright fixtures |

OpenAI Responses API 支持 function tools 和基于 JSON Schema 的 Structured Outputs，适合为 AgentScope 自定义 OpenAI Model Adapter 提供工具调用和强类型输出。[OpenAI Java Responses API](https://developers.openai.com/api/reference/java/resources/beta/subresources/responses)

AG-UI 是 Agent 与用户界面之间的事件协议，覆盖流式文本、Tool Call、状态快照/增量和中断；A2UI 主要用于 Agent 生成 UI 组件。MVP 选择 AG-UI 作为通信层。[AG-UI Overview](https://docs.ag-ui.com/)

## 5. 单 Agent 与能力边界

认证、画像、推荐和验证不需要各自的自主规划、人格或长期会话，把它们拆成 Agent 会增加上下文复制、状态同步、Token、延迟和评测路径。因此第三版采用一个 Agent 维持连续用户体验；其余模块依据是否需要模型推理，分别实现为 Skill、Tool、Service、Hook 或一次性辅助模型调用。

### 5.1 MedicalNavigationAgent

系统只创建一个面向用户的 AgentScope Java `ReActAgent`：`MedicalNavigationAgent`。它贯穿同一会话，负责：

- 识别当前对话处于匿名、认证、画像补全或医疗导航语境；
- 规划对话式登录/注册步骤，但不自行验证密码；
- 用尽量少的问题补全用户画像；
- 接收症状、决定是否追问；
- 选择并调用 Skills 和 Tools；
- 在信息与证据充分时生成候选回答。

后端根据 `ConversationSession.authStatus` 动态启用 Tool Group：未认证时只暴露认证工具；认证后再开放画像、症状、Memory 和 Provider 工具。身份门禁由后端决定，不能让 Agent 从聊天文字猜测或绕过。

### 5.2 对话式认证能力

认证不是独立 Agent。`MedicalNavigationAgent` 根据身份上下文规划自然语言交互，调用 Auth Tools 查询用户名、请求安全密码控件和读取认证结果。用户名唯一性、密码校验、限流和 Session 建立由 `AuthService` 确定性完成。

### 5.3 画像采集能力

画像采集不是独立 Agent。注册成功后，同一个 `MedicalNavigationAgent` 第一轮将尚缺字段合并为一个自然问题，后续只追问仍缺字段。累计最多 3 轮，之后调用 `completeProfileCollection`，未知字段保留 `NULL`，不能推断填写。

MVP 必要字段：

- `displayName`；
- `sex`：`FEMALE | MALE | INTERSEX | OTHER | PREFER_NOT_TO_SAY | UNKNOWN`；
- `dateOfBirth`。

`username` 是账户唯一标识，`displayName` 可以重复。年龄由出生日期实时计算，不单独持久化。

### 5.4 DiseaseInferenceSkill

`DiseaseInferenceSkill` 是核心 Agent 可调用、可孤立评测的模型能力，不创建独立 Agent。它将结构化症状与知识证据映射为最多 3 个疾病假设：

- `diseaseName/code`；
- `severity`；
- `estimatedProbability`；
- `supportingFacts`；
- `contradictingFacts`；
- `recommendedDepartments`；
- `evidenceRefs`。

概率是模型在当前候选集内的技术性估计，不应假装为经临床校准的真实发病概率。三个候选可归一化为总和 1.0 用于排序，但 UI 必须称为“模型估计可能性”。

### 5.5 推荐 Service 与解释 Skill

疾病候选与 Location Pipeline 的汇合、医院范围过滤、科室匹配、医生硬过滤、软排序和最多 27 个组合均由确定性 `RecommendationService` 完成。核心 Agent 再调用 `RecommendationExplanationSkill`，把已有 Evidence 转换为“医生 + 推荐理由”，不能修改排序或创造事实。

### 5.6 Verification Service 与辅助模型

最终验证不是独立 Agent。`VerificationService` 固定读取候选结果和 Evidence，执行 schema、硬规则、加权分数和 retry budget 判断。硬规则难以覆盖的语义一致性可由一次独立 GPT 调用补充，但该调用没有自主 Tool、Memory、人格或循环，因此属于辅助模型而不是 Agent。最终 pass/fail 仍由确定性 Gate 决定。

### 5.7 Symptom Summary 辅助模型

症状摘要也不是 Agent。`SymptomMemorySkill` 内部使用与核心模型分离的一次模型调用，将本次症状会话总结为短、可检索、去冗余的摘要并写入向量数据库。默认在主响应之后异步执行，失败不影响本轮结果。

## 6. 对话式身份门禁

### 6.1 登录/注册链路

```text
User: "我是 alice"
  ↓
MedicalNavigationAgent → lookupUsername("alice")
  ├── FOUND
  │     → AG-UI AUTH_SECRET_REQUEST
  │     → frontend renders temporary password input
  │     → POST /api/auth/conversation/login (not an LLM message)
  │     → authenticate(username, password)
  │           ├── success → AUTHENTICATED
  │           └── failure → generic retry response
  └── NOT_FOUND
        → ask whether to register
        → AG-UI AUTH_SECRET_REQUEST for new password
        → POST /api/auth/conversation/register
        → register(username, password)
        → PROFILE_COLLECTION
```

“通过对话完成”表示 Agent 控制流程和 UI 时机，不表示把密码发给模型。AG-UI 自定义事件请求前端渲染一次性密码控件，提交直接进入 AuthController；事件和响应只包含 `secretRequestId` 与成功/失败状态。

### 6.2 认证技术规则

- `username` 规范化规则必须唯一且版本固定；建议 MVP 区分大小写前先明确，默认采用 lowercase canonical username。
- 数据库对 `canonical_username` 建唯一索引；应用层预查不能替代数据库唯一约束。
- 密码只保存 Argon2id 或 BCrypt hash；MVP 使用 Spring Security `PasswordEncoder`。
- 认证失败统一返回相同文案，避免额外泄露用户名是否存在；“找不到则引导注册”只在交互确认后展示。
- 登录尝试限流；成功后轮换 Session ID 并签发 HttpOnly/SameSite Cookie 或短期 Token。
- 未认证 Session 的 Toolkit 只启用 `lookupUsername`、`requestSecretInput`、`getAuthResult`。
- Agent 不直接调用带明文密码参数的 Tool。

### 6.3 MyBatis 表与 Mapper

```sql
create table user_account (
  id                  bigint generated always as identity primary key,
  username            varchar(64) not null,
  canonical_username  varchar(64) not null unique,
  password_hash       varchar(255) not null,
  account_status      varchar(20) not null default 'ACTIVE',
  created_at          timestamptz not null,
  updated_at          timestamptz not null
);

create table user_profile (
  user_id              bigint primary key references user_account(id),
  display_name         varchar(128),
  sex                  varchar(24),
  date_of_birth        date,
  collection_rounds    smallint not null default 0,
  collection_completed boolean not null default false,
  updated_at           timestamptz not null
);
```

核心 Mapper：

- `UserAccountMapper.selectByCanonicalUsername`；
- `UserAccountMapper.insertAccount`；
- `UserAccountMapper.updatePasswordHash`；
- `UserProfileMapper.selectByUserId`；
- `UserProfileMapper.upsertMissingFields`；
- `UserProfileMapper.completeCollection`。

注册使用事务，并捕获唯一索引冲突返回 `USERNAME_TAKEN`。禁止把 SQL Mapper 直接暴露为 Agent Tool；Tool 只能调用 Service。

## 7. 画像采集最少问题策略

```text
round 1: "为了建立你的基本资料，请告诉我姓名、出生日期和性别；不想回答的项目可以跳过。"
  ↓ parse structured answer
knownFields = answered ∪ existingProfile
missingFields = requiredFields - knownFields - explicitlySkippedFields
  ↓
round 2/3: only ask missingFields in one compact question
  ↓
complete or rounds == 3 → persist known values; remaining = NULL
```

ProfileCompletenessHook 在核心 Agent 的每次画像采集输出后校验：

- 不重复询问已知或明确跳过字段；
- 每轮只生成一个合并问题；
- `collectionRounds <= 3`；
- 日期合法且不在未来；
- 不从姓名、文字风格等推断性别或出生日期；
- 第三轮后必须降级完成，不能阻塞医疗导航。

## 8. 并行工作链路

### 8.1 总链路

```text
Authenticated user submits symptom description
       ├──────────────────────────────────────────┐
       │                                          │
       ▼                                          ▼
Location Future（no model）              Medical Agent Future
browser geolocation                     symptom extraction
→ Google reverse geocode                → optional agent question
→ city/state hospital query             → disease hypotheses × 3
→ distance calculation                  → severity + probability
→ distance-sorted hospital pool         → departments + evidence
       │                                          │
       └──────────────────┬───────────────────────┘
                          ▼
                Recommendation Join
                          ↓
             radius filter by severity
                          ↓
            hospitals × doctors ranking
                          ↓
             up to 27 candidate results
                          ↓
           weighted gates + local repair ≤ 3
                          ↓
                  final AG-UI stream
```

### 8.2 并发实现

使用 `CompletableFuture`、Project Reactor `Mono.zip` 或受控 TaskExecutor 启动两个 Future。Location Future 的触发条件是认证完成并获得浏览器位置授权；它不等待模型，也不把 Google 原始响应放入 LLM 上下文。

- Location 失败：Agent 继续疾病链路并询问 ZIP/city，或返回不含 Provider 的疾病/科室结果。
- Medical 推理需要追问：Location Future 可继续运行并缓存 Session-scoped 结果。
- 两边都成功：在 `RecommendationJoinService` 汇合。
- 每个 Future 都有独立 timeout、cancellation 和 trace span。

### 8.3 Location Pipeline

1. 前端使用浏览器 Geolocation 获取经纬度，需要用户授权。
2. Google Geocoding 将经纬度解析为 normalized address、city、state、ZIP。
3. 查询同 city/state 医院。优先查询本地 Hospital DB；数据不足时通过 Google Places Adapter 补充并缓存。
4. 对同 city/state 结果使用 Haversine 计算直线距离，统一存储 miles。
5. 距离升序形成基础 Hospital Pool。

“登录时获取位置”应异步发起授权，拒绝授权不导致登录失败。

## 9. 症状、疾病与严重程度

### 9.1 精简症状结构

```yaml
SymptomCase:
  chiefComplaint: string
  symptoms: list<string>
  onset: nullable<string>
  severityText: nullable<string>
  relevantFacts: list<string>
  missingInformation: list<string>
  sourceMessageIds: list<string>
```

核心模型根据 system prompt 决定：

- 当前信息不足时返回一个高价值问题；
- 信息足够时调用疾病推理 Skill；
- 不为了填满 schema 机械追问；
- 不能把 Profile 中的未知字段作为疾病事实。

ClarificationHook 只校验 Agent 已经做出的“ASK”或“ANSWER”决策：问题是否重复、是否可回答、是否与当前任务相关、是否超过会话问题预算；它不替 Agent 决定何时提问。

### 9.2 疾病输出 schema

```yaml
DiseaseHypothesis:
  hypothesisId: string
  diseaseName: string
  severity: SEVERE | MODERATE | MILD
  estimatedProbability: decimal[0,1]
  supportingFacts: list<EvidenceFact>
  contradictingFacts: list<EvidenceFact>
  departments: list<string>
  knowledgeEvidenceIds: list<string>
  inferenceConfidence: decimal[0,1]
```

门禁要求：最多 3 个；概率合法；疾病名称非空；每个假设至少有一个来自用户输入或知识检索的 supporting fact；科室必须存在于允许词表。

## 10. 医院范围规则

按每个疾病假设的严重程度分别过滤：

| 严重程度 | 地理范围 | 排序 |
|---|---|---|
| SEVERE | 不做半径过滤，在当前 city 范围内 | 能力/科室硬过滤后，距离作为参考 |
| MODERATE | 用户 5 miles 内 | 先满足能力，后按距离与数据质量排序 |
| MILD | 用户 10 miles 内 | 先满足能力，后按距离与数据质量排序 |

若半径内不足 3 家医院：不自动扩大范围，返回现有结果并标注数量不足。后续可由产品策略决定是否询问用户扩大范围。

每个疾病最多选 3 家医院。医院必须提供目标科室或具备相应服务证据；仅仅“距离近”不能进入结果。

## 11. 本地 Provider 数据链路

### 11.1 Healthgrades 定时采集

```text
Spring Scheduler
  → acquire distributed lock
  → Playwright browser worker
  → navigate/search/paginate
  → selector-based extraction
  → raw snapshot + source URL + fetchedAt
  → staging_provider tables
  → schema/quality validation
  → normalize/deduplicate/affiliation match
  → publish versioned provider snapshot
  → local doctor database
```

工程原则：

- 浏览器自动化运行在独立 Worker，不能与用户请求线程共用浏览器进程。
- 优先使用稳定 DOM selector；鼠标键盘坐标操作只作为受控 fallback，因为分辨率和页面变化会使其脆弱。
- 每次采集保存 source URL、采集时间、解析器版本和原始快照哈希。
- 新数据先进入 staging；通过数量、字段完整率、重复率和异常变化门禁后再发布。
- 使用 `(source, externalId)` 唯一键实现幂等 upsert。
- 不用在线 Healthgrades 请求阻塞用户链路；用户查询只访问本地数据库。
- 采集失败继续使用最近一次成功 snapshot，并标记 freshness。

### 11.2 核心表

- `hospital`：名称、地址、city/state、经纬度、服务/科室、source、freshness；
- `doctor`：姓名、专业、rating、rating_count、source、freshness；
- `doctor_hospital_affiliation`：医生—医院关系及证据；
- `provider_source_snapshot`：采集批次、版本、状态、统计；
- `provider_raw_record`：原始记录引用和解析结果；
- `provider_ingestion_error`：失败页面和错误分类。

没有已验证 affiliation 的医生不能挂到某家医院形成组合推荐。

## 12. 医生硬过滤与软排序

### 12.1 硬过滤

- 医生记录有效且属于当前已发布 snapshot；
- 专业与疾病目标科室匹配；
- 医生与候选医院存在 affiliation 证据；
- rating 非非法值；
- 地址/城市关系可验证；
- 数据 freshness 未超过技术验证阈值。

### 12.2 软排序

```text
doctorScore = 0.45 × normalizedRating
            + 0.20 × ratingReliability
            + 0.20 × specialtyMatch
            + 0.10 × dataFreshness
            + 0.05 × distanceScore
```

`ratingReliability` 根据评价数量对小样本高分进行收缩，避免仅有一条评价的 5.0 分永远排第一。最终主要按 doctorScore 降序；距离同时展示为参考，不取代专业匹配与评分。

医生输出格式：

```yaml
doctor:
  doctorId: string
  name: string
  specialty: string
  rating: nullable<decimal>
  ratingCount: nullable<integer>
  hospitalAffiliation: string
  distanceMiles: decimal
recommendationReason:
  - specialty match fact
  - rating/reliability fact
  - affiliation/location fact
evidenceIds: list<string>
```

## 13. 3×3×3 Recommendation Model

```yaml
NavigationRecommendation:
  recommendationId: string
  disease: DiseaseHypothesisRef
  hospital: HospitalRecommendation
  department: DepartmentRecommendation
  doctor: DoctorRecommendation
  supportingFacts: list<EvidenceFact>
  recommendationReason: list<string>
  confidence:
    score: decimal[0,1]
    level: HIGH | MEDIUM | LOW
    components: map<string,decimal>
  limitations: list<string>
```

生成顺序：疾病候选 top 3 → 每个疾病医院 top 3 → 每家医院医生 top 3。排序和笛卡尔组合由确定性 Service 完成；LLM 只生成基于 Evidence 的解释。

## 14. NavigationContext 与 Memory

### 14.1 精简 NavigationContext

```yaml
NavigationContext:
  sessionId: string
  userId: nullable<long>
  authStatus: ANONYMOUS | USERNAME_IDENTIFIED | SECRET_PENDING | AUTHENTICATED
  profileMissingFields: list<string>
  location: nullable<LocationSummary>
  symptomCase: nullable<SymptomCase>
  diseaseHypotheses: list<DiseaseHypothesis>
  hospitalPoolRef: nullable<string>
  recommendations: list<NavigationRecommendation>
  validationBudgetRemaining: integer
  evidenceIndex: map<string,EvidenceRef>
```

该 Context 只保存本轮编排所需引用和摘要，不复制整份 Provider/Google 响应。

### 14.2 多层 Memory

| 层 | 内容 | 重要等级 | 存储 |
|---|---|---|---|
| User Profile | 姓名、性别、出生日期、账户关联 | HIGH | PostgreSQL 关系表，实时持久化 |
| User Preference | 距离、语言、医生/医院偏好 | HIGH/MEDIUM | PostgreSQL，带来源和更新时间 |
| Symptom Summary | 症状会话摘要、时间、embedding | MEDIUM | PostgreSQL + pgvector |
| Session Working Context | 当前链路、候选和 Evidence 引用 | LOW/EPHEMERAL | AgentState/Redis，TTL |

重要等级用于检索优先级、更新策略和冲突处理，不等于让 Agent 无条件写入。用户明确更正高等级 Profile 时，新值覆盖旧值并记录更新时间；偏好可以根据重复行为提出更新建议，但技术验证阶段不允许静默修改高等级信息。

### 14.3 SymptomMemorySkill

输入：本轮用户症状消息、最终结构化 `SymptomCase`、疾病假设摘要和时间。

流程：

1. 独立 Summary Model 生成受限 schema；
2. SummaryValidationHook 检查是否添加原对话不存在的症状；
3. Embedding Service 生成向量；
4. `SymptomMemoryMapper.insert` 写入摘要、结构化 tags、embedding 和来源 Session；
5. 后续检索按用户隔离，并同时使用向量相似度、时间衰减和重要等级。

失败时记录指标并跳过，不阻塞最终推荐。

## 15. Skill 设计

Skill 是可独立运行和评测的业务模块，输入输出必须结构化：

| Skill | 负责 | 主要输入 | 主要输出 |
|---|---|---|---|
| ConversationalAuthSkill | 登录/注册对话规划 | authStatus、用户消息 | next auth intent |
| ProfileCollectionSkill | 最少轮次画像补全 | existing profile、回答 | profile patch / question |
| SymptomExtractionSkill | 症状结构化 | user messages | `SymptomCase` |
| DiseaseInferenceSkill | 3 个疾病假设 | symptom、retrieved knowledge | hypotheses |
| DepartmentMappingSkill | 疾病到科室 | hypothesis | departments |
| RecommendationExplanationSkill | 证据化解释 | ranked combinations | reasons |
| SymptomMemorySkill | 症状摘要入向量库 | completed run | memory record |
| VerificationSkill | 语义一致性验证 | candidate + evidence | validation findings |

每个 Skill 可用 fixture 独立调用，不要求启动完整 Agent 会话，这也是模块化评测的基础。Skill 不是 Agent：它没有独立身份、会话、长期 Memory 或自主循环。

## 16. Tool 设计

### 16.1 Agent 可调用 Tool

| Tool | 使用场景 | 作用 |
|---|---|---|
| `lookupUsername(username)` | 未认证 Tool Group | 查询规范化用户名是否存在 |
| `requestSecretInput(purpose)` | 未认证 Tool Group | 发出 AG-UI 安全输入事件 |
| `getAuthenticationResult(requestId)` | 未认证 Tool Group | 读取认证结果，不含密码 |
| `getUserProfile()` | 认证后 Tool Group | 读取当前用户画像 |
| `patchUserProfile(fields)` | 画像采集 | 更新已明确回答的画像字段 |
| `completeProfileCollection()` | 画像采集 | 第三轮后完成或降级 |
| `updateSymptomCase(patch)` | 医疗导航 | Schema 校验后更新症状上下文 |
| `retrieveDiseaseKnowledge(query)` | Disease Skill | 检索本地疾病/科室知识 |
| `getLocationResult()` | 医疗导航 | 获取并行位置任务结果 |
| `queryHospitalPool(criteria)` | 推荐构建 | 查本地医院数据 |
| `queryDoctors(hospitalId, department)` | 推荐构建 | 查本地医生和 affiliation |
| `getMemory(query)` | 医疗导航 | 按用户和重要等级检索 Memory |

### 16.2 不暴露给 Agent 的确定性函数

- `hashPassword/verifyPassword`；
- `calculateDistance`；
- `filterHospitalsBySeverityRadius`；
- `rankHospitals/rankDoctors`；
- `calculateConfidence`；
- `validateJsonSchema`；
- `redactLogs`；
- `publishProviderSnapshot`；
- `authorizeToolCall`。

这些函数由 Service、Hook 或 Gate 固定执行，模型不能选择跳过。

## 17. 门禁系统

### 17.1 门禁拓扑

```text
User Event
  → G0 Session/Auth Gate
  → Auth/Profile Agent
  → G1 Identity/Profile Schema Gate
  → parallel(Location Pipeline, Medical Agent)
       ├── G2 Location JSON/Source Gate
       ├── G3 Transcript/Symptom Readability Gate
       └── G4 Disease Hypothesis Gate
  → Recommendation Join
  → G5 Hospital/Doctor Hard Filter Gate
  → G6 Evidence/Explanation Gate
  → Final Verification Gate
       ├── PASS
       ├── repair low-score items only, budget ≤ 3
       └── DEGRADED RESPONSE
```

### 17.2 Gate 定义

| Gate | 关键检查 | 失败处理 |
|---|---|---|
| G0 Auth | Session、用户身份、Tool 权限 | 返回登录/注册对话 |
| G1 Profile | JSON、日期、已知字段、3 轮限制 | 局部重提或 NULL 降级 |
| G2 Location | 经纬度范围、city/state、来源、freshness | ZIP/city fallback 或无 Provider 输出 |
| G3 Input | 文本可读、非空、Symptom JSON 合法 | 请求重述；不进入疾病 Skill |
| G4 Disease | 数量≤3、概率、严重度、facts、科室词表 | 重跑对应疾病条目 |
| G5 Provider | 实体存在、范围、科室、affiliation、评分 | 删除或替换对应组合 |
| G6 Evidence | 所有 claim 有 Evidence、理由与事实一致 | 只重写对应理由 |
| Final | 完整性、交叉一致性、置信度、无越界内容 | 局部修复或降级 |

### 17.3 ClarificationHook

ClarificationHook 不是问题生成器。它在核心模型提出问题或准备返回答案时校验：

- 问题是否与缺失信息有关；
- 是否重复询问已知信息；
- 画像问题是否超过 3 轮；
- 用户明确跳过的画像字段是否被再次询问；
- ANSWER 决策是否至少具备症状描述；
- 问题过多时压缩为一个合并问题。

### 17.4 局部 Verification Loop

```text
validationBudget = 3
candidateItems = up to 27

while validationBudget > 0:
    findings = validate(candidateItems)
    if no blocking findings: PASS
    targets = select lowest-confidence repairable items
    repair only targets by failure type
    validationBudget--

remove unresolved items
return remaining verified items + limitations
```

局部修复类型：

- Disease 低分：只重跑该疾病假设；其下医院/医生组合随后失效并重建。
- Hospital 低分：只替换该疾病下对应医院分支。
- Doctor 低分：只重查/重排该医院下医生。
- Explanation 低分：保留实体，只重写对应理由。
- Location 失败：不重复疾病推理。

Budget 用尽后删除阻断项；如果没有任何合格推荐，返回疾病/科室级结果或明确的无法推荐响应，禁止返回未验证条目。

## 18. 置信度设计

### 18.1 分层计算

```text
diseaseConfidence =
    0.35 × symptomEvidenceCoverage
  + 0.25 × knowledgeGrounding
  + 0.20 × structuredOutputValidity
  + 0.20 × inferenceConsistency

hospitalConfidence =
    0.35 × departmentCapability
  + 0.25 × locationValidity
  + 0.20 × distanceValidity
  + 0.20 × dataFreshness

doctorConfidence =
    0.30 × specialtyMatch
  + 0.25 × affiliationEvidence
  + 0.20 × ratingReliability
  + 0.15 × dataFreshness
  + 0.10 × distanceReference

explanationConfidence =
    0.50 × claimEvidenceCoverage
  + 0.30 × factualConsistency
  + 0.20 × completeness

finalConfidence =
    0.35 × diseaseConfidence
  + 0.25 × hospitalConfidence
  + 0.25 × doctorConfidence
  + 0.15 × explanationConfidence
```

所有 component 必须能由规则、schema 或 Evidence 计算；LLM 自报 confidence 只能作为 `inferenceConsistency` 的输入之一，不能直接成为最终分数。

### 18.2 阈值

| 分数 | 处理 |
|---|---|
| `>= 0.80` | 通过，显示 HIGH |
| `0.65–0.79` | 可显示 MEDIUM；若存在可修复低分 component，优先局部修复 |
| `< 0.65` | 阻断该条目并进入局部修复 |
| 任一硬门禁失败 | 与总分无关，直接阻断 |

UI 可以直接显示百分制“技术置信度”，同时说明它代表证据完整性和链路一致性，不等于疾病真实概率。也可在简洁模式中融入“证据较充分/有限”的话术。

## 19. 前端设计：AG-UI

### 19.1 页面结构

- 单一对话主界面，无固定登录/注册页；
- 顶部显示账户与位置权限状态；
- 中部为流式对话和进度事件；
- 安全密码输入以临时受控组件显示；
- 推荐结果按疾病分组，每个疾病下显示医院卡片和医生列表；
- 展开项展示支持事实、推荐理由、距离、评分和置信度组成；
- 用户可以更正画像、位置或症状并触发局部更新。

### 19.2 AG-UI 事件

标准事件用于 `RUN_STARTED/FINISHED`、流式文本、Tool Call 和 state delta；自定义事件：

- `AUTH_SECRET_REQUEST`：请求一次性密码输入；
- `AUTH_STATUS_CHANGED`；
- `PROFILE_MISSING_FIELDS`；
- `LOCATION_PERMISSION_REQUEST`；
- `PIPELINE_PROGRESS`；
- `RECOMMENDATION_SNAPSHOT`；
- `VALIDATION_FINDING`；
- `LOCAL_REPAIR_PROGRESS`。

前端只展示推理阶段摘要和 Tool 状态，不展示模型隐藏 chain-of-thought。

## 20. System Prompt 设计

核心 prompt 至少包含：

```text
You are a medical navigation hypothesis and provider recommendation agent.
You do not invent users, diseases evidence, hospitals, departments, doctors,
ratings, affiliations, addresses, or distances.

Decide dynamically whether to ask one concise, high-value question or proceed.
Do not ask for fields already available in the supplied context.
When sufficient, use tools and structured skills to produce up to three disease
hypotheses with evidence, severity, and estimated possibility.

Provider facts must come from tools. Explanations may only use supplied evidence.
Return fewer results when verified data is insufficient. Never fill quotas by guessing.
Passwords and secret values are never valid conversational inputs; request the secure
frontend secret control instead.
```

Prompt 版本进入 Evaluation Run 和日志。Prompt 只约束模型行为，不能替代 Auth、Schema、Provider 和 Final Verification Gate。

## 21. Evaluation：评测前置

### 21.1 实现顺序

在业务模块编码前先确定：

1. Evaluation Case schema；
2. 标签词表与标注指南；
3. 疾病严重程度、科室、医院和医生的可接受集合；
4. Gate 期望结果；
5. 失败分类；
6. 基线模型、prompt、Provider snapshot 和随机种子记录方式。

随后每完成一个 Skill/Service 就进行孤立评测，最后才做端到端评测。

### 21.2 数据集分层

| Set | 用途 |
|---|---|
| Auth Set | 用户名存在/不存在、重复注册、错误密码、限流、密码泄漏 |
| Profile Set | 一次完整回答、部分回答、拒答、格式错误、3 轮降级 |
| Input Set | 可读性、空文本、噪声、拼写、多轮症状 |
| Disease Set | top-3、severity、概率、facts、科室映射 |
| Location Set | 权限拒绝、坐标异常、Google timeout、city/state 边界 |
| Provider Set | 5/10 miles、全市、affiliation、评分、陈旧和空结果 |
| Memory Set | summary 忠实度、向量检索、用户隔离、Profile 更新 |
| Gate Set | 每个错误能被正确节点阻断并局部修复 |
| E2E Set | 从匿名会话到 27 条以内最终结果的完整链路 |

### 21.3 模块指标

- Auth：成功率、错误密码拒绝率、用户名并发唯一性、secret leakage = 0；
- Profile：字段抽取 F1、重复问题率、平均轮次、3 轮完成/降级率；
- Symptoms：JSON validity、事实忠实度、可读性分类；
- Disease：top-k recall、severity accuracy、evidence precision、probability calibration（若有足够标签）；
- Location：地址解析率、距离误差、同 city/state 精度、p95 latency；
- Provider：实体 precision、科室匹配、affiliation precision、radius compliance；
- Ranking：nDCG@3、rating reliability、稳定 tie-break；
- Explanation：unsupported claim rate、reason completeness；
- Verification：错误检出 recall、误杀率、平均 repair 次数、局部重跑节省量；
- Memory：summary hallucination rate、retrieval precision、cross-user leakage = 0；
- E2E：有效结果数、最终门禁通过率、延迟、Token、API 成本。

### 21.4 关键技术门禁

- 密码进入 LLM、Memory、日志或 Trace：0 次；
- 未认证调用医疗/用户数据 Tool：0 次；
- 虚构医院/医生或 affiliation：0 条；
- 半径硬规则违反：0 条；
- 无 Evidence 的事实 claim：0 条；
- Provider 硬过滤失败但仍显示：0 条；
- Validation Loop 超过 3 轮：0 次；
- 第三轮画像后仍阻塞主流程：0 次；
- 跨用户 Memory/Session 泄漏：0 次。

### 21.5 Evaluation Run

```yaml
EvaluationRun:
  runId: string
  datasetVersion: string
  applicationCommit: string
  agentScopeVersion: string
  openAiSdkVersion: string
  modelName: string
  modelSnapshot: string
  promptVersions: map<string,string>
  skillVersions: map<string,string>
  gatePolicyVersion: string
  providerSnapshot: string
  randomSeeds: list<long>
  metrics: map<string,number>
  failedCases: list<string>
  artifactRefs: list<string>
```

## 22. 日志与埋点

### 22.1 关键节点事件

- `session_started`、`username_lookup`、`auth_succeeded/failed`；
- `profile_round_started/completed/degraded`；
- `location_started/resolved/failed`；
- `symptom_extraction_completed`；
- `disease_hypotheses_generated`；
- `recommendation_join_started`；
- `provider_filter_completed`、`doctor_rank_completed`；
- `gate_passed/failed`；
- `local_repair_started/completed`；
- `response_emitted`；
- `symptom_memory_written/failed`；
- `provider_ingestion_started/published/failed`。

### 22.2 公共字段

`traceId/runId/sessionId/userIdHash/agentName/skillName/toolName/gateName/model/promptVersion/providerSnapshot/latencyMs/tokenUsage/status/errorCode/retryCount`。

禁止记录密码、密码 hash、完整原始症状、精确地址、完整模型隐藏推理。对用户 ID 使用内部 ID 或不可逆 hash，日志 payload 使用字段白名单。

## 23. API 与 AG-UI Endpoint

```text
POST   /api/conversations
POST   /api/conversations/{id}/runs
GET    /api/conversations/{id}/events          # SSE / AG-UI
POST   /api/auth/conversation/login            # secure form, bypasses LLM
POST   /api/auth/conversation/register         # secure form, bypasses LLM
POST   /api/location/consent
GET    /api/navigation/runs/{runId}
GET    /api/users/me/profile
PATCH  /api/users/me/profile
GET    /api/users/me/preferences
PATCH  /api/users/me/preferences
DELETE /api/users/me/memory/{memoryId}
```

登录/注册 endpoint 使用一次性 `secretRequestId` 绑定 Conversation 和 username intent，防止前端把密码作为普通 chat message 提交。

## 24. 推荐代码结构

```text
com.example.mednav
├── api
│   ├── conversation
│   ├── auth
│   ├── location
│   └── navigation
├── agui
│   ├── event
│   ├── adapter
│   └── stream
├── agent
│   ├── MedicalNavigationAgent
│   ├── MedicalNavigationAgentFactory
│   ├── ToolGroupPolicy
│   └── session
├── model
│   ├── openai
│   ├── prompt
│   └── schema
├── skill
├── tool
├── hook
├── gate
├── context
├── auth
├── profile
├── location
├── disease
├── provider
│   ├── hospital
│   ├── doctor
│   ├── ranking
│   └── ingestion
├── memory
├── mapper
├── persistence
├── evaluation
├── observability
└── config
```

## 25. 实施阶段

### Phase 0：Evaluation 与契约先行

- 冻结 Evaluation Case schema、标注指南、严重程度和科室词表。
- 定义所有 Skill/Tool/Gate schema 和失败码。
- 构造 Auth、Profile、Location、Disease、Provider fixtures。

### Phase 1：身份与前端骨架

- Spring Boot、MyBatis、PostgreSQL migrations。
- React + AG-UI 对话页面和安全密码控件。
- 单一 `MedicalNavigationAgent` 的认证/画像对话、动态 Tool Group、唯一用户名、密码 hash和 3 轮画像。
- Auth/Profile 模块评测与日志。

### Phase 2：数据与并行链路

- Google Location Adapter、距离计算和异步 Future。
- Healthgrades Browser Worker、staging、snapshot、本地医生库。
- 医院/医生硬过滤与软排序。

### Phase 3：Agent 推理链路

- AgentScope Java + OpenAI Model Adapter。
- Symptom、Disease、Department 和 Explanation Skills。
- 核心 system prompt 与 ClarificationHook。
- SymptomMemorySkill + pgvector。

### Phase 4：门禁与端到端

- ConfidenceService、所有 Gate 和局部 Verification Loop。
- 3×3×3 组合、AG-UI 推荐卡片和降级响应。
- 模块评测、E2E、故障注入、性能与成本报告。

## 26. Definition of Done

第三版技术验证完成需满足：

- 用户可在纯对话界面完成注册、登录和画像补全；
- 密码通过独立安全控件提交且从未进入模型链路；
- MyBatis 完成账户、画像、Provider、Memory 和评测记录的持久化；
- Location Pipeline 与 Medical Pipeline 可并行并独立降级；
- 核心 Agent 能决定追问或输出，ClarificationHook 能阻止重复/无效问题；
- 可生成最多 3×3×3 且全部来自真实本地 Provider 数据的推荐；
- 严重程度的全市/5 miles/10 miles 规则正确执行；
- 医生硬过滤、软排序、评分可靠性和距离参考可解释；
- 每条结果包含疾病事实、医生推荐理由、Evidence 和置信度；
- Verification 能按低分条目局部修复，最多 3 轮并可靠降级；
- Symptom Summary 可由独立模型写入并从向量数据库检索；
- 每个 Skill/Tool/Gate 有独立评测，关键技术门禁全部通过；
- 关键链路日志、Trace、Token、时延和 retry 指标可查询。

## 27. 待确认事项

1. 首个 GPT 模型名称与固定 snapshot，以及核心模型和 Summary/Verification 模型是否分级。
2. Google 医院数据是否只做补充，还是医院主数据源。
3. Healthgrades 采集字段、刷新周期和首个城市范围。
4. 疾病知识数据集和科室标准词表。
5. 疾病概率标签如何获得；若无可靠标签，是否仅展示可能性等级而不展示百分比。
6. 中度 5 miles、轻度 10 miles 的范围不足时是否允许用户确认后扩圈。
7. Evaluation Set 的标注人员、样本数量和首批疾病范围。

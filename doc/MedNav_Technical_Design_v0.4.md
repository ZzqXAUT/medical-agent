# Medical Navigation Agent 技术设计 v0.4

> AgentScope Java + OpenAI GPT + Spring Boot + MyBatis + AG-UI

| 项目 | 内容 |
|---|---|
| 状态 | 第四版实现基线 |
| 日期 | 2026-09-22 |
| 继承 | `MedNav_Technical_Design_v0.3.md` 及后续讨论 |
| 场景 | 面向美国应用场景的技术验证 |
| 核心 | 单 Agent、对话式身份门禁、并行定位/初诊、CMS 医院、Google 路线、疾病—医院—医生三级树、关系库长期记忆 + Redis 双缓存、局部验证 |

## 1. V4 最终决策

1. 只使用一个 `MedicalNavigationAgent`；认证、画像、症状理解、追问、初诊和解释均维持在同一对话中。
2. 需要模型推理且需孤立评测的能力封装为 Skill；外部查询封装为 Tool；确定性逻辑放在 Service；不可跳过的门禁放在 Hook/Gate。
3. 不使用固定业务状态机。Agent 由 system prompt 自主决定继续追问还是产生初诊结果。
4. Clarification 不做 Skill；`ClarificationHook` 验证 Agent 的 `ASK_USER | READY | READY_LIMITED | INSUFFICIENT_INFORMATION` 决策。
5. Profile 最多追问 3 轮；症状追问最多 3 轮。达到 3 轮后，`readinessConfidence >= 0.60` 才生成受限低置信度推荐，否则只返回已知症状和缺失信息。
6. 定位流程与“症状 → 疾病假设、严重程度、可能性”并行。
7. Session 内 Run 串行，Session 间并行，单个 Run 内无依赖 Tool Call 可并行。
8. 用户在推荐期间发来的消息永不忽略，每条消息创建新 Run；旧结果通过 revision 失效或局部重算。
9. 医院使用本地 CMS 数据库；Google 负责用户/医院地址解析及多交通方式路线距离、预计时间。
10. 不实现用户偏好及偏好数据库。
11. 医院先按 CMS Overall Rating 降序，再按驾驶路线距离升序；医生不评分、不做质量排序，只按专业匹配和医院任职关系筛选。
12. 最终结果采用疾病—医院—医生三级优先级树：疾病最多 3 个，每个疾病最多 2 家医院，每家医院最多 2 名医生，即最多 12 个医生叶子项。
13. 医院在同一疾病分支内去重；同一家医院可以出现在不同疾病分支，因为疾病关联理由和置信度可能不同。
14. 医院和医生均只使用 CMS Provider Data Catalog 的三个 CSV 数据集；每天凌晨 4:00 刷新本地快照，不再使用 Healthgrades 或浏览器自动采集。
15. Agent Memory 以关系数据库中的 `user_profile`、`session_clinical`、`session_summary` 为长期事实源，以 Redis 用户长期记忆缓存和当前 Session 工作缓存为运行时层；Session 期间不更新长期记忆，结束后统一事务提交。
16. 去掉 Safety Engine 和 Urgency/Care-Level；保留疾病严重程度 `SEVERE | MODERATE | MILD` 作为医院范围规则输入。
17. Evaluation Set 和标注规范优先于完整业务实现；每个 Skill/Tool/Gate 均需孤立评测。
18. 核心模型基于症状自主生成疾病、严重程度、可能性和内部 CMS 医生专业集合；不接独立疾病知识数据源，最终只输出疾病—医院—医生树。
19. CMS CSV Schema 漂移零容忍，任何列名、数量、顺序或类型变化都阻止整批发布；只导入第 13 节定义的字段。
20. Google Route Matrix 覆盖本地查询到的全部医院，不设候选数量上限；路线结果缓存 10 分钟，按最多 100 个路线元素拆分请求批次。
21. CMS 每日导入时区固定为 `America/Los_Angeles`。
22. Semantic Verification Model 启用但按条件调用，不参与每个 Turn。
23. 同一用户可以创建多个 Session；完整用户/Assistant 消息作为产品会话历史增量写入关系数据库，与 Agent Memory 分离。显式结束或空闲满 10 分钟的 Session 永久只读；仅关闭或离开页面、且空闲未满 10 分钟的 ACTIVE Session 可以恢复。

## 2. 产品流程

```text
打开对话
  ↓
对话式登录/注册
  ├─ 新用户 → 最多 3 轮画像采集 → 未知字段保持缺失
  └─ 老用户 → 合并询问“本次症状 / 是否更新资料”
  ↓
用户提交症状
  ├──────────────────────────────────┐
  │                                  │
  ▼                                  ▼
LocationTask                    Current-turn Agent Run
浏览器坐标/手动地址              症状结构化
→ Google Geocoding              → readiness 四态决策
→ CMS 同 city/state 医院         → 最多 3 个疾病假设
→ 全部医院 Route Matrix          → 严重程度/可能性/内部目标专业
  （100 元素/批，缓存 10m）
  │                                  │
  └──────────── Recommendation Join ─┘
                       ↓
             按疾病分别做范围过滤
                       ↓
      每个疾病：医院评分 DESC → 驾驶路线距离 ASC
                       ↓
          每个疾病保留最多 2 家医院
                       ↓
             每家医院筛选医生
                       ↓
       专业匹配 + CMS 任职关系，最多保留 2 名
                       ↓
             门禁与局部修复 ≤ 3
                       ↓
       AG-UI 疾病—医院—医生三级树
```

## 3. 总体架构

```text
React + TypeScript
└── AG-UI Client
      ↕ SSE / bidirectional events
Spring Boot API
├── Conversation/Auth Controller
├── AG-UI Adapter
├── SessionRunScheduler
├── AgentScope Runtime
├── OpenAI Model Adapter
├── NavigationContext Store
└── Trace / Metrics / Logs
      ↓
MedicalNavigationAgent（唯一 Agent）
├── Skills
├── AgentScope Toolkit
└── Hooks
      ↓
Deterministic Services
├── AuthService / ProfileService
├── LocationService / RouteMatrixService
├── CmsHospitalService
├── DoctorService / CmsProviderImportService
├── RecommendationService / HospitalRankingService
├── ConfidenceService / VerificationService
└── MemoryService / SessionMemoryFinalizer
      ↓
MyBatis → PostgreSQL
Redis → 用户长期记忆缓存 / 当前 Session 工作缓存 / AgentState / NavigationContext / Session queue
```

## 4. 技术栈

| 层 | 选型 |
|---|---|
| Backend | Java 17+、Spring Boot、WebFlux |
| Agent | AgentScope Java `ReActAgent` |
| LLM | GPT 模型；名称与 snapshot 配置化 |
| SDK | OpenAI 官方 Java SDK，Responses API |
| Model Contract | Function Calling + JSON Schema Structured Outputs |
| Frontend | React、TypeScript、AG-UI、SSE |
| Data | PostgreSQL（JSONB）、MyBatis |
| Session/Memory cache | AgentScope `AgentState`、Redis String(JSON) 双缓存、NavigationContext |
| Maps | Google Geocoding、Routes Compute Route Matrix |
| Hospital | CMS Hospital 数据定期导入本地库 |
| Provider data | CMS 三个 CSV 的版本化本地快照 |
| Automation | Spring Scheduling、HTTP Client、分布式锁、PostgreSQL COPY |
| Observability | OpenTelemetry、Micrometer |
| Test | JUnit 5、Testcontainers、MockWebServer、固定数据快照 |

三个模型槽位均通过环境变量配置，API 凭证只进入 Secret 管理，不写入代码、文档、日志或 Memory：

```yaml
models:
  primary: ${PRIMARY_MODEL}
  summary: ${SUMMARY_MODEL}
  semantic-verification: ${VERIFICATION_MODEL}
```

OpenAI Responses API 提供 function tools 和 JSON Schema Structured Outputs，用于 AgentScope 自定义 Model Adapter 的强类型调用。[OpenAI Java Responses API](https://developers.openai.com/api/reference/java/resources/beta/subresources/responses)

AG-UI 用作 Agent 与前端之间的流式事件协议；A2UI 当前不进入 MVP。[AG-UI Overview](https://docs.ag-ui.com/)

## 5. 单 Agent、Skill、Tool、Service、Hook

### 5.1 MedicalNavigationAgent

唯一 Agent 负责：

- 认证/注册自然语言流程规划；
- 新用户画像提问、老用户资料更新识别；
- 症状理解以及四态 readiness 决策；
- 选择 Skills 和 Tools；
- 疾病假设、严重程度、可能性及内部目标专业生成；
- 基于已验证数据解释医院和医生推荐。

身份状态由后端 Session 决定。未认证时只启用 Auth Tool Group；认证后才启用 Profile、Clinical、Location、Hospital、Doctor 和 Memory Tool Group。

### 5.2 Skills

| Skill | 输入 | 输出 |
|---|---|---|
| ProfileExtractionSkill | 用户回答、现有画像 | Profile patch |
| SymptomExtractionSkill | 当前消息、Working Context | `SymptomCase` |
| ClinicalReadinessSkill | 症状和已问问题 | `ASK_USER | READY | READY_LIMITED | INSUFFICIENT_INFORMATION` |
| DiseaseInferenceSkill | 症状、当前有效 Memory、CMS 专业词典 | 最多 3 个疾病假设及内部目标专业 |
| RecommendationExplanationSkill | 推荐树、Evidence | 事实化推荐理由 |
| SessionSummarySkill | Session 症状、初诊与上下文 | 一条 Session 摘要 |
| VerificationSkill（条件启用） | 候选解释、Evidence | 语义问题 findings |

`VerificationSkill` 只是一轮辅助模型检查，不是 Agent，不能决定最终 PASS；仅在产生或修改最终推荐且确定性 Gate 发现低置信度或语义一致性风险时调用，不参与登录、画像、普通追问和无风险 Turn。

### 5.3 Agent Tools

| Tool | 作用 |
|---|---|
| `lookupUsername(username)` | 查询规范化用户名 |
| `requestSecretInput(purpose)` | 请求前端安全密码控件 |
| `getAuthenticationResult(requestId)` | 获取认证状态，不返回密码 |
| `getEffectiveMemoryContext()` | 读取并动态合并用户长期记忆缓存与当前 Session 工作缓存 |
| `patchSessionMemory(patch)` | 把本 Session 明确产生的画像、疾病史、核心约束、症状或初诊更新写入 Session 工作缓存 |
| `completeProfileCollection()` | 完成或三轮降级 |
| `getLocationResult()` | 获取并行定位结果 |
| `queryHospitals(criteria)` | 查询本地 CMS 医院 |
| `queryDoctors(hospitalId, targetSpecialties)` | 按模型给出的内部 CMS 专业集合查询本地医生及 affiliation |
| `getHistoricalSessions()` | 从用户长期记忆缓存读取历史 Session 症状、初诊和摘要 |

### 5.4 不暴露为 Tool 的函数

密码散列/校验、距离与路线范围判断、医院排序、医生资格过滤与稳定限额、置信度计算、JSON 校验、日志脱敏、Tool 授权、Snapshot 发布和最终 Verification 均由确定性 Service/Hook 固定执行。

## 6. 对话式身份验证与 MyBatis

### 6.1 登录/注册

```text
用户给出 username
→ lookupUsername
  ├─ 存在 → 请求安全密码输入 → AuthService 登录
  └─ 不存在 → 引导注册 → 请求安全密码输入 → AuthService 注册
```

登录在对话界面完成，但密码不作为聊天消息。Agent 发出 `AUTH_SECRET_REQUEST`，前端渲染临时密码框，密码通过认证 Endpoint 直达 `AuthService`，不进入 LLM、AgentState、Memory、日志或 Trace。

```sql
create table user_account (
  id                  varchar(64) primary key, -- application-generated UUID/ULID
  username            varchar(64) not null,
  canonical_username  varchar(64) not null unique,
  password_hash       varchar(255) not null,
  account_status      varchar(20) not null default 'ACTIVE',
  created_at          timestamptz not null,
  updated_at          timestamptz not null
);
```

MyBatis Mapper 只由 Service 调用，不直接暴露给 Agent。注册使用事务和数据库唯一约束；密码使用 Spring Security `PasswordEncoder`。`user_account` 属于认证事实，注册成功时必须立即持久化，不受“Memory 在 Session 结束时统一提交”规则影响。Memory 表见第 17 节。

### 6.2 新用户画像

画像字段为 `birth_date`、`sex`、`height_cm`、`weight_kg`。首轮用一个问题覆盖尽可能多的缺失字段，后续排除已知或明确跳过字段。最多 3 轮；仍未知的字段保持缺失并结束画像阶段。

`ProfileCompletenessHook` 校验轮数、日期、数值范围、重复问题和非法推断。Session 期间，画像更新只进入 `memory:session:{session_id}`；首次 Session 结束时若存在有效更新才创建长期 `user_profile`，不为新用户预建空画像记录。

### 6.3 老用户入口

登录后从长期数据库加载画像、疾病史、核心约束以及历史 Session，并写入 `memory:user:{user_id}`。核心 prompt 生成一个合并问题：

> 欢迎回来。请描述你这次需要咨询的症状；如果基本资料需要补充或更新，也可以一起告诉我。

用户只说症状则直接进入 Clinical Branch；只更新资料则写入当前 Session 工作缓存后继续询问症状；两者同时出现则在同一 Run 中分别处理。画像不完整不能阻塞症状处理，长期库在 Session 结束前保持不变。

## 7. Session、Run 与并发模型

### 7.1 定义

- Session：完整多轮对话，持有 AgentState 和 NavigationContext。
- Run：一条用户消息触发的一次有限 AgentScope 调用。
- LocationTask：可跨多个 Run 存活的异步定位任务。
- Tool Call：Run 内的原子操作。

需要追问时，本次 Run 返回 `AskUser` 并结束；用户回复后创建新 Run。不存在一个 Future 长时间阻塞等待用户。

### 7.2 并发原则

```text
Session 内 Run：严格串行
Session 间：并行
Run 对 Web 请求：异步
Run 内无依赖 Tool：允许并行
Context commit：串行或乐观锁
```

每个 Session 使用逻辑队列；不同 Session 共享有界执行池。Run 内并行 Tool 返回不可变结果，最后统一提交 Context，避免多个 Tool 同时覆盖同一字段。

可以并行：画像缓存读取、历史 Session 缓存读取和 Location 状态读取；相互独立的 Tool 查询。不能并行：坐标→地址、疾病假设→内部目标专业、医院→医生。

### 7.3 新消息处理

任何新消息都创建新 Run，不能忽略。若旧 Run 尚未进入最终提交，新消息将其标记 `SUPERSEDED` 并尽量取消；旧 Run 即使完成也不得提交过期结果。若旧 Run已完成提交，新 Run 读取新 Context 并执行局部失效。

执行生命周期可使用 `QUEUED | RUNNING | CANCELLING | SUPERSEDED | COMPLETED | FAILED`；它不是医疗业务状态机。

## 8. NavigationContext 与 Revision

```yaml
NavigationContext:
  sessionId: string
  contextVersion: long
  userId: nullable<string>
  authStatus: ANONYMOUS | SECRET_PENDING | AUTHENTICATED
  profileMissingFields: list<string>
  memory:
    userCacheKey: nullable<string>
    sessionCacheKey: string
  location:
    revision: long
    status: NOT_REQUESTED | RUNNING | READY | DENIED | INVALID | FAILED
    result: nullable<LocationResult>
  clinical:
    revision: long
    symptomCase: nullable<SymptomCase>
    questionCount: integer
    hypotheses: list<DiseaseHypothesis>
  hospitalPool:
    basedOnLocationRevision: long
    snapshotRef: nullable<string>
  recommendations:
    basedOnLocationRevision: long
    basedOnClinicalRevision: long
    diseaseBranches: list<DiseaseRecommendationRef>
  validationBudgetRemaining: integer
  evidenceIndex: map<string,EvidenceRef>
```

新症状使 hypotheses、内部目标专业和推荐失效，但保留 Location；新位置使路线和推荐失效，但保留疾病假设；资料更新只影响依赖该字段的临床结果；询问推荐详情不重算上游。

最终提交前，Recommendation 的 location/clinical revision 必须等于当前 Context，否则丢弃旧结果。

## 9. 何时结束症状描述

核心模型输出：

```yaml
ClinicalReadinessDecision:
  decision: ASK_USER | READY | READY_LIMITED | INSUFFICIENT_INFORMATION
  missingInformation: list<MissingInformation>
  proposedQuestion: nullable<string>
  readinessConfidence: decimal
  symptomCase: SymptomCase
```

采用“模型自主判断 + 最小信息门禁”，不要求用户显式确认，也不提供“继续补充/开始推荐”按钮。

`ClarificationHook` 检查：问题是否重复、是否与初诊结果相关、是否一次包含过多问题、是否超过预算；对 READY 检查主诉和最小事实是否存在。症状追问最多 3 轮，执行规则固定为：

```text
questionCount < 3 且信息不足
→ ASK_USER

questionCount = 3 且 readinessConfidence >= 0.60
→ READY_LIMITED
→ 生成受限、低置信度疾病和推荐树
→ 明确展示缺失信息

questionCount = 3 且 readinessConfidence < 0.60
→ INSUFFICIENT_INFORMATION
→ 只返回已知症状和缺失信息
→ 不生成疾病—医院—医生推荐树
```

`readinessConfidence` 表示当前症状信息是否足以支持初诊推理，不是疾病概率。`0.60` 是技术验证初始阈值，必须通过 Evaluation Set 校准并以配置形式版本化。

## 10. 定位与初诊并行

首次有效症状消息触发：

```text
LocationTask                    Current-turn Medical Agent Run
     │                                       │
     │ 可跨 Run 缓存                          │ 可能返回 AskUser 并结束
     │                                       │
     └──────── 当双方 READY 时 Join ──────────┘
```

Location 先完成则缓存；Agent 先完成则等待 Location 结果事件；若 Agent 需要追问，LocationTask 继续。下一 Run 得到 hypotheses 后复用位置结果。

## 11. Location 门禁与 Google 路线

认证成功和位置成功是两个不同条件：拒绝浏览器定位不导致登录失败，但没有可用位置不能推荐附近医院。

```text
浏览器定位授权
  ├─ 同意 → lat/lng → Google reverse geocoding
  └─ 拒绝/失败 → Agent 请求 address、city+state 或 ZIP
                         ↓
                 Google geocoding
                         ↓
                    Location READY
```

若用户仍不提供位置，可返回疾病假设，但不得返回附近医院、路线或医生。

本地 CMS 查询得到同 city/state 的全部医院后，不做候选数量截断，也不做直线距离粗筛。Google `Compute Route Matrix` 对全部医院计算：

- `DRIVE`：路线距离和预计时间；
- `TRANSIT`：路线距离和预计时间，允许无结果；
- `WALK`：路线距离和预计时间，允许无结果。

Google Routes API 支持多目的地路线距离和预计时间。系统必须先按 Facility ID 去重，再按最多 100 个“用户—医院—交通方式”路线元素拆成多个请求批次，直到覆盖本地查到的所有医院；100 是单批大小，不是单个 Run 的总量上限。[Google Routes API](https://developers.google.com/maps/documentation/routes)

```yaml
route:
  result-cache-ttl: 10m
  batch-size: 100
```

路线缓存 Key 至少包含规范化用户位置、Facility ID、交通方式和路线参数版本。缓存 10 分钟后失效；该 TTL 只属于 Google 路线结果，与 Memory 缓存 TTL 无关。单个 Run 可以顺序或受控并行执行多个批次，但不得因批次超过 1 个而截断医院。

```yaml
HospitalRoute:
  hospitalId: string
  drive: { distanceMiles: decimal, durationMinutes: integer }
  transit: nullable<RouteMeasure>
  walk: nullable<RouteMeasure>
  calculatedAt: instant
  status: SUCCESS | NO_ROUTE | FAILED
```

## 12. 初诊结果

```yaml
SymptomCase:
  chiefComplaint: string
  symptoms: list<string>
  onset: nullable<string>
  relevantFacts: list<string>
  missingInformation: list<string>
  sourceMessageIds: list<string>

DiseaseHypothesis:
  hypothesisId: string
  diseaseName: string
  severity: SEVERE | MODERATE | MILD
  estimatedProbability: decimal[0,1]
  supportingUserFacts: list<EvidenceFact>
  contradictingFacts: list<EvidenceFact>
  targetSpecialties: list<string> # internal only; values must exist in active CMS specialty vocabulary
  evidenceIds: list<string>
  confidence: decimal[0,1]

DiseaseHypothesisPublic:
  hypothesisId: string
  diseaseName: string
  severity: SEVERE | MODERATE | MILD
  estimatedProbability: decimal[0,1]
  supportingUserFacts: list<EvidenceFact>
  contradictingFacts: list<EvidenceFact>
  evidenceIds: list<string>
  confidence: decimal[0,1]
```

最多 3 个疾病假设。核心模型直接基于症状和当前有效 Memory 自主判断疾病、严重程度、可能性及内部目标专业，不调用独立疾病知识数据源。概率为模型候选集内估计可能性，不代表经临床校准的患病概率；UI 必须如此表述。每个假设至少引用一条用户实际陈述，模型内部医学知识不能伪装成外部证据。

`targetSpecialties` 只用于连接 DAC 的 `pri_spec/sec_spec_*` 进行医生过滤，不作为独立推荐内容展示。Runtime 将当前 CMS snapshot 中的去重专业词典注入 Structured Output 枚举；`SpecialtyVocabularyHook` 再检查每个值确实存在。非法值只重跑对应疾病的该字段，最多受统一 3 轮局部修复预算约束。

## 13. CMS Provider 本地数据库

医院和医生只使用 CMS Provider Data Catalog 的三个公开 CSV。稳定配置使用 Dataset ID，不把每次发布都会变化的资源 URL 写死：

| 本地逻辑名 | CMS 数据集 | Dataset ID | 作用 |
|---|---|---|---|
| `facility_affiliation` | [Facility Affiliation Data](https://data.cms.gov/provider-data/dataset/27ea-46a8) | `27ea-46a8` | NPI 与医院 Facility ID 的任职关系 |
| `doctor_national` | [National Downloadable File](https://data.cms.gov/provider-data/dataset/mj5m-pzi6) | `mj5m-pzi6` | 医生身份、专业、执业地址等信息 |
| `hospital_general` | [Hospital General Information](https://data.cms.gov/provider-data/dataset/xubh-q36u) | `xubh-q36u` | 医院地址、类型、CMS Overall Rating 和质量指标 |

`NPI` 是医生唯一标识；`Facility ID` 是医院唯一标识。关联路径固定为：

```text
DAC_NationalDownloadableFile.NPI
  = Facility_Affiliation.NPI

Facility_Affiliation.Facility Affiliations Certification Number
  = Hospital_General_Information.Facility ID
```

`NPI`、`Facility ID`、ZIP Code、PAC ID 等标识一律按字符串保存，禁止数值化导致前导零丢失。National Downloadable File 的一行粒度是“医生/Enrollment/Group/Address”，因此 NPI 在源文件中重复是正常现象，不能把源文件直接当医生主表。

### 13.1 字段选取与用途

#### Facility_Affiliation.csv

| 源字段 | 本地字段 | 是否保留 | 用途 |
|---|---|---|---|
| NPI | `npi` | 是 | 关联医生主表，也是医生实体校验依据 |
| Ind_PAC_ID | `ind_pac_id` | 是 | CMS 医生辅助标识，用于审计和冲突排查，不作主键 |
| Provider Last/First/Middle Name、suff | 不在关系表重复保存 | 否 | 姓名以 DAC 为准，避免两个文件各存一份造成冲突；原始 CSV 仍保留以便审计 |
| facility_type | `facility_type` | 是 | 标识 affiliation 对应的机构类型，用于数据质量检查和展示限制 |
| Facility Affiliations Certification Number | `facility_id` | 是 | 与医院 `Facility ID` 等值连接，是医生挂载到医院节点的核心证据 |
| Facility Type Certification Number（当前本地样本中的扩展列） | 不进入推荐 Schema | 否 | 作为原始字段和 Schema 漂移样本保留；MVP 不用于三表连接或推荐 |

#### DAC_NationalDownloadableFile.csv

| 字段组 | 选取字段 | 落库与推荐用途 |
|---|---|---|
| 医生身份 | NPI、Ind_PAC_ID、姓名、suff | 生成唯一医生实体和展示名；NPI 是唯一业务键 |
| 专业资质 | Cred、Med_sch、Grd_yr | 作为医生事实和推荐理由；不折算成分数，不推断能力高低 |
| 专业 | pri_spec、sec_spec_1..4、sec_spec_all | 展平为专业明细；用于模型内部目标专业到医生的硬匹配。主专业和次专业都可满足匹配，但不据此做质量排序 |
| 基本信息 | gndr | 可展示或用于用户明确请求的过滤；默认不参与推荐 |
| 远程服务 | Telehlth | 仅作可用性事实展示或显式过滤，不作为评分 |
| Enrollment/组织 | Ind_enrl_ID、org_pac_id、num_org_mem、ind_assgn、grp_assgn | 保留源记录粒度和 CMS 归属信息，用于去重、审计和后续资格规则；MVP 不参与排序 |
| 执业地点 | Facility Name、adr_ln_1/2、ln_2_sprs、City/Town、State、ZIP Code、Telephone Number、adrs_id | 存为医生执业地点；用于展示和数据核验。医生挂载医院仍以 Facility Affiliation 的 Facility ID 为准，禁止仅按名称模糊连接 |

`sec_spec_all` 与 `sec_spec_1..4` 重复时优先使用分列字段；只有分列缺失时才解析 `sec_spec_all` 补充。相同 NPI 的姓名、资质若出现冲突，导入 Gate 记录冲突并按版本化规范化规则选择展示值，不能静默取任意一行。

#### Hospital_General_Information.csv

| 字段组 | 选取字段 | 落库与推荐用途 |
|---|---|---|
| 身份和地址 | Facility ID、Facility Name、Address、City/Town、State、ZIP Code、County/Parish、Telephone Number | 医院实体、同 city/state 筛选、Google 路线计算和展示 |
| 机构属性 | Hospital Type、Hospital Ownership | 展示和规则过滤的预留字段，不转成质量分 |
| 服务能力 | Emergency Services、birthing friendly designation | 作为可核验服务事实；仅在疾病规则明确需要时做硬过滤 |
| 总体评分 | Hospital overall rating、footnote | 当前医院排序第一关键字；空值保持 NULL，footnote 解释不可评分原因 |
| 死亡率 | MORT 组及 Facility/Better/No Different/Worse counts、footnote | 形成医院事实证据和数据完整性检查，MVP 不另行加权排序 |
| 安全 | Safety 组及 Facility/Better/No Different/Worse counts、footnote | 形成医院事实证据和数据完整性检查，MVP 不另行加权排序 |
| 再入院 | READM 组及 Facility/Better/No Different/Worse counts、footnote | 形成医院事实证据和数据完整性检查，MVP 不另行加权排序 |
| 患者体验 | Pt Exp Group Measure Count、Count of Facility Pt Exp Measures、footnote | 表示指标覆盖度，不等同患者体验得分，不参与排序 |
| Timely/Effective Care | TE Group Measure Count、Count of Facility TE Measures、footnote | 表示指标覆盖度，不等同质量得分，不参与排序 |

医院是否能进入疾病分支，只通过“该医院存在命中模型内部目标专业的 CMS affiliation 医生”证明医生覆盖关系；不得把该关系扩展成 CMS 未提供的医院服务能力事实。

### 13.2 规范化 Schema

```sql
create table cms_provider_snapshot (
  snapshot_id uuid primary key,
  status varchar(16) not null, -- DOWNLOADING | VALIDATING | READY | ACTIVE | NO_CHANGE | FAILED
  scheduled_at timestamptz not null,
  published_at timestamptz,
  failure_code varchar(64),
  created_at timestamptz not null default now()
);

create table cms_source_file (
  snapshot_id uuid not null references cms_provider_snapshot(snapshot_id),
  dataset_id varchar(16) not null,
  logical_name varchar(64) not null,
  source_modified_at timestamptz,
  source_released_at timestamptz,
  resolved_download_url text not null,
  etag text,
  sha256 char(64) not null,
  row_count bigint not null,
  raw_object_key text not null,
  primary key (snapshot_id, dataset_id)
);

create table cms_hospital (
  snapshot_id uuid not null,
  facility_id varchar(6) not null,
  facility_name varchar(128) not null,
  address varchar(160) not null,
  city varchar(64) not null,
  state char(2) not null,
  zip_code varchar(10) not null,
  county varchar(64),
  telephone varchar(24),
  hospital_type varchar(64),
  hospital_ownership varchar(64),
  emergency_services boolean,
  birthing_friendly boolean,
  overall_rating smallint,
  overall_rating_footnote varchar(32),
  mort_group_measure_count integer, mort_facility_measure_count integer,
  mort_better integer, mort_same integer, mort_worse integer, mort_footnote varchar(32),
  safety_group_measure_count integer, safety_facility_measure_count integer,
  safety_better integer, safety_same integer, safety_worse integer, safety_footnote varchar(32),
  readm_group_measure_count integer, readm_facility_measure_count integer,
  readm_better integer, readm_same integer, readm_worse integer, readm_footnote varchar(32),
  patient_experience_group_measure_count integer,
  patient_experience_facility_measure_count integer,
  patient_experience_footnote varchar(32),
  timely_effective_group_measure_count integer,
  timely_effective_facility_measure_count integer,
  timely_effective_footnote varchar(32),
  latitude numeric(9,6), longitude numeric(9,6), geocoded_at timestamptz,
  primary key (snapshot_id, facility_id),
  check (overall_rating is null or overall_rating between 1 and 5)
);

create table cms_doctor (
  snapshot_id uuid not null,
  npi varchar(10) not null,
  ind_pac_id varchar(10),
  last_name varchar(80) not null,
  first_name varchar(80) not null,
  middle_name varchar(80),
  suffix varchar(20),
  gender_code varchar(8),
  credential varchar(64),
  medical_school varchar(160),
  graduation_year smallint,
  primary key (snapshot_id, npi)
);

create table cms_doctor_specialty (
  snapshot_id uuid not null,
  npi varchar(10) not null,
  specialty_type varchar(10) not null, -- PRIMARY | SECONDARY
  specialty_ordinal smallint not null,
  specialty_name varchar(160) not null,
  normalized_specialty varchar(160) not null,
  primary key (snapshot_id, npi, specialty_type, specialty_ordinal),
  foreign key (snapshot_id, npi) references cms_doctor(snapshot_id, npi)
);

create table cms_doctor_practice (
  snapshot_id uuid not null,
  npi varchar(10) not null,
  ind_enrl_id varchar(32) not null,
  adrs_id varchar(32) not null,
  org_pac_id varchar(16),
  facility_name varchar(160),
  address_line_1 varchar(160), address_line_2 varchar(160), line_2_suppressed boolean,
  city varchar(64), state char(2), zip_code varchar(10), telephone varchar(24),
  telehealth boolean, individual_assignment boolean, group_assignment boolean,
  organization_member_count integer,
  primary key (snapshot_id, npi, ind_enrl_id, adrs_id),
  foreign key (snapshot_id, npi) references cms_doctor(snapshot_id, npi)
);

create table cms_doctor_hospital_affiliation (
  snapshot_id uuid not null,
  npi varchar(10) not null,
  facility_id varchar(6) not null,
  ind_pac_id varchar(10),
  facility_type varchar(64),
  primary key (snapshot_id, npi, facility_id),
  foreign key (snapshot_id, npi) references cms_doctor(snapshot_id, npi),
  foreign key (snapshot_id, facility_id) references cms_hospital(snapshot_id, facility_id)
);

create table cms_active_snapshot (
  singleton_id smallint primary key check (singleton_id = 1),
  snapshot_id uuid not null references cms_provider_snapshot(snapshot_id),
  activated_at timestamptz not null
);

create index idx_cms_hospital_region_rating
  on cms_hospital(snapshot_id, state, city, overall_rating desc, facility_id);
create index idx_cms_affiliation_facility
  on cms_doctor_hospital_affiliation(snapshot_id, facility_id, npi);
create index idx_cms_specialty_lookup
  on cms_doctor_specialty(snapshot_id, normalized_specialty, npi);
```

所有业务查询必须带当前 `snapshot_id`。医院地址可离线批量地理编码；在线请求只补充缺失或失效坐标。

### 13.3 每日 04:00 下载、导入与原子发布

任务由 Spring Scheduler 执行，不经过 Agent、LLM 或鼠标键盘自动化：

```java
@Scheduled(cron = "0 0 4 * * *", zone = "America/Los_Angeles")
public void refreshCmsProviderSnapshot() { ... }
```

生产部署固定使用 IANA 时区 `America/Los_Angeles`，由时区数据库自动处理夏令时，不使用固定 `UTC-8` 偏移。

执行链路：

```text
04:00 Scheduler → distributed lock
→ 对三个 Dataset ID 调用 CMS Metastore API：
  /provider-data/api/1/metastore/schemas/dataset/items/{datasetId}
→ 从 distribution.downloadURL 解析本次完整 CSV 地址
→ 条件 GET 下载到 snapshot 临时目录
→ 校验 HTTP/类型/文件大小/SHA-256/精确 Schema 指纹/编码/行数
→ 流式解压和解析 → PostgreSQL staging（COPY/batch load）
→ NPI、Facility ID、重复键、外键和 join 命中率 Gate
→ 生成规范化 snapshot 表数据与索引
→ 单事务切换 cms_active_snapshot
→ 清理过期 staging；保留原始文件、checksum 和导入报告
```

每天都会发起三个数据集的刷新检查。若 CMS 返回 `304 Not Modified`，可复用上一份已校验原始文件；不能因未变化而制造重复大文件。若三个来源均未变化，本次运行记录为 `NO_CHANGE` 并保持当前快照，不重复装载。只要任一来源变化，就以“变化文件的新版本 + 未变化文件的已校验版本”构造一个完整候选快照。

Schema 漂移采用零容忍：每个 CSV 保存版本化基线指纹，列名、列数、列顺序和解析类型必须完全相同；新增列、缺失列、重命名、换序或类型变化均使整批失败，不自动忽略或兼容。三个来源全部可用且全部通过 Schema 与关联 Gate 才能发布。任一下载、Schema 或关联校验失败时将本批次标记为 `FAILED`，继续服务上一批 `ACTIVE` 快照并告警，禁止发布半套新数据。导入范围严格限定为第 13.1 节定义的字段，不接额外组织能力数据源。

DAC 文件规模很大，下载和导入必须流式处理，禁止将整个 CSV 放入 JVM 内存。MyBatis 用于导入元数据、业务查询和快照切换；数百万行数据装载使用 PostgreSQL `COPY` 或等价 bulk load，禁止逐行 MyBatis `insert`。

## 14. 医院范围与排序

每个疾病分支使用该疾病自身的严重程度决定医院范围，不再用三个候选中的最高严重程度统一覆盖所有分支。这样不同疾病可以得到不同的有效医院集合。

| 当前疾病严重程度 | 该疾病分支的医院范围 |
|---|---|
| SEVERE | 当前 city，不做半径过滤 |
| MODERATE | Google 驾驶路线距离 ≤ 5 miles |
| MILD | Google 驾驶路线距离 ≤ 10 miles |

地理范围与专业覆盖证据属于硬过滤。路线阶段覆盖同 city/state 的全部本地医院；Join 后，医院只有在满足该疾病严重程度对应的驾驶距离规则，并存在命中模型内部目标专业的有效 affiliation 医生时，才进入对应疾病分支。随后对每个疾病分支独立执行医院字典序排序：

```text
1. CMS overallRating DESC NULLS LAST
2. Google DRIVE distanceMiles ASC NULLS LAST
3. hospitalId ASC（稳定排序）
```

预计驾驶时间以及公交、步行路线用于展示，不参与当前排序。无评分医院统一位于有评分医院之后，并按驾驶距离排序。每个疾病分支排序后最多保留前 2 家医院。

## 15. 医生筛选与推荐

医生推荐只基于 `DAC_NationalDownloadableFile.csv` 的医生/专业事实和 `Facility_Affiliation.csv` 的任职关系，不使用医生评分，也不做医生质量排序。

对“当前疾病 + 当前医院 + 模型内部目标专业”执行以下硬过滤：

1. 医生与 affiliation 均来自当前 `ACTIVE snapshot`；
2. NPI 为合法 10 位标识，姓名存在；
3. affiliation 的 `facility_id` 必须与当前医院 Facility ID 精确相等；
4. 医生的主专业或次专业命中该疾病的 `targetSpecialties`，且目标专业已经通过 `SpecialtyVocabularyHook`；
5. 所有用户可见医生事实都能回溯到具体源文件、snapshot 和 NPI。

MyBatis 查询按相同 `snapshot_id` 连接三张规范化表，禁止跨版本 join：

```sql
select distinct d.npi, d.first_name, d.middle_name, d.last_name,
       d.suffix, d.credential, d.medical_school, d.graduation_year
from cms_doctor_hospital_affiliation a
join cms_doctor d
  on d.snapshot_id = a.snapshot_id and d.npi = a.npi
join cms_doctor_specialty s
  on s.snapshot_id = d.snapshot_id and s.npi = d.npi
where a.snapshot_id = #{snapshotId}
  and a.facility_id = #{facilityId}
  and s.normalized_specialty in (<foreach collection="specialties">...</foreach>)
order by d.npi asc
limit 2;
```

这里的 `order by npi` 只保证截断结果稳定，不表达医生优劣。

过滤通过即视为“可推荐医生”，不计算医生分数，不根据学校、毕业年份、性别、资质、Telehealth 或 PAC 信息决定优劣。推荐理由采用可核验事实，例如：

```text
医生：Jane Doe, MD（NPI: 1234567890）
推荐理由：CMS National Downloadable File 记录其主专业为 Cardiology；
CMS Facility Affiliation Data 记录其任职医院为当前医院（Facility ID: 012345）。
```

每个医院节点仍最多展示 2 名医生。合格医生超过 2 名时，查询层以 `NPI ASC` 做稳定截断，仅用于结果可复现和测试，不代表推荐优先级，UI 不显示名次或“最佳医生”等措辞。后续如要决定真正的医生排序，必须先增加新的、可解释的数据依据并更新 Evaluation 标注；本版本不预留隐式评分。

没有合格医生时仍可保留医院条目，但显示“当前 CMS 数据中未找到同时满足专业匹配和医院任职关系的医生”。

## 16. 最终疾病—医院—医生三级树

最终顶级节点是疾病假设，第二级是该疾病下的医院，第三级是该医院下与该疾病内部目标专业匹配的医生。目标专业只参与内部筛选，不作为独立推荐层级展示。

- 疾病节点最多 3 个，按疾病估计可能性/疾病置信度的版本化规则排序；
- 每个疾病节点最多 2 家医院，按 CMS 评分和驾驶路线距离排序；
- 每家医院最多 2 名符合专业与 affiliation 硬条件的医生，不进行医生质量排序；
- 同一疾病分支内医院必须去重；
- 同一家医院允许出现在不同疾病分支，不做跨疾病合并。

```yaml
NavigationResult:
  diseases: # max 3
    - hypothesis: DiseaseHypothesisPublic # excludes internal targetSpecialties
      diseaseRank: integer
      recommendationReason: string # 基于症状事实、概率和严重程度的一段式说明
      diseaseConfidence: ConfidenceResult
      hospitals: # max 2 per disease
        - hospitalId: string
          name: string
          hospitalRankWithinDisease: integer
          cmsRating: nullable<decimal>
          address: string
          recommendationReason: string # 基于医院评分、驾车路线和匹配医生数的一段式说明
          diseaseHospitalMatchReasons: list<string>
          routes:
            drive: RouteMeasure
            transit: nullable<RouteMeasure>
            walk: nullable<RouteMeasure>
          doctors: # max 2 per hospital branch
            - npi: string
              name: string
              credential: nullable<string>
              primarySpecialty: nullable<string>
              secondarySpecialties: list<string>
              medicalSchool: nullable<string>
              graduationYear: nullable<integer>
              telehealth: nullable<boolean>
              affiliationFacilityId: string
              recommendationReason: string # 基于 CMS 专业与任职关系的一段式说明
              recommendationReasons: list<string>
              evidenceIds: list<string>
          hospitalConfidence: ConfidenceResult
          evidenceIds: list<string>
          limitations: list<string>
```

最大结构为 `3 diseases × 2 hospitals × 2 doctors = 12 doctor leaves`。这只是上限：某个疾病不足 2 家合格医院或某家医院不足 2 名合格医生时返回实际数量，禁止复制或虚构补齐。由于结果规模有界，MVP 不需要对最终树分页。

`DiseaseHypothesis.targetSpecialties` 属于服务端内部筛选字段，AG-UI 投影为 `DiseaseHypothesisPublic` 时必须移除。医生卡片仍可展示 DAC 记录的医生自身专业，这是医生事实，不是额外的专业推荐层级。

患者可见语言由当前症状对话确定为 `zh-CN` 或 `en-US`，并写入推荐快照的 `displayLanguage`。内部 JSON 字段名、严重程度枚举、CMS 专业值和 Evidence 保持稳定英文契约；疾病名称、症状事实、追问、严重程度标签、评分说明、路线单位、限制和三级推荐理由必须使用 `displayLanguage`。医院名称、医生姓名、资质缩写和美国地址属于官方专名，保留原文。临床输出 Hook 会拒绝与请求语言不一致的患者可见文本并触发确定性降级，禁止把 `MILD`、`overall rating`、`Facility Affiliation` 等内部词直接投影到中文界面。

## 17. Memory 设计

Agent Memory 分为关系数据库长期记忆、Redis 用户长期记忆缓存和 Redis 当前 Session 工作缓存。当前不实现 Preference Memory，也不使用向量数据库。完整对话 Transcript 属于产品会话历史，独立持久化，不能作为画像或临床事实源。

### 17.1 设计原则

- 长期记忆使用同一个关系数据库保存，分为用户画像、Session 症状与初诊疾病、Session 摘要三类。
- Redis 保存用户长期记忆缓存和当前 Session 工作缓存。
- 老用户登录时，从数据库加载长期记忆到用户缓存。
- 当前 Session 工作缓存只保存本 Session 的更新项，不复制长期画像。
- Agent 运行时读取两类缓存并动态合并为当前有效上下文；字段冲突时当前 Session 更新优先。
- Session 进行期间不更新长期 Memory 表；Session 结束后统一写入。
- 症状和初诊疾病按 Session 保存，不判断多个 Session 是否属于同一个就诊事件。
- 初诊疾病最多保存 Top 3，数组顺序代表疾病排名，不足 3 个不补空值。
- 用户认证账户不是 Memory，注册成功时仍立即写入 `user_account`。

### 17.2 长期记忆 Schema

```sql
create table user_profile (
  user_id varchar(64) primary key references user_account(id),
  profile jsonb,
  disease_history jsonb,
  core_constraints jsonb,
  updated_at timestamptz not null
);

create table session_clinical (
  session_id varchar(64) primary key,
  user_id varchar(64) not null references user_account(id),
  symptoms jsonb,
  preliminary_diseases jsonb,
  created_at timestamptz not null,
  unique (session_id, user_id)
);
create index idx_session_clinical_user_created
  on session_clinical(user_id, created_at desc);

create table session_summary (
  session_id varchar(64) primary key,
  user_id varchar(64) not null references user_account(id),
  summary text,
  created_at timestamptz not null,
  foreign key (session_id, user_id)
    references session_clinical(session_id, user_id)
);
create index idx_session_summary_user_created
  on session_summary(user_id, created_at desc);
```

每个用户最多一条 `user_profile`；每个 Session 在 `session_clinical` 和 `session_summary` 中各对应一条记录。`session_id` 同时提供幂等约束，结束流程重试时使用 UPSERT，不能产生重复历史。

`profile` 使用固定结构：

```json
{
  "birth_date": "1990-05-16",
  "sex": "female",
  "height_cm": 165,
  "weight_kg": 55
}
```

`disease_history` 保存使用 Agent 之前已经存在的疾病史，例如：

```json
["高血压", "哮喘"]
```

`core_constraints` 保存用户明确提供的核心约束，不保存行为推断偏好：

```json
{
  "drug_allergies": ["青霉素"],
  "food_allergies": ["花生"],
  "medication_contraindications": [],
  "dietary_restrictions": ["素食"]
}
```

新用户注册或登录时不创建空 `user_profile`。其首次 Session 结束后，只有存在有效画像、疾病史或核心约束更新时才创建；老用户仅合并本 Session 实际更新的字段。同一 Session 中同一字段发生变化时，最终值覆盖此前值。

`session_clinical.symptoms` 保存该 Session 的最终症状；`preliminary_diseases` 保存最终 Top 3 初诊疾病，数组顺序表示排名。初诊疾病是系统假设而非确诊，摘要和 UI 都必须保持这一语义。

`session_summary.summary` 只保留 Session 语境。画像、疾病史、核心约束、症状和初诊疾病直接从分类字段落库，禁止再从摘要反向提取并覆盖结构化数据。

### 17.3 Redis 用户长期记忆缓存

```text
Key: memory:user:{user_id}
Type: String(JSON)
```

```json
{
  "user_id": "u_001",
  "has_long_term_profile": true,
  "profile": {
    "birth_date": "1990-05-16",
    "sex": "female",
    "height_cm": 165,
    "weight_kg": 55
  },
  "disease_history": ["哮喘"],
  "core_constraints": {
    "drug_allergies": ["青霉素"],
    "food_allergies": [],
    "medication_contraindications": [],
    "dietary_restrictions": []
  },
  "history": [
    {
      "session_id": "s_100",
      "symptoms": ["咳嗽", "发热"],
      "preliminary_diseases": ["流行性感冒", "新型冠状病毒感染", "普通感冒"],
      "summary": "用户因咳嗽和发热进行导诊。",
      "created_at": "2026-09-10T10:00:00Z"
    }
  ]
}
```

`history` 由 `session_clinical` 与 `session_summary` 按 `session_id` 组合。用户缓存允许设置 TTL；缓存未命中时从长期数据库重新加载。它是长期事实的可丢弃副本，不是第二事实源。

### 17.4 Redis 当前 Session 工作缓存

```text
Key: memory:session:{session_id}
Type: String(JSON)
```

新老用户创建 Session 时都初始化为空：

```json
{
  "session_id": "s_001",
  "user_id": "u_001",
  "profile": {},
  "disease_history": [],
  "core_constraints": {},
  "symptoms": [],
  "preliminary_diseases": [],
  "summary": "",
  "updated_at": "2026-09-11T10:00:00Z"
}
```

`profile`、`disease_history` 和 `core_constraints` 只保存本 Session 发生的更新。例如用户只更新体重时，工作缓存只保存：

```json
{"profile": {"weight_kg": 57}}
```

不得把长期画像的其他字段复制到当前 Session 工作缓存。当前症状、Top 3 初诊疾病和 Session 摘要保存在各自字段中。

### 17.5 Agent 读取与动态合并

每次需要 Memory 上下文时，Runtime 同时读取：

```text
memory:user:{user_id}
memory:session:{session_id}
```

并只在运行内存中动态合并：

```text
effective profile          = long-term cache.profile + session cache.profile
effective disease history  = long-term cache.disease_history + session updates
effective constraints      = long-term cache.core_constraints + session updates
current clinical context   = session cache.symptoms + preliminary_diseases + summary
historical context         = long-term cache.history
```

当前 Session 字段优先。动态合并结果只供当前 Session 使用，不写回 `memory:user:{user_id}`。历史初诊结果必须标记为 `HISTORICAL`，不得自动当作当前疾病或确诊事实。

### 17.6 登录与 Session 创建

老用户：

```text
登录成功
→ 读取 user_profile
→ 读取 session_clinical 和 session_summary 历史
→ 组装并写入 memory:user:{user_id}
→ 创建 Session
→ 创建空 memory:session:{session_id}
```

新用户：

```text
注册并持久化 user_account
→ 创建 has_long_term_profile=false 的空用户缓存
→ 创建空 memory:session:{session_id}
```

新用户缓存中的 `profile`、`disease_history`、`core_constraints` 和 `history` 均为空；不因此创建空的长期 `user_profile`。

### 17.7 Session 结束时写入长期记忆

Session 正常完成、显式关闭或由 Session 生命周期管理器判定结束时，`SessionMemoryFinalizer` 读取 `memory:session:{session_id}`。如需模型生成摘要，由 `SessionSummarySkill` 在事务开始前形成最终摘要；结构化字段不从摘要提取。

随后在同一数据库事务中执行：

```text
session.profile              → 合并到 user_profile.profile
session.disease_history      → 合并到 user_profile.disease_history
session.core_constraints     → 合并到 user_profile.core_constraints
symptoms + preliminary_diseases → UPSERT session_clinical
summary                      → UPSERT session_summary
```

数据库提交成功后才重新读取最新长期记忆、刷新 `memory:user:{user_id}`，最后删除 `memory:session:{session_id}`。数据库写入失败时必须保留 Session 工作缓存并进入幂等重试，禁止先删缓存。

```text
长期关系数据库
    ↓ 登录时加载
memory:user:{user_id}
    + memory:session:{session_id}
    ↓ 运行时动态合并
当前有效上下文
    ↓ Session 结束后事务提交
长期关系数据库 → 刷新用户缓存 → 删除 Session 缓存
```

### 17.8 Redis 操作与失败语义

Redis 只需使用 `GET`、`SET` 和 `DEL` 实现当前设计：

```text
登录加载：数据库 SELECT → SET memory:user:{user_id}
创建会话：SET memory:session:{session_id}
读取上下文：GET 两类缓存 → 运行内存动态合并
更新工作数据：SET memory:session:{session_id}
结束会话：GET Session 缓存 → 数据库事务 → 刷新用户缓存 → DEL Session 缓存
```

用户长期记忆缓存可以配置 TTL，过期后从数据库重新加载。当前 Session 工作缓存不得因为普通缓存 TTL 而先于持久化事务删除；写库失败时必须保留以供重试。若后续增加异常 Session 清理，只能在确认长期事务成功或明确放弃且完成审计后删除。

### 17.9 多 Session 与会话历史

同一 `user_id` 可以拥有多个 Session。前端使用类似 ChatGPT 的侧栏列出 Session；用户可以创建新 Session、查看历史消息，或恢复仍处于 ACTIVE 且空闲未满 10 分钟的 Session。

```sql
create table conversation_session (
  session_id varchar(64) primary key,
  conversation_id varchar(64) not null unique,
  user_id varchar(64) not null references user_account(id),
  title varchar(160) not null,
  status varchar(20) not null,
  created_at timestamptz not null,
  last_activity_at timestamptz not null,
  completed_at timestamptz,
  completion_reason varchar(20)
);

create table conversation_message (
  id varchar(64) primary key,
  session_id varchar(64) not null references conversation_session(session_id),
  run_id varchar(64),
  sequence_no bigint not null,
  role varchar(20) not null,
  content text not null,
  message_type varchar(40) not null default 'TEXT',
  structured_payload text,
  created_at timestamptz not null,
  unique (session_id, sequence_no)
);
```

消息在每个 Turn 完成时增量写入，不以网页 DOM、本地存储或 Redis 作为唯一副本。`sequence_no` 保证稳定回放顺序；密码仍只进入专用 Secret API，禁止写入 `conversation_message`。最终推荐使用 `message_type=RECOMMENDATION_RESULT` 和结构化 JSON 快照保存，历史回放时恢复完整推荐树。会话历史只负责展示和审计，不参与画像合并、历史疾病判断或 Summary 事实提取。

生命周期语义：

- 用户显式点击“结束 Session”：执行 Memory Finalizer，并将 Session 标记为 `COMPLETED/EXPLICIT`，之后只能查看。
- 页面关闭、刷新或切换 Session：不等同于结束；空闲未满 10 分钟且 Runtime 状态仍在时可以恢复并继续创建 Run。
- 空闲达到 10 分钟：串行执行 Finalizer，并标记为 `COMPLETED/IDLE_TIMEOUT`；历史仍可查看，但不能恢复。
- 服务进程重启后，已持久化 Transcript 仍可查看；当前实现不从 Transcript 重建 Agent 运行态，因此重启前的 ACTIVE Session 不承诺可继续运行。

### 17.10 不保存的内容

- 密码、Token、Cookie、API Key；
- 模型隐藏推理；
- Google 大型原始响应和 CMS CSV 的会话副本；CMS 原始文件只进入受控导入存储；
- 用户偏好与行为推断。

## 18. 门禁与 Verification

```text
G0 Auth/Tool Permission
G1 Profile Schema/3-round
G2 Text Readability/Symptom JSON
G3 Location/Route JSON
G4 Disease Hypothesis/User-Fact Evidence/Specialty Vocabulary
G5 Hospital Range/Rating/Route
G6 Doctor Entity/Specialty/Affiliation
G7 Explanation/Evidence
Final Verification
```

Final Verification 最多 3 轮，只修复低分条目：疾病失败只重跑该假设及受影响医院匹配；内部目标专业失败只重跑该疾病的该字段；路线失败只重算对应医院；医院失败只删除/重建该医院；医生失败只处理该医生；理由失败只重写理由。预算耗尽后删除未通过条目；没有医院通过时返回疾病级降级结果。

Semantic Verification Model 已启用，但不是每轮必经节点。只有当 Run 新生成或实质修改最终推荐，并且确定性 Gate 标记低置信度、证据—话术不一致或跨层语义冲突时，才调用 `VerificationSkill`。认证、画像、普通症状追问、位置进度事件、纯缓存命中和已被硬规则直接判错的条目不调用。语义模型只能产生 findings；最终 PASS、局部重跑或删除仍由确定性 `VerificationService` 决定。

所有硬门禁优先于置信度，硬失败不能被高总分抵消。

## 19. 置信度

```text
diseaseConfidence = userFactCoverage + symptomConsistency
                    + schemaValidity + inferenceConsistency

hospitalConfidence = ratingValidity + specialtyCoverage
                     + locationValidity + routeValidity + dataFreshness

doctorConfidence = entityValidity + specialtyMatch
                   + affiliationEvidence + dataFreshness

explanationConfidence = claimEvidenceCoverage + consistency + completeness
```

具体权重进入版本化 `ConfidencePolicy`，并由 Evaluation 校准。最终置信度来自可计算的 Gate component，而不是 LLM 自报分数。用户可看到 HIGH/MEDIUM/LOW 或百分制“链路置信度”；必须说明它表示证据完整性与系统一致性，不等同于疾病概率。

## 20. 前端与 AG-UI

单一对话页面，不使用固定登录/注册页面。关键组件：聊天流、安全密码框、位置授权/手动地址、管线进度、疾病摘要、医院卡片、医生子列表、Evidence 和置信度展开项。

自定义事件：

- `AUTH_SECRET_REQUEST`、`AUTH_STATUS_CHANGED`；
- `PROFILE_MISSING_FIELDS`；
- `LOCATION_PERMISSION_REQUEST`、`LOCATION_STATUS_CHANGED`；
- `PIPELINE_PROGRESS`；
- `CLINICAL_QUESTION`；
- `HOSPITAL_RESULTS_SNAPSHOT`；
- `VALIDATION_FINDING`、`LOCAL_REPAIR_PROGRESS`。

前端展示阶段和 Tool 状态，不展示隐藏 chain-of-thought。

## 21. System Prompt 要点

```text
Maintain one continuous conversation covering authentication, profile updates,
symptom collection, initial disease hypotheses, and navigation explanations.

After an existing user logs in, ask one concise message inviting current symptoms
and optional corrections to profile information.

For symptoms, decide dynamically whether to ask one high-value question or return
READY. Do not require explicit user confirmation and do not repeat known questions.
After three clarification rounds, return READY_LIMITED only when readinessConfidence
is at least 0.60; otherwise return INSUFFICIENT_INFORMATION without a provider tree.

Infer up to three disease hypotheses, severity, estimated probability, and internal
target specialties directly from symptoms and effective memory. Target specialties
must use the supplied CMS specialty vocabulary and must remain internal filtering
inputs rather than a separate recommendation layer.

Never invent hospitals, doctors, hospital ratings, specialties, affiliations,
routes, or evidence. Do not score or rank doctors.
Provider facts must come from tools. Return fewer results when verified data is
insufficient. Passwords are never valid chat input; request the secure UI control.
```

## 22. Evaluation 前置

### 22.1 实现前产物

- Evaluation Case schema 和标注指南；
- 症状、疾病严重程度和 CMS 医生专业词表；
- CMS 三 CSV/Google 固定数据快照；
- 每个 Skill、Tool、Gate 的期望输入输出；
- 失败分类、阈值、随机种子和版本记录规则。

### 22.2 模块集

| Set | 覆盖 |
|---|---|
| Auth | 用户名、并发重复注册、错误密码、secret 泄漏 |
| Profile | 完整/部分/拒答/非法日期/三轮降级 |
| Clinical | 可读性、症状抽取、ASK/READY、三轮边界、0.60 阈值、疾病及内部目标专业 |
| Location | 拒绝定位、手动地址、全部本地医院覆盖、100 元素跨批次、10 分钟缓存、Route timeout、多交通方式 |
| CMS Hospital | rating null、city/state、目标专业 affiliation 证据和导入快照 |
| CMS Import | 三文件下载、304 复用、任意增删/重命名/换序/类型漂移整批失败、流式导入、join 完整性、原子切换与失败回滚 |
| Doctor | NPI 去重、主/次专业匹配、affiliation 精确连接、稳定截断、空结果、无医生评分 |
| Ranking | 严重程度范围、医院二级排序；验证医生没有质量排序 |
| Memory | 长短期动态合并、Session 隔离、Top 3 顺序、结束事务、缓存刷新、失败保留与幂等重试 |
| Concurrency | Session 串行、跨 Session 并行、Run 内并行、旧 Run 失效 |
| Verification | 条件触发、无需调用场景、错误检出、局部修复和 3 轮预算 |
| E2E | 注册/登录至疾病—医院—医生三级树及增量更新 |

### 22.3 阻断指标

- 密码进入 LLM、Memory、日志或 Trace：0；
- 未认证调用受保护 Tool：0；
- Session 内并发提交或旧 revision 覆盖：0；
- 虚构医院、医生或 affiliation：0；
- 5/10 miles 或同 city 硬规则违反：0；
- 本地医院因候选数量或 Route 批次数被静默截断：0；
- CMS Schema 指纹变化后仍发布新快照：0；
- `questionCount = 3` 时违反 0.60 readiness 分流规则：0；
- Semantic Verification Model 在认证、画像或普通追问 Turn 中被调用：0；
- 医院二级排序错误：0；
- 医生评分字段或医生质量排序进入响应：0；
- 疾病/医院/医生层级超出 `3/3/2` 上限：0；
- 同一疾病分支内重复医院：0；
- 医生未挂载在其已验证医院分支下：0；
- 无 Evidence 的用户可见事实：0；
- Verification 超过 3 轮：0；
- 跨用户 AgentState/Memory 泄漏：0。
- Session 结束前更新长期 Memory 表：0；
- 长期事务提交前删除 Session 工作缓存：0；
- 同一 `session_id` 产生重复 `session_clinical` 或 `session_summary`：0；

## 23. 日志与埋点

关键事件：认证、画像轮次、readiness 分数/轮次/分流结果、长期 Memory 加载、Session 工作缓存创建、动态合并、Session Memory 结束事务、缓存刷新/删除/重试、LocationTask、Google route cache hit/miss、医院总数、路线元素总数和批次、Agent Run、Skill/Tool、Join、医院过滤/排序、医生资格过滤/稳定截断、确定性 Gate、Semantic Verification 条件触发/跳过原因、局部修复、响应和 CMS 三文件导入。

公共字段：`traceId/runId/sessionId/userIdHash/contextVersion/locationRevision/clinicalRevision/model/promptVersion/cmsSnapshotId/cmsDatasetIds/cmsSourceModifiedAt/latencyMs/tokenUsage/status/errorCode/retryCount`。

不得记录密码、密码 hash、精确 secret、模型隐藏推理和大型原始 Tool payload。

## 24. API

```text
POST   /api/conversations
POST   /api/conversations/{id}/runs
GET    /api/conversations/{id}/events
POST   /api/auth/conversation/login
POST   /api/auth/conversation/register
POST   /api/location/consent
POST   /api/location/manual
GET    /api/navigation/runs/{runId}
GET    /api/navigation/runs/{runId}/results
GET    /api/navigation/runs/{runId}/diseases/{hypothesisId}
GET    /api/users/me/profile
GET    /api/users/me/session-memories
DELETE /api/users/me/session-memories/{sessionId}
```

## 25. 推荐代码结构

```text
com.example.mednav
├── api
├── agui
├── agent
│   ├── MedicalNavigationAgent
│   ├── MedicalNavigationAgentFactory
│   ├── OpenAiModelAdapter
│   └── ToolGroupPolicy
├── skill
├── tool
├── hook
├── gate
├── runtime
│   ├── SessionRunScheduler
│   ├── RunLifecycle
│   └── budget
├── context
├── auth
├── profile
├── clinical
├── location
├── hospital
│   ├── cms
│   ├── route
│   └── ranking
├── doctor
│   ├── cms
│   ├── specialty
│   └── selection
├── provider
│   └── cmsimport
├── recommendation
├── verification
├── memory
│   ├── model
│   ├── redis
│   ├── merge
│   └── finalizer
├── mapper
├── persistence
├── evaluation
├── observability
└── config
```

## 26. 实施顺序

1. Evaluation schema、症状/严重程度/CMS 专业词表、fixtures、Skill/Tool/Gate contracts。
2. Spring Boot、MyBatis、PostgreSQL migration 和认证门禁。
3. React + AG-UI、安全密码控件、用户画像三轮降级。
4. CMS 三 CSV 定时下载、staging、规范化 Schema、原子 snapshot 和导入评测。
5. Google Geocoding、全部本地医院 Route Matrix、10 分钟缓存、100 元素跨批次、医院排序和 CMS 医生筛选。
6. AgentScope Java + OpenAI Adapter、核心 Skills 和 prompt。
7. SessionRunScheduler、revision、并行 Join 和新消息失效。
8. 疾病—医院—医生三级树、疾病/医院排序、医生稳定截断和 Explanation。
9. 长期 Memory 三表、Redis 双缓存、动态合并、SessionSummarySkill 和结束事务。
10. 确定性 Gate、条件 Semantic Verification、局部修复、E2E、故障注入和评测报告。

## 27. Definition of Done

- 用户可通过纯对话完成注册、登录及画像补全，密码不进入模型链路。
- 老用户登录后只用一个合并问题进入症状或资料更新流程。
- LocationTask 与当前轮 Agent Run 并行，追问时位置结果可跨 Run 复用。
- 同 Session Run 串行、跨 Session 并行、Run 内独立 Tool 并行。
- 新消息永不忽略，revision 可阻止旧结果提交并支持局部重算。
- CMS 三 CSV 按 `America/Los_Angeles` 每天 04:00 自动刷新，零 Schema 漂移且三文件通过 Gate 后原子发布；失败时继续使用上一完整快照。
- CMS 医院、Google 多交通路线和 CMS 本地医生链路可运行并降级。
- Route Matrix 不限制本地候选医院数量，按 100 元素跨批次覆盖全部医院并使用 10 分钟缓存，不因批次数静默截断。
- 严重程度的全市/5/10 miles 规则正确。
- 医院按评分、驾驶路线距离排序；医生只按专业和 affiliation 筛选，不评分、不做质量排序。
- 最终输出为最多 3 个疾病、每个疾病最多 2 家医院、每家医院最多 2 名医生的三级树。
- 同一疾病内医院去重；同一家医院可出现在不同疾病分支，并使用各分支独立理由和置信度。
- 每个用户可见事实有 Evidence，每个医院和医生理由可解释。
- 不输出独立的服务类别推荐；核心模型生成的 CMS 目标专业只作为医生过滤字段，且全部通过专业词典 Gate。
- 症状追问最多 3 轮；第 3 轮后严格按 0.60 readiness 阈值决定受限推荐或停止 Provider 推荐。
- Semantic Verification Model 仅在最终推荐发生实质变化且存在语义风险时调用，普通 Turn 不调用。
- 老用户登录时从三张长期表组装用户缓存，新用户不创建空长期画像。
- Session 期间只更新当前 Session 工作缓存；结束时在同一事务中写入画像、临床记录和摘要。
- 长期事务成功后才刷新用户缓存并删除 Session 缓存；失败时保留并幂等重试。
- 每个 Session 在 `session_clinical` 和 `session_summary` 中最多各一条记录；完整 Transcript 单独写入 `conversation_session/conversation_message`，不属于 Memory，无向量记忆和偏好存储。
- 同一用户可创建多个 Session；ACTIVE 且空闲未满 10 分钟的 Session 可恢复，显式结束或超时 Session 只读。
- Verification 最多 3 轮且只修复失败分支。
- 模块评测、并发测试、关键门禁和链路埋点全部通过。

## 28. 已确定配置与部署占位

1. `PRIMARY_MODEL`、`SUMMARY_MODEL`、`VERIFICATION_MODEL` 和 OpenAI API 凭证由部署者后续填写；Semantic Verification Model 已启用为条件调用模型。
2. 疾病、严重程度、估计可能性和内部 CMS 目标专业由核心模型基于症状与有效 Memory 自主判断，不接独立疾病知识数据源。
3. CMS 三个 CSV 的 Schema 漂移为零容忍，只导入第 13 节字段；任何 Schema 变化均阻止整批发布。
4. Google Route Matrix 覆盖全部本地医院，缓存 10 分钟，按 100 个路线元素拆分请求批次；无候选数量上限和单 Run 总元素上限。
5. CMS 导入固定在 `America/Los_Angeles` 每日 04:00 执行。
6. 医生不足 2 名时仅返回现有合格医生，不放宽专业或 affiliation 硬条件。
7. 症状追问最多 3 轮；第 3 轮后 `readinessConfidence >= 0.60` 才输出受限推荐树，低于 0.60 时不生成 Provider 推荐。

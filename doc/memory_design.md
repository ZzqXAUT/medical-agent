# 医疗导诊 Agent Memory 设计

## 一、设计原则

- 长期记忆使用关系型数据库保存，分为用户画像、Session 症状与初诊疾病、Session 摘要三类。
- Redis 保存用户长期记忆缓存和当前 Session 工作缓存。
- 老用户登录时，从数据库读取长期记忆并加载到用户长期记忆缓存。
- 当前 Session 工作缓存只保存本 Session 的更新项，不复制长期画像。
- Agent 每轮同时读取用户长期记忆缓存和当前 Session 工作缓存，动态合并为当前有效上下文。
- 同一 Session 内同一字段前后不一致时，以后一次更新覆盖前一次更新。
- Session 进行期间不更新长期记忆；Session 结束后统一写入长期记忆。
- 症状和初诊疾病按 Session 保存，不判断多个 Session 是否属于同一个就诊事件。
- 初诊疾病最多保存 Top 3，数组顺序代表排名。

## 二、长期记忆数据库

三类长期记忆使用同一个关系型数据库中的三张表。

### 1. 用户画像表 `user_profile`

每个用户一条记录。

| 字段 | 类型 | 是否可空 | 存储内容 |
|---|---|---:|---|
| `user_id` | VARCHAR，主键 | 否 | 用户唯一标识 |
| `profile` | JSON | 是 | 用户画像，包括出生日期、性别、身高和体重 |
| `disease_history` | JSON | 是 | 使用 Agent 之前曾患有的疾病 |
| `core_constraints` | JSON | 是 | 过敏、用药禁忌等核心约束 |
| `updated_at` | DATETIME | 否 | 最后更新时间 |

`profile` 使用固定结构：

```json
{
  "birth_date": "1990-05-16",
  "sex": "female",
  "height_cm": 165,
  "weight_kg": 55
}
```

`disease_history` 示例：

```json
[
  "高血压",
  "哮喘"
]
```

`core_constraints` 示例：

```json
{
  "drug_allergies": ["青霉素"],
  "medication_contraindications": []
}
```

写入规则：

- 新用户登录时不创建空画像。
- 新用户首次 Session 结束后，根据 Session 中产生的更新项创建画像。
- 老用户在 Session 结束时，仅合并本 Session 更新过的字段。
- 同一 Session 内同一字段多次变化，以最后一次为准。
- Session 进行期间不更新该表。

### 2. Session 症状与初诊疾病表 `session_clinical`

每个 Session 一条记录，不判断多个 Session 是否属于同一次就诊事件。

| 字段 | 类型 | 是否可空 | 存储内容 |
|---|---|---:|---|
| `session_id` | VARCHAR，主键 | 否 | Session 唯一标识 |
| `user_id` | VARCHAR，索引 | 否 | 所属用户 |
| `symptoms` | JSON | 是 | 本 Session 最终症状 |
| `preliminary_diseases` | JSON | 是 | 初诊疾病 Top 3 |
| `created_at` | DATETIME | 否 | 创建时间 |

`preliminary_diseases` 最多保存三个疾病，数组顺序代表排名：

```json
[
  "流行性感冒",
  "新型冠状病毒感染",
  "普通感冒"
]
```

不足三个时按实际数量保存，不补空值。

### 3. Session 摘要表 `session_summary`

每个 Session 一条记录。

| 字段 | 类型 | 是否可空 | 存储内容 |
|---|---|---:|---|
| `session_id` | VARCHAR，主键 | 否 | Session 唯一标识 |
| `user_id` | VARCHAR，索引 | 否 | 所属用户 |
| `summary` | TEXT | 是 | 本 Session 最终摘要 |
| `created_at` | DATETIME | 否 | 创建时间 |

摘要只用于保留 Session 语境。画像、疾病史、核心约束、症状和初诊疾病直接从短期分类字段落库，不从摘要重新提取。

## 三、Redis 用户长期记忆缓存

老用户登录后，将数据库中的长期记忆加载到 Redis。

**Key：**

```text
memory:user:{user_id}
```

**类型：** Redis String，值为 JSON。

| 字段 | 类型 | 存储内容 |
|---|---|---|
| `user_id` | string | 用户唯一标识 |
| `has_long_term_profile` | boolean | 是否已经存在长期画像 |
| `profile` | object | 长期用户画像，包括出生日期、性别、身高和体重 |
| `disease_history` | array | 使用 Agent 之前的疾病史 |
| `core_constraints` | object | 长期核心约束 |
| `history` | array | 历史 Session 信息 |

其中 `profile` 的结构为：

```json
{
  "birth_date": "1990-05-16",
  "sex": "female",
  "height_cm": 165,
  "weight_kg": 55
}
```

完整示例：

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
  "disease_history": [
    "哮喘"
  ],
  "core_constraints": {
    "drug_allergies": ["青霉素"],
    "medication_contraindications": []
  },
  "history": [
    {
      "session_id": "s_100",
      "symptoms": ["咳嗽", "发热"],
      "preliminary_diseases": [
        "流行性感冒",
        "新型冠状病毒感染",
        "普通感冒"
      ],
      "summary": "用户因咳嗽和发热进行导诊。",
      "created_at": "2026-09-10T10:00:00"
    }
  ]
}
```

`history` 中每项包含：

| 字段 | 类型 | 存储内容 |
|---|---|---|
| `session_id` | string | 历史 Session 标识 |
| `symptoms` | array | 历史症状 |
| `preliminary_diseases` | array | 历史初诊疾病 Top 3 |
| `summary` | string | 历史 Session 摘要 |
| `created_at` | string | Session 创建时间 |

## 四、Redis 当前 Session 工作缓存

**Key：**

```text
memory:session:{session_id}
```

**类型：** Redis String，值为 JSON。

| 字段 | 类型 | 存储内容 |
|---|---|---|
| `session_id` | string | 当前 Session 标识 |
| `user_id` | string | 所属用户 |
| `profile` | object | 本 Session 中发生更新的画像字段，包括出生日期、性别、身高和体重 |
| `disease_history` | array | 本 Session 中发生更新的既往疾病史 |
| `core_constraints` | object | 本 Session 中发生更新的核心约束 |
| `symptoms` | array | 当前 Session 症状 |
| `preliminary_diseases` | array | 当前初诊疾病 Top 3 |
| `summary` | string | 当前 Session 摘要 |
| `updated_at` | string | 最近一次 Turn 更新时间 |

无论新用户还是老用户，创建 Session 时都初始化为空：

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
  "updated_at": "2026-09-11T10:00:00"
}
```

当前 Session 工作缓存中的 `profile` 与长期记忆中的 `profile` 使用相同结构：

```json
{
  "birth_date": "1990-05-16",
  "sex": "female",
  "height_cm": 165,
  "weight_kg": 55
}
```

但这里只保存本 Session 更新过的字段。例如，用户只修改体重：

```json
{
  "profile": {
    "weight_kg": 57
  }
}
```

不会把用户长期记忆缓存里的其他字段复制到当前 Session 工作缓存。`core_constraints` 和 `disease_history` 同样只记录当前 Session 的更新内容。

## 五、Agent 如何读取长短期记忆

由于当前 Session 工作缓存只保存更新项，Agent 每轮处理前同时读取：

```text
memory:user:{user_id}
memory:session:{session_id}
```

然后在运行内存中形成当前有效上下文：

```text
当前有效画像
= 用户长期记忆缓存.profile
+ 当前 Session 工作缓存.profile
```

发生字段冲突时，当前 Session 工作缓存优先。例如：

```json
{
  "height_cm": 165,
  "weight_kg": 55
}
```

以上是用户长期记忆缓存中的 `profile`，当前 Session 工作缓存为：

```json
{
  "weight_kg": 57
}
```

Agent 本轮使用的有效画像为：

```json
{
  "height_cm": 165,
  "weight_kg": 57
}
```

动态合并结果只供当前 Session 使用，不写回用户长期记忆缓存。

## 六、新老用户登录与 Session 创建

### 1. 老用户登录

```text
用户登录
   ↓
读取 user_profile
   ↓
读取 session_clinical 和 session_summary 历史记录
   ↓
写入 memory:user:{user_id}
   ↓
创建新 Session
   ↓
创建空的 memory:session:{session_id}
```

老用户的长期画像不会复制到当前 Session 工作缓存。

### 2. 新用户登录

```text
用户登录
   ↓
查询不到 user_profile
   ↓
创建用户长期记忆缓存
   ↓
创建空的 Session 工作缓存
```

新用户的用户长期记忆缓存示例：

```json
{
  "user_id": "u_new",
  "has_long_term_profile": false,
  "profile": {},
  "disease_history": [],
  "core_constraints": {},
  "history": []
}
```

## 七、每个 Turn 的更新规则

每轮处理流程：

```text
读取用户长期记忆缓存
        +
读取当前 Session 工作缓存
        ↓
合并成当前有效上下文
        ↓
处理用户本轮输入
        ↓
只把本轮产生的更新写入 Session 工作缓存
        ↓
更新症状、初诊疾病 Top 3 和摘要
```

具体规则：

- `profile`：只保存当前 Session 更新过的画像字段。
- `disease_history`：只保存当前 Session 对既往疾病史的更新。
- `core_constraints`：只保存当前 Session 更新过的约束字段。
- `symptoms`：保存本 Session 当前整理后的症状。
- `preliminary_diseases`：每轮可以重新计算，只保留当前 Top 3。
- `summary`：按 Turn 更新，只总结当前 Session。
- 同一 Session 中同一字段前后不一致时，后值覆盖前值。
- 本轮没有提及的字段不产生更新。

## 八、Session 结束时写入长期记忆

Session 结束时读取：

```text
memory:session:{session_id}
```

在同一个数据库事务中执行：

```text
profile 中存在的字段
    → 合并到 user_profile.profile

disease_history
    → 合并到 user_profile.disease_history

core_constraints 中存在的字段
    → 合并到 user_profile.core_constraints

symptoms + preliminary_diseases
    → 写入 session_clinical

summary
    → 写入 session_summary
```

数据库提交成功后：

1. 重新读取最新长期记忆。
2. 刷新 `memory:user:{user_id}`。
3. 删除 `memory:session:{session_id}`。

如果数据库写入失败，保留当前 Session 工作缓存用于重试。

完整数据流：

```text
数据库长期记忆
       ↓ 登录时加载
memory:user:{user_id}
       ↓ 与当前更新动态合并
memory:session:{session_id}
       ↓ Session 结束后提交
数据库长期记忆
```

## 九、Redis 缓存实现方式

使用 Redis 的 `GET`、`SET` 和 `DEL` 完成缓存操作：

```text
登录加载：数据库 SELECT → Redis SET 用户长期记忆缓存
创建会话：Redis SET 空的 Session 工作缓存
逐轮处理：Redis GET 两类缓存 → 动态合并 → Redis SET Session 工作缓存
会话结束：Redis GET Session 工作缓存 → 数据库事务 → 刷新用户缓存 → DEL Session 缓存
```

用户长期记忆缓存可以设置 TTL，过期后从数据库重新加载。当前 Session 工作缓存只有在数据库成功写入长期记忆后才删除；如果写入失败，则保留缓存用于重试。

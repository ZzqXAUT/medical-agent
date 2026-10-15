# Evaluation labels v0

每条样本至少包含：

- `caseId`：稳定唯一编号；
- `module`：当前被孤立评测的模块；
- `input`：模块输入；
- `expected`：可确定断言；
- `tags`：正常、边界、非法或降级标签；
- `datasetVersion`：评测集版本。

M1 至 M8 当前共 95 条样本。构建门禁会扫描全部 JSONL，校验必填字段、非空标签、数据集版本和全局唯一 `caseId`，不允许只校验最新里程碑。

执行原则：

- 确定性硬规则由对应模块的 JUnit/Integration Test 断言；
- 外部 HTTP 行为使用 Mock Server，真实 OpenAI/Google 抽样单独运行，不作为普通构建前置；
- Agent 输出先执行结构/硬规则校验，只有语义指标才使用条件模型评测；
- 每个新发现的失败样本必须回填到对应版本集，并绑定至可复现的模块测试。

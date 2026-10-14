# LLM 路由选择：公网 / 内网（角色识别 + 信息提取）

## 定位

MedicalAI 后端有两处"把医生对话内容交给 LLM"的地方，各自都能在**公网 LLM（DashScope）**与
**内网本地 LLM（llama.cpp，`.56:8080`）**之间选择：

| 功能 | 做什么 | 公网实现 | 内网实现 |
| --- | --- | --- | --- |
| 角色识别 | 给 ASR 的每个句段判断医生 / 患者 / 其他 | `DashScopeRoleClient` | `LocalLlmRoleClient` |
| 信息提取 | 从对话里抽主诉、既往史、诊断等 12 个结构化字段 | `DashScopeClinicalExtractionClient` | `LocalLlmExtractionClient` |

两对实现共用同一套提示词、同一份 JSON Schema 与同一套解析规则（`RoleLlmClient`、
`ClinicalExtractionLlmClient` 两个契约），因此**换路由不会换口径**。

路由是**能力驱动**的：界面上只出现当前真的能调用的路由。请求里显式指定的路由不可用时直接报错，
**绝不悄悄改走另一条路**（医疗内容不该因为"另一条路还活着"就被发去别处）。

```
                    ASR（本地 .78 / 公网 DashScope）
                               │  句段：index / role / text / start_ms / end_ms
             ┌─────────────────┴─────────────────┐
             ▼                                   ▼
    LlmRoleRouter                        ClinicalExtractionRouter
    ├── DASHSCOPE → DashScopeRoleClient  ├── DASHSCOPE → DashScopeClinicalExtractionClient
    └── LOCAL     → LocalLlmRoleClient   └── LOCAL     → LocalLlmExtractionClient（直连 .56）
                    （直连 .56）
```

## 三个选择入口

| 入口 | 界面位置 | 请求 |
| --- | --- | --- |
| 转写时的自动角色判断 | 录音上传页"角色识别路由"单选 | `POST /recordings/transcribe` 的 `role_provider` |
| 转写结果页手动重判 | 角色面板"公网 / 内网"按钮 + "开始 AI 判断" | `POST /transcript/roles/reclassify` 的 `provider` |
| 信息提取 | 提取面板"公网 / 内网"按钮 + "生成信息提取" | `POST /clinical-extraction/generate` 的 `provider` |

`role_provider` / `provider` 传 `null`（或不传）表示**跟随后端配置的默认路由**：

- 角色识别默认路由：`LLM_ROLE_ROUTE`（默认 `DASHSCOPE`）
- 信息提取默认路由：`LLM_EXTRACTION_ROUTE`（默认 `DASHSCOPE`）

转写时的选择会**随任务落库**（`ai_job.role_provider_route`），所以工作线程在转写完成后按医生当时
选的路由判断角色，而不是读进程启动时的配置。

### 报文

```http
POST /api/v1/visits/{visitId}/recordings/transcribe
{"provider":"LOCAL","role_provider":"LOCAL"}

POST /api/v1/visits/{visitId}/recordings/{recordingId}/retranscribe
{"provider":"LOCAL","role_provider":null}

POST /api/v1/visits/{visitId}/transcript/roles/reclassify
{"provider":"LOCAL"}

POST /api/v1/visits/{visitId}/clinical-extraction/generate
{"provider":"LOCAL"}
```

### 界面拿到的字段

`GET /api/v1/visits/{visitId}/transcript`：

- `source_route`：这条转写是**哪条 ASR 路由**产出的（`DASHSCOPE` / `LOCAL` / `MIXED` / `UNKNOWN`），只用于来源标签
- `available_routes`：**角色识别**当前可用路由，如 `["DASHSCOPE","LOCAL"]`
- `route_selection_required`：`available_routes.length > 1`
- `default_route`：推荐默认选中项（默认路由可用则为默认路由，否则第一条可用路由）

`GET /api/v1/visits/{visitId}/clinical-extraction` 同上，但描述的是**信息提取**路由。

### 错误码

| 码 | HTTP | 含义 |
| --- | --- | --- |
| `INVALID_ASR_PROVIDER` | 400 | ASR 路由取值非法 |
| `INVALID_ROLE_PROVIDER` | 400 | 角色识别路由取值非法（只允许 `DASHSCOPE` / `LOCAL`） |
| `INVALID_LLM_PROVIDER` | 400 | 重判 / 提取的路由取值非法 |
| `LLM_ROUTE_UNAVAILABLE` | 409 | 请求的路由当前不可用（内网未启用 / 公网无密钥） |
| `LOCAL_LLM_EXTRACTION_DISABLED` | 503 | 内网提取未启用却走到了内网提取客户端 |
| `LOCAL_LLM_EXTRACTION_UNAVAILABLE` | 503 | 内网提取调用失败（连接、HTTP、超时） |

## 配置

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `LLM_ROLE_ROUTE` | `DASHSCOPE` | 角色识别默认路由（沿用既有配置） |
| `LLM_EXTRACTION_ROUTE` | `DASHSCOPE` | 信息提取默认路由（新增） |
| `LOCAL_LLM_ENABLED` | `false` | 是否启用内网本地 LLM 的**角色识别** |
| `LOCAL_LLM_EXTRACTION_ENABLED` | 跟随 `LOCAL_LLM_ENABLED` | 是否启用内网本地 LLM 的**信息提取** |
| `LOCAL_LLM_ENDPOINT` | `http://192.168.1.56:8080` | 本地 LLM 服务地址（两个客户端共用） |
| `LOCAL_LLM_API_KEY` | 空 | llama.cpp 未开鉴权时留空 |
| `LOCAL_LLM_ROLE_MODEL` | `/models/Ternary-Bonsai-2-27B-PQ2_0.gguf` | 角色识别模型 |
| `LOCAL_LLM_EXTRACTION_MODEL` | 同上 | 信息提取模型 |
| `LOCAL_LLM_EXTRACTION_MAX_TOKENS` | `4096` | 提取输出上限（12 个字段带证据，需要空间） |
| `LOCAL_LLM_EXTRACTION_READ_TIMEOUT_MS` | `180000` | 提取读超时（比角色识别长） |
| `LOCAL_LLM_DISABLE_THINKING` | `true` | 角色识别是否附带 `enable_thinking=false` |
| `LOCAL_LLM_EXTRACTION_DISABLE_THINKING` | 跟随上面的开关 | 提取是否附带 `enable_thinking=false` |
| `LOCAL_LLM_ROLE_RETRY_MAX_ATTEMPTS` / `..._DELAY_MS` | `2` / `200` | 本地 LLM 调用重试（两个客户端共用） |

## 数据落库与审计

| 列 | 写入内容 |
| --- | --- |
| `asr_utterance.role_provider_route` | 每句角色判断**实际**用的路由（`DASHSCOPE` / `LOCAL` / `MANUAL`） |
| `ai_job.role_provider_route` | 本次转写完成时自动角色判断使用的路由（新增列，`ADD COLUMN IF NOT EXISTS`） |
| `clinical_extraction_version.provider_route` | 本次提取实际用的路由（既有列） |

## 实测证据（`.56`，llama.cpp `b10772`）

| 场景 | 结果 |
| --- | --- |
| 角色识别（4 句真实问诊） | HTTP 200，**4/4 判对**（98/95/96/94），3.86 s，137 tokens |
| 角色识别（同请求开思考） | 11.8 s、464 tokens，结论相同 → 关思考省 2/3 时间 |
| 信息提取（5 句、12 字段严格 schema） | HTTP 200，**22.9 s**，934 tokens，`chief_complaint` 及证据逐字命中原文，`doctor_diagnosis` = 急性阑尾炎、`doctor_medication` = 静脉用头孢抗感染、`doctor_followup` = 明天复查 |
| `response_format: json_schema strict` | llama.cpp 接受并返回合规 JSON（实测） |

## 已知限制

1. **llama.cpp 的 `json_schema` 不强制 `minLength`**：模型对填不出的字段会返回
   `{"value":null,"confidence":100,"evidence":[...]}` 而不是 schema 里的 `null` 分支。
   后端 `validateField` 会因 `value` 为空**丢弃该可选字段**（`chief_complaint` 除外——它缺失会让整次提取
   以 `EXTRACTION_CHIEF_REQUIRED` 失败并允许原地重试），因此不会写脏数据，但"内网模型偶尔多返回无意义字段"
   属正常现象。
2. **模型质量**：实测内网 27B/PQ2 会把"青霉素过敏"同时填进 `allergy_history` 与 `medication_history`。
   字段区分度不如公网 qwen-plus，建议用界面上的公网/内网按钮**对同一份转写各判一次做对比**。
3. **不做模板降级**：提取失败一律保存 `FAILED` 版本并显示原因，绝不写模板兜底内容。
4. **`.78` 网关的两个端点已不再被后端调用**：`AiServiceClient.extract`
   （`/internal/clinical-extraction/generate`）与 `assignRoleAssignments`
   （`/internal/transcript/assign-roles`）在内网路由改为直连 `.56` 后已无调用方（保留代码，未删除；
   `AiServiceClient.generate` 同样无调用方）。

## 验证

```bash
# 后端
cd backend && mvn -o test        # 77 个用例（新增 LlmRoleRouterTest 12、LocalLlmExtractionClientTest 7）
# 前端
cd frontend && npm run test      # 60 个用例
```

界面手工验证（需后端带 `LLM_ROLE_ROUTE=LOCAL LOCAL_LLM_EXTRACTION_ENABLED=true` 启动）：

1. 录音上传页选"本地 ASR"+"内网 LLM"→ 转写 → 后端日志出现
   `ASR角色判断完成：…，角色LLM路由=LOCAL，降级句段数=0`；
2. 转写结果页切"公网 / 内网"再点"开始 AI 判断"→ 角色标签会按所选路由重算，
   `asr_utterance.role_provider_route` 记录实际路由；
3. 提取面板切"内网"→"生成信息提取"→ 日志出现 `内网信息提取 LLM 请求/响应`。

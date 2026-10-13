# 角色识别 LLM 通信适配器（local-llm-role-adapter）

## 定位

MedicalAI 后端里的**角色识别（speaker 识别）**是把 ASR（MOSS 匿名推理结果）得到的
`speaker_id` 与逐句文本发给一个大语言模型，由它判断每一句说话人属于
`DOCTOR`（医生）/ `PATIENT`（患者）/ `OTHER`（其他）。

本项目新增了一路**通信适配器**，让这一层可以选择把匿名推理结果发给：

- **公网 LLM（DashScope）** —— 默认，原有行为不变；
- **内网本地 LLM（llama.cpp / OpenAI 兼容）** —— 新增，例如内网部署的
  `http://192.168.1.56:8080`，避免医疗音频转写内容离开内网。

`RoleLlmClient` 是适配器契约，`DashScopeRoleClient`（公网）与 `LocalLlmRoleClient`（内网）
是两个实现，`LlmRoleRouter` 按配置选择走哪一路。两条路由共用同一套提示词与降级语义，
保证跨路由结果口径一致。

## 架构

```
             ASR 句段（index / speaker_id / text …）
                          │
                          ▼
                 LlmRoleRouter           medicalai.llm.role-route
                ┌──────────┴───────────┐   DASHSCOPE（默认）→ 公网
                ▼                      ▼   LOCAL              → 内网
     DashScopeRoleClient      LocalLlmRoleClient
     （/compatible-mode/v1/        （/v1/chat/completions，
      chat/completions）             OpenAI 兼容，llama.cpp）
                └──────────┬───────────┘
                          ▼
           逐句角色 FALLBACK（OTHER, null）· 降级不丢转写
```

- 同步角色识别：`POST /v1/chat/completions`，`stream=false`，与公网语义一致
  （按 `role-batch-size` 分批、429/5xx/解析失败重试、最终降级 `FALLBACK`）。
- 流式能力：`LocalLlmRoleClient.streamChat(messages, onDelta)` 走 SSE
  （`stream=true`，解析 `data:` 事件行，跳过 `[DONE]`，聚合全部 delta 返回）。
  角色识别本身始终用同步路径，流式方法供后续流式交互/续写场景使用。

## 路由切换

角色识别路由由环境变量 `LLM_ROLE_ROUTE` 控制（默认 `DASHSCOPE`，即保持原公网行为）：

```bash
# 切到内网本地 LLM（必须同时启用本地适配器）
LLM_ROLE_ROUTE=LOCAL LOCAL_LLM_ENABLED=true
# 保持公网（默认，无需任何配置）
LLM_ROLE_ROUTE=DASHSCOPE
```

要点：

- 未启用本地适配器（`LOCAL_LLM_ENABLED=false`，默认）时，即使路由写 `LOCAL`，
  `LocalLlmRoleClient` **也只返回降级角色且不发出任何网络请求**，绝无可能静默改走公网。
- `/roles/reclassify` 接口的 `provider` 字段（`DASHSCOPE|LOCAL`）会与当前路由校验；
  路由为公网时显式请求 `LOCAL` 会被拒绝（`LLM_ROUTE_MISMATCH`），反之亦然。

## 配置

见 `backend/src/main/resources/application.yml` 中 `medicalai.local-llm` 与 `medicalai.llm` 区块。

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `LLM_ROLE_ROUTE` | `DASHSCOPE` | 角色识别路由：`DASHSCOPE` / `LOCAL` |
| `LOCAL_LLM_ENABLED` | `false` | 是否启用内网本地 LLM 适配器 |
| `LOCAL_LLM_ENDPOINT` | `http://192.168.1.56:8080` | 本地 LLM 服务地址 |
| `LOCAL_LLM_API_KEY` | 空 | llama.cpp 未开鉴权时留空，则不发 `Authorization` 头 |
| `LOCAL_LLM_ROLE_MODEL` | `/models/Ternary-Bonsai-2-27B-PQ2_0.gguf` | 本地角色识别模型 ID |
| `LOCAL_LLM_ROLE_REVIEW_THRESHOLD` | `70` | 置信度低于此值要求医生复核 |
| `LOCAL_LLM_ROLE_BATCH_SIZE` | `40` | 每批发送的句段数量 |
| `LOCAL_LLM_CONNECT_TIMEOUT_MS` | `5000` | 连接超时（毫秒） |
| `LOCAL_LLM_READ_TIMEOUT_MS` | `120000` | 读取超时（毫秒） |
| `LOCAL_LLM_ROLE_RETRY_MAX_ATTEMPTS` | `2` | 失败重试次数上限 |
| `LOCAL_LLM_ROLE_RETRY_DELAY_MS` | `200` | 重试前等待（毫秒） |

## 行为约定

1. **逐句判断**：`speaker_id` 只是 ASR 声学聚类标签，不作整段统一贴标签。
2. **分批**：超过 `role-batch-size` 自动分批，单批失败只降级该批，不影响整个录音其余批。
3. **重试**：仅对 429 / 5xx / 网络异常 / JSON 解析异常重试，最多 `role-retry-max-attempts` 次。
4. **降级**：调用失败或返回无效时，该批全部落为 `RoleAssignment("OTHER", null, "FALLBACK")`，
   转写文本永不丢失；降级批次会记录 `role_provider_route`，可追溯。
5. **不出网**：本地路由不做任何回退到公网的操作；公网路由也不接收内网录音内容。
6. **响应格式**：请求带 `temperature=0` 与 `response_format={"type":"json_object"}`，
   解析 `/choices/0/message/content` 中的 `items` 数组并严格校验
   `index` 集合、`role` 枚举与 `confidence`（0–100 整数）。

## 直连验证（无需启动后端）

```bash
# 健康检查
curl -s http://192.168.1.56:8080/health

# 角色识别（同步）
curl -s http://192.168.1.56:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{
    "model":"/models/Ternary-Bonsai-2-27B-PQ2_0.gguf",
    "messages":[{"role":"user","content":"{\"items\":[{\"index\":0,\"speaker_id\":1,\"text\":\"请描述哪里不舒服\"},{\"index\":1,\"speaker_id\":1,\"text\":\"我腰痛两天了\"}]}"}],
    "temperature":0,
    "response_format":{"type":"json_object"}
  }'

# 流式对话（SSE）
curl -N -s http://192.168.1.56:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"/models/Ternary-Bonsai-2-27B-PQ2_0.gguf","messages":[{"role":"user","content":"你好"}],"stream":true}'
```

## 端到端验证（经后端）

### 1. 登录字段必须是 SNAKE_CASE

后端 `spring.jackson.property-naming-strategy: SNAKE_CASE`，请求与响应字段都是下划线形式：

```powershell
# 请求体写进 UTF-8 文件，避免 PowerShell + 936 代码页传中文参数出错
'{"doctor_name":"测试医生","role":"DOCTOR"}' | Set-Content -Encoding utf8NoBOM $env:TEMP\login.json
$login = curl.exe -s -X POST http://127.0.0.1:8189/api/v1/auth/demo-login `
  -H "Content-Type: application/json" --data-binary "@$env:TEMP\login.json"
$token = ($login | ConvertFrom-Json).access_token    # 是 access_token，不是 accessToken
curl.exe -s -w "`nHTTP=%{http_code}`n" http://127.0.0.1:8189/api/v1/visits `
  -H "Authorization: Bearer $token"
```

`doctorName` → 400 `VALIDATION_ERROR 请输入医生名称`；`accessToken` → `$null`（随后所有请求都变成空 Bearer）。

### 2. `/roles/reclassify` 的前置条件（不满足就到不了 LLM）

`ClinicalWorkflowService.reclassifyTranscriptRoles` 的校验顺序：

| 顺序 | 条件 | 不满足时返回 |
| --- | --- | --- |
| 1 | 接诊属于当前医生 | 404 |
| 2 | `visit.status == "ACTIVE"`（`WAITING` / `COMPLETED` 都不算） | 409 `VISIT_NOT_ACTIVE 请先开始本次接诊` |
| 3 | 病历未被确认 | 409 `RECORD_CONFIRMED` |
| 4 | 已存在转写快照 | 409 `SNAPSHOT_REQUIRED 请先完成转写` |
| 5 | `provider` 与当前路由一致 | 409 `LLM_ROUTE_MISMATCH` |
| 6 | 至少一句 `role_source != MANUAL` | 直接返回，**不发出任何 LLM 请求** |

因此必须：先 `POST /api/v1/visits/{id}/start`（`WAITING` → `ACTIVE`），且该接诊已完成过一次真实转写
（快照只能由 ASR 转写产生；`PUT /transcript` 也要求快照已存在，无法用于造第一份快照）。

### 3. 最简可行的端到端验证步骤

1. 以 `LLM_ROLE_ROUTE=LOCAL` + `LOCAL_LLM_ENABLED=true` 启动后端；
2. 新建接诊 → `POST /api/v1/visits/{id}/start` → 录音上传 → `POST /api/v1/visits/{id}/recordings/transcribe`；
3. 转写过程本身就会调用角色路由（`AsrJobWorker` 步骤 8），看后端控制台是否出现：
   `ASR角色判断完成：任务ID=…，ASR路由=…，角色LLM路由=LOCAL，句段数=…，降级句段数=0`
   （出现「本地LLM角色调用已降级」或 `降级句段数 > 0` 即表示内网 LLM 调用失败）；
4. 此时接诊仍为 `ACTIVE` 且有快照，再调 `/roles/reclassify`：
   `-d '{"provider":"LOCAL"}'` → 200；`-d '{"provider":"DASHSCOPE"}'` → 409 `LLM_ROUTE_MISMATCH`。

## 测试与构建

```bash
cd backend
mvn -q -Dtest=LocalLlmRoleClientTest,LlmRoleRouterTest,DashScopeRoleClientTest test
mvn -q test          # 全部测试
mvn -q package       # 打包
```

- `LocalLlmRoleClientTest`：同步分类、路径/模型/无鉴权头、Bearer 头、无效结果降级、
  401 单次请求、429 重试、分批、SSE 流式聚合、未启用时零请求。
- `LlmRoleRouterTest`：默认公网路由、LOCAL 派发、未启用降级、`validateRequestedRoute` 校验。
- 既有 `DashScopeRoleClientTest` 保持绿，确认公网路径行为未受影响。
# 角色识别 LLM 通信适配器（local-llm-role-adapter）

> 路由选择（界面上的"公网 / 内网"按钮、转写时指定角色路由、信息提取的同类选择）见
> [llm-route-selection.md](llm-route-selection.md)；本文件只讲角色识别这条路由本身。

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
  角色识别本身始终用同步路径，流式方法供后续流式交互/续写场景使用
  （流式方法**不**附带下面的 `enable_thinking=false`，流式交互通常本来就需要思考）。

## 一次角色识别请求里到底发了什么

ASR 只产出文本和时间，**音频不出后端**；送给本地 LLM 的是一个批次的句段数组 + 一段固定提示词：

1. ASR（本地或公网）产出句段：`text` / `start_ms` / `end_ms` / 声学聚类 `speaker_id`。
   `AsrJobWorker.roleAssignedTurns` 把它们重排成 `[{index, speaker_id, start_ms, end_ms, text}, …]`，
   `index` 从 0 连续编号（**不是** `speaker_id`；`speaker_id` 只作为上下文特征一起发过去）。
2. `LlmRoleRouter` 按 `medicalai.llm.role-route` 选路，LOCAL 交给 `LocalLlmRoleClient`。
3. 按 `role-batch-size`（默认 40）分批，**一批一次 HTTP 请求**——不是逐句一次，也不是整段一次：

```http
POST http://192.168.1.56:8080/v1/chat/completions
Content-Type: application/json

{
  "model": "/models/Ternary-Bonsai-2-27B-PQ2_0.gguf",
  "messages": [
    {"role": "system", "content": "你是医疗对话逐句角色分类器。只返回符合要求的 JSON，不输出解释或思考过程。"},
    {"role": "user",   "content": "<逐句角色判断指令> + [{\"index\":0,\"speaker_id\":1,\"start_ms\":0,\"end_ms\":3000,\"text\":\"请描述一下哪里不舒服\"}, …]"}
  ],
  "temperature": 0,
  "response_format": {"type": "json_object"},
  "chat_template_kwargs": {"enable_thinking": false}
}
```

要点：

- **系统提示词是常量**（`RoleLlmClient.SYSTEM_PROMPT`）；**逐句角色判断指令 + 本批句段 JSON 拼在唯一一条
  user 消息里**（`RoleLlmClient.ROLE_ASSIGNMENT_INSTRUCTION + mapper.writeValueAsString(turns)`）。
  没有多轮历史、没有工具调用，一次请求就是一批彼此独立的判断。
- 提示词里明确写了"`speaker_id` 只是 ASR 的声学聚类标签，不得据此、据发言顺序或句段数量猜测"，
  并要求每个 `index` 恰好返回一次、不得增加未知 `index`。
- 期望回答：`{"items":[{"index":0,"role":"DOCTOR","confidence":96}, …]}`，
  其中 `role` ∈ {DOCTOR, PATIENT, OTHER}，`confidence` 是 0–100 整数。
- 只取 `/choices/0/message/content`，解析前先剥离 `<think>…</think>`（见下节）。

## 思考（thinking）与降级

本地模型的 Qwen 系模板默认开启思考，而角色判断只要 JSON 结果，思考 token 纯属浪费。
在 `.56`（`b10772`）上用 4 句真实问诊实测：

| 请求 | 耗时 | completion_tokens | 结果 |
| --- | --- | --- | --- |
| 默认（开思考） | 11.8 s | 464（大部分是思考） | 4/4 角色判对 |
| 附带 `chat_template_kwargs.enable_thinking=false` | 4.2 s | 137 | 4/4 角色判对 |

生成速度不变（约 45 tok/s），省下的全是思考 token。因此适配器**默认**附带
`chat_template_kwargs = {"enable_thinking": false}`；置 `LOCAL_LLM_DISABLE_THINKING=false`
可去掉这个字段（服务端不支持该字段时也只是忽略）。按 `role-batch-size=40` 估算，
一批的耗时大致能压到原来的 1/2～1/3，`read-timeout-ms=120000` 因此有充足余量。

**思考混进 `content` 的风险**：`llama-server` 的 `--reasoning-format` 决定思考落在
`reasoning_content` 还是直接拼进 `content`。后者会让一份完全合法的回答变成
`<think>…</think>\n{…json…}`；适配器现在会先剥离思考块（未闭合的思考块——回答被截断——
整段丢弃并按无效结果重试/降级），所以换模型模板或改这个参数不会再造成"静默全降级"。
公网 `DashScopeRoleClient` 未做该剥离：qwen-plus 走的是非思考路径，保持既有行为不动。

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
| `LOCAL_LLM_DISABLE_THINKING` | `true` | 角色识别请求附带 `chat_template_kwargs.enable_thinking=false`，关闭本地模型思考 |
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
   解析 `/choices/0/message/content` 中的 `items` 数组（解析前先剥离 `<think>…</think>` 思考块），
   并严格校验 `index` 集合、`role` 枚举与 `confidence`（0–100 整数）。
7. **关思考**：角色识别请求默认附带 `chat_template_kwargs={"enable_thinking":false}`，详见"思考（thinking）与降级"。

## 直连验证（无需启动后端）

```bash
# 存活检查：/v1 与 /model 不是 llama.cpp 的路由，GET 它们必然是 404，别拿它判断服务死活
curl -s http://192.168.1.56:8080/health      # {"status":"ok"}
curl -s http://192.168.1.56:8080/v1/models    # 能列出模型即表示权重已加载

# 角色识别（同步）——与适配器发出的报文一致：system + user 两条消息、关思考
curl -s http://192.168.1.56:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{
    "model":"/models/Ternary-Bonsai-2-27B-PQ2_0.gguf",
    "messages":[
      {"role":"system","content":"你是医疗对话逐句角色分类器。只返回符合要求的 JSON，不输出解释或思考过程。"},
      {"role":"user","content":"逐句判断以下句段的角色，每个 index 恰好返回一次。\n[{\"index\":0,\"speaker_id\":1,\"start_ms\":0,\"end_ms\":3000,\"text\":\"请描述哪里不舒服\"},{\"index\":1,\"speaker_id\":1,\"start_ms\":3200,\"end_ms\":7000,\"text\":\"我腰痛两天了\"}]"}
    ],
    "temperature":0,
    "response_format":{"type":"json_object"},
    "chat_template_kwargs":{"enable_thinking":false}
  }'
# 期望：{"choices":[{"message":{"content":"{\"items\":[{\"index\":0,\"role\":\"DOCTOR\",…}]}"}}]}

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
  401 单次请求、429 重试、分批、SSE 流式聚合、未启用时零请求、默认关思考且可配置关闭、
  思考混入 `content` 时仍能解析、思考块被截断时按无效结果降级。
- `LlmRoleRouterTest`：默认公网路由、LOCAL 派发、未启用降级、`validateRequestedRoute` 校验。
- 既有 `DashScopeRoleClientTest` 保持绿，确认公网路径行为未受影响。

## 部署与排错

`.56` 的连通性由**后端所在机器**决定（后端未必和 ASR、LLM 同机），部署时从后端机器实测 `/health`。

| 现象 | 原因与处理 |
| --- | --- |
| 转写任务**直接失败**，提示「公网角色识别未配置 DASHSCOPE_API_KEY，本地 ASR 结果无法继续处理」 | `LLM_ROLE_ROUTE=LOCAL` 但 `LOCAL_LLM_ENABLED` 仍是 `false`：`AsrJobWorker` 在步骤 8 之前就会拦住（这是**响亮失败**，不是静默降级）。两个变量必须一起设 |
| 转写成功，但角色全是 `OTHER`（`source=FALLBACK`），界面「待核对」句段等于全部句段 | `LOCAL_LLM_ENABLED=true` 但 `.56` 连不上 / 超时 / 返回不合法：降级只影响本批。用日志「本地LLM角色调用已降级…异常类型=…」定性 |
| 手动点「开始 AI 判断」返回成功，但角色仍全是 `OTHER` | 与上一条同因。手动重判路径没有转写路径的那道守卫，未启用时也会安静地返回全 FALLBACK |
| 日志「本地LLM角色调用已降级…异常类型=ConnectException / HTTP状态=null」 | 后端机器连不到 `LOCAL_LLM_ENDPOINT`：确认 `llama-server` 用 `--host 0.0.0.0` 启动（只绑 `127.0.0.1` 时只有 `.56` 本机能连），并放行 8080 |
| `curl http://192.168.1.56:8080/v1` 或 `/model` 返回 404 | **正常现象**，llama.cpp 没有这两个路由；用 `GET /health`、`GET /v1/models` 判断 |
| `curl http://192.168.1.78:8000/health` 返回 404 | 内网 AI 服务的健康检查是 `GET /healthz`，不是 `/health` |
| 同一段对话换路由后角色口径不一致 | 仓库里角色识别有两条独立实现：本适配器（Java 直连 `.56`）与 `.78` 网关的 `/internal/transcript/assign-roles`（Python，当前无 Java 调用方、提示词也不同）。同一时间只用一条，别让两者并存 |
| 每批耗时明显偏高 | 先确认 `enable_thinking` 已关闭；再检查 `role-batch-size` 是否过大（单批越长，单批失败时降级的句段越多） |
| 后端日志「内网信息提取 LLM 结构化语法降级…httpStatus=500」，提取面板曾报「内网信息提取模型返回异常（HTTP 500）」 | `.56` 的 llama.cpp 故障：推理退化成只输出 `/`（连原始 `/completion` 也一样），strict `json_schema` + 关思考时该 token 撞上语法采样器 → `Unexpected empty grammar stack after accepting piece: / (14)`。客户端已自动改用 `json_object` 重发一次，但**救不了退化推理**；服务端排查顺序、验收命令与修复选项见 [llm56-grammar-500-server-handoff.md](llm56-grammar-500-server-handoff.md)，现场自检用 `tools/llm56_grammar_500_repro.py` |
| 转写/提取「接口返回 200 但结果为空或报未返回有效 JSON」 | 别把 HTTP 200 当成功：`.56` 退化态下 `你好` 也是 200，内容是 `/` 串。先跑 `tools/llm56_grammar_500_repro.py`（退出码 2 = 推理已退化） |

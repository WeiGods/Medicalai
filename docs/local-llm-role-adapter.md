# 公网/本地角色判断与信息提取

一次接诊的 AI 链路由首次成功 ASR 来源锁定，角色判断、历史角色重判和信息提取均跟随来源。

```text
公网 ASR → DashScopeRoleClient → DashScopeClinicalExtractionClient
本地 ASR → LocalLlmRoleClient → AiServiceClient → 本地 AI 网关 → 本地 LLM
```

## 路由规则

- `visit.processing_route` 创建时为 NULL，正式 ASR 结果成功入库时与句段、快照在同一事务内锁定为 `DASHSCOPE` 或 `LOCAL`。失败不会提前锁定。
- 后续录音、补录和重转写必须使用原路由。不同路由返回 `ASR_ROUTE_MISMATCH`；活动任务的重复请求也必须匹配任务来源。
- 角色判断使用 ASR 任务来源；`DASHSCOPE_ROLE_V2` 兼容映射为公网。
- 角色重判和信息提取使用当前快照的来源表。省略 `provider` 自动跟随；显式指定其他路由返回 `LLM_ROUTE_MISMATCH`。
- 混合来源返回 `LLM_ROUTE_MIXED`，无法确认的来源返回 `LLM_ROUTE_UNKNOWN`，均不会调用模型。前端提示“请统一当前接诊的 ASR 路由并重新转写”。
- 历史成功录音全部为公网或未填写来源时按公网兼容；全部为 LOCAL 时按本地兼容；混合或无法安全推导时阻断。
- `LLM_ROLE_ROUTE` 默认仍为 DASHSCOPE，仅供没有业务来源的旧调用兼容，不能覆盖正常任务或快照来源。

## 两类本地服务的配置

API 容器的两个地址可以指向不同服务：

```bash
LOCAL_ASR_BASE_URL=http://local-asr:8000
INTERNAL_AI_SERVICE_BASE_URL=http://local-ai-gateway:8000
```

本地 AI 网关使用以下配置访问本地 OpenAI 兼容 LLM：

```bash
LLM_API_BASE=http://local-llm:8080/v1
LLM_API_KEY=
LLM_MODEL=your-local-model
EXTRACTION_LLM_MODEL=your-local-extraction-model
```

`LLM_API_KEY` 在本地模型不要求鉴权时可以留空。`EXTRACTION_LLM_MODEL` 留空时使用 `LLM_MODEL`。`LLM_API_BASE` 必须指向本地部署的模型；网关不会自行切换到 DashScope。

| 环境变量 | 默认 | 用途 |
| --- | --- | --- |
| `INTERNAL_AI_CONNECT_TIMEOUT_MS` | 5000 | API 连接本地网关的超时 |
| `INTERNAL_AI_READ_TIMEOUT_MS` | 120000 | API 等待本地网关响应的超时 |
| `LOCAL_LLM_ROLE_BATCH_SIZE` | 40 | 本地角色适配器每批句段数量 |
| `LOCAL_LLM_ROLE_RETRY_MAX_ATTEMPTS` | 2 | 本地角色适配器最大尝试次数 |
| `LOCAL_LLM_ROLE_RETRY_DELAY_MS` | 200 | 角色重试间隔 |
| `LLM_TIMEOUT_S` | 30 | 网关角色模型请求超时 |
| `EXTRACTION_LLM_TIMEOUT_S` | 30 | 网关信息提取模型请求超时 |

`LOCAL_LLM_ENABLED`、`LOCAL_LLM_ENDPOINT`、`LOCAL_LLM_API_KEY`、`LOCAL_LLM_ROLE_MODEL` 仅供独立 `streamChat()` 和旧嵌入式直连构造使用。Spring 医疗业务实例固定经过网关，不需要 `LOCAL_LLM_ENABLED=true`。

## 调用与失败行为

本地角色适配器按批调用 `POST /internal/transcript/assign-roles`，保留原 index、重试和响应校验。模型失败或输出无效时只保存 `OTHER/null/FALLBACK`，`role_provider_route` 仍为 LOCAL，不调用公网客户端。

本地信息提取调用 `POST /internal/clinical-extraction/generate`。模型不可用、快照哈希不匹配或证据无效时保存 FAILED，不切公网、不自动生成病历。公网角色与提取客户端同样不会切换本地。

角色枚举、提示词、人工确认、质量门禁、原文证据和病历确认流程保持现有语义。`streamChat()` 仍是独立 SSE 能力，不参与医疗业务路由。

角色提示词唯一来源为 `backend/src/main/resources/ai/role-assignment.json`，Java 从 classpath 加载，AI 镜像将同一资源复制到 `ai_service/role-assignment.json`。本地网关校验模型返回的角色、index 和 confidence 后再补 `source=LLM`，失败仍返回 FALLBACK。

## 历史异常接诊恢复与并发保护

普通转写不会放开跨路由限制。历史混合/未知来源且无任何病历签署历史的 ACTIVE 接诊，可通过工作台“统一路由并恢复整份转写”操作恢复：

```text
POST /api/v1/visits/{id}/recordings/rebuild-transcript
{"provider":"LOCAL","source_snapshot_id":"原快照 UUID","acknowledge_draft_reset":true}
```

后端确认不存在活动 ASR/录音会话，检查原音频可读取后，在事务内清理转写、人工角色、提取和未签署病历草稿，保留所有原音频并创建一个整份转写任务。接诊已经锁定时只能沿用原路由。该清理不可撤销，必须由医生确认重新核对所有草稿。相同原快照和路由的重复提交返回已有任务，不再次清理。

`visit.recovery_route` 在恢复首次成功前保存目标路由；失败后在音频页使用普通转写重试，省略 provider 也会沿用目标。首次成功与 `processing_route` 锁定、`recovery_route` 清空在同一事务提交。

工作线程在提交、轮询、结果读取及角色调用前核对租约与接诊边界，冲突任务落 FAILED；失败更新仅作用于该任务拥有的 PROCESSING 录音。模型网络调用不持有接诊行锁。角色重判冻结快照 ID/哈希、全部句段及展示状态，写回时重新核对；信息提取的成功和失败写入均核对当前已采用快照及接诊/录音状态。旧结果返回 `SOURCE_CHANGED`，不会更新当前提取版本。

## 验证

配置好本地 ASR、网关与 LLM 后，新建并开始接诊、上传录音，提交 ASR 时指定本地来源：

```text
POST /api/v1/visits/{id}/recordings/transcribe
{"provider":"LOCAL"}
```

成功后接诊详情应返回 `processing_route: LOCAL`；转写返回 `source_route: LOCAL`，角色句段返回 `role_provider_route: LOCAL`。角色重判和提取请求可以不带请求体：

```text
POST /api/v1/visits/{id}/transcript/roles/reclassify
POST /api/v1/visits/{id}/clinical-extraction/generate
```

提取成功后 `provider_route/source_route` 都应为 LOCAL。显式改为 DASHSCOPE 应被拒绝。公网接诊用相同流程指定 DASHSCOPE，并保持全程公网。

数据库启动脚本 `backend/src/main/resources/db/migration/schema.sql` 幂等增加字段及约束，回填可确认的历史接诊。历史混合接诊需要先核对与清理来源，不允许通过 provider 参数强制选择一路模型。

```bash
cd backend
mvn -q test
# PostgreSQL 租约测试需按 AsrLeasePostgresTest 配置独立测试库。
cd ../frontend
npm test -- --run
npm run build
cd ..
python -m unittest discover -s tests -p test_local_role_gateway.py
```

新增测试覆盖来源分发、跨路由拒绝、真实 JDBC 路由锁定与事务回滚、网关分批/重试/FALLBACK、本地提取失败落库和前端禁用/提示。

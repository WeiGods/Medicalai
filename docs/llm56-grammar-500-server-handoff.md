# `.56` 本地 LLM 故障交接（两层：推理退化 + 语法 500）

> 交接对象：负责 `192.168.1.56` 上 llama.cpp 容器的一方。
> 客户侧（后端 `LocalLlmExtractionClient`）的兜底已经做好，见文末「客户端已做的部分」；
> **本文只讲服务端要修什么**。

## 一句话现状

`.56` 上的 `llama-server`（`b10772-e8fd209c9`，模型 `/models/Ternary-Bonsai-2-27B-PQ2_0.gguf`）
现在**对所有输入都只吐出 `/` 这一个 token**（原始 `/completion` 也一样），这是 **Layer 1**；
当请求同时带 strict `json_schema` 与 `enable_thinking=false` 时，这串 `/` 会让语法采样器抛异常变成
**HTTP 500**，这是 **Layer 2**。**先修 Layer 1，再谈 Layer 2。**

## 现场

| 项 | 值 |
| --- | --- |
| 地址 | `http://192.168.1.56:8080` |
| 构建 | `b10772-e8fd209c9`（`GET /props` → `build_info`） |
| 模型 | `/models/Ternary-Bonsai-2-27B-PQ2_0.gguf`（PQ2_0 - 2.13 bpw，n_ctx 131072，total_slots 2） |
| 服务状态 | `GET /health` → `{"status":"ok"}`；`GET /v1/models` 正常列出模型；`is_sleeping=false` |

HTTP 健康检查全绿，但**输出是垃圾**——这就是为什么「健康检查 + 模型列表正常」和「合成数据能返回 200」
都会给人已经好了的错觉。

## 第 1 步：恢复推理（Layer 1，最高优先级）

### 验收命令

```bash
# 绕过 chat 模板、reasoning 与语法约束，直接看模型本体
curl -s http://192.168.1.56:8080/completion \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"法国的首都是","n_predict":8,"temperature":0,"cache_prompt":false}'
```

* **故障态（实测）**：`content` = `"////////"`（换 `1+1=`、`你好` 等完全不同提示词也一样）
* **正常态（期望）**：`content` 里应有 `巴黎`（或至少是有意义的文本，不是整串 `/`）

```bash
# 双方都验证过：两个 slot 同时请求，输出同样退化 → 不是“某个 slot 被污染”
curl -s http://192.168.1.56:8080/slots
```

### 排查清单（按性价比排序）

1. **重启容器**，重启后立刻重跑上面的 `/completion`。若恢复正常 → 记录重启时间，继续观察是否复发
   （复发说明有外部诱因，往下查）。
2. **模型文件完整性**：`ls -l` 大小、`sha256sum` 与原始下载比对；确认没有被替换成半截文件/另一个量化版本，
   确认挂载的是 `/models/Ternary-Bonsai-2-27B-PQ2_0.gguf` 本身。
3. **容器启动日志**：模型加载阶段的 `n_tensors`、tensor 覆盖（`--override-tensor`/`-ot` 之类）、
   CUDA 警告、显存不足回退；确认**只有一个进程/容器**占用这张卡。
4. **GPU 健康**：`nvidia-smi -q -d ECC`、`nvidia-smi -q | grep -i xid`、`dmesg | grep -i xid`；
   卡上出现 Xid/ECC 错误时，logits 会整体崩坏成这种“只会吐一个 token”的形态。
5. **量化本身**：`PQ2_0 / 2.13 bpw` 是极低比特量化。用同一份权重在另一台机器加载做对照；
   若处处退化，考虑换 Q4/Q5 级别权重再评估（这会同时影响提取质量，需要产品侧确认）。
6. **启动参数是否被改过**：`-c/-np/--chat-template*/--reasoning-*` 与文档记录的部署参数对比。

## 第 2 步：确认 Layer 2 是否仍然复现

Layer 1 修好（`/completion` 正常）之后，跑仓库里的诊断脚本：

```bash
python tools/llm56_grammar_500_repro.py                    # 默认打 http://192.168.1.56:8080
python tools/llm56_grammar_500_repro.py --endpoint http://192.168.1.56:8080
```

退出码：`0` = Layer 2 复现（推理已正常，语法缺陷仍在）；`1` = 两层都正常；`2` = 推理仍退化（回到第 1 步）。

脚本做三件事：

1. **Layer 1 体检**：原始 `/completion` 是否为有意义文本；
2. `strict json_schema + enable_thinking=false` 是否 500；
3. 两个对照：去掉 `enable_thinking=false`、以及换成 `json_object` 是否 200 **且内容是可用 JSON**。

### Layer 2 的实测事实（我在当前服务上得到的证据）

* 报错原文（服务端日志）：

  ```
  E slot iterate: id  0 | task 3145 | got exception: Unexpected empty grammar stack after accepting piece: / (14)
  E srv  send_error: task id = 3145, error: got exception: Unexpected empty grammar stack after accepting piece: / (14)
  ```

* 客户端看到：`HTTP 500`，`{"error":{"code":500,"message":"got exception: Unexpected empty grammar stack after accepting piece: / (14)","type":"server_error"}}`
* 抛异常位置：上游 `src/llama-grammar.cpp` 的 `llama_grammar_accept_token()`（`grammar.stacks.empty()` 分支）。
* **确定性**：`temperature=0` 下同报文每次必现；前缀被缓存后**0.16 s 就失败**，所以客户端重试毫无意义。
* **与内容无关**：换对话、去掉输入里的 `/`、把 12 字段 Schema 缩到 1 个字段、删掉 `minLength`，
  都一样报 `/ (14)`。
* **采样参数救不了**：`repeat_penalty` 1.1/1.3、`frequency_penalty` 0.3、`presence_penalty` 0.5、
  `dry_multiplier` 0.8、`temperature` 0.2 全部仍 500 —— 这个 token 是被“强制”出来的，不是模型挑的。
* 只要**去掉** `enable_thinking=false`（或换成 `json_object`），同一段对话就不再 500
  （但见下面的“200 不等于成功”）。

## 第 3 步：Layer 2 的修法

**根因方向**：`enable_thinking=false` 会让 Qwen 系模板把 `<think>\n\n</think>\n\n` 之类内容
**预填充进生成提示词**，而 llama-server 又把这段生成提示词喂给了输出格式语法
（output-format grammar），语法状态从一开始就与真实 token 流不一致；一旦模型吐出 `/`，
掩码已无可选 token，最终接受失败 → 空栈 → 抛异常 → 500。

上游对应工作：

* `ggml-org/llama.cpp#29066` *common: do not prefill the generation prompt into output-format grammar*（**尚未合并**）
* 相关：`#29006`（JSON Schema grammar 撞上 chat-template 控制符）、`#30419`（`json_schema` 下 `? (30)` 崩栈）、
  `#14413`、`#26787`

可选做法（按推荐顺序）：

1. **升级/打补丁**：换成包含 `#29066` 修复的构建（或把该补丁 cherry-pick 到当前容器镜像里）。
   这是唯一真正治本的选项。
2. **临时规避（需按第 2 步脚本验证）**：让服务端负责“不思考”，客户端就不再传
   `chat_template_kwargs.enable_thinking=false`，从而不产生那段生成提示词预填充：
   启动时加 `--reasoning-budget 0`（`LLAMA_ARG_THINK_BUDGET=0`，语义“immediate end of thinking”）。
   注意：**只把客户端的 `enable_thinking=false` 去掉是不够的**——实测那样模型会把全部输出预算烧在
   思考里（768/768 token，`content` 为空），必须让服务端在 0 预算下立刻收束思考。
3. **`--no-prefill-assistant`（待验证，可能不适用）**：该开关的语义是“最后一条消息是 assistant 时才生效”，
   而我们的请求最后一条是 user，预计无效；可作为交叉验证项。

## 验收标准

* [ ] `GET /completion` 对 3 个无关提示词返回有意义文本（不再出现整串 `/`）
* [ ] `python tools/llm56_grammar_500_repro.py` 不再返回 `2`
* [ ] strict `json_schema + enable_thinking=false` 返回 **200 且 `content` 是可用 JSON**（不是 500，也不是 `/` 串）
* [ ] 后端真实链路（内网路由：转写 → 角色判断 → 信息提取）连续跑通 3 次，无 5xx、无 `EXTRACTION_INVALID_RESPONSE`
* [ ] 上述结果贴进本文档或对应 issue

## 两个坑，别踩

1. **别用 HTTP 200 当成功判据**：退化态下 `你好` 也是 200，`content` 却是 `/` 串。
   必须检查 (`choices[0].message.content` + `reasoning_content`) 是否是有意义文本 / 可用 JSON。
2. **别靠重试**：`temperature=0` 下这是确定性故障，重试只会更快地再次失败
   （前缀命中缓存时 0.16 s 就抛）。

## 服务端修好之后：项目侧的端到端验收（由本项目侧执行，服务端不用管）

1. `python tools/llm56_grammar_500_repro.py`：期望不再出现 `DEGENERATE`，退出码为 **1**，
   三个变体都是 200 且 `可用 JSON=True`。
2. 按 [local-llm-role-adapter.md](local-llm-role-adapter.md) 的「端到端验证」启动后端
   （`LLM_ROLE_ROUTE=LOCAL`、`LOCAL_LLM_ENABLED=true`、`LOCAL_LLM_EXTRACTION_ENABLED=true`）。
3. 真实链路跑一遍：转写 → 后端日志应出现
   `ASR角色判断完成：…，角色LLM路由=LOCAL，降级句段数=0`；
   生成信息提取 → 日志应出现
   `内网信息提取 LLM 响应: … responseFormat=json_schema …`。
   **只要出现「结构化语法降级」或 `responseFormat=json_object`，说明还在降级，等于没修好。**
4. 连续 3 次：无 5xx、无 502「未返回有效 JSON」、无 `EXTRACTION_INVALID_RESPONSE`。
5. 把三份证据（自检脚本输出、转写角色日志、提取响应日志）贴回本文档。

## 客户端已做的部分（供对照，不需要服务端改）

* 内网提取客户端在 strict Schema 收到 **5xx** 时，会自动改用
  `response_format={"type":"json_object"}` 再发一次（字段完整性仍由 `ClinicalExtractionService` 校验），
  日志记「内网信息提取 LLM 结构化语法降级」；这只把「500」变成「可控的解析失败/成功」，
  **救不了退化推理**，Layer 1 仍必须由服务端解决。
* 相关代码：`backend/src/main/java/com/medicalai/service/LocalLlmExtractionClient.java`；
  测试：`backend/src/test/java/com/medicalai/service/LocalLlmExtractionClientTest.java`。
* `.78` 的 Python 网关 `ai_service/server.py` 也加了同款降级：`_post_chat_completion_with_schema_degrade()`
  用在 `/internal/clinical-extraction/generate` 与 `/internal/transcript/assign-roles` 两条 strict Schema 路径上，
  测试 `tests/test_ai_service_llm_schema_degrade.py`。
  **但这两条路径不发送 `chat_template_kwargs.enable_thinking=false`**，所以在当前退化态下它们看到的不是 500，
  而是「200 + `content` 为空/`/` 串」→ `_json_content` 解析失败 → 提取报 502「未返回有效 JSON」、
  角色识别整批落 `FALLBACK`。这也说明：**Java 侧报 500、Python 侧报 502，是同一个 Layer 1 的两种表象。**

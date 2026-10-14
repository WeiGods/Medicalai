# MedicalAI 运维部署说明

## 三个镜像的关系

系统由三个可部署镜像组成，均已推送到 Docker Hub（`mouyu/medicalai`）：

| 镜像 | 角色 | 大小 | 说明 |
| --- | --- | --- | --- |
| `mouyu/medicalai:frontend-latest` | Web 前端 | ~100 MB | Vue 构建产物 + nginx，对外端口 5173 |
| `mouyu/medicalai:backend-latest` | 业务后端 API | ~525 MB | Spring Boot（Java 21），对外端口 8080 |
| `mouyu/medicalai:local_ai-latest` | AI 推理服务 | ~55 GB | FastAPI + vLLM + FunASR，**内置全部 ASR 模型**，对外端口 8000 |

`local_ai-latest` 是在基础镜像 `mouyu/medicalai:vllm-0.26`（CUDA + PyTorch + vLLM + FunASR，约 47.6 GB）之上，通过 `ai_service/Dockerfile` 叠加 ffmpeg、AI 服务依赖、业务源码和模型文件构建的。基础镜像只用于重新构建 AI 镜像，日常部署不需要单独拉取或运行它。

```text
mouyu/medicalai:vllm-0.26            （基础镜像，仅构建时使用）
        |
        +-- ai_service/Dockerfile
            +-- ffmpeg + AI 服务依赖
            +-- model.py / serve_realtime_ws.py / ai_service/
            +-- 内置模型（见下节）
        |
        v
mouyu/medicalai:local_ai-latest      （AI 推理服务容器 "ai"）

mouyu/medicalai:backend-latest       （业务 API 容器 "api"，独立构建）
mouyu/medicalai:frontend-latest      （前端容器 "frontend"，独立构建）
```

三个容器的关系：浏览器访问 frontend，frontend 把 `/api` 反向代理给 backend，backend 通过 `AI_SERVICE_BASE_URL=http://ai:8000` 调用 AI 服务完成录音转写和病历生成。backend 还依赖外部 Postgres 和 MinIO 存储录音文件。

## 内置模型（拉取即用，无需下载）

`local_ai-latest` 已把全部模型打进镜像，启动后**不访问 ModelScope / HuggingFace**：

| 模型 | 镜像内路径 | 用途 |
| --- | --- | --- |
| Fun-ASR-Nano-2512 | `/workspace/Fun-ASR/Fun-ASR-Nano-2512` | ASR 主模型（vLLM 推理，含 Qwen3-0.6B） |
| fsmn-vad | `/workspace/Fun-ASR/models/fsmn-vad` | 流式语音活动检测 |
| speech_eres2netv2 | `/workspace/Fun-ASR/models/speech_eres2netv2_sv_zh-cn_16k-common` | 说话人分离 |

注意：容器内 `/workspace/.cache` 通常会挂载 `medicalai_models` 卷。模型本体在镜像层里，不依赖这个卷；但 vLLM 的 startup plan 和 torch compile cache 写在这个卷里。清空后不会导致功能失败，只会退回 2～3 分钟冷启动。保留缓存的热重启通常明显更快。

拉取后或清空缓存卷后的首次启动仍需约 2～3 分钟把模型加载进 GPU；缓存保留时重启通常约 70～90 秒。加载期间 `/healthz` 返回 `models_loaded: false`，业务接口返回 503，属正常现象。

## 环境要求

- NVIDIA GPU 一块（显存建议 ≥ 8 GB，默认 `ASR_GPU_MEM_UTIL=0.8`），宿主机需安装 NVIDIA 驱动和 nvidia-container-toolkit。
- 磁盘 ≥ 60 GB（AI 镜像解压后约 55 GB，加上其他镜像和业务数据）。
- 可访问的 Postgres（业务库）和 MinIO（录音存储）；compose 里也自带一个 demo MinIO。

## 部署步骤（推荐：docker compose）

1. 拉取镜像（frontend / backend 较小，AI 镜像较大请耐心等待）：

   ```bash
   docker pull mouyu/medicalai:frontend-latest
   docker pull mouyu/medicalai:backend-latest
   docker pull mouyu/medicalai:local_ai-latest
   ```

2. 在 `docker-compose.yml` 同目录准备 `.env`，至少包含：

   ```bash
   MINIO_ACCESS_KEY=xxx
   MINIO_SECRET_KEY=xxx
   DASHSCOPE_API_KEY=xxx          # 长录音异步转写用
   # 可选：直连自建大模型服务时配置；不配则走 DashScope qwen-plus
   # LLM_API_BASE=
   # LLM_API_KEY=
   # LLM_MODEL=
   ```

3. 根据实际环境修改 `docker-compose.yml` 中的 `DB_URL`（Postgres 地址）和 `MINIO_API_ENDPOINT`（MinIO 地址）。

4. 启动：

   ```bash
   docker compose up -d
   ```

   compose 已为三个服务配置了 `image:` 字段：镜像存在时直接使用，不存在时自动拉取；开发者需要本地构建时加 `--build` 即可。

5. 验证：

   ```bash
   curl http://localhost:8000/healthz
   # 等待 2~3 分钟后应返回 {"status":"ok","models_loaded":true}

   curl http://localhost:8080/actuator/health   # 或浏览器打开前端
   # 前端: http://localhost:5173
   ```

## 常用运维操作

```bash
# 查看 AI 服务加载日志（确认 "All models ready!"）
docker compose logs -f ai

# 确认 GPU 在容器内可用
docker run --rm --gpus all --entrypoint nvidia-smi mouyu/medicalai:local_ai-latest

# AI 服务卡住/模型加载失败时重启
docker compose restart ai

# 升级镜像（重新 pull 后重建容器，业务数据在卷和外部库中，不受影响）
docker compose pull
docker compose up -d

# 释放旧镜像占用的磁盘
docker image prune -f
```

## AI 服务可调参数（环境变量）

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `ASR_DEVICE` | `cuda:0` | 推理设备，CPU 环境可设 `cpu`（很慢，仅排障用） |
| `ASR_GPU_MEM_UTIL` | `0.8` | vLLM 显存占用比例，需与其他 GPU 任务共存时调低 |
| `ASR_MAX_MODEL_LEN` | `2048` | vLLM 最大序列长度 |
| `ASR_ENFORCE_EAGER` | `0` | `1` 时关闭 torch compile / CUDA Graph，启动略快但推理明显变慢；仅排障用 |
| `ASR_CUDAGRAPH_CAPTURE_SIZES` | `1,2,4,8,16,32,64,128` | CUDA Graph 抓取的 batch size 档位；缩小档位可降低启动耗时。设为空使用 vLLM 默认 |
| `ASR_MODEL_PT_MMAP` | `1` | `1` 时用 mmap 读取 Fun-ASR-Nano 的 `model.pt`，避免启动时一次性物化 2GB checkpoint |
| `ASR_DISABLE_SPK` | `0` | 设为 `1` 关闭说话人分离 |
| `ASR_LANGUAGE` | 自动 | 指定转写语言（zh/en/ja 等） |

## 维护者：如何重建带模型的 AI 镜像

模型暂存在仓库根目录 `docker-models/`（已被 `.gitignore` 和 `.dockerignore` 规则处理，不会进 git）：

```text
docker-models/
  Fun-ASR-Nano-2512/                     <- FunAudioLLM/Fun-ASR-Nano-2512
  fsmn-vad/                              <- iic/speech_fsmn_vad_zh-cn-16k-common-pytorch
  speech_eres2netv2_sv_zh-cn_16k-common/ <- iic/speech_eres2netv2_sv_zh-cn_16k-common
```

目录为空时可从 ModelScope 重新下载填充，然后构建并推送：

```bash
python -c "from modelscope import snapshot_download; \
  snapshot_download('FunAudioLLM/Fun-ASR-Nano-2512'); \
  snapshot_download('iic/speech_fsmn_vad_zh-cn-16k-common-pytorch'); \
  snapshot_download('iic/speech_eres2netv2_sv_zh-cn_16k-common')"

docker build -f ai_service/Dockerfile \
  -t mouyu/medicalai:local_ai-latest -t medicalai-ai:latest .
docker push mouyu/medicalai:local_ai-latest
```

`ASR_MODEL`、`ASR_HUB` 环境变量仍然有效：显式设置 `ASR_MODEL` 可覆盖内置模型路径，用于灰度测试新模型。

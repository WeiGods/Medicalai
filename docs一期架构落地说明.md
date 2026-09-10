# MedicalAI 一期架构落地说明

当前代码已增加 `backend/`、`frontend/`、`ai_service/` 三个可独立启动的骨架。Java 服务负责登录、患者和接诊；Python 服务提供 Mock ASR/病历生成接口；Vue 前端提供演示登录和患者/接诊工作台。

## 本地启动

```bash
docker compose up -d postgres minio
cd backend && mvn spring-boot:run
cd frontend && npm install && npm run dev
cd ai_service && pip install -r requirements.txt && uvicorn main:app --reload --port 8000
```

浏览器访问 `http://localhost:5173`，输入任意医生名称即可登录。

## 数据库

`backend/src/main/resources/db/migration/V1__initial_schema.sql` 已包含医生、登录会话、患者、接诊、录音、录音会话、ASR utterance、最终对话、病历版本、确认记录和 AI 任务表。

当前登录和患者列表使用内存 Mock，数据库迁移已准备好；下一步应把认证、患者同步和接诊写入 Repository，再接入 `medicalai_minimal_loop_v2` 的状态机规则。

## 当前边界

- 登录为演示模式，不验证密码和 OA token。
- ASR 与病历生成返回 Mock 结果，不加载 FunASR/vLLM。
- 未接入真实文件上传、WebSocket 音频流和 DOCX/PDF 导出。
- Docker Compose 保留仓库原有 `funasr` 开发容器；正式演示使用新增的 `frontend/api/ai/postgres/minio` 服务。

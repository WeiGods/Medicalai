FROM mouyu/medicalai:vllm-0.26

WORKDIR /workspace/Fun-ASR

RUN apt-get update -qq \
    && apt-get install -y -qq --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

COPY ai_service/requirements.txt /tmp/ai-requirements.txt
RUN pip install --no-cache-dir -r /tmp/ai-requirements.txt

COPY model.py serve_realtime_ws.py ./
COPY ai_service ./ai_service

ENV HF_HOME=/workspace/.cache/huggingface \
    MODELSCOPE_CACHE=/workspace/.cache/modelscope

EXPOSE 8000

CMD ["uvicorn", "ai_service.server:app", "--host", "0.0.0.0", "--port", "8000"]

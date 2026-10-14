FROM mouyu/medicalai:vllm-0.26

WORKDIR /workspace/Fun-ASR

RUN apt-get update -qq \
    && apt-get install -y -qq --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

COPY ai_service/requirements.txt /tmp/ai-requirements.txt
RUN pip install --no-cache-dir -r /tmp/ai-requirements.txt

# FunASR Nano ships audio/CTC components inside a 2GB checkpoint. mmap avoids
# materializing the whole archive during each container startup.
RUN python - <<'PY'
from pathlib import Path

p = Path("/root/conda/lib/python3.12/site-packages/funasr/models/fun_asr_nano/inference_vllm.py")
old = 'checkpoint = torch.load(model_pt, map_location="cpu")'
new = 'checkpoint = torch.load(model_pt, map_location="cpu", mmap=os.environ.get("ASR_MODEL_PT_MMAP", "1") != "0")'
s = p.read_text()
if old in s:
    s = s.replace(old, new)
elif new not in s:
    raise RuntimeError("FunASR Nano model.pt load site not found")
p.write_text(s)
PY

COPY model.py serve_realtime_ws.py streaming_speaker_turns.py offline_asr_structure.py ./
COPY ai_service ./ai_service
COPY 热词列表 ./

# Bake ASR / VAD / speaker models so a pulled image runs fully offline.
# The ASR dir must stay at /workspace/Fun-ASR/Fun-ASR-Nano-2512: that is the
# local-model path resolved by ai_service/server.py before any hub download.
COPY docker-models/Fun-ASR-Nano-2512 ./Fun-ASR-Nano-2512
COPY docker-models/fsmn-vad ./models/fsmn-vad
COPY docker-models/speech_eres2netv2_sv_zh-cn_16k-common ./models/speech_eres2netv2_sv_zh-cn_16k-common

ENV HF_HOME=/workspace/.cache/huggingface \
    MODELSCOPE_CACHE=/workspace/.cache/modelscope \
    VLLM_CACHE_ROOT=/workspace/.cache/vllm \
    VLLM_ENABLE_STARTUP_PLAN=1 \
    ASR_MODEL_PT_MMAP=1

EXPOSE 8000

CMD ["uvicorn", "ai_service.server:app", "--host", "0.0.0.0", "--port", "8000"]

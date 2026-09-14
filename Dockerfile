ARG BASE_IMAGE=xxxxrt666/torch-base:cu12.6-full
FROM ${BASE_IMAGE}

WORKDIR /workspace/Fun-ASR

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt \
    && pip install --no-cache-dir -U modelscope
# vLLM 0.26 pairs with torch 2.11 (pip will replace the base torch) and ships
# its own flash attention; the base image's flash_attn build breaks under the
# new torch, so remove it. numpy is downgraded last to satisfy funasr.
RUN pip install --no-cache-dir "vllm==0.26.0"
RUN pip install --no-cache-dir "numpy<2" \
    && pip uninstall -y flash-attn || true

ENV HF_HOME=/workspace/.cache/huggingface

CMD ["bash"]

ARG BASE_IMAGE=xxxxrt666/torch-base:cu12.6-full
FROM ${BASE_IMAGE}

WORKDIR /workspace/Fun-ASR

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt \
    && pip install --no-cache-dir -U modelscope
# vLLM 0.26 与 torch 2.11 配套（pip 会替换基础镜像中的 torch），并自带 Flash Attention；
# 基础镜像中的 flash_attn 构建在新版 torch 下无法使用，因此先移除。最后降级 numpy 以满足 FunASR 依赖。
RUN pip install --no-cache-dir "vllm==0.26.0"
RUN pip install --no-cache-dir "numpy<2" \
    && pip uninstall -y flash-attn || true

ENV HF_HOME=/workspace/.cache/huggingface

CMD ["bash"]

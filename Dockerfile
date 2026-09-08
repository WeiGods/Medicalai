FROM xxxxrt666/torch-base:cu12.6-full

WORKDIR /workspace/Fun-ASR

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt \
    && pip install --no-cache-dir -U modelscope

ENV HF_HOME=/workspace/.cache/huggingface

CMD ["bash"]

#!/usr/bin/env python3
"""FastAPI inference service for the MedicalAI business backend.

HTTP /internal endpoints consumed by the Java backend (AiServiceClient),
WebSocket /ws/asr migrated from serve_realtime_ws.py for realtime streaming,
and a shared Fun-ASR-Nano vLLM engine with streaming VAD and diarization.
"""

import asyncio
import math
from pathlib import Path
import json
import logging
import os
import subprocess
import sys
import threading
import time
from types import SimpleNamespace

import httpx
import numpy as np
import torch

REPO_ROOT = Path(__file__).resolve().parents[1]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

if os.environ.get("ASR_OFFLINE") == "1":
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["TRANSFORMERS_OFFLINE"] = "1"

import serve_realtime_ws as rt

from fastapi import FastAPI, HTTPException, UploadFile, WebSocket
from fastapi.concurrency import run_in_threadpool

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("ai_service")

SAMPLE_RATE = 16000


def _env(name, default=None):
    value = os.environ.get(name)
    return value if value not in (None, "") else default


def resolve_model():
    explicit = _env("ASR_MODEL")
    if explicit:
        return explicit, _env("ASR_HUB", "ms")
    local = REPO_ROOT / "Fun-ASR-Nano-2512"
    if local.is_dir():
        return str(local), "ms"
    return "FunAudioLLM/Fun-ASR-Nano-2512", _env("ASR_HUB", "ms")


class ServiceSettings:
    def __init__(self):
        model, hub = resolve_model()
        self.args = SimpleNamespace(
            model=model,
            vad_model=_env("ASR_VAD_MODEL", "fsmn-vad"),
            spk_model=_env("ASR_SPK_MODEL", "iic/speech_eres2netv2_sv_zh-cn_16k-common"),
            offline=_env("ASR_OFFLINE", "0") == "1",
            hub=hub,
            device=_env("ASR_DEVICE", "cuda:0"),
            language=_env("ASR_LANGUAGE"),
            dtype=_env("ASR_DTYPE", "bf16"),
            tensor_parallel_size=int(_env("ASR_TENSOR_PARALLEL", "1")),
            gpu_memory_utilization=float(_env("ASR_GPU_MEM_UTIL", "0.8")),
            max_model_len=int(_env("ASR_MAX_MODEL_LEN", "2048")),
            enforce_eager=_env("ASR_ENFORCE_EAGER", "0") == "1",
            cudagraph_capture_sizes=_env("ASR_CUDAGRAPH_CAPTURE_SIZES"),
            partial_window_sec=float(_env("ASR_PARTIAL_WINDOW_SEC", "15.0")),
            disable_spk=_env("ASR_DISABLE_SPK", "0") == "1",
            hotword_file=_env("HOTWORD_FILE", "热词列表"),
            use_context=_env("ASR_USE_CONTEXT", "0") == "1",
            vad_max_end_silence_ms=int(_env("ASR_VAD_MAX_END_SILENCE_MS", "0")),
            vad_silence_schedule=_env("ASR_VAD_SILENCE_SCHEDULE") or None,
            vad_speech_noise_thres=float(_env("ASR_VAD_SPEECH_NOISE_THRES", "0.6")),
            merge_gap_ms=int(_env("ASR_MERGE_GAP_MS", "500")),
            no_merge_turns=_env("ASR_DISABLE_MERGE", "0") == "1",
            spk_threshold=float(_env("ASR_SPK_THRESHOLD", "0.6")),
            spk_merge_thr=float(_env("ASR_SPK_MERGE_THR", "0.78")),
            spk_min_seg_ms=int(_env("ASR_SPK_MIN_SEG_MS", "1000")),
            decode_interval=float(_env("ASR_DECODE_INTERVAL", "0.48")),
            min_rms=float(_env("ASR_MIN_RMS", "0.004")),
            drop_filler_ms=int(_env("ASR_DROP_FILLER_MS", "1500")),
            vad_max_single_segment_ms=int(_env("ASR_VAD_MAX_SINGLE_SEGMENT_MS", "30000")),
            preset_spk_num=int(_env("ASR_PRESET_SPK_NUM", "2")),
            mode=_env("ASR_MODE", "realtime"),
        )
        self.llm_api_base = _env("LLM_API_BASE")
        self.llm_api_key = _env("LLM_API_KEY")
        self.llm_model = _env("LLM_MODEL", "qwen-plus")
        self.llm_timeout_s = float(_env("LLM_TIMEOUT_S", "30"))
        self._models_started = False
        self.lock = threading.Lock()

    def start_models_background(self):
        if self._models_started:
            return
        self._models_started = True

        def load():
            try:
                rt.load_models(self.args)
                _warmup_asr_engine()
                logger.info("Models ready")
            except Exception:
                self._models_started = False
                logger.error("Model loading failed", exc_info=True)

        threading.Thread(target=load, daemon=True, name="model-loader").start()

    @property
    def models_ready(self):
        return rt._vllm_engine is not None


settings = ServiceSettings()
app = FastAPI(title="MedicalAI Inference", version="0.1.0")


@app.on_event("startup")
async def startup():
    settings.start_models_background()


def require_models():
    if not settings.models_ready:
        raise HTTPException(status_code=503, detail="模型尚未加载完成，请稍后重试")


def _warmup_asr_engine():
    """Prime vLLM audio/CUDA paths before serving the first real request."""
    if rt._vllm_engine is None:
        return
    seconds = torch.arange(SAMPLE_RATE // 2, dtype=torch.float32) / SAMPLE_RATE
    tone = 0.05 * torch.sin(2 * math.pi * 440.0 * seconds)
    try:
        with settings.lock:
            rt._vllm_engine.generate(
                inputs=[tone],
                hotwords=rt._asr_kwargs.get("hotwords"),
                language=rt._asr_kwargs.get("language"),
                max_new_tokens=16,
            )
        logger.info("ASR engine warmup complete")
    except Exception:
        logger.exception("ASR engine warmup failed")


def decode_audio_to_pcm(data: bytes, suffix: str) -> np.ndarray:
    """Decode any container (webm/opus, mp3, wav...) to 16k mono int16 PCM."""
    import tempfile

    with tempfile.NamedTemporaryFile(suffix=suffix or ".bin", delete=False) as tmp:
        tmp.write(data)
        source = tmp.name
    try:
        proc = subprocess.run(
            ["ffmpeg", "-v", "error", "-i", source, "-vn", "-ac", "1", "-ar",
             str(SAMPLE_RATE), "-f", "s16le", "pipe:1"],
            capture_output=True, timeout=600,
        )
        if proc.returncode != 0 or not proc.stdout:
            raise RuntimeError(proc.stderr.decode("utf-8", "ignore")[-500:])
        return np.frombuffer(proc.stdout, dtype=np.int16).copy()
    finally:
        try:
            os.unlink(source)
        except OSError:
            pass


def _transcribe_sync(pcm: np.ndarray) -> list[dict]:
    """Run the streaming pipeline (VAD + vLLM decode + diarization) on a file."""
    require_models()
    session = _build_session()
    byte_chunk = session.chunk_samples * 2
    buffer = pcm.astype(np.int16).tobytes()
    with settings.lock:
        for i in range(0, len(buffer), byte_chunk):
            session.add_audio(buffer[i:i + byte_chunk])
            session.decode(False)
        result = session.decode(True)

    utterances = _sentences_to_utterances(result.get("sentences", []))
    if not utterances and result.get("partial"):
        utterances.append({"role": "UNKNOWN", "text": result["partial"],
                           "start_ms": 0, "end_ms": result.get("duration_ms", 0)})
    logger.info("Transcribed audio: utterances=%d, duration_ms=%s",
                len(utterances), result.get("duration_ms"))
    return utterances


def _sentences_to_utterances(result_sentences):
    utterances = []
    for sent in result_sentences or []:
        text = (sent.get("text") or "").strip()
        if not text:
            continue
        spk = sent.get("spk")
        utterances.append({
            "role": f"SPEAKER_{spk}" if spk is not None else "UNKNOWN",
            "speaker_id": int(spk) if spk is not None else None,
            "text": text,
            "start_ms": int(sent.get("start") or 0),
            "end_ms": int(sent.get("end") or 0),
        })
    return utterances


def _build_session():
    vad = rt.build_streaming_vad(rt._vad_model, settings.args)
    spk_tracker = None if settings.args.disable_spk else rt.HybridSpeakerTracker(
        rt._spk_model, settings.args.device,
        threshold=settings.args.spk_threshold,
        merge_thr=settings.args.spk_merge_thr,
        min_segment_ms=settings.args.spk_min_seg_ms,
    )
    return rt.RealtimeASRSession(
        rt._vllm_engine, dict(rt._asr_kwargs), vad, spk_tracker=spk_tracker,
        partial_window_sec=settings.args.partial_window_sec,
        merge_gap_ms=settings.args.merge_gap_ms,
        merge_enabled=not settings.args.no_merge_turns,
        use_context=settings.args.use_context,
        min_rms=settings.args.min_rms,
        drop_filler_ms=settings.args.drop_filler_ms,
    )


def _transcribe_sync_offline(pcm: np.ndarray) -> list[dict]:
    """Batch pipeline: full-file VAD, one batched vLLM call, then diarization.

    Batched decoding fills the GPU instead of decoding realtime-sized chunks,
    and the model sees each complete utterance in a single pass.
    """
    require_models()
    audio_float = pcm.astype(np.float32) / 32768.0
    vad = rt.build_streaming_vad(rt._vad_model, settings.args)

    with settings.lock:
        speaking = []
        for start_ms, end_ms in vad.process(torch.from_numpy(audio_float)):
            seg_audio = audio_float[int(start_ms * SAMPLE_RATE / 1000):int(end_ms * SAMPLE_RATE / 1000)]
            if len(seg_audio) >= 1600 and (
                settings.args.min_rms <= 0 or rt._audio_rms(seg_audio) >= settings.args.min_rms
            ):
                speaking.append((int(start_ms), int(end_ms), seg_audio))
        if not speaking:
            return []

        results = rt._vllm_engine.generate(
            inputs=[torch.from_numpy(seg_audio) for _, _, seg_audio in speaking],
            hotwords=rt._asr_kwargs.get("hotwords"),
            language=rt._asr_kwargs.get("language"),
            max_new_tokens=512,
        )

        sentences = []
        for (start_ms, end_ms, _), result in zip(speaking, results):
            text = rt._clean_asr_text((result or {}).get("text", ""))
            text, _ = rt.detect_and_fix_hallucination(text)
            short_filler = (
                settings.args.drop_filler_ms > 0
                and (end_ms - start_ms) < settings.args.drop_filler_ms
                and rt._is_filler_only(text)
            )
            if text.strip() and not short_filler:
                sentences.append({"text": text.strip(), "start": start_ms, "end": end_ms})

        if settings.args.disable_spk or not sentences:
            return _sentences_to_utterances(sentences)

        tracker = rt.HybridSpeakerTracker(
            rt._spk_model, settings.args.device,
            threshold=settings.args.spk_threshold,
            merge_thr=settings.args.spk_merge_thr,
            min_segment_ms=settings.args.spk_min_seg_ms,
        )

        # Official FunASR diarization recipe: slide a fixed window over the
        # VAD segments for embeddings, then cluster once, globally. The stock
        # ClusterBackend returns a single cluster when fed fewer than 20
        # embeddings, so shrink the window until we have enough to cluster.
        chunks = _sliding_chunks(speaking, seg_dur=1.5, seg_shift=0.75)
        for seg_dur, seg_shift in ((1.0, 0.25), (0.75, 0.25)):
            if len(chunks) >= 20:
                break
            chunks = _sliding_chunks(speaking, seg_dur=seg_dur, seg_shift=seg_shift)
        chunks = [ch for ch in chunks if (ch[1] - ch[0]) * 1000 >= 300]
        if not chunks:
            return _sentences_to_utterances(sentences)

        speech_list = [ch[2] for ch in chunks]
        spk_res = tracker.spk_model.generate(input=speech_list, cache={}, is_final=True)
        embeddings = torch.cat([r["spk_embedding"] for r in spk_res], dim=0).detach().cpu()
        preset_spk_num = settings.args.preset_spk_num or None
        labels = tracker.cluster_backend(embeddings, oracle_num=preset_spk_num)
        logger.info(
            "Offline diarization: %d chunks (preset_spk_num=%s), cluster labels=%s",
            len(chunks), preset_spk_num, np.asarray(labels).tolist(),
        )
        sv_output = tracker.postprocess(
            [[ch[0], ch[1], None] for ch in chunks], None,
            np.asarray(labels), embeddings.numpy(),
        )
        assigned = []
        for sentence in sentences:
            current = dict(sentence)
            tracker.distribute_spk([current], sv_output)
            assigned.extend(tracker._try_split(current, sv_output, {}, min_split_s=3.0))
        sentences = [
            s for s in assigned
            if not rt._is_sparse_text(s["text"], s["end"] - s["start"])
        ]
        return _sentences_to_utterances(sentences)


def _sliding_chunks(speaking, seg_dur, seg_shift):
    """Slide a fixed window across VAD segments (official sv_chunk recipe)."""
    chunk_len = int(seg_dur * SAMPLE_RATE)
    chunk_shift = int(seg_shift * SAMPLE_RATE)
    chunks = []
    for start_ms, _end_ms, audio in speaking:
        start_s = start_ms / 1000.0
        last_end = 0
        for offset in range(0, len(audio), chunk_shift):
            end = min(offset + chunk_len, len(audio))
            if end <= last_end:
                break
            last_end = end
            begin = max(0, end - chunk_len)
            chunks.append([
                start_s + begin / SAMPLE_RATE,
                start_s + end / SAMPLE_RATE,
                audio[begin:end],
            ])
    return chunks


async def transcribe_pcm(pcm: np.ndarray) -> list[dict]:
    return await run_in_threadpool(_transcribe_sync, pcm)


async def transcribe_pcm_offline(pcm: np.ndarray) -> list[dict]:
    return await run_in_threadpool(_transcribe_sync_offline, pcm)


SAMPLE_DIALOGUE = [
    {"role": "DOCTOR", "text": "您好，今天主要有什么不舒服？", "start_ms": 0, "end_ms": 3000},
    {"role": "PATIENT", "text": "最近三天反复头痛，主要是右侧太阳穴附近，有点胀痛。", "start_ms": 3200, "end_ms": 9000},
    {"role": "DOCTOR", "text": "每次头痛持续多久？有没有恶心、呕吐或者其他症状？", "start_ms": 9300, "end_ms": 14000},
    {"role": "PATIENT", "text": "每次大概半小时到一小时，休息后能好一点。偶尔有点恶心，没有呕吐。", "start_ms": 14200, "end_ms": 21000},
    {"role": "DOCTOR", "text": "之前有类似情况吗？平时有高血压或其他疾病吗？", "start_ms": 21300, "end_ms": 26000},
    {"role": "PATIENT", "text": "以前偶尔也会头痛，最近睡眠不太好。没有高血压，也没有其他慢性病。", "start_ms": 26200, "end_ms": 33000},
]


@app.get("/healthz")
async def healthz():
    return {"status": "ok", "models_loaded": settings.models_ready}


@app.get("/internal/asr/sample")
async def asr_sample(patient_name: str = ""):
    prefix = f"{patient_name}，" if patient_name else ""
    utterances = [dict(item) for item in SAMPLE_DIALOGUE]
    if prefix:
        utterances[1]["text"] = prefix + utterances[1]["text"]
    return {"jobId": f"sample-{int(time.time() * 1000)}", "status": "SUCCEEDED",
            "utterances": utterances}


@app.post("/internal/asr/transcribe")
async def asr_transcribe(file: UploadFile, mode: str = None):
    requested = (mode or settings.args.mode or "realtime").strip().lower()
    if requested not in {"realtime", "offline"}:
        raise HTTPException(status_code=400, detail="mode 仅支持 realtime / offline")
    data = await file.read()
    if not data:
        raise HTTPException(status_code=400, detail="音频内容为空")
    suffix = Path(file.filename or "").suffix or ".webm"
    try:
        pcm = await run_in_threadpool(decode_audio_to_pcm, data, suffix)
    except Exception as e:
        logger.error("Audio decode failed: %s", e)
        raise HTTPException(status_code=400, detail="音频解码失败，请确认文件格式") from e
    if pcm.size < SAMPLE_RATE // 2:
        raise HTTPException(status_code=400, detail="音频过短")
    transcribe = transcribe_pcm_offline if requested == "offline" else transcribe_pcm
    utterances = await transcribe(pcm)
    return {"jobId": f"asr-{int(time.time() * 1000)}", "status": "SUCCEEDED",
            "mode": requested, "utterances": utterances}


RECORD_PROMPT = """你是经验丰富的内科医生助理。根据对话和患者信息生成门诊病历，只输出 JSON，字段：
name, gender, age, phone, chief(主诉), present(现病史), past(既往史), opinion(初步诊断与处理意见),
medication(用药建议), followup(随访建议), doctor(接诊医生), date(日期 YYYY-MM-DD)。"""


def dialogue_text(dialogue, patient):
    patient_desc = ", ".join(f"{k}:{v}" for k, v in (patient or {}).items() if v)
    lines = []
    for item in dialogue or []:
        role = "医生" if str(item.get("role", "")).upper() == "DOCTOR" else "患者"
        lines.append(f"{role}：{item.get('text', '')}")
    return f"患者信息：{patient_desc}\n\n对话记录：\n" + "\n".join(lines)


def template_record(dialogue, patient):
    patient = patient or {}
    patient_lines = [str(item.get("text", "")).strip() for item in (dialogue or [])
                     if str(item.get("role", "")).upper() != "DOCTOR" and str(item.get("text", "")).strip()]
    doctor_lines = [str(item.get("text", "")).strip() for item in (dialogue or [])
                    if str(item.get("role", "")).upper() == "DOCTOR" and str(item.get("text", "")).strip()]
    chief = patient_lines[0] if patient_lines else "（待医生补充）"
    present = "；".join(patient_lines) or "（待医生补充）"
    return {
        "name": patient.get("name", ""), "gender": patient.get("gender", ""),
        "age": patient.get("age", ""), "phone": patient.get("phone", ""),
        "chief": chief, "present": present,
        "past": "无特殊既往史（由医生核对补充）",
        "opinion": "；".join(doctor_lines) or "（初步诊断与处理意见待医生补充）",
        "medication": "（用药建议待医生补充）",
        "followup": "（随访建议待医生补充）",
        "doctor": patient.get("doctor", ""), "date": time.strftime("%Y-%m-%d"),
    }


async def llm_generate_record(dialogue, patient):
    if not settings.llm_api_base:
        return None
    payload = {
        "model": settings.llm_model,
        "messages": [
            {"role": "system", "content": RECORD_PROMPT},
            {"role": "user", "content": dialogue_text(dialogue, patient)},
        ],
        "temperature": 0.2,
    }
    headers = {}
    if settings.llm_api_key:
        headers["Authorization"] = f"Bearer {settings.llm_api_key}"
    try:
        async with httpx.AsyncClient(timeout=settings.llm_timeout_s) as client:
            resp = await client.post(
                settings.llm_api_base.rstrip("/") + "/chat/completions",
                json=payload, headers=headers)
            resp.raise_for_status()
            content = resp.json()["choices"][0]["message"]["content"]
    except Exception as e:
        logger.error("LLM generation failed, fallback to template: %s", e)
        return None
    text = content.strip()
    if text.startswith("```"):
        text = text.strip("`")
        if text.startswith("json"):
            text = text[4:]
    try:
        record = json.loads(text)
        if isinstance(record, dict):
            record.setdefault("date", time.strftime("%Y-%m-%d"))
            return record
    except json.JSONDecodeError:
        logger.error("LLM returned non-JSON content, fallback to template")
    return None


@app.post("/internal/medical-record/generate")
async def medical_record_generate(body: dict):
    dialogue = body.get("dialogue") or []
    patient = body.get("patient") or {}
    snapshot_hash = body.get("snapshot_hash", "")
    record = await llm_generate_record(dialogue, patient)
    status = "SUCCEEDED"
    if record is None:
        record = await run_in_threadpool(template_record, dialogue, patient)
        status = "TEMPLATE_FALLBACK"
    return {"jobId": f"gen-{int(time.time() * 1000)}", "status": status,
            "sourceSnapshotHash": snapshot_hash, "record": record}

@app.post("/internal/transcript/assign-roles")
async def assign_roles(body: dict):
    turns = body.get("turns") or []
    speakers = sorted({str(t.get("speaker_id")) for t in turns if t.get("speaker_id") is not None})
    roles = {speaker: "OTHER" for speaker in speakers}
    if settings.llm_api_base and speakers:
        prompt = "将以下说话人按上下文映射为 DOCTOR、PATIENT、OTHER，只返回 JSON 对象，例如 {\"0\":\"DOCTOR\"}。\n" + json.dumps(turns, ensure_ascii=False)
        try:
            async with httpx.AsyncClient(timeout=settings.llm_timeout_s) as client:
                resp = await client.post(settings.llm_api_base.rstrip("/") + "/chat/completions",
                    json={"model": settings.llm_model, "messages":[{"role":"system","content":"你是医疗对话角色分类器。不要根据编号或发言顺序猜测；不确定返回 OTHER。"},{"role":"user","content":prompt}],"temperature":0},
                    headers={"Authorization": f"Bearer {settings.llm_api_key}"} if settings.llm_api_key else {})
                content = resp.json()["choices"][0]["message"]["content"].strip().strip('`')
                if content.startswith("json"): content = content[4:]
                parsed = json.loads(content)
                for key, value in parsed.items():
                    if str(key) in roles and str(value).upper() in {"DOCTOR", "PATIENT", "OTHER"}: roles[str(key)] = str(value).upper()
        except Exception:
            pass
    return {"roles": roles}


@app.websocket("/ws/asr")
async def ws_asr(websocket: WebSocket):
    """Realtime streaming endpoint migrated from serve_realtime_ws.handle_client."""
    await websocket.accept()
    if not settings.models_ready:
        await websocket.send_json({"event": "error", "message": "模型尚未加载完成"})
        await websocket.close()
        return

    session = _build_session()
    decode_interval = settings.args.decode_interval
    last_decode_time = 0.0
    logger.info("Realtime client connected: %s", websocket.client)

    def work(func, *args):
        with settings.lock:
            return func(*args)

    try:
        while True:
            message = await websocket.receive()
            if message.get("type") == "websocket.disconnect":
                break
            if message.get("text") is not None:
                cmd = message["text"].strip()
                if cmd.upper() == "START":
                    session.reset()
                    session.is_active = True
                    await websocket.send_json({"event": "started"})
                elif cmd.upper().startswith("HOTWORDS:"):
                    hotwords = [w.strip() for w in cmd[9:].split(",") if w.strip()]
                    session.asr_kwargs = dict(session.asr_kwargs, hotwords=hotwords)
                    await websocket.send_json({"event": "hotwords_set", "hotwords": hotwords})
                elif cmd.upper().startswith("LANGUAGE:"):
                    lang = cmd[9:].strip() or None
                    session.asr_kwargs = dict(session.asr_kwargs, language=lang)
                    await websocket.send_json({"event": "language_set", "language": lang})
                elif cmd.upper() == "STOP":
                    if session.is_active and session.total_samples > 0:
                        result = await asyncio.to_thread(work, session.decode, True)
                        await websocket.send_json(result)
                        session.is_active = False
                    await websocket.send_json({"event": "stopped"})
            elif message.get("bytes") is not None and session.is_active:
                await asyncio.to_thread(work, session.add_audio, message["bytes"])
                now = time.time()
                if now - last_decode_time >= decode_interval and session.should_decode():
                    result = await asyncio.to_thread(work, session.decode, False)
                    await websocket.send_json(result)
                    last_decode_time = now
    except Exception as e:
        logger.info("Realtime session ended: %s", e)
        try:
            await websocket.close()
        except Exception:
            pass

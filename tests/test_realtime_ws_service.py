import asyncio
import importlib.util
import sys
import threading
import time
import types
from pathlib import Path

import numpy as np
import torch


SERVICE_PATH = Path(__file__).resolve().parents[1] / "serve_realtime_ws.py"


def load_service_module(monkeypatch):
    for package_name in (
        "funasr",
        "funasr.models",
        "funasr.models.fsmn_vad_streaming",
    ):
        package = types.ModuleType(package_name)
        package.__path__ = []
        monkeypatch.setitem(sys.modules, package_name, package)

    dynamic_vad_stub = types.ModuleType("funasr.models.fsmn_vad_streaming.dynamic_vad")
    dynamic_vad_stub.DynamicStreamingVAD = object
    monkeypatch.setitem(
        sys.modules,
        "funasr.models.fsmn_vad_streaming.dynamic_vad",
        dynamic_vad_stub,
    )
    monkeypatch.setitem(
        sys.modules,
        "websockets",
        types.SimpleNamespace(
            exceptions=types.SimpleNamespace(ConnectionClosed=Exception),
            serve=lambda *args, **kwargs: None,
        ),
    )

    module_name = "serve_realtime_ws_under_test"
    sys.modules.pop(module_name, None)
    spec = importlib.util.spec_from_file_location(module_name, SERVICE_PATH)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


class SilentVad:
    current_speech_start = None

    def feed(self, audio, is_final=False):
        return []

    def reset(self):
        pass


class SegmentVad(SilentVad):
    def __init__(self, sample_rate, segment_end_sample):
        self.sample_rate = sample_rate
        self.segment_end_sample = segment_end_sample
        self.total_samples = 0

    def feed(self, audio, is_final=False):
        self.total_samples += len(audio)
        if self.total_samples == self.segment_end_sample:
            end_ms = int(self.total_samples * 1000 / self.sample_rate)
            return [[end_ms - 1000, end_ms]]
        return []


class DummyTokenizer:
    def encode(self, text):
        return []

    def decode(self, token_ids, skip_special_tokens=True):
        return ""


class DummyEngine:
    def __init__(self):
        self._engine = types.SimpleNamespace(tokenizer=DummyTokenizer())
        self.input_lengths = []

    def generate(self, inputs, **kwargs):
        self.input_lengths.extend(len(audio) for audio in inputs)
        return [{"text": "hello"}]


def test_two_hour_session_keeps_audio_bounded_and_duration_absolute(monkeypatch):
    module = load_service_module(monkeypatch)
    sample_rate = 10
    session = module.RealtimeASRSession(
        vllm_engine=DummyEngine(),
        asr_kwargs={},
        vad=SilentVad(),
        sample_rate=sample_rate,
        chunk_ms=1000,
        audio_lookback_sec=5,
    )
    one_second = (np.full(sample_rate, 2000, dtype=np.int16)).tobytes()

    for _ in range(2 * 60 * 60):
        session.add_audio(one_second)

    assert session.total_samples == 2 * 60 * 60 * sample_rate
    assert len(session.audio_buffer) <= 5 * sample_rate
    assert session.audio_buffer_start_sample == session.total_samples - len(session.audio_buffer)
    assert session._build_response(is_final=False)["duration_ms"] == 2 * 60 * 60 * 1000


def test_completed_segment_uses_absolute_offsets_after_audio_compaction(monkeypatch):
    module = load_service_module(monkeypatch)
    sample_rate = 16000
    vad = SegmentVad(sample_rate=sample_rate, segment_end_sample=11 * sample_rate)
    engine = DummyEngine()
    session = module.RealtimeASRSession(
        vllm_engine=engine,
        asr_kwargs={},
        vad=vad,
        sample_rate=sample_rate,
        chunk_ms=1000,
        audio_lookback_sec=2,
    )
    one_second = (np.full(sample_rate, 2000, dtype=np.int16)).tobytes()

    for _ in range(11):
        session.add_audio(one_second)

    # VAD-confirmed segments are deferred to the next decode() cycle.
    assert engine.input_lengths == []
    assert session.locked_sentences == []

    session.decode(is_final=False)

    assert engine.input_lengths == [sample_rate]
    assert session.locked_sentences == [{"text": "hello", "start": 10000, "end": 11000}]
    assert session._build_response(is_final=False)["duration_ms"] == 11000
    assert session.audio_buffer_start_sample > 0


def test_partial_decode_uses_recent_window(monkeypatch):
    module = load_service_module(monkeypatch)

    class ActiveVad(SilentVad):
        current_speech_start = 0

    sample_rate = 16000
    session = module.RealtimeASRSession(
        vllm_engine=DummyEngine(),
        asr_kwargs={},
        vad=ActiveVad(),
        sample_rate=sample_rate,
        partial_window_sec=2,
    )
    session.audio_buffer = np.arange(sample_rate * 5, dtype=np.float32)
    session.total_samples = len(session.audio_buffer)

    audio, start_ms = session.get_partial_decode_audio()

    assert start_ms == 3000
    np.testing.assert_array_equal(audio, session.audio_buffer[-sample_rate * 2:])


def test_final_decode_releases_audio_without_resetting_duration(monkeypatch):
    module = load_service_module(monkeypatch)
    sample_rate = 10
    for sample_count in (5, 100):
        session = module.RealtimeASRSession(
            vllm_engine=DummyEngine(),
            asr_kwargs={},
            vad=SilentVad(),
            sample_rate=sample_rate,
            chunk_ms=1000,
            audio_lookback_sec=5,
        )
        session.add_audio(np.zeros(sample_count, dtype=np.int16).tobytes())

        response = session.decode(is_final=True)

        assert response["duration_ms"] == sample_count * 100
        assert len(session.audio_buffer) == 0
        assert session.audio_buffer_start_sample == session.total_samples


def test_speaker_history_and_identity_state_have_hard_limits(monkeypatch):
    utils_stub = types.ModuleType("funasr.models.campplus.utils")
    utils_stub.sv_chunk = lambda segments: segments

    def postprocess(segments, vad_segments, labels, embeddings, return_spk_center=False):
        output = [[segment[0], segment[1], int(label)] for segment, label in zip(segments, labels)]
        centers = torch.stack(
            [embeddings[labels == label].mean(0) for label in sorted(set(labels.tolist()))]
        )
        return (output, centers) if return_spk_center else output

    def distribute_spk(sentences, speaker_segments):
        for sentence in sentences:
            sentence["spk"] = int(speaker_segments[-1][2])
        return sentences

    utils_stub.postprocess = postprocess
    utils_stub.distribute_spk = distribute_spk
    cluster_stub = types.ModuleType("funasr.models.campplus.cluster_backend")

    class FakeClusterBackend:
        def __init__(self, merge_thr):
            pass

        def to(self, device):
            return self

        def __call__(self, embeddings, oracle_num=None):
            return np.zeros(len(embeddings), dtype=np.int64)

    cluster_stub.ClusterBackend = FakeClusterBackend
    monkeypatch.setitem(sys.modules, "funasr.models.campplus.utils", utils_stub)
    monkeypatch.setitem(sys.modules, "funasr.models.campplus.cluster_backend", cluster_stub)
    module = load_service_module(monkeypatch)

    class FakeSpeakerModel:
        def generate(self, input, **kwargs):
            return [{"spk_embedding": torch.tensor([[1.0, 0.0]])} for _ in input]

    tracker = module.HybridSpeakerTracker(
        spk_model=FakeSpeakerModel(),
        device="cpu",
        max_history_chunks=3,
        max_speakers=2,
    )
    tracker.sv_chunk = lambda segments: segments
    tracker.cluster_backend = lambda embeddings, oracle_num=None: np.zeros(
        len(embeddings), dtype=np.int64
    )

    for index in range(6):
        sentence = {"text": f"segment {index}", "start": index * 1000, "end": (index + 1) * 1000}
        tracker.assign_streaming(
            np.ones(16000, dtype=np.float32),
            index,
            index + 1,
            sentence,
        )

    tracker._map_cluster_centers(torch.tensor([[1.0, 0.0], [0.0, 1.0]]), update=True)
    tracker._map_cluster_centers(torch.tensor([[-1.0, 0.0]]), update=True)

    assert len(tracker.all_chunks) == 3
    assert len(tracker.all_embeddings) == 3
    assert all(len(chunk) == 2 for chunk in tracker.all_chunks)
    assert len(tracker.speaker_centers) == 2

    finalized = tracker.finalize(
        [{"text": "abcdef", "start": 0, "end": 6000, "spk": 1}]
    )
    assert finalized == [
        {"text": "abc", "start": 0, "end": 3000, "spk": 1},
        {"text": "def", "start": 3000, "end": 6000, "spk": 0},
    ]


def test_finalize_uses_direct_cosine_for_short_speaker_history(monkeypatch):
    utils_stub = types.ModuleType("funasr.models.campplus.utils")
    utils_stub.sv_chunk = lambda segments: segments
    utils_stub.distribute_spk = lambda sentences, speaker_segments: sentences

    def postprocess(segments, vad_segments, labels, embeddings, return_spk_center=False):
        output = [[segment[0], segment[1], int(label)] for segment, label in zip(segments, labels)]
        centers = torch.stack(
            [embeddings[labels == label].mean(0) for label in sorted(set(labels.tolist()))]
        )
        return (output, centers) if return_spk_center else output

    utils_stub.postprocess = postprocess
    cluster_stub = types.ModuleType("funasr.models.campplus.cluster_backend")
    cluster_stub.ClusterBackend = lambda merge_thr: types.SimpleNamespace(
        to=lambda device: None,
        __call__=lambda embeddings, oracle_num=None: (_ for _ in ()).throw(AssertionError("short history must not cluster")),
    )
    monkeypatch.setitem(sys.modules, "funasr.models.campplus.utils", utils_stub)
    monkeypatch.setitem(sys.modules, "funasr.models.campplus.cluster_backend", cluster_stub)
    module = load_service_module(monkeypatch)

    class FakeSpeakerModel:
        def __init__(self):
            self.calls = 0

        def generate(self, input, **kwargs):
            center = [1.0, 0.0] if self.calls in (0, 2) else [0.0, 1.0]
            self.calls += len(input)
            return [{"spk_embedding": torch.tensor([center])} for _ in input]

    tracker = module.HybridSpeakerTracker(FakeSpeakerModel(), "cpu", max_history_chunks=8)
    tracker.sv_chunk = lambda segments: segments

    for index, expected_speaker in enumerate((0, 1, 0)):
        sentence = {"text": f"segment {index}", "start": index * 1000, "end": (index + 1) * 1000}
        tracker.assign_streaming(
            np.ones(16000, dtype=np.float32), index, index + 1, sentence
        )
        assert sentence["spk"] == expected_speaker

    assert [row[2] for row in tracker._cluster_recent(update_centers=False)] == [0, 1, 0]


def test_handler_is_responsive_and_serializes_shared_model_work(monkeypatch):
    module = load_service_module(monkeypatch)

    class BlockingSession:
        active_workers = 0
        max_active_workers = 0
        worker_lock = threading.Lock()

        def __init__(self, *args, **kwargs):
            self.is_active = False

        def reset(self):
            pass

        def add_audio(self, message):
            with self.worker_lock:
                type(self).active_workers += 1
                type(self).max_active_workers = max(
                    type(self).max_active_workers,
                    type(self).active_workers,
                )
            try:
                time.sleep(0.2)
            finally:
                with self.worker_lock:
                    type(self).active_workers -= 1

        def should_decode(self):
            return False

    class FakeWebSocket:
        remote_address = ("127.0.0.1", 12345)

        def __init__(self):
            self.messages = iter(["START", b"audio"])
            self.sent = []

        def __aiter__(self):
            return self

        async def __anext__(self):
            try:
                return next(self.messages)
            except StopIteration as error:
                raise StopAsyncIteration from error

        async def send(self, message):
            self.sent.append(message)

    monkeypatch.setattr(module, "load_models", lambda args: (object(), {}, object(), None))
    monkeypatch.setattr(module, "DynamicStreamingVAD", lambda model, **kwargs: object())
    monkeypatch.setattr(module, "RealtimeASRSession", BlockingSession)

    async def exercise_handler():
        stop = False
        gaps = []

        async def ticker():
            previous = asyncio.get_running_loop().time()
            while not stop:
                await asyncio.sleep(0.005)
                current = asyncio.get_running_loop().time()
                gaps.append(current - previous)
                previous = current

        ticker_task = asyncio.create_task(ticker())
        await asyncio.sleep(0.01)
        args = types.SimpleNamespace(
            device="cpu",
            decode_interval=0.48,
            disable_spk=True,
            partial_window_sec=15.0,
        )
        await asyncio.gather(
            module.handle_client(FakeWebSocket(), args),
            module.handle_client(FakeWebSocket(), args),
        )
        stop = True
        await ticker_task
        return gaps

    gaps = asyncio.run(exercise_handler())

    assert gaps
    assert max(gaps) < 0.08
    assert BlockingSession.max_active_workers == 1


def test_merge_turn_sentences_joins_same_speaker_fragments(monkeypatch):
    module = load_service_module(monkeypatch)

    sentences = [
        {"text": "今天先看报告", "start": 0, "end": 1200, "spk": 0},
        {"text": "，染色体有一条异常", "start": 1500, "end": 3000, "spk": 0},
        {"text": "嗯", "start": 3400, "end": 3600, "spk": 0},
        {"text": "好的", "start": 4000, "end": 4600, "spk": 1},
    ]

    merged = module.merge_turn_sentences(sentences, gap_ms=500, filler_gap_ms=1500)

    assert [item["text"] for item in merged] == [
        "今天先看报告，染色体有一条异常嗯",
        "好的",
    ]
    assert merged[0]["spk"] == 0
    assert merged[0]["end"] == 3600


def test_merge_turn_sentences_keeps_speaker_boundary(monkeypatch):
    module = load_service_module(monkeypatch)

    sentences = [
        {"text": "做内膜检查", "start": 0, "end": 1000, "spk": 0},
        {"text": "多少钱", "start": 1200, "end": 2000, "spk": 1},
    ]

    merged = module.merge_turn_sentences(sentences, gap_ms=500)

    assert len(merged) == 2


def test_build_streaming_vad_uses_fixed_silence_threshold(monkeypatch):
    module = load_service_module(monkeypatch)

    captured = {}

    class CaptureVad:
        def __init__(self, vad_model, speech_noise_thres=0.5, silence_schedule=None):
            captured["speech_noise_thres"] = speech_noise_thres
            captured["silence_schedule"] = silence_schedule

    monkeypatch.setattr(module, "DynamicStreamingVAD", CaptureVad)
    args = types.SimpleNamespace(vad_max_end_silence_ms=1200)

    module.build_streaming_vad(object(), args)

    assert captured["speech_noise_thres"] == 0.6
    assert captured["silence_schedule"] == [(float("inf"), 1200)]


def test_build_streaming_vad_accepts_full_schedule(monkeypatch):
    module = load_service_module(monkeypatch)

    captured = {}

    class CaptureVad:
        def __init__(self, vad_model, speech_noise_thres=0.5, silence_schedule=None):
            captured["silence_schedule"] = silence_schedule

    monkeypatch.setattr(module, "DynamicStreamingVAD", CaptureVad)
    args = types.SimpleNamespace(
        vad_max_end_silence_ms=0,
        vad_silence_schedule='[[5000,2000],[Infinity,1200]]',
    )

    module.build_streaming_vad(object(), args)

    assert captured["silence_schedule"] == [(5000.0, 2000), (float("inf"), 1200)]


def test_filler_only_text_detection(monkeypatch):
    module = load_service_module(monkeypatch)

    assert module._is_filler_only("嗯。嗯。嗯。")
    assert module._is_filler_only("啊")
    assert not module._is_filler_only("黄素是嗯")
    assert not module._is_filler_only("")


def test_sparse_text_detection(monkeypatch):
    module = load_service_module(monkeypatch)

    # One char stretched over seconds of audio is a noise hallucination.
    assert module._is_sparse_text("黄", 7800)
    assert module._is_sparse_text("嗯", 4000)
    # Natural speech survives: 3 chars in 800ms, or a quick 嗯.
    assert not module._is_sparse_text("多少钱", 800)
    assert not module._is_sparse_text("嗯", 500)
    assert not module._is_sparse_text("", 5000)


def test_decode_segment_drops_silent_audio_and_short_fillers(monkeypatch):
    module = load_service_module(monkeypatch)

    sample_rate = 16000

    class TextEngine:
        def __init__(self, text):
            self.text = text

        def generate(self, inputs, **kwargs):
            return [{"text": self.text}]

    loud_second = (np.full(sample_rate, 2000, dtype=np.int16)).tobytes()

    # Near-silent audio is skipped before it reaches the model.
    silent_session = module.RealtimeASRSession(
        vllm_engine=TextEngine("嗯。嗯。嗯。"),
        asr_kwargs={},
        vad=SegmentVad(sample_rate=sample_rate, segment_end_sample=sample_rate),
        sample_rate=sample_rate,
        chunk_ms=1000,
        audio_lookback_sec=5,
    )
    silent_session.add_audio(np.zeros(sample_rate, dtype=np.int16).tobytes())
    silent_session.decode(is_final=False)
    assert silent_session.locked_sentences == []

    # Short segments that decode to nothing but interjections are dropped.
    filler_session = module.RealtimeASRSession(
        vllm_engine=TextEngine("嗯。嗯。嗯。"),
        asr_kwargs={},
        vad=SegmentVad(sample_rate=sample_rate, segment_end_sample=sample_rate),
        sample_rate=sample_rate,
        chunk_ms=1000,
        audio_lookback_sec=5,
    )
    filler_session.add_audio(loud_second)
    filler_session.decode(is_final=False)
    assert filler_session.locked_sentences == []

    # Real speech survives the filters.
    speech_session = module.RealtimeASRSession(
        vllm_engine=TextEngine("多西环素要吃两周"),
        asr_kwargs={},
        vad=SegmentVad(sample_rate=sample_rate, segment_end_sample=sample_rate),
        sample_rate=sample_rate,
        chunk_ms=1000,
        audio_lookback_sec=5,
    )
    speech_session.add_audio(loud_second)
    speech_session.decode(is_final=False)

    assert [s["text"] for s in speech_session.locked_sentences] == ["多西环素要吃两周"]


def test_merge_turn_sentences_joins_same_speaker_fragments(monkeypatch):
    module = load_service_module(monkeypatch)

    sentences = [
        {"text": "今天先看报告", "start": 0, "end": 1200, "spk": 0},
        {"text": "，染色体有一条异常", "start": 1500, "end": 3000, "spk": 0},
        {"text": "嗯", "start": 3400, "end": 3600, "spk": 0},
        {"text": "好的", "start": 4000, "end": 4600, "spk": 1},
    ]

    merged = module.merge_turn_sentences(sentences, gap_ms=500, filler_gap_ms=1500)

    assert [item["text"] for item in merged] == [
        "今天先看报告，染色体有一条异常嗯",
        "好的",
    ]
    assert merged[0]["spk"] == 0
    assert merged[0]["end"] == 3600


def test_merge_turn_sentences_keeps_speaker_boundary(monkeypatch):
    module = load_service_module(monkeypatch)

    sentences = [
        {"text": "做内膜检查", "start": 0, "end": 1000, "spk": 0},
        {"text": "多少钱", "start": 1200, "end": 2000, "spk": 1},
    ]

    merged = module.merge_turn_sentences(sentences, gap_ms=500)

    assert len(merged) == 2


def test_build_streaming_vad_uses_fixed_silence_threshold(monkeypatch):
    module = load_service_module(monkeypatch)

    captured = {}

    class CaptureVad:
        def __init__(self, vad_model, speech_noise_thres=0.5, silence_schedule=None):
            captured["speech_noise_thres"] = speech_noise_thres
            captured["silence_schedule"] = silence_schedule

    monkeypatch.setattr(module, "DynamicStreamingVAD", CaptureVad)
    args = types.SimpleNamespace(vad_max_end_silence_ms=1200)

    module.build_streaming_vad(object(), args)

    assert captured["speech_noise_thres"] == 0.6
    assert captured["silence_schedule"] == [(float("inf"), 1200)]


def test_build_streaming_vad_accepts_full_schedule(monkeypatch):
    module = load_service_module(monkeypatch)

    captured = {}

    class CaptureVad:
        def __init__(self, vad_model, speech_noise_thres=0.5, silence_schedule=None):
            captured["silence_schedule"] = silence_schedule

    monkeypatch.setattr(module, "DynamicStreamingVAD", CaptureVad)
    args = types.SimpleNamespace(
        vad_max_end_silence_ms=0,
        vad_silence_schedule='[[5000,2000],[Infinity,1200]]',
    )

    module.build_streaming_vad(object(), args)

    assert captured["silence_schedule"] == [(5000.0, 2000), (float("inf"), 1200)]

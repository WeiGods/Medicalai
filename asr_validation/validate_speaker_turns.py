#!/usr/bin/env python3
"""Offline replay for short-window streaming speaker-turn detection.

This script keeps VAD as a speech gate, extracts fixed short speaker
embeddings inside every confirmed VAD segment, and runs the same
StreamingSpeakerTurnTracker that can later be wired into the realtime session.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import librosa
import numpy as np
import soundfile as sf
import torch

REPO_ROOT = Path(__file__).resolve().parents[1]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from streaming_speaker_turns import (  # noqa: E402
    OVERLAP_STATE,
    SPEAKER_STATE,
    UNKNOWN_STATE,
    SpeakerTurnConfig,
    StreamingSpeakerTurnTracker,
)


SAMPLE_RATE = 16000
WINDOW_IDENTITY_KEYS = (
    "segment_index",
    "segment_start_ms",
    "segment_end_ms",
    "start_ms",
    "end_ms",
    "window_ms",
)


def _window_identity(window: dict) -> tuple:
    return tuple(window.get(key) for key in WINDOW_IDENTITY_KEYS)


def _restore_embedding_cache_windows(
    windows: list[dict],
    cached_windows: list[dict],
) -> None:
    """Validate cached windows and restore extraction-time metadata."""

    if len(windows) != len(cached_windows):
        raise ValueError("embedding cache does not match the current windows")
    if any(
        _window_identity(window) != _window_identity(cached_window)
        for window, cached_window in zip(windows, cached_windows)
    ):
        raise ValueError("embedding cache does not match the current windows")
    for window, cached_window in zip(windows, cached_windows):
        if "rms" in cached_window:
            window["rms"] = cached_window["rms"]


def load_audio(path: Path, max_seconds: float | None = None) -> np.ndarray:
    audio, sample_rate = sf.read(path, dtype="float32", always_2d=False)
    if audio.ndim > 1:
        audio = audio.mean(axis=1)
    if sample_rate != SAMPLE_RATE:
        audio = librosa.resample(
            audio,
            orig_sr=sample_rate,
            target_sr=SAMPLE_RATE,
        )
    if max_seconds is not None and max_seconds > 0:
        audio = audio[: int(max_seconds * SAMPLE_RATE)]
    return np.ascontiguousarray(audio, dtype=np.float32)


def build_windows(
    segments,
    window_ms: int,
    hop_ms: int,
    bootstrap_window_ms: int | None = None,
    bootstrap_until_ms: int = 0,
) -> list[dict]:
    """Build fixed windows without crossing a confirmed VAD segment."""

    windows: list[dict] = []
    for segment_index, segment in enumerate(segments):
        start_ms = int(segment[0])
        end_ms = int(segment[1])
        duration_ms = end_ms - start_ms
        active_window_ms = (
            bootstrap_window_ms
            if (
                bootstrap_window_ms
                and bootstrap_until_ms > 0
                and start_ms < bootstrap_until_ms
            )
            else window_ms
        )
        if duration_ms <= 0:
            continue
        if duration_ms <= active_window_ms:
            windows.append(
                {
                    "segment_index": segment_index,
                    "segment_start_ms": start_ms,
                    "segment_end_ms": end_ms,
                    "start_ms": start_ms,
                    "end_ms": end_ms,
                    "window_ms": active_window_ms,
                }
            )
            continue

        starts = list(range(start_ms, end_ms - active_window_ms + 1, hop_ms))
        if not starts or starts[-1] + active_window_ms < end_ms:
            # A short tail gets a final context window instead of a tiny
            # embedding that is even less stable than the tail itself.
            starts.append(max(start_ms, end_ms - active_window_ms))
        for window_start in starts:
            windows.append(
                {
                    "segment_index": segment_index,
                    "segment_start_ms": start_ms,
                    "segment_end_ms": end_ms,
                    "start_ms": int(window_start),
                    "end_ms": int(window_start + active_window_ms),
                    "window_ms": active_window_ms,
                }
            )
    return windows


def extract_embeddings(
    audio: np.ndarray,
    windows: list[dict],
    spk_model,
    batch_size: int,
    device: str,
) -> list[torch.Tensor]:
    embeddings: list[torch.Tensor] = []
    for offset in range(0, len(windows), batch_size):
        batch = windows[offset : offset + batch_size]
        speech = []
        for window in batch:
            start = int(window["start_ms"] * SAMPLE_RATE / 1000)
            end = int(window["end_ms"] * SAMPLE_RATE / 1000)
            chunk = audio[start:end]
            window["rms"] = (
                float(np.sqrt(np.mean(np.square(chunk))))
                if len(chunk)
                else 0.0
            )
            speech.append(chunk)
        results = spk_model.generate(
            input=speech,
            cache={},
            is_final=True,
        )
        for result in results:
            embedding = result["spk_embedding"]
            embedding = torch.as_tensor(embedding, dtype=torch.float32)
            if embedding.ndim == 0:
                embedding = embedding.reshape(1)
            elif embedding.ndim == 1:
                embedding = embedding.reshape(-1)
            else:
                embedding = embedding.reshape(embedding.shape[-1])
            embeddings.append(embedding.detach().cpu())
    return embeddings


def track_windows(
    windows: list[dict],
    embeddings: list[torch.Tensor],
    config: SpeakerTurnConfig,
) -> StreamingSpeakerTurnTracker:
    tracker = StreamingSpeakerTurnTracker(config)
    for window, embedding in zip(windows, embeddings):
        if window["start_ms"] == window["segment_start_ms"]:
            tracker.begin_segment(window["segment_start_ms"])
        tracker.update_embedding(
            window["start_ms"],
            window["end_ms"],
            embedding,
            segment_start_ms=window["segment_start_ms"],
            segment_end_ms=window["segment_end_ms"],
            rms=window.get("rms"),
        )
    return tracker


def parse_focus_ranges(value: str | None) -> list[tuple[int, int]]:
    if not value:
        return []
    ranges = []
    for item in value.split(","):
        start_text, end_text = item.split("-", 1)
        ranges.append((int(start_text), int(end_text)))
    return ranges


def _turn_dict(turn) -> dict:
    return {
        "start_ms": turn.start_ms,
        "end_ms": turn.end_ms,
        "duration_ms": turn.duration_ms,
        "speaker_id": turn.speaker_id,
        "state": turn.state,
        "confidence": turn.confidence,
        "window_count": turn.window_count,
        "boundary_confidence": turn.boundary_confidence,
    }


def summarize(
    vad_segments,
    turns,
    windows,
) -> dict:
    durations = [turn.duration_ms for turn in turns]
    nonzero_durations = [duration for duration in durations if duration > 0]
    speaker_ids = sorted(
        {turn.speaker_id for turn in turns if turn.speaker_id is not None}
    )
    unknown_windows = sum(window["state"] == UNKNOWN_STATE for window in windows)
    overlap_windows = sum(window["state"] == OVERLAP_STATE for window in windows)
    mixed_vad_segments = []
    for segment_index, segment in enumerate(vad_segments):
        speaker_ids_in_segment = sorted(
            {
                turn.speaker_id
                for turn in turns
                if turn.speaker_id is not None
                and turn.end_ms > int(segment[0])
                and turn.start_ms < int(segment[1])
                and turn.state == SPEAKER_STATE
            }
        )
        if len(speaker_ids_in_segment) > 1:
            mixed_vad_segments.append(
                {
                    "segment_index": segment_index,
                    "start_ms": int(segment[0]),
                    "end_ms": int(segment[1]),
                    "speaker_ids": speaker_ids_in_segment,
                }
            )
    return {
        "vad_segments": len(vad_segments),
        "turns": len(turns),
        "speaker_ids": speaker_ids,
        "speaker_count": len(speaker_ids),
        "transitions": sum(
            turns[index - 1].speaker_id != turns[index].speaker_id
            for index in range(1, len(turns))
        ),
        "max_turn_ms": max(durations, default=0),
        "p50_turn_ms": round(float(np.percentile(durations, 50)), 1) if durations else 0.0,
        "p95_turn_ms": round(float(np.percentile(durations, 95)), 1) if durations else 0.0,
        "over_30s": sum(duration > 30000 for duration in durations),
        "under_500ms": sum(duration < 500 for duration in durations),
        "real_under_500ms": sum(
            0 < duration < 500 for duration in nonzero_durations
        ),
        "zero_duration_turns": sum(duration == 0 for duration in durations),
        "mixed_vad_segments": len(mixed_vad_segments),
        "mixed_vad_segment_details": mixed_vad_segments,
        "unknown_window_ratio": round(unknown_windows / len(windows), 6) if windows else 0.0,
        "overlap_window_ratio": round(overlap_windows / len(windows), 6) if windows else 0.0,
    }


def focus_report(turns, focus_ranges):
    report = []
    for start_ms, end_ms in focus_ranges:
        report.append(
            {
                "range_ms": [start_ms, end_ms],
                "turns": [
                    _turn_dict(turn)
                    for turn in turns
                    if turn.end_ms > start_ms and turn.start_ms < end_ms
                ],
            }
        )
    return report


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--audio", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument(
        "--vad-model",
        default="/workspace/Fun-ASR/models/fsmn-vad",
    )
    parser.add_argument("--vad-segments-json", default="")
    parser.add_argument(
        "--spk-model",
        default=(
            "/workspace/Fun-ASR/models/"
            "speech_eres2netv2_sv_zh-cn_16k-common"
        ),
    )
    parser.add_argument("--device", default="cuda:0")
    parser.add_argument("--vad-end-silence-ms", type=int, default=600)
    parser.add_argument("--window-ms", type=int, default=1200)
    parser.add_argument("--bootstrap-window-ms", type=int, default=0)
    parser.add_argument("--bootstrap-until-ms", type=int, default=0)
    parser.add_argument("--hop-ms", type=int, default=200)
    parser.add_argument("--confirm-windows", type=int, default=3)
    parser.add_argument("--speaker-confirm-windows", type=int, default=5)
    parser.add_argument("--speaker-min-span-ms", type=int, default=2000)
    parser.add_argument("--min-turn-ms", type=int, default=400)
    parser.add_argument("--max-speakers", type=int, default=2)
    parser.add_argument("--embedding-batch-size", type=int, default=32)
    parser.add_argument("--embedding-cache", default="")
    parser.add_argument(
        "--offline-global-reclassify",
        dest="offline_global_reclassify",
        action="store_true",
    )
    parser.add_argument(
        "--no-offline-global-reclassify",
        dest="offline_global_reclassify",
        action="store_false",
    )
    parser.set_defaults(offline_global_reclassify=True)
    parser.add_argument(
        "--offline-global-iterations",
        type=int,
        default=25,
    )
    parser.add_argument(
        "--offline-global-match-threshold",
        type=float,
        default=0.32,
    )
    parser.add_argument(
        "--offline-global-margin-threshold",
        type=float,
        default=0.04,
    )
    parser.add_argument(
        "--offline-global-unknown-threshold",
        type=float,
        default=0.25,
    )
    parser.add_argument(
        "--offline-global-gap-fill-ms",
        type=int,
        default=1000,
    )
    parser.add_argument(
        "--offline-global-min-run-windows",
        type=int,
        default=2,
    )
    parser.add_argument(
        "--offline-global-min-speaker-island-ms",
        type=int,
        default=0,
    )
    parser.add_argument("--max-seconds", type=float, default=0)
    parser.add_argument(
        "--focus-ranges",
        default=(
            "26910-35020,55750-65650,112840-122430,135680-141130,"
            "443390-445790,446060-449800,637340-654920"
        ),
    )
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args()

    started = time.perf_counter()
    audio = load_audio(Path(args.audio), args.max_seconds or None)
    if args.vad_segments_json:
        vad_payload = json.loads(
            Path(args.vad_segments_json).read_text(encoding="utf-8")
        )
        vad_segments = [
            (int(segment["start_ms"]), int(segment["end_ms"]))
            for segment in vad_payload["vad_segments"]
        ]
    else:
        from funasr import AutoModel
        from funasr.models.fsmn_vad_streaming.dynamic_vad import (
            DynamicStreamingVAD,
        )

        vad_model = AutoModel(
            device=args.device,
            disable_update=True,
            model="fsmn-vad",
            model_path=args.vad_model,
        )
        vad = DynamicStreamingVAD(
            vad_model,
            speech_noise_thres=0.6,
            silence_schedule=[(float("inf"), args.vad_end_silence_ms)],
        )
        vad_segments = vad.process(torch.from_numpy(audio))

    windows = build_windows(
        vad_segments,
        args.window_ms,
        args.hop_ms,
        bootstrap_window_ms=args.bootstrap_window_ms or None,
        bootstrap_until_ms=args.bootstrap_until_ms,
    )
    embedding_cache = Path(args.embedding_cache) if args.embedding_cache else None
    cache_loaded = False
    if embedding_cache is not None and embedding_cache.is_file():
        cache = torch.load(embedding_cache, map_location="cpu", weights_only=False)
        _restore_embedding_cache_windows(windows, cache.get("windows", []))
        embeddings = [
            torch.as_tensor(embedding, dtype=torch.float32).flatten()
            for embedding in cache["embeddings"]
        ]
        cache_loaded = True
    else:
        spk_model = AutoModel(
            device=args.device,
            disable_update=True,
            model=(
                "iic/speech_eres2netv2_sv_zh-cn_16k-common"
            ),
            model_path=args.spk_model,
        )
        embeddings = extract_embeddings(
            audio,
            windows,
            spk_model,
            args.embedding_batch_size,
            args.device,
        )
        if embedding_cache is not None:
            embedding_cache.parent.mkdir(parents=True, exist_ok=True)
            torch.save(
                {"windows": windows, "embeddings": embeddings},
                embedding_cache,
            )
    config = SpeakerTurnConfig(
        window_ms=args.window_ms,
        hop_ms=args.hop_ms,
        confirm_windows=args.confirm_windows,
        speaker_confirm_windows=args.speaker_confirm_windows,
        speaker_min_span_ms=args.speaker_min_span_ms,
        min_turn_ms=args.min_turn_ms,
        max_speakers=args.max_speakers,
        offline_global_max_speakers=args.max_speakers,
        offline_global_iterations=args.offline_global_iterations,
        offline_global_match_threshold=(
            args.offline_global_match_threshold
        ),
        offline_global_margin_threshold=(
            args.offline_global_margin_threshold
        ),
        offline_global_unknown_threshold=(
            args.offline_global_unknown_threshold
        ),
        offline_global_gap_fill_ms=args.offline_global_gap_fill_ms,
        offline_global_min_run_windows=(
            args.offline_global_min_run_windows
        ),
        offline_global_min_speaker_island_ms=(
            args.offline_global_min_speaker_island_ms
        ),
    )
    tracker = track_windows(windows, embeddings, config)
    offline_global_reclassify = (
        tracker.offline_global_reclassify()
        if args.offline_global_reclassify
        else {"enabled": False}
    )
    turns = tracker.finalize()
    result = {
        "audio": str(Path(args.audio).resolve()),
        "audio_duration_ms": int(len(audio) * 1000 / SAMPLE_RATE),
        "parameters": {
            "vad_end_silence_ms": args.vad_end_silence_ms,
            "vad_segments_json": args.vad_segments_json or None,
            "window_ms": args.window_ms,
            "bootstrap_window_ms": args.bootstrap_window_ms or None,
            "bootstrap_until_ms": args.bootstrap_until_ms or None,
            "hop_ms": args.hop_ms,
            "confirm_windows": args.confirm_windows,
            "speaker_confirm_windows": args.speaker_confirm_windows,
            "speaker_min_span_ms": args.speaker_min_span_ms,
            "min_turn_ms": args.min_turn_ms,
            "max_speakers": args.max_speakers,
            "offline_global_reclassify": args.offline_global_reclassify,
            "offline_global_iterations": args.offline_global_iterations,
            "offline_global_match_threshold": (
                args.offline_global_match_threshold
            ),
            "offline_global_margin_threshold": (
                args.offline_global_margin_threshold
            ),
            "offline_global_unknown_threshold": (
                args.offline_global_unknown_threshold
            ),
            "offline_global_gap_fill_ms": args.offline_global_gap_fill_ms,
            "offline_global_min_run_windows": (
                args.offline_global_min_run_windows
            ),
            "offline_global_min_speaker_island_ms": (
                args.offline_global_min_speaker_island_ms
            ),
        },
        "embedding_cache": {
            "path": str(embedding_cache) if embedding_cache else None,
            "loaded": cache_loaded,
        },
        "elapsed_sec": round(time.perf_counter() - started, 3),
        "summary": summarize(vad_segments, turns, tracker.snapshot_windows()),
        "offline_global_reclassify": offline_global_reclassify,
        "vad_segments": [
            {"start_ms": int(start), "end_ms": int(end)}
            for start, end in vad_segments
        ],
        "turns": [_turn_dict(turn) for turn in turns],
        "switches": tracker.snapshot_switches(),
        "windows": tracker.snapshot_windows(),
        "focus": focus_report(turns, parse_focus_ranges(args.focus_ranges)),
    }
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps(result, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    if args.quiet:
        print(json.dumps(result["summary"], ensure_ascii=False))
    else:
        print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

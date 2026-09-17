#!/usr/bin/env python3
"""Build WAV clips and CSV templates for speaker-turn annotation."""

from __future__ import annotations

import argparse
import csv
import json
import shutil
import subprocess
from pathlib import Path
from typing import Any


CSV_FIELDS = [
    "item_id",
    "audio_id",
    "type",
    "start_ms",
    "end_ms",
    "current_speaker",
    "is_real_change",
    "true_left",
    "true_right",
    "true_boundary_ms",
    "notes",
    "detected_sequence",
    "detected_boundary_ms",
    "clip_start_ms",
    "clip_end_ms",
    "clip_file",
]


def parse_case(value: str) -> tuple[str, Path]:
    audio_id, separator, result_path = value.partition("=")
    if not separator or not audio_id or not result_path:
        raise argparse.ArgumentTypeError(
            "case must use AUDIO_ID=RESULT_JSON"
        )
    return audio_id, Path(result_path)


def parse_focus_ranges(value: str) -> list[tuple[int, int]]:
    if not value.strip():
        return []
    ranges: list[tuple[int, int]] = []
    for item in value.split(","):
        start_text, separator, end_text = item.partition("-")
        if not separator:
            raise ValueError(f"invalid focus range: {item}")
        start_ms = int(start_text)
        end_ms = int(end_text)
        if end_ms <= start_ms:
            raise ValueError(f"invalid focus range: {item}")
        ranges.append((start_ms, end_ms))
    return ranges


def speaker_map(result: dict[str, Any]) -> dict[int, str]:
    ordered_ids: list[int] = []
    for turn in result["turns"]:
        speaker_id = turn.get("speaker_id")
        if speaker_id is None or speaker_id in ordered_ids:
            continue
        ordered_ids.append(int(speaker_id))
    return {
        speaker_id: chr(ord("A") + index)
        for index, speaker_id in enumerate(ordered_ids)
    }


def speaker_label(
    speaker_id: int | None,
    mapping: dict[int, str],
    *,
    state: str,
) -> str:
    if speaker_id is not None and int(speaker_id) in mapping:
        return mapping[int(speaker_id)]
    if state == "OVERLAP":
        return "OVERLAP"
    if state == "UNKNOWN":
        return "UNKNOWN"
    return "NONE"


def detected_sequence(
    turns: list[dict[str, Any]],
    start_ms: int,
    end_ms: int,
    mapping: dict[int, str],
) -> str:
    sequence: list[str] = []
    for turn in turns:
        if turn["end_ms"] <= start_ms or turn["start_ms"] >= end_ms:
            continue
        label = speaker_label(
            turn.get("speaker_id"),
            mapping,
            state=str(turn["state"]),
        )
        if not sequence or sequence[-1] != label:
            sequence.append(label)
    return ">".join(sequence) if sequence else "NONE"


def detected_boundaries(
    switches: list[dict[str, Any]],
    start_ms: int,
    end_ms: int,
) -> str:
    values = []
    for switch in switches:
        boundary_ms = int(round(float(switch["boundary_ms"])))
        if start_ms <= boundary_ms <= end_ms:
            values.append(str(boundary_ms))
    return ";".join(values)


def clip_name(item_id: str) -> str:
    return f"{item_id}.wav"


def extract_clip(
    ffmpeg: str,
    source_audio: Path,
    destination: Path,
    start_ms: int,
    end_ms: int,
) -> None:
    duration_s = max(0.001, (end_ms - start_ms) / 1000.0)
    command = [
        ffmpeg,
        "-hide_banner",
        "-loglevel",
        "error",
        "-y",
        "-ss",
        f"{start_ms / 1000.0:.3f}",
        "-t",
        f"{duration_s:.3f}",
        "-i",
        str(source_audio),
        "-ac",
        "1",
        "-ar",
        "16000",
        "-c:a",
        "pcm_s16le",
        str(destination),
    ]
    subprocess.run(command, check=True)


def build_items(
    audio_id: str,
    result: dict[str, Any],
    focus_ranges: list[tuple[int, int]],
    short_turn_max_ms: int,
    padding_ms: int,
) -> tuple[list[dict[str, Any]], dict[int, str]]:
    mapping = speaker_map(result)
    turns = result["turns"]
    switches = result.get("switches", [])
    duration_ms = int(result["audio_duration_ms"])
    source_audio = Path(result["audio"])
    items: list[dict[str, Any]] = []

    for start_ms, end_ms in focus_ranges:
        item_id = f"{audio_id}_boundary_{start_ms}_{end_ms}"
        clip_start_ms = max(0, start_ms - padding_ms)
        clip_end_ms = min(duration_ms, end_ms + padding_ms)
        items.append(
            {
                "item_id": item_id,
                "audio_id": audio_id,
                "type": "key_boundary",
                "start_ms": start_ms,
                "end_ms": end_ms,
                "current_speaker": detected_sequence(
                    turns,
                    start_ms,
                    end_ms,
                    mapping,
                ),
                "is_real_change": "",
                "true_left": "",
                "true_right": "",
                "true_boundary_ms": "",
                "notes": "",
                "detected_sequence": detected_sequence(
                    turns,
                    start_ms,
                    end_ms,
                    mapping,
                ),
                "detected_boundary_ms": detected_boundaries(
                    switches,
                    start_ms,
                    end_ms,
                ),
                "clip_start_ms": clip_start_ms,
                "clip_end_ms": clip_end_ms,
                "clip_file": clip_name(item_id),
                "source_audio": source_audio,
            }
        )

    for turn in turns:
        duration = int(turn["duration_ms"])
        if (
            turn["state"] != "SPEAKER"
            or duration <= 0
            or duration >= short_turn_max_ms
        ):
            continue
        start_ms = int(turn["start_ms"])
        end_ms = int(turn["end_ms"])
        item_id = f"{audio_id}_short_{start_ms}_{end_ms}"
        clip_start_ms = max(0, start_ms - padding_ms)
        clip_end_ms = min(duration_ms, end_ms + padding_ms)
        current = speaker_label(
            turn.get("speaker_id"),
            mapping,
            state=str(turn["state"]),
        )
        items.append(
            {
                "item_id": item_id,
                "audio_id": audio_id,
                "type": "short_turn",
                "start_ms": start_ms,
                "end_ms": end_ms,
                "current_speaker": current,
                "is_real_change": "",
                "true_left": "",
                "true_right": "",
                "true_boundary_ms": "",
                "notes": "",
                "detected_sequence": detected_sequence(
                    turns,
                    start_ms,
                    end_ms,
                    mapping,
                ),
                "detected_boundary_ms": detected_boundaries(
                    switches,
                    start_ms,
                    end_ms,
                ),
                "clip_start_ms": clip_start_ms,
                "clip_end_ms": clip_end_ms,
                "clip_file": clip_name(item_id),
                "source_audio": source_audio,
            }
        )

    return items, mapping


def write_csv(path: Path, items: list[dict[str, Any]]) -> None:
    with path.open("w", encoding="utf-8-sig", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=CSV_FIELDS)
        writer.writeheader()
        for item in items:
            writer.writerow(
                {
                    field: item.get(field, "")
                    for field in CSV_FIELDS
                }
            )


def write_markdown(
    path: Path,
    items: list[dict[str, Any]],
    mappings: dict[str, dict[int, str]],
) -> None:
    lines = [
        "# Speaker Turn Annotation Pack",
        "",
        "Fill `annotation.csv` in this directory. Keep `annotation_template.csv` unchanged.",
        "",
        "Per-audio mapping:",
        "",
    ]
    for audio_id, mapping in mappings.items():
        mapping_text = ", ".join(
            f"speaker {speaker_id} = {label}"
            for speaker_id, label in sorted(mapping.items())
        )
        lines.append(f"- `{audio_id}`: {mapping_text}")
    lines.extend(
        [
            "",
            "Allowed values:",
            "",
            "- `is_real_change`: `YES`, `NO`, or `OVERLAP`.",
            "- `true_left` / `true_right`: `A`, `B`, `OVERLAP`, or `NONE`.",
            "- `true_boundary_ms`: one integer in milliseconds when there is a real change.",
            "- `notes`: free text for ambiguous cases.",
            "",
            "| Item | Type | Target ms | Detected | Clip |",
            "|---|---|---:|---|---|",
        ]
    )
    for item in items:
        target = f"{item['start_ms']}-{item['end_ms']}"
        detected = item["detected_sequence"]
        if item["detected_boundary_ms"]:
            detected += f" @ {item['detected_boundary_ms']} ms"
        lines.append(
            f"| `{item['item_id']}` | {item['type']} | {target} | "
            f"{detected} | [{item['clip_file']}]({item['clip_file']}) |"
        )
    lines.append("")
    path.write_text("\n".join(lines), encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--case",
        action="append",
        type=parse_case,
        required=True,
        help="AUDIO_ID=RESULT_JSON; repeat for multiple recordings",
    )
    parser.add_argument("--output", required=True)
    parser.add_argument("--focus-ranges", default="")
    parser.add_argument(
        "--focus-audio-id",
        action="append",
        default=[],
        help=(
            "Only apply --focus-ranges to this audio ID; repeat as needed. "
            "By default, focus ranges apply to every case."
        ),
    )
    parser.add_argument("--short-turn-max-ms", type=int, default=500)
    parser.add_argument("--padding-ms", type=int, default=2000)
    parser.add_argument("--ffmpeg", default="ffmpeg")
    args = parser.parse_args()

    ffmpeg = shutil.which(args.ffmpeg)
    if ffmpeg is None:
        raise SystemExit(f"ffmpeg was not found: {args.ffmpeg}")

    output_dir = Path(args.output)
    output_dir.mkdir(parents=True, exist_ok=True)
    focus_ranges = parse_focus_ranges(args.focus_ranges)
    focus_audio_ids = set(args.focus_audio_id)
    all_items: list[dict[str, Any]] = []
    mappings: dict[str, dict[int, str]] = {}

    for audio_id, result_path in args.case:
        result = json.loads(result_path.read_text(encoding="utf-8"))
        audio_focus_ranges = (
            focus_ranges
            if not focus_audio_ids or audio_id in focus_audio_ids
            else []
        )
        items, mapping = build_items(
            audio_id,
            result,
            audio_focus_ranges,
            args.short_turn_max_ms,
            args.padding_ms,
        )
        mappings[audio_id] = mapping
        all_items.extend(items)

    for item in all_items:
        extract_clip(
            ffmpeg,
            item["source_audio"],
            output_dir / item["clip_file"],
            item["clip_start_ms"],
            item["clip_end_ms"],
        )

    manifest_items = [
        {
            key: value
            for key, value in item.items()
            if key != "source_audio"
        }
        for item in all_items
    ]
    (output_dir / "manifest.json").write_text(
        json.dumps(
            {
                "focus_ranges": focus_ranges,
                "short_turn_max_ms": args.short_turn_max_ms,
                "padding_ms": args.padding_ms,
                "speaker_mappings": {
                    audio_id: {
                        str(speaker_id): label
                        for speaker_id, label in mapping.items()
                    }
                    for audio_id, mapping in mappings.items()
                },
                "items": manifest_items,
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    write_csv(output_dir / "annotation_template.csv", all_items)
    annotation_path = output_dir / "annotation.csv"
    if not annotation_path.exists():
        write_csv(annotation_path, all_items)
    write_markdown(output_dir / "README.md", all_items, mappings)
    print(
        json.dumps(
            {
                "output": str(output_dir.resolve()),
                "items": len(all_items),
                "clips": len(all_items),
                "annotation_csv": str(annotation_path.resolve()),
            },
            ensure_ascii=False,
        )
    )


if __name__ == "__main__":
    main()

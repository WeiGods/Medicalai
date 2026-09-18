#!/usr/bin/env python3
"""Validate and score speaker-turn annotations against candidate results."""

from __future__ import annotations

import argparse
import csv
import io
import json
import re
import statistics
from dataclasses import dataclass, field
from functools import lru_cache
from pathlib import Path
from typing import Any, Iterable, Sequence


ALLOWED_CHANGES = {"YES", "NO", "OVERLAP"}
ALLOWED_SPEAKERS = {"A", "B", "OVERLAP", "NONE"}
SHORT_TURN_TYPES = {"short_turn", "short"}
KEY_BOUNDARY_TYPES = {"key_boundary", "boundary"}
SEQUENCE_PATTERN = re.compile(
    r"(?<![A-Z])([AB](?:\s*(?:>|->)\s*[AB])+)(?![A-Z])",
    re.IGNORECASE,
)
NUMBER_PATTERN = re.compile(r"^[+-]?\d+(?:\.\d+)?$")


@dataclass
class Annotation:
    item_id: str
    audio_id: str
    item_type: str
    start_ms: int
    end_ms: int
    current_speaker: str
    is_real_change: str
    true_left: str
    true_right: str
    true_boundary_text: str
    true_boundaries: list[int] = field(default_factory=list)
    true_sequence: str | None = None
    notes: str = ""
    detected_sequence: str = ""
    detected_boundary_text: str = ""
    detected_boundaries: list[int] = field(default_factory=list)
    clip_start_ms: int = 0
    clip_end_ms: int = 0
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)

    @property
    def is_complete(self) -> bool:
        return not self.errors


@dataclass(frozen=True)
class CaseSpec:
    label: str
    audio_id: str
    path: Path


def parse_case(value: str) -> CaseSpec:
    """Accept AUDIO_ID=PATH or LABEL=AUDIO_ID=PATH."""

    parts = value.split("=")
    if len(parts) == 2:
        audio_id, path_text = parts
        label = Path(path_text).stem
    elif len(parts) == 3:
        label, audio_id, path_text = parts
    else:
        raise argparse.ArgumentTypeError(
            "case must use AUDIO_ID=PATH or LABEL=AUDIO_ID=PATH"
        )
    if not label or not audio_id or not path_text:
        raise argparse.ArgumentTypeError(
            "case must use AUDIO_ID=PATH or LABEL=AUDIO_ID=PATH"
        )
    return CaseSpec(label=label, audio_id=audio_id, path=Path(path_text))


def parse_int(value: Any, field_name: str) -> int:
    text = str(value).strip()
    if not NUMBER_PATTERN.fullmatch(text):
        raise ValueError(f"{field_name} must be an integer: {value!r}")
    return int(round(float(text)))


def parse_boundary_text(value: str) -> list[int]:
    text = value.strip()
    if not text:
        return []
    values: list[int] = []
    for part in re.split(r"[;,\s]+", text):
        if not part:
            continue
        if not NUMBER_PATTERN.fullmatch(part):
            raise ValueError(f"invalid boundary value: {part!r}")
        values.append(int(round(float(part))))
    return sorted(values)


def normalize_sequence(value: str) -> str:
    return re.sub(r"\s+", "", value.upper())


def sequence_tokens(value: str) -> list[str]:
    return [token for token in value.split(">") if token]


def sequence_contains(container: str, target: str) -> bool:
    container_tokens = sequence_tokens(container)
    target_tokens = sequence_tokens(target)
    if not target_tokens or len(target_tokens) > len(container_tokens):
        return False
    width = len(target_tokens)
    return any(
        container_tokens[index : index + width] == target_tokens
        for index in range(len(container_tokens) - width + 1)
    )


def sequence_from_text(value: str) -> str | None:
    matches = SEQUENCE_PATTERN.findall(value)
    if not matches:
        return None
    return normalize_sequence(max(matches, key=len))


def sequence_from_endpoints(
    left: str,
    right: str,
    true_boundaries: Sequence[int],
) -> str | None:
    if left not in {"A", "B"} or right not in {"A", "B"}:
        return None
    if len(true_boundaries) > 1:
        return None
    return f"{left}>{right}"


def load_annotations(path: Path) -> list[Annotation]:
    annotations: list[Annotation] = []
    seen_ids: set[str] = set()
    annotation_text = path.read_text(encoding="utf-8-sig")
    first_line = next(
        (line for line in annotation_text.splitlines() if line.strip()),
        "",
    )
    delimiter = "\t" if "\t" in first_line else ","
    with io.StringIO(annotation_text, newline="") as source:
        reader = csv.DictReader(source, delimiter=delimiter)
        required_fields = {
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
        }
        missing_fields = sorted(required_fields - set(reader.fieldnames or []))
        if missing_fields:
            raise ValueError(
                "annotation CSV is missing columns: "
                + ", ".join(missing_fields)
            )

        for row_number, row in enumerate(reader, start=2):
            item_id = (row.get("item_id") or "").strip()
            if not item_id:
                raise ValueError(f"row {row_number} is missing item_id")
            if item_id in seen_ids:
                raise ValueError(f"duplicate item_id: {item_id}")
            seen_ids.add(item_id)

            errors: list[str] = []
            warnings: list[str] = []
            try:
                start_ms = parse_int(row.get("start_ms"), "start_ms")
            except ValueError as error:
                errors.append(str(error))
                start_ms = 0
            try:
                clip_start_ms = parse_int(
                    row.get("clip_start_ms") or start_ms,
                    "clip_start_ms",
                )
            except ValueError as error:
                errors.append(str(error))
                clip_start_ms = start_ms
            try:
                end_ms = parse_int(row.get("end_ms"), "end_ms")
            except ValueError as error:
                errors.append(str(error))
                end_ms = 0
            try:
                clip_end_ms = parse_int(
                    row.get("clip_end_ms") or end_ms,
                    "clip_end_ms",
                )
            except ValueError as error:
                errors.append(str(error))
                clip_end_ms = end_ms
            if end_ms <= start_ms:
                errors.append("end_ms must be greater than start_ms")
            if clip_end_ms <= clip_start_ms:
                errors.append("clip_end_ms must be greater than clip_start_ms")
            if clip_start_ms > start_ms or clip_end_ms < end_ms:
                errors.append("clip range must contain the focus range")

            item_type = (row.get("type") or "").strip().lower()
            if item_type not in SHORT_TURN_TYPES | KEY_BOUNDARY_TYPES:
                errors.append(f"unsupported type: {item_type!r}")

            is_real_change = (
                (row.get("is_real_change") or "").strip().upper()
            )
            if not is_real_change:
                errors.append("missing is_real_change")
            elif is_real_change not in ALLOWED_CHANGES:
                errors.append(
                    "is_real_change must be YES, NO, or OVERLAP"
                )

            true_left = (row.get("true_left") or "").strip().upper()
            true_right = (row.get("true_right") or "").strip().upper()
            for field_name, field_value in (
                ("true_left", true_left),
                ("true_right", true_right),
            ):
                if field_value and field_value not in ALLOWED_SPEAKERS:
                    errors.append(
                        f"{field_name} must be A, B, OVERLAP, or NONE"
                    )

            true_boundary_text = (
                row.get("true_boundary_ms") or ""
            ).strip()
            try:
                true_boundaries = parse_boundary_text(true_boundary_text)
            except ValueError as error:
                errors.append(str(error))
                true_boundaries = []
            if is_real_change == "YES" and not true_boundaries:
                warnings.append(
                    "true_boundary_ms is missing; only sequence and "
                    "short-turn outcomes are evaluated"
                )
            if (
                item_type in SHORT_TURN_TYPES
                and is_real_change == "YES"
                and true_boundaries
                and len(true_boundaries) != 2
            ):
                warnings.append(
                    "short_turn YES normally needs start and end in "
                    "true_boundary_ms"
                )
            if is_real_change == "NO" and true_boundary_text:
                warnings.append(
                    "true_boundary_ms is ignored when is_real_change=NO"
                )

            notes = (row.get("notes") or "").strip()
            true_sequence = sequence_from_text(notes)
            if true_sequence is None:
                true_sequence = sequence_from_endpoints(
                    true_left,
                    true_right,
                    true_boundaries,
                )
            if (
                item_type in KEY_BOUNDARY_TYPES
                and is_real_change in {"YES", "OVERLAP"}
                and len(true_boundaries) > 1
                and true_sequence is None
            ):
                warnings.append(
                    "multi-boundary item has no complete A>B sequence in notes"
                )
            if (
                item_type in KEY_BOUNDARY_TYPES
                and is_real_change == "YES"
                and not true_boundaries
                and true_sequence is None
            ):
                warnings.append(
                    "no true_boundary_ms or complete A>B sequence; boundary "
                    "item cannot be scored"
                )
            if (
                item_type in KEY_BOUNDARY_TYPES
                and is_real_change == "YES"
                and (not true_left or not true_right)
            ):
                warnings.append(
                    "true_left/true_right are recommended for key boundaries"
                )

            try:
                detected_boundaries = parse_boundary_text(
                    row.get("detected_boundary_ms") or ""
                )
            except ValueError as error:
                warnings.append(f"invalid detected_boundary_ms: {error}")
                detected_boundaries = []

            annotations.append(
                Annotation(
                    item_id=item_id,
                    audio_id=(row.get("audio_id") or "").strip(),
                    item_type=item_type,
                    start_ms=start_ms,
                    end_ms=end_ms,
                    current_speaker=(
                        (row.get("current_speaker") or "").strip().upper()
                    ),
                    is_real_change=is_real_change,
                    true_left=true_left,
                    true_right=true_right,
                    true_boundary_text=true_boundary_text,
                    true_boundaries=true_boundaries,
                    true_sequence=true_sequence,
                    notes=notes,
                    detected_sequence=(
                        (row.get("detected_sequence") or "").strip().upper()
                    ),
                    detected_boundary_text=(
                        (row.get("detected_boundary_ms") or "").strip()
                    ),
                    detected_boundaries=detected_boundaries,
                    clip_start_ms=clip_start_ms,
                    clip_end_ms=clip_end_ms,
                    errors=errors,
                    warnings=warnings,
                )
            )
    return annotations


def load_speaker_mappings(path: Path | None) -> dict[str, dict[str, str]]:
    if path is None or not path.is_file():
        return {}
    payload = json.loads(path.read_text(encoding="utf-8"))
    mappings = payload.get("speaker_mappings", {})
    return {
        str(audio_id): {
            str(speaker_id): str(label).upper()
            for speaker_id, label in mapping.items()
        }
        for audio_id, mapping in mappings.items()
    }


def infer_speaker_mapping(result: dict[str, Any]) -> dict[str, str]:
    ordered_ids: list[int] = []
    for turn in result.get("turns", []):
        speaker_id = turn.get("speaker_id")
        if speaker_id is None or int(speaker_id) in ordered_ids:
            continue
        ordered_ids.append(int(speaker_id))
    return {
        str(speaker_id): chr(ord("A") + index)
        for index, speaker_id in enumerate(ordered_ids)
    }


def speaker_label(
    speaker_id: int | None,
    mapping: dict[str, str],
    state: str,
) -> str:
    if speaker_id is not None:
        label = mapping.get(str(int(speaker_id)))
        if label is not None:
            return label
        return f"S{int(speaker_id)}"
    if state == "OVERLAP":
        return "OVERLAP"
    if state == "UNKNOWN":
        return "UNKNOWN"
    return "NONE"


def candidate_sequence(
    turns: Sequence[dict[str, Any]],
    start_ms: int,
    end_ms: int,
    mapping: dict[str, str],
) -> str:
    sequence: list[str] = []
    for turn in turns:
        if turn["end_ms"] <= start_ms or turn["start_ms"] >= end_ms:
            continue
        label = speaker_label(
            turn.get("speaker_id"),
            mapping,
            str(turn.get("state", "")),
        )
        if not sequence or sequence[-1] != label:
            sequence.append(label)
    return ">".join(sequence) if sequence else "NONE"


def candidate_boundaries(
    switches: Sequence[dict[str, Any]],
    start_ms: int,
    end_ms: int,
    edge_ignore_ms: int,
) -> tuple[list[int], list[int]]:
    internal: list[int] = []
    excluded_edges: list[int] = []
    for switch in switches:
        boundary_ms = int(round(float(switch["boundary_ms"])))
        if not start_ms <= boundary_ms <= end_ms:
            continue
        if (
            boundary_ms <= start_ms + edge_ignore_ms
            or boundary_ms >= end_ms - edge_ignore_ms
        ):
            excluded_edges.append(boundary_ms)
        else:
            internal.append(boundary_ms)
    return sorted(internal), sorted(excluded_edges)


def match_boundaries(
    truth: Sequence[int],
    detected: Sequence[int],
    tolerance_ms: int,
) -> dict[str, Any]:
    @lru_cache(maxsize=None)
    def solve(
        truth_index: int,
        detected_index: int,
    ) -> tuple[int, int, tuple[tuple[int, int], ...]]:
        if truth_index == 0 or detected_index == 0:
            return 0, 0, ()

        options = [
            solve(truth_index - 1, detected_index),
            solve(truth_index, detected_index - 1),
        ]
        difference = abs(
            truth[truth_index - 1] - detected[detected_index - 1]
        )
        if difference <= tolerance_ms:
            previous = solve(truth_index - 1, detected_index - 1)
            options.append(
                (
                    previous[0] + 1,
                    previous[1] + difference,
                    previous[2]
                    + ((truth[truth_index - 1], detected[detected_index - 1]),),
                )
            )
        return max(options, key=lambda item: (item[0], -item[1]))

    matched_count, error_sum, pairs = solve(len(truth), len(detected))
    errors = [
        abs(truth_value - detected_value)
        for truth_value, detected_value in pairs
    ]
    return {
        "truth_count": len(truth),
        "detected_count": len(detected),
        "matched_count": matched_count,
        "false_negatives": len(truth) - matched_count,
        "false_positives": len(detected) - matched_count,
        "matched_pairs": [
            {"truth_ms": truth_value, "detected_ms": detected_value}
            for truth_value, detected_value in pairs
        ],
        "error_sum_ms": error_sum,
        "mean_abs_error_ms": (
            round(error_sum / matched_count, 2) if matched_count else None
        ),
        "median_abs_error_ms": (
            round(float(statistics.median(errors)), 2) if errors else None
        ),
        "max_abs_error_ms": max(errors) if errors else None,
    }


class Candidate:
    def __init__(
        self,
        spec: CaseSpec,
        result: dict[str, Any],
        mapping: dict[str, str],
        *,
        edge_ignore_ms: int,
    ) -> None:
        self.spec = spec
        self.result = result
        self.mapping = mapping
        self.edge_ignore_ms = edge_ignore_ms
        self.turns = list(result.get("turns", []))
        self.switches = list(result.get("switches", []))

    def detected_sequence(self, annotation: Annotation) -> str:
        return candidate_sequence(
            self.turns,
            annotation.clip_start_ms,
            annotation.clip_end_ms,
            self.mapping,
        )

    def internal_boundaries(self, annotation: Annotation) -> tuple[list[int], list[int]]:
        return candidate_boundaries(
            self.switches,
            annotation.start_ms,
            annotation.end_ms,
            self.edge_ignore_ms,
        )

    def clip_boundaries(self, annotation: Annotation) -> tuple[list[int], list[int]]:
        return candidate_boundaries(
            self.switches,
            annotation.clip_start_ms,
            annotation.clip_end_ms,
            self.edge_ignore_ms,
        )

    def short_turn_presence(
        self,
        annotation: Annotation,
        min_overlap_ratio: float,
    ) -> dict[str, Any]:
        target_duration = annotation.end_ms - annotation.start_ms
        best_overlap_ms = 0
        best_label: str | None = None
        for turn in self.turns:
            if str(turn.get("state", "")) != "SPEAKER":
                continue
            overlap_ms = max(
                0,
                min(int(turn["end_ms"]), annotation.end_ms)
                - max(int(turn["start_ms"]), annotation.start_ms),
            )
            if overlap_ms <= best_overlap_ms:
                continue
            best_overlap_ms = overlap_ms
            best_label = speaker_label(
                turn.get("speaker_id"),
                self.mapping,
                str(turn.get("state", "")),
            )

        overlap_ratio = (
            best_overlap_ms / target_duration if target_duration > 0 else 0.0
        )
        return {
            "preserved": (
                best_label == annotation.current_speaker
                and overlap_ratio >= min_overlap_ratio
            ),
            "overlap_ratio": round(overlap_ratio, 4),
            "overlap_ms": best_overlap_ms,
            "candidate_speaker": best_label,
            "target_speaker": annotation.current_speaker,
        }

    def under_500_turns(self) -> list[dict[str, Any]]:
        turns: list[dict[str, Any]] = []
        for turn in self.turns:
            duration_ms = int(turn["end_ms"]) - int(turn["start_ms"])
            if (
                str(turn.get("state", "")) != "SPEAKER"
                or duration_ms <= 0
                or duration_ms >= 500
            ):
                continue
            turns.append(
                {
                    "start_ms": int(turn["start_ms"]),
                    "end_ms": int(turn["end_ms"]),
                    "duration_ms": duration_ms,
                    "speaker": speaker_label(
                        turn.get("speaker_id"),
                        self.mapping,
                        str(turn.get("state", "")),
                    ),
                }
            )
        return turns


def endpoint_sequence(sequence: str) -> tuple[str, str] | None:
    parts = sequence.split(">") if sequence else []
    if len(parts) < 2:
        return None
    return parts[0], parts[-1]


def evaluate_key_boundaries(
    annotations: Sequence[Annotation],
    candidates: Sequence[Candidate],
    tolerance_ms: int,
) -> tuple[dict[str, dict[str, Any]], dict[str, list[dict[str, Any]]]]:
    summaries: dict[str, dict[str, Any]] = {}
    details: dict[str, list[dict[str, Any]]] = {}
    for candidate in candidates:
        candidate_details: list[dict[str, Any]] = []
        for annotation in annotations:
            if annotation.item_type not in KEY_BOUNDARY_TYPES:
                continue
            if annotation.audio_id != candidate.spec.audio_id:
                continue
            detected_boundaries, excluded_edges = (
                candidate.internal_boundaries(annotation)
            )
            detected_clip_boundaries, excluded_clip_edges = (
                candidate.clip_boundaries(annotation)
            )
            boundary_scored = bool(annotation.true_boundaries)
            if boundary_scored:
                matched = match_boundaries(
                    annotation.true_boundaries,
                    detected_boundaries,
                    tolerance_ms,
                )
            else:
                matched = {
                    "truth_count": None,
                    "detected_count": None,
                    "matched_count": None,
                    "false_negatives": None,
                    "false_positives": None,
                    "matched_pairs": [],
                    "error_sum_ms": None,
                    "mean_abs_error_ms": None,
                    "median_abs_error_ms": None,
                    "max_abs_error_ms": None,
                }
            detected_sequence = candidate.detected_sequence(annotation)
            sequence_exact = (
                annotation.true_sequence == detected_sequence
                if annotation.true_sequence is not None
                else None
            )
            sequence_contained = (
                sequence_contains(detected_sequence, annotation.true_sequence)
                if annotation.true_sequence is not None
                else None
            )
            truth_endpoints = (
                (annotation.true_left, annotation.true_right)
                if annotation.true_left in {"A", "B"}
                and annotation.true_right in {"A", "B"}
                else None
            )
            detected_endpoints = endpoint_sequence(detected_sequence)
            candidate_details.append(
                {
                    "item_id": annotation.item_id,
                    "audio_id": annotation.audio_id,
                    "start_ms": annotation.start_ms,
                    "end_ms": annotation.end_ms,
                    "clip_start_ms": annotation.clip_start_ms,
                    "clip_end_ms": annotation.clip_end_ms,
                    "is_real_change": annotation.is_real_change,
                    "truth_sequence": annotation.true_sequence,
                    "detected_sequence": detected_sequence,
                    "boundary_scored": boundary_scored,
                    "sequence_exact": sequence_exact,
                    "sequence_contained": sequence_contained,
                    "endpoints_exact": (
                        truth_endpoints == detected_endpoints
                        if truth_endpoints is not None
                        and detected_endpoints is not None
                        else None
                    ),
                    "detected_switches_in_clip": len(
                        detected_clip_boundaries
                    ),
                    "edge_boundaries_excluded": excluded_clip_edges,
                    **matched,
                }
            )
        details[candidate.spec.label] = candidate_details
        matched_count = sum(
            item["matched_count"] or 0 for item in candidate_details
        )
        truth_count = sum(
            item["truth_count"] or 0 for item in candidate_details
        )
        detected_count = sum(
            item["detected_count"] or 0 for item in candidate_details
        )
        error_sum = sum(
            item["error_sum_ms"] or 0 for item in candidate_details
        )
        sequence_scored = [
            item
            for item in candidate_details
            if item["sequence_exact"] is not None
        ]
        boundary_scored = [
            item for item in candidate_details if item["boundary_scored"]
        ]
        sequence_exact_count = sum(
            bool(item["sequence_exact"]) for item in sequence_scored
        )
        sequence_contained_count = sum(
            bool(item["sequence_contained"])
            for item in sequence_scored
        )
        summaries[candidate.spec.label] = {
            "audio_id": candidate.spec.audio_id,
            "items": len(candidate_details),
            "boundary_scored_count": len(boundary_scored),
            "boundary_unscored_count": (
                len(candidate_details) - len(boundary_scored)
            ),
            "truth_boundaries": truth_count,
            "detected_boundaries": detected_count,
            "matched_boundaries": matched_count,
            "false_negatives": truth_count - matched_count,
            "false_positives": detected_count - matched_count,
            "boundary_recall": (
                round(matched_count / truth_count, 6)
                if truth_count
                else None
            ),
            "boundary_precision": (
                round(matched_count / detected_count, 6)
                if detected_count
                else None
            ),
            "mean_abs_error_ms": (
                round(error_sum / matched_count, 2)
                if matched_count
                else None
            ),
            "sequence_exact_count": sequence_exact_count,
            "sequence_contained_count": sequence_contained_count,
            "sequence_scored_count": len(sequence_scored),
            "sequence_mismatch_count": (
                len(sequence_scored) - sequence_exact_count
            ),
            "sequence_not_contained_count": (
                len(sequence_scored) - sequence_contained_count
            ),
        }
    return summaries, details


def evaluate_short_turns(
    annotations: Sequence[Annotation],
    candidates: Sequence[Candidate],
    *,
    min_overlap_ratio: float,
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    short_annotations = [
        annotation
        for annotation in annotations
        if annotation.item_type in SHORT_TURN_TYPES
    ]
    summary: dict[str, Any] = {
        "items": len(short_annotations),
        "truth_yes": sum(
            annotation.is_real_change == "YES"
            for annotation in short_annotations
        ),
        "truth_no": sum(
            annotation.is_real_change == "NO"
            for annotation in short_annotations
        ),
        "truth_overlap": sum(
            annotation.is_real_change == "OVERLAP"
            for annotation in short_annotations
        ),
    }
    candidate_results: list[dict[str, Any]] = []
    for candidate in candidates:
        details: list[dict[str, Any]] = []
        counters = {
            "true_turn_preserved": 0,
            "true_turn_removed": 0,
            "false_switch_removed": 0,
            "false_switch_preserved": 0,
            "overlap_preserved": 0,
            "overlap_removed": 0,
        }
        for annotation in short_annotations:
            if annotation.audio_id != candidate.spec.audio_id:
                continue
            presence = candidate.short_turn_presence(
                annotation,
                min_overlap_ratio,
            )
            if annotation.is_real_change == "YES":
                outcome = (
                    "true_turn_preserved"
                    if presence["preserved"]
                    else "true_turn_removed"
                )
            elif annotation.is_real_change == "NO":
                outcome = (
                    "false_switch_preserved"
                    if presence["preserved"]
                    else "false_switch_removed"
                )
            else:
                outcome = (
                    "overlap_preserved"
                    if presence["preserved"]
                    else "overlap_removed"
                )
            counters[outcome] += 1
            details.append(
                {
                    "item_id": annotation.item_id,
                    "audio_id": annotation.audio_id,
                    "start_ms": annotation.start_ms,
                    "end_ms": annotation.end_ms,
                    "truth": annotation.is_real_change,
                    "outcome": outcome,
                    **presence,
                }
            )

        under_500 = candidate.under_500_turns()
        summary_data = candidate.result.get("summary", {})
        turn_count = int(summary_data.get("turns", 0))
        under_500_count = int(
            summary_data.get("real_under_500ms", len(under_500))
        )
        candidate_results.append(
            {
                "label": candidate.spec.label,
                "audio_id": candidate.spec.audio_id,
                "result_path": str(candidate.spec.path),
                "turn_count": turn_count,
                "under_500ms": under_500_count,
                "under_500ms_ratio": (
                    round(under_500_count / turn_count, 6)
                    if turn_count
                    else None
                ),
                "unknown_ratio": summary_data.get("unknown_window_ratio"),
                "overlap_ratio": summary_data.get("overlap_window_ratio"),
                "speaker_count": summary_data.get("speaker_count"),
                "zero_duration_turns": summary_data.get(
                    "zero_duration_turns"
                ),
                **counters,
                "annotation_error_count": (
                    counters["true_turn_removed"]
                    + counters["false_switch_preserved"]
                ),
                "under_500_turns": under_500,
                "items": details,
            }
        )
    return summary, candidate_results


def validation_summary(
    annotations: Sequence[Annotation],
) -> dict[str, Any]:
    errors = [
        {
            "item_id": annotation.item_id,
            "messages": annotation.errors,
        }
        for annotation in annotations
        if annotation.errors
    ]
    warnings = [
        {
            "item_id": annotation.item_id,
            "messages": annotation.warnings,
        }
        for annotation in annotations
        if annotation.warnings
    ]
    return {
        "complete": not errors,
        "item_count": len(annotations),
        "valid_item_count": len(annotations) - len(errors),
        "errors": errors,
        "warnings": warnings,
    }


def load_candidates(
    specs: Sequence[CaseSpec],
    mappings: dict[str, dict[str, str]],
    *,
    edge_ignore_ms: int,
) -> list[Candidate]:
    candidates: list[Candidate] = []
    labels: set[str] = set()
    for spec in specs:
        if spec.label in labels:
            raise ValueError(f"duplicate candidate label: {spec.label}")
        labels.add(spec.label)
        result = json.loads(spec.path.read_text(encoding="utf-8"))
        mapping = mappings.get(spec.audio_id)
        if mapping is None:
            mapping = infer_speaker_mapping(result)
        candidates.append(
            Candidate(
                spec,
                result,
                mapping,
                edge_ignore_ms=edge_ignore_ms,
            )
        )
    return candidates


def discover_candidate_cases(
    directory: Path,
    audio_ids: Iterable[str],
) -> list[CaseSpec]:
    cases: list[CaseSpec] = []
    for audio_id in sorted(audio_ids):
        pattern = f"global_w800_island*_{audio_id}.json"
        for path in sorted(directory.glob(pattern)):
            cases.append(
                CaseSpec(
                    label=path.stem,
                    audio_id=audio_id,
                    path=path,
                )
            )
    return cases


def candidate_variant(candidate: dict[str, Any]) -> str:
    label = str(candidate["label"])
    suffix = f"_{candidate['audio_id']}"
    if label.endswith(suffix):
        return label[: -len(suffix)]
    return label


def build_recommendations(
    candidate_results: Sequence[dict[str, Any]],
    expected_audio_ids: set[str],
    key_boundary_summaries: dict[str, dict[str, Any]],
) -> list[dict[str, Any]]:
    grouped: dict[str, list[dict[str, Any]]] = {}
    for candidate in candidate_results:
        grouped.setdefault(candidate_variant(candidate), []).append(candidate)

    recommendations: list[dict[str, Any]] = []
    for variant, items in grouped.items():
        covered_audio_ids = {item["audio_id"] for item in items}
        if covered_audio_ids != expected_audio_ids:
            continue
        hard_constraints_ok = all(
            item.get("speaker_count") == 2
            and item.get("zero_duration_turns") == 0
            and item.get("unknown_ratio") is not None
            and item["unknown_ratio"] < 0.03
            for item in items
        )
        short_errors = sum(
            item["annotation_error_count"] for item in items
        )
        boundary_errors = sum(
            key_boundary_summaries[item["label"]]["false_negatives"]
            + key_boundary_summaries[item["label"]]["false_positives"]
            for item in items
        )
        sequence_errors = sum(
            key_boundary_summaries[item["label"]][
                "sequence_not_contained_count"
            ]
            for item in items
        )
        total_turns = sum(item["turn_count"] for item in items)
        total_under_500 = sum(item["under_500ms"] for item in items)
        recommendations.append(
            {
                "variant": variant,
                "candidate_labels": [item["label"] for item in items],
                "audio_ids": sorted(covered_audio_ids),
                "hard_constraints_ok": hard_constraints_ok,
                "annotation_error_count": short_errors,
                "boundary_error_count": boundary_errors,
                "sequence_error_count": sequence_errors,
                "under_500ms": total_under_500,
                "turn_count": total_turns,
                "under_500ms_ratio": (
                    round(total_under_500 / total_turns, 6)
                    if total_turns
                    else None
                ),
            }
        )

    return sorted(
        recommendations,
        key=lambda item: (
            not item["hard_constraints_ok"],
            item["annotation_error_count"],
            item["sequence_error_count"],
            item["boundary_error_count"],
            item["under_500ms_ratio"] or 1.0,
            item["variant"],
        ),
    )


def render_markdown(report: dict[str, Any]) -> str:
    lines = [
        "# Speaker Annotation Evaluation",
        "",
        f"- Annotation: `{report['annotation_path']}`",
        f"- Status: `{report['status']}`",
        f"- Boundary tolerance: `{report['boundary_tolerance_ms']} ms`",
        f"- Short-turn minimum overlap: `{report['short_overlap_ratio']:.0%}`",
        "",
        "## Validation",
        "",
    ]

    validation = report["validation"]
    lines.extend(
        [
            f"- Items: {validation['item_count']}",
            f"- Valid items: {validation['valid_item_count']}",
            f"- Errors: {len(validation['errors'])}",
            f"- Warnings: {len(validation['warnings'])}",
            "",
        ]
    )
    if validation["errors"]:
        lines.extend(["| Item | Error |", "|---|---|"])
        for item in validation["errors"]:
            lines.append(
                f"| `{item['item_id']}` | {'; '.join(item['messages'])} |"
            )
        lines.append("")
    if validation["warnings"]:
        lines.extend(["Warnings:", ""])
        for item in validation["warnings"]:
            lines.append(
                f"- `{item['item_id']}`: {'; '.join(item['messages'])}"
            )
        lines.append("")

    if report["status"] != "complete":
        lines.extend(
            [
                "Metrics are withheld until every annotation row is valid.",
                "",
            ]
        )
        return "\n".join(lines)

    lines.extend(
        [
            "## Key Boundaries",
            "",
            "| Candidate | Audio | Truth | Detected | Matched | FP | FN | "
            "Mean ms | Sequence exact | Sequence contained |",
            "|---|---|---:|---:|---:|---:|---:|---:|---:|---:|",
        ]
    )
    for label, summary in report["key_boundary_summary"].items():
        lines.append(
            "| {label} | {audio_id} | {truth_boundaries} | "
            "{detected_boundaries} | {matched_boundaries} | "
            "{false_positives} | {false_negatives} | "
            "{mean_abs_error_ms} | {sequence_exact_count}/"
            "{sequence_scored_count} | {sequence_contained_count}/"
            "{sequence_scored_count} |".format(
                label=label,
                **summary,
            )
        )
    lines.extend(
        [
            "",
            "Per-item details:",
            "",
        ]
    )
    for label, details in report["key_boundary_details"].items():
        lines.extend(
            [
                f"### {label}",
                "",
                "| Item | Truth | Detected | Matched | FP | FN | Mean ms | "
                "Exact sequence | Contained sequence |",
                "|---|---:|---:|---:|---:|---:|---:|---|---|",
            ]
        )
        for item in details:
            lines.append(
                "| `{item_id}` | {truth_count} | {detected_count} | "
                "{matched_count} | {false_positives} | "
                "{false_negatives} | {mean_abs_error_ms} | "
                "{sequence_exact} | {sequence_contained} |".format(**item)
            )
        lines.append("")

    short_summary = report["short_turn_summary"]
    lines.extend(
        [
            "## Short Turns",
            "",
            f"- Human YES: {short_summary['truth_yes']}",
            f"- Human NO: {short_summary['truth_no']}",
            f"- Human OVERLAP: {short_summary['truth_overlap']}",
            "",
            "| Candidate | Audio | Turns | <500 ms ratio | True retained | "
            "True removed | False retained | False removed | Overlap |",
            "|---|---|---:|---:|---:|---:|---:|---:|---:|",
        ]
    )
    for candidate in report["short_turn_candidates"]:
        lines.append(
            "| {label} | {audio_id} | {turn_count} | {ratio} | "
            "{true_turn_preserved} | {true_turn_removed} | "
            "{false_switch_preserved} | {false_switch_removed} | "
            "{overlap_preserved}/{overlap_removed} |".format(
                ratio=(
                    f"{candidate['under_500ms_ratio']:.2%}"
                    if candidate["under_500ms_ratio"] is not None
                    else ""
                ),
                **candidate,
            )
        )
    lines.append("")

    recommendations = report.get("recommendations", [])
    if recommendations:
        lines.extend(
            [
                "## Recommendation",
                "",
                "| Rank | Variant | Hard constraints | Short errors | "
                "Sequence hard errors | Boundary errors | <500 ms ratio |",
                "|---:|---|---|---:|---:|---:|---:|",
            ]
        )
        for rank, recommendation in enumerate(recommendations, start=1):
            ratio = recommendation["under_500ms_ratio"]
            lines.append(
                "| {rank} | `{variant}` | {hard_constraints_ok} | "
                "{annotation_error_count} | {sequence_error_count} | "
                "{boundary_error_count} | "
                "{ratio} |".format(
                    rank=rank,
                    ratio=f"{ratio:.2%}" if ratio is not None else "",
                    **recommendation,
                )
            )
        lines.extend(
            [
                "",
            ]
        )
    return "\n".join(lines)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--annotation", required=True)
    parser.add_argument(
        "--case",
        action="append",
        type=parse_case,
        default=[],
        help=(
            "AUDIO_ID=RESULT_JSON or LABEL=AUDIO_ID=RESULT_JSON; "
            "repeat for each candidate"
        ),
    )
    parser.add_argument(
        "--manifest",
        default="",
        help=(
            "annotation manifest containing speaker_mappings; defaults to "
            "manifest.json next to annotation.csv"
        ),
    )
    parser.add_argument(
        "--candidate-dir",
        default="",
        help=(
            "discover global_w800_island*_AUDIO.json files in this "
            "directory instead of passing every --case"
        ),
    )
    parser.add_argument(
        "--boundary-tolerance-ms",
        type=int,
        default=300,
    )
    parser.add_argument(
        "--edge-ignore-ms",
        type=int,
        default=1,
        help="do not score detected switches within this distance of range edges",
    )
    parser.add_argument(
        "--short-overlap-ratio",
        type=float,
        default=0.5,
    )
    parser.add_argument("--output-json", default="")
    parser.add_argument("--output-md", default="")
    parser.add_argument(
        "--allow-incomplete",
        action="store_true",
        help="write validation output and return success while annotations are blank",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.boundary_tolerance_ms < 0:
        raise SystemExit("--boundary-tolerance-ms must be non-negative")
    if args.edge_ignore_ms < 0:
        raise SystemExit("--edge-ignore-ms must be non-negative")
    if not 0 < args.short_overlap_ratio <= 1:
        raise SystemExit("--short-overlap-ratio must be in (0, 1]")

    annotation_path = Path(args.annotation)
    annotations = load_annotations(annotation_path)
    validation = validation_summary(annotations)

    manifest_path = (
        Path(args.manifest)
        if args.manifest
        else annotation_path.parent / "manifest.json"
    )
    mappings = load_speaker_mappings(manifest_path)
    case_specs = list(args.case)
    if args.candidate_dir:
        case_specs.extend(
            discover_candidate_cases(
                Path(args.candidate_dir),
                {
                    annotation.audio_id
                    for annotation in annotations
                    if annotation.audio_id
                },
            )
        )
    candidates = load_candidates(
        case_specs,
        mappings,
        edge_ignore_ms=args.edge_ignore_ms,
    )
    if validation["complete"] and not candidates:
        raise SystemExit(
            "at least one candidate is required when annotations are complete"
        )

    report: dict[str, Any] = {
        "annotation_path": str(annotation_path.resolve()),
        "status": "complete" if validation["complete"] else "incomplete",
        "validation": validation,
        "boundary_tolerance_ms": args.boundary_tolerance_ms,
        "edge_ignore_ms": args.edge_ignore_ms,
        "short_overlap_ratio": args.short_overlap_ratio,
    }
    if validation["complete"]:
        boundary_summary, boundary_details = evaluate_key_boundaries(
            annotations,
            candidates,
            args.boundary_tolerance_ms,
        )
        short_summary, short_candidates = evaluate_short_turns(
            annotations,
            candidates,
            min_overlap_ratio=args.short_overlap_ratio,
        )
        report["key_boundary_summary"] = boundary_summary
        report["key_boundary_details"] = boundary_details
        report["short_turn_summary"] = short_summary
        report["short_turn_candidates"] = short_candidates
        expected_audio_ids = {
            annotation.audio_id
            for annotation in annotations
            if annotation.audio_id
        }
        report["recommendations"] = build_recommendations(
            short_candidates,
            expected_audio_ids,
            boundary_summary,
        )
    else:
        report["key_boundary_summary"] = {}
        report["key_boundary_details"] = {}
        report["short_turn_summary"] = {}
        report["short_turn_candidates"] = []
        report["recommendations"] = []

    output_json = Path(args.output_json) if args.output_json else None
    output_md = Path(args.output_md) if args.output_md else None
    if output_json is not None:
        output_json.parent.mkdir(parents=True, exist_ok=True)
        output_json.write_text(
            json.dumps(report, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
    if output_md is not None:
        output_md.parent.mkdir(parents=True, exist_ok=True)
        output_md.write_text(render_markdown(report), encoding="utf-8")

    print(json.dumps(report, ensure_ascii=False, indent=2))
    if not validation["complete"] and not args.allow_incomplete:
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

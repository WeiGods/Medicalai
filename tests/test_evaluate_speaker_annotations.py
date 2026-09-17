import csv
import json

from asr_validation.annotation_tools.evaluate_speaker_annotations import (
    CaseSpec,
    Candidate,
    evaluate_key_boundaries,
    evaluate_short_turns,
    load_annotations,
    validation_summary,
)


ANNOTATION_FIELDS = [
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
]


def write_annotation_csv(path, rows):
    with path.open("w", encoding="utf-8", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=ANNOTATION_FIELDS)
        writer.writeheader()
        for row in rows:
            writer.writerow(
                {
                    field: row.get(field, "")
                    for field in ANNOTATION_FIELDS
                }
            )


def write_annotation_tsv(path, rows):
    with path.open("w", encoding="utf-8", newline="") as output:
        writer = csv.DictWriter(
            output,
            fieldnames=ANNOTATION_FIELDS,
            delimiter="\t",
        )
        writer.writeheader()
        for row in rows:
            writer.writerow(
                {
                    field: row.get(field, "")
                    for field in ANNOTATION_FIELDS
                }
            )


def build_candidate(path):
    payload = {
        "summary": {
            "turns": 6,
            "speaker_count": 2,
            "real_under_500ms": 1,
            "unknown_window_ratio": 0.01,
            "overlap_window_ratio": 0.0,
            "zero_duration_turns": 0,
        },
        "turns": [
            {"start_ms": 1000, "end_ms": 2000, "state": "SPEAKER", "speaker_id": 0},
            {"start_ms": 2000, "end_ms": 3000, "state": "SPEAKER", "speaker_id": 1},
            {"start_ms": 4000, "end_ms": 5000, "state": "SPEAKER", "speaker_id": 1},
            {"start_ms": 5000, "end_ms": 5500, "state": "SPEAKER", "speaker_id": 0},
            {"start_ms": 5500, "end_ms": 6000, "state": "SPEAKER", "speaker_id": 1},
            {"start_ms": 7000, "end_ms": 7200, "state": "SPEAKER", "speaker_id": 0},
            {"start_ms": 8000, "end_ms": 8300, "state": "SPEAKER", "speaker_id": 0},
        ],
        "switches": [
            {"boundary_ms": 2000},
            {"boundary_ms": 5000},
            {"boundary_ms": 5600},
            {"boundary_ms": 7000},
            {"boundary_ms": 7200},
            {"boundary_ms": 8000},
        ],
    }
    path.write_text(json.dumps(payload), encoding="utf-8")
    return Candidate(
        CaseSpec("candidate", "audio1", path),
        payload,
        {"0": "A", "1": "B"},
        edge_ignore_ms=1,
    )


def test_evaluator_scores_boundaries_and_short_turn_outcomes(tmp_path):
    annotation_path = tmp_path / "annotation.csv"
    write_annotation_csv(
        annotation_path,
        [
            {
                "item_id": "boundary-a",
                "audio_id": "audio1",
                "type": "key_boundary",
                "start_ms": 1000,
                "end_ms": 3000,
                "current_speaker": "A>B",
                "is_real_change": "YES",
                "true_left": "A",
                "true_right": "B",
                "true_boundary_ms": "2000",
                "notes": "A>B",
            },
            {
                "item_id": "boundary-b",
                "audio_id": "audio1",
                "type": "key_boundary",
                "start_ms": 4000,
                "end_ms": 6000,
                "current_speaker": "B>A>B",
                "is_real_change": "YES",
                "true_left": "B",
                "true_right": "B",
                "true_boundary_ms": "5000;5500",
                "notes": "B>A>B",
            },
            {
                "item_id": "short-real",
                "audio_id": "audio1",
                "type": "short_turn",
                "start_ms": 7000,
                "end_ms": 7200,
                "current_speaker": "A",
                "is_real_change": "YES",
                "true_boundary_ms": "7000;7200",
            },
            {
                "item_id": "short-false",
                "audio_id": "audio1",
                "type": "short_turn",
                "start_ms": 8000,
                "end_ms": 8200,
                "current_speaker": "B",
                "is_real_change": "NO",
            },
        ],
    )
    annotations = load_annotations(annotation_path)
    assert validation_summary(annotations)["complete"]

    candidate = build_candidate(tmp_path / "candidate.json")
    boundary_summary, boundary_details = evaluate_key_boundaries(
        annotations,
        [candidate],
        tolerance_ms=100,
    )
    assert boundary_summary["candidate"]["matched_boundaries"] == 3
    assert boundary_summary["candidate"]["false_positives"] == 0
    assert boundary_summary["candidate"]["false_negatives"] == 0
    assert boundary_summary["candidate"]["mean_abs_error_ms"] == 33.33
    assert all(
        item["sequence_exact"]
        for item in boundary_details["candidate"]
    )

    short_summary, short_candidates = evaluate_short_turns(
        annotations,
        [candidate],
        min_overlap_ratio=0.5,
    )
    assert short_summary["truth_yes"] == 1
    assert short_summary["truth_no"] == 1
    assert short_candidates[0]["true_turn_removed"] == 0
    assert short_candidates[0]["false_switch_removed"] == 1
    assert short_candidates[0]["annotation_error_count"] == 0


def test_evaluator_reports_blank_annotations_as_incomplete(tmp_path):
    annotation_path = tmp_path / "annotation.csv"
    write_annotation_csv(
        annotation_path,
        [
            {
                "item_id": "short-blank",
                "audio_id": "audio1",
                "type": "short_turn",
                "start_ms": 0,
                "end_ms": 200,
                "current_speaker": "A",
            }
        ],
    )

    summary = validation_summary(load_annotations(annotation_path))

    assert not summary["complete"]
    assert summary["errors"][0]["messages"] == ["missing is_real_change"]


def test_evaluator_accepts_tsv_without_boundary_truth(tmp_path):
    annotation_path = tmp_path / "annotation.tsv"
    write_annotation_tsv(
        annotation_path,
        [
            {
                "item_id": "boundary-clip",
                "audio_id": "audio1",
                "type": "key_boundary",
                "start_ms": 2500,
                "end_ms": 2600,
                "current_speaker": "B",
                "is_real_change": "YES",
                "true_left": "A",
                "true_right": "B",
                "notes": "A>B",
                "clip_start_ms": "1000",
                "clip_end_ms": "3000",
            }
        ],
    )
    annotations = load_annotations(annotation_path)
    summary = validation_summary(annotations)
    assert summary["complete"]
    assert summary["warnings"][0]["messages"] == [
        (
            "true_boundary_ms is missing; only sequence and short-turn "
            "outcomes are evaluated"
        )
    ]

    candidate = build_candidate(tmp_path / "candidate.json")
    boundary_summary, boundary_details = evaluate_key_boundaries(
        annotations,
        [candidate],
        tolerance_ms=100,
    )
    assert boundary_summary["candidate"]["boundary_scored_count"] == 0
    assert boundary_summary["candidate"]["false_positives"] == 0
    assert boundary_summary["candidate"]["false_negatives"] == 0
    assert boundary_summary["candidate"]["sequence_exact_count"] == 1
    assert boundary_summary["candidate"]["sequence_scored_count"] == 1
    assert boundary_summary["candidate"]["sequence_not_contained_count"] == 0
    assert boundary_details["candidate"][0]["sequence_exact"] is True

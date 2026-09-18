"""Offline structure helpers for speaker turns and ASR sentence spans.

The ASR engine supplies character timestamps.  This module keeps the offline
pipeline independent from the realtime session: it builds fixed speaker
windows, maps finalized speaker turns onto ASR sentences, and splits a
sentence at a real speaker boundary without rewriting its text.
"""

from __future__ import annotations

from typing import Iterable, Sequence
import unicodedata


SPEAKER_STATE = "SPEAKER"
UNKNOWN_STATE = "UNKNOWN"
OVERLAP_STATE = "OVERLAP"
DEFAULT_MIN_SPLIT_RUN_MS = 240
DEFAULT_MIN_SPLIT_RUN_CHARS = 3
SENTENCE_END_CHARS = frozenset("。！？!?；;")
PUNCTUATION_CHARS = frozenset("，。！？；：、,.!?;:…")


def _turn_value(turn, name):
    if isinstance(turn, dict):
        return turn.get(name)
    return getattr(turn, name)


def _is_content_char(char: str) -> bool:
    category = unicodedata.category(char)
    return not (category.startswith("P") or category.startswith("Z") or char.isspace())


def prepare_offline_punctuation(text: str, punctuate) -> str:
    """Keep ASR punctuation and only punctuate a punctuation-free decode.

    Fun-ASR can already emit punctuation and sentence boundaries.  Running a
    second punctuation model over that text duplicates punctuation without
    changing the content-only validation result.  When punctuation is present,
    preserve the ASR output verbatim.
    """

    raw = str(text or "").strip()
    if not raw:
        return raw
    if any(char in PUNCTUATION_CHARS for char in raw):
        return raw

    try:
        candidate = str(punctuate(raw) or "").strip()
    except Exception:
        return raw
    if not candidate:
        return raw
    if "".join(char for char in candidate if _is_content_char(char)) != "".join(
        char for char in raw if _is_content_char(char)
    ):
        return raw
    return candidate


def align_timestamps_to_text(
    text: str,
    timestamps: Iterable[dict],
) -> list[dict]:
    """Trim CTC timestamp tokens to text retained after offline cleanup.

    Hallucination cleanup can shorten an ASR decode while its timestamp list
    still describes the complete model output.  Those timestamps are safe to
    use only when their token text starts with the retained text.  Return an
    empty list on any mismatch so the caller can keep its linear fallback.
    """

    value = str(text or "").strip()
    if not value:
        return []

    token_items = []
    token_parts = []
    for item in timestamps or []:
        if not isinstance(item, dict):
            continue
        token = str(item.get("token") or "")
        if not token:
            continue
        token_items.append((token, dict(item)))
        token_parts.append(token)

    token_text = "".join(token_parts)
    if not token_text.startswith(value):
        return []
    if len(token_text) == len(value):
        return [item for _token, item in token_items]

    aligned = []
    consumed = 0
    for token, item in token_items:
        remaining = len(value) - consumed
        if remaining <= 0:
            break
        if len(token) <= remaining:
            aligned.append(item)
            consumed += len(token)
            continue

        start_time = float(item.get("start_time", 0.0))
        end_time = float(item.get("end_time", start_time))
        fraction = remaining / len(token)
        item["token"] = token[:remaining]
        item["end_time"] = start_time + (end_time - start_time) * fraction
        aligned.append(item)
        consumed += remaining

    return aligned if consumed == len(value) else []


def _split_sentences(text: str) -> list[str]:
    value = str(text or "").strip()
    if not value:
        return []

    sentences = []
    start = 0
    for index, char in enumerate(value):
        if char not in SENTENCE_END_CHARS:
            continue
        sentence = value[start : index + 1].strip()
        if sentence:
            sentences.append(sentence)
        start = index + 1
    tail = value[start:].strip()
    if tail:
        sentences.append(tail)
    return sentences


def build_timestamped_sentence_spans(
    raw_text: str,
    punctuated_text: str,
    timestamps: Iterable[dict],
    segment_start_ms: int,
    segment_end_ms: int,
) -> list[dict]:
    """Split offline text into sentences with content-only character times.

    The punctuation model adds punctuation after ASR, so the punctuated text
    and raw decode can have different lengths.  CTC timestamps describe the
    raw decode.  Build a content-only timeline first, then advance the cursor
    by each sentence's content length.  Keeping punctuation out of this
    timeline prevents a trailing punctuation timestamp from becoming the
    first character time of the next sentence.
    """

    raw = str(raw_text or "").strip()
    punctuated = str(punctuated_text or "").strip()
    sentences = _split_sentences(punctuated)
    if not sentences:
        return []
    if "".join(char for char in punctuated if _is_content_char(char)) != "".join(
        char for char in raw if _is_content_char(char)
    ):
        sentences = [raw]

    character_times = []
    for item in timestamps or []:
        if not isinstance(item, dict):
            continue
        token = str(item.get("token") or "")
        if not token:
            continue
        start_ms = int(
            segment_start_ms
            + round(float(item.get("start_time", 0.0)) * 1000)
        )
        end_ms = int(
            segment_start_ms
            + round(float(item.get("end_time", 0.0)) * 1000)
        )
        start_ms = max(int(segment_start_ms), min(int(segment_end_ms), start_ms))
        end_ms = max(start_ms, min(int(segment_end_ms), end_ms))
        for offset, char in enumerate(token):
            if not _is_content_char(char):
                continue
            if len(token) == 1:
                char_start, char_end = start_ms, end_ms
            else:
                char_start = start_ms + round(
                    (end_ms - start_ms) * offset / len(token)
                )
                char_end = start_ms + round(
                    (end_ms - start_ms) * (offset + 1) / len(token)
                )
            character_times.append((char, char_start, char_end))

    content_lengths = [
        sum(1 for char in sentence if _is_content_char(char))
        for sentence in sentences
    ]
    total_content = sum(content_lengths)
    use_character_times = bool(raw) and len(character_times) == total_content

    spans = []
    cursor = 0
    for sentence, content_length in zip(sentences, content_lengths):
        if content_length <= 0:
            continue
        if use_character_times:
            first = cursor
            last = min(len(character_times) - 1, cursor + content_length - 1)
            start_ms = int(character_times[first][1])
            end_ms = int(character_times[last][2])
            sentence_char_times = character_times[first : last + 1]
        else:
            start_ms = int(
                segment_start_ms
                + (segment_end_ms - segment_start_ms)
                * cursor
                / max(1, total_content)
            )
            cursor += content_length
            end_ms = int(
                segment_start_ms
                + (segment_end_ms - segment_start_ms)
                * cursor
                / max(1, total_content)
            )
            sentence_char_times = []

        spans.append(
            {
                "text": sentence,
                "start": start_ms,
                "end": end_ms,
                "_char_times": sentence_char_times,
            }
        )
        cursor += content_length
    return spans


def build_speaker_windows(
    segments: Iterable[Sequence],
    window_ms: int = 800,
    hop_ms: int = 200,
) -> list[dict]:
    """Build fixed windows that do not cross a confirmed VAD segment."""

    if window_ms <= 0:
        raise ValueError("window_ms must be positive")
    if hop_ms <= 0 or hop_ms > window_ms:
        raise ValueError("hop_ms must be in (0, window_ms]")

    windows: list[dict] = []
    for segment_index, segment in enumerate(segments):
        start_ms = int(segment[0])
        end_ms = int(segment[1])
        duration_ms = end_ms - start_ms
        if duration_ms <= 0:
            continue
        if duration_ms <= window_ms:
            windows.append(
                {
                    "segment_index": segment_index,
                    "segment_start_ms": start_ms,
                    "segment_end_ms": end_ms,
                    "start_ms": start_ms,
                    "end_ms": end_ms,
                }
            )
            continue

        starts = list(range(start_ms, end_ms - window_ms + 1, hop_ms))
        if not starts or starts[-1] + window_ms < end_ms:
            # Use a final context window instead of a tiny tail embedding.
            starts.append(max(start_ms, end_ms - window_ms))
        for window_start in starts:
            windows.append(
                {
                    "segment_index": segment_index,
                    "segment_start_ms": start_ms,
                    "segment_end_ms": end_ms,
                    "start_ms": int(window_start),
                    "end_ms": int(window_start + window_ms),
                }
            )
    return windows


def _known_turns(turns: Sequence):
    return [
        turn
        for turn in turns
        if _turn_value(turn, "speaker_id") is not None
        and _turn_value(turn, "state") == SPEAKER_STATE
    ]


def _speaker_at(
    center_ms: float,
    turns: Sequence,
    max_gap_ms: int = 1000,
) -> int | None:
    known = _known_turns(turns)
    if not known:
        return None

    for turn in known:
        if (
            float(_turn_value(turn, "start_ms"))
            <= center_ms
            < float(_turn_value(turn, "end_ms"))
        ):
            return int(_turn_value(turn, "speaker_id"))

    nearest = min(
        known,
        key=lambda turn: min(
            abs(center_ms - float(_turn_value(turn, "start_ms"))),
            abs(center_ms - float(_turn_value(turn, "end_ms"))),
        ),
    )
    distance = min(
        abs(center_ms - float(_turn_value(nearest, "start_ms"))),
        abs(center_ms - float(_turn_value(nearest, "end_ms"))),
    )
    if distance <= max_gap_ms:
        return int(_turn_value(nearest, "speaker_id"))
    return None


def _speaker_from_overlap(sentence: dict, turns: Sequence) -> int | None:
    sentence_start = int(sentence.get("start") or 0)
    sentence_end = int(sentence.get("end") or sentence_start)
    midpoint = (sentence_start + sentence_end) / 2.0
    matches = []
    for turn in _known_turns(turns):
        start_ms = int(_turn_value(turn, "start_ms"))
        end_ms = int(_turn_value(turn, "end_ms"))
        overlap_ms = max(0, min(sentence_end, end_ms) - max(sentence_start, start_ms))
        contains_midpoint = start_ms <= midpoint < end_ms
        matches.append(
            (
                overlap_ms,
                int(contains_midpoint),
                -int(end_ms - start_ms),
                int(_turn_value(turn, "speaker_id")),
            )
        )
    if matches:
        best = max(matches)
        if best[0] > 0 or best[1] > 0:
            return best[3]
    return _speaker_at(midpoint, turns)


def _split_by_character_speakers(
    sentence: dict,
    turns: Sequence,
    max_gap_ms: int,
    min_run_ms: int,
    min_run_chars: int,
) -> list[dict] | None:
    text = str(sentence.get("text") or "")
    char_times = list(sentence.get("_char_times") or [])
    content_positions = [
        index for index, char in enumerate(text) if _is_content_char(char)
    ]
    if not text or not content_positions or len(content_positions) != len(char_times):
        return None

    labels = [
        _speaker_at(
            (float(start_ms) + float(end_ms)) / 2.0,
            turns,
            max_gap_ms=max_gap_ms,
        )
        for _char, start_ms, end_ms in char_times
    ]

    raw_runs = []
    run_start = 0
    for index in range(1, len(labels) + 1):
        if index < len(labels) and labels[index] == labels[run_start]:
            continue
        raw_runs.append((run_start, index - 1, labels[run_start]))
        run_start = index

    runs = _resolve_weak_speaker_runs(
        raw_runs,
        char_times,
        turns,
        min_run_ms=min_run_ms,
        min_run_chars=min_run_chars,
    )
    if len(runs) <= 1:
        return None

    cuts = [0] + [content_positions[entry_start] for entry_start, _entry_end, _spk in runs[1:]]
    cuts.append(len(text))
    parts = []
    for run_index, (entry_start, entry_end, speaker_id) in enumerate(runs):
        text_start = cuts[run_index]
        text_end = cuts[run_index + 1]
        part_text = text[text_start:text_end].strip()
        if not part_text:
            return None
        parts.append(
            {
                "text": part_text,
                "start": int(char_times[entry_start][1]),
                "end": int(char_times[entry_end][2]),
                "spk": speaker_id,
            }
        )
    return parts


def _resolve_weak_speaker_runs(
    runs: Sequence[tuple[int, int, int | None]],
    char_times: Sequence[Sequence],
    turns: Sequence | None = None,
    *,
    min_run_ms: int,
    min_run_chars: int,
) -> list[tuple[int, int, int | None]]:
    """Absorb boundary jitter before turning runs into sentence cuts.

    A one-character token or a short tail can be assigned to the other speaker
    because of timestamp and embedding-window jitter.  Treat those runs as
    noise unless they are long enough to be a plausible spoken turn.
    """

    if min_run_ms < 0:
        raise ValueError("min_run_ms must be non-negative")
    if min_run_chars < 1:
        raise ValueError("min_run_chars must be positive")

    resolved = [(start, end, speaker_id) for start, end, speaker_id in runs]
    while len(resolved) > 1:
        weak_index = next(
            (
                index
                for index, run in enumerate(resolved)
                if not _is_strong_speaker_run(
                    run,
                    char_times,
                    min_run_ms=min_run_ms,
                    min_run_chars=min_run_chars,
                )
                and not _is_tracker_supported_edge_run(
                    index,
                    resolved,
                    char_times,
                    turns,
                    min_run_ms=min_run_ms,
                    min_run_chars=min_run_chars,
                )
            ),
            None,
        )
        if weak_index is None:
            break

        if weak_index == 0:
            replacement = resolved[1][2]
        elif weak_index == len(resolved) - 1:
            replacement = resolved[-2][2]
        else:
            left = resolved[weak_index - 1]
            right = resolved[weak_index + 1]
            weak_speaker = resolved[weak_index][2]
            if (
                weak_speaker is not None
                and weak_speaker != left[2]
                and weak_speaker != right[2]
                and _is_strong_speaker_run(
                    left,
                    char_times,
                    min_run_ms=min_run_ms,
                    min_run_chars=min_run_chars,
                )
                and _is_strong_speaker_run(
                    right,
                    char_times,
                    min_run_ms=min_run_ms,
                    min_run_chars=min_run_chars,
                )
            ):
                # A short known-speaker island between two strong, different
                # speakers is a plausible interjection, not boundary jitter.
                break
            if left[2] == right[2]:
                replacement = left[2]
            else:
                replacement = max(
                    (left, right),
                    key=lambda run: _run_strength(run, char_times),
                )[2]

        start, end, _speaker_id = resolved[weak_index]
        resolved[weak_index] = (start, end, replacement)
        resolved = _merge_adjacent_runs(resolved)

    if len(resolved) == 1:
        return resolved

    if all(
        not _is_strong_speaker_run(
            run,
            char_times,
            min_run_ms=min_run_ms,
            min_run_chars=min_run_chars,
        )
        for run in resolved
    ):
        return []
    return resolved


def _is_tracker_supported_edge_run(
    index: int,
    runs: Sequence[tuple[int, int, int | None]],
    char_times: Sequence[Sequence],
    turns: Sequence | None,
    *,
    min_run_ms: int,
    min_run_chars: int,
) -> bool:
    """Keep a short edge interjection when finalized turns support it.

    ASR sentences often start with a one-character reply (for example
    ``有``) that the speaker tracker correctly marked as a short turn.  The
    character itself is shorter than ``min_run_ms``, so use the enclosing
    tracker turn as the stronger evidence instead of treating it as jitter.
    """

    if turns is None or index not in {0, len(runs) - 1}:
        return False

    run = runs[index]
    adjacent = runs[1] if index == 0 else runs[-2]
    if (
        run[2] is None
        or adjacent[2] is None
        or run[2] == adjacent[2]
        or not _is_strong_speaker_run(
            adjacent,
            char_times,
            min_run_ms=min_run_ms,
            min_run_chars=min_run_chars,
        )
    ):
        return False

    run_start_ms = int(char_times[run[0]][1])
    run_end_ms = int(char_times[run[1]][2])
    for turn in _known_turns(turns):
        if int(_turn_value(turn, "speaker_id")) != run[2]:
            continue
        turn_start_ms = int(_turn_value(turn, "start_ms"))
        turn_end_ms = int(_turn_value(turn, "end_ms"))
        if (
            turn_start_ms < run_start_ms
            and run_end_ms < turn_end_ms
            and turn_end_ms - turn_start_ms >= min_run_ms
        ):
            return True
    return False


def _run_strength(
    run: tuple[int, int, int | None],
    char_times: Sequence[Sequence],
) -> tuple[int, int]:
    start, end, _speaker_id = run
    duration_ms = max(
        0,
        int(char_times[end][2]) - int(char_times[start][1]),
    )
    return duration_ms, end - start + 1


def _is_strong_speaker_run(
    run: tuple[int, int, int | None],
    char_times: Sequence[Sequence],
    *,
    min_run_ms: int,
    min_run_chars: int,
) -> bool:
    _start, _end, speaker_id = run
    duration_ms, char_count = _run_strength(run, char_times)
    return (
        speaker_id is not None
        and duration_ms >= min_run_ms
        and char_count >= min_run_chars
    )


def _merge_adjacent_runs(
    runs: Sequence[tuple[int, int, int | None]],
) -> list[tuple[int, int, int | None]]:
    merged: list[tuple[int, int, int | None]] = []
    for run in runs:
        if merged and merged[-1][2] == run[2]:
            merged[-1] = (merged[-1][0], run[1], run[2])
        else:
            merged.append(run)
    return merged


def map_sentences_to_turns(
    sentences: Iterable[dict],
    turns: Sequence,
    max_gap_ms: int = 1000,
    merge_gap_ms: int = 800,
    min_split_run_ms: int = DEFAULT_MIN_SPLIT_RUN_MS,
    min_split_run_chars: int = DEFAULT_MIN_SPLIT_RUN_CHARS,
) -> list[dict]:
    """Assign finalized speaker turns to sentence spans.

    Character timestamps are authoritative when they match the ASR content.
    A sentence is split only when its characters cross different speaker
    turns; punctuation and all characters are retained exactly.
    """

    mapped: list[dict] = []
    for sentence in sentences:
        text = str(sentence.get("text") or "").strip()
        if not text:
            continue
        item = dict(sentence)
        item["text"] = text

        split_parts = _split_by_character_speakers(
            item,
            turns,
            max_gap_ms,
            min_run_ms=min_split_run_ms,
            min_run_chars=min_split_run_chars,
        )
        if split_parts:
            mapped.extend(split_parts)
            continue

        item["spk"] = _speaker_from_overlap(item, turns)
        item.pop("_char_times", None)
        mapped.append(item)
    return _merge_adjacent_speaker_spans(mapped, merge_gap_ms)


def _merge_adjacent_speaker_spans(
    spans: Iterable[dict],
    max_gap_ms: int,
) -> list[dict]:
    """Join consecutive fragments that belong to the same speaker.

    Sentence and character timestamps can split one spoken turn at short
    pauses.  The public file-transcription result keeps those pieces together
    as a turn, so merge only when both sides have the same known speaker and
    the VAD gap is short.  Text is concatenated verbatim.
    """

    if max_gap_ms < 0:
        raise ValueError("max_gap_ms must be non-negative")

    merged: list[dict] = []
    for span in spans:
        text = str(span.get("text") or "").strip()
        if not text:
            continue
        item = dict(span)
        item["text"] = text
        if merged:
            previous = merged[-1]
            speaker_id = item.get("spk")
            gap_ms = int(item.get("start") or 0) - int(previous.get("end") or 0)
            if (
                speaker_id is not None
                and speaker_id == previous.get("spk")
                and 0 <= gap_ms <= max_gap_ms
            ):
                previous["text"] += text
                previous["end"] = int(item.get("end") or previous.get("end") or 0)
                continue
        merged.append(item)
    return merged

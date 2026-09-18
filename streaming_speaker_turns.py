"""Streaming speaker-turn detection from short speaker-embedding windows.

The VAD is only a speech gate.  A confirmed VAD segment may still contain
several speakers, so this module classifies short overlapping windows and
uses a confirmation state machine before accepting a speaker change.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Iterable, Sequence

import torch


SPEAKER_STATE = "SPEAKER"
UNKNOWN_STATE = "UNKNOWN"
OVERLAP_STATE = "OVERLAP"


def _normalize(embedding: torch.Tensor) -> torch.Tensor:
    embedding = torch.as_tensor(embedding, dtype=torch.float32).flatten()
    return torch.nn.functional.normalize(embedding, dim=0)


def _window_center_ms(window: "SpeakerWindow") -> float:
    return (float(window.start_ms) + float(window.end_ms)) / 2.0


@dataclass(frozen=True)
class SpeakerTurnConfig:
    """Tuning parameters for two-person streaming speaker turns."""

    window_ms: int = 1200
    hop_ms: int = 200
    confirm_windows: int = 3
    speaker_confirm_windows: int = 5
    speaker_min_span_ms: int = 2000
    min_turn_ms: int = 400
    max_speakers: int = 2
    match_threshold: float = 0.45
    forced_match_threshold: float = 0.2
    forced_match_margin: float = 0.05
    new_speaker_threshold: float = 0.45
    candidate_join_threshold: float = 0.5
    update_threshold: float = 0.65
    unknown_threshold: float = 0.35
    overlap_min_similarity: float = 0.45
    overlap_margin: float = 0.08
    center_update_rate: float = 0.15
    offline_backfill_strong_similarity: float = 0.45
    offline_backfill_strong_margin: float = 0.08
    offline_backfill_match_threshold: float = 0.30
    offline_backfill_margin: float = 0.04
    offline_backfill_neighbor_ms: int = 1200
    offline_backfill_min_support: int = 2
    offline_backfill_min_run_windows: int = 2
    offline_global_max_speakers: int = 2
    offline_global_iterations: int = 25
    offline_global_match_threshold: float = 0.32
    offline_global_margin_threshold: float = 0.04
    offline_global_unknown_threshold: float = 0.25
    offline_global_gap_fill_ms: int = 1000
    offline_global_min_run_windows: int = 2
    offline_global_min_speaker_island_ms: int = 0

    def __post_init__(self) -> None:
        if self.window_ms <= 0:
            raise ValueError("window_ms must be positive")
        if self.hop_ms <= 0 or self.hop_ms > self.window_ms:
            raise ValueError("hop_ms must be in (0, window_ms]")
        if self.confirm_windows <= 0:
            raise ValueError("confirm_windows must be positive")
        if self.speaker_confirm_windows <= 0:
            raise ValueError("speaker_confirm_windows must be positive")
        if self.speaker_min_span_ms < 0:
            raise ValueError("speaker_min_span_ms must be non-negative")
        if self.min_turn_ms < 0:
            raise ValueError("min_turn_ms must be non-negative")
        if self.max_speakers <= 0:
            raise ValueError("max_speakers must be positive")
        if not 0.0 <= self.candidate_join_threshold <= 1.0:
            raise ValueError("candidate_join_threshold must be in [0, 1]")
        if not 0.0 <= self.forced_match_threshold <= 1.0:
            raise ValueError("forced_match_threshold must be in [0, 1]")
        if not 0.0 <= self.forced_match_margin <= 1.0:
            raise ValueError("forced_match_margin must be in [0, 1]")
        if not 0.0 <= self.offline_backfill_strong_similarity <= 1.0:
            raise ValueError(
                "offline_backfill_strong_similarity must be in [0, 1]"
            )
        if not 0.0 <= self.offline_backfill_strong_margin <= 1.0:
            raise ValueError("offline_backfill_strong_margin must be in [0, 1]")
        if not 0.0 <= self.offline_backfill_match_threshold <= 1.0:
            raise ValueError(
                "offline_backfill_match_threshold must be in [0, 1]"
            )
        if not 0.0 <= self.offline_backfill_margin <= 1.0:
            raise ValueError("offline_backfill_margin must be in [0, 1]")
        if self.offline_backfill_neighbor_ms < 0:
            raise ValueError("offline_backfill_neighbor_ms must be non-negative")
        if self.offline_backfill_min_support < 0:
            raise ValueError("offline_backfill_min_support must be non-negative")
        if self.offline_backfill_min_run_windows <= 0:
            raise ValueError(
                "offline_backfill_min_run_windows must be positive"
            )
        if self.offline_global_max_speakers <= 0:
            raise ValueError("offline_global_max_speakers must be positive")
        if self.offline_global_iterations <= 0:
            raise ValueError("offline_global_iterations must be positive")
        if not 0.0 <= self.offline_global_match_threshold <= 1.0:
            raise ValueError(
                "offline_global_match_threshold must be in [0, 1]"
            )
        if not 0.0 <= self.offline_global_margin_threshold <= 1.0:
            raise ValueError(
                "offline_global_margin_threshold must be in [0, 1]"
            )
        if not 0.0 <= self.offline_global_unknown_threshold <= 1.0:
            raise ValueError(
                "offline_global_unknown_threshold must be in [0, 1]"
            )
        if self.offline_global_gap_fill_ms < 0:
            raise ValueError("offline_global_gap_fill_ms must be non-negative")
        if self.offline_global_min_run_windows <= 0:
            raise ValueError(
                "offline_global_min_run_windows must be positive"
            )
        if self.offline_global_min_speaker_island_ms < 0:
            raise ValueError(
                "offline_global_min_speaker_island_ms must be non-negative"
            )


@dataclass
class SpeakerWindow:
    """One short window and its classifier output."""

    start_ms: int
    end_ms: int
    embedding: torch.Tensor | None = None
    segment_start_ms: int | None = None
    segment_end_ms: int | None = None
    rms: float | None = None
    speaker_id: int | None = None
    state: str = UNKNOWN_STATE
    confidence: float = 0.0
    similarities: list[float] = field(default_factory=list)
    provisional: bool = False
    offline_backfilled: bool = False
    offline_reclassified: bool = False


@dataclass
class SpeakerTurn:
    """A finalized contiguous speaker activity interval."""

    start_ms: int
    end_ms: int
    speaker_id: int | None
    state: str
    confidence: float
    window_count: int
    boundary_confidence: float | None = None

    @property
    def duration_ms(self) -> int:
        return self.end_ms - self.start_ms


class StreamingSpeakerTurnTracker:
    """Online two-speaker classifier with delayed switch confirmation.

    ``update_embedding`` may be called as soon as a window is available in the
    realtime path.  For offline replay, feed windows in chronological order and
    call ``finalize`` at the end.
    """

    def __init__(self, config: SpeakerTurnConfig | None = None):
        self.config = config or SpeakerTurnConfig()
        self.centers: list[torch.Tensor] = []
        self.center_updates: list[int] = []
        self.windows: list[SpeakerWindow] = []
        self._stable_state = UNKNOWN_STATE
        self._stable_speaker_id: int | None = None
        self._candidate_start_index: int | None = None
        self._candidate_state = UNKNOWN_STATE
        self._candidate_speaker_id: int | None = None
        self._speaker_candidate_embeddings: list[torch.Tensor] = []
        self._speaker_candidate_windows: list[SpeakerWindow] = []
        self._initial_candidate_embeddings: list[torch.Tensor] = []
        self._initial_candidate_windows: list[SpeakerWindow] = []
        self._segment_boundary_ms: int | None = None
        self._accepted_switches: list[dict] = []

    @property
    def speaker_count(self) -> int:
        return len(self.centers)

    def reset(self) -> None:
        self.centers = []
        self.center_updates = []
        self.windows = []
        self._stable_state = UNKNOWN_STATE
        self._stable_speaker_id = None
        self._candidate_start_index = None
        self._candidate_state = UNKNOWN_STATE
        self._candidate_speaker_id = None
        self._speaker_candidate_embeddings = []
        self._speaker_candidate_windows = []
        self._initial_candidate_embeddings = []
        self._initial_candidate_windows = []
        self._segment_boundary_ms = None
        self._accepted_switches = []

    def begin_segment(self, segment_start_ms: int) -> None:
        """Record a hard VAD boundary that can sharpen a nearby switch."""

        self._segment_boundary_ms = int(segment_start_ms)
        self._reset_speaker_candidate()
        self._reset_initial_candidate()

    def update_embedding(
        self,
        start_ms: int,
        end_ms: int,
        embedding: torch.Tensor,
        *,
        segment_start_ms: int | None = None,
        segment_end_ms: int | None = None,
        rms: float | None = None,
    ) -> SpeakerWindow:
        if end_ms <= start_ms:
            raise ValueError("window end_ms must be greater than start_ms")
        window = SpeakerWindow(
            start_ms=int(start_ms),
            end_ms=int(end_ms),
            embedding=_normalize(embedding),
            segment_start_ms=segment_start_ms,
            segment_end_ms=segment_end_ms,
            rms=rms,
        )
        self._apply_match(window)
        self.windows.append(window)
        self._apply_switch_state(window)
        return window

    def _match_centers(self, embedding: torch.Tensor) -> tuple[list[float], int | None]:
        if not self.centers:
            return [], None
        similarities = torch.mv(torch.stack(self.centers), embedding)
        values = [float(value) for value in similarities]
        return values, int(torch.argmax(similarities))

    def _add_center(self, embedding: torch.Tensor) -> int:
        self.centers.append(embedding.clone())
        self.center_updates.append(1)
        return len(self.centers) - 1

    def _update_center(self, speaker_id: int, embedding: torch.Tensor) -> None:
        rate = self.config.center_update_rate
        updated = (1.0 - rate) * self.centers[speaker_id] + rate * embedding
        self.centers[speaker_id] = _normalize(updated)
        self.center_updates[speaker_id] += 1

    def _apply_match(self, window: SpeakerWindow) -> None:
        assert window.embedding is not None
        embedding = window.embedding
        similarities, best_id = self._match_centers(embedding)
        window.similarities = [round(value, 6) for value in similarities]

        if best_id is None:
            self._consider_initial_speaker(window)
            return

        best_similarity = similarities[best_id]
        if len(similarities) >= 2:
            second_best = sorted(similarities, reverse=True)[1]
            if (
                best_similarity >= self.config.overlap_min_similarity
                and second_best >= self.config.overlap_min_similarity
                and abs(best_similarity - second_best) <= self.config.overlap_margin
            ):
                self._reset_speaker_candidate()
                window.state = OVERLAP_STATE
                window.speaker_id = None
                window.confidence = max(0.0, 1.0 - (best_similarity - second_best))
                return

        if best_similarity >= self.config.match_threshold:
            self._reset_speaker_candidate()
            window.speaker_id = best_id
            window.state = SPEAKER_STATE
            window.confidence = best_similarity
            if best_similarity >= self.config.update_threshold:
                self._update_center(best_id, embedding)
            return

        if len(similarities) >= self.config.max_speakers:
            second_best = sorted(similarities, reverse=True)[1]
            if (
                best_similarity >= self.config.forced_match_threshold
                and best_similarity - second_best >= self.config.forced_match_margin
            ):
                self._reset_speaker_candidate()
                window.speaker_id = best_id
                window.state = SPEAKER_STATE
                window.confidence = best_similarity
                return

        if self._consider_new_speaker(window, best_similarity):
            return

        self._reset_speaker_candidate()
        window.state = UNKNOWN_STATE
        window.speaker_id = None
        window.confidence = max(0.0, best_similarity)

    def _consider_initial_speaker(self, window: SpeakerWindow) -> bool:
        """Confirm the first speaker before accepting it as a stable center.

        A short VAD segment at the beginning of a recording can contain only
        noise or a cough.  Treating that segment as a speaker creates a
        contaminated center that later forces the real first speaker into a
        second identity.
        """

        assert window.embedding is not None
        embedding = window.embedding
        if (
            window.segment_start_ms is None
            or window.segment_end_ms is None
        ):
            # Callers that do not provide VAD segment metadata keep the
            # original immediate-start behavior.
            self._add_center(embedding)
            window.speaker_id = 0
            window.state = SPEAKER_STATE
            window.confidence = 1.0
            return True

        if self._initial_candidate_embeddings:
            candidate_center = _normalize(
                torch.stack(self._initial_candidate_embeddings).mean(dim=0)
            )
            if float(torch.dot(candidate_center, embedding)) < self.config.candidate_join_threshold:
                self._reset_initial_candidate()

        self._initial_candidate_embeddings.append(embedding.clone())
        self._initial_candidate_windows.append(window)

        first_window = self._initial_candidate_windows[0]
        candidate_span_ms = window.end_ms - first_window.start_ms
        if (
            len(self._initial_candidate_windows)
            < self.config.speaker_confirm_windows
            or candidate_span_ms < self.config.speaker_min_span_ms
        ):
            window.speaker_id = None
            window.state = UNKNOWN_STATE
            window.confidence = 0.0
            return True

        candidate_center = _normalize(
            torch.stack(self._initial_candidate_embeddings).mean(dim=0)
        )
        self._add_center(candidate_center)
        self.center_updates[-1] = len(self._initial_candidate_windows)
        for pending_window in self._initial_candidate_windows:
            pending_window.speaker_id = 0
            pending_window.state = SPEAKER_STATE
            pending_window.confidence = max(
                pending_window.confidence,
                float(torch.dot(candidate_center, pending_window.embedding)),
            )
            pending_window.provisional = True

        self._reset_initial_candidate()
        return True

    def _reset_initial_candidate(self) -> None:
        self._initial_candidate_embeddings = []
        self._initial_candidate_windows = []

    def _consider_new_speaker(
        self,
        window: SpeakerWindow,
        best_similarity: float,
    ) -> bool:
        """Buffer low-similarity windows until they form a stable new cluster."""

        if (
            len(self.centers) >= self.config.max_speakers
            or best_similarity >= self.config.new_speaker_threshold
        ):
            self._reset_speaker_candidate()
            return False

        assert window.embedding is not None
        if self._speaker_candidate_embeddings:
            candidate_center = _normalize(
                torch.stack(self._speaker_candidate_embeddings).mean(dim=0)
            )
            candidate_similarity = float(torch.dot(candidate_center, window.embedding))
            if candidate_similarity < self.config.candidate_join_threshold:
                self._reset_speaker_candidate()

        if not self._speaker_candidate_embeddings:
            self._candidate_start_index = len(self.windows)

        self._speaker_candidate_embeddings.append(window.embedding.clone())
        self._speaker_candidate_windows.append(window)

        first_window = self._speaker_candidate_windows[0]
        candidate_span_ms = window.end_ms - first_window.start_ms
        if (
            len(self._speaker_candidate_windows)
            < self.config.speaker_confirm_windows
            or candidate_span_ms < self.config.speaker_min_span_ms
        ):
            window.state = UNKNOWN_STATE
            window.speaker_id = None
            window.confidence = max(0.0, best_similarity)
            return True

        candidate_center = _normalize(
            torch.stack(self._speaker_candidate_embeddings).mean(dim=0)
        )
        candidate_similarities, candidate_best_id = self._match_centers(candidate_center)
        if (
            candidate_best_id is not None
            and candidate_similarities[candidate_best_id]
            >= self.config.new_speaker_threshold
        ):
            self._reset_speaker_candidate()
            window.state = UNKNOWN_STATE
            window.speaker_id = None
            window.confidence = max(0.0, best_similarity)
            return True

        speaker_id = len(self.centers)
        self.centers.append(candidate_center)
        self.center_updates.append(len(self._speaker_candidate_embeddings))
        for pending_window in self._speaker_candidate_windows:
            pending_window.speaker_id = speaker_id
            pending_window.state = SPEAKER_STATE
            pending_window.confidence = max(
                pending_window.confidence,
                float(torch.dot(candidate_center, pending_window.embedding)),
            )
            pending_window.provisional = True

        start_index = self._candidate_start_index
        self._reset_speaker_candidate(keep_start=False)
        if start_index is not None:
            self._promote_switch(start_index, SPEAKER_STATE, speaker_id)
        return True

    def _reset_speaker_candidate(self, *, keep_start: bool = True) -> None:
        if not keep_start:
            self._candidate_start_index = None
        self._speaker_candidate_embeddings = []
        self._speaker_candidate_windows = []

    def _apply_switch_state(self, window: SpeakerWindow) -> None:
        observed_state = window.state
        observed_speaker = window.speaker_id

        if self._stable_state == UNKNOWN_STATE:
            if observed_state in (SPEAKER_STATE, OVERLAP_STATE):
                self._promote_switch(0, observed_state, observed_speaker)
            return

        if (
            observed_state == self._stable_state
            and observed_speaker == self._stable_speaker_id
        ):
            self._candidate_start_index = None
            self._candidate_state = UNKNOWN_STATE
            self._candidate_speaker_id = None
            return

        if observed_state == UNKNOWN_STATE:
            return

        same_candidate = (
            observed_state == self._candidate_state
            and observed_speaker == self._candidate_speaker_id
        )
        if not same_candidate:
            self._candidate_start_index = len(self.windows) - 1
            self._candidate_state = observed_state
            self._candidate_speaker_id = observed_speaker

        candidate_start = self._candidate_start_index
        assert candidate_start is not None
        if len(self.windows) - candidate_start < self.config.confirm_windows:
            return

        first_candidate = self.windows[candidate_start]
        if window.end_ms - first_candidate.start_ms < self.config.min_turn_ms:
            return
        self._promote_switch(
            candidate_start,
            observed_state,
            observed_speaker,
        )

    def _stable_index_before(self, current_index: int) -> int:
        for index in range(current_index - 1, -1, -1):
            window = self.windows[index]
            if (
                window.state == self._stable_state
                and window.speaker_id == self._stable_speaker_id
            ):
                return index
        return max(0, current_index - 1)

    def _promote_switch(
        self,
        start_index: int,
        state: str,
        speaker_id: int | None,
    ) -> None:
        if start_index > 0 and self._stable_state != UNKNOWN_STATE:
            boundary = self._estimate_boundary(start_index)
            self._accepted_switches.append(
                {
                    "window_index": start_index,
                    "boundary_ms": boundary,
                    "from_state": self._stable_state,
                    "from_speaker_id": self._stable_speaker_id,
                    "to_state": state,
                    "to_speaker_id": speaker_id,
                }
            )
        self._stable_state = state
        self._stable_speaker_id = speaker_id
        self._candidate_start_index = None
        self._candidate_state = UNKNOWN_STATE
        self._candidate_speaker_id = None

    def _estimate_boundary(self, start_index: int) -> float:
        previous_index = self._stable_index_before(start_index)
        previous = self.windows[previous_index]
        current = self.windows[start_index]
        boundary = (_window_center_ms(previous) + _window_center_ms(current)) / 2.0
        boundary = min(float(current.end_ms), max(float(previous.start_ms), boundary))
        if (
            self._segment_boundary_ms is not None
            and abs(self._segment_boundary_ms - current.start_ms) <= self.config.hop_ms
        ):
            boundary = float(self._segment_boundary_ms)
        return boundary

    def snapshot_windows(self) -> list[dict]:
        """Return JSON-ready classifier decisions for diagnostics."""

        return [
            {
                "start_ms": window.start_ms,
                "end_ms": window.end_ms,
                "speaker_id": window.speaker_id,
                "state": window.state,
                "confidence": round(float(window.confidence), 6),
                "similarities": window.similarities,
                "provisional": window.provisional,
                "offline_backfilled": window.offline_backfilled,
                "offline_reclassified": window.offline_reclassified,
                "segment_start_ms": window.segment_start_ms,
                "segment_end_ms": window.segment_end_ms,
                "rms": window.rms,
            }
            for window in self.windows
        ]

    def offline_global_reclassify(self) -> dict:
        """Reclassify all windows against global speaker prototypes.

        Offline decoding can use the complete recording, so it does not need to
        preserve the causal centers produced by streaming updates. This pass
        fits one or two global centers, reclassifies every window, then applies
        conservative temporal smoothing that keeps ambiguous windows UNKNOWN.
        """

        valid_indices = [
            index
            for index, window in enumerate(self.windows)
            if window.embedding is not None
        ]
        if not valid_indices:
            return {
                "enabled": True,
                "speaker_count": 0,
                "classified_windows": 0,
                "unknown_windows": 0,
                "overlap_windows": 0,
                "smoothed_windows": 0,
                "switches": 0,
            }

        embeddings = torch.stack(
            [self.windows[index].embedding for index in valid_indices]
        )
        centers = self._global_seed_centers(embeddings, valid_indices)
        centers = self._refine_global_centers(embeddings, centers)
        similarities = embeddings @ centers.T
        labels, states, confidences = self._classify_global_windows(similarities)
        labels, states, smoothed_windows = self._smooth_global_labels(
            labels,
            states,
            similarities,
            valid_indices,
        )
        labels, states, merged_islands, merged_island_windows = (
            self._merge_short_global_speaker_islands(
                labels,
                states,
                valid_indices,
            )
        )

        for row, window_index in enumerate(valid_indices):
            window = self.windows[window_index]
            window.speaker_id = labels[row]
            window.state = states[row]
            window.confidence = confidences[row]
            window.similarities = [
                round(float(value), 6) for value in similarities[row]
            ]
            window.provisional = False
            window.offline_backfilled = False
            window.offline_reclassified = True

        switch_count = self._replace_switches_from_global_labels(
            valid_indices,
            labels,
            states,
        )
        return {
            "enabled": True,
            "speaker_count": len(centers),
            "classified_windows": sum(
                state == SPEAKER_STATE for state in states
            ),
            "unknown_windows": sum(
                state == UNKNOWN_STATE for state in states
            ),
            "overlap_windows": sum(
                state == OVERLAP_STATE for state in states
            ),
            "smoothed_windows": smoothed_windows,
            "merged_short_speaker_islands": merged_islands,
            "merged_short_speaker_windows": merged_island_windows,
            "switches": switch_count,
        }

    def _global_seed_centers(
        self,
        embeddings: torch.Tensor,
        valid_indices: Sequence[int],
    ) -> torch.Tensor:
        max_speakers = min(
            self.config.offline_global_max_speakers,
            self.config.max_speakers,
        )
        groups: dict[int, list[int]] = {}
        for row, window_index in enumerate(valid_indices):
            window = self.windows[window_index]
            if window.speaker_id is None or window.state != SPEAKER_STATE:
                continue
            groups.setdefault(window.speaker_id, []).append(row)

        ordered_groups = sorted(
            groups.values(),
            key=lambda rows: (-len(rows), min(rows)),
        )
        centers = [
            _normalize(embeddings[rows].mean(dim=0))
            for rows in ordered_groups[:max_speakers]
        ]

        if not centers:
            similarities = embeddings @ embeddings.T
            first_row = int(torch.argmax(similarities.mean(dim=1)))
            centers.append(embeddings[first_row].clone())

        while len(centers) < max_speakers:
            center_matrix = torch.stack(centers)
            nearest_similarity = (embeddings @ center_matrix.T).max(dim=1).values
            next_row = int(torch.argmin(nearest_similarity))
            centers.append(embeddings[next_row].clone())

        return torch.stack(centers)

    def _refine_global_centers(
        self,
        embeddings: torch.Tensor,
        centers: torch.Tensor,
    ) -> torch.Tensor:
        for _ in range(self.config.offline_global_iterations):
            similarities = embeddings @ centers.T
            labels = torch.argmax(similarities, dim=1)
            updated_centers = []
            for speaker_id in range(centers.shape[0]):
                members = embeddings[labels == speaker_id]
                if len(members):
                    updated_centers.append(_normalize(members.mean(dim=0)))
                    continue
                nearest_similarity = similarities.max(dim=1).values
                farthest_row = int(torch.argmin(nearest_similarity))
                updated_centers.append(embeddings[farthest_row].clone())
            updated = torch.stack(updated_centers)
            if torch.allclose(updated, centers, atol=1e-5, rtol=0.0):
                return updated
            centers = updated
        return centers

    def _classify_global_windows(
        self,
        similarities: torch.Tensor,
    ) -> tuple[list[int | None], list[str], list[float]]:
        labels: list[int | None] = []
        states: list[str] = []
        confidences: list[float] = []
        for row in similarities:
            ordered_values, ordered_ids = torch.sort(row, descending=True)
            best_id = int(ordered_ids[0])
            best_similarity = float(ordered_values[0])
            second_similarity = (
                float(ordered_values[1])
                if len(ordered_values) >= 2
                else -1.0
            )
            margin = best_similarity - second_similarity

            if best_similarity < self.config.offline_global_unknown_threshold:
                labels.append(None)
                states.append(UNKNOWN_STATE)
                confidences.append(best_similarity)
                continue

            if (
                second_similarity >= self.config.overlap_min_similarity
                and best_similarity >= self.config.overlap_min_similarity
                and margin <= self.config.overlap_margin
            ):
                labels.append(None)
                states.append(OVERLAP_STATE)
                confidences.append(min(best_similarity, second_similarity))
                continue

            if (
                best_similarity >= self.config.offline_global_match_threshold
                and margin >= self.config.offline_global_margin_threshold
            ):
                labels.append(best_id)
                states.append(SPEAKER_STATE)
                confidences.append(best_similarity)
                continue

            labels.append(None)
            states.append(UNKNOWN_STATE)
            confidences.append(best_similarity)
        return labels, states, confidences

    def _smooth_global_labels(
        self,
        labels: list[int | None],
        states: list[str],
        similarities: torch.Tensor,
        valid_indices: Sequence[int],
    ) -> tuple[list[int | None], list[str], int]:
        smoothed_labels = list(labels)
        smoothed_states = list(states)
        smoothed_windows = 0
        index = 0
        while index < len(smoothed_states):
            if smoothed_states[index] != UNKNOWN_STATE:
                index += 1
                continue

            run_end = index
            while (
                run_end + 1 < len(smoothed_states)
                and smoothed_states[run_end + 1] == UNKNOWN_STATE
            ):
                run_end += 1

            if index > 0 and run_end + 1 < len(smoothed_states):
                previous_label = smoothed_labels[index - 1]
                next_label = smoothed_labels[run_end + 1]
                gap_ms = (
                    self.windows[valid_indices[run_end]].end_ms
                    - self.windows[valid_indices[index]].start_ms
                )
                if (
                    previous_label is not None
                    and previous_label == next_label
                    and run_end - index + 1
                    <= self.config.offline_global_min_run_windows
                    and gap_ms <= self.config.offline_global_gap_fill_ms
                ):
                    can_fill = True
                    for row in range(index, run_end + 1):
                        if (
                            float(similarities[row, previous_label])
                            < self.config.offline_global_unknown_threshold
                        ):
                            can_fill = False
                            break
                    if can_fill:
                        for row in range(index, run_end + 1):
                            smoothed_labels[row] = previous_label
                            smoothed_states[row] = SPEAKER_STATE
                            smoothed_windows += 1
            index = run_end + 1
        return smoothed_labels, smoothed_states, smoothed_windows

    def _merge_short_global_speaker_islands(
        self,
        labels: list[int | None],
        states: list[str],
        valid_indices: Sequence[int],
    ) -> tuple[list[int | None], list[str], int, int]:
        """Absorb short speaker islands flanked by the same global speaker.

        A single anomalous window can otherwise create two turn boundaries and
        a sub-500 ms speaker turn. The merge is deliberately conservative: it
        requires the same non-None speaker on both sides and leaves islands
        adjacent to UNKNOWN, OVERLAP, or a different speaker untouched.
        """

        min_span_ms = self.config.offline_global_min_speaker_island_ms
        if min_span_ms <= 0 or len(states) < 3:
            return labels, states, 0, 0

        merged_labels = list(labels)
        merged_states = list(states)
        merged_islands = 0
        merged_windows = 0
        changed = True
        while changed:
            changed = False
            runs = _label_runs(merged_states, merged_labels)
            for run_start, run_end, state, label in runs:
                if state != SPEAKER_STATE or label is None:
                    continue
                if run_start == 0 or run_end + 1 >= len(merged_states):
                    continue

                left_label = merged_labels[run_start - 1]
                right_label = merged_labels[run_end + 1]
                if (
                    merged_states[run_start - 1] != SPEAKER_STATE
                    or merged_states[run_end + 1] != SPEAKER_STATE
                    or left_label is None
                    or left_label != right_label
                    or left_label == label
                ):
                    continue

                start_ms = self._estimate_global_boundary(
                    valid_indices[run_start - 1],
                    valid_indices[run_start],
                )
                end_ms = self._estimate_global_boundary(
                    valid_indices[run_end],
                    valid_indices[run_end + 1],
                )
                if end_ms - start_ms >= min_span_ms:
                    continue

                for row in range(run_start, run_end + 1):
                    merged_labels[row] = left_label
                    merged_states[row] = SPEAKER_STATE
                merged_islands += 1
                merged_windows += run_end - run_start + 1
                changed = True
                break

        return merged_labels, merged_states, merged_islands, merged_windows

    def _replace_switches_from_global_labels(
        self,
        valid_indices: Sequence[int],
        labels: Sequence[int | None],
        states: Sequence[str],
    ) -> int:
        self._accepted_switches = []
        previous_label: int | None = None
        previous_index: int | None = None
        for row, window_index in enumerate(valid_indices):
            if states[row] != SPEAKER_STATE or labels[row] is None:
                continue
            label = labels[row]
            if (
                previous_label is not None
                and previous_index is not None
                and label != previous_label
            ):
                boundary = self._estimate_global_boundary(
                    previous_index,
                    window_index,
                )
                self._accepted_switches.append(
                    {
                        "window_index": window_index,
                        "boundary_ms": boundary,
                        "from_state": SPEAKER_STATE,
                        "from_speaker_id": previous_label,
                        "to_state": SPEAKER_STATE,
                        "to_speaker_id": label,
                        "offline_reclassified": True,
                    }
                )
            previous_label = label
            previous_index = window_index
        return len(self._accepted_switches)

    def _estimate_global_boundary(
        self,
        previous_index: int,
        current_index: int,
    ) -> float:
        previous = self.windows[previous_index]
        current = self.windows[current_index]
        boundary = (_window_center_ms(previous) + _window_center_ms(current)) / 2.0
        boundary = min(float(current.end_ms), max(float(previous.start_ms), boundary))
        segment_boundary = current.segment_start_ms
        if (
            segment_boundary is not None
            and abs(segment_boundary - current.start_ms) <= self.config.hop_ms
        ):
            boundary = float(segment_boundary)
        return boundary

    def offline_backfill_unknowns(self) -> dict:
        """Legacy local backfill kept for reproducing old validation runs.

        New offline runs should use :meth:`offline_global_reclassify`. The
        backfill path only matches early windows against causal centers and is
        retained to compare against previously recorded results.
        """

        if len(self.centers) < 2 or not self.windows:
            return {
                "enabled": True,
                "eligible_until_index": None,
                "eligible_unknown_windows": 0,
                "backfilled_windows": 0,
                "assigned_by_speaker": {},
                "filtered_short_runs": 0,
                "added_switches": 0,
            }

        first_index_by_speaker: dict[int, int] = {}
        for index, window in enumerate(self.windows):
            if window.speaker_id is None:
                continue
            first_index_by_speaker.setdefault(window.speaker_id, index)

        if len(first_index_by_speaker) < len(self.centers):
            return {
                "enabled": True,
                "eligible_until_index": None,
                "eligible_unknown_windows": 0,
                "backfilled_windows": 0,
                "assigned_by_speaker": {},
                "filtered_short_runs": 0,
                "added_switches": 0,
            }

        eligible_until = max(first_index_by_speaker.values())
        eligible = [
            index
            for index in range(eligible_until)
            if (
                self.windows[index].state == UNKNOWN_STATE
                and self.windows[index].embedding is not None
            )
        ]
        if not eligible:
            return {
                "enabled": True,
                "eligible_until_index": eligible_until,
                "eligible_unknown_windows": 0,
                "backfilled_windows": 0,
                "assigned_by_speaker": {},
                "filtered_short_runs": 0,
                "added_switches": 0,
            }

        matches = {
            index: self._match_centers(self.windows[index].embedding)
            for index in eligible
        }
        assignments: dict[int, int] = {}
        strong_assignments: dict[int, int] = {}
        for index in eligible:
            similarities, best_id = matches[index]
            if best_id is None:
                continue
            best_similarity = similarities[best_id]
            second_similarity = (
                sorted(similarities, reverse=True)[1]
                if len(similarities) >= 2
                else -1.0
            )
            margin = best_similarity - second_similarity
            if (
                best_similarity
                >= self.config.offline_backfill_strong_similarity
                and margin >= self.config.offline_backfill_strong_margin
            ):
                assignments[index] = best_id
                strong_assignments[index] = best_id

        neighbor_ms = self.config.offline_backfill_neighbor_ms
        if neighbor_ms > 0:
            for index in eligible:
                if index in assignments:
                    continue
                similarities, best_id = matches[index]
                if best_id is None:
                    continue
                best_similarity = similarities[best_id]
                second_similarity = (
                    sorted(similarities, reverse=True)[1]
                    if len(similarities) >= 2
                    else -1.0
                )
                margin = best_similarity - second_similarity
                if (
                    best_similarity
                    < self.config.offline_backfill_match_threshold
                    or margin < self.config.offline_backfill_margin
                ):
                    continue
                center_ms = _window_center_ms(self.windows[index])
                support: dict[int, int] = {}
                for support_index, support_speaker in assignments.items():
                    if support_index == index:
                        continue
                    if (
                        abs(
                            _window_center_ms(self.windows[support_index])
                            - center_ms
                        )
                        <= neighbor_ms
                    ):
                        support[support_speaker] = (
                            support.get(support_speaker, 0) + 1
                        )
                if not support:
                    continue
                support_speaker, support_count = max(
                    support.items(),
                    key=lambda item: item[1],
                )
                if (
                    support_speaker == best_id
                    and support_count
                    >= self.config.offline_backfill_min_support
                ):
                    assignments[index] = best_id

        kept_assignments = self._filter_offline_backfill_runs(assignments)
        filtered_short_runs = len(assignments) - len(kept_assignments)
        for index, speaker_id in kept_assignments.items():
            window = self.windows[index]
            similarities, _ = matches[index]
            window.speaker_id = speaker_id
            window.state = SPEAKER_STATE
            window.confidence = max(
                0.0,
                float(similarities[speaker_id]),
            )
            window.similarities = [round(value, 6) for value in similarities]
            window.offline_backfilled = True
            window.provisional = False

        added_switches = self._add_offline_backfill_switches(
            kept_assignments,
            eligible_until,
        )
        assigned_by_speaker: dict[str, int] = {}
        for speaker_id in kept_assignments.values():
            key = str(speaker_id)
            assigned_by_speaker[key] = assigned_by_speaker.get(key, 0) + 1
        return {
            "enabled": True,
            "eligible_until_index": eligible_until,
            "eligible_unknown_windows": len(eligible),
            "backfilled_windows": len(kept_assignments),
            "strong_assignments": len(strong_assignments),
            "assigned_by_speaker": assigned_by_speaker,
            "filtered_short_runs": filtered_short_runs,
            "added_switches": added_switches,
        }

    def _filter_offline_backfill_runs(
        self,
        assignments: dict[int, int],
    ) -> dict[int, int]:
        if not assignments:
            return {}

        kept: dict[int, int] = {}
        ordered = sorted(assignments)
        run_start = 0
        while run_start < len(ordered):
            run_end = run_start
            speaker_id = assignments[ordered[run_start]]
            while (
                run_end + 1 < len(ordered)
                and ordered[run_end + 1] == ordered[run_end] + 1
                and assignments[ordered[run_end + 1]] == speaker_id
            ):
                run_end += 1

            run_indices = ordered[run_start : run_end + 1]
            if len(run_indices) >= self.config.offline_backfill_min_run_windows:
                for index in run_indices:
                    kept[index] = speaker_id
            run_start = run_end + 1
        return kept

    def _add_offline_backfill_switches(
        self,
        assignments: dict[int, int],
        eligible_until: int,
    ) -> int:
        if not assignments:
            return 0

        existing_indices = {
            int(switch["window_index"]) for switch in self._accepted_switches
        }
        added = 0
        previous_label: int | None = None
        for index in range(eligible_until):
            window = self.windows[index]
            label = assignments.get(index, window.speaker_id)
            if window.state not in (SPEAKER_STATE, OVERLAP_STATE):
                label = assignments.get(index)
            if label is None:
                continue
            if (
                previous_label is not None
                and label != previous_label
                and index not in existing_indices
            ):
                previous_index = index - 1
                while (
                    previous_index >= 0
                    and assignments.get(previous_index) is None
                    and self.windows[previous_index].speaker_id is None
                ):
                    previous_index -= 1
                if previous_index < 0:
                    previous_label = label
                    continue
                previous_window = self.windows[previous_index]
                current_window = self.windows[index]
                boundary = (
                    _window_center_ms(previous_window)
                    + _window_center_ms(current_window)
                ) / 2.0
                boundary = min(
                    float(current_window.end_ms),
                    max(float(previous_window.start_ms), boundary),
                )
                self._accepted_switches.append(
                    {
                        "window_index": index,
                        "boundary_ms": boundary,
                        "from_state": SPEAKER_STATE,
                        "from_speaker_id": previous_label,
                        "to_state": SPEAKER_STATE,
                        "to_speaker_id": label,
                        "offline_backfilled": True,
                    }
                )
                existing_indices.add(index)
                added += 1
            previous_label = label
        return added

    def snapshot_switches(self) -> list[dict]:
        return [dict(switch) for switch in self._accepted_switches]

    def finalize(self) -> list[SpeakerTurn]:
        """Collapse classified windows into final speaker turns."""

        if not self.windows:
            return []

        boundaries = self._collect_finalize_boundaries()
        turns: list[SpeakerTurn] = []
        start_index = 0
        start_ms_override: int | None = None

        for boundary in boundaries + [None]:
            boundary_index = boundary["window_index"] if boundary else None
            if boundary_index is None:
                end_index = len(self.windows)
            else:
                end_index = max(start_index, min(boundary_index, len(self.windows)))

            turn_windows = self.windows[start_index:end_index]
            if turn_windows:
                turns.append(
                    self._build_turn(
                        turn_windows,
                        start_ms_override,
                        (
                            boundary["end_boundary_ms"]
                            if boundary is not None
                            else None
                        ),
                        end_ms_override=(
                            boundary["end_ms"]
                            if boundary is not None
                            else None
                        ),
                    )
                )
            if boundary_index is not None:
                start_index = end_index
                start_ms_override = boundary["start_ms"]

        return _merge_adjacent_turns(_drop_non_positive_turns(turns))

    def _collect_finalize_boundaries(self) -> list[dict]:
        """Create hard split points for accepted switches and VAD gaps."""

        switches_by_index: dict[int, dict] = {}
        for switch in self._accepted_switches:
            index = int(switch["window_index"])
            boundary_ms = int(round(switch["boundary_ms"]))
            switches_by_index[index] = {
                "window_index": index,
                "end_boundary_ms": boundary_ms,
                "end_ms": boundary_ms,
                "start_ms": boundary_ms,
            }

        boundaries: list[dict] = []
        previous_segment: tuple[int | None, int | None] | None = None
        for index, window in enumerate(self.windows):
            segment = (window.segment_start_ms, window.segment_end_ms)
            if previous_segment is not None and segment != previous_segment:
                switch = switches_by_index.get(index)
                if switch is not None:
                    # A VAD gap is a hard split. Keep the switch boundary
                    # metadata for diagnostics, but do not bridge the gap.
                    switch = dict(switch)
                    switch["end_ms"] = self.windows[index - 1].end_ms
                    switch["start_ms"] = window.start_ms
                    boundaries.append(switch)
                else:
                    boundaries.append(
                        {
                            "window_index": index,
                            "end_boundary_ms": None,
                            "end_ms": self.windows[index - 1].end_ms,
                            "start_ms": window.start_ms,
                        }
                    )
            previous_segment = segment

        seen = {boundary["window_index"] for boundary in boundaries}
        for index, switch in switches_by_index.items():
            if index not in seen:
                boundaries.append(switch)
        return sorted(boundaries, key=lambda boundary: boundary["window_index"])

    def _build_turn(
        self,
        windows: Sequence[SpeakerWindow],
        start_boundary_ms: int | None,
        end_boundary_ms: int | None,
        *,
        end_ms_override: int | None = None,
    ) -> SpeakerTurn:
        stable = _dominant_window_state(windows)
        speaker_id = stable[0]
        state = stable[1]
        confidence = stable[2]
        start_ms = (
            int(start_boundary_ms)
            if start_boundary_ms is not None
            else windows[0].start_ms
        )
        end_ms = (
            int(end_ms_override)
            if end_ms_override is not None
            else (
                int(end_boundary_ms)
                if end_boundary_ms is not None
                else windows[-1].end_ms
            )
        )
        boundary_confidence = None
        if end_boundary_ms is not None:
            boundary_confidence = max(
                0.0,
                1.0 - abs(end_boundary_ms - (start_ms + end_ms) / 2.0)
                / max(1.0, self.config.window_ms),
            )
        return SpeakerTurn(
            start_ms=int(start_ms),
            end_ms=int(end_ms),
            speaker_id=speaker_id,
            state=state,
            confidence=round(float(confidence), 6),
            window_count=len(windows),
            boundary_confidence=(
                round(float(boundary_confidence), 6)
                if boundary_confidence is not None
                else None
            ),
        )

    def iter_turns(self) -> Iterable[SpeakerTurn]:
        return iter(self.finalize())


def _dominant_window_state(
    windows: Sequence[SpeakerWindow],
) -> tuple[int | None, str, float]:
    if not windows:
        return None, UNKNOWN_STATE, 0.0

    counts: dict[tuple[str, int | None], int] = {}
    confidences: dict[tuple[str, int | None], list[float]] = {}
    for window in windows:
        key = (window.state, window.speaker_id)
        counts[key] = counts.get(key, 0) + 1
        confidences.setdefault(key, []).append(float(window.confidence))

    key = max(counts, key=lambda candidate: counts[candidate])
    state, speaker_id = key
    mean_confidence = sum(confidences[key]) / len(confidences[key])
    return speaker_id, state, mean_confidence


def _drop_non_positive_turns(
    turns: Sequence[SpeakerTurn],
) -> list[SpeakerTurn]:
    """Drop zero or negative spans created by coincident VAD boundaries."""

    return [turn for turn in turns if turn.end_ms > turn.start_ms]


def _label_runs(
    states: Sequence[str],
    labels: Sequence[int | None],
) -> list[tuple[int, int, str, int | None]]:
    runs: list[tuple[int, int, str, int | None]] = []
    run_start = 0
    for index in range(1, len(states) + 1):
        if (
            index < len(states)
            and states[index] == states[run_start]
            and labels[index] == labels[run_start]
        ):
            continue
        runs.append(
            (
                run_start,
                index - 1,
                states[run_start],
                labels[run_start],
            )
        )
        run_start = index
    return runs


def _merge_adjacent_turns(turns: Sequence[SpeakerTurn]) -> list[SpeakerTurn]:
    merged: list[SpeakerTurn] = []
    for turn in turns:
        if (
            merged
            and merged[-1].speaker_id == turn.speaker_id
            and merged[-1].state == turn.state
            and merged[-1].end_ms == turn.start_ms
        ):
            previous = merged[-1]
            total_windows = previous.window_count + turn.window_count
            previous.confidence = (
                previous.confidence * previous.window_count
                + turn.confidence * turn.window_count
            ) / total_windows
            previous.end_ms = turn.end_ms
            previous.window_count = total_windows
            continue
        merged.append(turn)
    return merged


def merge_short_turns(
    turns: Sequence[SpeakerTurn],
    min_turn_ms: int,
) -> list[SpeakerTurn]:
    """Absorb short UNKNOWN/OVERLAP islands without inventing a speaker cut."""

    if min_turn_ms <= 0 or len(turns) < 2:
        return list(turns)

    result: list[SpeakerTurn] = []
    for turn in turns:
        if (
            result
            and turn.duration_ms < min_turn_ms
            and turn.state in (UNKNOWN_STATE, OVERLAP_STATE)
        ):
            previous = result[-1]
            previous.end_ms = turn.end_ms
            previous.window_count += turn.window_count
            continue
        result.append(turn)
    return _merge_adjacent_turns(result)

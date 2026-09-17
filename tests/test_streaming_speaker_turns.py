import torch

from streaming_speaker_turns import (
    OVERLAP_STATE,
    SPEAKER_STATE,
    UNKNOWN_STATE,
    SpeakerTurnConfig,
    StreamingSpeakerTurnTracker,
)


def _embedding(x, y):
    return torch.tensor([x, y], dtype=torch.float32)


def _feed(tracker, values, window_ms=1200, hop_ms=200):
    start_ms = 0
    for value in values:
        tracker.update_embedding(
            start_ms,
            start_ms + window_ms,
            _embedding(*value),
        )
        start_ms += hop_ms


def test_two_speaker_switch_is_confirmed_and_boundary_is_between_windows():
    tracker = StreamingSpeakerTurnTracker(
        SpeakerTurnConfig(
            window_ms=1200,
            hop_ms=200,
            confirm_windows=3,
            speaker_confirm_windows=3,
            speaker_min_span_ms=400,
        )
    )
    _feed(
        tracker,
        [
            (1.0, 0.0),
            (0.99, 0.01),
            (0.98, 0.02),
            (0.0, 1.0),
            (0.01, 0.99),
            (0.02, 0.98),
        ],
    )

    turns = tracker.finalize()

    assert tracker.speaker_count == 2
    assert [turn.speaker_id for turn in turns] == [0, 1]
    assert [turn.state for turn in turns] == [SPEAKER_STATE, SPEAKER_STATE]
    assert turns[0].end_ms == turns[1].start_ms
    assert 1000 <= turns[1].start_ms <= 1200
    assert tracker.snapshot_switches()[0]["to_speaker_id"] == 1


def test_short_opposite_speaker_glitch_does_not_cut_turn():
    tracker = StreamingSpeakerTurnTracker(
        SpeakerTurnConfig(
            window_ms=1200,
            hop_ms=200,
            confirm_windows=3,
            speaker_confirm_windows=3,
            speaker_min_span_ms=400,
        )
    )
    _feed(
        tracker,
        [
            (1.0, 0.0),
            (1.0, 0.0),
            (1.0, 0.0),
            (0.0, 1.0),
            (0.0, 1.0),
            (1.0, 0.0),
            (1.0, 0.0),
            (1.0, 0.0),
        ],
    )

    turns = tracker.finalize()

    assert len(turns) == 1
    assert turns[0].speaker_id == 0


def test_ambiguous_windows_are_unknown_not_forced_to_speaker():
    config = SpeakerTurnConfig(
        window_ms=1200,
        hop_ms=200,
        confirm_windows=2,
        match_threshold=0.85,
        new_speaker_threshold=0.4,
        unknown_threshold=0.3,
    )
    tracker = StreamingSpeakerTurnTracker(config)
    _feed(
        tracker,
        [
            (1.0, 0.0),
            (0.75, 0.66),
            (0.74, 0.67),
            (0.73, 0.68),
        ],
    )

    states = [window.state for window in tracker.windows]
    assert UNKNOWN_STATE in states or OVERLAP_STATE in states
    assert all(turn.state != SPEAKER_STATE or turn.speaker_id == 0 for turn in tracker.finalize())


def test_vad_boundary_snaps_nearby_speaker_switch():
    tracker = StreamingSpeakerTurnTracker(
        SpeakerTurnConfig(
            window_ms=1200,
            hop_ms=200,
            confirm_windows=3,
            speaker_confirm_windows=3,
            speaker_min_span_ms=400,
        )
    )
    _feed(tracker, [(1.0, 0.0), (1.0, 0.0), (1.0, 0.0)])
    tracker.begin_segment(1800)
    for index, value in enumerate([(0.0, 1.0), (0.0, 1.0), (0.0, 1.0)]):
        start_ms = 1800 + index * 200
        tracker.update_embedding(
            start_ms,
            start_ms + 1200,
            _embedding(*value),
        )

    turns = tracker.finalize()

    assert turns[-1].start_ms == 1800


def test_reset_clears_centers_and_history():
    tracker = StreamingSpeakerTurnTracker()
    _feed(tracker, [(1.0, 0.0), (0.0, 1.0), (0.0, 1.0)])

    tracker.reset()

    assert tracker.speaker_count == 0
    assert tracker.windows == []
    assert tracker.finalize() == []


def test_short_low_similarity_run_does_not_create_duplicate_center():
    tracker = StreamingSpeakerTurnTracker()
    _feed(
        tracker,
        [
            (1.0, 0.0),
            (0.0, 1.0),
            (0.01, 0.99),
            (0.02, 0.98),
            (0.01, 0.99),
        ],
    )

    assert tracker.speaker_count == 1
    assert all(
        window.speaker_id is None and window.state == UNKNOWN_STATE
        for window in tracker.windows[1:]
    )


def test_stable_low_similarity_cluster_becomes_second_speaker():
    tracker = StreamingSpeakerTurnTracker()
    _feed(
        tracker,
        [
            (1.0, 0.0),
            (1.0, 0.0),
            (0.0, 1.0),
            (0.01, 0.99),
            (0.02, 0.98),
            (0.01, 0.99),
            (0.0, 1.0),
        ],
    )

    assert tracker.speaker_count == 2
    assert [turn.speaker_id for turn in tracker.finalize()] == [0, 1]
    assert tracker.snapshot_switches()[0]["to_speaker_id"] == 1


def test_vad_boundary_discards_partial_speaker_candidate():
    tracker = StreamingSpeakerTurnTracker()
    _feed(tracker, [(1.0, 0.0), (1.0, 0.0)])
    _feed(tracker, [(0.0, 1.0), (0.0, 1.0), (0.0, 1.0)])

    tracker.begin_segment(2000)
    _feed(tracker, [(0.0, 1.0), (0.0, 1.0)])

    assert tracker.speaker_count == 1


def test_short_initial_vad_segment_does_not_create_a_contaminated_center():
    tracker = StreamingSpeakerTurnTracker(
        SpeakerTurnConfig(
            window_ms=1200,
            hop_ms=200,
            confirm_windows=3,
            speaker_confirm_windows=3,
            speaker_min_span_ms=600,
        )
    )

    tracker.begin_segment(0)
    tracker.update_embedding(
        0,
        300,
        _embedding(0.0, 1.0),
        segment_start_ms=0,
        segment_end_ms=300,
    )

    assert tracker.speaker_count == 0
    assert tracker.windows[0].state == UNKNOWN_STATE

    tracker.begin_segment(1000)
    for index, value in enumerate(
        [(1.0, 0.0), (0.99, 0.01), (0.98, 0.02), (0.97, 0.03)]
    ):
        start_ms = 1000 + index * 200
        tracker.update_embedding(
            start_ms,
            start_ms + 1200,
            _embedding(*value),
            segment_start_ms=1000,
            segment_end_ms=4000,
        )

    assert tracker.speaker_count == 1
    assert tracker.windows[-1].speaker_id == 0


def test_finalize_splits_at_vad_gap_even_when_speaker_is_unchanged():
    tracker = StreamingSpeakerTurnTracker(
        SpeakerTurnConfig(
            window_ms=1200,
            hop_ms=200,
            confirm_windows=3,
            speaker_confirm_windows=3,
            speaker_min_span_ms=400,
        )
    )

    tracker.begin_segment(0)
    for index, value in enumerate([(1.0, 0.0), (0.99, 0.01), (0.98, 0.02)]):
        start_ms = index * 200
        tracker.update_embedding(
            start_ms,
            start_ms + 1200,
            _embedding(*value),
            segment_start_ms=0,
            segment_end_ms=1600,
        )

    tracker.begin_segment(2400)
    for index, value in enumerate([(1.0, 0.0), (0.99, 0.01), (0.98, 0.02)]):
        start_ms = 2400 + index * 200
        tracker.update_embedding(
            start_ms,
            start_ms + 1200,
            _embedding(*value),
            segment_start_ms=2400,
            segment_end_ms=4000,
        )

    turns = tracker.finalize()

    assert len(turns) >= 2
    assert turns[0].end_ms < turns[1].start_ms
    assert all(turn.speaker_id == 0 for turn in turns if turn.speaker_id is not None)


def test_offline_backfill_splits_unknown_windows_before_centers_exist():
    tracker = StreamingSpeakerTurnTracker(
        SpeakerTurnConfig(
            window_ms=800,
            hop_ms=200,
            confirm_windows=2,
            speaker_confirm_windows=5,
            speaker_min_span_ms=2000,
            min_turn_ms=400,
            offline_backfill_min_support=1,
        )
    )

    tracker.begin_segment(0)
    for index, value in enumerate(
        [(1.0, 0.0), (1.0, 0.0), (0.0, 1.0), (0.0, 1.0)]
    ):
        start_ms = index * 200
        tracker.update_embedding(
            start_ms,
            start_ms + 800,
            _embedding(*value),
            segment_start_ms=0,
            segment_end_ms=1400,
        )

    tracker.begin_segment(3000)
    for index, value in enumerate([(1.0, 0.0)] * 7):
        start_ms = 3000 + index * 200
        tracker.update_embedding(
            start_ms,
            start_ms + 800,
            _embedding(*value),
            segment_start_ms=3000,
            segment_end_ms=4400,
        )

    tracker.begin_segment(5000)
    for index, value in enumerate([(0.0, 1.0)] * 7):
        start_ms = 5000 + index * 200
        tracker.update_embedding(
            start_ms,
            start_ms + 800,
            _embedding(*value),
            segment_start_ms=5000,
            segment_end_ms=6400,
        )

    backfill = tracker.offline_backfill_unknowns()

    assert backfill["backfilled_windows"] == 4
    assert backfill["added_switches"] == 2
    assert [window.state for window in tracker.windows[:4]] == [
        SPEAKER_STATE,
        SPEAKER_STATE,
        SPEAKER_STATE,
        SPEAKER_STATE,
    ]
    first_turns = [
        turn for turn in tracker.finalize() if turn.start_ms < 1400
    ]
    assert [turn.speaker_id for turn in first_turns] == [0, 1]
    assert first_turns[0].end_ms == first_turns[1].start_ms


def test_offline_backfill_leaves_ambiguous_early_windows_unknown():
    tracker = StreamingSpeakerTurnTracker(
        SpeakerTurnConfig(
            window_ms=800,
            hop_ms=200,
            confirm_windows=2,
            speaker_confirm_windows=5,
            speaker_min_span_ms=2000,
            min_turn_ms=400,
            offline_backfill_min_support=1,
        )
    )

    tracker.begin_segment(0)
    for index in range(4):
        start_ms = index * 200
        tracker.update_embedding(
            start_ms,
            start_ms + 800,
            _embedding(0.71, 0.70),
            segment_start_ms=0,
            segment_end_ms=1400,
        )

    tracker.begin_segment(3000)
    for index, value in enumerate([(1.0, 0.0)] * 7):
        start_ms = 3000 + index * 200
        tracker.update_embedding(
            start_ms,
            start_ms + 800,
            _embedding(*value),
            segment_start_ms=3000,
            segment_end_ms=4400,
        )

    tracker.begin_segment(5000)
    for index, value in enumerate([(0.0, 1.0)] * 7):
        start_ms = 5000 + index * 200
        tracker.update_embedding(
            start_ms,
            start_ms + 800,
            _embedding(*value),
            segment_start_ms=5000,
            segment_end_ms=6400,
        )

    backfill = tracker.offline_backfill_unknowns()

    assert backfill["backfilled_windows"] == 0
    assert all(
        window.state == UNKNOWN_STATE
        for window in tracker.windows[:4]
    )


def test_offline_global_reclassify_recovers_early_two_speaker_windows():
    tracker = StreamingSpeakerTurnTracker(
        SpeakerTurnConfig(
            window_ms=800,
            hop_ms=200,
            confirm_windows=2,
            speaker_confirm_windows=5,
            speaker_min_span_ms=2000,
            min_turn_ms=400,
        )
    )

    tracker.begin_segment(0)
    for index, value in enumerate(
        [(1.0, 0.0), (0.0, 1.0), (1.0, 0.0), (0.0, 1.0)]
    ):
        start_ms = index * 200
        tracker.update_embedding(
            start_ms,
            start_ms + 800,
            _embedding(*value),
            segment_start_ms=0,
            segment_end_ms=1400,
        )

    tracker.begin_segment(2000)
    for index, value in enumerate(
        [(1.0, 0.0)] * 8 + [(0.0, 1.0)] * 8
    ):
        start_ms = 2000 + index * 200
        tracker.update_embedding(
            start_ms,
            start_ms + 800,
            _embedding(*value),
            segment_start_ms=2000,
            segment_end_ms=5200,
        )

    assert any(
        window.state == UNKNOWN_STATE for window in tracker.windows[:4]
    )

    result = tracker.offline_global_reclassify()

    assert result["speaker_count"] == 2
    assert [window.speaker_id for window in tracker.windows[:4]] == [0, 1, 0, 1]
    assert all(
        window.state == SPEAKER_STATE for window in tracker.windows[:4]
    )
    assert all(window.offline_reclassified for window in tracker.windows)


def test_offline_global_reclassify_keeps_global_outlier_unknown():
    tracker = StreamingSpeakerTurnTracker(
        SpeakerTurnConfig(
            window_ms=800,
            hop_ms=200,
            confirm_windows=2,
            speaker_confirm_windows=5,
            speaker_min_span_ms=2000,
            min_turn_ms=400,
        )
    )

    tracker.begin_segment(0)
    for index, value in enumerate(
        [(1.0, 0.0, 0.0)] * 6 + [(0.0, 1.0, 0.0)] * 6
    ):
        start_ms = index * 200
        tracker.update_embedding(
            start_ms,
            start_ms + 800,
            torch.tensor(value, dtype=torch.float32),
            segment_start_ms=0,
            segment_end_ms=2200,
        )

    tracker.begin_segment(3000)
    tracker.update_embedding(
        3000,
        3800,
        torch.tensor([0.0, 0.0, 1.0], dtype=torch.float32),
        segment_start_ms=3000,
        segment_end_ms=3800,
    )

    tracker.offline_global_reclassify()

    assert tracker.windows[-1].state == UNKNOWN_STATE
    assert tracker.windows[-1].speaker_id is None


def test_offline_global_merges_short_speaker_island_when_enabled():
    tracker = StreamingSpeakerTurnTracker(
        SpeakerTurnConfig(
            window_ms=800,
            hop_ms=200,
            confirm_windows=2,
            speaker_confirm_windows=5,
            speaker_min_span_ms=2000,
            min_turn_ms=400,
            offline_global_min_speaker_island_ms=500,
        )
    )

    tracker.begin_segment(0)
    sequence = [(1.0, 0.0)] * 6 + [(0.0, 1.0)] * 2
    sequence += [(1.0, 0.0)] * 6
    for index, value in enumerate(sequence):
        start_ms = index * 200
        tracker.update_embedding(
            start_ms,
            start_ms + 800,
            _embedding(*value),
            segment_start_ms=0,
            segment_end_ms=3600,
        )

    result = tracker.offline_global_reclassify()

    assert result["merged_short_speaker_islands"] == 1
    assert result["merged_short_speaker_windows"] == 2
    assert all(
        window.state == SPEAKER_STATE and window.speaker_id == 0
        for window in tracker.windows
    )
    assert len(tracker.finalize()) == 1


def test_offline_global_keeps_short_speaker_island_when_disabled():
    tracker = StreamingSpeakerTurnTracker(
        SpeakerTurnConfig(
            window_ms=800,
            hop_ms=200,
            confirm_windows=2,
            speaker_confirm_windows=5,
            speaker_min_span_ms=2000,
            min_turn_ms=400,
            offline_global_min_speaker_island_ms=0,
        )
    )

    tracker.begin_segment(0)
    sequence = [(1.0, 0.0)] * 6 + [(0.0, 1.0)] * 2
    sequence += [(1.0, 0.0)] * 6
    for index, value in enumerate(sequence):
        start_ms = index * 200
        tracker.update_embedding(
            start_ms,
            start_ms + 800,
            _embedding(*value),
            segment_start_ms=0,
            segment_end_ms=3600,
        )

    result = tracker.offline_global_reclassify()

    assert result["merged_short_speaker_islands"] == 0
    assert [window.speaker_id for window in tracker.windows] == (
        [0] * 6 + [1] * 2 + [0] * 6
    )


def test_offline_global_keeps_island_longer_than_minimum_span():
    tracker = StreamingSpeakerTurnTracker(
        SpeakerTurnConfig(
            window_ms=800,
            hop_ms=200,
            confirm_windows=2,
            speaker_confirm_windows=5,
            speaker_min_span_ms=2000,
            min_turn_ms=400,
            offline_global_min_speaker_island_ms=500,
        )
    )

    tracker.begin_segment(0)
    sequence = [(1.0, 0.0)] * 6 + [(0.0, 1.0)] * 4
    sequence += [(1.0, 0.0)] * 6
    for index, value in enumerate(sequence):
        start_ms = index * 200
        tracker.update_embedding(
            start_ms,
            start_ms + 800,
            _embedding(*value),
            segment_start_ms=0,
            segment_end_ms=4000,
        )

    result = tracker.offline_global_reclassify()

    assert result["merged_short_speaker_islands"] == 0
    assert [window.speaker_id for window in tracker.windows] == (
        [0] * 6 + [1] * 4 + [0] * 6
    )


def test_finalize_drops_zero_duration_turn_at_coincident_boundaries():
    tracker = StreamingSpeakerTurnTracker(
        SpeakerTurnConfig(
            window_ms=800,
            hop_ms=200,
            confirm_windows=2,
            speaker_confirm_windows=5,
            speaker_min_span_ms=2000,
            min_turn_ms=400,
        )
    )

    for index in range(3):
        start_ms = index * 800
        tracker.update_embedding(
            start_ms,
            start_ms + 800,
            _embedding(1.0, 0.0),
            segment_start_ms=0,
            segment_end_ms=2400,
        )

    tracker.windows[1].speaker_id = 0
    tracker.windows[1].state = SPEAKER_STATE
    tracker.windows[2].speaker_id = 1
    tracker.windows[2].state = SPEAKER_STATE
    tracker._accepted_switches = [
        {
            "window_index": 1,
            "boundary_ms": 800.0,
            "from_state": SPEAKER_STATE,
            "from_speaker_id": 0,
            "to_state": SPEAKER_STATE,
            "to_speaker_id": 0,
        },
        {
            "window_index": 2,
            "boundary_ms": 800.0,
            "from_state": SPEAKER_STATE,
            "from_speaker_id": 0,
            "to_state": SPEAKER_STATE,
            "to_speaker_id": 1,
        },
    ]

    turns = tracker.finalize()

    assert all(turn.duration_ms > 0 for turn in turns)

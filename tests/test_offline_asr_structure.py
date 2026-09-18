from offline_asr_structure import (
    align_timestamps_to_text,
    build_speaker_windows,
    build_timestamped_sentence_spans,
    map_sentences_to_turns,
    prepare_offline_punctuation,
)


def _turn(start_ms, end_ms, speaker_id):
    return {
        "start_ms": start_ms,
        "end_ms": end_ms,
        "speaker_id": speaker_id,
        "state": "SPEAKER",
    }


def test_build_speaker_windows_does_not_cross_vad_segments():
    windows = build_speaker_windows(
        [
            (0, 1000, None),
            (1400, 2000, None),
        ],
        window_ms=800,
        hop_ms=200,
    )

    assert windows == [
        {
            "segment_index": 0,
            "segment_start_ms": 0,
            "segment_end_ms": 1000,
            "start_ms": 0,
            "end_ms": 800,
        },
        {
            "segment_index": 0,
            "segment_start_ms": 0,
            "segment_end_ms": 1000,
            "start_ms": 200,
            "end_ms": 1000,
        },
        {
            "segment_index": 1,
            "segment_start_ms": 1400,
            "segment_end_ms": 2000,
            "start_ms": 1400,
            "end_ms": 2000,
        },
    ]


def _timestamp(token, start_time, end_time):
    return {
        "token": token,
        "start_time": start_time,
        "end_time": end_time,
        "score": 1.0,
    }


def test_offline_punctuation_keeps_existing_asr_punctuation():
    def fail_if_called(_text):
        raise AssertionError("punctuation model must not run twice")

    assert prepare_offline_punctuation("名字啊？", fail_if_called) == "名字啊？"
    assert prepare_offline_punctuation("李萍。", fail_if_called) == "李萍。"
    assert prepare_offline_punctuation("胸痹，我们", fail_if_called) == "胸痹，我们"
    assert prepare_offline_punctuation("好好，但愿", fail_if_called) == "好好，但愿"


def test_offline_punctuation_runs_only_for_punctuation_free_text():
    calls = []

    def punctuate(text):
        calls.append(text)
        return "你好吗？我很好。"

    assert (
        prepare_offline_punctuation("你好吗我很好", punctuate)
        == "你好吗？我很好。"
    )
    assert calls == ["你好吗我很好"]


def test_offline_punctuation_rejects_content_changes():
    assert (
        prepare_offline_punctuation("你好吗", lambda _text: "你还好吗？")
        == "你好吗"
    )


def test_align_timestamps_uses_only_retained_text_prefix():
    timestamps = [
        _timestamp("甲", 0.0, 0.1),
        _timestamp("乙", 0.1, 0.2),
        _timestamp("丙", 0.2, 0.3),
        _timestamp("丁", 0.3, 0.4),
    ]

    aligned = align_timestamps_to_text("甲乙", timestamps)

    assert [item["token"] for item in aligned] == ["甲", "乙"]
    assert aligned[0] == timestamps[0]
    assert aligned[1] == timestamps[1]


def test_align_timestamps_slices_multi_character_token_by_character():
    timestamps = [
        _timestamp("甲乙", 1.0, 1.4),
        _timestamp("丙", 1.4, 1.5),
    ]

    aligned = align_timestamps_to_text("甲", timestamps)

    assert len(aligned) == 1
    assert aligned[0]["token"] == "甲"
    assert aligned[0]["start_time"] == 1.0
    assert aligned[0]["end_time"] == 1.2


def test_align_timestamps_rejects_non_prefix_text():
    timestamps = [
        _timestamp("甲", 0.0, 0.1),
        _timestamp("丙", 0.1, 0.2),
    ]

    assert align_timestamps_to_text("甲乙", timestamps) == []


def test_align_timestamps_returns_empty_for_missing_text():
    assert align_timestamps_to_text("", [_timestamp("甲", 0.0, 0.1)]) == []


def test_timestamped_sentence_spans_keep_punctuation_out_of_next_sentence_times():
    spans = build_timestamped_sentence_spans(
        "甲。乙丙。",
        "甲？乙丙。",
        [
            _timestamp("甲", 0.0, 0.1),
            _timestamp("。", 0.1, 0.15),
            _timestamp("乙", 0.15, 0.25),
            _timestamp("丙", 0.25, 0.35),
            _timestamp("。", 0.35, 0.4),
        ],
        segment_start_ms=1000,
        segment_end_ms=1400,
    )

    assert spans == [
        {
            "text": "甲？",
            "start": 1000,
            "end": 1100,
            "_char_times": [("甲", 1000, 1100)],
        },
        {
            "text": "乙丙。",
            "start": 1150,
            "end": 1350,
            "_char_times": [
                ("乙", 1150, 1250),
                ("丙", 1250, 1350),
            ],
        },
    ]


def test_timestamped_sentence_spans_fall_back_to_linear_time_on_mismatch():
    spans = build_timestamped_sentence_spans(
        "甲乙丙。",
        "甲乙丙。",
        [_timestamp("甲", 0.0, 0.1)],
        segment_start_ms=1000,
        segment_end_ms=4000,
    )

    assert spans == [
        {
            "text": "甲乙丙。",
            "start": 1000,
            "end": 4000,
            "_char_times": [],
        }
    ]


def test_map_sentence_splits_at_character_level_speaker_boundary():
    sentence = {
        "text": "甲乙丙，丁戊己。",
        "start": 0,
        "end": 600,
        "_char_times": [
            ("甲", 0, 100),
            ("乙", 100, 200),
            ("丙", 200, 300),
            ("丁", 300, 400),
            ("戊", 400, 500),
            ("己", 500, 600),
        ],
    }

    mapped = map_sentences_to_turns(
        [sentence],
        [
            _turn(0, 300, 0),
            _turn(300, 600, 1),
        ],
    )

    assert mapped == [
        {"text": "甲乙丙，", "start": 0, "end": 300, "spk": 0},
        {"text": "丁戊己。", "start": 300, "end": 600, "spk": 1},
    ]
    assert "".join(part["text"] for part in mapped) == sentence["text"]


def test_map_sentence_absorbs_single_character_speaker_tail():
    sentence = {
        "text": "甲乙丙丁。",
        "start": 0,
        "end": 400,
        "_char_times": [
            ("甲", 0, 100),
            ("乙", 100, 200),
            ("丙", 200, 300),
            ("丁", 300, 400),
        ],
    }

    mapped = map_sentences_to_turns(
        [sentence],
        [
            _turn(0, 300, 0),
            _turn(300, 400, 1),
        ],
    )

    assert mapped == [
        {"text": "甲乙丙丁。", "start": 0, "end": 400, "spk": 0}
    ]


def test_map_sentence_keeps_short_leading_reply_supported_by_tracker_turn():
    sentence = {
        "text": "有，我觉得好。",
        "start": 444580,
        "end": 445100,
        "_char_times": [
            ("有", 444580, 444680),
            ("我", 444700, 444800),
            ("觉", 444800, 444900),
            ("得", 444900, 445000),
            ("好", 445000, 445100),
        ],
    }

    mapped = map_sentences_to_turns(
        [sentence],
        [
            _turn(444300, 444700, 0),
            _turn(444700, 448000, 1),
        ],
    )

    assert mapped == [
        {"text": "有，", "start": 444580, "end": 444680, "spk": 0},
        {"text": "我觉得好。", "start": 444700, "end": 445100, "spk": 1},
    ]


def test_map_sentence_absorbs_tail_starting_exactly_at_tracker_boundary():
    sentence = {
        "text": "甲乙丙丁，吗？",
        "start": 447600,
        "end": 448100,
        "_char_times": [
            ("甲", 447600, 447700),
            ("乙", 447700, 447800),
            ("丙", 447800, 447900),
            ("丁", 447900, 448000),
            ("吗", 448000, 448100),
        ],
    }

    mapped = map_sentences_to_turns(
        [sentence],
        [
            _turn(444700, 448000, 0),
            _turn(448000, 450000, 1),
        ],
    )

    assert mapped == [
        {"text": "甲乙丙丁，吗？", "start": 447600, "end": 448100, "spk": 0}
    ]


def test_map_sentence_does_not_split_short_phrase_across_speakers():
    sentence = {
        "text": "有一，个，你好。",
        "start": 0,
        "end": 700,
        "_char_times": [
            ("有", 0, 100),
            ("一", 100, 200),
            ("个", 250, 350),
            ("你", 400, 500),
            ("好", 500, 600),
        ],
    }

    mapped = map_sentences_to_turns(
        [sentence],
        [
            _turn(0, 225, 0),
            _turn(225, 700, 1),
        ],
    )

    assert len(mapped) == 1
    assert mapped[0]["text"] == sentence["text"]


def test_map_sentence_keeps_short_internal_interjection_between_speakers():
    sentence = {
        "text": "甲乙丙丁，戊己，庚辛壬癸。",
        "start": 0,
        "end": 1300,
        "_char_times": [
            ("甲", 0, 200),
            ("乙", 200, 400),
            ("丙", 400, 600),
            ("丁", 600, 700),
            ("戊", 700, 750),
            ("己", 750, 800),
            ("庚", 800, 900),
            ("辛", 900, 1000),
            ("壬", 1000, 1100),
            ("癸", 1100, 1300),
        ],
    }

    mapped = map_sentences_to_turns(
        [sentence],
        [
            _turn(0, 700, 0),
            _turn(700, 800, 1),
            _turn(800, 1300, 0),
        ],
    )

    assert mapped == [
        {"text": "甲乙丙丁，", "start": 0, "end": 700, "spk": 0},
        {"text": "戊己，", "start": 700, "end": 800, "spk": 1},
        {"text": "庚辛壬癸。", "start": 800, "end": 1300, "spk": 0},
    ]


def test_map_sentence_absorbs_unknown_short_run_between_speakers():
    sentence = {
        "text": "甲乙丙丁戊己。",
        "start": 0,
        "end": 900,
        "_char_times": [
            ("甲", 0, 150),
            ("乙", 150, 300),
            ("丙", 300, 450),
            ("丁", 450, 600),
            ("戊", 600, 750),
            ("己", 750, 900),
        ],
    }

    mapped = map_sentences_to_turns(
        [sentence],
        [
            _turn(0, 450, 0),
            _turn(750, 900, 1),
        ],
        max_gap_ms=0,
    )

    assert mapped == [
        {"text": "甲乙丙丁戊己。", "start": 0, "end": 900, "spk": 0}
    ]


def test_map_sentence_keeps_single_turn_unchanged():
    sentence = {
        "text": "嗯，然后呢？",
        "start": 100,
        "end": 900,
        "_char_times": [
            ("嗯", 100, 200),
            ("然", 200, 300),
            ("后", 300, 400),
            ("呢", 400, 500),
        ],
    }

    mapped = map_sentences_to_turns([sentence], [_turn(0, 1000, 1)])

    assert mapped == [
        {"text": "嗯，然后呢？", "start": 100, "end": 900, "spk": 1}
    ]


def test_overlap_fallback_uses_nearest_known_speaker_across_short_gap():
    sentence = {
        "text": "嗯。",
        "start": 900,
        "end": 1050,
    }

    mapped = map_sentences_to_turns(
        [sentence],
        [
            _turn(0, 900, 0),
            _turn(1100, 2000, 1),
        ],
        max_gap_ms=200,
    )

    assert mapped == [
        {"text": "嗯。", "start": 900, "end": 1050, "spk": 0}
    ]


def test_map_sentences_does_not_merge_separate_pause_spans():
    sentences = [
        {"text": "就是。", "start": 0, "end": 300},
        {"text": "然后呢？", "start": 1200, "end": 1600},
    ]

    mapped = map_sentences_to_turns(sentences, [_turn(0, 2000, 0)])

    assert mapped == [
        {"text": "就是。", "start": 0, "end": 300, "spk": 0},
        {"text": "然后呢？", "start": 1200, "end": 1600, "spk": 0},
    ]


def test_map_sentences_merges_close_fragments_from_same_speaker():
    sentences = [
        {"text": "甲甲。", "start": 0, "end": 320},
        {"text": "乙乙。", "start": 400, "end": 900},
        {"text": "丙。", "start": 970, "end": 1200},
        {"text": "丁。", "start": 1200, "end": 1500},
    ]

    mapped = map_sentences_to_turns(
        sentences,
        [
            _turn(0, 500, 0),
            _turn(500, 1000, 1),
        ],
        merge_gap_ms=500,
    )

    assert mapped == [
        {"text": "甲甲。", "start": 0, "end": 320, "spk": 0},
        {"text": "乙乙。丙。丁。", "start": 400, "end": 1500, "spk": 1},
    ]
    assert "".join(part["text"] for part in mapped) == "".join(
        sentence["text"] for sentence in sentences
    )


def test_map_sentences_does_not_merge_different_speakers():
    sentences = [
        {"text": "甲。", "start": 0, "end": 300},
        {"text": "乙。", "start": 400, "end": 700},
    ]

    mapped = map_sentences_to_turns(
        sentences,
        [
            _turn(0, 350, 0),
            _turn(350, 1000, 1),
        ],
        merge_gap_ms=500,
    )

    assert [part["spk"] for part in mapped] == [0, 1]

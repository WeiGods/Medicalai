# Speaker Turn Annotation Pack

Fill `annotation.csv` in this directory. Keep `annotation_template.csv` unchanged.

Per-audio mapping:

- `audio1`: speaker 0 = A, speaker 1 = B
- `audio2`: speaker 0 = A, speaker 1 = B

Allowed values:

- `is_real_change`: `YES`, `NO`, or `OVERLAP`.
- `true_left` / `true_right`: `A`, `B`, `OVERLAP`, or `NONE`.
- `true_boundary_ms`: one integer in milliseconds when there is a real change.
- `notes`: free text for ambiguous cases.

| Item | Type | Target ms | Detected | Clip |
|---|---|---:|---|---|
| `audio1_boundary_26910_35020` | key_boundary | 26910-35020 | B>A @ 26910;32510 ms | [audio1_boundary_26910_35020.wav](audio1_boundary_26910_35020.wav) |
| `audio1_boundary_55750_65650` | key_boundary | 55750-65650 | A>B>A>B>A @ 58450;60850;61650;64450 ms | [audio1_boundary_55750_65650.wav](audio1_boundary_55750_65650.wav) |
| `audio1_boundary_112840_122430` | key_boundary | 112840-122430 | A>B>A @ 114940;118940 ms | [audio1_boundary_112840_122430.wav](audio1_boundary_112840_122430.wav) |
| `audio1_boundary_135680_141130` | key_boundary | 135680-141130 | A>B>A @ 138680;139980 ms | [audio1_boundary_135680_141130.wav](audio1_boundary_135680_141130.wav) |
| `audio1_boundary_443390_445790` | key_boundary | 443390-445790 | A>B>A @ 444390;444790 ms | [audio1_boundary_443390_445790.wav](audio1_boundary_443390_445790.wav) |
| `audio1_boundary_446060_449800` | key_boundary | 446060-449800 | A>B>A @ 448160;448960 ms | [audio1_boundary_446060_449800.wav](audio1_boundary_446060_449800.wav) |
| `audio1_boundary_637340_654920` | key_boundary | 637340-654920 | A>B>A @ 640140;649840 ms | [audio1_boundary_637340_654920.wav](audio1_boundary_637340_654920.wav) |
| `audio1_short_900_1300` | short_turn | 900-1300 | B @ 900;1300 ms | [audio1_short_900_1300.wav](audio1_short_900_1300.wav) |
| `audio1_short_86530_86730` | short_turn | 86530-86730 | B @ 86530;86730 ms | [audio1_short_86530_86730.wav](audio1_short_86530_86730.wav) |
| `audio1_short_99160_99460` | short_turn | 99160-99460 | B @ 99160;99460 ms | [audio1_short_99160_99460.wav](audio1_short_99160_99460.wav) |
| `audio1_short_151300_151500` | short_turn | 151300-151500 | B @ 151300;151500 ms | [audio1_short_151300_151500.wav](audio1_short_151300_151500.wav) |
| `audio1_short_151500_151700` | short_turn | 151500-151700 | A @ 151500;151700 ms | [audio1_short_151500_151700.wav](audio1_short_151500_151700.wav) |
| `audio1_short_188095_188530` | short_turn | 188095-188530 | B @ 188095 ms | [audio1_short_188095_188530.wav](audio1_short_188095_188530.wav) |
| `audio1_short_444390_444790` | short_turn | 444390-444790 | B @ 444390;444790 ms | [audio1_short_444390_444790.wav](audio1_short_444390_444790.wav) |
| `audio1_short_662270_662470` | short_turn | 662270-662470 | B @ 662270;662470 ms | [audio1_short_662270_662470.wav](audio1_short_662270_662470.wav) |
| `audio1_short_662470_662870` | short_turn | 662470-662870 | A @ 662470;662870 ms | [audio1_short_662470_662870.wav](audio1_short_662470_662870.wav) |
| `audio2_short_99510_99810` | short_turn | 99510-99810 | A @ 99510;99810 ms | [audio2_short_99510_99810.wav](audio2_short_99510_99810.wav) |
| `audio2_short_105675_106140` | short_turn | 105675-106140 | B @ 105675 ms | [audio2_short_105675_106140.wav](audio2_short_105675_106140.wav) |
| `audio2_short_242550_242950` | short_turn | 242550-242950 | A @ 242550;242950 ms | [audio2_short_242550_242950.wav](audio2_short_242550_242950.wav) |
| `audio2_short_282140_282540` | short_turn | 282140-282540 | B @ 282140;282540 ms | [audio2_short_282140_282540.wav](audio2_short_282140_282540.wav) |
| `audio2_short_378600_378900` | short_turn | 378600-378900 | B @ 378600;378900 ms | [audio2_short_378600_378900.wav](audio2_short_378600_378900.wav) |
| `audio2_short_404300_404500` | short_turn | 404300-404500 | B @ 404300;404500 ms | [audio2_short_404300_404500.wav](audio2_short_404300_404500.wav) |
| `audio2_short_423655_424140` | short_turn | 423655-424140 | B @ 423655 ms | [audio2_short_423655_424140.wav](audio2_short_423655_424140.wav) |

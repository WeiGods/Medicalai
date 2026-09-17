# 人工标注说明

本次只填写同目录下的 `annotation.csv`。不要覆盖模板
`annotation_template.csv`。

## 填写方法

每条记录先听对应 WAV。短片段前后各保留 2 秒上下文，所以应以 WAV 中实际
听到的语音为准，不要只根据 CSV 里的检测结果判断。

`is_real_change` 只能填：

- `YES`：确实发生了换话。
- `NO`：检测出的换话或短轮次是错误切换。
- `OVERLAP`：关键位置主要是两人重叠说话。

`true_left` / `true_right` 只能填 `A`、`B`、`OVERLAP` 或 `NONE`：

- 单次换话时，分别填换话前后的说话人。
- 连续换话时，`true_left` 填第一个说话人，`true_right` 填最后一个说话人，
  完整顺序写进 `notes`，例如 `A>B>A>B>A`。

`true_boundary_ms`：

- 单次换话填一个整数毫秒值。
- 关键区间存在多次换话时，按先后顺序用分号分隔，例如
  `58400;60800;61700;64400`。
- 短轮次确认是真实接话时，填该短轮次的真实开始和结束毫秒值，例如
  `99150;99450`。

不确定时不要猜，填 `NO` 之外的值前优先在 `notes` 写明听感、重叠或噪声情况。

## 本轮重点

基线共有 16 个 `<500 ms` 短轮次需要标注。候选 `500 ms` 合并了其中 9 个，
因此这 9 条是检查“是否误删真实接话”的关键：

```text
audio1_short_900_1300
audio1_short_86530_86730
audio1_short_151300_151500
audio1_short_151500_151700
audio1_short_662270_662470
audio1_short_662470_662870
audio2_short_242550_242950
audio2_short_282140_282540
audio2_short_404300_404500
```

候选 `500 ms` 后仍保留的 7 个短轮次也需要逐一确认，判断它们是真接话、
误切换还是重叠：

```text
audio1_short_99160_99460
audio1_short_188095_188530
audio1_short_444390_444790
audio2_short_99510_99810
audio2_short_105675_106140
audio2_short_378600_378900
audio2_short_423655_424140
```

7 个关键边界区间必须全部标注。标注完成后的边界命中率、平均误差和参数选择
都只使用人工真值计算。

## 标注后评估

填写完成后，在仓库根目录运行：

```powershell
python asr_validation\annotation_tools\evaluate_speaker_annotations.py --annotation asr_validation\global_validation\annotation_pack\annotation.csv --candidate-dir asr_validation\global_validation\results\candidate --output-json asr_validation\global_validation\results\annotation_evaluation.json --output-md asr_validation\global_validation\results\annotation_evaluation.md
```

评估器会先检查 23 条记录是否完整，再计算关键边界命中率、误报、漏报、
平均绝对误差，并比较 `500/600/800 ms` 候选对真实短接话和误切换的处理结果。
如果记录有缺失或字段格式错误，评估器不会生成指标，只输出需要修正的项目。

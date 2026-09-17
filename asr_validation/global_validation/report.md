# 全局两人重分类验证记录

验证日期：2026-09-17

验证状态：人工标注评估闭环完成；候选 `500 ms` 继续作为暂定默认参数

结论：`UNKNOWN`、零时长和说话人数目标通过。人工标注评估中 `500/600 ms`
并列最优，确认的短轮次错误均为 4 个；`800 ms` 虽然进一步降低比例，但增加到
5 个。两份音频合计 `<500 ms` 比例仍为 1.67%，未达到不超过 1% 的目标。

7 个关键区间的完整人工顺序都能在带 2 秒上下文的 clip 内连续找到。按 clip
完整序列严格比较为 4/7；差异来自 clip 上下文多出的首/尾标签，`不可包含`
的硬性顺序错误为 0。本轮没有填写毫秒级 `true_boundary_ms`，因此不评估边界
FP/FN、命中率和平均毫秒误差。

## 验证环境

| 项目 | 值 |
|---|---|
| Docker 镜像 | `mouyu/medicalai:local_ai-mergeoff-20260917` |
| 容器 | `medicalai-offline-turn-20260917` |
| GPU | `NVIDIA GeForce RTX 5060 8151 MiB` |
| Torch | `2.11.0+cu130` |
| FunASR | `1.4.14` |
| 音频 1 | `01病史采集`，668.160 秒 |
| 音频 2 | `问诊胸痛`，496.303 秒 |

固定参数：

| 参数 | 值 |
|---|---:|
| VAD end silence | `600 ms` |
| Window | `800 ms` |
| Hop | `200 ms` |
| Confirm windows | `3` |
| Speaker confirm windows | `5` |
| Speaker min span | `2000 ms` |
| Min turn | `400 ms` |
| Max speakers | `2` |
| Global iterations | `25` |
| Global match threshold | `0.32` |
| Global margin threshold | `0.04` |
| Global unknown threshold | `0.25` |
| Global gap fill | `1000 ms` |
| Global unknown run limit | `2 windows` |

两份音频使用完全相同的参数，启用 `offline_global_reclassify`，未使用旧的
`offline_backfill` 路径。

## 汇总指标

| 指标 | 音频 1 | 音频 2 | 目标 | 结果 |
|---|---:|---:|---:|---|
| VAD segments | 134 | 89 | - | - |
| Turns | 245 | 188 | - | - |
| Transitions | 189 | 136 | - | - |
| Speaker count | 2 | 2 | 2 | 通过 |
| UNKNOWN window ratio | 1.5625% | 0.8941% | < 3% | 通过 |
| OVERLAP window ratio | 0.6944% | 0.0471% | - | - |
| Zero-duration turns | 0 | 0 | 0 | 通过 |
| Real turns under 500 ms | 9 | 7 | 不超过 1% | 未通过 |
| Real turns under 500 ms ratio | 3.67% | 3.72% | 不超过 1% | 未通过 |
| Mixed VAD segments | 60 | 35 | - | - |
| P50 turn | 1600 ms | 2000 ms | - | - |
| P95 turn | 5200 ms | 6330 ms | - | - |
| Max turn | 13580 ms | 15580 ms | - | - |

第一份音频的 7 个关键区间都出现了说话人切换，说明全局重分类已经能切开盘内换话。
短碎片由逐窗口全局归属产生，没有通过硬阈值扫描解决。

## 与旧回填结果对比

音频 1，旧 `800 ms + offline_backfill` 与当前全局重分类：

| 指标 | 旧回填 | 全局重分类 | 变化 |
|---|---:|---:|---:|
| Turns | 219 | 245 | +26 |
| Transitions | 150 | 189 | +39 |
| UNKNOWN ratio | 8.5503% | 1.5625% | -6.9878 个百分点 |
| Zero-duration turns | 2 | 0 | -2 |
| Real turns under 500 ms | 0 | 9 | +9 |
| Mixed VAD segments | 49 | 60 | +11 |
| P50 turn | 2000 ms | 1600 ms | -400 ms |
| P95 turn | 5506 ms | 5200 ms | -306 ms |

全局重分类解决了旧回填的主要覆盖问题，但增加了短碎片和切换次数。

## 候选短片段合并

基线逐窗口全局归属产生的 `SPEAKER` 微段没有被现有 `merge_short_turns()`
处理。本轮增加保守的全局标签层合并：只有短 `SPEAKER` 岛两侧都是同一个全局
说话人时才吸收，邻接 `UNKNOWN`、`OVERLAP` 或不同说话人的岛保持不变。

该策略通过 `offline_global_min_speaker_island_ms` 控制，默认 `0`，不改变基线。
候选值测试结果：

| 音频 | 参数 | Turns | `<500 ms` | 比例 | 合并岛 | 合并窗口 | 判定 |
|---|---:|---:|---:|---:|---:|---:|---|
| 音频 1 | 基线 0 ms | 245 | 9 | 3.67% | 0 | 0 | 未通过 |
| 音频 1 | 500 ms | 237 | 3 | 1.27% | 4 | 5 | 接近 |
| 音频 1 | 600 ms | 237 | 3 | 1.27% | 4 | 5 | 同上 |
| 音频 1 | 800 ms | 233 | 2 | 0.86% | 8 | 12 | 单份通过 |
| 音频 2 | 基线 0 ms | 188 | 7 | 3.72% | 0 | 0 | 未通过 |
| 音频 2 | 500 ms | 182 | 4 | 2.20% | 3 | 5 | 未通过 |
| 音频 2 | 600 ms | 182 | 4 | 2.20% | 3 | 5 | 同上 |
| 音频 2 | 800 ms | 166 | 4 | 2.41% | 13 | 27 | 变差 |

`500 ms` 和 `600 ms` 在两份音频上等价。`800 ms` 只让音频 1 达标，却让音频 2
比例升高，同时把音频 2 的合并岛从 3 增加到 13，存在吞掉真实短接话的风险。
因此候选默认值暂定 `500 ms`，不采用 `800 ms` 作为全局参数。

候选 `500 ms` 后仍保留的 7 个短轮次：

音频 1：

| 时间范围 | 时长 | Speaker |
|---|---:|---:|
| 99160-99460 ms | 300 ms | 1 |
| 188095-188530 ms | 435 ms | 1 |
| 444390-444790 ms | 400 ms | 1 |

音频 2：

| 时间范围 | 时长 | Speaker |
|---|---:|---:|
| 99510-99810 ms | 300 ms | 0 |
| 105675-106140 ms | 465 ms | 1 |
| 378600-378900 ms | 300 ms | 1 |
| 423655-424140 ms | 485 ms | 1 |

另外有 9 个基线短轮次被 `500 ms` 候选合并，人工标注必须同时覆盖这些片段，
否则无法判断合并是否误删了真实接话。

## 人工标注评估闭环

本轮新增
`asr_validation/annotation_tools/evaluate_speaker_annotations.py`，用于在人工填写
`annotation.csv` 后完成三件事：

1. 自动识别 Tab/逗号分隔；当前人工文件是 Tab 分隔的 `annotation.csv`。
2. 有毫秒真值时才做边界一对一匹配；缺失 `true_boundary_ms` 只记 warning，
   不生成虚假边界指标。
3. 关键边界在 `clip_start_ms..clip_end_ms` 内比较完整序列，同时报告
   `exact` 与人工顺序是否为检测序列的连续子序列。
4. 根据人工真值比较短轮次合并结果：
   `YES` 被合并记为误删真实接话，`NO` 被合并记为修复误切换，
   `OVERLAP` 单独统计。

空的标注包已经做过端到端校验：23 条记录全部识别为待填写，没有生成虚假指标。
测试覆盖了边界匹配、短接话保留、误切换合并和空标注拒绝计算。

标注完成后的命令：

```powershell
python asr_validation\annotation_tools\evaluate_speaker_annotations.py --annotation asr_validation\global_validation\annotation_pack\annotation.csv --candidate-dir asr_validation\global_validation\results\candidate --output-json asr_validation\global_validation\results\annotation_evaluation.json --output-md asr_validation\global_validation\results\annotation_evaluation.md
```

`500/600/800 ms` 的音频 1、音频 2 候选 JSON 已复制到被 `.gitignore` 排除的
`asr_validation/global_validation/results/candidate/`，评估结果也写入
`results/`，不会进入提交。

### 标注后评估结果

标注校验：23/23 条有效，0 个错误，14 个 `true_boundary_ms` 缺失 warning。
人工分布为 `YES 7`、`NO 1`、`OVERLAP 8`。

| 候选 | 音频 | 真实短轮次保留 | 真实短轮次误删 | 误切换保留 | OVERLAP 保留/删除 | `<500 ms` 比例 |
|---|---|---:|---:|---:|---:|---:|
| `500/600 ms` | 音频 1 | 3 | 1 | 0 | 2/3 | 1.27% |
| `500/600 ms` | 音频 2 | 1 | 2 | 1 | 2/1 | 2.20% |
| `800 ms` | 音频 1 | 2 | 2 | 0 | 2/3 | 0.86% |
| `800 ms` | 音频 2 | 1 | 2 | 1 | 2/1 | 2.41% |

关键边界顺序比较（仅音频 1 有人工关键边界）：

| 候选 | 严格完整序列 | 连续子序列 | 不可包含错误 |
|---|---:|---:|---:|
| `500/600/800 ms` | 4/7 | 7/7 | 0 |

三个严格不匹配都是 clip 上下文在人工序列前/后多出的标签：

| 范围 | 人工顺序 | 检测序列 |
|---|---|---|
| `55750-65650 ms` | `A>B>A>B>A` | `B>A>B>A>B>A` |
| `135680-141130 ms` | `A>B>A>B` | `B>A>B>A>B` |
| `446060-449800 ms` | `B>A>B>A` | `A>B>A>B>A` |

人工短轮次错误汇总：`500/600 ms` 为 4 个，`800 ms` 为 5 个。
`800 ms` 额外误删了音频 1 的 `188095-188530 ms` 真实 `B>A` 接话。
因此继续选择 `500 ms` 作为候选默认值；`500 ms` 与 `600 ms` 结果等价。

## Validator 复用

候选重跑首次暴露 embedding cache 假不匹配：提取阶段向窗口写入 `rms`，缓存
重载时新窗口没有该字段。validator 现在按稳定窗口字段校验缓存，并恢复
`rms`，不再因为该元数据差异拒绝有效缓存。

validator 新增 `--vad-segments-json`，可复用基线结果中的 `vad_segments`。
音频 1 复用验证的 `summary`、`turns` 和全局分类统计与完整重跑一致。
VAD 复用后的候选运行时间从约 27 秒降到约 2 秒；语音门控边界不参与说话人
重新分类，因此该复用不会改变本轮实验变量。

2026-09-17 已将宿主机修复后的 `asr_validation/validate_speaker_turns.py`
同步到验证容器，并用 `--vad-segments-json` 和 embedding cache 复跑音频 1。
同步后结果的 `vad_segments`、`turns`、`speaker_count`、`transitions`、
`under_500ms`、`zero_duration_turns`、`mixed_vad_segments`、
`unknown_window_ratio` 和 `overlap_window_ratio` 均与归档的 `500 ms`
候选逐项一致，确认缓存修复没有改变候选指标。

容器未安装 `pytest`，本轮直接发现并调用 `tests/test_streaming_speaker_turns.py`
中的 18 个测试函数，结果为 18/18 通过。最终记录以宿主机和容器哈希一致的
`streaming_speaker_turns.py` 为准。

## 早期归属修复

旧回填结果中的三段早期 `UNKNOWN`：

| 时间范围 | 旧状态 |
|---|---|
| 8190-8990 ms | `UNKNOWN` |
| 9090-10640 ms | `UNKNOWN` |
| 11730-14090 ms | `UNKNOWN` |

当前全局重分类结果：

| 时间范围 | 归属 |
|---|---|
| 8190-8990 ms | speaker 0 |
| 9090-10640 ms | speaker 1 |
| 11730-12830 ms | speaker 0 |
| 12830-14090 ms | speaker 1 |

`8190-14090 ms` 已经不再保留为大块 `UNKNOWN`。相邻没有窗口的时间段是 VAD 或
窗口覆盖间隙，不是未知归属窗口。

## 关键边界

下表记录音频 1 当前实际产生的切换点。说话人编号是相对编号，不代表固定的医生或患者。

| 关键范围 | 实际切换点 | 短碎片 |
|---|---|---|
| 26910-35020 ms | 32510 | 无 |
| 55750-65650 ms | 58450, 60850, 61650, 64450 | 无 |
| 112840-122430 ms | 114940, 118940 | 无 |
| 135680-141130 ms | 138680, 139980 | 无 |
| 443390-445790 ms | 444390, 444790 | 444390-444790 为 400 ms |
| 446060-449800 ms | 448160, 448960 | 无 |
| 637340-654920 ms | 640140, 649840 | 无 |

人工顺序评估显示：7 个关键区间的顺序都能作为检测序列的连续子序列找到，
说明关键换话没有被吞掉。因为未提供毫秒级 `true_boundary_ms`，仍不能计算边界
命中率、FP/FN 或平均毫秒误差。

## 短轮次明细

音频 1：

| 时间范围 | 时长 | Speaker |
|---|---:|---:|
| 900-1300 ms | 400 ms | 1 |
| 86530-86730 ms | 200 ms | 1 |
| 99160-99460 ms | 300 ms | 1 |
| 151300-151500 ms | 200 ms | 1 |
| 151500-151700 ms | 200 ms | 0 |
| 188095-188530 ms | 435 ms | 1 |
| 444390-444790 ms | 400 ms | 1 |
| 662270-662470 ms | 200 ms | 1 |
| 662470-662870 ms | 400 ms | 0 |

音频 2：

| 时间范围 | 时长 | Speaker |
|---|---:|---:|
| 99510-99810 ms | 300 ms | 0 |
| 105675-106140 ms | 465 ms | 1 |
| 242550-242950 ms | 400 ms | 0 |
| 282140-282540 ms | 400 ms | 1 |
| 378600-378900 ms | 300 ms | 1 |
| 404300-404500 ms | 200 ms | 1 |
| 423655-424140 ms | 485 ms | 1 |

其中连续微段需要重点复核，例如音频 1 的 `151300-151700 ms` 和
`662270-662870 ms`。这些片段可能是一个短换话被拆成两个微段，也可能是错误切换。

## 实现观察

`min_turn_ms` 目前只限制候选切换的建立，不是最终轮次的硬最小长度。
`merge_short_turns()` 只处理 `UNKNOWN` 和 `OVERLAP` 小岛，并且没有在
`finalize()` 主路径中调用。本轮没有直接修改实时主路径，而是在全局标签层
增加保守的 `SPEAKER` 岛合并，避免影响实时行为。

下一轮不应降低或提高 `offline_global_match_threshold` 来掩盖问题。优先处理切换持续性
和短轮次后处理，再校准全局阈值。

## 判定

已通过：

- 两份音频都稳定为 2 个说话人。
- 音频 1 `UNKNOWN` 为 1.5625%，音频 2 为 0.8941%，均低于 3%。
- 两份音频的零时长轮次均为 0。
- 音频 1 的早期 `8190-14090 ms` 不再是大块 `UNKNOWN`。
- 两份音频能够使用同一组参数完整运行。

候选 `500 ms` 仍未通过：

- 合计 `<500 ms` 比例为 1.67%，高于 1% 目标。
- 音频 1 仍误删 1 个真实短接话；音频 2 误删 2 个并保留 1 个误切换。

尚未验证：

- 7 个关键边界的精确时间误差。
- A/B 角色在两份音频全时段的全局一致性；本轮只在标注 clip 中核对顺序。
- 实时 1.2-1.6 秒滚动缓冲的行为；该功能本轮未测试。

## 下一轮工作

1. 只针对已标注的 4 个 `500 ms` 短轮次错误设计后处理：优先恢复被误删的
   真实接话，同时不重新引入音频 2 的 `NO` 误切换。
2. 不继续盲扫 `500/600/800 ms` 或全局阈值；候选值变更必须同时汇报
   保留/误删/误切换/OVERLAP 分类结果。
3. 补充关键边界毫秒真值后，再评估边界 FP/FN 和平均误差。
4. 离线通过后再实现实时 1.2-1.6 秒 provisional/回溯逻辑。

## 原始产物

| 文件 | 路径 |
|---|---|
| 音频 1 结果 | `asr_validation/global_validation/results/global_w800_audio1.json` |
| 音频 2 结果 | `asr_validation/global_validation/results/global_w800_audio2.json` |
| 音频 1 embedding cache | `asr_validation/global_validation/results/embedding_w800_audio1.pt` |
| 音频 2 embedding cache | `asr_validation/global_validation/results/embedding_w800_audio2.pt` |
| 运行日志 | `asr_validation/global_validation/results/*.log` |
| 候选结果 | `asr_validation/global_validation/results/candidate/` |
| 标注评估输出 | `asr_validation/global_validation/results/annotation_evaluation.*` |

原始结果目录由 `.gitignore` 中的 `/asr_validation/**/results/` 排除，不会进入提交。

代码版本：

| 文件 | SHA256 |
|---|---|
| `streaming_speaker_turns.py` | `40A4535EF88D5E9B277354FB835E919AD22ED96BADFDEAF8A221191CE1D2940C` |
| `asr_validation/validate_speaker_turns.py` | `5B9FEBF5BE9FD5C37C4E55DD054507B34EF77319D2365E8BFD6C343B008B89B4` |
| `asr_validation/annotation_tools/build_speaker_annotation_pack.py` | `7F3E0B4F349A63CDA3EA01FD47E1F10903EFBC3FEB1F2D711217E974B6613D91` |
| `asr_validation/annotation_tools/evaluate_speaker_annotations.py` | `CEE63BDA51F19A35E7E99B69C5C636E504A9B469500E2041E632647EF0F14F81` |

package com.medicalai.service;

import com.medicalai.mapper.RecordingMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 为不可变对话快照计算稳定哈希。
 *
 * <p>证据不仅依赖文本，也依赖角色、时间范围和角色采纳来源。所有这些字段都必须进入哈希，
 * 否则角色修订后可能错误复用旧提取结果，造成“同一证据指向不同快照”的版本串联问题。
 */
final class DialogueSnapshotHasher {
    private DialogueSnapshotHasher() {}

    static String hash(UUID visitId, List<RecordingMapper.Turn> turns) {
        try {
            StringBuilder source = new StringBuilder("visit=").append(visitId).append('\n');
            for (RecordingMapper.Turn turn : turns) {
                append(source, turn.role());
                append(source, turn.text());
                append(source, turn.startMs());
                append(source, turn.endMs());
                append(source, turn.speakerId());
                append(source, turn.roleSource());
                append(source, turn.roleConfidence());
                append(source, turn.roleProviderRoute());
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("转写快照哈希生成失败", error);
        }
    }

    /** 长度前缀避免不同字段组合在拼接后产生歧义。 */
    private static void append(StringBuilder target, Object value) {
        String text = value == null ? "<null>" : String.valueOf(value);
        target.append(text.length()).append(':').append(text).append('\n');
    }
}

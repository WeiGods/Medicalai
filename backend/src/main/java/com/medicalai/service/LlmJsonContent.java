package com.medicalai.service;

/**
 * 从 LLM 回答的 {@code content} 里取出 JSON 文本的公共处理。
 *
 * <p>{@code llama-server} 的 {@code --reasoning-format} 决定思考内容落在 {@code reasoning_content}
 * 还是直接混进 {@code content}；后者会让一份完全合法的回答变成 {@code <think>…</think>\n{…json…}}，
 * 若不剥离就会解析失败并静默降级。因此本地路由与公网路由统一走这里。
 */
final class LlmJsonContent {
    private LlmJsonContent() {
    }

    /**
     * 剥离思考块并去掉 Markdown 代码围栏。
     *
     * <p>先去掉成对的 {@code <think>…</think>}，再丢弃未闭合的思考块（回答被截断时思考块没有结束标记，
     * 其中不可能包含完整 JSON），最后清掉残留的孤立标记。
     */
    static String cleaned(String content) {
        return fencesStripped(reasoningStripped(content));
    }

    static String reasoningStripped(String content) {
        return content.replaceAll("(?is)<think\\b[^>]*>.*?</think\\b[^>]*>", " ")
                .replaceAll("(?is)<think\\b[^>]*>.*$", " ")
                .replaceAll("(?i)</?think\\b[^>]*>", " ")
                .strip();
    }

    static String fencesStripped(String content) {
        return content.replaceFirst("(?is)^```(?:json)?\\s*", "")
                .replaceFirst("(?s)\\s*```\\s*$", "")
                .strip();
    }
}

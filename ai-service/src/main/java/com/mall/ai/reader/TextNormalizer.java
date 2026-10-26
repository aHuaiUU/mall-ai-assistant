package com.mall.ai.reader;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本归一化：把解析器吐出的原始文本变成适合切分的干净文本。
 *
 * 处理三类实测问题：
 *
 *   - PDF 每行后插一个空行（政策那份 1045 行有 505 个空行），按空行切会碎成一堆单行块；
 *   - PDF 把词断在两行（「…权益保」+ 空行 +「护法》」），中文直接拼接可修复；
 *   - 行尾 {@code \r}：{@code \r\n} 按 {@code \n} 切行后残留 {@code \r}，
 *       会让 {@code startsWith("## ")} 之类判断失败。
 *
 * 同时要保护 Markdown 结构：以 {@code #} / {@code -} / {@code >} / 数字序号开头的结构行
 * 不与上一行合并，否则商品目录会失去边界。
 */
@Slf4j
@Component
public class TextNormalizer {

    /** 以这些开头的是结构行，不能和上一行合并 */
    private static final String[] STRUCTURAL_PREFIXES = {
            "#", "- ", "* ", "+ ", ">", "|", "```", "    ", "\t"
    };

    /** 行尾是这些标点时，说明上一行语义已结束，不与下一行合并 */
    private static final String TERMINAL_PUNCTUATION = "。！？；：」』）】”’!?;:)]}>";

    /**
     * 统一换行符、合并被断开的中文行与连续空行，同时保留 Markdown 结构行。
     *
     * @param raw 解析器输出的原始文本
     * @return 归一化后的文本；入参为空时返回空串
     */
    public String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }

        // 1) 统一换行符，彻底消灭 \r
        String unified = raw.replace("\r\n", "\n").replace('\r', '\n');

        // 2) 逐行去尾空白，合并断行
        // 2.1  -1：尽可能全部切割，并且 保留末尾的空字符串
        String[] lines = unified.split("\n", -1);
        // 储存文本内容
        List<String> merged = new ArrayList<>(lines.length);

        for (String line : lines) {
            // 2.2 去除空字符串
            String trimmed = stripTrailing(line);
            if (trimmed.isEmpty()) {
                merged.add("");
                continue;
            }
            // 2.3 判断当前文本内容是否应该合并到上一轮文本
            if (shouldMergeWith(merged, trimmed)) {
                // 2.3.1 合并到「最近的非空行」尾部，并跨过中间的空行
                mergeIntoPrevious(merged, trimmed);
            } else {
                // 2.3.1 无需合并直接添加
                merged.add(trimmed.strip());
            }
        }

        // 3) 合并连续空行：只保留一个（Markdown 的段落边界靠空行，但不能超过一个）
        StringBuilder sb = new StringBuilder(raw.length());
        boolean lastBlank = true;   // 开头不留空行
        for (String line : merged) {
            if (line.isEmpty()) {
                if (!lastBlank) {
                    sb.append('\n');
                }
                lastBlank = true;
            } else {
                sb.append(line).append('\n');
                lastBlank = false;
            }
        }
        // 返回清洗后内容
        String result = sb.toString().strip();
        log.debug("归一化：{} 字符 -> {} 字符", raw.length(), result.length());
        return result;
    }

    /**
     * 判断当前行是否应该并到上一行。
     *
     * 合并条件：上一行不是空行、且不是结构行、且不以句末标点结尾；
     * 当前行也不是结构行。四个条件同时满足才合并。
     */
    private boolean shouldMergeWith(List<String> merged, String currentLine) {
        if (merged.isEmpty()) {
            return false;
        }
        // 往回找最近的非空行。PDF 抽取的结果是「一行正文 + 一个空行」交替，
        // 如果只看紧挨着的上一行，那永远是空行，merge 永远不成立，
        // 整份 PDF 就会碎成一堆 40 字左右的段落。
        int lastIdx = merged.size() - 1;
        while (lastIdx >= 0 && merged.get(lastIdx).isEmpty()) {
            lastIdx--;
        }
        if (lastIdx < 0) {
            return false;
        }
        String previous = merged.get(lastIdx);
        // 若以 "#", "- ", "* ", "+ ", ">", "|", "```", "    ", "\t" 和`1、` `2.` `3）` `(4)` `（5）` 开头false掉
        if (startsWithStructural(previous) || startsWithStructural(currentLine)) {
            return false;
        }
        // 是否以。！？；：」』）】”’!?;:)]}>结尾false
        if (endsWithTerminal(previous)) {
            return false;
        }
        return true;
    }

    /**
     * 合并时要跨过中间的空行：把 currentLine 并到最近的非空行上，并丢掉中间的空串。
     */
    private void mergeIntoPrevious(List<String> merged, String currentLine) {
        int lastIdx = merged.size() - 1;
        while (lastIdx >= 0 && merged.get(lastIdx).isEmpty()) {
            lastIdx--;
        }
        if (lastIdx < 0) {
            merged.add(currentLine.strip());
            return;
        }
        merged.subList(lastIdx + 1, merged.size()).clear();
        merged.set(lastIdx, joinLines(merged.get(lastIdx), currentLine.strip()));
    }
    //判断是否"#", "- ", "* ", "+ ", ">", "|", "```", "    ", "\t" 和`1、` `2.` `3）` `(4)` `（5）` 开头
    private boolean startsWithStructural(String line) {
        for (String prefix : STRUCTURAL_PREFIXES) {
            if (line.startsWith(prefix)) {
                return true;
            }
        }
        // 数字序号：1、  2.  (1)
        return line.matches("^\\d+[、.．)）].*") || line.matches("^[（(]\\d+[)）].*");
    }

    // 是否以。！？；：」』）】”’!?;:)]}>结尾
    private boolean endsWithTerminal(String line) {
        if (line.isEmpty()) {
            return false;
        }
        char last = line.charAt(line.length() - 1);
        return TERMINAL_PUNCTUATION.indexOf(last) >= 0;
    }

    /**
     * 拼接两行：中文直接拼，英文/数字之间补一个空格。
     * 补空格是为了不让 {@code Hello} + {@code World} 变成 {@code HelloWorld}。
     */
    private String joinLines(String previous, String current) {
        if (previous.isEmpty()) {
            return current;
        }
        char a = previous.charAt(previous.length() - 1);
        char b = current.charAt(0);
        if (isAsciiWordChar(a) && isAsciiWordChar(b)) {
            return previous + ' ' + current;
        }
        return previous + current;
    }
    // 判断 两段拼接中间是否有空格
    private boolean isAsciiWordChar(char c) {
        return c < 128 && (Character.isLetterOrDigit(c) || c == '-' || c == '_');
    }

    //去除空白字符串
    private String stripTrailing(String line) {
        int end = line.length();
        while (end > 0 && (line.charAt(end - 1) == ' ' || line.charAt(end - 1) == '\t')) {
            end--;
        }
        return line.substring(0, end);
    }
}
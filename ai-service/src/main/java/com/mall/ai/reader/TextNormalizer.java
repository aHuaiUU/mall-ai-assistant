package com.mall.ai.reader;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本归一化：把「解析器吐出来的原始文本」变成「适合切分的干净文本」。
 *
 * <p>为什么单独一层：切分策略和文本清洗是两个问题。混在一起，切分出问题时
 * 分不清是清洗错了还是切法错了，单测也没法拆开写。
 *
 * <p>这里修的是三个实测出来的具体问题（见 docs/pitfalls-and-interview.md）：
 * <ol>
 *   <li><b>PDF 每行后面插一个空行</b>：云米那份 11 页 PDF，1045 行里有 505 个空行（48%）。
 *       不处理就按空行切，会切出 505 个只有一行字的碎片。</li>
 *   <li><b>PDF 把一个词断在两行</b>：实测抽到「《中华人民共和国消费者权益保」+ 空行 +
 *       「护法》」，「保护法」这个词在向量库里就消失了，检索不到。
 *       中文不需要空格分词，直接拼接是安全的。</li>
 *   <li><b>行尾 CRLF</b>：md 文件是 {@code \r\n}，按 {@code \n} 切行后每行尾部残留
 *       {@code \r}，导致 {@code line.startsWith("## ")} 之类判断全部失败。</li>
 * </ol>
 *
 * <p>反过来要保护的是 <b>Markdown 结构</b>：以 {@code #} / {@code -} / {@code >} / 数字序号
 * 开头的行是结构行，绝不能和上一行合并，否则商品目录就变成一坨没有边界的文字。
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

    public String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }

        // 1) 统一换行符，彻底消灭 \r
        String unified = raw.replace("\r\n", "\n").replace('\r', '\n');

        // 2) 逐行去尾空白，合并断行
        String[] lines = unified.split("\n", -1);
        List<String> merged = new ArrayList<>(lines.length);
        for (String line : lines) {
            String trimmed = stripTrailing(line);
            if (trimmed.isEmpty()) {
                merged.add("");
                continue;
            }
            if (shouldMergeWith(merged, trimmed)) {
                int last = merged.size() - 1;
                merged.set(last, joinLines(merged.get(last), trimmed.strip()));
            } else {
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

        String result = sb.toString().strip();
        log.debug("归一化：{} 字符 -> {} 字符", raw.length(), result.length());
        return result;
    }

    /**
     * 判断当前行是否应该并到上一行。
     *
     * <p>合并条件：上一行不是空行、且不是结构行、且不以句末标点结尾；
     * 当前行也不是结构行。四个条件同时满足才合并。
     */
    private boolean shouldMergeWith(List<String> merged, String currentLine) {
        if (merged.isEmpty()) {
            return false;
        }
        String previous = merged.get(merged.size() - 1);
        if (previous.isEmpty()) {
            return false;
        }
        if (startsWithStructural(previous) || startsWithStructural(currentLine)) {
            return false;
        }
        if (endsWithTerminal(previous)) {
            return false;
        }
        return true;
    }

    private boolean startsWithStructural(String line) {
        for (String prefix : STRUCTURAL_PREFIXES) {
            if (line.startsWith(prefix)) {
                return true;
            }
        }
        // 数字序号：1、  2.  (1)
        return line.matches("^\\d+[、.．)）].*") || line.matches("^[（(]\\d+[)）].*");
    }

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

    private boolean isAsciiWordChar(char c) {
        return c < 128 && (Character.isLetterOrDigit(c) || c == '-' || c == '_');
    }

    private String stripTrailing(String line) {
        int end = line.length();
        while (end > 0 && (line.charAt(end - 1) == ' ' || line.charAt(end - 1) == '\t')) {
            end--;
        }
        return line.substring(0, end);
    }
}

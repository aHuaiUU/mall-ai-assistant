package com.mall.ai.reader;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
public class CustomTextSplitter implements TextSplitter {

    @Override
    public List<String> split(String normalizedText, SplitContext ctx) {

        //① 按 "## " 把 text 切成若干段        // 有结构的文档，每段是一款商品
        //② 如果 ctx.hasHeadings() == false     // 售后政策那种，按空行/段落切

        String[] content;
        if (ctx.hasHeadings()) {
            content = normalizedText.split("(?=## )");
        }else {
            content = normalizedText.split("\n");
        }

        //③ 遍历段：能塞进 chunkSize 就塞进当前块，塞不下就把当前块收掉、新开一块
        List<String> chunks = new ArrayList<>();
        int cursor = 0;
        while (cursor < content.length) {
            int before = cursor;
            String seg = content[cursor];

            // 若单个段就超过 chunkSize 的，硬切成几块
            if (seg.length() > ctx.chunkSize() ) {
                // 计算需要切成几块
                int splitCount = (int) Math.ceil((double) seg.length() / ctx.chunkSize());
                for (int j = 0; j < splitCount; j++) {
                    // 计算当前块的结束位置
                    int end = Math.min(seg.length(), (j + 1) * ctx.chunkSize());
                    String substring = seg.substring(j * ctx.chunkSize(), end);
                    chunks.add(substring);
                }
                cursor++;
                continue;
            }

            StringBuilder current = new StringBuilder();
            // 若单个段不超过 chunkSize，直接塞进当前块
            while (cursor < content.length && current.length() + content[cursor].length() <= ctx.chunkSize()) {
                current.append(content[cursor]);
                cursor++;
            }
            // 防止特殊输入死循环，保证 cursor 会前进
            if (cursor == before) {
                throw new IllegalStateException(
                        "切分器没有前进：cursor=" + cursor + "，段长=" + content[cursor].length()
                                + "，chunkSize=" + ctx.chunkSize());
            }
            if (!current.isEmpty()) chunks.add(current.toString());

        }
        log.info("chunks.size() = " + chunks.size());

        return chunks;
    }
}
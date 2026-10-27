package com.mall.ai.dto;

import lombok.Data;

import java.util.List;

/** 一次单轮问答的结果。 */
@Data
public class AskResultVO {

    private String question;

    private String answer;

    /** 是否使用了检索片段。false 表示检索为空或跳过了检索，回答应当明确说没有资料 */
    private boolean usedContext;

    /** 命中条数 */
    private int hitCount;

    /** 拼进提示词的上下文字符数，用于观察 token 预算 */
    private int contextChars;

    private long costMs;

    /** 命中的片段摘要，方便核对回答是否有依据 */
    private List<KbHitVO> hits;
}

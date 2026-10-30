package com.mall.ai.controller;

import com.mall.ai.common.BizException;
import com.mall.ai.dto.ChatRequestVO;
import com.mall.ai.service.ChatStreamService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatStreamService chatStreamService;


    @PostMapping("/stream")
    public SseEmitter stream(@RequestBody ChatRequestVO request){
        if(request == null || request.getMessage() == null || request.getMessage().isBlank()){
            throw new BizException(400,"问题不能回答");
        }
        return chatStreamService.stream(request);
    }
}

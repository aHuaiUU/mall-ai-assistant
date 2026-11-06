package com.mall.ai.tool;

import com.mall.ai.client.TradeClient;
import com.mall.ai.dto.OrderVO;
import com.mall.ai.service.ToolCallLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 订单查询工具：把 hmall trade-service 的只读订单接口包装成模型可调用的工具。
 *
 * 三个刻意的设计：
 *
 * 订单号可以不传：用户常说「我昨天的订单到哪了」却不给单号。若把参数声明为必填，
 * 模型有概率编一个假单号去查——那比查不到更糟。所以声明为可选，缺单号时返回引导话术。
 * 不回显完整订单号：订单号是敏感标识，工具只回 masked 尾号，模型没机会泄露全文。
 * 状态码翻译成人话：模型拿到 "3" 不会知道是「已发货待确认」，拿到「已发货」才能组织语言。
 *
 *
 * 安全注意：hmall 的 {@code GET /orders/{id}} 不校验订单归属（服务间内部接口的默认假设）。
 * 现在没有登录体系，接入后必须在 {@link #queryOrder} 里补一句
 * {@code if (!order.getUserId().equals(currentUserId)) return "没有查到该订单";}——
 * 否则等于给模型开了一个任意订单查询接口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTool {

    /** 订单状态码 → 人话（定义见 OrderVO 注释） */
    private static final String[] STATUS_TEXT = {
            "未知", "未付款", "已付款未发货", "已发货待确认", "交易成功", "交易取消", "已评价"
    };

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final TradeClient tradeClient;
    private final ToolCallLogService toolCallLogService;

    @Tool(name = "queryOrder",
            description = "按订单号查询订单状态（是否付款、是否发货、金额）。" +
                    "当用户询问订单进度、物流、退款状态时调用。" +
                    "用户没有提供订单号时不要编造，直接向用户索要订单号。")
    public String queryOrder(
            @ToolParam(description = "订单号，纯数字。用户明确给出订单号时才传，没给就不要传",
                    required = false)
            Long orderId,
            ToolContext toolContext) {

        Object sid = toolContext == null ? null : toolContext.getContext().get("sessionId");
        Object tid = toolContext == null ? null : toolContext.getContext().get("traceId");
        String sessionId = sid == null ? null : sid.toString();
        String traceId = tid == null ? null : tid.toString();

        long start = System.currentTimeMillis();
        String argsJson = orderId == null ? "{}" : "{\"orderId\":" + orderId + "}";
        log.info("工具调用 queryOrder args: {}", argsJson);

        try {
            // 缺订单号：返回引导，而不是抛异常或让模型猜
            if (orderId == null) {
                long cost = System.currentTimeMillis() - start;
                String hint = "查询订单需要订单号。请告诉我订单号（在「我的订单」里可以看到），我再帮你查。";
                toolCallLogService.record("queryOrder", argsJson, hint, null, cost, sessionId, traceId);
                return hint;
            }
            if (orderId <= 0) {
                long cost = System.currentTimeMillis() - start;
                String hint = "订单号格式不正确，订单号是一串数字。";
                toolCallLogService.record("queryOrder", argsJson, hint, null, cost, sessionId, traceId);
                return hint;
            }

            OrderVO order = tradeClient.detail(orderId);
            long cost = System.currentTimeMillis() - start;

            if (order == null) {
                String empty = "没有查到订单号 " + maskOrderId(orderId) + " 对应的订单，请确认订单号是否正确。";
                toolCallLogService.record("queryOrder", argsJson, empty, null, cost, sessionId, traceId);
                return empty;
            }

            String text = render(order);
            toolCallLogService.record("queryOrder", argsJson, text, null, cost, sessionId, traceId);
            return text;

        } catch (Exception e) {
            // 任何异常都转成人话：让模型据此回答「暂时查不到」，而不是把 500 甩给用户
            long cost = System.currentTimeMillis() - start;
            log.error("工具调用 queryOrder args: {}, error: {}", argsJson, e.getMessage());
            toolCallLogService.record("queryOrder", argsJson, null, e.getMessage(), cost, sessionId, traceId);
            return "订单服务暂时不可用，请稍后重试。";
        }
    }

    /** 组织成模型能直接读的文本；只给关键字段，不把整张表塞进上下文 */
    private String render(OrderVO order) {
        StringBuilder sb = new StringBuilder();
        sb.append("订单 ").append(maskOrderId(order.getId()))
                .append(" | 状态：").append(statusText(order.getStatus()))
                .append(" | 金额：").append(yuan(order.getTotalFee())).append(" 元");

        appendTime(sb, " | 下单：", order.getCreateTime());
        appendTime(sb, " | 付款：", order.getPayTime());
        appendTime(sb, " | 发货：", order.getConsignTime());
        appendTime(sb, " | 完成：", order.getEndTime());
        appendTime(sb, " | 关闭：", order.getCloseTime());

        sb.append("\n以上为实时查询结果。");
        return sb.toString();
    }

    /**
     * 订单号脱敏：只留尾 4 位。
     * 目的是「让模型能确认是哪一个订单」而不是「让模型有机会泄露完整单号」。
     */
    private String maskOrderId(Long id) {
        if (id == null) {
            return "未知";
        }
        String s = String.valueOf(id);
        return s.length() <= 4 ? "****" : "****" + s.substring(s.length() - 4);
    }

    private String statusText(Integer status) {
        if (status == null || status < 1 || status >= STATUS_TEXT.length) {
            return "未知";
        }
        return STATUS_TEXT[status];
    }

    /** hmall 金额单位是分 */
    private String yuan(Integer fen) {
        return fen == null ? "-" : String.format("%.2f", fen / 100.0);
    }

    private void appendTime(StringBuilder sb, String label, LocalDateTime time) {
        if (time != null) {
            sb.append(label).append(time.format(TIME_FMT));
        }
    }
}
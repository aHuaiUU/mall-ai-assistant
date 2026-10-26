package com.mall.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单（镜像 hmall 的 {@code com.hmall.trade.domain.vo.OrderVO}）。
 *
 * totalFee 单位也是「分」。
 *
 * 安全提醒：hmall 的 {@code GET /orders/{id}} 是内部接口，不校验订单归属
 * —— 谁拿到 orderId 都能查。所以 AI 层必须在工具里做两件事：
 * 一是只允许查当前登录用户自己的订单（拿 userId 过滤），
 * 二是订单号属于敏感数据，回答里不要原样回显完整 ID。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderVO {

    private Long id;
    /** 总金额，单位：分 */
    private Integer totalFee;
    /** 支付类型：1 支付宝 / 2 微信 / 3 扣减余额 */
    private Integer paymentType;
    private Long userId;
    /** 1 未付款 / 2 已付款未发货 / 3 已发货未确认 / 4 确认收货 / 5 交易取消 / 6 已评价 */
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime payTime;
    private LocalDateTime consignTime;
    private LocalDateTime endTime;
    private LocalDateTime closeTime;
    private LocalDateTime commentTime;
}
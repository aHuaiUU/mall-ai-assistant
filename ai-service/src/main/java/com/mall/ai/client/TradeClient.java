package com.mall.ai.client;

import com.mall.ai.dto.OrderVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 调 hmall trade-service 的只读接口。
 *
 * 契约来源：{@code trade-service/.../OrderController#queryOrderById}，返回裸 {@code OrderVO}，
 * 不包 {@code R}。
 *
 * 端口按 application.yaml 的 8085（实测 item-service 也是 application.yaml 覆盖了
 * bootstrap.yaml 的 8082，起在 8081）。启动后确认日志里的
 * {@code Tomcat started on port(s)} 再配地址。
 */
@FeignClient(name = "hmall-trade-service", url = "${hmall.trade-service.url}")
public interface TradeClient {

    @GetMapping("/orders/{id}")
    OrderVO detail(@PathVariable("id") Long orderId);
}
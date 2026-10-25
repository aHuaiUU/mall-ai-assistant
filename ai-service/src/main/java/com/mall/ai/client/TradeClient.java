package com.mall.ai.client;

import com.mall.ai.dto.OrderVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 调 hmall trade-service 的<b>只读</b>接口。
 *
 * <p>契约来源：{@code trade-service/.../OrderController#queryOrderById}，返回裸 {@code OrderVO}。
 *
 * <p><b>端口坑</b>：trade-service 的 {@code bootstrap.yaml} 里也写着 {@code port: 8082}，
 * 和 item-service 撞端口，两个服务同时起必有一个失败。起之前先把
 * trade-service 的 bootstrap.yaml 改成 8085，否则这里配什么地址都连不上。
 */
@FeignClient(name = "hmall-trade-service", url = "${hmall.trade-service.url}")
public interface TradeClient {

    @GetMapping("/orders/{id}")
    OrderVO detail(@PathVariable("id") Long orderId);
}

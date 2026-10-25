package com.mall.ai.controller;

import com.mall.ai.client.ItemClient;
import com.mall.ai.dto.ItemDTO;
import com.mall.ai.dto.PageDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 【临时验证入口，第 4 步工具化之后删掉】
 *
 * <p>只为第 1 步的完成标准服务：「能通过 ItemClient 查到一条真实商品」。
 * 打开 http://localhost:9090/dev/probe/search?key=RIMOWA 能出商品，就说明
 * Feign 地址、超时、JSON 反序列化、price 单位换算是通的。
 */
@Slf4j
@RestController
@RequestMapping("/dev/probe")
@RequiredArgsConstructor
public class DevProbeController {

    private final ItemClient itemClient;

    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam(value = "key", required = false) String key,
                                      @RequestParam(value = "maxPrice", required = false) Integer maxPrice) {
        long start = System.currentTimeMillis();
        PageDTO<ItemDTO> page = itemClient.search(key, null, null, null, maxPrice, 1, 5);
        long cost = System.currentTimeMillis() - start;

        List<String> brief = page.getList().stream()
                .map(i -> i.getName() + "｜" + i.priceYuan() + " 元｜库存 " + i.getStock())
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("costMs", cost);
        result.put("total", page.getTotal());
        result.put("pages", page.getPages());
        result.put("returned", page.getList().size());
        result.put("items", brief);
        log.info("probe ok: total={} returned={} cost={}ms", page.getTotal(), page.getList().size(), cost);
        return result;
    }

    @GetMapping("/item/{id}")
    public Map<String, Object> item(@PathVariable("id") Long id) {
        ItemDTO item = itemClient.detail(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", item != null);
        if (item != null) {
            result.put("id", item.getId());
            result.put("name", item.getName());
            result.put("priceYuan", item.priceYuan());
            result.put("stock", item.getStock());
            result.put("status", item.getStatus());
        }
        return result;
    }
}

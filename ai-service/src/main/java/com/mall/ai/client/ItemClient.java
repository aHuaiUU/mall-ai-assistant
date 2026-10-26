package com.mall.ai.client;

import com.mall.ai.dto.ItemDTO;
import com.mall.ai.dto.PageDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 调 hmall item-service 的只读接口。
 *
 * 三个与常见写法不同的点：
 *
 *   - 搜索路径是 {@code /search/list}，不是 {@code /items/search}；参数是 {@code key} 不是 {@code keyword}。
 *   - 响应不包 {@code R}，直接返回 {@code PageDTO<ItemDTO>}。套一层会解析出 null 且不报错。
 *   - 参数逐个声明 {@code @RequestParam}，不用 {@code @SpringQueryMap}：后者对 null 字段的
 *       处理依赖 feign 版本，逐个声明最不容易出意外。
 *
 * 配置了 {@code url} 之后 {@code name} 只是 bean 名，不会去注册中心找实例，因此不依赖 Nacos。
 */
@FeignClient(name = "hmall-item-service", url = "${hmall.item-service.url}")
public interface ItemClient {

    /**
     * 商品搜索。hmall 侧只返回 {@code status=1}（在售）的商品。
     *
     * {@code pageSize} 是 hmall 的分页大小，AI 层调用时统一按工具参数上限 20 收口。
     *
     * 价格必须成对传：hmall 的 {@code SearchController} 写的是
     * {@code .between(query.getMaxPrice() != null, Item::getPrice, minPrice, maxPrice)}，
     * 只传 {@code maxPrice} 会生成 {@code price BETWEEN NULL AND x}，SQL 恒为 NULL，返回 0 条。
     * 实测：{@code key=跑步鞋} 1178 条 → 加 {@code maxPrice=50000} 变 0 条 →
     * 补上 {@code minPrice=0} 回到 596 条。所以只想限上限时也要传 {@code minPrice=0}。
     *
     * 另注意价格单位是分：工具参数收「元」，进来先乘以 100。
     */
    @GetMapping("/search/list")
    PageDTO<ItemDTO> search(@RequestParam(value = "key", required = false) String key,
                            @RequestParam(value = "category", required = false) String category,
                            @RequestParam(value = "brand", required = false) String brand,
                            @RequestParam(value = "minPrice", required = false) Integer minPrice,
                            @RequestParam(value = "maxPrice", required = false) Integer maxPrice,
                            @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
                            @RequestParam(value = "pageSize", defaultValue = "10") Integer pageSize);

    /** 商品详情，含库存。查库存就走这个接口，不需要在 hmall 新增接口。 */
    @GetMapping("/items/{id}")
    ItemDTO detail(@PathVariable("id") Long id);
}
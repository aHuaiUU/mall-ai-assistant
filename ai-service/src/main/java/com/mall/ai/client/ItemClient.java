package com.mall.ai.client;

import com.mall.ai.dto.ItemDTO;
import com.mall.ai.dto.PageDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 调 hmall item-service 的<b>只读</b>接口。
 *
 * <p>契约来源：{@code F:\\File\\JavaWord\\hmall\\item-service\\...\\SearchController}、
 * {@code ItemController}，逐行核对过，不是猜的。
 *
 * <p>三个容易踩的点：
 * <ol>
 *   <li><b>路径是 {@code /search/list}，不是 {@code /items/search}</b>。架构文档 4.3 那段契约是错的。</li>
 *   <li><b>不返回 {@code R} 包装</b>，直接返回 {@code PageDTO<ItemDTO>}。</li>
 *   <li><b>不用 {@code @SpringQueryMap}，改成逐个 {@code @RequestParam}</b>：Feign 对 GET 上的 POJO
 *       参数默认当请求体处理，hmall 收不到；{@code @SpringQueryMap} 能解决，
 *       但它对 null 字段的处理依赖 feign 版本，逐个声明参数最不容易出意外。</li>
 * </ol>
 *
 * <p>{@code url} 写死指向配置项，<b>因此不需要 Nacos</b>。
 * {@code name} 只是 bean 名，配上 {@code url} 后不会去注册中心找实例。
 */
@FeignClient(name = "hmall-item-service", url = "${hmall.item-service.url}")
public interface ItemClient {

    /**
     * 商品搜索。hmall 侧只返回 {@code status=1}（在售）的商品。
     *
     * <p>{@code pageSize} 是 hmall 的分页大小，AI 层调用时统一按工具参数上限 20 收口。
     *
     * <p><b>价格必须成对传</b>：hmall 的 {@code SearchController} 写的是
     * {@code .between(query.getMaxPrice() != null, Item::getPrice, minPrice, maxPrice)}，
     * 只传 {@code maxPrice} 会生成 {@code price BETWEEN NULL AND x}，SQL 恒为 NULL，返回 0 条。
     * 实测：{@code key=跑步鞋} 1178 条 → 加 {@code maxPrice=50000} 变 0 条 →
     * 补上 {@code minPrice=0} 回到 596 条。所以<b>只想限上限时也要传 {@code minPrice=0}</b>。
     *
     * <p>另注意价格单位是<b>分</b>：工具参数收「元」，进来先乘以 100。
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

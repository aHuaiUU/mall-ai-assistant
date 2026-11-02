package com.mall.ai.tool;

import com.mall.ai.client.ItemClient;
import com.mall.ai.dto.ItemDTO;
import com.mall.ai.dto.PageDTO;
import com.mall.ai.service.ToolCallLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * ProductTool 单元测试
 *
 * 重点验证三件事：
 * 元 → 分的换算是否正确
 * 有价格上限时，minPrice 是否被强制补成 0（hmall 只传上限会返回 0 条）
 * 异常和空结果是否被转成人话，而不是往外抛
 */
class ProductToolTest {

    private final ToolCallLogService logService = mock(ToolCallLogService.class);
    private final String sessionId = "10896";
    @Test
    @DisplayName("有价格上限时，必须同时传 minPrice=0 和 maxPrice=元转分")
    void passesBothPriceBounds() {
        FakeItemClient fake = new FakeItemClient(pageOf(item(1L, "跑步鞋A", 28900, 15)));
        ProductTool tool = new ProductTool(fake, logService);

        String text = tool.searchProduct("跑步鞋", 500, new ToolContext(Map.of("sessionId", sessionId)));

        assertEquals(0, fake.capturedMinPrice, "有上限时必须补 minPrice=0");
        assertEquals(50_000, fake.capturedMaxPrice, "500 元应换算成 50000 分");
        assertEquals("跑步鞋", fake.capturedKeyword);
        // 返回文本里价格应该是元，不是分
        assertTrue(text.contains("289.00"), "返回文本里的价格应为 289.00 元，实际：" + text);
    }

    @Test
    @DisplayName("没有价格上限时，两个价格参数都传 null")
    void passesNoPriceBounds() {
        FakeItemClient fake = new FakeItemClient(pageOf(item(2L, "拉杆箱", 129900, 3)));
        ProductTool tool = new ProductTool(fake, logService);

        tool.searchProduct("拉杆箱", null, new ToolContext(Map.of("sessionId", sessionId)));

        assertNull(fake.capturedMinPrice);
        assertNull(fake.capturedMaxPrice);
    }

    @Test
    @DisplayName("空结果返回提示文案，不抛异常")
    void emptyResultReturnsHint() {
        FakeItemClient fake = new FakeItemClient(pageOf());
        ProductTool tool = new ProductTool(fake, logService);

        String text = tool.searchProduct("不存在的东西", null,  new ToolContext(Map.of("sessionId", sessionId)));

        assertTrue(text.contains("没有找到"), "应返回「没有找到」的提示，实际：" + text);
    }

    @Test
    @DisplayName("下游异常被转成人话，不往外抛")
    void downstreamErrorIsCaught() {
        FakeItemClient fake = new FakeItemClient(null);
        fake.shouldThrow = true;
        ProductTool tool = new ProductTool(fake, logService);

        String text = tool.searchProduct("跑步鞋", 500, new ToolContext(Map.of("sessionId", sessionId)));

        assertTrue(text.contains("暂时不可用"), "应返回降级文案，实际：" + text);
    }

    @Test
    @DisplayName("空关键词不发下游请求，直接返回提示")
    void blankKeywordSkipsDownstream() {
        FakeItemClient fake = new FakeItemClient(pageOf(item(3L, "x", 100, 1)));
        ProductTool tool = new ProductTool(fake, logService);

        String text = tool.searchProduct("   ", null, new ToolContext(Map.of("sessionId", sessionId)));

        assertTrue(text.contains("关键词"));
        assertNull(fake.capturedKeyword, "空关键词不应该请求下游");
    }

    // ---------- 测试辅助：用假 ItemClient 替换 Feign ----------

    /** 手写实现 ItemClient 接口，记录收到的参数；不需要 Spring、不需要 HTTP */
    private static class FakeItemClient implements ItemClient {
        String capturedKeyword;
        Integer capturedMinPrice;
        Integer capturedMaxPrice;
        Integer capturedPageSize;
        boolean shouldThrow;

        private final PageDTO<ItemDTO> response;

        FakeItemClient(PageDTO<ItemDTO> response) {
            this.response = response;
        }

        @Override
        public PageDTO<ItemDTO> search(String key, String category, String brand,
                                       Integer minPrice, Integer maxPrice,
                                       Integer pageNo, Integer pageSize) {
            if (shouldThrow) {
                throw new RuntimeException("connection refused (模拟 item-service 挂了)");
            }
            this.capturedKeyword = key;
            this.capturedMinPrice = minPrice;
            this.capturedMaxPrice = maxPrice;
            this.capturedPageSize = pageSize;
            return response;
        }

        @Override
        public ItemDTO detail(Long id) {
            return null;
        }
    }

    private static PageDTO<ItemDTO> pageOf(ItemDTO... items) {
        PageDTO<ItemDTO> page = new PageDTO<>();
        page.setTotal((long) items.length);
        page.setPages(1L);
        page.setList(List.of(items));
        return page;
    }

    private static ItemDTO item(Long id, String name, int priceFen, int stock) {
        ItemDTO item = new ItemDTO();
        item.setId(id);
        item.setName(name);
        item.setBrand("测试品牌");
        item.setPrice(priceFen);
        item.setStock(stock);
        item.setStatus(1);
        return item;
    }
}
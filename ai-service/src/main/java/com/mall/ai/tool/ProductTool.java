package com.mall.ai.tool;

import com.mall.ai.client.ItemClient;
import com.mall.ai.dto.ItemDTO;
import com.mall.ai.dto.PageDTO;
import com.mall.ai.service.ToolCallLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 商品查询工具：把mall item-service的只读搜索包装称模型可调用的工具
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProductTool {

    /**
     * 最大价格查询范围，单位：元
     */
    private static final int MAX_PRICE_YUAN = 1_000_000;
    /**
     * 每次搜索返回的商品数量
     */
    private static final Integer RESULT_LIMIT = 5;

    private final ItemClient itemClient;
    private final ToolCallLogService toolCallLogService;


    @Tool(name = "searchProduct", description = "按关键词搜索在售商品。" +
            "当用户想要找商品、问有没有某个品类商品、或询问商品价格/库存时调用本工具。" +
            "一次最多返回5款，不要为了拿全量商品反复调用")
    public String searchProduct(
            @ToolParam(description = "商品关键词，例如：手机、笔记本、拉杆箱等。" +
                    "用户只说了品类时就用品类名，不要臆造品牌名。")
            String keyword,

            @ToolParam(description = "价格上限，单位：元" +
                    "用户提到预算、多少钱以内、不超过多少、2000以内 时必须传这个参数，" +
                    "不要省略；用户没有提到预算不传这个参数",
                    required = false)
            Integer maxPriceYuan,
            ToolContext toolContext) {

        Object sid = toolContext.getContext().get("sessionId");



        long start = System.currentTimeMillis();
        // 记录模型传入参数：出现问题时复现模型传入参数
        String argsJson = buildArgsJson(keyword, maxPriceYuan);
        log.info("工具调用 searchProduct args: {}", argsJson);

        try {
            // 参数校验 缺少商品关键字时中断该工具调用
            if (keyword == null || keyword.isBlank()) {
                String hint = "缺少商品关键词。请告诉我你想要找的商品或品类，例如：手机、笔记本、拉杆箱等";
                toolCallLogService.record("searchProduct", argsJson, hint,
                        null, System.currentTimeMillis() - start);
                return hint;
            }

            // mall价格单位是分，用户输入的元需要转换为分
            // 设置价查询范围
            Integer minPriceFen = null;
            Integer maxPriceFen = null;
            if (maxPriceYuan != null) {
                int safeYuan = Math.max(1, Math.min(maxPriceYuan, MAX_PRICE_YUAN));
                minPriceFen = 0;  // 有上限必须给下限 0
                maxPriceFen = safeYuan * 100; //设置查询上限 元 转 分
            }

            // 调用item-service搜索商品
            PageDTO<ItemDTO> page = itemClient.search(
                    keyword.strip(), null, null,
                    minPriceFen, maxPriceFen, 1, RESULT_LIMIT);

            long cost = System.currentTimeMillis() - start;
            List<ItemDTO> items =
                    (page == null || page.getList() == null || page.getList().isEmpty()) ? List.of() : page.getList();

            // 商场可能没有查询的商品，告诉模型没找到
            if (items.isEmpty()) {
                String empty = "没有找到符合要求的商品：[" + keyword.strip() + "]。" +
                        "请检查关键词是否正确，或调整价格范围";
                // 记录模型查询日志
                toolCallLogService.record("searchProduct", argsJson, empty,
                        null, cost);
                return empty;
            }

            String text = render(page.getTotal(), items);
            // 记录模型成功查询日志
            toolCallLogService.record("searchProduct", argsJson, text,
                    null, cost,sid == null ? null : sid.toString());
            return text;
        } catch (Exception e) {
            // 任何异常都转成人话返回
            long cost = System.currentTimeMillis() - start;
            log.error("工具调用 searchproduct args: {}, error: {}", argsJson, e.getMessage());
            toolCallLogService.record("searchProduct", argsJson, null, e.getMessage(), cost);
            return "商品服务暂时不可用，请稍后重试。";
        }

    }

    // 构建查询到的商品信息,返回给模型处理
    private String render(Long total, List<ItemDTO> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("命中 ").append(total == null ? items.size() : total).append("款")
                .append(", 返回前 ").append(items.size()).append(" 款：\n");
        int index = 1;
        for (ItemDTO item : items) {
            sb.append(index++).append(". ")
                    .append("【ID ").append(item.getId()).append("】")
                    .append(item.getName() == null ? "未命名商品" : item.getName())
                    .append(" | 品牌 ").append(item.getBrand() == null ? "未命名品牌" : item.getBrand())
                    .append(" | ").append(item.priceYuan()).append(" 元")
                    .append(" | 库存 ").append(item.getStock() == null ? "-" : item.getStock())
                    .append("\n");
        }
        sb.append("以上价格与库存为实时查询结果。");
        return sb.toString();
    }

    private String buildArgsJson(String keyword, Integer maxPriceYuan) {
        String safeKeyword = keyword == null ? "" : keyword.replace("\"", "'");
        if(maxPriceYuan == null) {
            return "{\"keyword\":\"" + safeKeyword + "\"}";
        }
        return "{\"keyword\":\"" + safeKeyword + "\",\"maxPriceYuan\":" + maxPriceYuan + "}";
    }
}

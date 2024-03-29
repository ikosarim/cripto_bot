package me.ikosarim.cripto_bot.tasks;

import lombok.extern.slf4j.Slf4j;
import me.ikosarim.cripto_bot.containers.CurrencyPairList;
import me.ikosarim.cripto_bot.containers.TradeObject;
import me.ikosarim.cripto_bot.json_model.OrderCancelStatus;
import me.ikosarim.cripto_bot.json_model.OrderCreateStatus;
import me.ikosarim.cripto_bot.service.SendRequestsService;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;

import static java.util.Collections.singletonList;

@Slf4j
@Component
public class ScalpingAlgorithmTask implements Runnable {

    private String pairUrl;

    public void setPairUrl(String pairUrl) {
        this.pairUrl = pairUrl;
    }

    @Autowired
    private SendRequestsService sendRequestsService;

    @Autowired
    private Map<String, TradeObject> tradeObjectMap;

    @Autowired
    private ThreadPoolTaskScheduler threadPoolTaskScheduler;
    @Autowired
    private Map<String, ScheduledFuture<ReplaceOrderInGlassTask>> scheduledFutureMap;

    private ApplicationContext ctx;

    @Autowired
    public void setApplicationContext(ApplicationContext ctx) throws BeansException {
        this.ctx = ctx;
    }

    @Override
    public void run() {
        Map<String, Map<String, Double>> actualPairTradePriceMap;
        try {
            actualPairTradePriceMap = sendRequestsService.sendGetTradesRequest(pairUrl);
        } catch (ResourceAccessException | HttpServerErrorException e) {
            log.error("Error in send get trades request");
            log.error(Arrays.toString(e.getStackTrace()));
            return;
        }
        tradeObjectMap.forEach((pairName, tradeObject) -> actualPairTradePriceMap.forEach((name, actualPriceMap) -> {
            if (name.equals(pairName)) {
                log.info("Scalping algorithm task; pair - " + pairName);
                if (actualPriceMap.get("sell") > tradeObject.getUpperBorder()
                        && actualPriceMap.get("sell") < tradeObject.getUppestBorder()) {
                    log.info("Enter in up corridor, pair - " + name);
                    cancelTrendTask(tradeObject, "Bear_");
                    workInScalpingTradeCorridor(tradeObject, actualPriceMap.get("sell"),
                            actualPriceMap.get("sell") < tradeObject.getActualTradePrice(),
                            tradeObject.isSellOrder(), "sell", true, false);
                } else if (actualPriceMap.get("buy") < tradeObject.getLowerBorder()
                        && actualPriceMap.get("buy") > tradeObject.getLowestBorder()) {
                    log.info("Enter in low corridor, pair - " + name);
                    cancelTrendTask(tradeObject, "Bull_");
                    workInScalpingTradeCorridor(tradeObject, actualPriceMap.get("buy"),
                            actualPriceMap.get("buy") > tradeObject.getActualTradePrice(),
                            tradeObject.isBuyOrder(), "buy", false, true);
                } else if (actualPriceMap.get("buy") > tradeObject.getLowerBorder()
                        && actualPriceMap.get("sell") < tradeObject.getUpperBorder()) {
                    log.info("Enter in main corridor, pair - " + name);
                    workInMainCorridor(tradeObject, (actualPriceMap.get("buy") + actualPriceMap.get("sell")) / 2);
                } else if (actualPriceMap.get("sell") > tradeObject.getUppestBorder()) {
                    log.info("Enter above up corridor, pair - " + name);
                    createOrderForTrade(tradeObject, actualPriceMap.get("sell"), "buy", "Bull_", false,
                            false);
                    createNewTradeConditions(pairName, tradeObject);
                } else if (actualPriceMap.get("buy") < tradeObject.getLowestBorder()) {
                    log.info("Enter below low corridor, pair - " + name);
                    createOrderForTrade(tradeObject, actualPriceMap.get("buy"), "sell", "Bear_", false,
                            false);
                    createNewTradeConditions(pairName, tradeObject);
                }
            }
        }));
    }

    private void workInMainCorridor(TradeObject tradeObject, Double actualPrice) {
        tradeObject.setActualTradePrice(actualPrice);
        log.info("Actual price for pair - " + tradeObject.getPairName() + ", - " + actualPrice);
        cancelAndRemoveScalpingTask(tradeObject);
    }

    private void cancelAndRemoveScalpingTask(TradeObject tradeObject) {
        ScheduledFuture<ReplaceOrderInGlassTask> taskFuture = scheduledFutureMap.get(tradeObject.getPairName());
        if (taskFuture != null) {
            log.info("Need to cancel scalping task, name - " + tradeObject.getPairName());
            tradeObject.setBuyOrder(false);
            tradeObject.setSellOrder(false);
            cancelTaskOrder(taskFuture);
            taskFuture.cancel(true);
            scheduledFutureMap.remove(tradeObject.getPairName(), taskFuture);
            log.info("Сancel scalping task, name - " + tradeObject.getPairName());
        }
    }

    private void cancelTaskOrder(ScheduledFuture<ReplaceOrderInGlassTask> taskFuture) {
        Map<String, Object> args = new HashMap<>() {{
            try {
                put("order_id", taskFuture.get().getOrderId());
            } catch (InterruptedException | ExecutionException e) {
                log.error(Arrays.toString(e.getStackTrace()));
            }
        }};
        OrderCancelStatus orderCancelStatus = sendRequestsService.sendOrderCancelRequest(args);
        if (!orderCancelStatus.isResult()) {
            log.warn("Error in cancel order in scalping algorithm task - " + orderCancelStatus.getError());
        }
    }

    private void cancelTrendTask(TradeObject tradeObject, String trendType) {
        ScheduledFuture<ReplaceOrderInGlassTask> taskFuture = scheduledFutureMap.get(trendType + tradeObject.getPairName());
        if (taskFuture != null) {
            log.info("Need to cancel trend task, name - " + trendType + tradeObject.getPairName());
            cancelTaskOrder(taskFuture);
            taskFuture.cancel(true);
            scheduledFutureMap.remove(trendType + tradeObject.getPairName(), taskFuture);
            log.info("Cancel trend task, name - " + trendType + tradeObject.getPairName());
        }
    }

    private void createNewTradeConditions(String pairName, TradeObject tradeObject) {
        log.info("Need to create new borders for pair - " + pairName);
        TradeObject newBorderTradeObject = sendRequestsService.sendInitGetTradesRequest(
                pairName,
                new CurrencyPairList(singletonList(tradeObject))
        ).get(pairName);
        newBorderTradeObject.setOrderBookDeltaPrice(tradeObject.getOrderBookDeltaPrice());
        tradeObjectMap.put(pairName, newBorderTradeObject);
        log.info("Create new borders for pair - " + pairName);
    }

    private void workInScalpingTradeCorridor(TradeObject tradeObject, Double actualPrice, boolean priceCondition,
                                             boolean alreadyExecuteTask, String tradeType, boolean isSell, boolean isBuy) {
        if (tradeObject.getActualTradePrice() == actualPrice) {
            return;
        } else if (priceCondition) {
            if (!alreadyExecuteTask) {
                createOrderForTrade(tradeObject, actualPrice, tradeType, "", isSell, isBuy);
            }
        } else {
            cancelAndRemoveScalpingTask(tradeObject);
        }
        tradeObject.setActualTradePrice(actualPrice);
        log.info("Actual price for pair - " + tradeObject.getPairName() + ", - " + actualPrice);
    }

    private Long createOrderForTrade(TradeObject tradeObject, Double actualPrice, String tradeType, String trendType,
                                     boolean isSell, boolean isBuy) {
        log.info("Need to create order for trade, name " + tradeObject.getPairName() + ", type - " + trendType + tradeType);
        Long orderId = createOrder(tradeObject, actualPrice, tradeType);
        if (orderId != null) {
            ReplaceOrderInGlassTask task = ctx.getBean(ReplaceOrderInGlassTask.class);
            task.setOrderId(orderId);
            task.setTradeObject(tradeObject);
            task.setTradeType(tradeType);
            task.setTrendType(trendType);
            tradeObject.setSellOrder(isSell);
            tradeObject.setBuyOrder(isBuy);
            scheduledFutureMap.put(
                    trendType + tradeObject.getPairName(),
                    (ScheduledFuture<ReplaceOrderInGlassTask>) threadPoolTaskScheduler.scheduleWithFixedDelay(task, 2000)
            );
            log.info("Create and run replaceOrderInGlassTask, name " + tradeObject.getPairName() + ", type - " + trendType + tradeType);
        }
        return orderId;
    }

    private Long createOrder(TradeObject tradeObject, Double actualPrice, String orderType) {
        final Double finalPriceToTrade = actualPrice;
        Map<String, Object> createOrderArguments = new HashMap<>() {{
            put("pair", tradeObject.getPairName());
            put("quantity", "buy".equals(orderType) ? tradeObject.getQuantity() + 0.1 * tradeObject.getQuantity() : tradeObject.getQuantity());
            put("price", finalPriceToTrade);
            put("type", orderType);
        }};
        OrderCreateStatus orderCreateStatus = sendRequestsService.sendOrderCreateRequest(createOrderArguments);
        if (!orderCreateStatus.isResult()) {
            log.warn("Error in create order in scalping task, name - " + tradeObject.getPairName() + ", type - " + orderType);
            log.warn("Error - " + orderCreateStatus.getError());
            return null;
        }
        if ("buy".equals(orderType)) {
            tradeObject.setOrderBookBidPrice(finalPriceToTrade);
        } else if ("sell".equals(orderType)) {
            tradeObject.setOrderBookAskPrice(finalPriceToTrade);
        }
        log.info("Order created, name - " + tradeObject.getPairName() + ", type - " + orderType);
        return orderCreateStatus.getOrderId();
    }
}
package com.laioffer.onlineorder.aftersales;


import java.util.List;


/**
 * The customer's after-sales page (GET /after-sales/cases).
 *
 * @param cases          the customer's cases, newest first, without their history
 * @param eligibleOrders the orders that can get a request now, latest completed first
 */
public record AfterSalesOverviewDto(List<AfterSalesCaseDto> cases, List<EligibleOrderDto> eligibleOrders) {
}

package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.entity.OrderEntity;
import com.laioffer.onlineorder.ids.DistributedIdGenerator;
import org.springframework.data.relational.core.mapping.event.BeforeConvertCallback;
import org.springframework.stereotype.Component;


/**
 * Gives every new order its public order number (orders.order_no, V10_1) from the Snowflake generator, at the one
 * point all order inserts share: Spring Data JDBC's save of a new {@link OrderEntity}. Checkout's insert is the only
 * one in the application, so the number is generated in the checkout transaction; if the generator cannot issue one
 * (no worker id lease, clock behind) checkout fails with 503 and nothing is written. Updates and orders that already
 * carry a number are left unchanged. orders.order_no is NOT NULL and UNIQUE, so an insert that bypasses this (raw SQL)
 * fails instead of creating an order without a number.
 */
@Component
public class OrderNumberCallback implements BeforeConvertCallback<OrderEntity> {

    private final DistributedIdGenerator idGenerator;


    public OrderNumberCallback(DistributedIdGenerator idGenerator) {
        this.idGenerator = idGenerator;
    }


    @Override
    public OrderEntity onBeforeConvert(OrderEntity order) {
        if (order.id() != null || order.orderNo() != null) {
            return order;
        }
        return order.withOrderNo(idGenerator.nextId());
    }
}

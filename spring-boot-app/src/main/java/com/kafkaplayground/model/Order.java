package com.kafkaplayground.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Order {
    private String orderId;
    private String userId;
    private String item;
    private double total;
    private String status;

    public Order() {}

    public Order(String orderId, String userId, String item, double total, String status) {
        this.orderId = orderId;
        this.userId = userId;
        this.item = item;
        this.total = total;
        this.status = status;
    }

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getItem() { return item; }
    public void setItem(String item) { this.item = item; }
    public double getTotal() { return total; }
    public void setTotal(double total) { this.total = total; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    @Override
    public String toString() {
        return "Order{orderId='" + orderId + "', userId='" + userId + "', total=" + total + ", status='" + status + "'}";
    }
}

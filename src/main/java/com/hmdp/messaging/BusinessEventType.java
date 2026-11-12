package com.hmdp.messaging;

/**
 * Message types and logical consumer groups; the names are also stable protocol values in the
 * database and in messages.
 */
public enum BusinessEventType {
  FEED("feed"),
  LIKE("relations"),
  FOLLOW("relations"),
  MEDIA_DELETE("media"),
  SHOP_INVALIDATE("cache"),
  SECKILL_PUBLISH("activities"),
  SECKILL_RETRY("activities"),
  SECKILL_FULFILL("fulfillment"),
  ORDER_EXPIRE("deadlines"),
  ORDER_CREATED("orders"),
  ORDER_PAID("orders"),
  ORDER_CLOSED("orders");

  private final String consumerGroup;

  BusinessEventType(String consumerGroup) {
    this.consumerGroup = consumerGroup;
  }

  public String getConsumerGroup() {
    return consumerGroup;
  }

  public static BusinessEventType from(String kind) {
    for (BusinessEventType type : values()) {
      if (type.name().equals(kind)) {
        return type;
      }
    }
    throw new IllegalArgumentException("Unknown event type: " + kind);
  }
}

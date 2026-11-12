package com.hmdp.enums;

/**
 * Order status codes stored in the database; free vouchers also use PAID to mean claimed, so it
 * doesn't imply money was received.
 */
public enum OrderStatus {
  UNPAID(1),
  PAID(2),
  USED(3),
  CANCELLED(4),
  REFUNDING(5),
  REFUNDED(6);

  private final int code;

  OrderStatus(int code) {
    this.code = code;
  }

  public int getCode() {
    return code;
  }

  public boolean matches(Integer value) {
    return value != null && value == code;
  }
}

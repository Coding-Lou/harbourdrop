package com.hmdp.enums;

/** Voucher type codes stored in the database. */
public enum VoucherType {
  ORDINARY(0),
  SECKILL(1);

  private final int code;

  VoucherType(int code) {
    this.code = code;
  }

  public int getCode() {
    return code;
  }

  public boolean matches(Integer value) {
    return value != null && value == code;
  }
}

package com.hmdp.enums;

/** Reliable task states; SENT only means delivery was acknowledged, DONE means consumed. */
public enum OutboxState {
  PENDING,
  SENDING,
  SENT,
  DONE,
  REVIEW;

  public boolean matches(String value) {
    return name().equals(value);
  }
}

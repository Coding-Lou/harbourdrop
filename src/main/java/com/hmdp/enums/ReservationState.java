package com.hmdp.enums;

/** Stable state values shared by durable reservations and the Redis request projection. */
public enum ReservationState {
  PROCESSING,
  SUCCESS,
  REVIEW,
  FAILED;

  public boolean matches(String value) {
    return name().equals(value);
  }
}

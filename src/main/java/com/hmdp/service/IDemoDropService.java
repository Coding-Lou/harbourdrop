package com.hmdp.service;

/** Keeps the demo catalogue alive by publishing surplus-bag drops for a few seeded shops. */
public interface IDemoDropService {

  /**
   * Publishes a new drop for every configured shop that has no drop on sale or coming up.
   *
   * @return the number of drops created
   */
  int ensureDrops();
}

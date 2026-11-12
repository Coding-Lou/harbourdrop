package com.hmdp.utils;

public interface ILock {

  /**
   * Tries to acquire the lock.
   *
   * @param timeoutSec lock hold timeout; the lock is released automatically when it expires
   * @return true if the lock was acquired; false otherwise
   */
  boolean tryLock(long timeoutSec);

  /** Releases the lock. */
  void unlock();
}

package com.hmdp.repository;

/**
 * Insert and partial-update operations that Spring Data's {@code save} does not provide.
 *
 * <p>{@code save} merges an entity whose ID is already set, which would silently turn a duplicate
 * insert into an update. {@link #insert} always issues an INSERT and flushes immediately, so a
 * unique-key violation surfaces at the call site as a {@code DataIntegrityViolationException}.
 */
public interface InsertRepository<T> {
  T insert(T entity);

  /**
   * Copies the non-null fields of {@code patch} onto the stored row with the same ID.
   *
   * @return false when no row has that ID
   */
  boolean updateById(T patch);
}

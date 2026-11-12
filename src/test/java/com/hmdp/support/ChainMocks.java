package com.hmdp.support;

import static org.mockito.Mockito.mock;

import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import org.mockito.Answers;
import org.mockito.stubbing.Answer;

/**
 * Mockito helpers for MyBatis-Plus fluent chain wrappers.
 *
 * <p>MyBatis-Plus 3.5 declares fluent methods as returning the type variable {@code Children},
 * which erases to {@code Object}; Mockito's {@code RETURNS_SELF} deliberately ignores such methods.
 * This answer returns the mock only for {@code Children}, leaving terminal calls such as {@code
 * one()} at their defaults. Temporary until the persistence layer moves to Spring Data JPA.
 */
public final class ChainMocks {
  private static final Answer<Object> SELF_FOR_CHILDREN =
      invocation -> {
        Type returned = invocation.getMethod().getGenericReturnType();
        if (returned instanceof TypeVariable<?>
            && "Children".equals(((TypeVariable<?>) returned).getName())) {
          return invocation.getMock();
        }
        return Answers.RETURNS_SELF.answer(invocation);
      };

  private ChainMocks() {}

  public static <T> T fluent(Class<T> type) {
    return mock(type, SELF_FOR_CHILDREN);
  }
}

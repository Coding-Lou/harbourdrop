package com.hmdp.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.hmdp.support.IntegrationTestBase;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Executes every native query in every repository once against real MySQL.
 *
 * <p>Hibernate does not validate native SQL at startup, so a typo in a rarely used operations query
 * would otherwise only fail in production. Writes run inside a transaction that is always rolled
 * back. Constraint violations are acceptable (the statement parsed and ran); grammar or
 * unknown-column errors are not.
 */
@SpringBootTest(properties = "citylens.reliability.scheduling-enabled=false")
class NativeQuerySmokeIntegrationTest extends IntegrationTestBase {
  @Autowired ApplicationContext context;
  @Autowired TransactionTemplate transactions;
  @Autowired JdbcTemplate jdbc;

  @Test
  void everyNativeQueryExecutes() {
    assertEquals(DATABASE, jdbc.queryForObject("SELECT DATABASE()", String.class));
    List<String> failures = new ArrayList<>();
    int executed = 0;
    for (Object bean : context.getBeansOfType(Repository.class).values()) {
      for (Class<?> type : bean.getClass().getInterfaces()) {
        if (!type.getPackageName().equals("com.hmdp.repository")) {
          continue;
        }
        for (Method method : type.getDeclaredMethods()) {
          Query query = method.getAnnotation(Query.class);
          if (query == null || !query.nativeQuery()) {
            continue;
          }
          Object[] args = sampleArguments(method);
          try {
            transactions.executeWithoutResult(
                tx -> {
                  tx.setRollbackOnly();
                  try {
                    method.invoke(bean, args);
                  } catch (InvocationTargetException e) {
                    throw e.getCause() instanceof RuntimeException runtime
                        ? runtime
                        : new IllegalStateException(e.getCause());
                  } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                  }
                });
          } catch (DataIntegrityViolationException acceptable) {
            // The statement was valid SQL; sample values merely violated a constraint.
          } catch (RuntimeException broken) {
            failures.add(type.getSimpleName() + "." + method.getName() + ": " + broken);
          }
          executed++;
        }
      }
    }
    if (!failures.isEmpty()) {
      fail(String.join("\n", failures));
    }
    assertTrue(executed > 100, "expected to execute the migrated native queries: " + executed);
  }

  private static Object[] sampleArguments(Method method) {
    Parameter[] parameters = method.getParameters();
    Object[] args = new Object[parameters.length];
    for (int i = 0; i < parameters.length; i++) {
      Class<?> type = parameters[i].getType();
      if (type == String.class) {
        args[i] = "smoke-test";
      } else if (type == Integer.class || type == int.class) {
        args[i] = 1;
      } else if (type == Long.class || type == long.class) {
        args[i] = 1L;
      } else if (type == Timestamp.class) {
        args[i] = new Timestamp(System.currentTimeMillis());
      } else if (type == LocalDateTime.class) {
        args[i] = LocalDateTime.now();
      } else if (List.class.isAssignableFrom(type)) {
        args[i] = List.of(1L);
      } else {
        throw new IllegalArgumentException("No sample value for " + type + " in " + method);
      }
    }
    return args;
  }
}

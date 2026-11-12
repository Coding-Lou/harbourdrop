package com.hmdp;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hmdp.repository.BlogRepository;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.persistence.EntityManager;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.jdbc.core.JdbcTemplate;

/** Layering rules and native-query binding checks that keep SQL inside the repository layer. */
class ArchitectureTest {
  private static final Pattern NAMED_PARAMETER = Pattern.compile("(?<![:\\w]):(\\w+)");
  private static JavaClasses production;

  @BeforeAll
  static void importProductionClasses() {
    production =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.hmdp");
  }

  @Test
  void onlyRepositoriesTalkSqlDirectly() {
    noClasses()
        .that()
        .resideOutsideOfPackage("com.hmdp.repository..")
        .should()
        .dependOnClassesThat()
        .areAssignableTo(JdbcTemplate.class)
        .orShould()
        .callMethod(EntityManager.class, "createNativeQuery", String.class)
        .check(production);
  }

  @Test
  void controllersDependOnServicesNotRepositories() {
    noClasses()
        .that()
        .resideInAPackage("com.hmdp.controller..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("com.hmdp.repository..")
        .check(production);
  }

  @Test
  void controllerFieldsAreServiceInterfaces() {
    production.stream()
        .filter(type -> type.getPackageName().equals("com.hmdp.controller"))
        .map(type -> type.reflect())
        .forEach(
            controller -> {
              for (Field field : controller.getDeclaredFields()) {
                if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                  continue;
                }
                assertTrue(
                    field.getType().isInterface()
                        && field.getType().getPackage().getName().equals("com.hmdp.service"),
                    controller.getName() + "." + field.getName());
              }
            });
  }

  @Test
  void nativeQueriesBindExactlyTheDeclaredParameters() {
    int checked = 0;
    for (var type : production) {
      if (!type.getPackageName().equals("com.hmdp.repository") || !type.isInterface()) {
        continue;
      }
      for (Method method : type.reflect().getDeclaredMethods()) {
        Query query = method.getAnnotation(Query.class);
        if (query == null) {
          continue;
        }
        String sql = query.value();
        assertFalse(sql.contains("${") || sql.contains("#{"), method.toString());
        Set<String> used = new TreeSet<>();
        Matcher matcher = NAMED_PARAMETER.matcher(sql.replaceAll("'(?:[^']|'')*'", "''"));
        while (matcher.find()) {
          used.add(matcher.group(1));
        }
        Set<String> declared = new TreeSet<>();
        for (Parameter parameter : method.getParameters()) {
          Param param = parameter.getAnnotation(Param.class);
          if (param != null) {
            declared.add(param.value());
          }
        }
        assertEquals(declared, used, method.toString());
        checked++;
      }
    }
    assertTrue(checked > 100, "expected the migrated native queries to be checked: " + checked);
  }

  @Test
  void rawTupleRowsAreNeverExposedUnderTheirPublicName() {
    // Spring Data's tuple maps are read-only; callers must get Rows.mutable(...) copies through a
    // default method, so a native query returning maps must be named *Tuples or *Row(s).
    for (var type : production) {
      if (!type.getPackageName().equals("com.hmdp.repository") || !type.isInterface()) {
        continue;
      }
      for (Method method : type.reflect().getDeclaredMethods()) {
        boolean returnsMaps =
            Map.class.isAssignableFrom(method.getReturnType())
                || method.getGenericReturnType().getTypeName().contains("java.util.Map");
        if (method.isAnnotationPresent(Query.class) && returnsMaps) {
          String name = method.getName();
          assertTrue(
              name.endsWith("Tuples") || name.endsWith("Row") || name.endsWith("Rows"),
              method.toString());
        }
      }
    }
  }

  @Test
  void emptyLikeBatchNeverReachesTheDatabase() {
    BlogRepository blogs = mock(BlogRepository.class, CALLS_REAL_METHODS);
    assertEquals(Collections.emptyList(), blogs.selectLikedBlogIds(1L, Collections.emptyList()));
    verify(blogs, never()).selectLikedBlogIdsIn(any(), any());
  }
}

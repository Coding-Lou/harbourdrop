package com.hmdp.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Id;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Transient;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ReflectionUtils;

/** Fragment implementation shared by every repository that extends {@link InsertRepository}. */
public class InsertRepositoryImpl<T> implements InsertRepository<T> {
  @PersistenceContext private EntityManager entityManager;

  @Override
  @Transactional
  public T insert(T entity) {
    entityManager.persist(entity);
    entityManager.flush();
    return entity;
  }

  @Override
  @Transactional
  public boolean updateById(T patch) {
    Object id =
        entityManager.getEntityManagerFactory().getPersistenceUnitUtil().getIdentifier(patch);
    if (id == null) {
      return false;
    }
    @SuppressWarnings("unchecked")
    T stored = (T) entityManager.find(patch.getClass(), id);
    if (stored == null) {
      return false;
    }
    ReflectionUtils.doWithFields(
        patch.getClass(),
        field -> {
          ReflectionUtils.makeAccessible(field);
          Object value = field.get(patch);
          if (value != null) {
            field.set(stored, value);
          }
        },
        InsertRepositoryImpl::isPersistentColumn);
    entityManager.flush();
    return true;
  }

  private static boolean isPersistentColumn(Field field) {
    int modifiers = field.getModifiers();
    return !Modifier.isStatic(modifiers)
        && !Modifier.isTransient(modifiers)
        && !field.isAnnotationPresent(Id.class)
        && !field.isAnnotationPresent(Transient.class);
  }
}

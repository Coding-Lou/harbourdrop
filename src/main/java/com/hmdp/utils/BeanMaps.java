package com.hmdp.utils;

import java.beans.PropertyDescriptor;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;

/** Converts a JavaBean into a mutable map of its readable properties, keeping nulls and types. */
public final class BeanMaps {
  private BeanMaps() {}

  public static Map<String, Object> toMap(Object bean) {
    BeanWrapper wrapper = new BeanWrapperImpl(bean);
    Map<String, Object> map = new LinkedHashMap<>();
    for (PropertyDescriptor property : wrapper.getPropertyDescriptors()) {
      String name = property.getName();
      if (!"class".equals(name) && wrapper.isReadableProperty(name)) {
        map.put(name, wrapper.getPropertyValue(name));
      }
    }
    return map;
  }
}

package com.myj.mmp.common.utils;

import cn.hutool.core.util.ClassUtil;
import com.myj.mmp.common.annotation.DiffField;
import com.myj.mmp.common.annotation.DiffId;
import com.myj.mmp.modules.market.dto.MarketingActivityPrizesDTO;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.*;

@Slf4j
public class ObjectDiffUtil {

    public static List<String> compare(Object oldObj, Object newObj) {
        return compare(oldObj, newObj, "");
    }

    private static List<String> compare(Object oldObj, Object newObj, String prefix) {
        List<String> changes = new ArrayList<>();

        if (oldObj == null && newObj == null) return changes;

        if (oldObj == null || newObj == null) {
            changes.add(String.format("%s 由 [%s] 变更成 [%s]",
                    prefix,
                    formatValue(oldObj, "yyyy-MM-dd HH:mm:ss"),
                    formatValue(newObj, "yyyy-MM-dd HH:mm:ss")));
            return changes;
        }

        Class<?> clazz = oldObj.getClass();
        for (Field field : clazz.getDeclaredFields()) {
            field.setAccessible(true);

            DiffField annotation = field.getAnnotation(DiffField.class);
            if (annotation == null) continue;

            String fieldNameCn = annotation.value();
            String fieldDateFormat = annotation.dateFormat(); // 从注解读取格式
            String fullFieldName = prefix.isEmpty() ? fieldNameCn : prefix + "." + fieldNameCn;

            try {
                Object oldValue = field.get(oldObj);
                Object newValue = field.get(newObj);

                // List 对比
                if (oldValue instanceof List || newValue instanceof List) {
                    changes.addAll(compareList((List<?>) oldValue, (List<?>) newValue, field, fullFieldName, fieldDateFormat));
                }
                // 简单类型
                else if (isSimpleType(field.getType())) {
                    if (!Objects.equals(oldValue, newValue)) {
                        if (!(oldValue == null && newValue == null)) {
                            changes.add(String.format("%s 由 [%s] 变更成 [%s]",
                                    fullFieldName,
                                    formatValue(oldValue, fieldDateFormat),
                                    formatValue(newValue, fieldDateFormat)));
                        }
                    }
                }
                // 嵌套对象递归
                else {
                    changes.addAll(compare(oldValue, newValue, fullFieldName));
                }

            } catch (IllegalAccessException e) {
                log.error("处理字段值异常", e);
            }
        }
        return changes;
    }

    private static List<String> compareList(List<?> oldList, List<?> newList, Field field,
                                            String fullFieldName, String dateFormat) {
        List<String> changes = new ArrayList<>();
        oldList = oldList == null ? Collections.emptyList() : oldList;
        newList = newList == null ? Collections.emptyList() : newList;

        if (oldList.isEmpty() && newList.isEmpty()) return changes;

        // List<简单类型>
        if (isSimpleList(field)) {
            if (!Objects.equals(oldList, newList)) {
                changes.add(String.format("%s 由 [%s] 变更成 [%s]",
                        fullFieldName,
                        formatValue(oldList, dateFormat),
                        formatValue(newList, dateFormat)));
            }
            return changes;
        }

        // List<对象> → 按 @DiffId 对比
        Map<Object, Object> oldMap = listToMap(oldList);
        Map<Object, Object> newMap = listToMap(newList);

        Set<Object> allKeys = new HashSet<>();
        allKeys.addAll(oldMap.keySet());
        allKeys.addAll(newMap.keySet());

        for (Object key : allKeys) {
            Object o1 = oldMap.get(key);
            Object o2 = newMap.get(key);

            if (o1 == null && o2 != null) {
                changes.add(String.format("%s[%s] 新增：[%s]",
                        fullFieldName, key, describeObject(o2, dateFormat)));
            } else if (o1 != null && o2 == null) {
                changes.add(String.format("%s[%s] 删除：[%s]",
                        fullFieldName, key, describeObject(o1, dateFormat)));
            } else {
//                changes.addAll(compare(o1, o2, fullFieldName));
                changes.addAll(compare(o1, o2, fullFieldName + "[" + key + "]"));
            }
        }
        return changes;
    }

    private static Map<Object, Object> listToMap(List<?> list) {
        Map<Object, Object> map = new HashMap<>();
        for (Object item : list) {
            if (item == null) continue;
            Field idField = getIdField(item.getClass());
            if (idField != null) {
                try {
                    idField.setAccessible(true);
                    Object idValue = idField.get(item);
                    if (idValue != null) {
                        map.put(idValue, item);
                    }
                } catch (IllegalAccessException e) {
                    log.error("获取ID字段值异常", e);
                }
            }
        }
        return map;
    }

    private static Field getIdField(Class<?> clazz) {
        for (Field field : clazz.getDeclaredFields()) {
            if (field.getAnnotation(DiffId.class) != null) {
                return field;
            }
        }
        return null;
    }

    private static boolean isSimpleType(Class<?> clazz) {
        return clazz.isPrimitive()
                || clazz.equals(String.class)
                || Number.class.isAssignableFrom(clazz)
                || Boolean.class.isAssignableFrom(clazz)
                || Date.class.isAssignableFrom(clazz)
                || java.time.temporal.Temporal.class.isAssignableFrom(clazz);
    }

    private static boolean isSimpleList(Field field) {
        try {
            if (field.getGenericType() instanceof ParameterizedType) {
                Type[] types = ((ParameterizedType) field.getGenericType()).getActualTypeArguments();
                if (types.length == 1) {
                    Class<?> genericClass = Class.forName(types[0].getTypeName());
                    return isSimpleType(genericClass);
                }
            }
        } catch (Exception e) {
            log.error("无法解析 List 泛型类型", e);
        }
        return false;
    }

    private static String formatValue(Object value, String dateFormat) {
        if (value == null) return "";
        if (value instanceof Date) {
            return new SimpleDateFormat(dateFormat).format((Date) value);
        }
        return value.toString();
    }


    /**
     * 描述对象：只输出带 @DiffField 注解的字段和值
     */
    private static String describeObject(Object obj, String dateFormat) {
        if (obj == null) return "";

        StringBuilder sb = new StringBuilder("{");
        Class<?> clazz = obj.getClass();

        for (Field f : clazz.getDeclaredFields()) {
            f.setAccessible(true);
            DiffField annotation = f.getAnnotation(DiffField.class);
            if (annotation == null) continue;

            try {
                Object value = f.get(obj);
                sb.append(annotation.value()).append("=");

                if (value instanceof List<?>) {
                    sb.append("[");
                    List<?> list = (List<?>) value;
                    for (Object item : list) {
                        sb.append(describeObject(item, dateFormat)).append(", ");
                    }
                    if (!list.isEmpty()) {
                        sb.setLength(sb.length() - 2);
                    }
                    sb.append("]");
                } else {
                    sb.append(formatValue(value, annotation.dateFormat()));
                }
                sb.append(", ");
            } catch (IllegalAccessException e) {
                log.error("处理字段值异常", e);
            }
        }

        if (sb.length() > 1) {
            sb.setLength(sb.length() - 2);
        }
        sb.append("}");
        return sb.toString();
    }
}

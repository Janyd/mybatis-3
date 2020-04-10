/**
 * Copyright 2009-2020 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ibatis.reflection;

import org.apache.ibatis.reflection.invoker.*;
import org.apache.ibatis.reflection.property.PropertyNamer;

import java.lang.reflect.*;
import java.text.MessageFormat;
import java.util.*;
import java.util.Map.Entry;

/**
 * 反射器 作用是收集Class的getter setter方法、字段、构造函数等等信息，并且缓存缓存在 @see org.apache.ibatis.reflection.DefaultReflectorFactory#reflectorMap
 * This class represents a cached set of class definition information that
 * allows for easy mapping between property names and getter/setter methods.
 *
 * @author Clinton Begin
 */
public class Reflector {

    /**
     * 反射的Class类型
     */
    private final Class<?> type;

    /**
     * 可通过getter读取的字段数组
     */
    private final String[] readablePropertyNames;

    /**
     * 可通过setter写入的字段数组
     */
    private final String[] writablePropertyNames;

    /**
     * setter方法Method并封装在Invoker内，方便于执行setter方法，key ->字段名称 value -> set的 Invoker
     */
    private final Map<String, Invoker> setMethods = new HashMap<>();

    /**
     * getter方法Method方法封装在Invoker内，方便于执行getter方法，key->字段名称 value-> get的 Invoker
     */
    private final Map<String, Invoker> getMethods = new HashMap<>();

    /**
     * setter方法，入参类型
     */
    private final Map<String, Class<?>> setTypes = new HashMap<>();

    /**
     * getter方法，出参类型
     */
    private final Map<String, Class<?>> getTypes = new HashMap<>();

    /**
     * Class 默认构造函数 ，一般为无参数构造函数
     */
    private Constructor<?> defaultConstructor;

    /**
     * 字段大小写转换，大写字段名key -> 小写字段名value
     */
    private Map<String, String> caseInsensitivePropertyMap = new HashMap<>();

    public Reflector(Class<?> clazz) {
        //反射器处理的Class
        type = clazz;

        //添加默认构造函数Method
        addDefaultConstructor(clazz);

        //查找并添加getter方法
        addGetMethods(clazz);

        //查找并添加setter方法
        addSetMethods(clazz);

        //查找Class的字段
        addFields(clazz);

        //获取可通过getter字段名
        readablePropertyNames = getMethods.keySet().toArray(new String[0]);
        //获取可通过setter的字段名
        writablePropertyNames = setMethods.keySet().toArray(new String[0]);


        for (String propName : readablePropertyNames) {
            caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
        }
        for (String propName : writablePropertyNames) {
            caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
        }
    }

    private void addDefaultConstructor(Class<?> clazz) {
        Constructor<?>[] constructors = clazz.getDeclaredConstructors();

        //获取无参的构造函数
        Arrays.stream(constructors).filter(constructor -> constructor.getParameterTypes().length == 0)
            .findAny().ifPresent(constructor -> this.defaultConstructor = constructor);
    }

    private void addGetMethods(Class<?> clazz) {
        Map<String, List<Method>> conflictingGetters = new HashMap<>();
        //获取Class的所有方法包含了父类与接口方法
        Method[] methods = getClassMethods(clazz);
        //经过过滤只保留getter方法
        Arrays.stream(methods).filter(m -> m.getParameterTypes().length == 0 && PropertyNamer.isGetter(m.getName()))
            .forEach(m -> addMethodConflict(conflictingGetters, PropertyNamer.methodToProperty(m.getName()), m));
        resolveGetterConflicts(conflictingGetters);
    }

    /**
     * 解决getter冲突方法
     *
     * @param conflictingGetters
     */
    private void resolveGetterConflicts(Map<String, List<Method>> conflictingGetters) {
        //遍历每个属性，查找其最匹配的方法。因为子类可以覆写父类的方法，所以一个属性可能拥有多个getter方法
        for (Entry<String, List<Method>> entry : conflictingGetters.entrySet()) {
            //getter方法胜出者
            Method winner = null;
            String propName = entry.getKey();
            boolean isAmbiguous = false;
            for (Method candidate : entry.getValue()) {

                if (winner == null) {
                    winner = candidate;
                    continue;
                }
                Class<?> winnerType = winner.getReturnType();
                Class<?> candidateType = candidate.getReturnType();
                //类型相同
                if (candidateType.equals(winnerType)) {
                    if (!boolean.class.equals(candidateType)) {
                        isAmbiguous = true;
                        break;
                    } else if (candidate.getName().startsWith("is")) {
                        winner = candidate;
                    }
                } else if (candidateType.isAssignableFrom(winnerType)) {
                    //判断candidateType是否是winnerType的父类
                    // OK getter type is descendant
                } else if (winnerType.isAssignableFrom(candidateType)) {
                    //如果candidateType 是candidateType的子类，需要替换为子类
                    winner = candidate;
                } else {
                    isAmbiguous = true;
                    break;
                }
            }
            addGetMethod(propName, winner, isAmbiguous);
        }
    }

    /**
     * 添加getter方法
     *
     * @param name        属性名
     * @param method      方法类
     * @param isAmbiguous 该方法是否有歧义
     */
    private void addGetMethod(String name, Method method, boolean isAmbiguous) {
        MethodInvoker invoker = isAmbiguous
            ? new AmbiguousMethodInvoker(method, MessageFormat.format(
            "Illegal overloaded getter method with ambiguous type for property ''{0}'' in class ''{1}''. This breaks the JavaBeans specification and can cause unpredictable results.",
            name, method.getDeclaringClass().getName()))
            : new MethodInvoker(method);
        getMethods.put(name, invoker);
        Type returnType = TypeParameterResolver.resolveReturnType(method, type);
        getTypes.put(name, typeToClass(returnType));
    }

    private void addSetMethods(Class<?> clazz) {
        Map<String, List<Method>> conflictingSetters = new HashMap<>();
        Method[] methods = getClassMethods(clazz);
        Arrays.stream(methods).filter(m -> m.getParameterTypes().length == 1 && PropertyNamer.isSetter(m.getName()))
            .forEach(m -> addMethodConflict(conflictingSetters, PropertyNamer.methodToProperty(m.getName()), m));
        resolveSetterConflicts(conflictingSetters);
    }

    private void addMethodConflict(Map<String, List<Method>> conflictingMethods, String name, Method method) {
        if (isValidPropertyName(name)) {
            List<Method> list = conflictingMethods.computeIfAbsent(name, k -> new ArrayList<>());
            list.add(method);
        }
    }

    private void resolveSetterConflicts(Map<String, List<Method>> conflictingSetters) {
        // 遍历每个属性，查找其最匹配的方法。因为子类可以覆写父类的方法，所以一个属性，可能对应多个 setting 方法
        for (Entry<String, List<Method>> entry : conflictingSetters.entrySet()) {
            String propName = entry.getKey();
            List<Method> setters = entry.getValue();
            Class<?> getterType = getTypes.get(propName);
            //提取getter，判断其是否有歧义的getter
            boolean isGetterAmbiguous = getMethods.get(propName) instanceof AmbiguousMethodInvoker;
            boolean isSetterAmbiguous = false;
            Method match = null;
            for (Method setter : setters) {
                if (!isGetterAmbiguous && setter.getParameterTypes()[0].equals(getterType)) {
                    // should be the best match
                    match = setter;
                    break;
                }
                if (!isSetterAmbiguous) {
                    match = pickBetterSetter(match, setter, propName);
                    isSetterAmbiguous = match == null;
                }
            }
            if (match != null) {
                addSetMethod(propName, match);
            }
        }
    }

    private Method pickBetterSetter(Method setter1, Method setter2, String property) {
        if (setter1 == null) {
            return setter2;
        }
        Class<?> paramType1 = setter1.getParameterTypes()[0];
        Class<?> paramType2 = setter2.getParameterTypes()[0];
        if (paramType1.isAssignableFrom(paramType2)) {
            return setter2;
        } else if (paramType2.isAssignableFrom(paramType1)) {
            return setter1;
        }
        //封装AmbiguousMethodInvoker用于在调用方法时，直接报错
        MethodInvoker invoker = new AmbiguousMethodInvoker(setter1,
            MessageFormat.format(
                "Ambiguous setters defined for property ''{0}'' in class ''{1}'' with types ''{2}'' and ''{3}''.",
                property, setter2.getDeclaringClass().getName(), paramType1.getName(), paramType2.getName()));
        setMethods.put(property, invoker);
        Type[] paramTypes = TypeParameterResolver.resolveParamTypes(setter1, type);
        setTypes.put(property, typeToClass(paramTypes[0]));
        return null;
    }

    private void addSetMethod(String name, Method method) {
        MethodInvoker invoker = new MethodInvoker(method);
        setMethods.put(name, invoker);
        Type[] paramTypes = TypeParameterResolver.resolveParamTypes(method, type);
        setTypes.put(name, typeToClass(paramTypes[0]));
    }

    private Class<?> typeToClass(Type src) {
        Class<?> result = null;
        if (src instanceof Class) {
            //普通类型，直接使用类
            result = (Class<?>) src;
        } else if (src instanceof ParameterizedType) {
            //泛型类型，使用泛型
            result = (Class<?>) ((ParameterizedType) src).getRawType();
        } else if (src instanceof GenericArrayType) {
            //泛型数组，获得具体类
            Type componentType = ((GenericArrayType) src).getGenericComponentType();
            if (componentType instanceof Class) {
                result = Array.newInstance((Class<?>) componentType, 0).getClass();
            } else {
                Class<?> componentClass = typeToClass(componentType);
                result = Array.newInstance(componentClass, 0).getClass();
            }
        }
        if (result == null) {
            result = Object.class;
        }
        return result;
    }

    private void addFields(Class<?> clazz) {
        //获取属性
        Field[] fields = clazz.getDeclaredFields();
        for (Field field : fields) {
            if (!setMethods.containsKey(field.getName())) {
                //当字段不存在setter方法时
                // issue #379 - removed the check for final because JDK 1.5 allows
                // modification of final fields through reflection (JSR-133). (JGB)
                // pr #16 - final static can only be set by the classloader
                //获取属性的修饰符
                int modifiers = field.getModifiers();

                if (!(Modifier.isFinal(modifiers) && Modifier.isStatic(modifiers))) {
                    //仅当非final与static
                    addSetField(field);
                }
            }
            if (!getMethods.containsKey(field.getName())) {
                //当字段不存在getter方法时
                addGetField(field);
            }
        }
        if (clazz.getSuperclass() != null) {
            //获取父类的字段
            addFields(clazz.getSuperclass());
        }
    }

    /**
     * 添加字段的setter方法
     *
     * @param field
     */
    private void addSetField(Field field) {
        if (isValidPropertyName(field.getName())) {
            //将字段无setter，给字段添加SetFieldInvoker方法
            setMethods.put(field.getName(), new SetFieldInvoker(field));
            //获取真实field类型
            Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
            setTypes.put(field.getName(), typeToClass(fieldType));
        }
    }

    private void addGetField(Field field) {
        if (isValidPropertyName(field.getName())) {
            //给字段封装getter的Invoker
            getMethods.put(field.getName(), new GetFieldInvoker(field));
            Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
            getTypes.put(field.getName(), typeToClass(fieldType));
        }
    }

    /**
     * 判断是否有效属性值
     *
     * @param name
     * @return
     */
    private boolean isValidPropertyName(String name) {
        return !(name.startsWith("$") || "serialVersionUID".equals(name) || "class".equals(name));
    }

    /**
     * This method returns an array containing all methods
     * declared in this class and any superclass.
     * We use this method, instead of the simpler <code>Class.getMethods()</code>,
     * because we want to look for private methods as well.
     *
     * @param clazz The class
     * @return An array containing all methods in this class
     */
    private Method[] getClassMethods(Class<?> clazz) {
        Map<String, Method> uniqueMethods = new HashMap<>();
        Class<?> currentClass = clazz;
        while (currentClass != null && currentClass != Object.class) {
            addUniqueMethods(uniqueMethods, currentClass.getDeclaredMethods());

            // we also need to look for interface methods -
            // because the class may be abstract
            Class<?>[] interfaces = currentClass.getInterfaces();
            for (Class<?> anInterface : interfaces) {
                addUniqueMethods(uniqueMethods, anInterface.getMethods());
            }

            currentClass = currentClass.getSuperclass();
        }

        Collection<Method> methods = uniqueMethods.values();

        return methods.toArray(new Method[0]);
    }

    private void addUniqueMethods(Map<String, Method> uniqueMethods, Method[] methods) {
        for (Method currentMethod : methods) {
            if (!currentMethod.isBridge()) {
                String signature = getSignature(currentMethod);
                // check to see if the method is already known
                // if it is known, then an extended class must have
                // overridden a method
                if (!uniqueMethods.containsKey(signature)) {
                    uniqueMethods.put(signature, currentMethod);
                }
            }
        }
    }

    private String getSignature(Method method) {
        StringBuilder sb = new StringBuilder();
        Class<?> returnType = method.getReturnType();
        if (returnType != null) {
            sb.append(returnType.getName()).append('#');
        }
        sb.append(method.getName());
        Class<?>[] parameters = method.getParameterTypes();
        for (int i = 0; i < parameters.length; i++) {
            sb.append(i == 0 ? ':' : ',').append(parameters[i].getName());
        }
        return sb.toString();
    }

    /**
     * 判断是否可修改可访问性
     * <p>
     * Checks whether can control member accessible.
     *
     * @return If can control member accessible, it return {@literal true}
     * @since 3.5.0
     */
    public static boolean canControlMemberAccessible() {
        try {
            SecurityManager securityManager = System.getSecurityManager();
            if (null != securityManager) {
                securityManager.checkPermission(new ReflectPermission("suppressAccessChecks"));
            }
        } catch (SecurityException e) {
            return false;
        }
        return true;
    }

    /**
     * Gets the name of the class the instance provides information for.
     *
     * @return The class name
     */
    public Class<?> getType() {
        return type;
    }

    public Constructor<?> getDefaultConstructor() {
        if (defaultConstructor != null) {
            return defaultConstructor;
        } else {
            throw new ReflectionException("There is no default constructor for " + type);
        }
    }

    public boolean hasDefaultConstructor() {
        return defaultConstructor != null;
    }

    public Invoker getSetInvoker(String propertyName) {
        Invoker method = setMethods.get(propertyName);
        if (method == null) {
            throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
        }
        return method;
    }

    public Invoker getGetInvoker(String propertyName) {
        Invoker method = getMethods.get(propertyName);
        if (method == null) {
            throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
        }
        return method;
    }

    /**
     * Gets the type for a property setter.
     *
     * @param propertyName - the name of the property
     * @return The Class of the property setter
     */
    public Class<?> getSetterType(String propertyName) {
        Class<?> clazz = setTypes.get(propertyName);
        if (clazz == null) {
            throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
        }
        return clazz;
    }

    /**
     * Gets the type for a property getter.
     *
     * @param propertyName - the name of the property
     * @return The Class of the property getter
     */
    public Class<?> getGetterType(String propertyName) {
        Class<?> clazz = getTypes.get(propertyName);
        if (clazz == null) {
            throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
        }
        return clazz;
    }

    /**
     * Gets an array of the readable properties for an object.
     *
     * @return The array
     */
    public String[] getGetablePropertyNames() {
        return readablePropertyNames;
    }

    /**
     * Gets an array of the writable properties for an object.
     *
     * @return The array
     */
    public String[] getSetablePropertyNames() {
        return writablePropertyNames;
    }

    /**
     * Check to see if a class has a writable property by name.
     *
     * @param propertyName - the name of the property to check
     * @return True if the object has a writable property by the name
     */
    public boolean hasSetter(String propertyName) {
        return setMethods.containsKey(propertyName);
    }

    /**
     * Check to see if a class has a readable property by name.
     *
     * @param propertyName - the name of the property to check
     * @return True if the object has a readable property by the name
     */
    public boolean hasGetter(String propertyName) {
        return getMethods.containsKey(propertyName);
    }

    public String findPropertyName(String name) {
        return caseInsensitivePropertyMap.get(name.toUpperCase(Locale.ENGLISH));
    }
}

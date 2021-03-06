package com.guicedee.guicedinjection.interfaces;

import com.guicedee.guicedinjection.GuiceConfig;
import com.guicedee.guicedinjection.GuiceContext;
import com.guicedee.logger.LogFactory;
import io.github.classgraph.ClassInfo;

import jakarta.validation.constraints.NotNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import static com.guicedee.guicedinjection.GuiceContext.*;
/**
 * Supplies standard set changer and comparable's for services
 *
 * @param <J>
 */
public interface IDefaultService<J extends IDefaultService<J>>
        extends Comparable<J>, Comparator<J> {
    Map<String, Set<Class>> loaderClasses = new ConcurrentHashMap<>();

    /**
     * Method loaderToSet, converts a ServiceLoader into a TreeSet
     *
     * @param loader of type ServiceLoader
     * @return Set
     */
    @SuppressWarnings("unchecked")
    @NotNull
    static <T extends Comparable<T>> Set<T> loaderToSet(ServiceLoader<T> loader) {
        @SuppressWarnings("rawtypes")
        Set<Class> loadeds = new HashSet<>();

        String type = loader.toString();
        type = type.replace("java.util.ServiceLoader[", "");
        type = type.substring(0, type.length() - 1);

        if (!loaderClasses.containsKey(type)) {
            GuiceConfig<?> config = GuiceContext.instance().getConfig();
            if (config.isServiceLoadWithClassPath()) {
                for (ClassInfo classInfo : instance()
                        .getScanResult()
                        .getClassesImplementing(type)) {
                    Class<T> load = (Class<T>) classInfo.loadClass();
                    loadeds.add(load);
                }
            }
            try {
                for (T newInstance : loader) {
                    loadeds.add(newInstance.getClass());
                }
            } catch (Throwable T) {
                LogFactory.getLog("IDefaultService").log(Level.SEVERE, "Unable to provide instance of " + type + " to TreeSet", T);
            }
            loaderClasses.put(type, loadeds);
        }

        Set<T> outcomes = new TreeSet<>();
        for (Class<?> aClass : loaderClasses.get(type)) {
            outcomes.add((T) GuiceContext.get(aClass));
        }
        return outcomes;
    }

    static <T extends Comparable<T>> Set<Class<T>> loadClassSet(ServiceLoader<T> loader) {
        String type = loader.toString();
        type = type.replace("java.util.ServiceLoader[", "");
        type = type.substring(0, type.length() - 1);

        if (!loaderClasses.containsKey(type)) {
            Set<Class> loadeds = new HashSet<>();
            GuiceConfig<?> config = GuiceContext.instance().getConfig();
            if (config.isServiceLoadWithClassPath()) {
                for (ClassInfo classInfo : instance()
                        .getScanResult()
                        .getClassesImplementing(type)) {
                    @SuppressWarnings("unchecked")
                    Class<T> load = (Class<T>) classInfo.loadClass();
                    loadeds.add(load);
                }
            }
            try {
                for (T newInstance : loader) {
                    //noinspection unchecked
                    loadeds.add((Class<T>) newInstance.getClass());
                }
            } catch (Throwable T) {
                LogFactory.getLog("IDefaultService").log(Level.SEVERE, "Unable to provide instance of " + type + " to TreeSet", T);
            }
            loaderClasses.put(type, loadeds);
        }
        //noinspection unchecked
        return (Set)loaderClasses.get(type);
    }

    /**
     * Method loaderToSet, converts a ServiceLoader into a TreeSet
     *
     * @param loader of type ServiceLoader
     * @return Set
     */
    @SuppressWarnings("unchecked")
    @NotNull
    static <T> Set<T> loaderToSetNoInjection(ServiceLoader<T> loader) {
        Set<Class<T>> loadeds = new HashSet<>();
        GuiceConfig config = GuiceContext.instance().getConfig();
        String type = loader.toString();
        type = type.replace("java.util.ServiceLoader[", "");
        type = type.substring(0, type.length() - 1);
        if (config.isServiceLoadWithClassPath() && !buildingInjector) {
            for (ClassInfo classInfo : instance()
                    .getScanResult()
                    .getClassesImplementing(type)) {
                Class<T> load = (Class<T>) classInfo.loadClass();
                loadeds.add(load);
            }
        }
        Set<Class<T>> completed = new LinkedHashSet<>();
        Set<T> output = new LinkedHashSet<>();
        try {
            for (T newInstance : loader) {
                output.add(newInstance);
                completed.add((Class<T>) newInstance.getClass());
            }
        } catch (Throwable T) {
            LogFactory.getLog("IDefaultService").log(Level.SEVERE, "Cannot load services - ", T);
        }
        for (Class<T> newInstance : loadeds) {
            if (completed.contains(newInstance)) {
                continue;
            }
            try {
                output.add((T) newInstance.getDeclaredConstructor());
            } catch (NoSuchMethodException e) {
                LogFactory.getLog("IDefaultService")
                        .log(Level.SEVERE, "Cannot load a service through default constructor", e);
            }
        }
        return output;
    }

    /**
     * Method compare ...
     *
     * @param o1 of type J
     * @param o2 of type J
     * @return int
     */
    @Override
    default int compare(J o1, J o2) {
        if (o1 == null || o2 == null) {
            return -1;
        }
        return o1.sortOrder()
                .compareTo(o2.sortOrder());
    }

    /**
     * Default Sort Order 100
     *
     * @return 100
     */
    default Integer sortOrder() {
        return 100;
    }

    /**
     * Method compareTo ...
     *
     * @param o of type J
     * @return int
     */
    @Override
    default int compareTo(@NotNull J o) {
        int sort = sortOrder().compareTo(o.sortOrder());
        if (sort == 0) {
            return -1;
        }
        return sort;
    }


}

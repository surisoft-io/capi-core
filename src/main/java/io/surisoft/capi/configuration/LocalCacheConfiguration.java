package io.surisoft.capi.configuration;

import io.surisoft.capi.schema.Service;
import org.cache2k.Cache;
import org.cache2k.Cache2kBuilder;

public class LocalCacheConfiguration {

    public static Cache<String, Service> serviceCache() {

        return new Cache2kBuilder<String, Service>(){}
                .name("serviceCache-" + System.currentTimeMillis())
                .eternal(true)
                .entryCapacity(10000)
                .storeByReference(true)
                .build();
    }


}

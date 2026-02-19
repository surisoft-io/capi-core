package io.surisoft.capi.processor;

import com.hazelcast.map.EntryProcessor;
import io.surisoft.capi.schema.ThrottleServiceObject;

import java.util.Map;

public class ThrottleEntryProcessor implements EntryProcessor<String, ThrottleServiceObject, Boolean> {

    private final long totalCallsAllowed;
    private final long expirationDuration;
    private final String consumerKey;

    public ThrottleEntryProcessor(long totalCallsAllowed, long expirationDuration, String consumerKey) {
        this.totalCallsAllowed = totalCallsAllowed;
        this.expirationDuration = expirationDuration;
        this.consumerKey = consumerKey;
    }

    @Override
    public Boolean process(Map.Entry<String, ThrottleServiceObject> entry) {
        ThrottleServiceObject throttleServiceObject = entry.getValue();

        if (throttleServiceObject == null || throttleServiceObject.isExpired()) {
            throttleServiceObject = new ThrottleServiceObject(entry.getKey(), consumerKey, totalCallsAllowed, expirationDuration);
            entry.setValue(throttleServiceObject);
            return true;
        }

        if (throttleServiceObject.canCall()) {
            throttleServiceObject.increment();
            entry.setValue(throttleServiceObject);
            return true;
        }

        return false;
    }
}

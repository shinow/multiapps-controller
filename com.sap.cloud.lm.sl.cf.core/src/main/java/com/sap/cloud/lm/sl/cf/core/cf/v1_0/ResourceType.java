package com.sap.cloud.lm.sl.cf.core.cf.v1_0;

import java.util.EnumSet;
import java.util.Set;

public enum ResourceType {
    MANAGED_SERVICE("managed-service"), USER_PROVIDED_SERVICE("user-provided-service"), EXISTING_SERVICE(
        "existing-service"), EXISTING_SERVICE_KEY("existing-service-key");

    private String value;

    ResourceType(String value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return value;
    }

    public static ResourceType get(String value) {
        for (ResourceType v : values()) {
            if (v.value.equals(value))
                return v;
        }
        return null;
    }

    public static Set<ResourceType> getServiceTypes() {
        return EnumSet.of(MANAGED_SERVICE, USER_PROVIDED_SERVICE, EXISTING_SERVICE);
    }
}
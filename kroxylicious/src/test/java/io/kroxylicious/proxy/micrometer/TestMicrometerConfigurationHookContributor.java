/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.micrometer;

import org.jetbrains.annotations.NotNull;

import io.kroxylicious.proxy.config.BaseConfig;
import io.kroxylicious.proxy.service.ConfigurationDefinition;
import io.kroxylicious.proxy.service.Context;

public class TestMicrometerConfigurationHookContributor implements MicrometerConfigurationHookContributor {

    @NotNull
    @Override
    public String getTypeName() {
        return SHORT_NAME;
    }

    @Override
    public ConfigurationDefinition getConfigDefinition() {
        return new ConfigurationDefinition(Config.class, true);
    }

    @Override
    public MicrometerConfigurationHook getInstance(Context context) {
        return new TestHook(SHORT_NAME, context.getConfig(), context);
    }

    public static class Config extends BaseConfig {

    }

    public static final String SHORT_NAME = "test";

}
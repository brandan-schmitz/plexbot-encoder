package net.celestialdata.plexbotencoder.utilities;

import io.smallrye.config.ConfigSourceInterceptor;
import io.smallrye.config.ConfigSourceInterceptorContext;
import io.smallrye.config.ConfigValue;
import io.smallrye.config.Priorities;

import javax.annotation.Priority;

@Priority(Priorities.LIBRARY + 1000)
public class ConfigInterceptor implements ConfigSourceInterceptor {

    @Override
    public ConfigValue getValue(final ConfigSourceInterceptorContext context, final String name) {
        ConfigValue configValue = context.proceed(name);

        switch (name) {
            case "quarkus.banner.enabled":
                configValue = configValue.withValue("false");
                break;
            case "quarkus.log.level":
                var logLevel = context.proceed("AppSettings.logLevel");
                configValue = logLevel.withName(name);
                break;
        }

        return configValue;
    }
}
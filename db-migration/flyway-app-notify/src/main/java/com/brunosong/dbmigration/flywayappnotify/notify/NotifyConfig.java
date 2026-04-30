package com.brunosong.dbmigration.flywayappnotify.notify;

import org.flywaydb.core.api.callback.Callback;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(NotifyProperties.class)
public class NotifyConfig {

    @Bean
    public FlywayConfigurationCustomizer notifyCallbackCustomizer(MigrationNotifyCallback callback) {
        return cfg -> cfg.callbacks(new Callback[]{callback});
    }
}
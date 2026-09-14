package com.example.lib;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Auto-configured by the platform jar; consumers get a Greeter bean without declaring one. */
@AutoConfiguration
public class LibAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public Greeter greeter() {
        return Greeters.standard();
    }
}

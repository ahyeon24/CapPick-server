package com.cappick.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.cappick.domain.usage.UsageBudgetProperties;

@Configuration
@EnableConfigurationProperties(UsageBudgetProperties.class)
public class UsageConfig {
}

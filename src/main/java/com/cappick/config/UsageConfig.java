package com.cappick.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.cappick.domain.usage.UsageBudgetProperties;
import com.cappick.domain.usage.UsageQuotaProperties;

@Configuration
@EnableConfigurationProperties({UsageBudgetProperties.class, UsageQuotaProperties.class})
public class UsageConfig {
}

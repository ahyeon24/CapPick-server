package com.cappick.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.cappick.external.contentanalysis.gemini.GeminiProperties;

@Configuration
@EnableConfigurationProperties(GeminiProperties.class)
public class AiConfig {
}

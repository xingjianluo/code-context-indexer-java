package com.github.xingjianluo.codecontextindexerjava.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 启用 cci.* 配置绑定。
 */
@Configuration
@EnableConfigurationProperties(IndexerProperties.class)
public class IndexerConfig {
}

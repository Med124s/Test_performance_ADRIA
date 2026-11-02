package com.loadpilot.backend.config;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Cree le service de chiffrement des secrets, aussi utilise par la migration Liquibase 033. */
@Configuration
public class SecretsEncryptionConfig {

    @Bean
    public static BeanFactoryPostProcessor liquibaseAfterSecretCipher() {
        return beanFactory -> {
            if (!beanFactory.containsBeanDefinition("liquibase")) return;
            BeanDefinition liquibase = beanFactory.getBeanDefinition("liquibase");
            String[] current = liquibase.getDependsOn();
            String[] next = current == null ? new String[1] : java.util.Arrays.copyOf(current, current.length + 1);
            next[next.length - 1] = "secretCipher";
            liquibase.setDependsOn(next);
        };
    }
}

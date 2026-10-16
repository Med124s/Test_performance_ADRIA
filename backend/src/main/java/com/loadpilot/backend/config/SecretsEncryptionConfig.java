package com.loadpilot.backend.config;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Correction P1 : la migration Liquibase qui chiffre les secrets existants
 * (EncryptApplicationSecretsChange) utilise la meme cle que l'application. Le bean de chiffrement
 * (secretCipher) est donc cree AVANT Liquibase, qui lui-meme precede JPA : une cle absente ou
 * invalide arrete le demarrage avant toute lecture ou ecriture en base.
 */
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

package com.example.demo_java_api.config;

import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.service.TransactionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.io.IOException;

@Configuration
public class ScalarDbConfig {

    @Value("${scalardb.config.file:scalardb.properties}")
    private String scalarDbConfigFile;

    @Bean
    public DistributedTransactionManager distributedTransactionManager() throws IOException {
        TransactionFactory factory = TransactionFactory.create(scalarDbConfigFile);
        return factory.getTransactionManager();
    }
}
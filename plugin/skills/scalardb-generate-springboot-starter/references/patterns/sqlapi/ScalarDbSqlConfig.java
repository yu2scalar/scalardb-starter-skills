package com.example.demo_sql_api.config;

import com.scalar.db.sql.SqlSessionFactory;
import com.scalar.db.sql.TransactionMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.io.IOException;

/**
 * ScalarDB SQL Configuration for One-Phase Commit (1PC) transactions.
 *
 * This configuration creates a SqlSessionFactory with TransactionMode.TRANSACTION,
 * which is suitable for:
 * - Standalone SQL services (SqlService)
 * - 1PC participant services (OnePCSqlService)
 * - 1PC BFF services (OnePCSqlBffService)
 *
 * For Two-Phase Commit (2PC) transactions, use ScalarDbSqlConfig2PC instead.
 */
@Configuration
public class ScalarDbSqlConfig {

    @Value("${scalardb_sql.config.file:scalardb_sql.properties}")
    private String scalarDbSqlConfigFile;

    @Bean
    public SqlSessionFactory sqlSessionFactory() throws IOException {
        return SqlSessionFactory.builder()
                .withPropertiesFile(scalarDbSqlConfigFile)
                .withDefaultTransactionMode(TransactionMode.TRANSACTION)
                .build();
    }
}

package com.example.demo_sql_api.config;

import com.scalar.db.sql.exception.AuthenticationException;
import com.scalar.db.sql.exception.AuthorizationException;
import com.scalar.db.sql.exception.SqlException;
import com.scalar.db.sql.exception.UnknownTransactionStatusException;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;

/**
 * SQL API Retry Configuration
 *
 * Uses Spring Framework's built-in retry support (org.springframework.core.retry,
 * Framework 7+ / Spring Boot 4+) instead of the separate spring-retry library —
 * no additional dependency is required.
 *
 * Retryability follows the official ScalarDB documentation
 * (official-style policy): every exception in the SqlException family is
 * retryable with limits (bounded attempts + backoff), EXCEPT:
 * - UnknownTransactionStatusException: the commit outcome is unknown;
 *   blind retry risks duplicate operations. The SQL API exposes no
 *   transaction-state resolution API, so manual (read-back) verification
 *   is required.
 * - AuthenticationException / AuthorizationException: credential or
 *   permission problems; retrying cannot change the result.
 *
 * Retryable examples: TransactionRetryableException and its subclasses
 * (transient by contract), and plain SqlException, which may be transient
 * or persistent — bounded retry handles both.
 *
 * The built-in RetryPolicy matches includes()/excludes() against the thrown
 * exception AND its nested causes, and exclusions take precedence over the
 * included base type — required here because all excluded types are
 * subclasses of SqlException.
 *
 * Uses common retry parameters from application.properties via RetryProperties.
 *
 * @see <a href="https://scalardb.scalar-labs.com/docs/latest/scalardb-sql/sql-api-guide">ScalarDB SQL API Guide</a>
 */
@Configuration
public class SqlApiRetryConfig {

    @Autowired
    private RetryProperties retryProperties;

    @Bean(name = "sqlApiRetryTemplate")
    public RetryTemplate sqlApiRetryTemplate() {
        // retry.max-attempts is the TOTAL attempt count, while the built-in
        // maxRetries counts only the re-attempts after the initial failure —
        // hence the -1 mapping. (Parameters are shared with the Core API.)
        RetryPolicy retryPolicy = RetryPolicy.builder()
                .includes(SqlException.class)
                .excludes(UnknownTransactionStatusException.class,
                        AuthenticationException.class,
                        AuthorizationException.class)
                .maxRetries(retryProperties.getMaxAttempts() - 1)
                .delay(Duration.ofMillis(retryProperties.getInitialDelayMs()))
                .multiplier(retryProperties.getMultiplier())
                .maxDelay(Duration.ofMillis(retryProperties.getMaxDelayMs()))
                .build();
        return new RetryTemplate(retryPolicy);
    }
}

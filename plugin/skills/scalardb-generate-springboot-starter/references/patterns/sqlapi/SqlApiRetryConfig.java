package com.example.demo_sql_api.config;

import com.example.demo_sql_api.config.RetryProperties;
import com.scalar.db.sql.exception.TransactionRetryableException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.RetryContext;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

import java.util.Set;

/**
 * SQL API Retry Configuration
 *
 * Defines retryable exceptions based on ScalarDB SQL API documentation:
 * - TransactionRetryableException: Explicitly marked as retryable
 *
 * Uses common retry parameters from application.properties via RetryProperties.
 *
 * Note: UnknownTransactionStatusException requires manual verification
 * and is NOT automatically retried to avoid duplicate operations.
 *
 * @see <a href="https://scalardb.scalar-labs.com/docs/latest/scalardb-sql/sql-api-guide">ScalarDB SQL API Guide</a>
 */
@Configuration
public class SqlApiRetryConfig {

    @Autowired
    private RetryProperties retryProperties;

    /**
     * Retryable exceptions for SQL API based on ScalarDB official documentation
     */
    private static final Set<Class<? extends Throwable>> RETRYABLE_EXCEPTIONS = Set.of(
        TransactionRetryableException.class
    );

    @Bean(name = "sqlApiRetryTemplate")
    public RetryTemplate sqlApiRetryTemplate() {
        RetryTemplate template = new RetryTemplate();

        // Use common retry parameters from properties (same as Core API)
        template.setRetryPolicy(new SqlApiRetryPolicy(
            retryProperties.getMaxAttempts(),
            RETRYABLE_EXCEPTIONS
        ));

        // Use common backoff parameters from properties (same as Core API)
        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(retryProperties.getInitialDelayMs());
        backOffPolicy.setMultiplier(retryProperties.getMultiplier());
        backOffPolicy.setMaxInterval(retryProperties.getMaxDelayMs());
        template.setBackOffPolicy(backOffPolicy);

        return template;
    }

    /**
     * Custom RetryPolicy that checks exception cause chains
     *
     * This policy checks both the exception itself and its entire cause chain
     * to detect retryable exceptions that may be wrapped by application or
     * framework layers.
     */
    private static class SqlApiRetryPolicy extends SimpleRetryPolicy {
        private final Set<Class<? extends Throwable>> retryableExceptions;

        public SqlApiRetryPolicy(int maxAttempts, Set<Class<? extends Throwable>> retryableExceptions) {
            super(maxAttempts);
            this.retryableExceptions = retryableExceptions;
        }

        @Override
        public boolean canRetry(RetryContext context) {
            Throwable t = context.getLastThrowable();
            // Allow first attempt (when there's no exception yet)
            if (t == null) {
                return super.canRetry(context);
            }
            // For retries, check if exception is retryable
            return isRetryable(t) && super.canRetry(context);
        }

        /**
         * Check if exception is retryable by examining both the exception
         * itself and its entire cause chain
         */
        private boolean isRetryable(Throwable t) {
            // Check if exception itself is retryable
            for (Class<? extends Throwable> retryableClass : retryableExceptions) {
                if (retryableClass.isInstance(t)) {
                    return true;
                }
            }

            // Check cause chain
            Throwable cause = t.getCause();
            while (cause != null) {
                for (Class<? extends Throwable> retryableClass : retryableExceptions) {
                    if (retryableClass.isInstance(cause)) {
                        return true;
                    }
                }
                cause = cause.getCause();
            }

            return false;
        }
    }
}

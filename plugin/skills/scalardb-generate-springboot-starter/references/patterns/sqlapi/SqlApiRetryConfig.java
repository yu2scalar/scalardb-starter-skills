package com.example.demo_sql_api.config;

import com.example.demo_sql_api.config.RetryProperties;
import com.scalar.db.sql.exception.AuthenticationException;
import com.scalar.db.sql.exception.AuthorizationException;
import com.scalar.db.sql.exception.SqlException;
import com.scalar.db.sql.exception.UnknownTransactionStatusException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.RetryContext;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

/**
 * SQL API Retry Configuration
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
        RetryTemplate template = new RetryTemplate();

        // Use common retry parameters from properties (same as Core API)
        template.setRetryPolicy(new SqlApiRetryPolicy(retryProperties.getMaxAttempts()));

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
     * This policy checks both the exception itself and its entire cause chain,
     * so retryable exceptions wrapped by application or framework layers are
     * still detected. Exclusions are checked first — anywhere in the chain —
     * because all excluded types are subclasses of SqlException.
     */
    private static class SqlApiRetryPolicy extends SimpleRetryPolicy {

        public SqlApiRetryPolicy(int maxAttempts) {
            super(maxAttempts);
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
         * Check retryability by examining the exception and its entire
         * cause chain (official-style policy — see the class javadoc).
         */
        private boolean isRetryable(Throwable t) {
            // Pass 1: exclusions anywhere in the cause chain short-circuit to
            // non-retryable (all are subclasses of SqlException).
            for (Throwable c = t; c != null; c = c.getCause()) {
                if (c instanceof UnknownTransactionStatusException
                        || c instanceof AuthenticationException
                        || c instanceof AuthorizationException) {
                    return false;
                }
            }
            // Pass 2: any SqlException in the chain is retryable
            // (TransactionRetryableException is a subclass and is covered here).
            for (Throwable c = t; c != null; c = c.getCause()) {
                if (c instanceof SqlException) {
                    return true;
                }
            }
            return false;
        }
    }
}

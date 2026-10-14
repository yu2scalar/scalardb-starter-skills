package com.example.demo_java_api.config;

import com.example.demo_java_api.config.RetryProperties;
import com.scalar.db.exception.transaction.TransactionException;
import com.scalar.db.exception.transaction.UnknownTransactionStatusException;
import com.scalar.db.exception.transaction.UnsatisfiedConditionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.RetryContext;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

/**
 * Core API Retry Configuration
 *
 * Retryability follows the official ScalarDB exception-handling guide
 * (official-style policy): every exception in the TransactionException
 * family is retryable with limits (bounded attempts + backoff), EXCEPT:
 * - UnknownTransactionStatusException: the commit outcome is unknown;
 *   blind retry risks duplicate operations (manual verification required).
 * - UnsatisfiedConditionException: an application-logic outcome
 *   (condition not met); retrying cannot change the result.
 *
 * Retryable examples: CrudConflictException / CommitConflictException /
 * TransactionNotFoundException (transient by contract), and plain
 * CrudException / CommitException / TransactionException (begin failure),
 * which may be transient (e.g. storage throttling on reads) or persistent —
 * bounded retry handles both.
 *
 * Uses common retry parameters from application.properties via RetryProperties.
 *
 * @see <a href="https://scalardb.scalar-labs.com/docs/latest/api-guide#how-to-handle-exceptions">ScalarDB Exception Handling</a>
 */
@Configuration
public class CoreApiRetryConfig {

    @Autowired
    private RetryProperties retryProperties;

    @Bean(name = "coreApiRetryTemplate")
    public RetryTemplate coreApiRetryTemplate() {
        RetryTemplate template = new RetryTemplate();

        // Use common retry parameters from properties
        template.setRetryPolicy(new CoreApiRetryPolicy(retryProperties.getMaxAttempts()));

        // Use common backoff parameters from properties
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
     * because both excluded types are subclasses of the retryable base types.
     */
    private static class CoreApiRetryPolicy extends SimpleRetryPolicy {

        public CoreApiRetryPolicy(int maxAttempts) {
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
            // non-retryable (both are subclasses of the retryable base types).
            for (Throwable c = t; c != null; c = c.getCause()) {
                if (c instanceof UnknownTransactionStatusException
                        || c instanceof UnsatisfiedConditionException) {
                    return false;
                }
            }
            // Pass 2: any TransactionException in the chain is retryable.
            for (Throwable c = t; c != null; c = c.getCause()) {
                if (c instanceof TransactionException) {
                    return true;
                }
            }
            return false;
        }
    }
}

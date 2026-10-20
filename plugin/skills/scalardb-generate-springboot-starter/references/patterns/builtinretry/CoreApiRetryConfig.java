package com.example.demo_java_api.config;

import com.scalar.db.exception.transaction.TransactionException;
import com.scalar.db.exception.transaction.UnknownTransactionStatusException;
import com.scalar.db.exception.transaction.UnsatisfiedConditionException;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;

/**
 * Core API Retry Configuration
 *
 * Uses Spring Framework's built-in retry support (org.springframework.core.retry,
 * Framework 7+ / Spring Boot 4+) instead of the separate spring-retry library —
 * no additional dependency is required.
 *
 * Retryability follows the official ScalarDB exception-handling guide
 * (official-style policy): every exception in the TransactionException
 * family is retryable with limits (bounded attempts + backoff), EXCEPT:
 * - UnknownTransactionStatusException: the commit outcome is unknown;
 *   blind retry risks duplicate operations (manual verification required).
 * - UnsatisfiedConditionException: an application-logic outcome
 *   (condition not met); retrying cannot change the result.
 * - Authentication / authorization failures from ScalarDB Cluster: they
 *   arrive as a TransactionException (e.g. CrudException) flagged with
 *   isAuthenticationError() / isAuthorizationError(), not as separate
 *   classes, so excludes() cannot express them — the predicate below
 *   rejects them; retrying cannot change the result.
 *
 * Retryable examples: CrudConflictException / CommitConflictException /
 * TransactionNotFoundException (transient by contract), and plain
 * CrudException / CommitException / TransactionException (begin failure),
 * which may be transient (e.g. storage throttling on reads) or persistent —
 * bounded retry handles both.
 *
 * The built-in RetryPolicy matches includes()/excludes() against the thrown
 * exception AND its nested causes, and exclusions take precedence over the
 * included base type — required here because both excluded types are
 * subclasses of TransactionException.
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
        // retry.max-attempts is the TOTAL attempt count, while the built-in
        // maxRetries counts only the re-attempts after the initial failure —
        // hence the -1 mapping.
        RetryPolicy retryPolicy = RetryPolicy.builder()
                .includes(TransactionException.class)
                .excludes(UnknownTransactionStatusException.class,
                        UnsatisfiedConditionException.class)
                .predicate(t -> !isAuthError(t))
                .maxRetries(retryProperties.getMaxAttempts() - 1)
                .delay(Duration.ofMillis(retryProperties.getInitialDelayMs()))
                .multiplier(retryProperties.getMultiplier())
                .maxDelay(Duration.ofMillis(retryProperties.getMaxDelayMs()))
                .build();
        return new RetryTemplate(retryPolicy);
    }

    /** True if any TransactionException in the cause chain is a Cluster auth failure. */
    private static boolean isAuthError(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof TransactionException te
                    && (te.isAuthenticationError() || te.isAuthorizationError())) {
                return true;
            }
        }
        return false;
    }
}

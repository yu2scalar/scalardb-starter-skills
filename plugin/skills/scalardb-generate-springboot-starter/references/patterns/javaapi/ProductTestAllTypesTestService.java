package com.example.demo_java_api.service;

import com.example.demo_java_api.model.ProductTestAllTypesTest;
import com.example.demo_java_api.dto.ProductTestAllTypesTestDto;
import com.example.demo_java_api.dto.ResponseStatusDto;
import com.example.demo_java_api.exception.CustomException;
import com.example.demo_java_api.mapper.ProductTestAllTypesTestMapper;
import com.example.demo_java_api.repository.ProductTestAllTypesTestRepository;
import com.scalar.db.api.DistributedTransaction;
import com.scalar.db.api.DistributedTransactionManager;
import com.scalar.db.io.Key;
import com.scalar.db.exception.transaction.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;

/**
 * Service class with automatic retry logic for transient failures.
 *
 * This service uses Spring Retry to automatically retry operations that fail
 * due to transient exceptions like CommitConflictException.
 *
 * Note: UnknownTransactionStatusException requires manual verification
 * and is NOT automatically retried to avoid duplicate operations.
 */
@Slf4j
@Service
public class ProductTestAllTypesTestService {
    DistributedTransactionManager manager;

    @Autowired
    @Qualifier("coreApiRetryTemplate")
    private RetryTemplate coreApiRetryTemplate;

    @Autowired
    ProductTestAllTypesTestRepository allTypesTestRepository;

    public ProductTestAllTypesTestService(DistributedTransactionManager manager) throws InstantiationException, IllegalAccessException {
        this.manager = manager;
    }

    // Create Record
    public ResponseStatusDto insertProductTestAllTypesTest(ProductTestAllTypesTestDto allTypesTestDto) throws CustomException {
        try {
            return coreApiRetryTemplate.execute(context -> {
                DistributedTransaction transaction = null;
                try {
                    ProductTestAllTypesTest allTypesTest = ProductTestAllTypesTestMapper.mapToProductTestAllTypesTest(allTypesTestDto);
                    transaction = manager.start();
                    allTypesTestRepository.insertProductTestAllTypesTest(transaction, allTypesTest);
                    transaction.commit();
                    return ResponseStatusDto.builder().code(0).message("").build();
                } catch (Exception e) {
                    handleTransactionException(e, transaction);
                    throw e;
                }
            });
        } catch (Exception e) {
            throw new CustomException(e, determineErrorCode(e));
        }
    }

    // Upsert Record
    public ResponseStatusDto upsertProductTestAllTypesTest(ProductTestAllTypesTestDto allTypesTestDto) throws CustomException {
        try {
            return coreApiRetryTemplate.execute(context -> {
                DistributedTransaction transaction = null;
                try {
                    ProductTestAllTypesTest allTypesTest = ProductTestAllTypesTestMapper.mapToProductTestAllTypesTest(allTypesTestDto);
                    transaction = manager.start();
                    allTypesTestRepository.upsertProductTestAllTypesTest(transaction, allTypesTest);
                    transaction.commit();
                    return ResponseStatusDto.builder().code(0).message("").build();
                } catch (Exception e) {
                    handleTransactionException(e, transaction);
                    throw e;
                }
            });
        } catch (Exception e) {
            throw new CustomException(e, determineErrorCode(e));
        }
    }

    // Retrieve Record
    public ProductTestAllTypesTestDto getProductTestAllTypesTest(ProductTestAllTypesTestDto allTypesTestDto) throws CustomException {
        try {
            return coreApiRetryTemplate.execute(context -> {
                DistributedTransaction transaction = null;
                try {
                    ProductTestAllTypesTest allTypesTest = ProductTestAllTypesTestMapper.mapToProductTestAllTypesTest(allTypesTestDto);
                    transaction = manager.start();
                    allTypesTest = allTypesTestRepository.getProductTestAllTypesTest(transaction, allTypesTest);
                    transaction.commit();
                    return ProductTestAllTypesTestMapper.mapToProductTestAllTypesTestDto(allTypesTest);
                } catch (Exception e) {
                    handleTransactionException(e, transaction);
                    throw e;
                }
            });
        } catch (Exception e) {
            throw new CustomException(e, determineErrorCode(e));
        }
    }

    // Update Record
    public ResponseStatusDto updateProductTestAllTypesTest(ProductTestAllTypesTestDto allTypesTestDto) throws CustomException {
        try {
            return coreApiRetryTemplate.execute(context -> {
                DistributedTransaction transaction = null;
                try {
                    ProductTestAllTypesTest allTypesTest = ProductTestAllTypesTestMapper.mapToProductTestAllTypesTest(allTypesTestDto);
                    transaction = manager.start();
                    allTypesTestRepository.updateProductTestAllTypesTest(transaction, allTypesTest);
                    transaction.commit();
                    return ResponseStatusDto.builder().code(0).message("").build();
                } catch (Exception e) {
                    handleTransactionException(e, transaction);
                    throw e;
                }
            });
        } catch (Exception e) {
            throw new CustomException(e, determineErrorCode(e));
        }
    }

    // Delete Record
    public ResponseStatusDto deleteProductTestAllTypesTest(ProductTestAllTypesTestDto allTypesTestDto) throws CustomException {
        try {
            return coreApiRetryTemplate.execute(context -> {
                DistributedTransaction transaction = null;
                try {
                    ProductTestAllTypesTest allTypesTest = ProductTestAllTypesTestMapper.mapToProductTestAllTypesTest(allTypesTestDto);
                    transaction = manager.start();
                    allTypesTestRepository.deleteProductTestAllTypesTest(transaction, allTypesTest);
                    transaction.commit();
                    return ResponseStatusDto.builder().code(0).message("").build();
                } catch (Exception e) {
                    handleTransactionException(e, transaction);
                    throw e;
                }
            });
        } catch (Exception e) {
            throw new CustomException(e, determineErrorCode(e));
        }
    }

    // Retrieve All Records
    public List<ProductTestAllTypesTestDto> getProductTestAllTypesTestListAll() throws CustomException {
        try {
            return coreApiRetryTemplate.execute(context -> {
                DistributedTransaction transaction = null;
                List<ProductTestAllTypesTest> allTypesTestList = new ArrayList<>();
                try {
                    transaction = manager.start();
                    allTypesTestList = allTypesTestRepository.getProductTestAllTypesTestListAll(transaction);
                    transaction.commit();
                    return ProductTestAllTypesTestMapper.mapToProductTestAllTypesTestDtoList(allTypesTestList);
                } catch (Exception e) {
                    handleTransactionException(e, transaction);
                    throw e;
                }
            });
        } catch (Exception e) {
            throw new CustomException(e, determineErrorCode(e));
        }
    }

    // Retrieve Records by Partition Key
    public List<ProductTestAllTypesTestDto> getProductTestAllTypesTestListByPk(ProductTestAllTypesTestDto allTypesTestDto) throws CustomException {
        try {
            return coreApiRetryTemplate.execute(context -> {
                DistributedTransaction transaction = null;
                List<ProductTestAllTypesTest> allTypesTestList = new ArrayList<>();
                try {
                    ProductTestAllTypesTest allTypesTest = ProductTestAllTypesTestMapper.mapToProductTestAllTypesTest(allTypesTestDto);
                    Key partitionKey = allTypesTest.getPartitionKey();
                    transaction = manager.start();
                    allTypesTestList = allTypesTestRepository.getProductTestAllTypesTestListByPk(transaction, partitionKey);
                    transaction.commit();
                    return ProductTestAllTypesTestMapper.mapToProductTestAllTypesTestDtoList(allTypesTestList);
                } catch (Exception e) {
                    handleTransactionException(e, transaction);
                    throw e;
                }
            });
        } catch (Exception e) {
            throw new CustomException(e, determineErrorCode(e));
        }
    }

    private void handleTransactionException(Exception e, DistributedTransaction transaction) {
        if (e instanceof UnknownTransactionStatusException) {
            // The commit may or may not have been applied, so do NOT roll back:
            // a rollback cannot undo a commit that already succeeded. Report the
            // transaction ID instead and leave the outcome to manual verification
            // (this exception is never auto-retried; the API returns 9200).
            String transactionId = ((UnknownTransactionStatusException) e).getTransactionId()
                    .orElseGet(() -> transaction != null ? transaction.getId() : "unknown");
            String message = "Transaction status is unknown (transaction ID: " + transactionId
                    + "). The commit may or may not have been applied; verify the data manually before retrying.";
            log.error(message, e);
            throw new CustomException(message, e, 9200);
        }
        log.error(e.getMessage(), e);
        if (transaction != null) {
            try {
                transaction.rollback();
            } catch (RollbackException ex) {
                log.error(ex.getMessage(), ex);
            }
        }
    }

    /**
     * Determine error code based on exception type.
     *
     * Note: UnknownTransactionStatusException requires manual verification
     * before retry to avoid duplicate operations. This exception is NOT
     * automatically retried.
     */
    private int determineErrorCode(Exception e) {
        // A CustomException already carries a code determined closer to the
        // source (e.g. repository not-found) - preserve it.
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof CustomException && ((CustomException) c).getErrorCode() != null) {
                return ((CustomException) c).getErrorCode();
            }
        }
        // Specific subtypes before their parent type; scan the whole cause
        // chain so wrapped exceptions are still classified.
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof UnsatisfiedConditionException) return 9100;
            if (c instanceof UnknownTransactionStatusException) return 9200;
            // ScalarDB Cluster reports authentication / authorization failures
            // as a flagged TransactionException (same code as the SQL API: 9600).
            if (c instanceof TransactionException te
                    && (te.isAuthenticationError() || te.isAuthorizationError())) return 9600;
        }
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof TransactionException) return 9300;
        }
        if (e instanceof RuntimeException) return 9400;
        return 9500;
    }
}

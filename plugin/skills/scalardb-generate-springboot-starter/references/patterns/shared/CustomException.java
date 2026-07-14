package com.example.demo_java_api.exception;

import com.scalar.db.exception.transaction.TransactionException;

public class CustomException extends RuntimeException {

    private Integer errorCode;

    public Integer getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(Integer errorCode) {
        this.errorCode = errorCode;
    }

    public CustomException(TransactionException ex, Integer errorCode) {
        super(ex.getMessage(), ex);
        this.setErrorCode(errorCode);
    }

    public CustomException(Exception ex, Integer errorCode) {
        super(ex.getMessage(), ex);
        this.setErrorCode(errorCode);
    }

    public CustomException(String message, Integer errorCode) {
        super(message);
        this.setErrorCode(errorCode);
    }

    public CustomException(String message, Throwable cause, Integer errorCode) {
        super(message, cause);
        this.setErrorCode(errorCode);
    }
}
package com.example.demo_java_api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {
    private boolean success;
    private String message;
    private T data;
    private Integer errorCode;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime timestamp;

    // Constructor for success with data
    public ApiResponse(T data) {
        this.success = true;
        this.message = "Success";
        this.data = data;
        this.timestamp = LocalDateTime.now();
    }

    // Constructor for success with data and custom message
    public ApiResponse(T data, String message) {
        this.success = true;
        this.message = message;
        this.data = data;
        this.timestamp = LocalDateTime.now();
    }

    // Constructor for error with errorCode
    public ApiResponse(Integer errorCode, String message) {
        this.success = false;
        this.message = message;
        this.errorCode = errorCode;
        this.timestamp = LocalDateTime.now();
    }

    // Constructor for simple success (no data)
    public ApiResponse(boolean success, String message) {
        this.success = success;
        this.message = message;
        this.timestamp = LocalDateTime.now();
    }

    // Static factory method for success with data
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(data);
    }

    // Static factory method for success with data and message
    public static <T> ApiResponse<T> success(T data, String message) {
        return new ApiResponse<>(data, message);
    }

    // Static factory method for simple success (no data)
    public static <T> ApiResponse<T> success(String message) {
        return ApiResponse.<T>builder()
                .success(true)
                .message(message)
                .timestamp(LocalDateTime.now())
                .build();
    }

    // Static factory method for error with errorCode
    public static <T> ApiResponse<T> error(Integer errorCode, String message) {
        return new ApiResponse<>(errorCode, message);
    }

    // Static factory method for error from ResponseStatusDto
    public static <T> ApiResponse<T> error(ResponseStatusDto status) {
        return ApiResponse.<T>builder()
                .success(false)
                .message(status.getMessage())
                .errorCode(status.getCode())
                .timestamp(LocalDateTime.now())
                .build();
    }

    // Static factory method for wrapping ResponseStatusDto as success
    public static <T> ApiResponse<T> fromResponseStatus(ResponseStatusDto status) {
        // Code 0 is used for success in the service layer, along with HTTP 200-299 range
        boolean isSuccess = status.getCode() == 0 || (status.getCode() >= 200 && status.getCode() < 300);
        String message = status.getMessage();

        // If message is empty and operation succeeded, provide default success message
        if (isSuccess && (message == null || message.isEmpty())) {
            message = "Operation completed successfully";
        }

        return ApiResponse.<T>builder()
                .success(isSuccess)
                .message(message)
                .errorCode(isSuccess ? null : status.getCode())
                .timestamp(LocalDateTime.now())
                .build();
    }
}

package com.example.demo_java_api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.nio.ByteBuffer;

@Data
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
public class ProductTestAllTypesTestDto {
    private Integer intValue;
    private Long bigintValue;
    private Float floatValue;
    private Boolean booleanValue;
    private Double doubleValue;
    private String textValue;
    @Schema(type = "string", format = "binary", description = "Base64 encoded binary data", example = "dGVzdA==")
    private byte[] blobValue;
    @Schema(type = "string", format = "date", example = "2025-09-15")
    private LocalDate dateValue;
    @Schema(type = "string", format = "time", example = "14:30:00")
    private LocalTime timeValue;
    @Schema(type = "string", format = "date-time", example = "2025-09-15T14:30:00")
    private LocalDateTime timestampValue;
    @Schema(type = "string", format = "date-time", example = "2025-09-15T14:30:00.000Z")
    private Instant timestamptzValue;
}
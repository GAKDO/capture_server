package com.gakdo.trace.capture.inspection.domain;

import com.gakdo.trace.capture.comparison.domain.DefectClass;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** DB·계약의 소문자 코드(scratch·dent·crack·stain) ↔ DefectClass. */
@Converter
public class DefectClassConverter implements AttributeConverter<DefectClass, String> {

    @Override
    public String convertToDatabaseColumn(DefectClass value) {
        return value == null ? null : value.code();
    }

    @Override
    public DefectClass convertToEntityAttribute(String code) {
        return code == null ? null : DefectClass.fromCode(code);
    }
}

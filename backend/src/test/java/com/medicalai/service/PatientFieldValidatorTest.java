package com.medicalai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.medicalai.exception.BusinessException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PatientFieldValidatorTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 20);

    @Test
    void treatsBlankContactFieldsAsAbsent() {
        assertNull(PatientFieldValidator.normalizePhone(null));
        assertNull(PatientFieldValidator.normalizePhone(" "));
        assertNull(PatientFieldValidator.normalizeIdNumber(null, TODAY));
        assertNull(PatientFieldValidator.normalizeIdNumber(" ", TODAY));
    }

    @Test
    void acceptsValidPhoneAndIdNumber() {
        assertEquals("13812345678", PatientFieldValidator.normalizePhone(" 13812345678 "));
        assertEquals("990105194912310023",
                PatientFieldValidator.normalizeIdNumber(" 990105194912310023 ", TODAY));
    }

    @Test
    void rejectsBadPhoneAndIdNumber() {
        assertThrows(BusinessException.class, () -> PatientFieldValidator.normalizePhone("1381234567"));
        assertThrows(BusinessException.class, () -> PatientFieldValidator.normalizePhone("12812345678"));
        assertThrows(BusinessException.class,
                () -> PatientFieldValidator.normalizeIdNumber("99010519491231", TODAY));
        assertThrows(BusinessException.class,
                () -> PatientFieldValidator.normalizeIdNumber("990105194913310023", TODAY));
        assertThrows(BusinessException.class,
                () -> PatientFieldValidator.normalizeIdNumber("990105194912310024", TODAY));
    }
}

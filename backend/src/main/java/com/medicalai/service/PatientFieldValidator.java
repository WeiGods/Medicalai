package com.medicalai.service;

import com.medicalai.exception.BusinessException;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/** Optional patient contact fields are validated only when the doctor supplies them. */
public final class PatientFieldValidator {
    private static final Pattern PHONE = Pattern.compile("1[3-9]\\d{9}");
    private static final Pattern ID_NUMBER = Pattern.compile("\\d{17}[\\dX]");
    private static final int[] CHECK_WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    private static final String CHECK_CODES = "10X98765432";

    private PatientFieldValidator() {
    }

    public static String normalizePhone(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) return null;
        if (!PHONE.matcher(normalized).matches()) {
            throw invalid("PATIENT_PHONE_INVALID", "请输入正确的11位手机号");
        }
        return normalized;
    }

    public static String normalizeIdNumber(String value, LocalDate today) {
        String normalized = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) return null;
        if (!ID_NUMBER.matcher(normalized).matches()) {
            throw invalid("PATIENT_ID_NUMBER_INVALID", "请输入18位身份证号");
        }
        if (birthDate(normalized).map(date -> date.isAfter(today)).orElse(true)) {
            throw invalid("PATIENT_ID_NUMBER_DATE_INVALID", "身份证中的出生日期无效");
        }
        if (!checkCodeValid(normalized)) {
            throw invalid("PATIENT_ID_NUMBER_CHECK_INVALID", "身份证校验码不正确");
        }
        return normalized;
    }

    private static Optional<LocalDate> birthDate(String idNumber) {
        try {
            return Optional.of(LocalDate.of(
                    Integer.parseInt(idNumber.substring(6, 10)),
                    Integer.parseInt(idNumber.substring(10, 12)),
                    Integer.parseInt(idNumber.substring(12, 14))));
        } catch (NumberFormatException | java.time.DateTimeException error) {
            return Optional.empty();
        }
    }

    private static boolean checkCodeValid(String idNumber) {
        int sum = 0;
        for (int index = 0; index < CHECK_WEIGHTS.length; index++) {
            sum += (idNumber.charAt(index) - '0') * CHECK_WEIGHTS[index];
        }
        return CHECK_CODES.charAt(sum % 11) == idNumber.charAt(17);
    }

    private static BusinessException invalid(String code, String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, code, message);
    }
}

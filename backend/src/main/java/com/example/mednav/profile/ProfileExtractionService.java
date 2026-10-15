package com.example.mednav.profile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Deterministic M2 extractor. A model-backed implementation can replace it in M3. */
@Service
public class ProfileExtractionService {

    public static final List<String> PROFILE_FIELDS =
            List.of("birth_date", "sex", "height_cm", "weight_kg");

    private static final Pattern DATE_SEPARATED = Pattern.compile(
            "\\b(\\d{4})\\s*[-./]\\s*(\\d{1,2})\\s*[-./]\\s*(\\d{1,2})\\b");
    private static final Pattern DATE_CHINESE = Pattern.compile(
            "(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日?");
    private static final Pattern HEIGHT_CM = Pattern.compile(
            "(?i)\\b(\\d{2,3}(?:\\.\\d+)?)\\s*(?:cm|centimeters?|厘米)\\b");
    private static final Pattern HEIGHT_US = Pattern.compile(
            "(?i)\\b([3-7])\\s*(?:ft|feet|foot|')\\s*(\\d{1,2})?\\s*(?:in|inches|\")?");
    private static final Pattern WEIGHT_KG = Pattern.compile(
            "(?i)\\b(\\d{1,3}(?:\\.\\d+)?)\\s*(?:kg|kilograms?|公斤|千克)\\b");
    private static final Pattern WEIGHT_LB = Pattern.compile(
            "(?i)\\b(\\d{2,3}(?:\\.\\d+)?)\\s*(?:lb|lbs|pounds?)\\b");
    private static final Pattern FEMALE = Pattern.compile("(?i)(?:\\b(?:female|woman)\\b|女)");
    private static final Pattern MALE = Pattern.compile("(?i)(?:\\b(?:male|man)\\b|男)");
    private static final Pattern SKIP = Pattern.compile(
            "(?i)(?:prefer not|rather not|skip|don't know|do not know|unknown|不想|跳过|不知道|不清楚)");

    public ProfileExtractionResult extract(String message, Map<String, Object> alreadyKnown) {
        Map<String, Object> values = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        String source = message == null ? "" : message.strip();
        String normalized = source.toLowerCase(Locale.ROOT);

        if (!alreadyKnown.containsKey("birth_date")) {
            parseBirthDate(source, values, errors);
        }
        if (!alreadyKnown.containsKey("sex")) {
            if (FEMALE.matcher(normalized).find()) {
                values.put("sex", "female");
            } else if (MALE.matcher(normalized).find()) {
                values.put("sex", "male");
            }
        }
        if (!alreadyKnown.containsKey("height_cm")) {
            parseHeight(source, values, errors);
        }
        if (!alreadyKnown.containsKey("weight_kg")) {
            parseWeight(source, values, errors);
        }

        return new ProfileExtractionResult(
                Map.copyOf(values), List.copyOf(errors), SKIP.matcher(source).find());
    }

    public List<String> missingFields(Map<String, Object> profile) {
        return PROFILE_FIELDS.stream().filter(field -> !profile.containsKey(field)).toList();
    }

    public String questionFor(List<String> missingFields, int round) {
        String fields = missingFields.stream().map(this::displayName).reduce((a, b) -> a + "、" + b).orElse("");
        return "为了完善用户画像，请一次告诉我你的" + fields
                + "。可使用例如：1990-05-16，female，165 cm，55 kg。"
                + "不愿提供的字段可以直接说跳过。（第 " + round + "/3 轮）";
    }

    private void parseBirthDate(
            String source, Map<String, Object> values, List<String> errors) {
        Matcher separated = DATE_SEPARATED.matcher(source);
        Matcher chinese = DATE_CHINESE.matcher(source);
        Matcher matched = separated.find() ? separated : chinese.find() ? chinese : null;
        if (matched == null) {
            return;
        }
        try {
            LocalDate date = LocalDate.of(
                    Integer.parseInt(matched.group(1)),
                    Integer.parseInt(matched.group(2)),
                    Integer.parseInt(matched.group(3)));
            if (date.isBefore(LocalDate.of(1900, 1, 1)) || date.isAfter(LocalDate.now())) {
                errors.add("birth_date must be between 1900-01-01 and today");
            } else {
                values.put("birth_date", date.toString());
            }
        } catch (DateTimeException exception) {
            errors.add("birth_date must be a valid YYYY-MM-DD date");
        }
    }

    private void parseHeight(String source, Map<String, Object> values, List<String> errors) {
        Matcher centimeters = HEIGHT_CM.matcher(source);
        BigDecimal value = null;
        if (centimeters.find()) {
            value = new BigDecimal(centimeters.group(1));
        } else {
            Matcher us = HEIGHT_US.matcher(source);
            if (us.find()) {
                int feet = Integer.parseInt(us.group(1));
                int inches = us.group(2) == null ? 0 : Integer.parseInt(us.group(2));
                value = BigDecimal.valueOf(feet * 12L + inches)
                        .multiply(BigDecimal.valueOf(2.54));
            }
        }
        if (value != null) {
            value = value.setScale(1, RoundingMode.HALF_UP);
            if (value.compareTo(BigDecimal.valueOf(50)) < 0
                    || value.compareTo(BigDecimal.valueOf(250)) > 0) {
                errors.add("height must be between 50 and 250 cm");
            } else {
                values.put("height_cm", value);
            }
        }
    }

    private void parseWeight(String source, Map<String, Object> values, List<String> errors) {
        Matcher kilograms = WEIGHT_KG.matcher(source);
        BigDecimal value = null;
        if (kilograms.find()) {
            value = new BigDecimal(kilograms.group(1));
        } else {
            Matcher pounds = WEIGHT_LB.matcher(source);
            if (pounds.find()) {
                value = new BigDecimal(pounds.group(1)).multiply(BigDecimal.valueOf(0.45359237));
            }
        }
        if (value != null) {
            value = value.setScale(1, RoundingMode.HALF_UP);
            if (value.compareTo(BigDecimal.valueOf(2)) < 0
                    || value.compareTo(BigDecimal.valueOf(500)) > 0) {
                errors.add("weight must be between 2 and 500 kg");
            } else {
                values.put("weight_kg", value);
            }
        }
    }

    private String displayName(String field) {
        return switch (field) {
            case "birth_date" -> "出生日期";
            case "sex" -> "性别";
            case "height_cm" -> "身高";
            case "weight_kg" -> "体重";
            default -> field;
        };
    }
}

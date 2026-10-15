package com.example.mednav.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProfileExtractionServiceTest {

    private final ProfileExtractionService service = new ProfileExtractionService();

    @Test
    void extractsUsUnitsAndDoesNotAskKnownFieldsAgain() {
        ProfileExtractionResult result = service.extract(
                "Born 1988-04-03, male, 5 ft 10 in, 180 lbs",
                Map.of("sex", "female"));

        assertThat(result.values())
                .containsEntry("birth_date", "1988-04-03")
                .containsEntry("height_cm", new BigDecimal("177.8"))
                .containsEntry("weight_kg", new BigDecimal("81.6"))
                .doesNotContainKey("sex");
        assertThat(service.missingFields(result.values())).contains("sex");
    }

    @Test
    void rejectsInvalidRangesWithoutInventingValues() {
        ProfileExtractionResult result =
                service.extract("2099-01-01, 400 cm, 900 kg", Map.of());

        assertThat(result.values()).isEmpty();
        assertThat(result.validationErrors()).hasSize(3);
    }

    @Test
    void recognizesAnExplicitSkip() {
        ProfileExtractionResult result =
                service.extract("I prefer not to provide these details", Map.of());

        assertThat(result.skipRemaining()).isTrue();
    }

    @Test
    void acceptsCommonChineseAndDotSeparatedBirthDates() {
        ProfileExtractionResult dotted =
                service.extract("2003.7.3，男性，180cm，70kg", Map.of());
        ProfileExtractionResult chinese =
                service.extract("出生日期是2003年7月3日", Map.of());

        assertThat(dotted.values()).containsEntry("birth_date", "2003-07-03");
        assertThat(chinese.values()).containsEntry("birth_date", "2003-07-03");
    }
}

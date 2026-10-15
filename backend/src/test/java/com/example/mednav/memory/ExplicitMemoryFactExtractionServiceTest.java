package com.example.mednav.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ExplicitMemoryFactExtractionServiceTest {

    private final ExplicitMemoryFactExtractionService service =
            new ExplicitMemoryFactExtractionService();

    @Test
    void extractsOnlyExplicitWhitelistedLongTermFacts() {
        ExplicitMemoryFacts facts = service.extract(
                "I was diagnosed with asthma. Drug allergy: penicillin. "
                        + "Food allergy: peanuts. I cannot take ibuprofen. I am vegetarian.");

        assertThat(facts.diseaseHistory()).containsExactly("asthma");
        assertThat(facts.coreConstraints())
                .containsEntry("drug_allergies", List.of("penicillin"))
                .containsEntry("food_allergies", List.of("peanuts"))
                .containsEntry("medication_contraindications", List.of("ibuprofen"))
                .containsEntry("dietary_restrictions", List.of("vegetarian"));
    }

    @Test
    void doesNotTurnOrdinarySymptomsIntoDiseaseHistory() {
        ExplicitMemoryFacts facts = service.extract(
                "I have a severe headache and vomiting for two hours.");

        assertThat(facts.isEmpty()).isTrue();
    }

    @Test
    void extractsChineseExplicitFacts() {
        ExplicitMemoryFacts facts = service.extract(
                "我既往患有哮喘。药物过敏：青霉素。食物过敏：花生。我是素食主义者。");

        assertThat(facts.diseaseHistory()).containsExactly("哮喘");
        assertThat(facts.coreConstraints().get("drug_allergies")).containsExactly("青霉素");
        assertThat(facts.coreConstraints().get("food_allergies")).containsExactly("花生");
        assertThat(facts.coreConstraints().get("dietary_restrictions"))
                .containsExactly("素食主义者");
    }
}

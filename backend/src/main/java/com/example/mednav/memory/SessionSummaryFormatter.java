package com.example.mednav.memory;

import java.util.StringJoiner;
import org.springframework.stereotype.Component;

@Component
public class SessionSummaryFormatter {

    public String fallback(SessionWorkingMemory memory) {
        StringJoiner summary = new StringJoiner("；");
        if (memory.symptoms() != null) {
            if (hasText(memory.symptoms().chiefComplaint())) {
                summary.add("本次主要症状为" + memory.symptoms().chiefComplaint());
            } else if (hasText(memory.symptoms().narrative())) {
                summary.add("用户提交了本次症状描述");
            }
            if (hasText(memory.symptoms().duration())) {
                summary.add("持续时间" + memory.symptoms().duration());
            }
        }
        if (!memory.preliminaryDiseases().isEmpty()) {
            summary.add("系统形成的初步疾病假设包括"
                    + String.join("、", memory.preliminaryDiseases())
                    + "，这些结果不是确诊");
        }
        String value = summary.toString();
        return value.isBlank() ? "本次会话未形成可保存的症状或初步疾病假设。" : value + "。";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

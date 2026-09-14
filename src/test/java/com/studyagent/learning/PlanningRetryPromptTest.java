package com.studyagent.learning;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.model.LearningPlanStage;
import org.junit.jupiter.api.Test;

class PlanningRetryPromptTest {
    private final LearningPlanningService service = new LearningPlanningService(null, null, null, null, null, null, null, new ObjectMapper(), null);

    @Test void validationRetryCarriesLatestFailureWithoutChangingBaseTask() {
        var previous = failed();
        String prompt = service.retryPrompt("current task", previous, "same-input");
        assertThat(prompt).startsWith("current task").contains("validation error", "bad output", "完整JSON");
        assertThat(previous.getRawOutput()).isEqualTo("bad output");
    }

    @Test void doesNotReuseFeedbackFromChangedInputsOrSuccessfulStage() {
        var previous = failed();
        assertThat(service.retryPrompt("current task", previous, "new-input")).isEqualTo("current task");
        previous.setStatus("SUCCEEDED");
        assertThat(service.retryPrompt("current task", previous, "same-input")).isEqualTo("current task");
    }

    @Test void transportFailureWithoutModelOutputDoesNotInventRepairContext() {
        var previous = failed(); previous.setRawOutput(null);
        assertThat(service.retryPrompt("current task", previous, "same-input")).isEqualTo("current task");
        assertThat(service.retryPrompt("current task", null, "same-input")).isEqualTo("current task");
    }

    private LearningPlanStage failed() {
        var stage = new LearningPlanStage();
        stage.setStatus("FAILED"); stage.setInputHash("same-input");
        stage.setRawOutput("bad output"); stage.setErrorMessage("validation error");
        return stage;
    }
}

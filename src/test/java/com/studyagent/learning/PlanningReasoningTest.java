package com.studyagent.learning;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.studyagent.config.LearningPlanningProperties;
import com.studyagent.config.LearningPlanningReasoningProperties;
import io.agentscope.core.model.Model;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlanningReasoningTest {
    @Test void outlineKeepsItsOutputBudgetWithoutReasoning() {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("deepseek-v4-flash");
        var budgets = new LearningPlanningProperties(4800,28000,6000,600);
        var enabled = new PlanningModel(model, budgets, new LearningPlanningReasoningProperties(true,16000,"high"));
        var disabled = new PlanningModel(model, budgets, new LearningPlanningReasoningProperties(false,16000,"high"));
        for (String stage : new String[]{"EXTRACT/0", "TASK_BATCH/0"}) {
            assertThat(enabled.fingerprintConfiguration(stage)).isEqualTo(disabled.fingerprintConfiguration(stage));
            assertThat(enabled.options(stage).getMaxTokens()).isEqualTo(6000);
        }
        assertThat(enabled.fingerprintConfiguration("OUTLINE")).isEqualTo(disabled.fingerprintConfiguration("OUTLINE"));
        assertThat(enabled.options("OUTLINE").getMaxTokens()).isEqualTo(16000);
        assertThat(enabled.options("OUTLINE").getAdditionalBodyParams()).containsEntry("thinking", Map.of("type", "disabled"));
        assertThat(enabled.options("EMPHASIS/0").getAdditionalBodyParams()).containsEntry("thinking", Map.of("type", "disabled"));
        for (String stage : new String[]{"EMPHASIS_REVIEW/0"}) {
            assertThat(enabled.fingerprintConfiguration(stage)).isNotEqualTo(disabled.fingerprintConfiguration(stage));
            assertThat(enabled.options(stage).getMaxTokens()).isEqualTo(16000);
            assertThat(enabled.options(stage).getAdditionalBodyParams()).containsEntry("thinking", Map.of("type", "enabled"));
        }
    }
}

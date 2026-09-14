package com.studyagent.learning;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.agent.governance.KnowledgeSearchRetryExecutor;
import com.studyagent.agent.integration.*;
import com.studyagent.config.LearningConversationProperties;
import com.studyagent.identity.IdentityScope;
import com.studyagent.model.*;
import com.studyagent.rag.retrieval.*;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.state.AgentState;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/** Executes the actual SDK runtime with in-process model responses; never a provider acceptance test. */
class LearningConversationGatewayTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final LearningConversationProperties properties = new LearningConversationProperties(8000,700,3000,5,60,"LOCAL");

    @Test
    void autonomousSearchAndExplanationStopWithoutExtraModelCallAndKeepPairedContext() {
        var calls = new AtomicInteger();
        Model model = new Model() {
            public String getModelName() { return "local-sdk-test"; }
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                assertThat(options.getToolChoice()).isNull();
                assertThat(tools).extracting(ToolSchema::getName).containsExactlyInAnyOrder("knowledge_search", "learning_explanation_done");
                if (calls.getAndIncrement() == 0) {
                    return Flux.just(ChatResponse.builder().id("search").content(List.of(ToolUseBlock.builder().id("search-call")
                            .name("knowledge_search").input(Map.of("query", "synthetic topic")).content("{\"query\":\"synthetic topic\"}").build())).finishReason("tool_calls").build());
                }
                assertThat(messages.stream().flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream()).toList()).hasSize(1);
                return Flux.just(ChatResponse.builder().id("explanation").content(List.of(
                        TextBlock.builder().text("这里是完整的测试讲解，只包含测试作者自行编写的示例事实与来源，没有课程资料。来源 [source-1]。").build(),
                        ToolUseBlock.builder().id("done-call").name("learning_explanation_done").input(Map.of()).content("{}").build()))
                        .finishReason("tool_calls").build());
            }
        };
        var result = gateway(model).respond(session(), point("NEW"), turn(), saved(List.of()), List.of(), e -> { });
        assertThat(calls.get()).isEqualTo(2);
        assertThat(result.intent().action()).isEqualTo(LearningTurnIntent.Action.EXPLANATION);
        assertThat(result.answer()).contains("完整的测试讲解");
        var messages = AgentState.fromJsonString(result.preparedContext()).getContext();
        LearningContextMessages.requirePaired(messages);
        assertThat(messages.stream().flatMap(m -> m.getContentBlocks(ToolUseBlock.class).stream()).toList()).hasSize(2);
        assertThat(messages).allMatch(m -> LearningContextMessages.belongsTo(m, 20L));
    }

    @Test
    void ordinaryQuestionRestoresOnlySavedContextAndDoesNotRequireTransition() {
        Msg historical = LearningContextMessages.summary("preserved synthetic fact", 19L, "POINT");
        Model model = new Model() {
            public String getModelName() { return "local-sdk-test"; }
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                assertThat(messages.stream().map(Msg::getTextContent).toList()).anyMatch(t -> t.contains("preserved synthetic fact"));
                assertThat(messages.stream().filter(m -> m.getRole() == MsgRole.SYSTEM).map(Msg::getTextContent).toList())
                        .anyMatch(t -> t.contains("整体大纲和进度"));
                assertThat(options.getToolChoice()).isNull();
                return Flux.just(ChatResponse.builder().id("qa").content(List.of(TextBlock.builder().text("A synthetic clarification.").build())).finishReason("stop").build());
            }
        };
        var result = gateway(model).respond(session(), point("EXPLAINING"), turn(), saved(List.of(historical)), List.of(), e -> { });
        assertThat(result.intent().action()).isNull();
        assertThat(AgentState.fromJsonString(result.preparedContext()).getContext()).first().extracting(Msg::getId).isEqualTo(historical.getId());
    }

    private LearningConversationGateway gateway(Model model) {
        return gateway(model, mock(SourceReader.class));
    }

    @Test
    void confirmedSourceCanSupportExplanationWithoutSemanticSearch() {
        var calls = new AtomicInteger();
        var reader = mock(SourceReader.class);
        when(reader.read(1L, 2L, "planned-source")).thenReturn(
                new SourceReader.Source("planned-source", 3L, "Original lecture", "page 4", "Author-written source fact"));
        Model model = new Model() {
            public String getModelName() { return "local-sdk-test"; }
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                assertThat(tools).extracting(ToolSchema::getName).contains("knowledge_read");
                if (calls.getAndIncrement() == 0) {
                    return Flux.just(ChatResponse.builder().id("read").content(List.of(ToolUseBlock.builder().id("read-call")
                            .name("knowledge_read").input(Map.of("chunkId", "planned-source"))
                            .content("{\"chunkId\":\"planned-source\"}").build())).finishReason("tool_calls").build());
                }
                assertThat(messages.stream().flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                        .flatMap(r -> r.getOutput().stream()).filter(TextBlock.class::isInstance)
                        .map(TextBlock.class::cast).map(TextBlock::getText).toList())
                        .anyMatch(t -> t.contains("Author-written source fact"));
                return Flux.just(ChatResponse.builder().id("explanation").content(List.of(
                        TextBlock.builder().text("This is a complete explanation of the author-written synthetic source fact [planned-source].").build(),
                        ToolUseBlock.builder().id("done").name("learning_explanation_done").input(Map.of()).content("{}").build()))
                        .finishReason("tool_calls").build());
            }
        };
        var point = point("NEW"); point.setSourcesJson("[\"planned-source\"]");
        var result = gateway(model, reader).respond(session(), point, turn(), saved(List.of()), List.of(), e -> { });
        assertThat(result.intent().action()).isEqualTo(LearningTurnIntent.Action.EXPLANATION);
        assertThat(calls.get()).isEqualTo(2);
        verify(reader).read(1L, 2L, "planned-source");
        LearningContextMessages.requirePaired(AgentState.fromJsonString(result.preparedContext()).getContext());
    }

    private LearningConversationGateway gateway(Model model, SourceReader reader) {
        var scopes = mock(AgentInvocationScopeFactory.class);
        when(scopes.createRuntimeContext("s", 1L, 2L, 20L)).thenAnswer(i -> RuntimeContext.builder().userId("1").sessionId("s")
                .put(AgentInvocationScope.class, new AgentInvocationScope(1L, 2L, 20L))
                .put(KnowledgeSearchScope.class, new KnowledgeSearchScope(1L, 2L)).build());
        var retrieval = mock(KnowledgeRetrievalService.class);
        when(retrieval.search(1L, 2L, "synthetic topic")).thenReturn(new KnowledgeSearchResponse("synthetic topic", null,
                List.of(new KnowledgeSearchResponse.Result("source-1", "synthetic fact", null, 1))));
        var search = new KnowledgeSearchTool(retrieval, new KnowledgeSearchRetryExecutor(), mapper);
        return new LearningConversationGateway(model, search, reader, scopes, properties, mock(LearningTraceService.class), new IdentityScope(), mapper,
                mock(LearningCardStageService.class), mock(LearningPersistenceService.class), mock(LearningCatalog.class), mock(LearningPlanningService.class));
    }
    private LearningSession session() {
        var s = new LearningSession(); s.setId(10L); s.setUserId(1L); s.setKnowledgeBaseId(2L); s.setAgentscopeSessionId("s"); s.setLearningGoal("synthetic goal"); return s;
    }
    private KnowledgePoint point(String status) {
        var p = new KnowledgePoint(); p.setId(20L); p.setStatus(status); p.setTopic("synthetic topic"); p.setSubtopicsJson("[]"); return p;
    }
    private LearningTurn turn() {
        var t = new LearningTurn(); t.setId(30L); t.setTraceId("trace"); t.setUserMessage("explain synthetic topic"); return t;
    }
    private LearningContext saved(List<Msg> messages) {
        var c = new LearningContext(); c.setAgentStateJson(LearningContextMessages.stateJson("1", "s", messages)); return c;
    }
}

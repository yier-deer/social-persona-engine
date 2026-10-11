package com.socialpersona.matchmaker.controller;

import com.socialpersona.gateway.PythonClient;
import com.socialpersona.gateway.dto.MatchmakerRequest;
import com.socialpersona.gateway.dto.MatchmakerResponse;
import com.socialpersona.matchmaker.dto.MatchmakerChatRequest;
import com.socialpersona.matchmaker.dto.MatchmakerChatResponse;
import com.socialpersona.matchmaker.entity.MatchmakerSession;
import com.socialpersona.matchmaker.service.MatchmakerSessionService;
import com.socialpersona.persona.dto.PersonaConfigDTO;
import com.socialpersona.persona.entity.Persona;
import com.socialpersona.persona.repository.PersonaMapper;
import com.socialpersona.persona.service.PersonaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * MatchmakerController 单元测试 —— 验证牵线人 7 阶段流转
 *
 * 覆盖：
 *   1. start → 创建会话，返回 basic_profile
 *   2. status → 活跃/过期会话
 *   3. chat 单轮 → Python 调用 + stage 推进
 *   4. chat 7 阶段完整流转 → 最终 isComplete=true
 *   5. chat Python 错误 → 错误回复兜底
 *   6. confirm → 创建 Persona
 */
@ExtendWith(MockitoExtension.class)
public class MatchmakerControllerTest {

    @Mock private MatchmakerSessionService sessionService;
    @Mock private PersonaService personaService;
    @Mock private PersonaMapper personaMapper;
    @Mock private PythonClient pythonClient;

    @InjectMocks
    private MatchmakerController controller;

    private MatchmakerSession testSession;

    @BeforeEach
    public void setUp() {
        testSession = new MatchmakerSession();
        testSession.setSessionId("session-1");
        testSession.setCurrentStage("basic_profile");
        testSession.setCollectedDataJson("{}");
        testSession.setHistoryJson("[]");
        testSession.setStatus("in_progress");
    }

    // ==================== start ====================

    @Test
    public void testStartCreatesSession() {
        when(sessionService.createSession()).thenReturn(testSession);

        MatchmakerChatResponse resp = controller.start(new java.util.HashMap<>());

        assertEquals("session-1", resp.getSessionId());
        assertEquals("basic_profile", resp.getCurrentStage());
        assertFalse(resp.isComplete());
        assertNotNull(resp.getReply());
        verify(sessionService).createSession();
    }

    // ==================== status ====================

    @Test
    public void testStatusActiveSession() {
        when(sessionService.getSession("session-1")).thenReturn(testSession);

        MatchmakerChatResponse resp = controller.status("session-1");

        assertEquals("basic_profile", resp.getCurrentStage());
        assertFalse(resp.isComplete());
    }

    @Test
    public void testStatusExpiredSession() {
        when(sessionService.getSession("session-expired")).thenReturn(null);

        MatchmakerChatResponse resp = controller.status("session-expired");

        assertTrue(resp.getReply().contains("过期"));
    }

    // ==================== chat 单轮 ====================

    @Test
    public void testChatSingleRound() {
        when(sessionService.getSession("session-1")).thenReturn(testSession);

        MatchmakerResponse pyResp = new MatchmakerResponse();
        pyResp.setReply("好的，来聊聊你的喜好。");
        pyResp.setNextStage("style_anchor");
        pyResp.setExtractedData(Map.of("hobby", "读书"));
        pyResp.setIsComplete(false);
        when(pythonClient.matchmakerChat(any())).thenReturn(pyResp);

        doNothing().when(sessionService).advanceStage(anyString(), anyString(), anyMap());
        doNothing().when(sessionService).appendHistory(anyString(), anyString(), anyString());

        MatchmakerChatRequest req = new MatchmakerChatRequest();
        req.setSessionId("session-1");
        req.setUserMessage("我喜欢安静的女生");

        MatchmakerChatResponse resp = controller.chat(req);

        assertEquals("style_anchor", resp.getCurrentStage());
        assertFalse(resp.isComplete());
        assertEquals("好的，来聊聊你的喜好。", resp.getReply());
        assertNotNull(resp.getExtractedData());

        verify(pythonClient).matchmakerChat(any());
        verify(sessionService).advanceStage(eq("session-1"), eq("style_anchor"), anyMap());
    }

    @Test
    public void testChatInjectMatchmakerPersona() {
        when(sessionService.getSession("session-1")).thenReturn(testSession);

        MatchmakerResponse pyResp = new MatchmakerResponse();
        pyResp.setReply("好的。");
        pyResp.setNextStage("style_anchor");
        pyResp.setExtractedData(Map.of());
        pyResp.setIsComplete(false);
        when(pythonClient.matchmakerChat(any())).thenReturn(pyResp);

        doNothing().when(sessionService).appendHistory(anyString(), anyString(), anyString());
        doNothing().when(sessionService).advanceStage(anyString(), anyString(), anyMap());

        MatchmakerChatRequest req = new MatchmakerChatRequest();
        req.setSessionId("session-1");
        req.setUserMessage("你好");

        controller.chat(req);

        ArgumentCaptor<MatchmakerRequest> captor = ArgumentCaptor.forClass(MatchmakerRequest.class);
        verify(pythonClient).matchmakerChat(captor.capture());
        MatchmakerRequest sent = captor.getValue();
        assertNotNull(sent.getPersonaConfig(), "personaConfig 应被注入");
    }

    @Test
    public void testChatExpiredSession() {
        when(sessionService.getSession("session-expired")).thenReturn(null);

        MatchmakerChatRequest req = new MatchmakerChatRequest();
        req.setSessionId("session-expired");
        req.setUserMessage("你好");

        MatchmakerChatResponse resp = controller.chat(req);

        assertTrue(resp.getReply().contains("过期"));
        verify(pythonClient, never()).matchmakerChat(any());
    }

    // ==================== chat 7 阶段完整流转 ====================

    @Test
    public void testChatFullStageFlow() {
        String[] stages = {
                "basic_profile", "style_anchor", "boundary_probe",
                "attachment_explore", "system_detail", "sample_confirm"
        };

        // 构建 7 个 Python 响应：前 6 个推进，第 7 个完成
        List<MatchmakerResponse> pyResponses = new ArrayList<>();
        for (int i = 0; i < stages.length - 1; i++) {
            MatchmakerResponse r = new MatchmakerResponse();
            r.setReply("第 " + (i + 1) + " 阶段回复");
            r.setNextStage(stages[i + 1]);
            r.setExtractedData(Map.of("key_" + i, "val_" + i));
            r.setIsComplete(false);
            pyResponses.add(r);
        }
        MatchmakerResponse finalResp = new MatchmakerResponse();
        finalResp.setReply("最终确认");
        finalResp.setNextStage("sample_confirm");
        finalResp.setExtractedData(Map.of("final_key", "final_val"));
        finalResp.setIsComplete(true);
        finalResp.setPersonaConfig(Map.of("name", "小奈", "big_five_json", "{}"));
        pyResponses.add(finalResp);

        // 模拟 sessionService：每次返回当前阶段
        when(sessionService.getSession("session-1")).thenReturn(testSession);
        doNothing().when(sessionService).appendHistory(anyString(), anyString(), anyString());
        doAnswer(inv -> {
            testSession.setCurrentStage(inv.getArgument(1));
            return null;
        }).when(sessionService).advanceStage(anyString(), anyString(), anyMap());

        // Python 链式返回
        when(pythonClient.matchmakerChat(any()))
                .thenReturn(pyResponses.get(0), pyResponses.get(1), pyResponses.get(2),
                        pyResponses.get(3), pyResponses.get(4), pyResponses.get(5));

        MatchmakerChatRequest req = new MatchmakerChatRequest();
        req.setSessionId("session-1");

        // 执行 7 轮
        for (int i = 0; i < stages.length; i++) {
            req.setUserMessage("第 " + (i + 1) + " 轮回复");
            MatchmakerChatResponse resp = controller.chat(req);

            assertEquals(stages[Math.min(i + 1, stages.length - 1)], resp.getCurrentStage(),
                    "第 " + (i + 1) + " 轮后 stage 应为 " + stages[Math.min(i + 1, stages.length - 1)]);

            if (i == stages.length - 1) {
                assertTrue(resp.isComplete(), "最后一轮后应完成");
            } else {
                assertFalse(resp.isComplete(), "第 " + (i + 1) + " 轮不应完成");
            }
        }

        verify(pythonClient, times(stages.length)).matchmakerChat(any());
    }

    // ==================== chat Python 错误 ====================

    @Test
    public void testChatPythonErrorReturnsBadGateway() {
        when(sessionService.getSession("session-1")).thenReturn(testSession);
        doNothing().when(sessionService).appendHistory(anyString(), anyString(), anyString());
        when(pythonClient.matchmakerChat(any())).thenThrow(new RuntimeException("Python 超时"));

        MatchmakerChatRequest req = new MatchmakerChatRequest();
        req.setSessionId("session-1");
        req.setUserMessage("你好");

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> controller.chat(req));
        assertEquals(502, error.getStatusCode().value());
        verify(sessionService, never()).advanceStage(anyString(), anyString(), anyMap());
    }

    // ==================== confirm ====================

    @Test
    public void testConfirmCreatesPersona() {
        when(sessionService.getSession("session-1")).thenReturn(testSession);

        Persona testP = new Persona();
        testP.setId("persona-1");
        testP.setName("小奈");
        when(personaService.createPersona(any(PersonaConfigDTO.class), anyString(), nullable(String.class), anyString()))
                .thenReturn(testP);
        doNothing().when(sessionService).markCompleted(anyString(), anyString());

        MatchmakerChatResponse resp = controller.confirm("session-1", "test-master-key", null, null);

        assertTrue(resp.isComplete());
        assertEquals("persona-1", resp.getPersonaId());
        assertTrue(resp.getReply().contains("创建好了"));

        verify(personaService).createPersona(any(PersonaConfigDTO.class), anyString(), nullable(String.class), anyString());
        verify(personaService, never()).saveMasterKeyHash(any());
        verify(sessionService).markCompleted("session-1", "persona-1");
    }

    @Test
    public void testConfirmExpiredSession() {
        when(sessionService.getSession("session-nonexistent")).thenReturn(null);

        MatchmakerChatResponse resp = controller.confirm("session-nonexistent", "key", null, null);

        assertTrue(resp.getReply().contains("不存在"));
        verify(personaService, never()).createPersona(any(), anyString(), anyString(), anyString());
    }

    @Test
    public void confirmDoesNotRequireClientMasterKey() throws Exception {
        when(sessionService.getSession("session-1")).thenReturn(testSession);
        Persona created = new Persona();
        created.setId("persona-optional-key");
        when(personaService.createPersona(any(), anyString(), nullable(String.class), anyString())).thenReturn(created);

        MockMvcBuilders.standaloneSetup(controller).build().perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/matchmaker/confirm")
                        .param("sessionId", "session-1"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.complete").value(true));
        verify(personaService, never()).saveMasterKeyHash(any());
    }

    @Test
    public void chatRejectsSkippedStagesAndPrematureCompletion() {
        when(sessionService.getSession("session-1")).thenReturn(testSession);
        MatchmakerResponse response = new MatchmakerResponse();
        response.setReply("ready");
        response.setNextStage("sample_confirm");
        response.setIsComplete(true);
        when(pythonClient.matchmakerChat(any())).thenReturn(response);
        MatchmakerChatRequest request = new MatchmakerChatRequest();
        request.setSessionId("session-1");
        request.setUserMessage("hello");
        MatchmakerChatResponse result = controller.chat(request);
        assertEquals("basic_profile", result.getCurrentStage());
        assertFalse(result.isComplete());
    }
}

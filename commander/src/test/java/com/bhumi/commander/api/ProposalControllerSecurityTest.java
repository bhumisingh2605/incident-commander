
package com.bhumi.commander.api;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bhumi.commander.remediation.ActionExecutor;
import com.bhumi.commander.remediation.ProposalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@org.springframework.context.annotation.Import(com.bhumi.commander.TestPostgres.class)
@SpringBootTest(properties = {
        "COMMANDER_VIEWER_PASSWORD=test-viewer-password-123",
        "COMMANDER_OPERATOR_PASSWORD=test-operator-password-123",
        "COMMANDER_WEBHOOK_PASSWORD=test-webhook-password-123",
        "spring.ai.ollama.init.pull-model-strategy=never"
})
@AutoConfigureMockMvc
class ProposalControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProposalService proposals;

    @MockitoBean
    private ActionExecutor executor;

    @Test
    void unauthenticatedUserCannotListProposals() throws Exception {
        mockMvc.perform(get("/api/proposals"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(proposals, executor);
    }

    @Test
    void viewerCanListProposals() throws Exception {
        mockMvc.perform(get("/api/proposals")
                        .with(httpBasic("viewer", "test-viewer-password-123")))
                .andExpect(status().isOk());

        verifyNoInteractions(proposals, executor);
    }

    @Test
    void viewerCannotApproveProposal() throws Exception {
        mockMvc.perform(post("/api/proposals/123/approve")
                        .with(httpBasic("viewer", "test-viewer-password-123")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(proposals, executor);
    }

    @Test
    void viewerCannotRejectProposal() throws Exception {
        mockMvc.perform(post("/api/proposals/123/reject")
                        .with(httpBasic("viewer", "test-viewer-password-123")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(proposals, executor);
    }

    @Test
    void viewerCannotExecuteProposal() throws Exception {
        mockMvc.perform(post("/api/proposals/123/execute")
                        .with(httpBasic("viewer", "test-viewer-password-123")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(proposals, executor);
    }

    @Test
    void operatorCanAccessProposalList() throws Exception {
        mockMvc.perform(get("/api/proposals")
                        .with(httpBasic("operator", "test-operator-password-123")))
                .andExpect(status().isOk());

        verifyNoInteractions(proposals, executor);
    }
    @Test
    void debugEndpointsAreNotExposedByDefault() throws Exception {
        mockMvc.perform(get("/debug/health")
                        .param("service", "payment-service")
                        .with(httpBasic("operator", "test-operator-password-123")))
                .andExpect(status().isNotFound());
    }
}

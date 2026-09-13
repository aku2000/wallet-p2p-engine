package com.wallet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wallet.controller.HealthController;
import com.wallet.controller.TransferController;
import com.wallet.controller.WalletController;
import com.wallet.dto.CreateTransferRequest;
import com.wallet.metrics.WalletMetrics;
import com.wallet.model.Transfer;
import com.wallet.model.TransferStatus;
import com.wallet.model.Wallet;
import com.wallet.service.TransferService;
import com.wallet.service.WalletService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {WalletController.class, TransferController.class, HealthController.class})
@AutoConfigureMockMvc(addFilters = false) // Unit test endpoints directly without requiring filter chain
class WalletControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private WalletService walletService;

    @MockBean
    private TransferService transferService;

    @MockBean
    private WalletMetrics walletMetrics;

    @Test
    @DisplayName("POST /wallets creates or returns wallet")
    void shouldCreateOrReturnWallet() throws Exception {
        UUID walletId = UUID.randomUUID();
        Wallet wallet = new Wallet(walletId, "alice", 0, Instant.now(), Instant.now());
        when(walletService.getOrCreate("alice")).thenReturn(wallet);

        mockMvc.perform(post("/wallets")
                        .requestAttr("userId", "alice"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(walletId.toString()))
                .andExpect(jsonPath("$.user_id").value("alice"))
                .andExpect(jsonPath("$.balance").value(0));
    }

    @Test
    @DisplayName("GET /wallets/{id} returns wallet details")
    void shouldGetWallet() throws Exception {
        UUID walletId = UUID.randomUUID();
        Wallet wallet = new Wallet(walletId, "alice", 50000, Instant.now(), Instant.now());
        when(walletService.getById(walletId)).thenReturn(wallet);

        mockMvc.perform(get("/wallets/" + walletId)
                        .requestAttr("userId", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(walletId.toString()))
                .andExpect(jsonPath("$.balance").value(50000));
    }

    @Test
    @DisplayName("POST /transfers executes transfer and returns 201")
    void shouldExecuteTransfer() throws Exception {
        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();

        Transfer completed = new Transfer(
                transferId,
                fromId,
                toId,
                1000,
                TransferStatus.COMPLETED,
                "key-123",
                "hash-123",
                "test transfer",
                null,
                Instant.now(),
                Instant.now()
        );

        when(transferService.executeTransfer(any(CreateTransferRequest.class), eq("alice")))
                .thenReturn(completed);

        CreateTransferRequest req = new CreateTransferRequest(
                fromId.toString(),
                toId.toString(),
                1000,
                "key-123",
                "test transfer"
        );

        mockMvc.perform(post("/transfers")
                        .requestAttr("userId", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(transferId.toString()))
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.amount_paise").value(1000));
    }

    @Test
    @DisplayName("GET /health returns 200")
    void healthCheckShouldBePublic() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("wallet-p2p-engine"));
    }
}


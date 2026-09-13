package com.wallet;

import com.wallet.dto.CreateTransferRequest;
import com.wallet.exception.ForbiddenException;
import com.wallet.exception.InvalidRequestException;
import com.wallet.exception.WalletNotFoundException;
import com.wallet.metrics.WalletMetrics;
import com.wallet.model.Wallet;
import com.wallet.repository.LedgerRepository;
import com.wallet.repository.TransferRepository;
import com.wallet.repository.WalletRepository;
import com.wallet.service.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock
    private WalletRepository walletRepository;
    @Mock
    private TransferRepository transferRepository;
    @Mock
    private LedgerRepository ledgerRepository;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private WalletMetrics metrics;

    private TransferService transferService;

    @BeforeEach
    void setUp() {
        transferService = new TransferService(
                walletRepository,
                transferRepository,
                ledgerRepository,
                transactionTemplate,
                metrics
        );
    }

    @Test
    @DisplayName("Should reject negative or zero amount with InvalidRequestException")
    void shouldRejectNegativeOrZeroAmount() {
        CreateTransferRequest request = new CreateTransferRequest(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                0, // invalid: zero
                "key-1",
                "test"
        );

        assertThrows(InvalidRequestException.class, () ->
                transferService.executeTransfer(request, "alice"));
    }

    @Test
    @DisplayName("Should reject self-transfer with InvalidRequestException")
    void shouldRejectSelfTransfer() {
        String walletId = UUID.randomUUID().toString();
        CreateTransferRequest request = new CreateTransferRequest(
                walletId,
                walletId, // same wallet
                5000,
                "key-1",
                "test"
        );

        assertThrows(InvalidRequestException.class, () ->
                transferService.executeTransfer(request, "alice"));
    }

    @Test
    @DisplayName("Should reject when caller does not own from_wallet with ForbiddenException")
    void shouldRejectUnownedWallet() {
        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();

        // Wallet belongs to 'bob', but caller is 'alice'
        Wallet fromWallet = new Wallet(fromId, "bob", 10000, Instant.now(), Instant.now());
        when(walletRepository.findById(fromId)).thenReturn(Optional.of(fromWallet));

        CreateTransferRequest request = new CreateTransferRequest(
                fromId.toString(),
                toId.toString(),
                5000,
                "key-1",
                "test"
        );

        assertThrows(ForbiddenException.class, () ->
                transferService.executeTransfer(request, "alice"));
    }

    @Test
    @DisplayName("Should reject when destination wallet not found with WalletNotFoundException")
    void shouldRejectMissingDestinationWallet() {
        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();

        Wallet fromWallet = new Wallet(fromId, "alice", 10000, Instant.now(), Instant.now());
        when(walletRepository.findById(fromId)).thenReturn(Optional.of(fromWallet));
        when(walletRepository.findById(toId)).thenReturn(Optional.empty()); // destination missing

        CreateTransferRequest request = new CreateTransferRequest(
                fromId.toString(),
                toId.toString(),
                5000,
                "key-1",
                "test"
        );

        assertThrows(WalletNotFoundException.class, () ->
                transferService.executeTransfer(request, "alice"));
    }
}


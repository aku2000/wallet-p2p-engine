package com.wallet.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class DatabaseConfig {

    /**
     * TransactionTemplate for programmatic transaction control in TransferService.
     *
     * We use TransactionTemplate (not @Transactional) for the transfer transaction
     * because we need to catch DuplicateKeyException OUTSIDE the transaction boundary.
     * With @Transactional, the exception would trigger rollback before our catch block.
     * With TransactionTemplate, we control the boundary explicitly and can catch the
     * exception after Spring has already rolled back the transaction.
     */
    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager txManager) {
        return new TransactionTemplate(txManager);
    }
}


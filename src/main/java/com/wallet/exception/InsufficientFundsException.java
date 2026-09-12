package com.wallet.exception;

public class InsufficientFundsException extends RuntimeException {
    private final long balance;
    private final long requested;

    public InsufficientFundsException(long balance, long requested) {
        super("Insufficient funds: balance=" + balance + " paise, requested=" + requested + " paise");
        this.balance = balance;
        this.requested = requested;
    }

    public long getBalance()   { return balance; }
    public long getRequested() { return requested; }
}


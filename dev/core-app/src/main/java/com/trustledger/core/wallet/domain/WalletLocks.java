package com.trustledger.core.wallet.domain;

/**
 * Port: does the current transaction hold the row lock of a wallet it loaded? Lets {@code WalletApi} refuse a
 * money movement on a wallet the caller forgot to lock.
 */
public interface WalletLocks {

    boolean isLockedForUpdate(Wallet wallet);
}

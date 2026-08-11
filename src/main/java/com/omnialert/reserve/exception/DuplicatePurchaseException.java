package com.omnialert.reserve.exception;

public class DuplicatePurchaseException extends RuntimeException {
    public DuplicatePurchaseException(String message) {
        super(message);
    }
}

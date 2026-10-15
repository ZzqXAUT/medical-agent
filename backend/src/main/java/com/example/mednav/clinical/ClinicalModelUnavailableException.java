package com.example.mednav.clinical;

public class ClinicalModelUnavailableException extends RuntimeException {

    public ClinicalModelUnavailableException(String message) {
        super(message);
    }

    public ClinicalModelUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

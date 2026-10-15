package com.example.mednav.location;

public class LocationException extends RuntimeException {

    private final String code;
    private final boolean invalidInput;

    public LocationException(String code, String message, boolean invalidInput) {
        super(message);
        this.code = code;
        this.invalidInput = invalidInput;
    }

    public LocationException(String code, String message, boolean invalidInput, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.invalidInput = invalidInput;
    }

    public String code() {
        return code;
    }

    public boolean invalidInput() {
        return invalidInput;
    }
}

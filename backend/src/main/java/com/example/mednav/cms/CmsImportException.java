package com.example.mednav.cms;

public class CmsImportException extends RuntimeException {

    private final String code;

    public CmsImportException(String code, String message) {
        super(message);
        this.code = code;
    }

    public CmsImportException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}

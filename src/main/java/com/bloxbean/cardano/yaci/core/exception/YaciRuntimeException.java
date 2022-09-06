package com.bloxbean.cardano.yaci.core.exception;

public class YaciRuntimeException extends RuntimeException {

    public YaciRuntimeException(String msg) {
        super(msg);
    }

    public YaciRuntimeException(String msg, Exception e) {
        super(msg, e);
    }
}

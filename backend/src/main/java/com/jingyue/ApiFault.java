package com.jingyue;

public class ApiFault extends RuntimeException {
    public final int status;
    public final String code;
    public ApiFault(int status,String code,String message) { super(message); this.status=status; this.code=code; }
}

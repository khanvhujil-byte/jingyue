package com.jingyue;

import java.util.*;

public final class Identity {
    private static final ThreadLocal<Map<String,Object>> CURRENT=new ThreadLocal<>();
    private Identity() {}
    static void set(Map<String,Object> user) { CURRENT.set(user); }
    static void clear() { CURRENT.remove(); }
    public static Map<String,Object> current() { return CURRENT.get(); }
    public static long id() {
        Map<String,Object> user=current();
        if(user==null) throw new ApiFault(401,"LOGIN_REQUIRED","请先登录");
        return Values.number(user,"id");
    }
    public static void role(String expected) {
        id(); if(!expected.equals(Values.text(current(),"role"))) throw new ApiFault(403,"FORBIDDEN","当前账号没有此操作权限");
    }
}

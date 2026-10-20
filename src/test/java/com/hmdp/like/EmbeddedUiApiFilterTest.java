package com.hmdp.like;

import com.hmdp.config.EmbeddedUiApiFilter;
import com.hmdp.utils.LoginInterceptor;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;

class EmbeddedUiApiFilterTest {
    @Test void forwardsApiWithOriginalMethodIdentityAndQuery() throws Exception {
        MockHttpServletRequest req=new MockHttpServletRequest("PUT","/api/blog/like/4");
        req.addHeader("authorization","test-token"); req.setQueryString("liked=false");
        MockHttpServletResponse res=new MockHttpServletResponse();
        new EmbeddedUiApiFilter().doFilter(req,res,new MockFilterChain());
        assertEquals("/blog/like/4",res.getForwardedUrl());
        assertEquals("PUT",req.getMethod()); assertEquals("test-token",req.getHeader("authorization"));
        assertEquals("liked=false",req.getQueryString());
    }
    @Test void publicStaticRequestsKeepTheirOriginalRoute() throws Exception {
        MockHttpServletRequest req=new MockHttpServletRequest("GET","/index.html");
        MockHttpServletResponse res=new MockHttpServletResponse(); MockFilterChain chain=new MockFilterChain();
        new EmbeddedUiApiFilter().doFilter(req,res,chain);
        assertSame(req,chain.getRequest()); assertNull(res.getForwardedUrl());
    }
    @Test void staticAssetsArePublicWhileBusinessHandlersStillRequireLogin() throws Exception {
        LoginInterceptor login=new LoginInterceptor();
        assertTrue(login.preHandle(new MockHttpServletRequest("GET","/js/common.js"),
                new MockHttpServletResponse(),new ResourceHttpRequestHandler()));
        MockHttpServletResponse protectedResponse=new MockHttpServletResponse();
        assertFalse(login.preHandle(new MockHttpServletRequest("PUT","/blog/like/4"),protectedResponse,new Object()));
        assertEquals(401,protectedResponse.getStatus());
    }
}

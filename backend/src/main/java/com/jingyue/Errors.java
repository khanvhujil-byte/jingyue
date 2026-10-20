package com.jingyue;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import javax.servlet.http.*;
import java.io.IOException;
import org.slf4j.*;

@RestControllerAdvice
public class Errors {
    private static final Logger LOG=LoggerFactory.getLogger(Errors.class);
    @ExceptionHandler(ApiFault.class) public void business(ApiFault e,HttpServletRequest req,HttpServletResponse res) throws IOException {
        AuthFilter.error(res,e,String.valueOf(req.getAttribute("requestId")));
    }
    @ExceptionHandler({MethodArgumentTypeMismatchException.class,HttpMessageNotReadableException.class,IllegalArgumentException.class,org.springframework.web.multipart.MaxUploadSizeExceededException.class})
    public void parameter(Exception e,HttpServletRequest req,HttpServletResponse res) throws IOException {
        business(new ApiFault(400,"INVALID_PARAMETER","请求参数不正确"),req,res);
    }
    @ExceptionHandler(com.hmdp.limiter.exception.RateLimitException.class)
    public void limit(Exception e,HttpServletRequest req,HttpServletResponse res) throws IOException {
        business(new ApiFault(429,"RATE_LIMITED","操作较频繁，请稍后重试"),req,res);
    }
    @ExceptionHandler(Exception.class) public void other(Exception e,HttpServletRequest req,HttpServletResponse res) throws IOException {
        // Do not log request bodies, Authorization headers or ticket credentials.
        LOG.error("请求处理异常 requestId={} type={}",req.getAttribute("requestId"),e.getClass().getName());
        business(new ApiFault(503,"SERVICE_UNAVAILABLE","处理结果暂未确认，请查询原请求"),req,res);
    }
}

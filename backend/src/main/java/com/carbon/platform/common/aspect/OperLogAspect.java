package com.carbon.platform.common.aspect;

import com.carbon.platform.common.annotation.OperLog;
import com.carbon.platform.common.util.SecurityUtils;
import com.carbon.platform.system.entity.SysOperLog;
import com.carbon.platform.system.mapper.SysOperLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.Arrays;

/**
 * 操作日志切面：拦截 {@link OperLog} 注解的方法，落库审计留痕。
 *
 * <p>注意：审计写入采用异步 + 异常吞没策略——<b>审计失败绝不能影响主业务</b>，
 * 但会打 error 日志以便排查。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class OperLogAspect {

    private static final int MAX_TEXT = 4000;

    private final SysOperLogMapper operLogMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Around("@annotation(operLog)")
    public Object around(ProceedingJoinPoint pjp, OperLog operLog) throws Throwable {
        long start = System.currentTimeMillis();
        Object result = null;
        Throwable error = null;
        try {
            result = pjp.proceed();
            return result;
        } catch (Throwable t) {
            error = t;
            throw t;
        } finally {
            try {
                save(pjp, operLog, result, error, System.currentTimeMillis() - start);
            } catch (Exception e) {
                log.error("审计日志写入失败（不影响业务）", e);
            }
        }
    }

    @Async
    protected void save(ProceedingJoinPoint pjp, OperLog operLog, Object result,
                        Throwable error, long costMs) {
        SysOperLog logEntity = new SysOperLog();
        logEntity.setTitle(operLog.title());
        logEntity.setBusinessType(operLog.businessType().getLabel());
        logEntity.setCostMs(costMs);
        logEntity.setOperTime(LocalDateTime.now());
        logEntity.setOperName(SecurityUtils.currentUsername());

        MethodSignature sig = (MethodSignature) pjp.getSignature();
        logEntity.setMethod(sig.getDeclaringTypeName() + "." + sig.getName());

        HttpServletRequest req = currentRequest();
        if (req != null) {
            logEntity.setRequestMethod(req.getMethod());
            logEntity.setOperUrl(truncate(req.getRequestURI(), 255));
            logEntity.setOperIp(clientIp(req));
        }

        if (operLog.saveParam()) {
            logEntity.setOperParam(truncate(toJson(sanitizeArgs(pjp.getArgs())), MAX_TEXT));
        }
        if (operLog.saveResult() && result != null) {
            logEntity.setJsonResult(truncate(toJson(result), MAX_TEXT));
        }

        if (error != null) {
            logEntity.setStatus(1);
            logEntity.setErrorMsg(truncate(error.getMessage(), MAX_TEXT));
        } else {
            logEntity.setStatus(0);
        }
        operLogMapper.insert(logEntity);
    }

    /** 剔除无法序列化的参数（文件流、请求对象等） */
    private Object[] sanitizeArgs(Object[] args) {
        if (args == null) {
            return new Object[0];
        }
        return Arrays.stream(args).map(a -> {
            if (a == null) {
                return null;
            }
            if (a instanceof MultipartFile || a instanceof HttpServletRequest
                    || a instanceof jakarta.servlet.http.HttpServletResponse) {
                return "[" + a.getClass().getSimpleName() + "]";
            }
            return a;
        }).toArray();
    }

    private HttpServletRequest currentRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        return attrs instanceof ServletRequestAttributes sra ? sra.getRequest() : null;
    }

    private String clientIp(HttpServletRequest req) {
        String[] headers = {"X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP"};
        for (String h : headers) {
            String v = req.getHeader(h);
            if (v != null && !v.isBlank() && !"unknown".equalsIgnoreCase(v)) {
                return truncate(v.split(",")[0].trim(), 64);
            }
        }
        return truncate(req.getRemoteAddr(), 64);
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}

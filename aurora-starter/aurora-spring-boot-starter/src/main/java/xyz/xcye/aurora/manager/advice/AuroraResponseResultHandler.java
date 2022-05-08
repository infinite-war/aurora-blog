package xyz.xcye.aurora.manager.advice;

import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import xyz.xcye.aurora.manager.json.FieldFilterSerializer;
import xyz.xcye.core.annotaion.FieldFilter;
import xyz.xcye.core.annotaion.controller.ModifyOperation;
import xyz.xcye.core.annotaion.controller.ResponseRealResult;
import xyz.xcye.core.annotaion.controller.SelectOperation;
import xyz.xcye.core.entity.ExceptionResultEntity;
import xyz.xcye.core.entity.R;
import xyz.xcye.core.enums.ResponseStatusCodeEnum;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * 对所有响应的数据进行包装处理
 * @author qsyyke
 */

@Slf4j
@ControllerAdvice
public class AuroraResponseResultHandler implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object responseBody, MethodParameter methodParameter, MediaType selectedContentType, Class<? extends HttpMessageConverter<?>> selectedConverterType, ServerHttpRequest request, ServerHttpResponse response) {
        //response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        //执行的方法
        Method method = methodParameter.getMethod();

        assert method != null;
        //判断是否存在SelectOperation注解，也就是查询操作
        boolean hasSelectOperationAnnotation = method.isAnnotationPresent(SelectOperation.class);
        boolean hasModifyOperationAnnotation = method.isAnnotationPresent(ModifyOperation.class);
        boolean hasResponseRealResultAnnotation = method.isAnnotationPresent(ResponseRealResult.class);

        if (responseBody instanceof R) {
            return fieldFilter(responseBody, method);
        }

        /**
         * 获取方法或者异常的返回值类型
         * 这里如果没有发生异常是通过注解进行判断，如果存在异常，因为做了全局处理，返回值是ExceptionResultEntity
         */
        Class<?> resultReturnType = method.getReturnType();
        if (hasSelectOperationAnnotation) {
            // 存在注解，没有发生异常，对结果封装
            R r = R.success(ResponseStatusCodeEnum.SUCCESS.getCode(),
                    ResponseStatusCodeEnum.SUCCESS.getMessage(), responseBody, true);
            // 需要R对象整体传入
            return fieldFilter(r, method);
        }

        if (hasModifyOperationAnnotation) {
            boolean operateStatus = false;
            // 通过判断返回结果，设置success的值
            if (responseBody instanceof Integer) {
                Integer influenceRows = (Integer) responseBody;
                operateStatus = influenceRows == 1;
            }
            return R.result(ResponseStatusCodeEnum.SUCCESS.getCode(),
                    ResponseStatusCodeEnum.SUCCESS.getMessage(), new HashMap<>(), operateStatus);
        }

        if (responseBody instanceof ExceptionResultEntity) {
            //发生了异常，对结果进行封装
            ExceptionResultEntity exceptionResultEntity = (ExceptionResultEntity) responseBody;

            Map<String,Object> errorMap = new HashMap<>();
            errorMap.put("errorUrl",exceptionResultEntity.getErrorUrl());
            if (exceptionResultEntity.getData() != null) {
                errorMap.put("additionalData",exceptionResultEntity.getData());
            }
            return R.failure(exceptionResultEntity.getCode(), exceptionResultEntity.getMessage(),errorMap);
        }

        if (hasResponseRealResultAnnotation) {
            // 不需要对结果进行封装，直接返回
            return responseBody;
        }

        return fieldFilter(responseBody,method);
    }

    /**
     * 进行字段过滤，对于返回值是ModifyResult的对象，不做任何处理，因为没有需要过滤的字段，对于查询操作，因为返回的是一个
     * VO层实体，会存在敏感字段，这里根据当前登录用户的权限进行判断，如果拥有查看敏感字段的权限，或者是管理员，那么不做脱敏处理
     * 返回过滤该字段
     * @param responseBody
     * @param method
     * @return
     */
    private Object fieldFilter(Object responseBody, Method method) {
        // 判断是否有FieldFilter注解(判断当前登录用户是否具有查看密码的权限，动态过滤某个字段)
        boolean hasFieldFilterAnnotation = method.isAnnotationPresent(FieldFilter.class);

        if (hasFieldFilterAnnotation) {
            FieldFilter fieldFilterAnnotation = method.getAnnotation(FieldFilter.class);
            // 需要排除的字段集合
            String[] excludeFields = fieldFilterAnnotation.excludeFields();
            // 序列化的class
            Class<?> serializerClass = fieldFilterAnnotation.value();

            // 过滤字段
            FieldFilterSerializer filterSerializer = new FieldFilterSerializer();
            filterSerializer.filter(serializerClass,excludeFields);
            try {
                String json = filterSerializer.toJSONString(responseBody);
                responseBody = JSON.parse(json);
            } catch (JsonProcessingException e) {
                log.warn("对象转json出错了:{},出错原因:{}",responseBody,e.getMessage());
            }
        }

        if (responseBody == null) {
            return R.success();
        }else {
            return responseBody;
        }
    }
}

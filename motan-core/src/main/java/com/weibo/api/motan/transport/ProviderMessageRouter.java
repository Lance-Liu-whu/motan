/*
 *  Copyright 2009-2016 Weibo, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.weibo.api.motan.transport;

import com.weibo.api.motan.common.URLParamType;
import com.weibo.api.motan.core.extension.ExtensionLoader;
import com.weibo.api.motan.exception.MotanBizException;
import com.weibo.api.motan.exception.MotanFrameworkException;
import com.weibo.api.motan.exception.MotanServiceException;
import com.weibo.api.motan.protocol.rpc.CompressRpcCodec;
import com.weibo.api.motan.rpc.*;
import com.weibo.api.motan.serialize.DeserializableObject;
import com.weibo.api.motan.util.LoggerUtil;
import com.weibo.api.motan.util.MotanFrameworkUtil;
import com.weibo.api.motan.util.ReflectUtil;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * service 消息处理
 * <p>
 * <pre>
 * 		1） 多个service的支持
 * 		2） 区分service的方式： group/interface/version
 * </pre>
 *
 * @author maijunsheng
 * @version 创建时间：2013-6-4
 */
public class ProviderMessageRouter implements MessageHandler {
    protected Map<String, Provider<?>> providers = new HashMap<>();

    // 所有暴露出去的方法计数
    // 比如：messageRouter 里面涉及2个Service: ServiceA 有5个public method，ServiceB
    // 有10个public method，那么就是15
    protected AtomicInteger methodCounter = new AtomicInteger(0);

    protected ProviderProtectedStrategy strategy;

    public ProviderMessageRouter() {
        strategy = ExtensionLoader.getExtensionLoader(ProviderProtectedStrategy.class).getExtension(URLParamType.providerProtectedStrategy.getValue());
        strategy.setMethodCounter(methodCounter);
    }

    public ProviderMessageRouter(URL url) {
        String providerProtectedStrategy = url.getParameter(URLParamType.providerProtectedStrategy.getName(), URLParamType.providerProtectedStrategy.getValue());
        strategy = ExtensionLoader.getExtensionLoader(ProviderProtectedStrategy.class).getExtension(providerProtectedStrategy);
        strategy.setMethodCounter(methodCounter);
    }

    public ProviderMessageRouter(Provider<?> provider) {
        addProvider(provider);
    }

    @Override
    public Object handle(Channel channel, Object message) {
        if (channel == null || message == null) {
            throw new MotanFrameworkException("RequestRouter handler(channel, message) params is null");
        }

        if (!(message instanceof Request)) {
            throw new MotanFrameworkException("RequestRouter message type not support: " + message.getClass());
        }

        Request request = (Request) message;
        String serviceKey = MotanFrameworkUtil.getServiceKey(request);
        Provider<?> provider = providers.get(serviceKey);

        // 兼容模式。TODO：可以增加是否启用兼容的配置项
        if (provider == null) {
            provider = providers.get(request.getInterfaceName());
        }
        if (provider == null) {
            String errInfo = this.getClass().getSimpleName() + " handler Error: provider not exist serviceKey=" + serviceKey + " "
                    + MotanFrameworkUtil.toStringWithRemoteIp(request);
            LoggerUtil.error(errInfo);
            MotanServiceException exception = new MotanServiceException(errInfo);
            DefaultResponse response = MotanFrameworkUtil.buildErrorResponse(request, exception);
            return response;
        }
        Method method = provider.lookupMethod(request.getMethodName(), request.getParamtersDesc());
        fillParamDesc(request, method);
        processLazyDeserialize(request, method);
        Response response = call(request, provider);
        response.setSerializeNumber(request.getSerializeNumber());
        response.setRpcProtocolVersion(request.getRpcProtocolVersion());
        return response;
    }

    protected Response call(Request request, Provider<?> provider) {
        try {
            return strategy.call(request, provider);
        } catch (Exception e) {
            return MotanFrameworkUtil.buildErrorResponse(request, new MotanBizException("provider call process error", e));
        }
    }

    private void processLazyDeserialize(Request request, Method method) {
        if (method != null && request.getArguments() != null && request.getArguments().length == 1
                && request.getArguments()[0] instanceof DeserializableObject
                && request instanceof DefaultRequest) {
            try {
                Object[] args = ((DeserializableObject) request.getArguments()[0]).deserializeMulti(method.getParameterTypes());
                ((DefaultRequest) request).setArguments(args);
            } catch (IOException e) {
                throw new MotanFrameworkException("deserialize parameters fail: " + request.toString() + ", error:" + e.getMessage());
            }
        }
    }

    private void fillParamDesc(Request request, Method method) {
        if (method != null && StringUtils.isBlank(request.getParamtersDesc())
                && request instanceof DefaultRequest) {
            DefaultRequest dr = (DefaultRequest) request;
            dr.setParamtersDesc(ReflectUtil.getMethodParamDesc(method));
            dr.setMethodName(method.getName());
        }
    }

    public synchronized void addProvider(Provider<?> provider) {
        String serviceKey = MotanFrameworkUtil.getServiceKey(provider.getUrl());
        if (providers.containsKey(serviceKey)) {
            throw new MotanFrameworkException("provider alread exist: " + serviceKey);
        }

        providers.put(serviceKey, provider);
        //兼容模式仅作为特殊情况下的兜底，key重复时直接覆盖
        providers.put(provider.getUrl().getPath(), provider);

        // 获取该service暴露的方法数：
        List<Method> methods = ReflectUtil.getPublicMethod(provider.getInterface());
        CompressRpcCodec.putMethodSign(provider, methods);// 对所有接口方法生成方法签名。适配方法签名压缩调用方式。

        int publicMethodCount = methods.size();
        methodCounter.addAndGet(publicMethodCount);

        LoggerUtil.info("RequestRouter addProvider: url=" + provider.getUrl() + " all_public_method_count=" + methodCounter.get());
    }

    public synchronized void removeProvider(Provider<?> provider) {
        String serviceKey = MotanFrameworkUtil.getServiceKey(provider.getUrl());

        providers.remove(serviceKey);
        providers.remove(provider.getUrl().getPath());
        List<Method> methods = ReflectUtil.getPublicMethod(provider.getInterface());
        int publicMethodCount = methods.size();
        methodCounter.getAndSet(methodCounter.get() - publicMethodCount);

        LoggerUtil.info("RequestRouter removeProvider: url=" + provider.getUrl() + " all_public_method_count=" + methodCounter.get());
    }

    public int getPublicMethodCount() {
        return methodCounter.get();
    }

}

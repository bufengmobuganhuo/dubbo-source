/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.event;

import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.extension.SPI;

import java.util.concurrent.Executor;

/**
 * {@link Event Dubbo Event} Dispatcher
 *
 * @see Event
 * @see EventListener
 * @see DirectEventDispatcher
 * @since 2.7.5
 */
@SPI("direct")
public interface EventDispatcher extends Listenable<EventListener<?>> {

    // 该线程池用于"串行"(同步的方式)调用被触发的EventListener，也就是direct模式
    Executor DIRECT_EXECUTOR = Runnable::run;

    // 将被触发的事件分发给相应的EventListener对象
    void dispatch(Event event);

    // 获取direct模式中使用的线程池
    default Executor getExecutor() {
        return DIRECT_EXECUTOR;
    }

    // 工具方法，用于获取EventDispatcher接口的默认实现
    static EventDispatcher getDefaultExtension() {
        return ExtensionLoader.getExtensionLoader(EventDispatcher.class).getDefaultExtension();
    }
}

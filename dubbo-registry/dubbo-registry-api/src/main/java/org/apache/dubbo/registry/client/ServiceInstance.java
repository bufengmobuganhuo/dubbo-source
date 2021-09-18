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
package org.apache.dubbo.registry.client;

import java.io.Serializable;
import java.util.Map;

/**
 * The model class of an instance of a service, which is used for service registration and discovery.
 * <p>
 *
 * @since 2.7.5
 */
public interface ServiceInstance extends Serializable {

    // 服务的唯一标识
    String getId();

    // 当前服务实例对应的服务名称（一种服务只有一个名称）
    String getServiceName();

    // 服务实例的host
    String getHost();

    // 服务实例的port
    Integer getPort();

    // 当前服务实例的状态
    default boolean isEnabled() {
        return true;
    }

    // 检测当前服务实例的状态
    default boolean isHealthy() {
        return true;
    }

    // 获取当前服务实例关联的元数据
    Map<String, String> getMetadata();

    // 计算当前服务实例的hashCode值
    int hashCode();

    // 比较两个服务实例
    boolean equals(Object another);

}

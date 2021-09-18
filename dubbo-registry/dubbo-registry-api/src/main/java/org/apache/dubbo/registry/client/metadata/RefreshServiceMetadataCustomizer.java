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
package org.apache.dubbo.registry.client.metadata;

import org.apache.dubbo.metadata.WritableMetadataService;
import org.apache.dubbo.registry.client.ServiceInstance;
import org.apache.dubbo.registry.client.ServiceInstanceCustomizer;

import static org.apache.dubbo.metadata.WritableMetadataService.getExtension;
import static org.apache.dubbo.registry.client.metadata.ServiceInstanceMetadataUtils.getExportedServicesRevision;
import static org.apache.dubbo.registry.client.metadata.ServiceInstanceMetadataUtils.getMetadataStorageType;
import static org.apache.dubbo.registry.client.metadata.ServiceInstanceMetadataUtils.getSubscribedServicesRevision;

/**
 * An {@link ServiceInstanceCustomizer} to refresh metadata.
 */
public class RefreshServiceMetadataCustomizer implements ServiceInstanceCustomizer {

    // 优先级最低，所以最后执行
    public int getPriority() {
        return MIN_PRIORITY;
    }

    @Override
    public void customize(ServiceInstance serviceInstance) {
        // 从ServiceInstance对象的metadata集合中获取当前ServiceInstance存储元数据的方式（local还是remote）
        String metadataStoredType = getMetadataStorageType(serviceInstance);
        WritableMetadataService writableMetadataService = getExtension(metadataStoredType);
        // 从ServiceInstance.metadata集合中获取两个revision，并调用refreshMetadata()方法进行更新
        writableMetadataService.refreshMetadata(getExportedServicesRevision(serviceInstance),
                getSubscribedServicesRevision(serviceInstance));
    }
}

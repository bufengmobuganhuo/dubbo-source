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
package org.apache.dubbo.metadata.report;


import org.apache.dubbo.common.URL;
import org.apache.dubbo.metadata.definition.model.ServiceDefinition;
import org.apache.dubbo.metadata.report.identifier.MetadataIdentifier;
import org.apache.dubbo.metadata.report.identifier.ServiceMetadataIdentifier;
import org.apache.dubbo.metadata.report.identifier.SubscriberMetadataIdentifier;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 *
 */
public interface MetadataReport {
    // 存储Provider元数据
    void storeProviderMetadata(MetadataIdentifier providerMetadataIdentifier, ServiceDefinition serviceDefinition);
    // 存储Consumer元数据
    void storeConsumerMetadata(MetadataIdentifier consumerMetadataIdentifier, Map<String, String> serviceParameterMap);
    // 存储、删除Service元数据
    void saveServiceMetadata(ServiceMetadataIdentifier metadataIdentifier, URL url);
    void removeServiceMetadata(ServiceMetadataIdentifier metadataIdentifier);
    // 查询暴露的URL
    List<String> getExportedURLs(ServiceMetadataIdentifier metadataIdentifier);
    // 查询、存储订阅数据
    List<String> getSubscribedURLs(SubscriberMetadataIdentifier subscriberMetadataIdentifier);
    void saveSubscribedData(SubscriberMetadataIdentifier subscriberMetadataIdentifier, Set<String> urls);
    // 查询ServiceDefinition
    String getServiceDefinition(MetadataIdentifier metadataIdentifier);
}

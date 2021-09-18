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
package org.apache.dubbo.metadata;

import org.apache.dubbo.common.URL;

import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.util.Collections.unmodifiableSortedSet;
import static java.util.stream.StreamSupport.stream;

/**
 * A framework interface of Dubbo Metadata Service defines the contract of Dubbo Services registartion and subscription
 * between Dubbo service providers and its consumers. The implementation will be exported as a normal Dubbo service that
 * the clients would subscribe, whose version comes from the {@link #version()} method and group gets from
 * {@link #serviceName()}, that means, The different Dubbo service(application) will export the different
 * {@link MetadataService} that persists all the exported and subscribed metadata, they are present by
 * {@link #getExportedURLs()} and {@link #getSubscribedURLs()} respectively. What's more, {@link MetadataService}
 * also providers the fine-grain methods for the precise queries.
 *
 * @see WritableMetadataService
 * @since 2.7.5
 */
public interface MetadataService {

    //FIXME the value is default, it was used by testing temporarily
    static final String DEFAULT_EXTENSION = "default";

    /**
     * The value of all service names
     */
    String ALL_SERVICE_NAMES = "*";

    /**
     * The value of All service instances
     */
    String ALL_SERVICE_INTERFACES = "*";

    /**
     * The service interface name of {@link MetadataService}
     */
    String SERVICE_INTERFACE_NAME = MetadataService.class.getName();

    /**
     * The contract version of {@link MetadataService}, the future update must make sure compatible.
     */
    String VERSION = "1.0.0";

    // 获取当前ServiceInstance所属服务的名称
    String serviceName();

    // 获取当前MetadataService接口的版本
    default String version() {
        return VERSION;
    }

    // 获取当前ServiceInstance订阅的全部URL
    default SortedSet<String> getSubscribedURLs(){
        throw new UnsupportedOperationException("This operation is not supported for consumer.");
    }

    // 获取当前ServiceInstance发布的全部URL
    default SortedSet<String> getExportedURLs() {
        return getExportedURLs(ALL_SERVICE_INTERFACES);
    }

    // 根据服务接口查找当前ServiceInstance暴露的全部接口
    default SortedSet<String> getExportedURLs(String serviceInterface) {
        return getExportedURLs(serviceInterface, null);
    }

    // 根据服务接口和group两个条件查找当前ServiceInstance暴露的全部接口
    default SortedSet<String> getExportedURLs(String serviceInterface, String group) {
        return getExportedURLs(serviceInterface, group, null);
    }

    // 根据服务接口、group和version三个条件查找当前ServiceInstance暴露的全部接口
    default SortedSet<String> getExportedURLs(String serviceInterface, String group, String version) {
        return getExportedURLs(serviceInterface, group, version, null);
    }

    // 根据服务接口、group、version和protocol四个条件查找当前ServiceInstance暴露的全部接口
    SortedSet<String> getExportedURLs(String serviceInterface, String group, String version, String protocol);

    // 根据指定条件查询ServiceDefinition
    String getServiceDefinition(String interfaceName, String version, String group);

    /**
     * Interface definition.
     *
     * @return
     */
    String getServiceDefinition(String serviceKey);

    /**
     * Is the {@link URL} for the {@link MetadataService} or not?
     *
     * @param url {@link URL url}
     * @return
     */
    static boolean isMetadataServiceURL(URL url) {
        String serviceInterface = url.getServiceInterface();
        return SERVICE_INTERFACE_NAME.equals(serviceInterface);
    }

    /**
     * Convert the multiple {@link URL urls} to a {@link List list} of {@link URL urls}
     *
     * @param urls the strings presents the {@link URL Dubbo URLs}
     * @return non-null
     */
    static List<URL> toURLs(Iterable<String> urls) {
        return stream(urls.spliterator(), false)
                .map(URL::valueOf)
                .collect(Collectors.toList());
    }

    /**
     * Convert the specified {@link Iterable} of {@link URL URLs} to be the {@link URL#toFullString() strings} presenting
     * the {@link URL URLs}
     *
     * @param iterable {@link Iterable} of {@link URL}
     * @return the non-null read-only {@link SortedSet sorted set} of {@link URL#toFullString() strings} presenting
     * @see URL#toFullString()
     */
    static SortedSet<String> toSortedStrings(Iterable<URL> iterable) {
        return toSortedStrings(StreamSupport.stream(iterable.spliterator(), false));
    }

    /**
     * Convert the specified {@link Stream} of {@link URL URLs} to be the {@link URL#toFullString() strings} presenting
     * the {@link URL URLs}
     *
     * @param stream {@link Stream} of {@link URL}
     * @return the non-null read-only {@link SortedSet sorted set} of {@link URL#toFullString() strings} presenting
     * @see URL#toFullString()
     */
    static SortedSet<String> toSortedStrings(Stream<URL> stream) {
        return unmodifiableSortedSet(stream.map(URL::toFullString).collect(TreeSet::new, Set::add, Set::addAll));
    }
}

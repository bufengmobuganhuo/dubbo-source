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

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.extension.SPI;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.metadata.MetadataService;
import org.apache.dubbo.metadata.ServiceNameMapping;
import org.apache.dubbo.metadata.WritableMetadataService;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.Registry;
import org.apache.dubbo.registry.client.event.ServiceInstancesChangedEvent;
import org.apache.dubbo.registry.client.event.listener.ServiceInstancesChangedListener;
import org.apache.dubbo.registry.client.metadata.ServiceInstanceMetadataUtils;
import org.apache.dubbo.registry.client.metadata.SubscribedURLsSynthesizer;
import org.apache.dubbo.registry.client.metadata.proxy.MetadataServiceProxyFactory;
import org.apache.dubbo.registry.client.selector.ServiceInstanceSelector;
import org.apache.dubbo.registry.support.FailbackRegistry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;
import static java.util.Collections.unmodifiableSet;
import static java.util.stream.Collectors.toSet;
import static java.util.stream.Stream.of;
import static org.apache.dubbo.common.URLBuilder.from;
import static org.apache.dubbo.common.constants.CommonConstants.DUBBO_PROTOCOL;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PID_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PROTOCOL_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PROVIDER_SIDE;
import static org.apache.dubbo.common.constants.CommonConstants.SIDE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.TIMESTAMP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.PROVIDED_BY;
import static org.apache.dubbo.common.constants.RegistryConstants.REGISTRY_TYPE_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.SERVICE_REGISTRY_TYPE;
import static org.apache.dubbo.common.constants.RegistryConstants.SUBSCRIBED_SERVICE_NAMES_KEY;
import static org.apache.dubbo.common.extension.ExtensionLoader.getExtensionLoader;
import static org.apache.dubbo.common.function.ThrowableAction.execute;
import static org.apache.dubbo.common.utils.CollectionUtils.isEmpty;
import static org.apache.dubbo.common.utils.CollectionUtils.isNotEmpty;
import static org.apache.dubbo.common.utils.StringUtils.isBlank;
import static org.apache.dubbo.metadata.MetadataService.toURLs;
import static org.apache.dubbo.registry.client.ServiceDiscoveryFactory.getExtension;
import static org.apache.dubbo.registry.client.metadata.ServiceInstanceMetadataUtils.getExportedServicesRevision;
import static org.apache.dubbo.registry.client.metadata.ServiceInstanceMetadataUtils.getMetadataStorageType;
import static org.apache.dubbo.registry.client.metadata.ServiceInstanceMetadataUtils.getProtocolPort;

/**
 * Being different to the traditional registry, {@link ServiceDiscoveryRegistry} that is a new service-oriented
 * {@link Registry} based on {@link ServiceDiscovery}, it will not interact in the external registry directly,
 * but store the {@link URL urls} that Dubbo services exported and referenced into {@link WritableMetadataService}
 * when {@link #register(URL)} and {@link #subscribe(URL, NotifyListener)} methods are executed. After that the exported
 * {@link URL urls} can be get from {@link WritableMetadataService#getExportedURLs()} and its variant methods. In contrast,
 * {@link WritableMetadataService#getSubscribedURLs()} method offers the subscribed {@link URL URLs}.
 * <p>
 * Every {@link ServiceDiscoveryRegistry} object has its own {@link ServiceDiscovery} instance that was initialized
 * under {@link #ServiceDiscoveryRegistry(URL) the construction}. As the primary argument of constructor , the
 * {@link URL} of connection the registry decides what the kind of ServiceDiscovery is. Generally, each
 * protocol associates with a kind of {@link ServiceDiscovery}'s implementation if present, or the
 * {@link FileSystemServiceDiscovery} will be the default one. Obviously, it's also allowed to extend
 * {@link ServiceDiscovery} using {@link SPI the Dubbo SPI}.
 * <p>
 * In the {@link #subscribe(URL, NotifyListener) subscription phase}, the {@link ServiceDiscovery} instance will be used
 * to discovery the {@link ServiceInstance service instances} via the {@link ServiceDiscovery#getInstances(String)}.
 * However, the argument of this method requires the service name that the subscribed {@link URL} can't find, thus,
 * {@link ServiceNameMapping} will help to figure out one or more services that exported correlative Dubbo services. If
 * the service names can be found, the exported {@link URL URLs} will be get from the remote {@link MetadataService}
 * being deployed on all {@link ServiceInstance instances} of services. The whole process runs under the
 * {@link #subscribeURLs(URL, NotifyListener, String, Collection)} method. It's very expensive to invoke
 * {@link MetadataService} for each {@link ServiceInstance service instance}, thus {@link ServiceDiscoveryRegistry}
 * introduces a cache to optimize the calculation with "revisions". If the revisions of N
 * {@link ServiceInstance service instances} are same, {@link MetadataService} is invoked just only once, and then it
 * does return the exported {@link URL URLs} as a template by which others are
 * {@link #cloneExportedURLs(URL, Collection) cloned}.
 * <p>
 * In contrast, current {@link ServiceInstance service instance} will not be registered to the registry whether any
 * Dubbo service is exported or not.
 * <p>
 *
 * @see ServiceDiscovery
 * @see FailbackRegistry
 * @see WritableMetadataService
 * @since 2.7.5
 */
public class ServiceDiscoveryRegistry extends FailbackRegistry {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    private final ServiceDiscovery serviceDiscovery;

    private final Set<String> subscribedServices;

    private final ServiceNameMapping serviceNameMapping;

    private final WritableMetadataService writableMetadataService;

    private final Set<String> registeredListeners = new LinkedHashSet<>();

    private final List<SubscribedURLsSynthesizer> subscribedURLsSynthesizers;

    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * A cache for all URLs of services that the subscribed services exported
     * The key is the service name
     * The value is a nested {@link Map} whose key is the revision and value is all URLs of services
     */
    private final Map<String, Map<String, List<URL>>> serviceRevisionExportedURLsCache = new LinkedHashMap<>();

    public ServiceDiscoveryRegistry(URL registryURL) {
        // 初始化父类，其中包括FailbackRegistry中的时间轮和重试定时任务以及AbstractRegistry中的本地文件缓存等
        super(registryURL);
        // 初始化ServiceDiscovery对象
        this.serviceDiscovery = createServiceDiscovery(registryURL);
        // 从registryURL中解析出subscribed-services参数，并按照逗号切分，得到subscribedServices集合(当前订阅的服务名称)
        this.subscribedServices = parseServices(registryURL.getParameter(SUBSCRIBED_SERVICE_NAMES_KEY));
        // 获取DefaultServiceNameMapping对象
        this.serviceNameMapping = ServiceNameMapping.getDefaultExtension();
        // 初始化WritableMetadataService对象
        String metadataStorageType = getMetadataStorageType(registryURL);
        this.writableMetadataService = WritableMetadataService.getExtension(metadataStorageType);
        // 获取目前支持的全部SubscribedURLsSynthesizer实现（订阅服务的完整信息），并初始化
        this.subscribedURLsSynthesizers = initSubscribedURLsSynthesizers();
    }

    public ServiceDiscovery getServiceDiscovery() {
        return serviceDiscovery;
    }

    /**
     * Get the subscribed services from the specified registry {@link URL url}
     *
     * @param registryURL the specified registry {@link URL url}
     * @return non-null
     */
    public static Set<String> getSubscribedServices(URL registryURL) {
        String subscribedServiceNames = registryURL.getParameter(SUBSCRIBED_SERVICE_NAMES_KEY);
        return isBlank(subscribedServiceNames) ? emptySet() :
                unmodifiableSet(of(subscribedServiceNames.split(","))
                        .map(String::trim)
                        .filter(StringUtils::isNotEmpty)
                        .collect(toSet()));
    }

    /**
     * Create the {@link ServiceDiscovery} from the registry {@link URL}
     *
     * @param registryURL the {@link URL} to connect the registry
     * @return non-null
     */
    protected ServiceDiscovery createServiceDiscovery(URL registryURL) {
        // 根据registryURL获取对应的ServiceDiscovery实现
        ServiceDiscovery originalServiceDiscovery = getServiceDiscovery(registryURL);
        // ServiceDiscovery外层添加一层EventPublishingServiceDiscovery修饰器，
        // EventPublishingServiceDiscovery会在register()、initialize()等方法前后触发相应的事件，
        // 例如，在register()方法的前后分别会触发ServiceInstancePreRegisteredEvent和ServiceInstanceRegisteredEvent
        ServiceDiscovery serviceDiscovery = enhanceEventPublishing(originalServiceDiscovery);
        execute(() -> {
            // 初始化ServiceDiscovery
            serviceDiscovery.initialize(registryURL.addParameter(INTERFACE_KEY, ServiceDiscovery.class.getName())
                    .removeParameter(REGISTRY_TYPE_KEY));
        });
        return serviceDiscovery;
    }

    private List<SubscribedURLsSynthesizer> initSubscribedURLsSynthesizers() {
        ExtensionLoader<SubscribedURLsSynthesizer> loader = ExtensionLoader.getExtensionLoader(SubscribedURLsSynthesizer.class);
        return Collections.unmodifiableList(new ArrayList<>(loader.getSupportedExtensionInstances()));
    }

    /**
     * Get the instance {@link ServiceDiscovery} from the registry {@link URL} using
     * {@link ServiceDiscoveryFactory} SPI
     *
     * @param registryURL the {@link URL} to connect the registry
     * @return
     */
    private ServiceDiscovery getServiceDiscovery(URL registryURL) {
        ServiceDiscoveryFactory factory = getExtension(registryURL);
        return factory.getServiceDiscovery(registryURL);
    }

    /**
     * Enhance the original {@link ServiceDiscovery} with event publishing feature
     *
     * @param original the original {@link ServiceDiscovery}
     * @return {@link EventPublishingServiceDiscovery} instance
     */
    private ServiceDiscovery enhanceEventPublishing(ServiceDiscovery original) {
        return new EventPublishingServiceDiscovery(original);
    }

    protected boolean shouldRegister(URL providerURL) {

        String side = providerURL.getParameter(SIDE_KEY);

        boolean should = PROVIDER_SIDE.equals(side); // Only register the Provider.

        if (!should) {
            if (logger.isDebugEnabled()) {
                logger.debug(String.format("The URL[%s] should not be registered.", providerURL.toString()));
            }
        }

        return should;
    }

    protected boolean shouldSubscribe(URL subscribedURL) {
        return !shouldRegister(subscribedURL);
    }

    @Override
    public final void register(URL url) {
        if (!shouldRegister(url)) { // Should Not Register
            return;
        }
        // 父类FailbackRegistery会回调子类的doRegister()方法
        super.register(url);
    }

    @Override
    public void doRegister(URL url) {
        // 元数据发布到MetadataService
        if (writableMetadataService.exportURL(url)) {
            if (logger.isInfoEnabled()) {
                logger.info(format("The URL[%s] registered successfully.", url.toString()));
            }
        } else {
            if (logger.isWarnEnabled()) {
                logger.info(format("The URL[%s] has been registered.", url.toString()));
            }
        }
    }

    @Override
    public final void unregister(URL url) {
        if (!shouldRegister(url)) {
            return;
        }
        super.unregister(url);
    }

    @Override
    public void doUnregister(URL url) {
        if (writableMetadataService.unexportURL(url)) {
            if (logger.isInfoEnabled()) {
                logger.info(format("The URL[%s] deregistered successfully.", url.toString()));
            }
        } else {
            if (logger.isWarnEnabled()) {
                logger.info(format("The URL[%s] has been deregistered.", url.toString()));
            }
        }
    }

    @Override
    public final void subscribe(URL url, NotifyListener listener) {
        if (!shouldSubscribe(url)) { // Should Not Subscribe
            return;
        }
        // 父类FailbackRegistry会回调子类的doSubscribe()
        super.subscribe(url, listener);
    }

    @Override
    public void doSubscribe(URL url, NotifyListener listener) {
        subscribeURLs(url, listener);
    }

    protected void subscribeURLs(URL url, NotifyListener listener) {
        // 调用 WriteMetadataService.subscribeURL() 方法在 subscribedServiceURLs 集合中记录当前订阅的 URL
        writableMetadataService.subscribeURL(url);
        // 通过订阅的 URL 获取 Service Name
        Set<String> serviceNames = getServices(url);
        if (CollectionUtils.isEmpty(serviceNames)) {
            throw new IllegalStateException("Should has at least one way to know which services this interface belongs to, subscription url: " + url);
        }
        // 订阅
        serviceNames.forEach(serviceName -> subscribeURLs(url, listener, serviceName));

    }

    @Override
    public final void unsubscribe(URL url, NotifyListener listener) {
        if (!shouldSubscribe(url)) { // Should Not Subscribe
            return;
        }
        super.unsubscribe(url, listener);
    }

    @Override
    public void doUnsubscribe(URL url, NotifyListener listener) {
        writableMetadataService.unsubscribeURL(url);
    }

    @Override
    public boolean isAvailable() {
        return !serviceDiscovery.getServices().isEmpty();
    }

    @Override
    public void destroy() {
        super.destroy();
        execute(() -> {
            // stop ServiceDiscovery
            serviceDiscovery.destroy();
        });
    }

    protected void subscribeURLs(URL url, NotifyListener listener, String serviceName) {
        // 根据Service Name获取ServiceInstance对象
        List<ServiceInstance> serviceInstances = serviceDiscovery.getInstances(serviceName);
        // 订阅
        subscribeURLs(url, listener, serviceName, serviceInstances);

        // 注册 ServiceInstancesChangedListener 监听器
        registerServiceInstancesChangedListener(url, new ServiceInstancesChangedListener(serviceName) {

            @Override
            public void onEvent(ServiceInstancesChangedEvent event) {
                subscribeURLs(url, listener, event.getServiceName(), new ArrayList<>(event.getServiceInstances()));
            }
        });
    }

    /**
     * Subscribe the {@link URL URLs} that the specified service exported are
     * {@link #getExportedURLs(ServiceInstance) get} from {@link MetadataService} if present, or try to
     * be {@link #synthesizeSubscribedURLs(URL, Collection) synthesized} by
     * the instances of {@link SubscribedURLsSynthesizer}
     *
     * @param subscribedURL    the subscribed {@link URL url}
     * @param listener         {@link NotifyListener}
     * @param serviceName
     * @param serviceInstances
     * @see #getExportedURLs(URL, Collection)
     * @see #synthesizeSubscribedURLs(URL, Collection)
     */
    protected void subscribeURLs(URL subscribedURL, NotifyListener listener, String serviceName,
                                 Collection<ServiceInstance> serviceInstances) {

        if (isEmpty(serviceInstances)) {
            logger.warn(format("There is no instance in service[name : %s]", serviceName));
            return;
        }

        List<URL> subscribedURLs = new LinkedList<>();

        // 尝试通过MetadataService获取subscribedURL集合
        subscribedURLs.addAll(getExportedURLs(subscribedURL, serviceInstances));

        if (subscribedURLs.isEmpty()) { // If empty, try to synthesize
            // 尝试通过SubscribedURLsSynthesizer获取subscribedURL集合
            subscribedURLs.addAll(synthesizeSubscribedURLs(subscribedURL, serviceInstances));
        }

        listener.notify(subscribedURLs);
    }

    /**
     * Register the {@link ServiceInstancesChangedListener} If absent
     *
     * @param url      {@link URL}
     * @param listener the {@link ServiceInstancesChangedListener}
     */
    private void registerServiceInstancesChangedListener(URL url, ServiceInstancesChangedListener listener) {
        String listenerId = createListenerId(url, listener);
        if (registeredListeners.add(listenerId)) {
            serviceDiscovery.addServiceInstancesChangedListener(listener);
        }
    }

    private String createListenerId(URL url, ServiceInstancesChangedListener listener) {
        return listener.getServiceName() + ":" + url.toString(VERSION_KEY, GROUP_KEY, PROTOCOL_KEY);
    }

    /**
     * Get the exported {@link URL URLs} from the  {@link MetadataService} in the specified
     * {@link ServiceInstance service instances}
     *
     * @param subscribedURL the subscribed {@link URL url}
     * @param instances     {@link ServiceInstance service instances}
     * @return the exported {@link URL URLs} if present, or <code>{@link Collections#emptyList() empty list}</code>
     */
    private List<URL> getExportedURLs(URL subscribedURL, Collection<ServiceInstance> instances) {

        // local service instances could be mutable
        List<ServiceInstance> serviceInstances = instances.stream()
                .filter(ServiceInstance::isEnabled)
                .filter(ServiceInstance::isHealthy)
                .filter(ServiceInstanceMetadataUtils::isDubboServiceInstance)
                .collect(Collectors.toList());

        int size = serviceInstances.size();

        if (size == 0) {
            return emptyList();
        }

        // 从第一个ServiceInstance即可获取Service Name
        prepareServiceRevisionExportedURLs(serviceInstances);

        List<URL> subscribedURLs = cloneExportedURLs(subscribedURL, serviceInstances);

        // clear local service instances
        serviceInstances.clear();

        return subscribedURLs;
    }

    /**
     * Prepare the {@link #serviceRevisionExportedURLsCache} exclusively
     *
     * @param serviceInstances {@link ServiceInstance service instances}
     * @see #expungeStaleRevisionExportedURLs(List)
     * @see #initializeRevisionExportedURLs(List)
     */
    private void prepareServiceRevisionExportedURLs(List<ServiceInstance> serviceInstances) {
        executeExclusively(() -> {
            // 1. expunge stale
            expungeStaleRevisionExportedURLs(serviceInstances);
            // 2. Initialize
            initializeRevisionExportedURLs(serviceInstances);
        });
    }

    /**
     * Initialize the {@link URL URLs} that {@link ServiceInstance service instances} exported into
     * {@link #serviceRevisionExportedURLsCache the cache}.
     * <p>
     * Typically, the {@link URL URLs} that one {@link ServiceInstance service instance} exported can be get from
     * the same instances' {@link MetadataService}, but the cost is very expensive if there are a lot of instances
     * in this service. Thus, the exported {@link URL URls} should be cached  and stored into
     * {@link #serviceRevisionExportedURLsCache the cache}.
     * <p>
     * In most cases, {@link #serviceRevisionExportedURLsCache the cache} only holds a single list of exported URLs for
     * each service because there is no difference on the Dubbo services(interfaces) between the service instances.
     * However, if there are one or more upgrading or increasing Dubbo services that are deploying on the some of
     * instances, other instances still maintain the previous ones, in this way, there are two versions of the services,
     * they are called "revisions", in other words, one revision associates a list of exported URLs that can be reused
     * for other instances with same revision, and one service allows one or more revisions.
     *
     * @param serviceInstances {@link ServiceInstance service instances}
     */
    private void initializeRevisionExportedURLs(List<ServiceInstance> serviceInstances) {
        // initialize the revision exported URLs that the selected service instance exported
        initializeSelectedRevisionExportedURLs(serviceInstances);
        // initialize the revision exported URLs that other service instances exported
        serviceInstances.forEach(this::initializeRevisionExportedURLs);
    }

    /**
     * Initialize the {@link URL URLs} that the {@link #selectServiceInstance(List) selected service instance} exported
     * into {@link #serviceRevisionExportedURLsCache the cache}.
     *
     * @param serviceInstances {@link ServiceInstance service instances}
     */
    private void initializeSelectedRevisionExportedURLs(List<ServiceInstance> serviceInstances) {
        // Try to initialize revision exported URLs until success
        for (int i = 0; i < serviceInstances.size(); i++) {
            // select a instance of {@link ServiceInstance}
            ServiceInstance selectedInstance = selectServiceInstance(serviceInstances);
            List<URL> revisionExportedURLs = initializeRevisionExportedURLs(selectedInstance);
            if (isNotEmpty(revisionExportedURLs)) {    // If the result is valid
                break;
            }
        }
    }

    /**
     * Expunge the revision exported {@link URL URLs} in {@link #serviceRevisionExportedURLsCache the cache} if
     * some revisions of {@link ServiceInstance service instance} had been out of date possibly
     *
     * @param serviceInstances {@link ServiceInstance service instances}
     */
    private void expungeStaleRevisionExportedURLs(List<ServiceInstance> serviceInstances) {

        // 从第一个ServiceInstance即可获取Service Name
        String serviceName = serviceInstances.get(0).getServiceName();
        // 获取该Service Name当前在
        // serviceRevisionExportedURLsCache（Map<ServiceName, Map<Revision, ServiceName对应的最新URL集合>>）中对应的URL集合
        Map<String, List<URL>> revisionExportedURLsMap = getRevisionExportedURLsMap(serviceName);
        if (revisionExportedURLsMap.isEmpty()) { // 没有缓存任何URL，则无须后续清理操作，直接返回即可
            return;
        }

        // 获取Service Name在serviceRevisionExportedURLsCache中缓存的修订版本
        Set<String> existedRevisions = revisionExportedURLsMap.keySet(); // read-only
        // 从ServiceInstance中获取当前最新的修订版本
        Set<String> currentRevisions = serviceInstances.stream()
                .map(ServiceInstanceMetadataUtils::getExportedServicesRevision)
                .collect(Collectors.toSet());
        // 获取要删除的陈旧修订版本：staleRevisions = existedRevisions(copy) - currentRevisions
        Set<String> staleRevisions = new HashSet<>(existedRevisions);
        staleRevisions.removeAll(currentRevisions);
        // remove exported URLs if staled
        staleRevisions.forEach(revisionExportedURLsMap::remove);
    }

    /**
     * Clone the exported URLs that are based on {@link #getTemplateExportedURLs(URL, ServiceInstance) the template URLs}
     * from the some of {@link ServiceInstance service instances} with different revisions
     *
     * @param subscribedURL    the subscribed {@link URL url}
     * @param serviceInstances {@link ServiceInstance service instances}
     * @return non-null
     */
    private List<URL> cloneExportedURLs(URL subscribedURL, Collection<ServiceInstance> serviceInstances) {

        if (isEmpty(serviceInstances)) {
            return emptyList();
        }

        List<URL> clonedExportedURLs = new LinkedList<>();

        serviceInstances.forEach(serviceInstance -> {
            // 获取该ServiceInstance的host
            String host = serviceInstance.getHost();
            // 获取该ServiceInstance的模板URL集合，getTemplateExportedURLs()方法会根据Service Name以及当前ServiceInstance的revision
            // 从serviceRevisionExportedURLsCache缓存中获取对应的URL集合，另外，还会根据subscribedURL的protocol、group、version等参数进行过滤
            getTemplateExportedURLs(subscribedURL, serviceInstance)
                    .stream()
                     // 删除timestamp、pid等参数
                    .map(templateURL -> templateURL.removeParameter(TIMESTAMP_KEY))
                    .map(templateURL -> templateURL.removeParameter(PID_KEY))
                    .map(templateURL -> {
                        String protocol = templateURL.getProtocol();
                        int port = getProtocolPort(serviceInstance, protocol);
                        if (Objects.equals(templateURL.getHost(), host)
                                && Objects.equals(templateURL.getPort(), port)) { // use templateURL if equals
                            return templateURL;
                        }
                        // 覆盖host、port参数
                        URLBuilder clonedURLBuilder = from(templateURL) // remove the parameters from the template URL
                                .setHost(host)  // reset the host
                                .setPort(port); // reset the port

                        return clonedURLBuilder.build();
                    })
                    .forEach(clonedExportedURLs::add);
        });
        return clonedExportedURLs;
    }


    /**
     * Select one {@link ServiceInstance} by {@link ServiceInstanceSelector the strategy} if there are more that one
     * instances in order to avoid the hot spot appearing the some instance
     *
     * @param serviceInstances the {@link List list} of {@link ServiceInstance}
     * @return <code>null</code> if <code>serviceInstances</code> is empty.
     * @see ServiceInstanceSelector
     */
    private ServiceInstance selectServiceInstance(List<ServiceInstance> serviceInstances) {
        int size = serviceInstances.size();
        if (size == 0) {
            return null;
        } else if (size == 1) {
            return serviceInstances.get(0);
        }
        ServiceInstanceSelector selector = getExtensionLoader(ServiceInstanceSelector.class).getAdaptiveExtension();
        return selector.select(getUrl(), serviceInstances);
    }

    /**
     * Get the template exported {@link URL urls} from the specified {@link ServiceInstance}.
     * <p>
     * First, put the revision {@link ServiceInstance service instance}
     * associating {@link #getExportedURLs(ServiceInstance) exported URLs} into cache.
     * <p>
     * And then compare a new {@link ServiceInstance service instances'} revision with cached one,If they are equal,
     * return the cached template {@link URL urls} immediately, or to get template {@link URL urls} that the provider
     * {@link ServiceInstance instance} exported via executing {@link ##getExportedURLs(ServiceInstance) (ServiceInstance)}
     * method.
     * <p>
     * Eventually, the retrieving result will be cached and returned.
     *
     * @param subscribedURL    the subscribed {@link URL url}
     * @param selectedInstance the {@link ServiceInstance}
     *                         associating with the {@link URL urls}
     * @return non-null {@link List} of {@link URL urls}
     */
    private List<URL> getTemplateExportedURLs(URL subscribedURL, ServiceInstance selectedInstance) {

        List<URL> exportedURLs = getRevisionExportedURLs(selectedInstance);

        if (isEmpty(exportedURLs)) {
            return emptyList();
        }

        return filterSubscribedURLs(subscribedURL, exportedURLs);
    }

    /**
     * Initialize the URLs that the specified {@link ServiceInstance service instance} exported
     *
     * @param serviceInstance the {@link ServiceInstance} exports the Dubbo Services
     * @return the {@link URL URLs} that the {@link ServiceInstance} exported, it's calculated from
     * The invocation to remote {@link MetadataService}, or get from {@link #serviceRevisionExportedURLsCache cache} if
     * {@link ServiceInstanceMetadataUtils#getExportedServicesRevision(ServiceInstance) revision} is hit
     */
    private List<URL> initializeRevisionExportedURLs(ServiceInstance serviceInstance) {
        if (serviceInstance == null) {
            return emptyList();
        }

        // 获取Service Name
        String serviceName = serviceInstance.getServiceName();
        // 获取该ServiceInstance.metadata中携带的revision值
        String revision = getExportedServicesRevision(serviceInstance);
        // 从serviceRevisionExportedURLsCache集合中获取该revision值对应的URL集合
        Map<String, List<URL>> revisionExportedURLsMap = getRevisionExportedURLsMap(serviceName);
        List<URL> revisionExportedURLs = revisionExportedURLsMap.get(revision);

        boolean firstGet = false;

        // serviceRevisionExportedURLsCache缓存未命中
        if (revisionExportedURLs == null) { // The hit is missing in cache

            if (!revisionExportedURLsMap.isEmpty()) { // The case is that current ServiceInstance with the different revision
                if (logger.isWarnEnabled()) {
                    logger.warn(format("The ServiceInstance[id: %s, host : %s , port : %s] has different revision : %s" +
                                    ", please make sure the service [name : %s] is changing or not.",
                            serviceInstance.getId(),
                            serviceInstance.getHost(),
                            serviceInstance.getPort(),
                            revision,
                            serviceInstance.getServiceName()
                    ));
                }
            } else { // Else, it's the first time to get the exported URLs
                firstGet = true;
            }
            // 调用该ServiceInstance对应的MetadataService服务，获取其发布的URL集合
            revisionExportedURLs = getExportedURLs(serviceInstance);
            // 调用MetadataService服务成功之后，更新到serviceRevisionExportedURLsCache缓存中
            if (revisionExportedURLs != null) { // just allow the valid result into exportedURLsMap

                revisionExportedURLsMap.put(revision, revisionExportedURLs);

                if (logger.isDebugEnabled()) {
                    logger.debug(format("Get the exported URLs[size : %s, first : %s] from the target service " +
                                    "instance [id: %s , service : %s , host : %s , port : %s , revision : %s]",
                            revisionExportedURLs.size(), firstGet,
                            serviceInstance.getId(),
                            serviceInstance.getServiceName(),
                            serviceInstance.getHost(),
                            serviceInstance.getPort(),
                            revision
                    ));
                }
            }
        } else { // Else, The cache is hit
            if (logger.isDebugEnabled()) {
                logger.debug(format("Get the exported URLs[size : %s] from cache, the instance" +
                                "[id: %s , service : %s , host : %s , port : %s , revision : %s]",
                        revisionExportedURLs.size(),
                        serviceInstance.getId(),
                        serviceInstance.getServiceName(),
                        serviceInstance.getHost(),
                        serviceInstance.getPort(),
                        revision
                ));
            }
        }

        return revisionExportedURLs;
    }

    private Map<String, List<URL>> getRevisionExportedURLsMap(String serviceName) {
        return serviceRevisionExportedURLsCache.computeIfAbsent(serviceName, s -> new LinkedHashMap());
    }

    /**
     * Get all services {@link URL URLs} that the specified {@link ServiceInstance service instance} exported from cache
     *
     * @param serviceInstance the {@link ServiceInstance} exports the Dubbo Services
     * @return the same as {@link #getExportedURLs(ServiceInstance)}
     */
    private List<URL> getRevisionExportedURLs(ServiceInstance serviceInstance) {

        if (serviceInstance == null) {
            return emptyList();
        }

        String serviceName = serviceInstance.getServiceName();
        // get the revision from the specified {@link ServiceInstance}
        String revision = getExportedServicesRevision(serviceInstance);

        return getRevisionExportedURLs(serviceName, revision);
    }

    private List<URL> getRevisionExportedURLs(String serviceName, String revision) {
        return executeShared(() -> {
            Map<String, List<URL>> revisionExportedURLsMap = getRevisionExportedURLsMap(serviceName);
            List<URL> exportedURLs = revisionExportedURLsMap.get(revision);
            // Get a copy from source in order to prevent the caller trying to change the cached data
            return exportedURLs != null ? new ArrayList<>(exportedURLs) : emptyList();
        });
    }

    /**
     * Get all services {@link URL URLs} that the specified {@link ServiceInstance service instance} exported
     * via the proxy to invoke the {@link MetadataService}
     *
     * @param providerServiceInstance the {@link ServiceInstance} exported the Dubbo services
     * @return The possible result :
     * <ol>
     * <li>The normal result</li>
     * <li>The empty result if the {@link ServiceInstance service instance} did not export yet</li>
     * <li><code>null</code> if there is an invocation error on {@link MetadataService} proxy</li>
     * </ol>
     * @see MetadataServiceProxyFactory
     * @see MetadataService
     */
    private List<URL> getExportedURLs(ServiceInstance providerServiceInstance) {

        List<URL> exportedURLs = null;
        // 获取指定ServiceInstance实例存储元数据的类型
        String metadataStorageType = getMetadataStorageType(providerServiceInstance);

        try {
            // 创建MetadataService接口的本地代理
            MetadataService metadataService = MetadataServiceProxyFactory.getExtension(metadataStorageType)
                    .getProxy(providerServiceInstance);
            if (metadataService != null) {
                // 通过本地代理，请求该ServiceInstance的MetadataService服务
                SortedSet<String> urls = metadataService.getExportedURLs();
                exportedURLs = toURLs(urls);
            }
        } catch (Throwable e) {
            if (logger.isErrorEnabled()) {
                logger.error(format("Failed to get the exported URLs from the target service instance[%s]",
                        providerServiceInstance), e);
            }
            exportedURLs = null; // set the result to be null if failed to get
        }
        return exportedURLs;
    }

    private void executeExclusively(Runnable runnable) {
        Lock writeLock = lock.writeLock();
        writeLock.lock();
        try {
            runnable.run();
        } finally {
            writeLock.unlock();
        }
    }

    private <T> T executeShared(Supplier<T> supplier) {
        Lock readLock = lock.readLock();
        readLock.lock();
        try {
            return supplier.get();
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Synthesize new subscribed {@link URL URLs} from old one
     *
     * @param subscribedURL
     * @param serviceInstances
     * @return non-null
     */
    private Collection<? extends URL> synthesizeSubscribedURLs(URL subscribedURL, Collection<ServiceInstance> serviceInstances) {
        return subscribedURLsSynthesizers.stream()
                .filter(synthesizer -> synthesizer.supports(subscribedURL))
                .map(synthesizer -> synthesizer.synthesize(subscribedURL, serviceInstances))
                .flatMap(Collection::stream)
                .collect(Collectors.toList());
    }

    /**
     * 1.developer explicitly specifies the application name this interface belongs to
     * 2.check Interface-App mapping
     * 3.use the services specified in registry url.
     *
     * @param subscribedURL
     * @return
     */
    protected Set<String> getServices(URL subscribedURL) {
        Set<String> subscribedServices = new LinkedHashSet<>();
        // 首先尝试从subscribeURL中获取provided-by参数值，其中封装了全部Service Name
        String serviceNames = subscribedURL.getParameter(PROVIDED_BY);
        if (StringUtils.isNotEmpty(serviceNames)) {
            subscribedServices = parseServices(serviceNames);
        }

        if (isEmpty(subscribedServices)) {
            // 如果没有指定provided-by参数，则尝试通过subscribedURL构造Service ID，
            // 然后通过ServiceNameMapping的get()方法查找Service Name
            subscribedServices = findMappedServices(subscribedURL);
            if (isEmpty(subscribedServices)) {
                // 如果subscribedServices依旧为空，则返回registryURL中的subscribed-services参数值
                subscribedServices = getSubscribedServices();
            }
        }
        return subscribedServices;
    }

    public static Set<String> parseServices(String literalServices) {
        return isBlank(literalServices) ? emptySet() :
                unmodifiableSet(of(literalServices.split(","))
                        .map(String::trim)
                        .filter(StringUtils::isNotEmpty)
                        .collect(toSet()));
    }

    /**
     * Get the subscribed service names
     *
     * @return non-null
     */
    public Set<String> getSubscribedServices() {
        return subscribedServices;
    }

    /**
     * Get the mapped services name by the specified {@link URL}
     *
     * @param subscribedURL
     * @return
     */
    protected Set<String> findMappedServices(URL subscribedURL) {
        String serviceInterface = subscribedURL.getServiceInterface();
        String group = subscribedURL.getParameter(GROUP_KEY);
        String version = subscribedURL.getParameter(VERSION_KEY);
        String protocol = subscribedURL.getParameter(PROTOCOL_KEY, DUBBO_PROTOCOL);
        return serviceNameMapping.get(serviceInterface, group, version, protocol);
    }

    /**
     * Create an instance of {@link ServiceDiscoveryRegistry} if supported
     *
     * @param registryURL the {@link URL url} of registry
     * @return <code>null</code> if not supported
     */
    public static ServiceDiscoveryRegistry create(URL registryURL) {
        return supports(registryURL) ? new ServiceDiscoveryRegistry(registryURL) : null;
    }

    /**
     * Supports or not ?
     *
     * @param registryURL the {@link URL url} of registry
     * @return if supported, return <code>true</code>, or <code>false</code>
     */
    public static boolean supports(URL registryURL) {
        return SERVICE_REGISTRY_TYPE.equalsIgnoreCase(registryURL.getParameter(REGISTRY_TYPE_KEY));
    }

    private static List<URL> filterSubscribedURLs(URL subscribedURL, List<URL> exportedURLs) {
        return exportedURLs.stream()
                .filter(url -> isSameServiceInterface(subscribedURL, url))
                .filter(url -> isSameParameter(subscribedURL, url, VERSION_KEY))
                .filter(url -> isSameParameter(subscribedURL, url, GROUP_KEY))
                .filter(url -> isCompatibleProtocol(subscribedURL, url))
                .collect(Collectors.toList());
    }

    private static boolean isSameServiceInterface(URL one, URL another) {
        return Objects.equals(one.getServiceInterface(), another.getServiceInterface());
    }

    private static boolean isSameParameter(URL one, URL another, String key) {
        return Objects.equals(one.getParameter(key), another.getParameter(key));
    }

    private static boolean isCompatibleProtocol(URL one, URL another) {
        String protocol = one.getParameter(PROTOCOL_KEY);
        return isCompatibleProtocol(protocol, another);
    }

    private static boolean isCompatibleProtocol(String protocol, URL targetURL) {
        return protocol == null || Objects.equals(protocol, targetURL.getParameter(PROTOCOL_KEY))
                || Objects.equals(protocol, targetURL.getProtocol());
    }
}
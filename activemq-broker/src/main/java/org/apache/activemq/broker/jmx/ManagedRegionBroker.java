/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.broker.jmx;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

import javax.management.InstanceNotFoundException;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.TabularData;
import javax.management.openmbean.TabularDataSupport;
import javax.management.openmbean.TabularType;

import org.apache.activemq.broker.Broker;
import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.broker.ConnectionContext;
import org.apache.activemq.broker.ProducerBrokerExchange;
import org.apache.activemq.broker.jmx.OpenTypeSupport.OpenTypeFactory;
import org.apache.activemq.broker.region.Destination;
import org.apache.activemq.broker.region.DestinationFactory;
import org.apache.activemq.broker.region.DestinationFactoryImpl;
import org.apache.activemq.broker.region.DestinationInterceptor;
import org.apache.activemq.broker.region.Queue;
import org.apache.activemq.broker.region.Region;
import org.apache.activemq.broker.region.RegionBroker;
import org.apache.activemq.broker.region.Subscription;
import org.apache.activemq.broker.region.Topic;
import org.apache.activemq.broker.region.TopicRegion;
import org.apache.activemq.broker.region.TopicSubscription;
import org.apache.activemq.broker.region.policy.AbortSlowConsumerStrategy;
import org.apache.activemq.command.ActiveMQDestination;
import org.apache.activemq.command.ActiveMQMessage;
import org.apache.activemq.command.ActiveMQTopic;
import org.apache.activemq.command.ConsumerInfo;
import org.apache.activemq.command.Message;
import org.apache.activemq.command.MessageId;
import org.apache.activemq.command.ProducerInfo;
import org.apache.activemq.command.SubscriptionInfo;
import org.apache.activemq.store.MessageRecoveryListener;
import org.apache.activemq.store.PersistenceAdapter;
import org.apache.activemq.store.TopicMessageStore;
import org.apache.activemq.thread.Scheduler;
import org.apache.activemq.thread.TaskRunnerFactory;
import org.apache.activemq.transaction.XATransaction;
import org.apache.activemq.usage.SystemUsage;
import org.apache.activemq.util.JMXSupport;
import org.apache.activemq.util.ServiceStopper;
import org.apache.activemq.util.SubscriptionKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ManagedRegionBroker extends RegionBroker {
    private static final Logger LOG = LoggerFactory.getLogger(ManagedRegionBroker.class);
    private final ManagementContext managementContext;
    private final ObjectName brokerObjectName;
    private final Map<ObjectName, DestinationView> topics = new ConcurrentHashMap<ObjectName, DestinationView>();
    private final Map<ObjectName, DestinationView> queues = new ConcurrentHashMap<ObjectName, DestinationView>();
    private final Map<ObjectName, DestinationView> temporaryQueues = new ConcurrentHashMap<ObjectName, DestinationView>();
    private final Map<ObjectName, DestinationView> temporaryTopics = new ConcurrentHashMap<ObjectName, DestinationView>();
    private final Map<ObjectName, SubscriptionView> queueSubscribers = new ConcurrentHashMap<ObjectName, SubscriptionView>();
    private final Map<ObjectName, SubscriptionView> topicSubscribers = new ConcurrentHashMap<ObjectName, SubscriptionView>();
    private final Map<ObjectName, SubscriptionView> durableTopicSubscribers = new ConcurrentHashMap<ObjectName, SubscriptionView>();
    private final Map<ObjectName, SubscriptionView> inactiveDurableTopicSubscribers = new ConcurrentHashMap<ObjectName, SubscriptionView>();
    private final Map<ObjectName, SubscriptionView> temporaryQueueSubscribers = new ConcurrentHashMap<ObjectName, SubscriptionView>();
    private final Map<ObjectName, SubscriptionView> temporaryTopicSubscribers = new ConcurrentHashMap<ObjectName, SubscriptionView>();
    private final Map<ObjectName, ProducerView> queueProducers = new ConcurrentHashMap<ObjectName, ProducerView>();
    private final Map<ObjectName, ProducerView> topicProducers = new ConcurrentHashMap<ObjectName, ProducerView>();
    private final Map<ObjectName, ProducerView> temporaryQueueProducers = new ConcurrentHashMap<ObjectName, ProducerView>();
    private final Map<ObjectName, ProducerView> temporaryTopicProducers = new ConcurrentHashMap<ObjectName, ProducerView>();
    private final Map<ObjectName, ProducerView> dynamicDestinationProducers = new ConcurrentHashMap<ObjectName, ProducerView>();
    private final Map<SubscriptionKey, ObjectName> subscriptionKeys = new ConcurrentHashMap<SubscriptionKey, ObjectName>();
    private final Map<Subscription, ObjectName> subscriptionMap = new ConcurrentHashMap<Subscription, ObjectName>();
    private final Set<ObjectName> registeredMBeans = new CopyOnWriteArraySet<ObjectName>();
    /* This is the first broker in the broker interceptor chain. */
    private Broker contextBroker;

    private final ExecutorService asyncInvokeService;
    private final long mbeanTimeout;

    public ManagedRegionBroker(BrokerService brokerService, ManagementContext context, ObjectName brokerObjectName, TaskRunnerFactory taskRunnerFactory, SystemUsage memoryManager,
                               DestinationFactory destinationFactory, DestinationInterceptor destinationInterceptor,Scheduler scheduler,ThreadPoolExecutor executor) throws IOException {
        super(brokerService, taskRunnerFactory, memoryManager, destinationFactory, destinationInterceptor,scheduler,executor);
        this.managementContext = context;
        this.brokerObjectName = brokerObjectName;
        this.mbeanTimeout = brokerService.getMbeanInvocationTimeout();
        this.asyncInvokeService = mbeanTimeout > 0 ? executor : null;;
    }

    @Override
    public void start() throws Exception {
        super.start();
        // build all existing durable subscriptions
        buildExistingSubscriptions();
    }

    @Override
    protected void doStop(ServiceStopper stopper) {
        super.doStop(stopper);
        // lets remove any mbeans not yet removed
        for (Iterator<ObjectName> iter = registeredMBeans.iterator(); iter.hasNext();) {
            ObjectName name = iter.next();
            try {
                managementContext.unregisterMBean(name);
            } catch (InstanceNotFoundException e) {
                LOG.warn("The MBean: " + name + " is no longer registered with JMX");
            } catch (Exception e) {
                stopper.onException(this, e);
            }
        }
        registeredMBeans.clear();
    }

    @Override
    protected Region createQueueRegion(SystemUsage memoryManager, TaskRunnerFactory taskRunnerFactory, DestinationFactory destinationFactory) {
        return new ManagedQueueRegion(this, destinationStatistics, memoryManager, taskRunnerFactory, destinationFactory);
    }

    @Override
    protected Region createTempQueueRegion(SystemUsage memoryManager, TaskRunnerFactory taskRunnerFactory, DestinationFactory destinationFactory) {
        return new ManagedTempQueueRegion(this, destinationStatistics, memoryManager, taskRunnerFactory, destinationFactory);
    }

    @Override
    protected Region createTempTopicRegion(SystemUsage memoryManager, TaskRunnerFactory taskRunnerFactory, DestinationFactory destinationFactory) {
        return new ManagedTempTopicRegion(this, destinationStatistics, memoryManager, taskRunnerFactory, destinationFactory);
    }

    @Override
    protected Region createTopicRegion(SystemUsage memoryManager, TaskRunnerFactory taskRunnerFactory, DestinationFactory destinationFactory) {
        return new ManagedTopicRegion(this, destinationStatistics, memoryManager, taskRunnerFactory, destinationFactory);
    }

    public void register(ActiveMQDestination destName, Destination destination) {
        // TODO refactor to allow views for custom destinations
        try {
            ObjectName objectName = createObjectName(destName);
            DestinationView view;
            if (destination instanceof Queue) {
                view = new QueueView(this, (Queue)destination);
            } else if (destination instanceof Topic) {
                view = new TopicView(this, (Topic)destination);
            } else {
                view = null;
                LOG.warn("JMX View is not supported for custom destination: " + destination);
            }
            if (view != null) {
                registerDestination(objectName, destName, view);
            }
        } catch (Exception e) {
            LOG.error("Failed to register destination " + destName, e);
        }
    }

    public void unregister(ActiveMQDestination destName) {
        try {
            ObjectName objectName = createObjectName(destName);
            unregisterDestination(objectName);
        } catch (Exception e) {
            LOG.error("Failed to unregister " + destName, e);
        }
    }

    public ObjectName registerSubscription(ConnectionContext context, Subscription sub) {
        String connectionClientId = context.getClientId();
        ObjectName brokerJmxObjectName = brokerObjectName;
        String objectNameStr = getSubscriptionObjectName(sub.getConsumerInfo(), connectionClientId, brokerJmxObjectName);
        SubscriptionKey key = new SubscriptionKey(context.getClientId(), sub.getConsumerInfo().getSubscriptionName());
        try {
            ObjectName objectName = new ObjectName(objectNameStr);
            SubscriptionView view;
            if (sub.getConsumerInfo().getConsumerId().getConnectionId().equals("OFFLINE")) {
                // add offline subscribers to inactive list
                SubscriptionInfo info = new SubscriptionInfo();
                info.setClientId(context.getClientId());
                info.setSubscriptionName(sub.getConsumerInfo().getSubscriptionName());
                info.setDestination(sub.getConsumerInfo().getDestination());
                info.setSelector(sub.getSelector());
                addInactiveSubscription(key, info, sub);
            } else {
                String userName = brokerService.isPopulateUserNameInMBeans() ? context.getUserName() : null;
                if (sub.getConsumerInfo().isDurable()) {
                    view = new DurableSubscriptionView(this, context.getClientId(), userName, sub);
                } else {
                    if (sub instanceof TopicSubscription) {
                        view = new TopicSubscriptionView(context.getClientId(), userName, (TopicSubscription) sub);
                    } else {
                        view = new SubscriptionView(context.getClientId(), userName, sub);
                    }
                }
                registerSubscription(objectName, sub.getConsumerInfo(), key, view);
            }
            subscriptionMap.put(sub, objectName);
            return objectName;
        } catch (Exception e) {
            LOG.error("Failed to register subscription " + sub, e);
            return null;
        }
    }

    public static String getSubscriptionObjectName(ConsumerInfo info, String connectionClientId, ObjectName brokerJmxObjectName) {
        Hashtable<String, String> map = brokerJmxObjectName.getKeyPropertyList();
        String brokerDomain = brokerJmxObjectName.getDomain();
        String objectNameStr = brokerDomain + ":" + "BrokerName=" + map.get("BrokerName") + ",Type=Subscription,";
        String destinationType = "destinationType=" + info.getDestination().getDestinationTypeAsString();
        String destinationName = "destinationName=" + JMXSupport.encodeObjectNamePart(info.getDestination().getPhysicalName());
        String clientId = "clientId=" + JMXSupport.encodeObjectNamePart(connectionClientId);
        String persistentMode = "persistentMode=";
        String consumerId = "";
        if (info.isDurable()) {
            persistentMode += "Durable,subscriptionID=" + JMXSupport.encodeObjectNamePart(info.getSubscriptionName());
        } else {
            persistentMode += "Non-Durable";
            if (info.getConsumerId() != null) {
                consumerId = ",consumerId=" + JMXSupport.encodeObjectNamePart(info.getConsumerId().toString());
            }
        }
        objectNameStr += persistentMode + ",";
        objectNameStr += destinationType + ",";
        objectNameStr += destinationName + ",";
        objectNameStr += clientId;
        objectNameStr += consumerId;
        return objectNameStr;
    }

    @Override
    public Subscription addConsumer(ConnectionContext context, ConsumerInfo info) throws Exception {
        Subscription sub = super.addConsumer(context, info);
        SubscriptionKey subscriptionKey = new SubscriptionKey(sub.getContext().getClientId(), sub.getConsumerInfo().getSubscriptionName());
        ObjectName inactiveName = subscriptionKeys.get(subscriptionKey);
        if (inactiveName != null) {
            // if it was inactive, register it
            registerSubscription(context, sub);
        }
        return sub;
    }

    @Override
    public void removeConsumer(ConnectionContext context, ConsumerInfo info) throws Exception {
        for (Subscription sub : subscriptionMap.keySet()) {
            if (sub.getConsumerInfo().equals(info)) {
               // unregister all consumer subs
               unregisterSubscription(subscriptionMap.get(sub), true);
            }
        }
        super.removeConsumer(context, info);
    }

    @Override
    public void addProducer(ConnectionContext context, ProducerInfo info)
            throws Exception {
        super.addProducer(context, info);
        String connectionClientId = context.getClientId();
        ObjectName objectName = createObjectName(info, connectionClientId);
        String userName = brokerService.isPopulateUserNameInMBeans() ? context.getUserName() : null;
        ProducerView view = new ProducerView(info, connectionClientId, userName, this);
        registerProducer(objectName, info.getDestination(), view);
    }

    @Override
    public void removeProducer(ConnectionContext context, ProducerInfo info) throws Exception {
        ObjectName objectName = createObjectName(info, context.getClientId());
        unregisterProducer(objectName);
        super.removeProducer(context, info);
    }

    @Override
    public void send(ProducerBrokerExchange exchange, Message message) throws Exception {
        if (exchange != null && exchange.getProducerState() != null && exchange.getProducerState().getInfo() != null) {
            ProducerInfo info = exchange.getProducerState().getInfo();
            if (info.getDestination() == null && info.getProducerId() != null) {
                ObjectName objectName = createObjectName(info, exchange.getConnectionContext().getClientId());
                ProducerView view = this.dynamicDestinationProducers.get(objectName);
                if (view != null) {
                    ActiveMQDestination dest = message.getDestination();
                    if (dest != null) {
                        view.setLastUsedDestinationName(dest);
                    }
                }
            }
         }
        super.send(exchange, message);
    }

    public void unregisterSubscription(Subscription sub) {
        ObjectName name = subscriptionMap.remove(sub);
        if (name != null) {
            try {
                SubscriptionKey subscriptionKey = new SubscriptionKey(sub.getContext().getClientId(), sub.getConsumerInfo().getSubscriptionName());
                ObjectName inactiveName = subscriptionKeys.get(subscriptionKey);
                if (inactiveName != null) {
                    inactiveDurableTopicSubscribers.remove(inactiveName);
                    managementContext.unregisterMBean(inactiveName);
                }
            } catch (Exception e) {
                LOG.error("Failed to unregister subscription " + sub, e);
            }
        }
    }

    protected void registerDestination(ObjectName key, ActiveMQDestination dest, DestinationView view) throws Exception {
        if (dest.isQueue()) {
            if (dest.isTemporary()) {
                temporaryQueues.put(key, view);
            } else {
                queues.put(key, view);
            }
        } else {
            if (dest.isTemporary()) {
                temporaryTopics.put(key, view);
            } else {
                topics.put(key, view);
            }
        }
        try {
            AsyncAnnotatedMBean.registerMBean(asyncInvokeService, mbeanTimeout, managementContext, view, key);
            registeredMBeans.add(key);
        } catch (Throwable e) {
            LOG.warn("Failed to register MBean: " + key);
            LOG.debug("Failure reason: " + e, e);
        }
    }

    protected void unregisterDestination(ObjectName key) throws Exception {

        DestinationView view = removeAndRemember(topics, key, null);
        view = removeAndRemember(queues, key, view);
        view = removeAndRemember(temporaryQueues, key, view);
        view = removeAndRemember(temporaryTopics, key, view);
        if (registeredMBeans.remove(key)) {
            try {
                managementContext.unregisterMBean(key);
            } catch (Throwable e) {
                LOG.warn("Failed to unregister MBean: " + key);
                LOG.debug("Failure reason: " + e, e);
            }
        }
        if (view != null) {
            key = view.getSlowConsumerStrategy();
            if (key!= null && registeredMBeans.remove(key)) {
                try {
                    managementContext.unregisterMBean(key);
                } catch (Throwable e) {
                    LOG.warn("Failed to unregister slow consumer strategy MBean: " + key);
                    LOG.debug("Failure reason: " + e, e);
                }
            }
        }
    }

    protected void registerProducer(ObjectName key, ActiveMQDestination dest, ProducerView view) throws Exception {

        if (dest != null) {
            if (dest.isQueue()) {
                if (dest.isTemporary()) {
                    temporaryQueueProducers.put(key, view);
                } else {
                    queueProducers.put(key, view);
                }
            } else {
                if (dest.isTemporary()) {
                    temporaryTopicProducers.put(key, view);
                } else {
                    topicProducers.put(key, view);
                }
            }
        } else {
            dynamicDestinationProducers.put(key, view);
        }

        try {
            AsyncAnnotatedMBean.registerMBean(asyncInvokeService, mbeanTimeout, managementContext, view, key);
            registeredMBeans.add(key);
        } catch (Throwable e) {
            LOG.warn("Failed to register MBean: " + key);
            LOG.debug("Failure reason: " + e, e);
        }
    }

    protected void unregisterProducer(ObjectName key) throws Exception {
        queueProducers.remove(key);
        topicProducers.remove(key);
        temporaryQueueProducers.remove(key);
        temporaryTopicProducers.remove(key);
        dynamicDestinationProducers.remove(key);
        if (registeredMBeans.remove(key)) {
            try {
                managementContext.unregisterMBean(key);
            } catch (Throwable e) {
                LOG.warn("Failed to unregister MBean: " + key);
                LOG.debug("Failure reason: " + e, e);
            }
        }
    }

    private DestinationView removeAndRemember(Map<ObjectName, DestinationView> map, ObjectName key, DestinationView view) {
        DestinationView candidate = map.remove(key);
        if (candidate != null && view == null) {
            view = candidate;
        }
        return candidate != null ? candidate : view;
    }

    protected void registerSubscription(ObjectName key, ConsumerInfo info, SubscriptionKey subscriptionKey, SubscriptionView view) throws Exception {
        ActiveMQDestination dest = info.getDestination();
        if (dest.isQueue()) {
            if (dest.isTemporary()) {
                temporaryQueueSubscribers.put(key, view);
            } else {
                queueSubscribers.put(key, view);
            }
        } else {
            if (dest.isTemporary()) {
                temporaryTopicSubscribers.put(key, view);
            } else {
                if (info.isDurable()) {
                    durableTopicSubscribers.put(key, view);
                    // unregister any inactive durable subs
                    try {
                        ObjectName inactiveName = subscriptionKeys.get(subscriptionKey);
                        if (inactiveName != null) {
                            inactiveDurableTopicSubscribers.remove(inactiveName);
                            registeredMBeans.remove(inactiveName);
                            managementContext.unregisterMBean(inactiveName);
                        }
                    } catch (Throwable e) {
                        LOG.error("Unable to unregister inactive durable subscriber: " + subscriptionKey, e);
                    }
                } else {
                    topicSubscribers.put(key, view);
                }
            }
        }

        try {
            AsyncAnnotatedMBean.registerMBean(asyncInvokeService, mbeanTimeout, managementContext, view, key);
            registeredMBeans.add(key);
        } catch (Throwable e) {
            LOG.warn("Failed to register MBean: " + key);
            LOG.debug("Failure reason: " + e, e);
        }

    }

    protected void unregisterSubscription(ObjectName key, boolean addToInactive) throws Exception {
        queueSubscribers.remove(key);
        topicSubscribers.remove(key);
        temporaryQueueSubscribers.remove(key);
        temporaryTopicSubscribers.remove(key);
        if (registeredMBeans.remove(key)) {
            try {
                managementContext.unregisterMBean(key);
            } catch (Throwable e) {
                LOG.warn("Failed to unregister MBean: " + key);
                LOG.debug("Failure reason: " + e, e);
            }
        }
        DurableSubscriptionView view = (DurableSubscriptionView)durableTopicSubscribers.remove(key);
        if (view != null) {
            // need to put this back in the inactive list
            SubscriptionKey subscriptionKey = new SubscriptionKey(view.getClientId(), view.getSubscriptionName());
            if (addToInactive) {
                SubscriptionInfo info = new SubscriptionInfo();
                info.setClientId(subscriptionKey.getClientId());
                info.setSubscriptionName(subscriptionKey.getSubscriptionName());
                info.setDestination(new ActiveMQTopic(view.getDestinationName()));
                info.setSelector(view.getSelector());
                addInactiveSubscription(subscriptionKey, info, (brokerService.isKeepDurableSubsActive() ? view.subscription : null));
            }
        }
    }

    protected void buildExistingSubscriptions() throws Exception {
        Map<SubscriptionKey, SubscriptionInfo> subscriptions = new HashMap<SubscriptionKey, SubscriptionInfo>();
        Set<ActiveMQDestination> destinations = destinationFactory.getDestinations();
        if (destinations != null) {
            for (ActiveMQDestination dest : destinations) {
                if (dest.isTopic()) {
                    SubscriptionInfo[] infos = destinationFactory.getAllDurableSubscriptions((ActiveMQTopic)dest);
                    if (infos != null) {
                        for (int i = 0; i < infos.length; i++) {
                            SubscriptionInfo info = infos[i];
                            SubscriptionKey key = new SubscriptionKey(info);
                            if (!alreadyKnown(key)) {
                                LOG.debug("Restoring durable subscription mbean: " + info);
                                subscriptions.put(key, info);
                            }
                        }
                    }
                }
            }
        }

        for (Map.Entry<SubscriptionKey, SubscriptionInfo> entry : subscriptions.entrySet()) {
            addInactiveSubscription(entry.getKey(), entry.getValue(), null);
        }
    }

    private boolean alreadyKnown(SubscriptionKey key) {
        boolean known = false;
        known = ((TopicRegion) getTopicRegion()).durableSubscriptionExists(key);
        if (LOG.isTraceEnabled()) {
            LOG.trace("Sub with key: " + key + ", " + (known ? "": "not") +  " already registered");
        }
        return known;
    }

    protected void addInactiveSubscription(SubscriptionKey key, SubscriptionInfo info, Subscription subscription) {
        try {
            ConsumerInfo offlineConsumerInfo = subscription != null ? subscription.getConsumerInfo() : ((TopicRegion)getTopicRegion()).createInactiveConsumerInfo(info);
            ObjectName objectName = new ObjectName(getSubscriptionObjectName(offlineConsumerInfo, info.getClientId(), brokerObjectName));
            SubscriptionView view = new InactiveDurableSubscriptionView(this, key.getClientId(), info, subscription);

            try {
                AsyncAnnotatedMBean.registerMBean(asyncInvokeService, mbeanTimeout, managementContext, view, objectName);
                registeredMBeans.add(objectName);
            } catch (Throwable e) {
                LOG.warn("Failed to register MBean: " + key);
                LOG.debug("Failure reason: " + e, e);
            }

            inactiveDurableTopicSubscribers.put(objectName, view);
            subscriptionKeys.put(key, objectName);
        } catch (Exception e) {
            LOG.error("Failed to register subscription " + info, e);
        }
    }

    public CompositeData[] browse(SubscriptionView view) throws OpenDataException {
        List<Message> messages = getSubscriberMessages(view);
        CompositeData c[] = new CompositeData[messages.size()];
        for (int i = 0; i < c.length; i++) {
            try {
                c[i] = OpenTypeSupport.convert(messages.get(i));
            } catch (Throwable e) {
                LOG.error("failed to browse : " + view, e);
            }
        }
        return c;
    }

    public TabularData browseAsTable(SubscriptionView view) throws OpenDataException {
        OpenTypeFactory factory = OpenTypeSupport.getFactory(ActiveMQMessage.class);
        List<Message> messages = getSubscriberMessages(view);
        CompositeType ct = factory.getCompositeType();
        TabularType tt = new TabularType("MessageList", "MessageList", ct, new String[] {"JMSMessageID"});
        TabularDataSupport rc = new TabularDataSupport(tt);
        for (int i = 0; i < messages.size(); i++) {
            rc.put(new CompositeDataSupport(ct, factory.getFields(messages.get(i))));
        }
        return rc;
    }

    protected List<Message> getSubscriberMessages(SubscriptionView view) {
        // TODO It is very dangerous operation for big backlogs
        if (!(destinationFactory instanceof DestinationFactoryImpl)) {
            throw new RuntimeException("unsupported by " + destinationFactory);
        }
        PersistenceAdapter adapter = ((DestinationFactoryImpl)destinationFactory).getPersistenceAdapter();
        final List<Message> result = new ArrayList<Message>();
        try {
            ActiveMQTopic topic = new ActiveMQTopic(view.getDestinationName());
            TopicMessageStore store = adapter.createTopicMessageStore(topic);
            store.recover(new MessageRecoveryListener() {
                public boolean recoverMessage(Message message) throws Exception {
                    result.add(message);
                    return true;
                }

                public boolean recoverMessageReference(MessageId messageReference) throws Exception {
                    throw new RuntimeException("Should not be called.");
                }

                public boolean hasSpace() {
                    return true;
                }

                public boolean isDuplicate(MessageId id) {
                    return false;
                }
            });
        } catch (Throwable e) {
            LOG.error("Failed to browse messages for Subscription " + view, e);
        }
        return result;

    }

    protected ObjectName[] getTopics() {
        Set<ObjectName> set = topics.keySet();
        return set.toArray(new ObjectName[set.size()]);
    }

    protected ObjectName[] getQueues() {
        Set<ObjectName> set = queues.keySet();
        return set.toArray(new ObjectName[set.size()]);
    }

    protected ObjectName[] getTemporaryTopics() {
        Set<ObjectName> set = temporaryTopics.keySet();
        return set.toArray(new ObjectName[set.size()]);
    }

    protected ObjectName[] getTemporaryQueues() {
        Set<ObjectName> set = temporaryQueues.keySet();
        return set.toArray(new ObjectName[set.size()]);
    }

    protected ObjectName[] getTopicSubscribers() {
        Set<ObjectName> set = topicSubscribers.keySet();
        return set.toArray(new ObjectName[set.size()]);
    }

    protected ObjectName[] getDurableTopicSubscribers() {
        Set<ObjectName> set = durableTopicSubscribers.keySet();
        return set.toArray(new ObjectName[set.size()]);
    }

    protected ObjectName[] getQueueSubscribers() {
        Set<ObjectName> set = queueSubscribers.keySet();
        return set.toArray(new ObjectName[set.size()]);
    }

    protected ObjectName[] getTemporaryTopicSubscribers() {
        Set<ObjectName> set = temporaryTopicSubscribers.keySet();
        return set.toArray(new ObjectName[set.size()]);
    }

    protected ObjectName[] getTemporaryQueueSubscribers() {
        Set<ObjectName> set = temporaryQueueSubscribers.keySet();
        return set.toArray(new ObjectName[set.size()]);
    }

    protected ObjectName[] getInactiveDurableTopicSubscribers() {
        Set<ObjectName> set = inactiveDurableTopicSubscribers.keySet();
        return set.toArray(new ObjectName[set.size()]);
    }

    protected ObjectName[] getTopicProducers() {
        Set<ObjectName> set = topicProducers.keySet();
        return set.toArray(new ObjectName[set.size()]);
    }

    protected ObjectName[] getQueueProducers() {
        Set<ObjectName> set = queueProducers.keySet();
        return set.toArray(new ObjectName[set.size()]);
    }

    protected ObjectName[] getTemporaryTopicProducers() {
        Set<ObjectName> set = temporaryTopicProducers.keySet();
        return set.toArray(new ObjectName[set.size()]);
    }

    protected ObjectName[] getTemporaryQueueProducers() {
        Set<ObjectName> set = temporaryQueueProducers.keySet();
        return set.toArray(new ObjectName[set.size()]);
    }

    protected ObjectName[] getDynamicDestinationProducers() {
        Set<ObjectName> set = dynamicDestinationProducers.keySet();
        return set.toArray(new ObjectName[set.size()]);
    }

    public Broker getContextBroker() {
        return contextBroker;
    }

    public void setContextBroker(Broker contextBroker) {
        this.contextBroker = contextBroker;
    }

    protected ObjectName createObjectName(ActiveMQDestination destName) throws MalformedObjectNameException {
        // Build the object name for the destination
        Hashtable<String, String> map = brokerObjectName.getKeyPropertyList();
        ObjectName objectName = new ObjectName(brokerObjectName.getDomain() + ":" + "BrokerName=" + map.get("BrokerName") + "," + "Type="
                                               + JMXSupport.encodeObjectNamePart(destName.getDestinationTypeAsString()) + "," + "Destination="
                                               + JMXSupport.encodeObjectNamePart(destName.getPhysicalName()));
        return objectName;
    }

    protected ObjectName createObjectName(ProducerInfo producerInfo, String connectionClientId) throws MalformedObjectNameException {
        // Build the object name for the producer info
        Hashtable<String, String> map = brokerObjectName.getKeyPropertyList();

        String destinationType = "destinationType=";
        String destinationName = "destinationName=";

        if (producerInfo.getDestination() == null) {
            destinationType += "Dynamic";
            destinationName = null;
        } else {
            destinationType += producerInfo.getDestination().getDestinationTypeAsString();
            destinationName += JMXSupport.encodeObjectNamePart(producerInfo.getDestination().getPhysicalName());
        }

        String clientId = "clientId=" + JMXSupport.encodeObjectNamePart(connectionClientId);
        String producerId = "producerId=" + JMXSupport.encodeObjectNamePart(producerInfo.getProducerId().toString());

        ObjectName objectName = new ObjectName(brokerObjectName.getDomain() + ":" + "BrokerName=" + map.get("BrokerName") + ","
                                               + "Type=Producer" + ","
                                               + destinationType + ","
                                               + (destinationName != null ? destinationName + "," : "")
                                               + clientId + "," + producerId);
        return objectName;
    }

    public ObjectName registerSlowConsumerStrategy(AbortSlowConsumerStrategy strategy) throws MalformedObjectNameException {
        ObjectName objectName = null;
        try {
            objectName = createObjectName(strategy);
            if (!registeredMBeans.contains(objectName))  {
                AbortSlowConsumerStrategyView view = new AbortSlowConsumerStrategyView(this, strategy);
                AsyncAnnotatedMBean.registerMBean(asyncInvokeService, mbeanTimeout, managementContext, view, objectName);
                registeredMBeans.add(objectName);
            }
        } catch (Exception e) {
            LOG.warn("Failed to register MBean: " + strategy);
            LOG.debug("Failure reason: " + e, e);
        }
        return objectName;
    }

    protected ObjectName createObjectName(XATransaction transaction) throws MalformedObjectNameException {
        Hashtable<String, String> map = brokerObjectName.getKeyPropertyList();
        ObjectName objectName = new ObjectName(brokerObjectName.getDomain() + ":" + "BrokerName=" + map.get("BrokerName")
                                               + "," + "Type=RecoveredXaTransaction"
                                               + "," + "Xid="
                                               + JMXSupport.encodeObjectNamePart(transaction.getTransactionId().toString()));
        return objectName;
    }

    public void registerRecoveredTransactionMBean(XATransaction transaction) {
        try {
            ObjectName objectName = createObjectName(transaction);
            if (!registeredMBeans.contains(objectName))  {
                RecoveredXATransactionView view = new RecoveredXATransactionView(this, transaction);
                AsyncAnnotatedMBean.registerMBean(asyncInvokeService, mbeanTimeout, managementContext, view, objectName);
                registeredMBeans.add(objectName);
            }
        } catch (Exception e) {
            LOG.warn("Failed to register prepared transaction MBean: " + transaction);
            LOG.debug("Failure reason: " + e, e);
        }
    }

    public void unregister(XATransaction transaction) {
        try {
            ObjectName objectName = createObjectName(transaction);
            if (registeredMBeans.remove(objectName)) {
                try {
                    managementContext.unregisterMBean(objectName);
                } catch (Throwable e) {
                    LOG.warn("Failed to unregister MBean: " + objectName);
                    LOG.debug("Failure reason: " + e, e);
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to create object name to unregister " + transaction, e);
        }
    }

    private ObjectName createObjectName(AbortSlowConsumerStrategy strategy) throws MalformedObjectNameException{
        Hashtable<String, String> map = brokerObjectName.getKeyPropertyList();
        ObjectName objectName = new ObjectName(brokerObjectName.getDomain() + ":" + "BrokerName=" + map.get("BrokerName") + ","
                            + "Type=SlowConsumerStrategy," + "InstanceName=" + JMXSupport.encodeObjectNamePart(strategy.getName()));
        return objectName;
    }

    public ObjectName getSubscriberObjectName(Subscription key) {
        return subscriptionMap.get(key);
    }

    public Subscription getSubscriber(ObjectName key) {
        Subscription sub = null;
        for (Entry<Subscription, ObjectName> entry: subscriptionMap.entrySet()) {
            if (entry.getValue().equals(key)) {
                sub = entry.getKey();
                break;
            }
        }
        return sub;
    }

    public Map<ObjectName, DestinationView> getQueueViews() {
        return queues;
    }
}

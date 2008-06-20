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
package org.apache.activemq.camel;

import javax.jms.JMSException;
import javax.jms.Queue;
import javax.jms.QueueReceiver;
import javax.jms.QueueSender;

import org.apache.activemq.ActiveMQSession;

/**
 * A JMS {@link Queue} object which refers to a Camel endpoint
 *
 * @version $Revision: $
 */
public class CamelQueue extends CamelDestination implements Queue {

    public CamelQueue(String uri) {
        super(uri);
    }

    public String getQueueName() throws JMSException {
        return getUri();
    }

    public QueueSender createSender(ActiveMQSession session) throws JMSException {
        return new CamelQueueSender(this, resolveEndpoint(session), session);
    }
    public QueueReceiver createReceiver(ActiveMQSession session, String messageSelector) {
        return new CamelQueueReceiver(this, resolveEndpoint(session), session, messageSelector);
    }

}
/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.activemq.artemis.core.protocol.mqtt;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.activemq.artemis.core.config.WildcardConfiguration;
import org.apache.activemq.artemis.core.message.impl.CoreMessageObjectPools;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.impl.ServerSessionImpl;
import org.apache.activemq.artemis.spi.core.protocol.SessionCallback;

public class MQTTSession {

   private static Map<String, MQTTSessionState> SESSIONS = new ConcurrentHashMap<>();

   private final String id = UUID.randomUUID().toString();

   private final String identity;

   private MQTTProtocolHandler protocolHandler;

   private MQTTSubscriptionManager subscriptionManager;

   private MQTTSessionCallback sessionCallback;

   private ServerSessionImpl serverSession;

   private MQTTPublishManager mqttPublishManager;

   private MQTTConnectionManager mqttConnectionManager;

   private MQTTRetainMessageManager retainMessageManager;

   private MQTTConnection connection;

   protected MQTTSessionState state;

   private boolean stopped = false;

   private MQTTLogger log = MQTTLogger.LOGGER;

   private MQTTProtocolManager protocolManager;

   private boolean clean;

   private WildcardConfiguration wildcardConfiguration;

   private CoreMessageObjectPools coreMessageObjectPools = new CoreMessageObjectPools();

   public MQTTSession(MQTTProtocolHandler protocolHandler,
                      MQTTConnection connection,
                      MQTTProtocolManager protocolManager,
                      WildcardConfiguration wildcardConfiguration) throws Exception {
      this.protocolHandler = protocolHandler;
      this.protocolManager = protocolManager;
      this.wildcardConfiguration = wildcardConfiguration;

      identity = protocolHandler.getServer().getIdentity();

      this.connection = connection;

      mqttConnectionManager = new MQTTConnectionManager(this);
      mqttPublishManager = new MQTTPublishManager(this);
      sessionCallback = new MQTTSessionCallback(this, connection);
      subscriptionManager = new MQTTSubscriptionManager(this);
      retainMessageManager = new MQTTRetainMessageManager(this);

      log.debug("SESSION CREATED: " + id);
   }

   // Called after the client has Connected.
   synchronized void start() throws Exception {
      mqttPublishManager.start();
      subscriptionManager.start();
      stopped = false;
   }

   // TODO ensure resources are cleaned up for GC.
   synchronized void stop() throws Exception {
      if (!stopped) {
         protocolHandler.stop();
         subscriptionManager.stop();
         mqttPublishManager.stop();

         if (serverSession != null) {
            serverSession.stop();
            serverSession.close(false);
         }

         if (state != null) {
            state.setAttached(false);
         }

         if (isClean()) {
            clean();
            removeSessionStateFromSessionMap(connection.getClientID());
         }
      }
      stopped = true;
   }

   boolean getStopped() {
      return stopped;
   }

   boolean isClean() {
      return clean;
   }

   void setClean(boolean clean) {
      this.clean = clean;
   }

   MQTTPublishManager getMqttPublishManager() {
      return mqttPublishManager;
   }

   MQTTSessionState getState() {
      return state;
   }

   MQTTConnectionManager getConnectionManager() {
      return mqttConnectionManager;
   }

   MQTTSessionState getSessionState() {
      return state;
   }

   ServerSessionImpl getServerSession() {
      return serverSession;
   }

   ActiveMQServer getServer() {
      return protocolHandler.getServer();
   }

   MQTTSubscriptionManager getSubscriptionManager() {
      return subscriptionManager;
   }

   MQTTProtocolHandler getProtocolHandler() {
      return protocolHandler;
   }

   SessionCallback getSessionCallback() {
      return sessionCallback;
   }

   void setServerSession(ServerSessionImpl serverSession) {
      this.serverSession = serverSession;
   }

   void setSessionState(MQTTSessionState state) {
      this.state = state;
      state.setAttached(true);
   }

   MQTTRetainMessageManager getRetainMessageManager() {
      return retainMessageManager;
   }

   MQTTConnection getConnection() {
      return connection;
   }

   MQTTProtocolManager getProtocolManager() {
      return protocolManager;
   }

   void clean() throws Exception {
      subscriptionManager.clean();
      mqttPublishManager.clean();
      state.clear();
   }

   public WildcardConfiguration getWildcardConfiguration() {
      return wildcardConfiguration;
   }

   public void setWildcardConfiguration(WildcardConfiguration wildcardConfiguration) {
      this.wildcardConfiguration = wildcardConfiguration;
   }

   public CoreMessageObjectPools getCoreMessageObjectPools() {
      return coreMessageObjectPools;
   }

   public static Map<String, MQTTSessionState> getSessions() {
      return new HashMap<>(SESSIONS);
   }

   public MQTTSessionState getSessionStateFromSessionMap(String clientId) {
      return SESSIONS.get(generateSessionStateKey(clientId));
   }

   public void putSessionStateIntoSessionMap(String clientId, MQTTSessionState state) {
      SESSIONS.put(generateSessionStateKey(clientId), state);
   }

   public void removeSessionStateFromSessionMap(String clientId) {
      SESSIONS.remove(generateSessionStateKey(clientId));
   }

   /**
    * When performing cluster testing, different nodes of the cluster are actually started in the same JVM process,
    * and their class data is shared. {@link #SESSIONS} is shared, cause the test abnormal when multiple consumers
    * use the same clientId to connect to different nodes simultaneously abnormal.
    * if {@link ActiveMQServer#getIdentity()} is not null, indicates that the test is in progress.
    * {@link ActiveMQServer#getIdentity()} is unique among different nodes. Use "identity+clientId" as the key to
    * call {@link #SESSIONS} during testing, to ensure the correctness of test cases.
    *
    * @param clientId
    * @return
    */
   private String generateSessionStateKey(String clientId) {
      String key = clientId;
      if (identity != null) {
         key = identity + key;
      }
      return key;
   }
}

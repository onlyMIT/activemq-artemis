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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.mqtt.MqttDecoder;
import io.netty.handler.codec.mqtt.MqttEncoder;
import io.netty.handler.codec.mqtt.MqttMessage;
import org.apache.activemq.artemis.api.core.ActiveMQBuffer;
import org.apache.activemq.artemis.api.core.BaseInterceptor;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.api.core.management.CoreNotificationType;
import org.apache.activemq.artemis.api.core.management.ManagementHelper;
import org.apache.activemq.artemis.core.postoffice.Binding;
import org.apache.activemq.artemis.core.remoting.impl.netty.NettyServerConnection;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.Consumer;
import org.apache.activemq.artemis.core.server.Queue;
import org.apache.activemq.artemis.core.server.impl.ServerConsumerImpl;
import org.apache.activemq.artemis.core.server.management.Notification;
import org.apache.activemq.artemis.core.server.management.NotificationListener;
import org.apache.activemq.artemis.spi.core.protocol.AbstractProtocolManager;
import org.apache.activemq.artemis.spi.core.protocol.ConnectionEntry;
import org.apache.activemq.artemis.spi.core.protocol.ProtocolManagerFactory;
import org.apache.activemq.artemis.spi.core.protocol.RemotingConnection;
import org.apache.activemq.artemis.spi.core.remoting.Acceptor;
import org.apache.activemq.artemis.spi.core.remoting.Connection;
import org.apache.activemq.artemis.utils.collections.TypedProperties;

import static org.apache.activemq.artemis.api.core.management.CoreNotificationType.CONSUMER_CREATED;

/**
 * MQTTProtocolManager
 */
class MQTTProtocolManager extends AbstractProtocolManager<MqttMessage, MQTTInterceptor, MQTTConnection> implements NotificationListener {

   private static final List<String> websocketRegistryNames = Arrays.asList("mqtt", "mqttv3.1");

   private ActiveMQServer server;

   private MQTTLogger log = MQTTLogger.LOGGER;
   private final List<MQTTInterceptor> incomingInterceptors = new ArrayList<>();
   private final List<MQTTInterceptor> outgoingInterceptors = new ArrayList<>();

   //TODO Read in a list of existing client IDs from stored Sessions.
   private Map<String, MQTTConnection> connectedClients = new ConcurrentHashMap<>();

   MQTTProtocolManager(ActiveMQServer server,
                       List<BaseInterceptor> incomingInterceptors,
                       List<BaseInterceptor> outgoingInterceptors) {
      this.server = server;
      this.updateInterceptors(incomingInterceptors, outgoingInterceptors);
      server.getManagementService().addNotificationListener(this);
   }

   @Override
   public void onNotification(Notification notification) {
      if (!(notification.getType() instanceof CoreNotificationType))
         return;

      CoreNotificationType type = (CoreNotificationType) notification.getType();
      if (type != CONSUMER_CREATED)
         return;

      TypedProperties props = notification.getProperties();

      SimpleString protocolName = props.getSimpleStringProperty(ManagementHelper.HDR_PROTOCOL_NAME);

      if (protocolName == null || !protocolName.toString().equals(MQTTProtocolManagerFactory.MQTT_PROTOCOL_NAME))
         return;

      int distance = props.getIntProperty(ManagementHelper.HDR_DISTANCE);

      if (distance > 0) {
         SimpleString queueName = props.getSimpleStringProperty(ManagementHelper.HDR_ROUTING_NAME);

         Binding binding = server.getPostOffice().getBinding(queueName);
         if (binding != null) {
            Queue queue = (Queue) binding.getBindable();
            String clientId = props.getSimpleStringProperty(ManagementHelper.HDR_CLIENT_ID).toString();
            //If the client ID represents a client already connected to the server then the server MUST disconnect the existing client.
            //Avoid consumers with the same client ID in the cluster appearing at different nodes at the same time
            Collection<Consumer> consumersSet = queue.getConsumers();
            for (Consumer consumer : consumersSet) {
               ServerConsumerImpl serverConsumer = (ServerConsumerImpl) consumer;
               if (clientId.equals(serverConsumer.getConnectionClientID()))
                  serverConsumer.getRemotingConnection().destroy();
            }
         }
      }
   }

   @Override
   public ProtocolManagerFactory getFactory() {
      return new MQTTProtocolManagerFactory();
   }

   @Override
   public void updateInterceptors(List incoming, List outgoing) {
      this.incomingInterceptors.clear();
      this.incomingInterceptors.addAll(getFactory().filterInterceptors(incoming));

      this.outgoingInterceptors.clear();
      this.outgoingInterceptors.addAll(getFactory().filterInterceptors(outgoing));
   }

   @Override
   public ConnectionEntry createConnectionEntry(Acceptor acceptorUsed, Connection connection) {
      try {
         MQTTConnection mqttConnection = new MQTTConnection(connection);
         ConnectionEntry entry = new ConnectionEntry(mqttConnection, null, System.currentTimeMillis(), MQTTUtil.DEFAULT_KEEP_ALIVE_FREQUENCY);

         NettyServerConnection nettyConnection = ((NettyServerConnection) connection);
         MQTTProtocolHandler protocolHandler = nettyConnection.getChannel().pipeline().get(MQTTProtocolHandler.class);
         protocolHandler.setConnection(mqttConnection, entry);
         return entry;
      } catch (Exception e) {
         log.error(e);
         return null;
      }
   }

   @Override
   public boolean acceptsNoHandshake() {
      return false;
   }

   @Override
   public void removeHandler(String name) {
      // TODO add support for handlers
   }

   @Override
   public void handleBuffer(RemotingConnection connection, ActiveMQBuffer buffer) {
      connection.bufferReceived(connection.getID(), buffer);
   }

   @Override
   public void addChannelHandlers(ChannelPipeline pipeline) {
      pipeline.addLast(MqttEncoder.INSTANCE);
      pipeline.addLast(new MqttDecoder(MQTTUtil.MAX_MESSAGE_SIZE));

      pipeline.addLast(new MQTTProtocolHandler(server, this));
   }

   /**
    * The protocol handler passes us an 8 byte long array from the transport.  We sniff these first 8 bytes to see
    * if they match the first 8 bytes from MQTT Connect packet.  In many other protocols the protocol name is the first
    * thing sent on the wire.  However, in MQTT the protocol name doesn't come until later on in the CONNECT packet.
    *
    * In order to fully identify MQTT protocol via protocol name, we need up to 12 bytes.  However, we can use other
    * information from the connect packet to infer that the MQTT protocol is being used.  This is enough to identify MQTT
    * and add the Netty codec in the pipeline.  The Netty codec takes care of things from here.
    *
    * MQTT CONNECT PACKET: See MQTT 3.1.1 Spec for more info.
    *
    * Byte 1: Fixed Header Packet Type.  0b0001000 (16) = MQTT Connect
    * Byte 2-[N]: Remaining length of the Connect Packet (encoded with 1-4 bytes).
    *
    * The next set of bytes represents the UTF8 encoded string MQTT (MQTT 3.1.1) or MQIsdp (MQTT 3.1)
    * Byte N: UTF8 MSB must be 0
    * Byte N+1: UTF8 LSB must be (4(MQTT) or 6(MQIsdp))
    * Byte N+1: M (first char from the protocol name).
    *
    * Max no bytes used in the sequence = 8.
    */
   @Override
   public boolean isProtocol(byte[] array) {
      ByteBuf buf = Unpooled.wrappedBuffer(array);

      if (!(buf.readByte() == 16 && validateRemainingLength(buf) && buf.readByte() == (byte) 0)) return false;
      byte b = buf.readByte();
      return ((b == 4 || b == 6) && (buf.readByte() == 77));
   }

   private boolean validateRemainingLength(ByteBuf buffer) {
      byte msb = (byte) 0b10000000;
      for (byte i = 0; i < 4; i++) {
         if ((buffer.readByte() & msb) != msb)
            return true;
      }
      return false;
   }

   @Override
   public void handshake(NettyServerConnection connection, ActiveMQBuffer buffer) {
   }

   @Override
   public List<String> websocketSubprotocolIdentifiers() {
      return websocketRegistryNames;
   }

   public void invokeIncoming(MqttMessage mqttMessage, MQTTConnection connection) {
      super.invokeInterceptors(this.incomingInterceptors, mqttMessage, connection);
   }

   public void invokeOutgoing(MqttMessage mqttMessage, MQTTConnection connection) {
      super.invokeInterceptors(this.outgoingInterceptors, mqttMessage, connection);
   }

   public boolean isClientConnected(String clientId, MQTTConnection connection) {
      return connectedClients.get(clientId).equals(connection);
   }

   public void removeConnectedClient(String clientId) {
      connectedClients.remove(clientId);
   }

   /**
    * @param clientId
    * @param connection
    * @return the {@code MQTTConnection} that the added connection replaced or null if there was no previous entry for
    * the {@code clientId}
    */
   public MQTTConnection addConnectedClient(String clientId, MQTTConnection connection) {
      return connectedClients.put(clientId, connection);
   }
}

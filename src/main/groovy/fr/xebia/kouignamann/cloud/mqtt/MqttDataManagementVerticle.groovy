package fr.xebia.kouignamann.cloud.mqtt

import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.vertx.groovy.platform.Verticle
import org.vertx.java.core.json.impl.Json

class MqttDataManagementVerticle extends Verticle implements MqttCallback {
    def logger

    boolean started
    MqttClient client
    MqttConnectOptions options

    def start() {
        logger = container.logger

        started = true
        configure()

        logger.info "Start -> Done initialize handler";
    }

    def configure() {
        // FIXME how to use conf.json with cloudbees
        //String uri = config['server-uri']
        //String clientId = config['client-id']
        def uri = System.getProperty('mqtt.uri', 'tcp://m10.cloudmqtt.com:10325')
        def clientId = System.getProperty('mqtt.clientId', 'cloud')
        def username = System.getProperty('mqtt.username', 'kouign-amann')
        def password = System.getProperty('mqtt.password', 'kouign-amann')

        logger.info "Connect to MQTT broker $uri with username: $username, clientId: $clientId"

        client = new MqttClient(uri, clientId, new MemoryPersistence())
        client.setCallback(this)

        options = new MqttConnectOptions()
        options.setPassword(password.getChars())
        options.setUserName(username)
        options.setConnectionTimeout(MqttConnectOptions.CONNECTION_TIMEOUT_DEFAULT * 4)
        options.setKeepAliveInterval(10)
        //options.setCleanSession(true)

        try {
            client.connect(options)
            logger.info "MQTT connected to $client.serverURI with clientId: $clientId options: $options"
            client.subscribe('fr.xebia.kouignamann.nuc.central.processSingleVote', 2)
        } catch (MqttException e) {
            logger.error "Cannot connect to $client.serverURI with clientId: $clientId, options:$options", e
        }
    }



    @Override
    synchronized void connectionLost(Throwable throwable) {
        if (throwable instanceof MqttException) {
            MqttException mqttException = (MqttException) throwable;
            switch (mqttException.reasonCode) {
                case MqttException.REASON_CODE_CONNECTION_LOST:
                case MqttException.REASON_CODE_CLIENT_DISCONNECTING:
                case MqttException.REASON_CODE_CONNECT_IN_PROGRESS:
                    logger.warn "MQTT connectionLost! $throwable"
                    break;
                default:
                    logger.warn "MQTT connectionLost! $throwable", throwable

            }
        } else {
            logger.warn "MQTT connectionLost! $throwable", throwable
        }
        while (started && !client.isConnected()) {
            try {
                client?.connect(options)
                sleep 1000
            } catch (Exception e) {
                logger.error "Cannot reconnect", e
            }
        }

    }

    @Override
    void messageArrived(String s, MqttMessage mqttMessage) throws Exception {
        logger.info "messageArrived: $mqttMessage"
        def jsonMessage = Json.decodeValue(new String(mqttMessage.getPayload()), Map)
        def dtInterval = getInterval(new Date(jsonMessage.voteTime))
        vertx.eventBus.send("vertx.database.db",
                [action: "insert", stmt: """
                    INSERT INTO votes VALUES (?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                    `nfc_id` = values(nfc_id),
                    `rasp_id` = values(rasp_id),
                    `slot_dt` = values(slot_dt),
                    `note` = values(note),
                    `dt` = values(dt)
                    """, values: [jsonMessage.nfcId + "_" + dtInterval, jsonMessage.nfcId,
                        jsonMessage.hardwareUid, dtInterval, jsonMessage.note, new Date(jsonMessage.voteTime).format('yyyy-MM-dd HH:mm:ss')]
                ],
                { response ->
                    //logger.info response
                })
    }

    def getInterval(Date date) {
        Calendar c = date.toCalendar()
        if (c.get(Calendar.MINUTE) > 30) {
            c.set(Calendar.HOUR, c.get(Calendar.HOUR) + 1)
        }
        return c.format("YYYY-MM-dd-HH")
    }

    @Override
    void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {
        //logger.info "deliveryComplete"
    }

    @Override
    def stop() {
        logger.info "Stop Mqtt client"
        started = false
        if(client.isConnected())
            client.disconnect()
        client.close()
    }
}
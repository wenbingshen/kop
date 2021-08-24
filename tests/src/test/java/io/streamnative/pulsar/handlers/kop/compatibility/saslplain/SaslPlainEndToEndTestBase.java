package io.streamnative.pulsar.handlers.kop.compatibility.saslplain;


import static org.mockito.Mockito.spy;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

import com.google.common.collect.Sets;
import io.jsonwebtoken.SignatureAlgorithm;
import io.streamnative.kafka.client.api.Consumer;
import io.streamnative.kafka.client.api.ConsumerRecord;
import io.streamnative.kafka.client.api.Header;
import io.streamnative.kafka.client.api.KafkaClientFactory;
import io.streamnative.kafka.client.api.KafkaClientFactoryImpl;
import io.streamnative.kafka.client.api.KafkaVersion;
import io.streamnative.kafka.client.api.Producer;
import io.streamnative.kafka.client.api.ProducerConfiguration;
import io.streamnative.kafka.client.api.RecordMetadata;
import io.streamnative.pulsar.handlers.kop.KafkaServiceConfiguration;
import io.streamnative.pulsar.handlers.kop.compatibility.DefaultKafkaClientFactory;
import lombok.Cleanup;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.security.JaasUtils;
import org.apache.pulsar.broker.ServiceConfiguration;
import org.apache.pulsar.broker.authentication.AuthenticationProviderToken;
import org.apache.pulsar.broker.authentication.utils.AuthTokenUtils;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.impl.auth.AuthenticationToken;
import org.apache.pulsar.common.policies.data.AuthAction;
import org.apache.pulsar.common.policies.data.TenantInfo;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import javax.crypto.SecretKey;
import javax.security.auth.login.Configuration;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Slf4j
public class SaslPlainEndToEndTestBase extends KopProtocolHandlerTestBase{
    private static final String SIMPLE_USER = "muggle_user";
    private static final String TENANT = "SaslPlainTest";
    private static final String ANOTHER_USER = "death_eater_user";
    private static final String ADMIN_USER = "admin_user";
    private static final String NAMESPACE = "ns1";
    private static final String KAFKA_TOPIC = "topic1";
    private static final String TOPIC = "persistent://" + TENANT + "/" + NAMESPACE + "/" + KAFKA_TOPIC;
    private String adminToken;
    private String userToken;
    private String anotherToken;
    private File jaasConfigFile;

    protected Map<KafkaVersion, KafkaClientFactory> kafkaClientFactories = Arrays.stream(KafkaVersion.values())
            .collect(Collectors.toMap(
                    version -> version,
                    version -> {
                        if (version.equals(KafkaVersion.DEFAULT)) {
                            return new DefaultKafkaClientFactory();
                        } else {
                            return new KafkaClientFactoryImpl(version);
                        }
                    },
                    (k, v) -> {
                        throw new IllegalStateException("Duplicated key: " + k);
                    }, TreeMap::new));

    public SaslPlainEndToEndTestBase(final String entryFormat) {
        super(entryFormat);
    }

    @BeforeClass
    @Override
    protected void setup() throws Exception {
        SecretKey secretKey = AuthTokenUtils.createSecretKey(SignatureAlgorithm.HS256);

        AuthenticationProviderToken provider = new AuthenticationProviderToken();

        Properties properties = new Properties();
        properties.setProperty("tokenSecretKey", AuthTokenUtils.encodeKeyBase64(secretKey));
        ServiceConfiguration authConf = new ServiceConfiguration();
        authConf.setProperties(properties);
        provider.initialize(authConf);

        userToken = AuthTokenUtils.createToken(secretKey, SIMPLE_USER, Optional.empty());
        adminToken = AuthTokenUtils.createToken(secretKey, ADMIN_USER, Optional.empty());
        anotherToken = AuthTokenUtils.createToken(secretKey, ANOTHER_USER, Optional.empty());

        super.resetConfig();
        conf.setKopAllowedNamespaces(Collections.singleton(TENANT + "/" + NAMESPACE));
        ((KafkaServiceConfiguration) conf).setSaslAllowedMechanisms(Sets.newHashSet("PLAIN"));
        ((KafkaServiceConfiguration) conf).setKafkaMetadataTenant("internal");
        ((KafkaServiceConfiguration) conf).setKafkaMetadataNamespace("__kafka");

        conf.setClusterName(super.configClusterName);
        conf.setAuthorizationEnabled(true);
        conf.setAuthenticationEnabled(true);
        conf.setAuthorizationAllowWildcardsMatching(true);
        conf.setSuperUserRoles(Sets.newHashSet(ADMIN_USER));
        conf.setAuthenticationProviders(
                Sets.newHashSet("org.apache.pulsar.broker.authentication."
                        + "AuthenticationProviderToken"));
        conf.setBrokerClientAuthenticationPlugin(AuthenticationToken.class.getName());
        conf.setBrokerClientAuthenticationParameters("token:" + adminToken);
        conf.setProperties(properties);

        super.internalSetup();

        admin.tenants().createTenant(TENANT,
                TenantInfo.builder()
                        .adminRoles(Collections.singleton(ADMIN_USER))
                        .allowedClusters(Collections.singleton(configClusterName))
                        .build());
        admin.namespaces().createNamespace(TENANT + "/" + NAMESPACE);
        admin.topics().createPartitionedTopic(TOPIC, 1);
        admin
                .namespaces().grantPermissionOnNamespace(TENANT + "/" + NAMESPACE, SIMPLE_USER,
                Sets.newHashSet(AuthAction.consume, AuthAction.produce));
    }

    @Override
    protected void createAdmin() throws Exception {
        super.admin = spy(PulsarAdmin.builder().serviceHttpUrl(brokerUrl.toString())
                .authentication(this.conf.getBrokerClientAuthenticationPlugin(),
                        this.conf.getBrokerClientAuthenticationParameters()).build());
    }

    @AfterClass
    protected void cleanup() throws Exception {
        super.internalCleanup();
        jaasConfigFile.delete();
    }

    @AfterMethod
    protected void cleanJaasConf() {
        jaasConfigFile.delete();
        Configuration.setConfiguration(null);
    }

    protected void writeJaasFile(String username, String password) throws IOException {
        jaasConfigFile = File.createTempFile("jaas", ".conf");
        jaasConfigFile.deleteOnExit();
        System.setProperty(JaasUtils.JAVA_LOGIN_CONFIG_PARAM, jaasConfigFile.toString());
        StringBuilder builder = new StringBuilder();
        builder.append("org.apache.kafka.common.security.plain.PlainLoginModule");
        builder.append(' ');
        builder.append("required");
        builder.append(' ');
        builder.append("username");
        builder.append('=');
        builder.append(username);
        builder.append(' ');
        builder.append("password");
        builder.append('=');
        builder.append(password);
        builder.append(';');
        List<String> lines = Arrays.asList("KafkaClient { ", builder.toString(), "};");
        Files.write(jaasConfigFile.toPath(), lines, StandardCharsets.UTF_8);
    }

    @Test(timeOut = 40000)
    void simpleProduceAndConsume() throws Exception {

        writeJaasFile(TENANT + "/" + NAMESPACE, "token:" + userToken);

        final List<String> keys = new ArrayList<>();
        final List<String> values = new ArrayList<>();
        final List<Header> headers = new ArrayList<>();

        long offset = 0;
        for (KafkaVersion version : kafkaClientFactories.keySet()) {
            final Producer<String, String> producer = kafkaClientFactories.get(version)
                    .createProducer(producerConfigurationWithSaslPlain(version,
                            TENANT + "/" + NAMESPACE,
                            "token:" + userToken));
            // send a message that only contains value
            String value = "value-from-" + version.name() + offset;
            values.add("value-from-" + version.name() + offset);

            RecordMetadata metadata = producer.newContextBuilder(KAFKA_TOPIC, value).build().sendAsync().get();
            log.info("Kafka client {} sent {} to {}", version, value, metadata);
            Assert.assertEquals(metadata.getTopic(), KAFKA_TOPIC);
            Assert.assertEquals(metadata.getPartition(), 0);
            Assert.assertEquals(metadata.getOffset(), offset);
            offset++;

            // send a message that contains key and headers, which are optional
            String key = "key-from-" + version.name() + offset;
            value = "value-from-" + version.name() + offset;
            keys.add(key);
            values.add(value);
            // Because there is no header in ProducerRecord before 0.11.x.
            if (!version.equals(KafkaVersion.KAFKA_0_10_0_0)) {
                headers.add(new Header("header-" + key, "header-" + value));
            }

            metadata = producer.newContextBuilder(KAFKA_TOPIC, value)
                    .key(key)
                    .headers(headers.subList(headers.size() - 1, headers.size()))
                    .build()
                    .sendAsync()
                    .get();
            log.info("Kafka client {} sent {} (key={}) to {}", version, value, key, metadata);
            Assert.assertEquals(metadata.getTopic(), KAFKA_TOPIC);
            Assert.assertEquals(metadata.getPartition(), 0);
            Assert.assertEquals(metadata.getOffset(), offset);
            offset++;

            producer.close();
        }

        for (KafkaVersion version : kafkaClientFactories.keySet()) {
            final Consumer<String, String> consumer = kafkaClientFactories.get(version)
                    .createConsumer(consumerConfigurationWithSaslPlain(version,
                            TENANT + "/" + NAMESPACE,
                            "token:" + userToken));
            consumer.subscribe(KAFKA_TOPIC);
            final List<ConsumerRecord<String, String>> records = consumer.receiveUntil(values.size(), 6000);
            Assert.assertEquals(records.stream().map(ConsumerRecord::getValue).collect(Collectors.toList()), values);
            if (conf.getEntryFormat().equals("pulsar")) {
                // NOTE: PulsarEntryFormatter will encode an empty String as key if key doesn't exist
                Assert.assertEquals(records.stream()
                        .map(ConsumerRecord::getKey)
                        .filter(key -> key != null && !key.isEmpty())
                        .collect(Collectors.toList()), keys);
            } else {
                Assert.assertEquals(records.stream()
                        .map(ConsumerRecord::getKey)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList()), keys);
            }
            Assert.assertEquals(records.stream()
                    .map(ConsumerRecord::getHeaders)
                    .filter(Objects::nonNull)
                    .map(headerList -> headerList.get(0))
                    .collect(Collectors.toList()), headers);

            // no more records
            List<ConsumerRecord<String, String>> emptyRecords = consumer.receive(200);
            assertTrue(emptyRecords.isEmpty());

            // ensure that we can list the topic
            Map<String, List<PartitionInfo>> result = consumer.listTopics(1000);
            assertEquals(result.size(), 1);
            assertTrue(result.containsKey(KAFKA_TOPIC),
                    "list of topics " + result.keySet().toString() + "  does not contains " + KAFKA_TOPIC);
            consumer.close();
        }
    }

    @Test(timeOut = 20000)
    void badCredentialFail() throws Exception {
        writeJaasFile(TENANT + "/" + NAMESPACE, "token:dsa");
        for (KafkaVersion version : kafkaClientFactories.keySet()) {
            try {
                @Cleanup final Producer<String, String> producer = kafkaClientFactories.get(version)
                        .createProducer(producerConfigurationWithSaslPlain(version,
                                TENANT + "/" + NAMESPACE,
                                "token:" + userToken));
                producer.newContextBuilder(KAFKA_TOPIC, "").build().sendAsync().get();
                fail("should have failed");
            } catch (Exception e) {
                assertTrue(e.getMessage().contains("SaslAuthenticationException"));
            }
        }
    }

    @Test(timeOut = 20000)
    void badUserFail() throws Exception {

        writeJaasFile(TENANT + "/" + NAMESPACE, "token:" + anotherToken);

        for (KafkaVersion version : kafkaClientFactories.keySet()) {
            try {
                @Cleanup final Producer<String, String> producer = kafkaClientFactories.get(version)
                        .createProducer(producerConfigurationWithSaslPlain(version,
                                TENANT + "/" + NAMESPACE,
                                "token:" + userToken));
                producer.newContextBuilder(KAFKA_TOPIC, "").build().sendAsync().get();
                fail("should have failed");
            } catch (Exception e) {
                assertTrue(e.getMessage().contains("TopicAuthorizationException"));
            }
        }
    }


    @Test(timeOut = 60000)
    void clientWithoutAuth() throws Exception {
        final int metadataTimeoutMs = 3000;

        for (KafkaVersion version : kafkaClientFactories.keySet()) {
            try {
                @Cleanup final Producer<String, String> producer = kafkaClientFactories.get(version)
                        .createProducer(ProducerConfiguration.builder()
                                .bootstrapServers("localhost:" + getKafkaBrokerPort())
                                .keySerializer(version.getStringSerializer())
                                .valueSerializer(version.getStringSerializer())
                                .maxBlockMs(Integer.toString(metadataTimeoutMs))
                                .build());

                producer.newContextBuilder(KAFKA_TOPIC, "hello").build().sendAsync().get();
                fail("should have failed");
            } catch (Exception e) {
                assertTrue(e.getCause() instanceof TimeoutException);
                assertTrue(e.getMessage().contains("Failed to update metadata"));
            }
        }
    }
}

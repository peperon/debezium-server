/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.server.milvus;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.enterprise.event.Observes;

import org.awaitility.Awaitility;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

import io.debezium.connector.postgresql.connection.PostgresConnection;
import io.debezium.jdbc.JdbcConfiguration;
import io.debezium.server.TestConfigSource;
import io.debezium.server.events.ConnectorCompletedEvent;
import io.debezium.util.Testing;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.response.QueryResp.QueryResult;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration test that verifies basic reading from PostgreSQL database and writing to a Milvus vector database.
 *
 * @author Jiri Pechanec
 */
@QuarkusTest
@QuarkusTestResource(VectorPostgresTestResourceLifecycleManager.class)
@QuarkusTestResource(MilvusTestResourceLifecycleManager.class)
public class MilvusIT {

    private static final int MESSAGE_COUNT = 2;
    public static final String COLLECTION_NAME = "testc_inventory_t_vector";

    @ConfigProperty(name = "debezium.source.database.hostname")
    String dbHostname;

    @ConfigProperty(name = "debezium.source.database.port")
    String dbPort;

    @ConfigProperty(name = "debezium.source.database.user")
    String dbUser;

    @ConfigProperty(name = "debezium.source.database.password")
    String dbPassword;

    @ConfigProperty(name = "debezium.source.database.dbname")
    String dbName;

    @ConfigProperty(name = "debezium.sink.milvus.uri")
    String milvusUri;

    private MilvusClientV2 client;

    {
        Testing.Files.delete(TestConfigSource.OFFSET_STORE_PATH);
        Testing.Files.createTestingFile(MilvusTestConfigSource.OFFSET_STORE_PATH);
    }

    @BeforeEach
    void setupDependencies() throws Exception {
        Testing.Print.enable();

        final var config = ConnectConfig.builder()
                .uri(milvusUri)
                .build();
        client = new MilvusClientV2(config);
    }

    void connectorCompleted(@Observes ConnectorCompletedEvent event) throws Exception {
        if (!event.isSuccess()) {
            throw new RuntimeException(event.getError().get());
        }
    }

    @Test
    public void testMilvus() throws Exception {
        final var queryResults = new AtomicReference<List<QueryResult>>();
        Awaitility.await().atMost(Duration.ofSeconds(MilvusTestConfigSource.waitForSeconds())).until(() -> {
            final var request = QueryReq.builder()
                    .collectionName(COLLECTION_NAME)
                    .filter("value like \"%\"")
                    .build();
            final var response = client.query(request);
            queryResults.set(response.getQueryResults());
            return response.getQueryResults().size() == MESSAGE_COUNT;
        });

        assertThat(queryResults.get()).hasSize(MESSAGE_COUNT);
        final var dataRead1 = queryResults.get().get(0).getEntity();
        final var dataRead2 = queryResults.get().get(1).getEntity();

        assertThat(dataRead1.get("pk")).isEqualTo(1l);
        assertThat(dataRead1.get("value")).isEqualTo("one");
        assertThat(dataRead1.get("f_vector")).isEqualTo(List.of(1.1f, 1.2f, 1.3f));
        assertThat(dataRead1.get("f_json")).isInstanceOf(JsonObject.class);

        assertThat(dataRead2.get("pk")).isEqualTo(2l);
        assertThat(dataRead2.get("value")).isEqualTo("two");
        assertThat(dataRead2.get("f_vector")).isEqualTo(List.of(2.1f, 2.2f, 2.3f));
        assertThat(dataRead2.get("f_json")).isInstanceOf(JsonObject.class);

        final JdbcConfiguration config = JdbcConfiguration.create()
                .with("hostname", dbHostname)
                .with("port", dbPort)
                .with("user", dbUser)
                .with("password", dbPassword)
                .with("dbname", dbName)
                .build();
        try (PostgresConnection connection = new PostgresConnection(config, "Debezium Milvus Test")) {
            connection.execute("UPDATE inventory.t_vector SET value = 'two-up' WHERE pk = 2");
        }

        Awaitility.await().atMost(Duration.ofSeconds(MilvusTestConfigSource.waitForSeconds())).until(() -> {
            final var request = QueryReq.builder()
                    .collectionName(COLLECTION_NAME)
                    .filter("value like \"two-up\"")
                    .build();
            final var response = client.query(request);
            queryResults.set(response.getQueryResults());
            return response.getQueryResults().size() == 1;
        });

        assertThat(queryResults.get()).hasSize(1);
        final var dataUpdate2 = queryResults.get().get(0).getEntity();

        assertThat(dataUpdate2.get("pk")).isEqualTo(2l);
        assertThat(dataUpdate2.get("value")).isEqualTo("two-up");
        assertThat(dataUpdate2.get("f_vector")).isEqualTo(List.of(2.1f, 2.2f, 2.3f));

        try (PostgresConnection connection = new PostgresConnection(config, "Debezium Milvus Test")) {
            connection.execute("DELETE FROM inventory.t_vector WHERE pk = 2");
        }

        Awaitility.await().atMost(Duration.ofSeconds(MilvusTestConfigSource.waitForSeconds())).until(() -> {
            final var request = QueryReq.builder()
                    .collectionName(COLLECTION_NAME)
                    .filter("value like \"two-up\"")
                    .build();
            final var response = client.query(request);
            queryResults.set(response.getQueryResults());
            return response.getQueryResults().size() == 0;
        });

        assertThat(queryResults.get()).hasSize(0);
    }
}

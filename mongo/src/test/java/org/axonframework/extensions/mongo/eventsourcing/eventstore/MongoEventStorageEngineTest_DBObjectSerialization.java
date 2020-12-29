/*
 * Copyright (c) 2010-2020. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.extensions.mongo.eventsourcing.eventstore;

import com.mongodb.BasicDBObject;
import com.mongodb.ServerAddress;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import org.axonframework.common.jdbc.PersistenceExceptionResolver;
import org.axonframework.eventsourcing.eventstore.AbstractEventStorageEngine;
import org.axonframework.extensions.mongo.DefaultMongoTemplate;
import org.axonframework.extensions.mongo.serialization.DBObjectXStreamSerializer;
import org.axonframework.serialization.Serializer;
import org.axonframework.serialization.upcasting.event.EventUpcaster;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Collections;

/**
 * Test class validating the {@link MongoEventStorageEngine} with the {@link DBObjectXStreamSerializer}.
 *
 * @author Rene de Waele
 */
@Testcontainers
class MongoEventStorageEngineTest_DBObjectSerialization extends AbstractMongoEventStorageEngineTest {

    @Container
    private static final MongoDBContainer MONGO_DB_CONTAINER = new MongoDBContainer("mongo");
    private static final Serializer DB_OBJECT_XSTREAM_SERIALIZER = DBObjectXStreamSerializer.builder().build();

    private DefaultMongoTemplate mongoTemplate;
    @SuppressWarnings("FieldCanBeLocal")
    private MongoEventStorageEngine testSubject;

    @BeforeEach
    void setUp() {
        MongoSettingsFactory mongoSettingsFactory = new MongoSettingsFactory();
        ServerAddress containerAddress =
                new ServerAddress(MONGO_DB_CONTAINER.getHost(), MONGO_DB_CONTAINER.getFirstMappedPort());
        mongoSettingsFactory.setMongoAddresses(Collections.singletonList(containerAddress));
        mongoSettingsFactory.setConnectionsPerHost(100);
        mongoSettingsFactory.setWriteConcern(WriteConcern.JOURNALED);
        MongoFactory mongoFactory = new MongoFactory();
        mongoFactory.setMongoClientSettings(mongoSettingsFactory.createMongoClientSettings());
        MongoClient mongoClient = mongoFactory.createMongo();

        mongoTemplate = DefaultMongoTemplate.builder()
                                            .mongoDatabase(mongoClient)
                                            .build();
        mongoTemplate.eventCollection().deleteMany(new BasicDBObject());
        mongoTemplate.snapshotCollection().deleteMany(new BasicDBObject());
        mongoTemplate.eventCollection().dropIndexes();
        mongoTemplate.snapshotCollection().dropIndexes();

        testSubject = MongoEventStorageEngine.builder()
                                             .snapshotSerializer(DB_OBJECT_XSTREAM_SERIALIZER)
                                             .eventSerializer(DB_OBJECT_XSTREAM_SERIALIZER)
                                             .mongoTemplate(mongoTemplate)
                                             .build();
        setTestSubject(testSubject);
    }

    @Override
    @AfterEach
    public void tearDown() {
        mongoTemplate.eventCollection().dropIndexes();
        mongoTemplate.snapshotCollection().dropIndexes();
        mongoTemplate.eventCollection().deleteMany(new BasicDBObject());
        mongoTemplate.snapshotCollection().deleteMany(new BasicDBObject());
    }

    @Override
    protected AbstractEventStorageEngine createEngine(EventUpcaster upcasterChain) {
        return MongoEventStorageEngine.builder()
                                      .snapshotSerializer(DB_OBJECT_XSTREAM_SERIALIZER)
                                      .upcasterChain(upcasterChain)
                                      .eventSerializer(DB_OBJECT_XSTREAM_SERIALIZER)
                                      .mongoTemplate(mongoTemplate)
                                      .build();
    }

    @Override
    protected AbstractEventStorageEngine createEngine(PersistenceExceptionResolver persistenceExceptionResolver) {
        return MongoEventStorageEngine.builder()
                                      .snapshotSerializer(DB_OBJECT_XSTREAM_SERIALIZER)
                                      .persistenceExceptionResolver(persistenceExceptionResolver)
                                      .eventSerializer(DB_OBJECT_XSTREAM_SERIALIZER)
                                      .mongoTemplate(mongoTemplate)
                                      .build();
    }
}

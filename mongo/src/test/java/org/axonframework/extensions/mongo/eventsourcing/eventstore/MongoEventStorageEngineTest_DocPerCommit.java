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
import org.axonframework.common.jdbc.PersistenceExceptionResolver;
import org.axonframework.eventhandling.DomainEventData;
import org.axonframework.eventhandling.DomainEventMessage;
import org.axonframework.eventhandling.EventMessage;
import org.axonframework.eventhandling.TrackedEventData;
import org.axonframework.eventhandling.TrackingToken;
import org.axonframework.eventsourcing.eventstore.EventStoreException;
import org.axonframework.extensions.mongo.MongoTemplate;
import org.axonframework.extensions.mongo.eventsourcing.eventstore.documentpercommit.DocumentPerCommitStorageStrategy;
import org.axonframework.extensions.mongo.serialization.DBObjectXStreamSerializer;
import org.axonframework.extensions.mongo.util.MongoTemplateFactory;
import org.axonframework.modelling.command.AggregateStreamCreationException;
import org.axonframework.serialization.Serializer;
import org.axonframework.serialization.upcasting.event.EventUpcaster;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static java.util.stream.Collectors.toList;
import static org.axonframework.eventsourcing.utils.EventStoreTestUtils.AGGREGATE;
import static org.axonframework.eventsourcing.utils.EventStoreTestUtils.createEvent;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class validating the {@link MongoEventStorageEngine} using the document-per-commit strategy.
 *
 * @author Rene de Waele
 */
@Testcontainers
class MongoEventStorageEngineTest_DocPerCommit extends AbstractMongoEventStorageEngineTest {

    @Container
    private static final MongoDBContainer MONGO_CONTAINER = new MongoDBContainer("mongo");
    private static final Serializer DB_OBJECT_XSTREAM_SERIALIZER = DBObjectXStreamSerializer.builder().build();

    private MongoTemplate mongoTemplate;
    private MongoEventStorageEngine testSubject;

    @BeforeEach
    void setUp() {
        mongoTemplate = MongoTemplateFactory.build(
                MONGO_CONTAINER.getHost(), MONGO_CONTAINER.getFirstMappedPort()
        );
        mongoTemplate.eventCollection().deleteMany(new BasicDBObject());
        mongoTemplate.snapshotCollection().deleteMany(new BasicDBObject());
        mongoTemplate.eventCollection().dropIndexes();
        mongoTemplate.snapshotCollection().dropIndexes();

        testSubject = MongoEventStorageEngine.builder()
                                             .snapshotSerializer(DB_OBJECT_XSTREAM_SERIALIZER)
                                             .eventSerializer(DB_OBJECT_XSTREAM_SERIALIZER)
                                             .mongoTemplate(mongoTemplate)
                                             .storageStrategy(new DocumentPerCommitStorageStrategy())
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

    @Test
    void testFetchHighestSequenceNumber() {
        testSubject.appendEvents(createEvent(0), createEvent(1));
        testSubject.appendEvents(createEvent(2), createEvent(3));

        Optional<Long> optionalLastSequence = testSubject.lastSequenceNumberFor(AGGREGATE);
        assertTrue(optionalLastSequence.isPresent());
        assertEquals(3L, optionalLastSequence.get());
        assertFalse(testSubject.lastSequenceNumberFor("not-exist").isPresent());
    }

    @Test
    void testFetchingEventsReturnsResultsWhenMoreThanBatchSizeCommitsAreAvailable() {
        testSubject.appendEvents(createEvent(0), createEvent(1), createEvent(2));
        testSubject.appendEvents(createEvent(3), createEvent(4), createEvent(5));
        testSubject.appendEvents(createEvent(6), createEvent(7), createEvent(8));
        testSubject.appendEvents(createEvent(9), createEvent(10), createEvent(11));
        testSubject.appendEvents(createEvent(12), createEvent(13), createEvent(14));

        List<? extends TrackedEventData<?>> result = testSubject.fetchTrackedEvents(null, 2);
        TrackedEventData<?> last = result.get(result.size() - 1); // We decide to omit the last event
        last.trackingToken();
        List<? extends TrackedEventData<?>> actual = testSubject.fetchTrackedEvents(last.trackingToken(), 2);
        assertFalse(actual.isEmpty(), "Expected to retrieve some events");
        // We want to make sure we get the first event from the next commit
        assertEquals(result.size(), ((DomainEventData<?>) actual.get(0)).getSequenceNumber());
    }

    @Test
    void testFetchingEventsReturnsResultsWhenMoreThanBatchSizeCommitsAreAvailable_PartiallyReadingCommit() {
        testSubject.appendEvents(createEvent(0), createEvent(1), createEvent(2));
        testSubject.appendEvents(createEvent(3), createEvent(4), createEvent(5));
        testSubject.appendEvents(createEvent(6), createEvent(7), createEvent(8));
        testSubject.appendEvents(createEvent(9), createEvent(10), createEvent(11));
        testSubject.appendEvents(createEvent(12), createEvent(13), createEvent(14));

        List<? extends TrackedEventData<?>> result = testSubject.fetchTrackedEvents(null, 2);
        TrackedEventData<?> last = result.get(result.size() - 2); // We decide to omit the last event
        last.trackingToken();
        List<? extends TrackedEventData<?>> actual = testSubject.fetchTrackedEvents(last.trackingToken(), 2);
        assertFalse(actual.isEmpty(), "Expected to retrieve some events");
        // We want to make sure we get the last event from the commit
        assertEquals(result.size() - 1, ((DomainEventData<?>) actual.get(0)).getSequenceNumber());
    }

    @Test
    @Override
    public void testStoreDuplicateFirstEventWithExceptionTranslatorThrowsAggregateIdentifierAlreadyExistsException() {
        logger.info("Unique event identifier is not currently guaranteed in the Mongo Event Storage Engine");
    }

    @Test
    @Override
    public void testStoreDuplicateEventWithoutExceptionResolver() {
        testSubject = createEngine((PersistenceExceptionResolver) null);
        assertThrows(EventStoreException.class, () -> {
            testSubject.appendEvents(createEvent(0));
            testSubject.appendEvents(createEvent(0));
        });
    }

    @Test
    @Override
    public void testStoreDuplicateEventWithExceptionTranslator() {
        assertThrows(AggregateStreamCreationException.class, () -> {
            testSubject.appendEvents(createEvent(0));
            testSubject.appendEvents(createEvent(0));
        });
    }

    @SuppressWarnings("JUnit3StyleTestMethodInJUnit4Class")
    @Override
    public void testCreateTokenAtExactTime() {
        DomainEventMessage<String> event1 = createEvent(0, Instant.parse("2007-12-03T10:15:30.00Z"));
        DomainEventMessage<String> event2 = createEvent(1, Instant.parse("2007-12-03T10:15:40.01Z"));
        DomainEventMessage<String> event3 = createEvent(2, Instant.parse("2007-12-03T10:15:35.01Z"));

        testSubject.appendEvents(event1, event2, event3);

        TrackingToken tokenAt = testSubject.createTokenAt(Instant.parse("2007-12-03T10:15:30.00Z"));

        List<EventMessage<?>> readEvents = testSubject.readEvents(tokenAt, false)
                                                      .collect(toList());

        assertEventStreamsById(Arrays.asList(event1, event2, event3), readEvents);
    }

    @SuppressWarnings("JUnit3StyleTestMethodInJUnit4Class")
    @Override
    public void testCreateTokenWithUnorderedEvents() {
        DomainEventMessage<String> event1 = createEvent(0, Instant.parse("2007-12-03T10:15:46.00Z"));
        DomainEventMessage<String> event2 = createEvent(1, Instant.parse("2007-12-03T10:15:40.00Z"));
        DomainEventMessage<String> event3 = createEvent(2, Instant.parse("2007-12-03T10:15:50.00Z"));
        DomainEventMessage<String> event4 = createEvent(3, Instant.parse("2007-12-03T10:15:45.00Z"));
        DomainEventMessage<String> event5 = createEvent(4, Instant.parse("2007-12-03T10:15:42.00Z"));

        testSubject.appendEvents(event1, event2, event3, event4, event5);

        TrackingToken tokenAt = testSubject.createTokenAt(Instant.parse("2007-12-03T10:15:45.00Z"));

        List<EventMessage<?>> readEvents = testSubject.readEvents(tokenAt, false)
                                                      .collect(toList());

        assertEventStreamsById(Arrays.asList(event1, event2, event3, event4, event5), readEvents);
    }

    @Override
    protected MongoEventStorageEngine createEngine(EventUpcaster upcasterChain) {
        return MongoEventStorageEngine.builder()
                                      .upcasterChain(upcasterChain)
                                      .mongoTemplate(mongoTemplate)
                                      .storageStrategy(new DocumentPerCommitStorageStrategy())
                                      .build();
    }

    @Override
    protected MongoEventStorageEngine createEngine(PersistenceExceptionResolver persistenceExceptionResolver) {
        return MongoEventStorageEngine.builder()
                                      .persistenceExceptionResolver(persistenceExceptionResolver)
                                      .mongoTemplate(mongoTemplate)
                                      .storageStrategy(new DocumentPerCommitStorageStrategy())
                                      .build();
    }
}

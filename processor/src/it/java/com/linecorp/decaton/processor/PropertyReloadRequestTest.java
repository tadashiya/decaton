/*
 * Copyright 2023 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.decaton.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.decaton.client.DecatonClient;
import com.linecorp.decaton.processor.internal.HashableByteArray;
import com.linecorp.decaton.processor.runtime.DynamicProperty;
import com.linecorp.decaton.processor.runtime.ProcessorProperties;
import com.linecorp.decaton.processor.runtime.ProcessorSubscription;
import com.linecorp.decaton.processor.runtime.ProcessorsBuilder;
import com.linecorp.decaton.processor.runtime.StaticPropertySupplier;
import com.linecorp.decaton.protobuf.ProtocolBuffersDeserializer;
import com.linecorp.decaton.protocol.Sample.HelloTask;
import com.linecorp.decaton.testing.KafkaClusterExtension;
import com.linecorp.decaton.testing.TestUtils;

public class PropertyReloadRequestTest {
    @RegisterExtension
    public static KafkaClusterExtension rule = new KafkaClusterExtension();

    private String topicName;

    @BeforeEach
    public void setUp() {
        topicName = rule.admin().createRandomTopic(3, 3);
    }

    @AfterEach
    public void tearDown() {
        rule.admin().deleteTopics(true, topicName);
    }

    @Test
    @Timeout(30)
    public void testPropertyDynamicSwitch() throws Exception {
        Set<String> keys = new HashSet<>();

        for (int i = 0; i < 10000; i++) {
            keys.add("key" + i);
        }
        Set<HashableByteArray> processedKeys = ConcurrentHashMap.newKeySet();
        CountDownLatch processLatch = new CountDownLatch(keys.size());

        DecatonProcessor<HelloTask> processor = (context, task) -> {
            processedKeys.add(new HashableByteArray(context.key()));
            processLatch.countDown();
        };

        DynamicProperty<Integer> concurrencyProp =
                new DynamicProperty<>(ProcessorProperties.CONFIG_PARTITION_CONCURRENCY);
        concurrencyProp.set(1);

        DynamicProperty<Integer> recordsProp =
                new DynamicProperty<>(ProcessorProperties.CONFIG_MAX_PENDING_RECORDS);
        recordsProp.set(10);

        try (ProcessorSubscription subscription = TestUtils.subscription(
                rule.bootstrapServers(),
                builder -> builder.processorsBuilder(ProcessorsBuilder
                                                             .consuming(topicName,
                                                                        new ProtocolBuffersDeserializer<>(
                                                                                HelloTask.parser()))
                                                             .thenProcess(processor))
                                  .addProperties(StaticPropertySupplier.of(concurrencyProp, recordsProp)));
             DecatonClient<HelloTask> client = TestUtils.client(topicName, rule.bootstrapServers())) {

            int count = 0;
            for (String key : keys) {
                count++;
                if (count == 1000) {
                    TimeUnit.SECONDS.sleep(1);
                    concurrencyProp.set(3);
                } else if (count == 3000) {
                    TimeUnit.SECONDS.sleep(1);
                    recordsProp.set(20);
                } else if (count == 5000) {
                    TimeUnit.SECONDS.sleep(1);
                    concurrencyProp.set(1);
                    recordsProp.set(5);
                } else if (count == 7500) {
                    TimeUnit.SECONDS.sleep(1);
                    concurrencyProp.set(5);
                } else if (count == 9000) {
                    TimeUnit.SECONDS.sleep(1);
                    recordsProp.set(15);
                }
                client.put(key, HelloTask.getDefaultInstance());
            }
            processLatch.await();
        }

        assertEquals(10000, processedKeys.size());
    }
}

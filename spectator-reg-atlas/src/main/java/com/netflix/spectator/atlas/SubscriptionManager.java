/*
 * Copyright 2014-2019 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.spectator.atlas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.spectator.api.Clock;
import com.netflix.spectator.atlas.impl.Subscription;
import com.netflix.spectator.atlas.impl.Subscriptions;
import com.netflix.spectator.ipc.http.HttpClient;
import com.netflix.spectator.ipc.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Helper for managing the set of LWC subscriptions.
 */
class SubscriptionManager {

  private static final Logger LOGGER = LoggerFactory.getLogger(SubscriptionManager.class);

  private final ObjectMapper mapper;
  private final HttpClient client;
  private final Clock clock;
  private final URI uri;
  private final int connectTimeout;
  private final int readTimeout;
  private final long stepMillis;

  private final long configTTL;

  private final Map<Subscription, Long> subscriptions = new ConcurrentHashMap<>();

  private Subscriptions payload;
  private String etag;

  /** Create a new instance. */
  SubscriptionManager(ObjectMapper mapper, HttpClient client, Clock clock, AtlasConfig config) {
    this.mapper = mapper;
    this.client = client;
    this.clock = clock;
    this.uri = URI.create(config.configUri());
    this.connectTimeout = (int) config.connectTimeout().toMillis();
    this.readTimeout = (int) config.readTimeout().toMillis();
    this.stepMillis = config.step().toMillis();
    this.configTTL = config.configTTL().toMillis();
  }

  /** Returns the current set of active subscriptions. */
  List<Subscription> subscriptions() {
    return new ArrayList<>(subscriptions.keySet());
  }

  /** Refresh the subscriptions from the server. */
  void refresh() {
    // Request latest expressions from the server
    try {
      HttpResponse res = client.get(uri)
          .withConnectTimeout(connectTimeout)
          .withReadTimeout(readTimeout)
          .addHeader("If-None-Match", etag)
          .send()
          .decompress();
      if (res.status() == 304) {
        LOGGER.debug("no modification to subscriptions");
      } else if (res.status() != 200) {
        LOGGER.warn("failed to update subscriptions, received status {}", res.status());
      } else {
        etag = res.header("ETag");
        payload = filterByStep(mapper.readValue(res.entity(), Subscriptions.class));
      }
    } catch (Exception e) {
      LOGGER.warn("failed to update subscriptions (uri={})", uri, e);
    }

    // Update with the current payload, it will be null if there hasn't been a single
    // successful request
    if (payload != null) {
      long now = clock.wallTime();
      payload.update(subscriptions, now, now + configTTL);
    }
  }

  private Subscriptions filterByStep(Subscriptions subs) {
    List<Subscription> subscriptions = subs
        .getExpressions()
        .stream()
        .filter(s -> s.getFrequency() == stepMillis)
        .collect(Collectors.toList());
    return new Subscriptions().withExpressions(subscriptions);
  }
}

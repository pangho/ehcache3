/*
 * Copyright Terracotta, Inc.
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

package org.ehcache.internal.store.offheap.factories;

import org.ehcache.function.BiFunction;
import org.ehcache.function.Predicate;
import org.ehcache.function.Predicates;
import org.ehcache.internal.store.offheap.HeuristicConfiguration;
import org.ehcache.internal.store.offheap.portability.SerializerPortability;
import org.ehcache.spi.serialization.DefaultSerializationProvider;
import org.ehcache.spi.serialization.SerializationProvider;
import org.ehcache.spi.serialization.Serializer;
import org.junit.Test;
import org.terracotta.offheapstore.paging.PageSource;
import org.terracotta.offheapstore.paging.UpfrontAllocatingPageSource;
import org.terracotta.offheapstore.storage.OffHeapBufferStorageEngine;
import org.terracotta.offheapstore.storage.PointerSize;
import org.terracotta.offheapstore.storage.portability.Portability;
import org.terracotta.offheapstore.util.Factory;

import java.util.Map;

import static org.ehcache.internal.store.offheap.OffHeapStoreUtils.getBufferSource;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class EhcacheSegmentTest {

  private EhcacheSegmentFactory.EhcacheSegment<String, String> createTestSegment() {
    return createTestSegment(Predicates.<Map.Entry<String, String>>none(), mock(EhcacheSegmentFactory.EhcacheSegment.EvictionListener.class));
  }
  
  private EhcacheSegmentFactory.EhcacheSegment<String, String> createTestSegment(Predicate<Map.Entry<String, String>> evictionPredicate) {
    return createTestSegment(evictionPredicate, mock(EhcacheSegmentFactory.EhcacheSegment.EvictionListener.class));
  }
  
  private EhcacheSegmentFactory.EhcacheSegment<String, String> createTestSegment(EhcacheSegmentFactory.EhcacheSegment.EvictionListener<String, String> evictionListener) {
    return createTestSegment(Predicates.<Map.Entry<String, String>>none(), evictionListener);
  }
  
  private EhcacheSegmentFactory.EhcacheSegment<String, String> createTestSegment(Predicate<Map.Entry<String, String>> evictionPredicate, EhcacheSegmentFactory.EhcacheSegment.EvictionListener<String, String> evictionListener) {
    HeuristicConfiguration configuration = new HeuristicConfiguration(1024 * 1024);
    SerializationProvider serializationProvider = new DefaultSerializationProvider(null);
    serializationProvider.start(null);
    PageSource pageSource = new UpfrontAllocatingPageSource(getBufferSource(), configuration.getMaximumSize(), configuration.getMaximumChunkSize(), configuration.getMinimumChunkSize());
    Serializer<String> stringSerializer = serializationProvider.createValueSerializer(String.class, EhcacheSegmentTest.class.getClassLoader());
    Portability<String> keyPortability = new SerializerPortability<String>(stringSerializer);
    Portability<String> elementPortability = new SerializerPortability<String>(stringSerializer);
    Factory<OffHeapBufferStorageEngine<String, String>> storageEngineFactory = OffHeapBufferStorageEngine.createFactory(PointerSize.INT, pageSource, configuration.getInitialSegmentTableSize(), keyPortability, elementPortability, false, true);
    return new EhcacheSegmentFactory.EhcacheSegment<String, String>(pageSource, storageEngineFactory.newInstance(), 1, evictionPredicate, evictionListener);
  }

  @Test
  public void testComputeFunctionCalledWhenNoMapping() {
    EhcacheSegmentFactory.EhcacheSegment<String, String> segment = createTestSegment();
    try {
      String value = segment.compute("key", new BiFunction<String, String, String>() {
        @Override
        public String apply(String s, String s2) {
          return "value";
        }
      }, false);
      assertThat(value, is("value"));
      assertThat(segment.get("key"), is(value));
    } finally {
      segment.destroy();
    }
  }

  @Test
  public void testComputeFunctionReturnsSameNoPin() {
    EhcacheSegmentFactory.EhcacheSegment<String, String> segment = createTestSegment();
    try {
      segment.put("key", "value");
      String value = segment.compute("key", new BiFunction<String, String, String>() {
        @Override
        public String apply(String s, String s2) {
          return s2;
        }
      }, false);
      assertThat(value, is("value"));
      assertThat(segment.isPinned("key"), is(false));
    } finally {
      segment.destroy();
    }
  }

  @Test
  public void testComputeFunctionReturnsSamePins() {
    EhcacheSegmentFactory.EhcacheSegment<String, String> segment = createTestSegment();
    try {
      segment.put("key", "value");
      String value = segment.compute("key", new BiFunction<String, String, String>() {
        @Override
        public String apply(String s, String s2) {
          return s2;
        }
      }, true);
      assertThat(value, is("value"));
      assertThat(segment.isPinned("key"), is(true));
    } finally {
      segment.destroy();
    }
  }

  @Test
  public void testComputeFunctionReturnsSamePreservesPinWhenNoPin() {
    EhcacheSegmentFactory.EhcacheSegment<String, String> segment = createTestSegment();
    try {
      segment.putPinned("key", "value");
      String value = segment.compute("key", new BiFunction<String, String, String>() {
        @Override
        public String apply(String s, String s2) {
          return s2;
        }
      }, false);
      assertThat(value, is("value"));
      assertThat(segment.isPinned("key"), is(true));
    } finally {
      segment.destroy();
    }
  }

  @Test
  public void testComputeFunctionReturnsDifferentNoPin() {
    EhcacheSegmentFactory.EhcacheSegment<String, String> segment = createTestSegment();
    try {
      segment.put("key", "value");
      String value = segment.compute("key", new BiFunction<String, String, String>() {
        @Override
        public String apply(String s, String s2) {
          return "otherValue";
        }
      }, false);
      assertThat(value, is("otherValue"));
      assertThat(segment.isPinned("key"), is(false));
    } finally {
      segment.destroy();
    }
  }

  @Test
  public void testComputeFunctionReturnsDifferentPins() {
    EhcacheSegmentFactory.EhcacheSegment<String, String> segment = createTestSegment();
    try {
      segment.put("key", "value");
      String value = segment.compute("key", new BiFunction<String, String, String>() {
        @Override
        public String apply(String s, String s2) {
          return "otherValue";
        }
      }, true);
      assertThat(value, is("otherValue"));
      assertThat(segment.isPinned("key"), is(true));
    } finally {
      segment.destroy();
    }
  }

  @Test
  public void testComputeFunctionReturnsDifferentClearsPin() {
    EhcacheSegmentFactory.EhcacheSegment<String, String> segment = createTestSegment();
    try {
      segment.putPinned("key", "value");
      String value = segment.compute("key", new BiFunction<String, String, String>() {
        @Override
        public String apply(String s, String s2) {
          return "otherValue";
        }
      }, false);
      assertThat(value, is("otherValue"));
      assertThat(segment.isPinned("key"), is(false));
    } finally {
      segment.destroy();
    }
  }

  @Test
  public void testComputeFunctionReturnsNullRemoves() {
    EhcacheSegmentFactory.EhcacheSegment<String, String> segment = createTestSegment();
    try {
      segment.putPinned("key", "value");
      String value = segment.compute("key", new BiFunction<String, String, String>() {
        @Override
        public String apply(String s, String s2) {
          return null;
        }
      }, false);
      assertThat(value, nullValue());
      assertThat(segment.containsKey("key"), is(false));
    } finally {
      segment.destroy();
    }
  }

  @Test
  public void testComputeIfPresentNotCalledOnNotContainedKey() {
    EhcacheSegmentFactory.EhcacheSegment<String, String> segment = createTestSegment();
    try {
      try {
        segment.computeIfPresent("key", new BiFunction<String, String, String>() {
          @Override
          public String apply(String s, String s2) {
            throw new UnsupportedOperationException("Should not have been called!");
          }
        });
      } catch (UnsupportedOperationException e) {
        fail("Mapping function should not have been called.");
      }
    } finally {
      segment.destroy();
    }
  }

  @Test
  public void testComputeIfPresentReturnsSameValue() {
    EhcacheSegmentFactory.EhcacheSegment<String, String> segment = createTestSegment();
    try {
      segment.put("key", "value");
      String value = segment.computeIfPresent("key", new BiFunction<String, String, String>() {
        @Override
        public String apply(String s, String s2) {
          return s2;
        }
      });
      assertThat(segment.get("key"), is(value));
    } finally {
      segment.destroy();
    }
  }

  @Test
  public void testComputeIfPresentReturnsDifferentValue() {
    EhcacheSegmentFactory.EhcacheSegment<String, String> segment = createTestSegment();
    try {
      segment.put("key", "value");
      String value = segment.computeIfPresent("key", new BiFunction<String, String, String>() {
        @Override
        public String apply(String s, String s2) {
          return "newValue";
        }
      });
      assertThat(segment.get("key"), is(value));
    } finally {
      segment.destroy();
    }
  }

  @Test
  public void testComputeIfPresentReturnsNullRemovesMapping() {
    EhcacheSegmentFactory.EhcacheSegment<String, String> segment = createTestSegment();
    try {
      segment.put("key", "value");
      String value = segment.computeIfPresent("key", new BiFunction<String, String, String>() {
        @Override
        public String apply(String s, String s2) {
          return null;
        }
      });
      assertThat(segment.containsKey("key"), is(false));
    } finally {
      segment.destroy();
    }
  }

  @Test
  public void testPutVetoedComputesMetadata() {
    EhcacheSegmentFactory.EhcacheSegment<String, String> segment = createTestSegment(new Predicate<Map.Entry<String, String>>() {
      @Override
      public boolean test(Map.Entry<String, String> argument) {
        return "vetoed".equals(argument.getKey());
      }
    });
    try {
      segment.put("vetoed", "value");
      assertThat(segment.getMetadata("vetoed", EhcacheSegmentFactory.EhcacheSegment.VETOED), is(EhcacheSegmentFactory.EhcacheSegment.VETOED));
    } finally {
      segment.destroy();
    }
  }

  @Test
  public void testPutPinnedVetoedComputesMetadata() {
    EhcacheSegmentFactory.EhcacheSegment<String, String> segment = createTestSegment(new Predicate<Map.Entry<String, String>>() {
      @Override
      public boolean test(Map.Entry<String, String> argument) {
        return "vetoed".equals(argument.getKey());
      }
    });
    try {
      segment.putPinned("vetoed", "value");
      assertThat(segment.getMetadata("vetoed", EhcacheSegmentFactory.EhcacheSegment.VETOED), is(EhcacheSegmentFactory.EhcacheSegment.VETOED));
    } finally {
      segment.destroy();
    }
  }

  @Test
  public void testVetoedPreventsEviction() {
    EhcacheSegmentFactory.EhcacheSegment<String, String> segment = createTestSegment();
    try {
      assertThat(segment.evictable(1), is(true));
      assertThat(segment.evictable(EhcacheSegmentFactory.EhcacheSegment.VETOED | 1), is(false));
    } finally {
      segment.destroy();
    }
  }

  @Test
  public void testEvictionFiresEvent() {
    EhcacheSegmentFactory.EhcacheSegment.EvictionListener<String, String> evictionListener = mock(EhcacheSegmentFactory.EhcacheSegment.EvictionListener.class);
    EhcacheSegmentFactory.EhcacheSegment<String, String> segment = createTestSegment(evictionListener);
    try {
      segment.put("key", "value");
      segment.evict(segment.getEvictionIndex(), false);
      verify(evictionListener).onEviction("key", "value");
    } finally {
      segment.destroy();
    }
  }
}
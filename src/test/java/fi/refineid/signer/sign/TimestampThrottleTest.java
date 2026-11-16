// Copyright 2026 Petri Koistinen
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package fi.refineid.signer.sign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.europa.esig.dss.enumerations.DigestAlgorithm;
import eu.europa.esig.dss.model.DSSException;
import eu.europa.esig.dss.model.TimestampBinary;
import eu.europa.esig.dss.spi.x509.tsp.TSPSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TimestampThrottleTest {

  private static final byte[] DIGEST = new byte[] {1, 2, 3, 4};
  private static final TimestampBinary TOKEN = new TimestampBinary(new byte[] {0x30, 0x00});

  private static final class FakeClock {
    long nowNanos;
    final List<Long> sleepsNanos = new ArrayList<>();

    long read() {
      return nowNanos;
    }

    void advance(Duration delta) {
      nowNanos += delta.toNanos();
    }

    void sleepNanos(long nanos) {
      sleepsNanos.add(nanos);
      nowNanos += nanos;
    }
  }

  @Test
  @DisplayName("first document does not wait; second document waits the remaining time to 15 seconds")
  void spacesSeparateDocumentsToFifteenSeconds() {
    FakeClock clock = new FakeClock();
    clock.nowNanos = -50_000_000_000L;
    TimestampThrottle throttle =
        new TimestampThrottle(clock::read, clock::sleepNanos, TimestampThrottle.MIN_DOCUMENT_SPACING);

    TSPSource firstDocument = throttle.forDocument((alg, digest) -> TOKEN);
    assertSame(TOKEN, firstDocument.getTimeStampResponse(DigestAlgorithm.SHA256, DIGEST));
    assertTrue(clock.sleepsNanos.isEmpty(), "first document must not be delayed");

    clock.advance(Duration.ofSeconds(4));

    TSPSource secondDocument = throttle.forDocument((alg, digest) -> TOKEN);
    assertSame(TOKEN, secondDocument.getTimeStampResponse(DigestAlgorithm.SHA256, DIGEST));
    assertEquals(
        List.of(Duration.ofSeconds(11).toNanos()),
        clock.sleepsNanos,
        "second document must sleep the remaining 11s to reach 15s spacing");

    clock.advance(Duration.ofSeconds(16));
    TSPSource thirdDocument = throttle.forDocument((alg, digest) -> TOKEN);
    assertSame(TOKEN, thirdDocument.getTimeStampResponse(DigestAlgorithm.SHA256, DIGEST));
    assertEquals(1, clock.sleepsNanos.size(), "third document after 16s must not sleep");
  }

  @Test
  @DisplayName("multiple timestamps on the same document are not delayed even across interleaving")
  void sameDocumentMultipleTimestampsDoNotWaitEvenWhenInterleaved() {
    FakeClock clock = new FakeClock();
    TimestampThrottle throttle =
        new TimestampThrottle(clock::read, clock::sleepNanos, TimestampThrottle.MIN_DOCUMENT_SPACING);

    TSPSource docA =
        throttle.forDocument(
            (alg, digest) -> {
              clock.advance(Duration.ofMillis(250));
              return TOKEN;
            });
    TSPSource docB = throttle.forDocument((alg, digest) -> TOKEN);

    // Doc A signature timestamp at t=0 (finishes at 0.25s).
    docA.getTimeStampResponse(DigestAlgorithm.SHA256, DIGEST);
    assertTrue(clock.sleepsNanos.isEmpty());

    // Doc B timestamp interleaves: sleeps 15s (to 15.25s).
    docB.getTimeStampResponse(DigestAlgorithm.SHA256, DIGEST);
    assertEquals(List.of(Duration.ofSeconds(15).toNanos()), clock.sleepsNanos);

    // Doc A archive timestamp runs 1s later: must NOT sleep against Doc B!
    clock.advance(Duration.ofSeconds(1));
    docA.getTimeStampResponse(DigestAlgorithm.SHA256, DIGEST);
    assertEquals(
        1,
        clock.sleepsNanos.size(),
        "follow-up timestamp on Doc A must not be delayed by interleaved Doc B");

    // Doc C runs 2s after Doc A's archive timestamp finished: sleeps remaining 13s.
    clock.advance(Duration.ofSeconds(2));
    throttle.forDocument((alg, digest) -> TOKEN).getTimeStampResponse(DigestAlgorithm.SHA256, DIGEST);
    assertEquals(2, clock.sleepsNanos.size());
    assertEquals(Duration.ofSeconds(13).toNanos(), clock.sleepsNanos.get(1));
  }

  @Test
  @DisplayName("spurious early wakeups re-check monotonic clock until full 15s has elapsed")
  void spuriousEarlyWakeupLoopsUntilFullSpacingElapsed() {
    FakeClock clock = new FakeClock();
    List<Long> requestedSleeps = new ArrayList<>();
    TimestampThrottle throttle =
        new TimestampThrottle(
            clock::read,
            nanos -> {
              requestedSleeps.add(nanos);
              long actual = nanos > Duration.ofSeconds(1).toNanos() ? nanos / 2 : nanos;
              clock.nowNanos += actual;
            },
            TimestampThrottle.MIN_DOCUMENT_SPACING);

    throttle.forDocument((alg, digest) -> TOKEN).getTimeStampResponse(DigestAlgorithm.SHA256, DIGEST);
    long firstDoneAt = clock.nowNanos;

    throttle.forDocument((alg, digest) -> TOKEN).getTimeStampResponse(DigestAlgorithm.SHA256, DIGEST);
    assertTrue(requestedSleeps.size() >= 2);
    assertEquals(TimestampThrottle.MIN_DOCUMENT_SPACING.toNanos(), clock.nowNanos - firstDoneAt);
  }

  @Test
  @DisplayName("failed network attempt spaces both same-document retry and subsequent documents")
  void failedNetworkAttemptSpacesRetryAndNextDocument() {
    FakeClock clock = new FakeClock();
    AtomicInteger attempts = new AtomicInteger();
    TimestampThrottle throttle =
        new TimestampThrottle(clock::read, clock::sleepNanos, TimestampThrottle.MIN_DOCUMENT_SPACING);

    TSPSource flakyFirst =
        throttle.forDocument(
            (alg, digest) -> {
              clock.advance(Duration.ofSeconds(1));
              if (attempts.incrementAndGet() == 1) {
                throw new DSSException("HTTP 429 Too Many Requests");
              }
              return TOKEN;
            });
    assertThrows(
        DSSException.class,
        () -> flakyFirst.getTimeStampResponse(DigestAlgorithm.SHA256, DIGEST));

    // Retrying the failed first document must wait 15s rather than hammering immediately.
    assertSame(TOKEN, flakyFirst.getTimeStampResponse(DigestAlgorithm.SHA256, DIGEST));
    assertEquals(List.of(Duration.ofSeconds(15).toNanos()), clock.sleepsNanos);

    // Subsequent document also waits 15s after the retry completed.
    throttle.forDocument((alg, digest) -> TOKEN).getTimeStampResponse(DigestAlgorithm.SHA256, DIGEST);
    assertEquals(
        List.of(Duration.ofSeconds(15).toNanos(), Duration.ofSeconds(15).toNanos()),
        clock.sleepsNanos);
  }

  @Test
  @DisplayName("concurrent document threads reserve distinct 15s slots without holding lock during HTTP")
  void concurrentThreadsReserveDistinctSlotsWithoutBlockingHttp() throws Exception {
    AtomicLong nowNanos = new AtomicLong(1_000_000_000L);
    List<Long> scheduledSleeps = Collections.synchronizedList(new ArrayList<>());
    CountDownLatch firstInHttp = new CountDownLatch(1);
    CountDownLatch allowFirstHttpReturn = new CountDownLatch(1);

    TimestampThrottle throttle =
        new TimestampThrottle(
            nowNanos::get,
            nanos -> {
              scheduledSleeps.add(nanos);
              nowNanos.addAndGet(nanos);
            },
            TimestampThrottle.MIN_DOCUMENT_SPACING);

    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      Future<TimestampBinary> first =
          pool.submit(
              () ->
                  throttle
                      .forDocument(
                          (alg, digest) -> {
                            firstInHttp.countDown();
                            try {
                              assertTrue(allowFirstHttpReturn.await(5, TimeUnit.SECONDS));
                            } catch (InterruptedException e) {
                              Thread.currentThread().interrupt();
                              throw new DSSException(e);
                            }
                            return TOKEN;
                          })
                      .getTimeStampResponse(DigestAlgorithm.SHA256, DIGEST));

      assertTrue(firstInHttp.await(5, TimeUnit.SECONDS));

      // While thread 1 is inside HTTP I/O, thread 2 reserves its +15s slot and completes without deadlock.
      Future<TimestampBinary> second =
          pool.submit(
              () ->
                  throttle
                      .forDocument((alg, digest) -> TOKEN)
                      .getTimeStampResponse(DigestAlgorithm.SHA256, DIGEST));

      assertSame(TOKEN, second.get(5, TimeUnit.SECONDS));
      allowFirstHttpReturn.countDown();
      assertSame(TOKEN, first.get(5, TimeUnit.SECONDS));
    }

    assertEquals(List.of(TimestampThrottle.MIN_DOCUMENT_SPACING.toNanos()), scheduledSleeps);
  }

  @Test
  @DisplayName("interruption while waiting restores interrupt status and releases unused slot")
  void interruptionDuringWaitSkipsAuthorityAndReleasesSlot() {
    FakeClock clock = new FakeClock();
    AtomicInteger secondCalls = new AtomicInteger();
    AtomicInteger sleepCalls = new AtomicInteger();
    TimestampThrottle throttle =
        new TimestampThrottle(
            clock::read,
            nanos -> {
              if (sleepCalls.incrementAndGet() == 1) {
                clock.advance(Duration.ofSeconds(3));
                throw new InterruptedException("stop");
              }
              clock.sleepNanos(nanos);
            },
            TimestampThrottle.MIN_DOCUMENT_SPACING);

    throttle.forDocument((alg, digest) -> TOKEN).getTimeStampResponse(DigestAlgorithm.SHA256, DIGEST);

    TSPSource interruptedSecond =
        throttle.forDocument(
            (alg, digest) -> {
              secondCalls.incrementAndGet();
              return TOKEN;
            });

    try {
      assertThrows(
          DSSException.class,
          () -> interruptedSecond.getTimeStampResponse(DigestAlgorithm.SHA256, DIGEST));
      assertTrue(Thread.currentThread().isInterrupted(), "interrupt flag must be restored");
    } finally {
      Thread.interrupted();
    }
    assertEquals(0, secondCalls.get(), "authority must not be called after sleep interruption");

    // Third document waits only the remaining 12s from the first document (not 27s).
    throttle.forDocument((alg, digest) -> TOKEN).getTimeStampResponse(DigestAlgorithm.SHA256, DIGEST);
    assertEquals(List.of(Duration.ofSeconds(12).toNanos()), clock.sleepsNanos);
  }
}

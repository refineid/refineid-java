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

import eu.europa.esig.dss.enumerations.DigestAlgorithm;
import eu.europa.esig.dss.model.DSSException;
import eu.europa.esig.dss.model.TimestampBinary;
import eu.europa.esig.dss.spi.x509.tsp.TSPSource;
import java.io.Serial;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Spaces timestamp requests across separate documents so batch or scripted
 * signing respects the shared timestamp authority's fair-use policy.
 *
 * <p>Each signed document or container acquires its own {@link TSPSource}
 * view through {@link #forDocument(TSPSource)}. A document reserves a
 * monotonic send slot before its first timestamp request and sleeps outside
 * the reservation monitor, so neither sleeping nor remote HTTP roundtrips
 * hold a JVM-wide lock. Follow-up timestamps on the same document (such as
 * an archive timestamp after a signature timestamp) proceed without
 * intra-document delay while extending the spacing horizon for subsequent
 * documents.
 */
public final class TimestampThrottle {

  /** Minimum fair-use spacing between timestamp requests for separate documents. */
  public static final Duration MIN_DOCUMENT_SPACING = Duration.ofSeconds(15);

  private static final TimestampThrottle SHARED =
      new TimestampThrottle(System::nanoTime, TimeUnit.NANOSECONDS::sleep, MIN_DOCUMENT_SPACING);

  /** Returns the process-wide timestamp throttle shared across signing flows. */
  public static TimestampThrottle shared() {
    return SHARED;
  }

  /** Pause callback for injectable testing without real wall-clock delays. */
  @FunctionalInterface
  interface Sleeper {
    void sleepNanos(long nanos) throws InterruptedException;
  }

  private final LongSupplier nanoClock;
  private final Sleeper sleeper;
  private final long spacingNanos;

  private boolean hasSchedule;
  private long nextSlotNanos;

  TimestampThrottle(LongSupplier nanoClock, Sleeper sleeper, Duration spacing) {
    this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    if (spacing.isNegative()) {
      throw new IllegalArgumentException("spacing must not be negative");
    }
    this.spacingNanos = spacing.toNanos();
  }

  /**
   * Wraps {@code delegate} for signing one document or container, enforcing
   * {@link #MIN_DOCUMENT_SPACING} after any earlier document's timestamp
   * request while allowing follow-up timestamps on the same document without
   * intra-document delay.
   */
  public TSPSource forDocument(TSPSource delegate) {
    Objects.requireNonNull(delegate, "delegate");
    return new DocumentTspSource(this, delegate, false);
  }

  private synchronized long reserveSlot() {
    long now = nanoClock.getAsLong();
    long scheduled = !hasSchedule || (nextSlotNanos - now <= 0) ? now : nextSlotNanos;
    nextSlotNanos = scheduled + spacingNanos;
    hasSchedule = true;
    return scheduled;
  }

  private synchronized void cancelUnusedTailSlot(long scheduled) {
    if (hasSchedule && nextSlotNanos == scheduled + spacingNanos) {
      nextSlotNanos = scheduled;
    }
  }

  private synchronized void recordCompletion() {
    long afterCompletion = nanoClock.getAsLong() + spacingNanos;
    if (!hasSchedule || (afterCompletion - nextSlotNanos > 0)) {
      nextSlotNanos = afterCompletion;
      hasSchedule = true;
    }
  }

  private void awaitUntil(long targetNanos) throws DSSException {
    while (true) {
      long remaining = targetNanos - nanoClock.getAsLong();
      if (remaining <= 0) {
        return;
      }
      long bounded = Math.min(remaining, spacingNanos > 0 ? remaining : 0);
      if (bounded <= 0) {
        return;
      }
      try {
        sleeper.sleepNanos(bounded);
      } catch (InterruptedException interrupted) {
        cancelUnusedTailSlot(targetNanos);
        Thread.currentThread().interrupt();
        throw new DSSException("Interrupted while waiting between timestamp requests", interrupted);
      }
    }
  }

  private static final class DocumentTspSource implements TSPSource {
    @Serial private static final long serialVersionUID = 1L;

    private final transient TimestampThrottle throttle;
    private final TSPSource delegate;
    private boolean firstTimestampSucceeded;

    private DocumentTspSource(
        TimestampThrottle throttle, TSPSource delegate, boolean firstTimestampSucceeded) {
      this.throttle = throttle;
      this.delegate = delegate;
      this.firstTimestampSucceeded = firstTimestampSucceeded;
    }

    @Override
    public synchronized TimestampBinary getTimeStampResponse(
        DigestAlgorithm digestAlgorithm, byte[] digest) throws DSSException {
      if (!firstTimestampSucceeded) {
        long scheduled = throttle.reserveSlot();
        throttle.awaitUntil(scheduled);
      }
      try {
        TimestampBinary response = delegate.getTimeStampResponse(digestAlgorithm, digest);
        firstTimestampSucceeded = true;
        return response;
      } finally {
        throttle.recordCompletion();
      }
    }

    @Serial
    private Object readResolve() {
      return new DocumentTspSource(SHARED, delegate, firstTimestampSucceeded);
    }
  }
}

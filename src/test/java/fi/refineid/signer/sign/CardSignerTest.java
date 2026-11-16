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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.europa.esig.dss.enumerations.DigestAlgorithm;
import eu.europa.esig.dss.enumerations.SignatureAlgorithm;
import eu.europa.esig.dss.model.SignatureValue;
import eu.europa.esig.dss.model.ToBeSigned;
import eu.europa.esig.dss.token.DSSPrivateKeyEntry;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CardSignerTest {

  private static final byte[] SAMPLE_DATA = new byte[] {1, 2, 3};
  private static final byte[] SAMPLE_SIGNATURE = new byte[] {4, 5, 6};

  @Test
  @DisplayName("CardSigner keeps an unshared copy of PIN 2 and zeroes it on close")
  void defensiveCopyZeroedOnClose() throws Exception {
    char[] callerPin = "123456".toCharArray();
    CardSigner signer =
        CardSigner.forTest(
            new CardSigner.SignToken() {
              @Override
              public SignatureValue sign(
                  ToBeSigned dataToSign, DigestAlgorithm digest, DSSPrivateKeyEntry key) {
                return new SignatureValue(SignatureAlgorithm.ECDSA_SHA256, SAMPLE_SIGNATURE);
              }

              @Override
              public void close() {}
            },
            null,
            callerPin);

    Arrays.fill(callerPin, '\0');
    assertFalse(signer.isHeldPinZeroedForTest(), "clearing caller buffer must not zero heldPin");

    SignatureValue value = signer.signWithToken(new ToBeSigned(SAMPLE_DATA), DigestAlgorithm.SHA256);
    assertEquals(SignatureAlgorithm.ECDSA_SHA256, value.getAlgorithm());

    signer.close();
    assertTrue(signer.isHeldPinZeroedForTest(), "close() must overwrite heldPin with zeros");
  }

  @Test
  @DisplayName("PIN refusal zeroes heldPin immediately, strips provider cause, and latches against retries")
  void pinRefusalZeroesAndLatches() {
    AtomicInteger tokenCalls = new AtomicInteger();
    CardSigner signer =
        CardSigner.forTest(
            new CardSigner.SignToken() {
              @Override
              public SignatureValue sign(
                  ToBeSigned dataToSign, DigestAlgorithm digest, DSSPrivateKeyEntry key) {
                tokenCalls.incrementAndGet();
                throw new RuntimeException("CKR_PIN_INCORRECT");
              }

              @Override
              public void close() {}
            },
            null,
            "123456".toCharArray());

    SigningFailedException first =
        assertThrows(
            SigningFailedException.class,
            () -> signer.signWithToken(new ToBeSigned(SAMPLE_DATA), DigestAlgorithm.SHA256));
    assertTrue(first.isPinFailure());
    assertEquals("The card refused that PIN", first.getMessage());
    assertNull(first.getCause());
    assertTrue(signer.isHeldPinZeroedForTest());
    assertEquals(1, tokenCalls.get());

    SigningFailedException second =
        assertThrows(
            SigningFailedException.class,
            () -> signer.signWithToken(new ToBeSigned(SAMPLE_DATA), DigestAlgorithm.SHA256));
    assertTrue(second.isPinFailure());
    assertEquals(1, tokenCalls.get(), "latched signer must not call token.sign again");
  }

  @Test
  @DisplayName("non-PIN card failure translates sentence cleanly without latching PIN refusal")
  void nonPinCardFailureUsesCardSentenceWithoutLatching() {
    AtomicInteger tokenCalls = new AtomicInteger();
    CardSigner signer =
        CardSigner.forTest(
            new CardSigner.SignToken() {
              @Override
              public SignatureValue sign(
                  ToBeSigned dataToSign, DigestAlgorithm digest, DSSPrivateKeyEntry key) {
                if (tokenCalls.incrementAndGet() == 1) {
                  throw new RuntimeException("CKR_TOKEN_NOT_PRESENT");
                }
                return new SignatureValue(SignatureAlgorithm.ECDSA_SHA256, SAMPLE_SIGNATURE);
              }

              @Override
              public void close() {}
            },
            null,
            "123456".toCharArray());

    SigningFailedException missing =
        assertThrows(
            SigningFailedException.class,
            () -> signer.signWithToken(new ToBeSigned(SAMPLE_DATA), DigestAlgorithm.SHA256));
    assertFalse(missing.isPinFailure());
    assertEquals("No card in the reader", missing.getMessage());
    assertNull(missing.getCause());
    assertFalse(signer.isHeldPinZeroedForTest());

    signer.close();
    assertTrue(signer.isHeldPinZeroedForTest());
  }
}

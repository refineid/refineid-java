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

package fi.refineid.signer.card;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.security.auth.login.AccountLockedException;
import javax.security.auth.login.FailedLoginException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CardFailureTest {

  @Test
  @DisplayName("PIN refusal codes, driver phrasings, and FailedLoginException classify as PIN refusals")
  void pinIncorrectIsPinFailure() {
    for (String message :
        new String[] {
          "CKR_PIN_INCORRECT",
          "CKR_PIN_FINAL_TRY",
          "Incorrect PIN",
          "Invalid PIN",
          "Wrong PIN"
        }) {
      CardFailure pkcs11 = CardFailure.from(new RuntimeException("DSS wrap", new Exception(message)));
      assertEquals(CardFailure.Kind.PIN_REFUSED, pkcs11.kind(), message);
      assertEquals("The card refused that PIN", pkcs11.sentence(), message);
      assertTrue(pkcs11.isPinFailure(), message);
    }

    CardFailure jaas =
        CardFailure.from(new RuntimeException("login failed", new FailedLoginException()));
    assertEquals(CardFailure.Kind.PIN_REFUSED, jaas.kind());
    assertTrue(jaas.isPinFailure());
  }

  @Test
  @DisplayName("CKR_PIN_LOCKED and AccountLockedException classify as PIN blocked")
  void pinLockedIsPinFailure() {
    CardFailure pkcs11 =
        CardFailure.from(new RuntimeException("DSS wrap", new Exception("CKR_PIN_LOCKED")));
    assertEquals(CardFailure.Kind.PIN_BLOCKED, pkcs11.kind());
    assertEquals("The card has blocked that PIN", pkcs11.sentence());
    assertTrue(pkcs11.isPinFailure());

    CardFailure jaas =
        CardFailure.from(new RuntimeException("login failed", new AccountLockedException()));
    assertEquals(CardFailure.Kind.PIN_BLOCKED, jaas.kind());
    assertTrue(jaas.isPinFailure());
  }

  @Test
  @DisplayName("non-credential errors including 'unlocked' or mutex lock messages are not PIN failures")
  void nonCredentialErrorsAreNotPinFailures() {
    CardFailure missing = CardFailure.from(new RuntimeException("CKR_TOKEN_NOT_PRESENT"));
    assertEquals(CardFailure.Kind.NO_CARD, missing.kind());
    assertFalse(missing.isPinFailure());

    for (String message :
        new String[] {"session unlocked", "CKR_MUTEX_NOT_LOCKED", "keychain locked"}) {
      CardFailure other = CardFailure.from(new RuntimeException(message));
      assertEquals(CardFailure.Kind.OTHER, other.kind(), message);
      assertFalse(other.isPinFailure(), message);
    }
  }

  @Test
  @DisplayName("cyclic exception cause chains terminate cleanly")
  void cyclicCauseChainTerminates() {
    Exception first = new Exception("outer");
    Exception second = new Exception("CKR_PIN_INCORRECT", first);
    first.initCause(second);

    CardFailure failure = CardFailure.from(first);
    assertEquals(CardFailure.Kind.PIN_REFUSED, failure.kind());
  }
}

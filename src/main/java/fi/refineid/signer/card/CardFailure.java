package fi.refineid.signer.card;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.Set;
import javax.security.auth.login.AccountLockedException;
import javax.security.auth.login.CredentialExpiredException;
import javax.security.auth.login.FailedLoginException;

/**
 * What went wrong with the card, said twice: once for the person
 * holding it, once for whoever is debugging it.
 *
 * <p>The two are not the same sentence. A holder needs to know whether
 * to put a card in, update software, or call the issuer. A support
 * report needs the provider's own words, module paths and all. Showing
 * the second to the first is how an application ends up putting a
 * library path in front of someone who wants to sign a PDF.
 *
 * <p>This is also the only place that reads a provider's failure text.
 * Above it, the application knows about cards and credentials and
 * nothing about PKCS#11.
 */
public record CardFailure(Kind kind, String sentence, String detail) {

  private static final long CKR_SLOT_ID_INVALID = 0x00000003L;
  private static final long CKR_ATTRIBUTE_TYPE_INVALID = 0x00000012L;
  private static final long CKR_PIN_INCORRECT = 0x000000A0L;
  private static final long CKR_PIN_INVALID = 0x000000A1L;
  private static final long CKR_PIN_LEN_RANGE = 0x000000A2L;
  private static final long CKR_PIN_EXPIRED = 0x000000A3L;
  private static final long CKR_PIN_LOCKED = 0x000000A4L;
  private static final long CKR_TOKEN_NOT_PRESENT = 0x000000E0L;

  /** Which class of card or credential failure occurred. */
  public enum Kind {
    NO_CARD,
    OUTDATED_MODULE,
    PIN_REFUSED,
    PIN_BLOCKED,
    BROKEN_INSTALL,
    UNSUPPORTED_KEY,
    OTHER
  }

  public CardFailure(String sentence, String detail) {
    this(Kind.OTHER, sentence, detail);
  }

  /**
   * Whether the card refused or blocked the PIN, so no further
   * signature in the same job may present a held PIN again.
   */
  public boolean isPinFailure() {
    return kind == Kind.PIN_REFUSED || kind == Kind.PIN_BLOCKED;
  }

  /** Translates a provider failure into something a person can act on. */
  public static CardFailure from(Throwable failure) {
    String detail = chain(failure);
    OptionalLong code = pkcs11ErrorCode(failure);
    if (code.isPresent()) {
      long ckr = code.getAsLong();
      if (ckr == CKR_TOKEN_NOT_PRESENT || ckr == CKR_SLOT_ID_INVALID) {
        return new CardFailure(Kind.NO_CARD, "No card in the reader", detail);
      }
      if (ckr == CKR_ATTRIBUTE_TYPE_INVALID) {
        return new CardFailure(
            Kind.OUTDATED_MODULE, "The card software on this computer is too old", detail);
      }
      if (ckr == CKR_PIN_LOCKED) {
        return new CardFailure(Kind.PIN_BLOCKED, "The card has blocked that PIN", detail);
      }
      if (ckr == CKR_PIN_INCORRECT
          || ckr == CKR_PIN_INVALID
          || ckr == CKR_PIN_LEN_RANGE
          || ckr == CKR_PIN_EXPIRED) {
        return new CardFailure(Kind.PIN_REFUSED, "The card refused that PIN", detail);
      }
    }

    String lower = detail.toLowerCase(Locale.ROOT);
    if (lower.contains("has 0 slots")
        || lower.contains("token not present")
        || lower.contains("token_not_present")
        || lower.contains("no such slot")
        || lower.contains("slot_id_invalid")) {
      return new CardFailure(Kind.NO_CARD, "No card in the reader", detail);
    }
    if (lower.contains("ckr_attribute_type_invalid")) {
      return new CardFailure(
          Kind.OUTDATED_MODULE, "The card software on this computer is too old", detail);
    }
    if (hasCause(failure, AccountLockedException.class)
        || lower.contains("pin_locked")
        || lower.contains("pin locked")
        || lower.contains("pin is locked")) {
      return new CardFailure(Kind.PIN_BLOCKED, "The card has blocked that PIN", detail);
    }
    if (hasCause(failure, FailedLoginException.class)
        || hasCause(failure, CredentialExpiredException.class)
        || lower.contains("pin_incorrect")
        || lower.contains("pin incorrect")
        || lower.contains("pin is incorrect")
        || lower.contains("incorrect pin")
        || lower.contains("pin_invalid")
        || lower.contains("pin invalid")
        || lower.contains("invalid pin")
        || lower.contains("wrong pin")
        || lower.contains("pin_final_try")
        || lower.contains("pin_len_range")
        || lower.contains("pin_expired")) {
      return new CardFailure(Kind.PIN_REFUSED, "The card refused that PIN", detail);
    }
    if (lower.contains("dlopen") || lower.contains("library")) {
      return new CardFailure(
          Kind.BROKEN_INSTALL, "The card software is not installed correctly", detail);
    }
    if (lower.contains("must be at least") || lower.contains("must be at most")) {
      return new CardFailure(
          Kind.UNSUPPORTED_KEY,
          "The card software will not use this card's signing key",
          detail);
    }
    return new CardFailure(Kind.OTHER, "The card could not be opened", detail);
  }

  private static OptionalLong pkcs11ErrorCode(Throwable failure) {
    Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable step = failure; step != null && visited.add(step); step = step.getCause()) {
      if ("sun.security.pkcs11.wrapper.PKCS11Exception".equals(step.getClass().getName())) {
        try {
          Method getter = step.getClass().getMethod("getErrorCode");
          if (getter.invoke(step) instanceof Number code) {
            return OptionalLong.of(code.longValue());
          }
        } catch (ReflectiveOperationException ignored) {
          // Fall back to cause-chain message inspection when module encapsulation hides it.
        }
      }
    }
    return OptionalLong.empty();
  }

  private static boolean hasCause(Throwable failure, Class<? extends Throwable> expected) {
    Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable step = failure; step != null && visited.add(step); step = step.getCause()) {
      if (expected.isInstance(step)) {
        return true;
      }
    }
    return false;
  }

  /** Every message in the cause chain, which is where the reason hides. */
  private static String chain(Throwable failure) {
    StringBuilder text = new StringBuilder();
    Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable step = failure; step != null && visited.add(step); step = step.getCause()) {
      if (step.getMessage() != null) {
        text.append(text.isEmpty() ? "" : "; ").append(step.getMessage());
      }
    }
    return text.toString();
  }
}

package fi.refineid.signer.card;

import java.util.Locale;

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
public record CardFailure(String sentence, String detail) {

  /** Translates a provider failure into something a person can act on. */
  public static CardFailure from(Throwable failure) {
    String detail = chain(failure);
    String lower = detail.toLowerCase(Locale.ROOT);
    if (lower.contains("has 0 slots") || lower.contains("token not present")
        || lower.contains("no such slot") || lower.contains("slot_id_invalid")) {
      return new CardFailure("No card in the reader", detail);
    }
    if (lower.contains("ckr_attribute_type_invalid")) {
      return new CardFailure("The card software on this computer is too old", detail);
    }
    if (lower.contains("pin_incorrect") || lower.contains("pin incorrect")) {
      return new CardFailure("The card refused that PIN", detail);
    }
    if (lower.contains("pin_locked") || lower.contains("locked")) {
      return new CardFailure("The card has blocked that PIN", detail);
    }
    if (lower.contains("dlopen") || lower.contains("library")) {
      return new CardFailure("The card software is not installed correctly", detail);
    }
    if (lower.contains("must be at least") || lower.contains("must be at most")) {
      return new CardFailure(
          "The card software will not use this card's signing key", detail);
    }
    return new CardFailure("The card could not be opened", detail);
  }

  /** Every message in the cause chain, which is where the reason hides. */
  private static String chain(Throwable failure) {
    StringBuilder text = new StringBuilder();
    for (Throwable step = failure; step != null; step = step.getCause()) {
      if (step.getMessage() != null) {
        text.append(text.isEmpty() ? "" : "; ").append(step.getMessage());
      }
    }
    return text.toString();
  }
}

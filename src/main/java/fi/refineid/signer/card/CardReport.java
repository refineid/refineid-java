package fi.refineid.signer.card;

import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.List;

/**
 * What this machine can sign with, as lines a person can read.
 *
 * <p>Run before anything else on a new machine: it answers whether the
 * card stack is installed, whether Java can reach it, and which keys
 * the card offers, without signing anything or spending a PIN attempt.
 */
public final class CardReport {

  private CardReport() {
  }

  public static void main(String[] arguments) {
    for (String line : lines()) {
      System.out.println(line);
    }
  }

  /** The report, so a window can show what the console prints. */
  public static List<String> lines() {
    try (CardKeystore card = CardKeystore.open()) {
      return describe(card);
    } catch (CardUnavailableException unavailable) {
      return List.of("card: unavailable", "  " + unavailable.getMessage());
    } catch (Exception failure) {
      return List.of("card: failed", "  " + failure);
    }
  }

  private static List<String> describe(CardKeystore card) throws Exception {
    List<String> lines = new java.util.ArrayList<>();
    lines.add("provider: " + card.providerName());
    List<String> signing = card.signingAliases();
    lines.add("keys that can sign: " + signing.size());
    KeyStore store = card.keyStore();
    for (String alias : signing) {
      lines.add("  " + alias);
      if (store.getCertificate(alias) instanceof X509Certificate certificate) {
        lines.add("    subject: " + certificate.getSubjectX500Principal().getName());
        lines.add("    key: " + certificate.getPublicKey().getAlgorithm()
            + " " + keySize(certificate) + " bits");
        lines.add("    signs with: " + certificate.getSigAlgName());
      }
    }
    for (String alias : card.aliases()) {
      if (!signing.contains(alias)) {
        lines.add("  " + alias + " (certificate only, no key)");
      }
    }
    return lines;
  }

  private static String keySize(X509Certificate certificate) {
    return switch (certificate.getPublicKey()) {
      case java.security.interfaces.RSAPublicKey rsa -> String.valueOf(rsa.getModulus().bitLength());
      case java.security.interfaces.ECPublicKey ec ->
          String.valueOf(ec.getParams().getCurve().getField().getFieldSize());
      default -> "?";
    };
  }
}

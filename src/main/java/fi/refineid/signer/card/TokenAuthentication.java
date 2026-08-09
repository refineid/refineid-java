package fi.refineid.signer.card;

import java.nio.file.Path;
import java.security.KeyStore;
import java.security.Provider;
import java.security.Security;
import java.util.Locale;

/**
 * Who collects the PIN: the token, or this application.
 *
 * <p>PKCS#11 answers this itself. A token that declares a protected
 * authentication path collects its own credential -- a system dialog,
 * a pin pad on the reader -- and a caller must not send one. A token
 * that declares none expects the caller to supply it, and one login
 * then serves the session.
 *
 * <p>Asked rather than configured. Which module is in use, and how it
 * was built, is not this application's business; what it needs to know
 * is whether it should be putting a PIN field in front of anyone, and
 * the token already says so.
 */
public enum TokenAuthentication {

  /**
   * The token asks, per operation, through its own interface.
   *
   * <p>The PIN never enters this process. The number of times a holder
   * is asked is the token's decision, not this application's.
   */
  TOKEN_COLLECTS,

  /**
   * The caller supplies it, and one login serves the session.
   *
   * <p>This is what lets a batch cost one entry: the value is held for
   * the job and presented for each signature.
   */
  CALLER_SUPPLIES;

  /**
   * Asks the token, without spending anything.
   *
   * <p>Opening with no password either succeeds, which only a token
   * collecting its own credential can do, or is refused before the
   * card is contacted at all -- the provider will not invent a login
   * it has no password for. No attempt is spent either way.
   */
  public static TokenAuthentication of(Path module, int slot) {
    Provider provider = null;
    try {
      provider = Security.getProvider("SunPKCS11").configure("--"
          + String.join(System.lineSeparator(),
              "name = ReFineIDProbe",
              "library = \"" + module + "\"",
              "slotListIndex = " + slot));
      KeyStore store = KeyStore.getInstance("PKCS11", provider);
      store.load(null, null);
      return TOKEN_COLLECTS;
    } catch (Exception refused) {
      String detail = CardFailure.from(refused).detail().toLowerCase(Locale.ROOT);
      return detail.contains("password") ? CALLER_SUPPLIES : TOKEN_COLLECTS;
    } finally {
      if (provider != null) {
        Security.removeProvider(provider.getName());
      }
    }
  }
}

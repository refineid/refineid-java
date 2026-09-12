package fi.refineid.signer.card;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.Provider;
import java.security.Security;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The card, as a {@link KeyStore}.
 *
 * <p>This is the whole of this application's card access. There is no
 * APDU here and there will not be one: the platform card stack owns
 * the protocol, and the JCA is the seam (ADR-0002). On macOS that
 * stack is reached through the PKCS#11 module built in RefineID-Apple
 * (ADR-0004).
 *
 * <p>No PIN is passed from Java. The module advertises
 * {@code CKF_PROTECTED_AUTHENTICATION_PATH}, which tells the provider
 * that the token collects its own credential, so the system dialog
 * asks and this process never holds a PIN, never logs one, and cannot
 * leak one. A {@code null} password below is therefore the correct
 * argument rather than an omission.
 */
public final class CardKeystore implements AutoCloseable {

  /**
   * The modules this application will load, in the order it tries
   * them.
   *
   * <p>The signing module is first and is the one this application
   * wants: it publishes the key the card gates behind PIN 2, which is
   * the key a document signature is made with. The general module
   * publishes the authentication key as well, which is for logging in
   * to things and must not be used to sign a document.
   */
  /** Names a module somewhere else, for a build under test. */
  public static final String MODULE_PROPERTY = "refineid.module";

  /** Which slot of that module to open; see CardSigner for why. */
  public static final String SLOT_PROPERTY = "refineid.slot";

  private static final List<Path> MODULE_SEARCH_PATH = List.of(
      Path.of("/usr/local/lib/librefineid_pkcs11_sign.dylib"),
      Path.of("/usr/local/lib/librefineid_pkcs11.dylib"));

  private final Provider provider;
  private final KeyStore keyStore;

  private CardKeystore(Provider provider, KeyStore keyStore) {
    this.provider = provider;
    this.keyStore = keyStore;
  }

  /**
   * Opens the card through the first module found on this machine.
   *
   * @throws CardUnavailableException when no module is installed, or
   *     the one that is cannot be configured or opened
   */
  public static CardKeystore open() throws CardUnavailableException {
    // A module named on the command line wins: this is how a build
    // under test, or another vendor's module entirely, is pointed at
    // without editing a search path. The application is not tied to
    // one implementation of PKCS#11 and this is where that shows.
    String named = System.getProperty(MODULE_PROPERTY);
    if (named != null && !named.isBlank()) {
      return open(Path.of(named));
    }
    Path module = MODULE_SEARCH_PATH.stream()
        .filter(Files::isReadable)
        .findFirst()
        .orElseThrow(() -> new CardUnavailableException(
            "no RefineID PKCS#11 module is installed; looked in "
                + MODULE_SEARCH_PATH));
    return open(module);
  }

  /**
   * Opens the card through one named module.
   *
   * <p>Kept separate so a test, or a machine with the module somewhere
   * else, can say which one to use without editing a search path.
   */
  public static CardKeystore open(Path module) throws CardUnavailableException {
    try {
      Provider configured = Security.getProvider("SunPKCS11")
          .configure("--" + pkcs11Configuration(module));
      KeyStore store = KeyStore.getInstance("PKCS11", configured);
      // Null password: the token collects its own. See the class note.
      store.load(null, null);
      return new CardKeystore(configured, store);
    } catch (Exception failure) {
      throw new CardUnavailableException(reason(module, failure), failure);
    }
  }

  /**
   * What went wrong, said as the thing to do about it.
   *
   * <p>An empty reader and an unloadable module both surface as a
   * provider that would not initialize, and they send the holder to
   * different places: one to the reader, one to the installation.
   */
  private static String reason(Path module, Exception failure) {
    String detail = chain(failure).toLowerCase(java.util.Locale.ROOT);
    // The module publishes a slot per identity the card carries, so an
    // empty reader is not an empty slot but no slots at all, and the
    // provider refuses to initialize rather than opening onto nothing.
    if (detail.contains("token not present") || detail.contains("ckr_token_not_present")
        || detail.contains("has 0 slots") || detail.contains("no such slot")) {
      return "no card in the reader";
    }
    if (detail.contains("library") || detail.contains("dlopen")) {
      return "the card module at " + module + " could not be loaded";
    }
    return "the card could not be opened through " + module + ": " + chain(failure);
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

  /**
   * The provider configuration, as SunPKCS11 wants it.
   *
   * <p>{@code slotListIndex = 0} rather than a fixed slot id: the
   * module numbers its slots as it sees fit and a reader that comes
   * and goes changes them, while the first slot is the card that is
   * present.
   */
  private static String pkcs11Configuration(Path module) {
    return String.join(System.lineSeparator(),
        "name = RefineID",
        "library = " + module,
        "slotListIndex = " + System.getProperty(SLOT_PROPERTY, "0"));
  }

  /** Every alias the card publishes. */
  public List<String> aliases() throws CardUnavailableException {
    try {
      return Collections.list(keyStore.aliases());
    } catch (KeyStoreException failure) {
      throw new CardUnavailableException("the card published no usable aliases", failure);
    }
  }

  /**
   * The aliases that can actually sign, which is not every alias: a
   * card carries certificates it holds no key for.
   */
  public List<String> signingAliases() throws CardUnavailableException {
    List<String> signing = new ArrayList<>();
    for (String alias : aliases()) {
      try {
        if (keyStore.isKeyEntry(alias)) {
          signing.add(alias);
        }
      } catch (KeyStoreException failure) {
        throw new CardUnavailableException("alias " + alias + " could not be read", failure);
      }
    }
    return signing;
  }

  /** The opened store, for the parts of DSS that want one. */
  public KeyStore keyStore() {
    return keyStore;
  }

  /** The provider name, which a diagnostic report is entitled to say. */
  public String providerName() {
    return provider.getName();
  }

  @Override
  public void close() throws IOException {
    // Removing the provider drops the module's session with the card.
    // Left registered, a second open would find a stale one and the
    // card would appear to be missing while it sat in the reader.
    Security.removeProvider(provider.getName());
  }
}

package fi.refineid.signer.sign;

import java.net.URI;
import java.util.prefs.Preferences;

/**
 * Which timestamp authority to use, and what it wants to be told.
 *
 * <p>A signature without a timestamp carries only the time the signing
 * computer claimed. The default is the qualified authority every
 * ReFineID client shares, and it needs no credentials; an organization
 * running its own, or paying for one, is the reason this is
 * configurable at all, and those are the ones that ask for a user name
 * and a password.
 *
 * <p>Kept in the platform preference store, which is where a setting
 * belongs and is not a keychain: the password sits there in the clear,
 * so it is stored only when one was entered.
 *
 * @param address where to ask for a timestamp
 * @param username the user name the authority wants, or empty for none
 * @param password the password that goes with it, or empty for none
 */
public record TimestampSettings(String address, String username, String password) {

  /** The qualified authority every ReFineID client shares. */
  public static final String DEFAULT_ADDRESS = "http://timestamp.sectigo.com/qualified";

  private static final String ADDRESS_KEY = "timestamp.address";
  private static final String USERNAME_KEY = "timestamp.username";
  private static final String PASSWORD_KEY = "timestamp.password";

  public TimestampSettings {
    address = address == null || address.isBlank() ? DEFAULT_ADDRESS : address.trim();
    username = username == null ? "" : username.trim();
    password = password == null ? "" : password;
  }

  /** What this machine is set to, or the default when it is set to nothing. */
  public static TimestampSettings stored() {
    Preferences preferences = store();
    return new TimestampSettings(
        preferences.get(ADDRESS_KEY, DEFAULT_ADDRESS),
        preferences.get(USERNAME_KEY, ""),
        preferences.get(PASSWORD_KEY, ""));
  }

  /** Remembers this setting for the next run. */
  public void save() {
    Preferences preferences = store();
    preferences.put(ADDRESS_KEY, address);
    preferences.put(USERNAME_KEY, username);
    if (password.isBlank()) {
      preferences.remove(PASSWORD_KEY);
    } else {
      preferences.put(PASSWORD_KEY, password);
    }
  }

  /** Whether the authority is to be told who is asking. */
  public boolean hasCredentials() {
    return !username.isBlank();
  }

  /**
   * Whether the address could be dialled at all.
   *
   * <p>Checked before it is saved: an address that cannot be parsed
   * fails at the moment a document is signed, which is the worst
   * moment to find out.
   */
  public boolean isAddressUsable() {
    try {
      URI uri = URI.create(address);
      return uri.getHost() != null
          && (uri.getScheme().equals("http") || uri.getScheme().equals("https"));
    } catch (RuntimeException malformed) {
      return false;
    }
  }

  /** The host the credentials belong to. */
  public String host() {
    return URI.create(address).getHost();
  }

  /** The port, defaulted by scheme when the address names none. */
  public int port() {
    URI uri = URI.create(address);
    if (uri.getPort() != -1) {
      return uri.getPort();
    }
    return "https".equals(uri.getScheme()) ? 443 : 80;
  }

  private static Preferences store() {
    return Preferences.userNodeForPackage(TimestampSettings.class);
  }
}

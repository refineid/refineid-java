package fi.refineid.signer.ui;

import javafx.application.Application;

/**
 * The entry point a packaged build starts at.
 *
 * <p>Starting at a class that extends {@code Application} requires the
 * JavaFX runtime on the module path, and a packaged application carries
 * its libraries on the class path. Launching from a class that is not
 * itself an {@code Application} is how the toolkit is allowed to start
 * from there.
 */
public final class Launcher {

  private Launcher() {
  }

  public static void main(String[] arguments) {
    Application.launch(SignerApp.class, arguments);
  }
}

package fi.refineid.signer.ui;

import java.util.Optional;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.VBox;

/**
 * Asks for PIN 2 once, for one job.
 *
 * <p>The card verifies PIN 2 before every signature and never carries
 * one verification into the next. What it does not require is a person
 * answering every time: the value entered here is held for this job
 * and presented to the card for each signature, so twelve documents
 * cost one entry rather than twelve.
 *
 * <p>It is held in memory for the length of the job and cleared after.
 * Nothing writes it down.
 */
final class PinPrompt {

  private static final int SPACING = 10;

  /**
   * Asks for the PIN, or returns empty when the holder cancels.
   *
   * @param signatures how many signatures this one entry will make,
   *     which is what the holder is agreeing to
   */
  Optional<char[]> ask(int signatures) {
    PasswordField field = new PasswordField();
    Dialog<ButtonType> dialog = new Dialog<>();
    dialog.setTitle("PIN 2");
    dialog.setHeaderText(signatures == 1
        ? "Enter PIN 2 to sign"
        : "Enter PIN 2 to sign " + signatures + " documents");
    dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
    Label note = new Label(signatures == 1
        ? "The card is asked for one signature."
        : "The card is asked for " + signatures
            + " signatures, one for each document, with this one entry.");
    note.setWrapText(true);
    dialog.getDialogPane().setContent(new VBox(SPACING, field, note));
    dialog.setOnShown(event -> field.requestFocus());
    if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
      return Optional.empty();
    }
    char[] entered = field.getText().toCharArray();
    field.clear();
    return entered.length == 0 ? Optional.empty() : Optional.of(entered);
  }
}

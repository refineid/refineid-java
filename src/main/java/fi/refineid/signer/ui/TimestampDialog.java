package fi.refineid.signer.ui;

import fi.refineid.signer.sign.TimestampSettings;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;

/**
 * Where to ask for a timestamp, and what to tell it.
 *
 * <p>The shared qualified authority needs nothing filled in here. An
 * organization running or paying for its own is the reason this exists,
 * and those are the ones that want a user name and a password.
 */
final class TimestampDialog {

  private static final int SPACING = 8;
  private static final int FIELD_WIDTH = 320;

  private final TextField address = new TextField();
  private final TextField username = new TextField();
  private final PasswordField password = new PasswordField();

  /** Shows the dialog and saves what was entered, if it can be used. */
  void show() {
    TimestampSettings stored = TimestampSettings.stored();
    address.setText(stored.address());
    address.setPrefWidth(FIELD_WIDTH);
    address.setPromptText(TimestampSettings.DEFAULT_ADDRESS);
    username.setText(stored.username());
    username.setPromptText("none");
    password.setText(stored.password());
    password.setPromptText("none");

    Dialog<ButtonType> dialog = new Dialog<>();
    dialog.setTitle("Timestamp Service");
    dialog.setHeaderText("Where signatures are timestamped");
    dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

    GridPane fields = new GridPane();
    fields.setHgap(SPACING);
    fields.setVgap(SPACING);
    fields.addRow(0, new Label("Address"), address);
    fields.addRow(1, new Label("User name"), username);
    fields.addRow(2, new Label("Password"), password);
    fields.add(new Label("Leave the credentials empty unless the authority asks for them."),
        0, 3, 2, 1);
    dialog.getDialogPane().setContent(fields);

    if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
      return;
    }
    TimestampSettings entered =
        new TimestampSettings(address.getText(), username.getText(), password.getText());
    if (!entered.isAddressUsable()) {
      // Refused here rather than at the moment a document is signed,
      // which is the worst moment to discover an address is not one.
      Alert refused = new Alert(Alert.AlertType.ERROR);
      refused.setHeaderText("That address cannot be used");
      refused.setContentText(
          "A timestamp authority is reached over http or https, as in "
              + TimestampSettings.DEFAULT_ADDRESS);
      refused.showAndWait();
      return;
    }
    entered.save();
  }
}

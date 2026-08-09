package fi.refineid.signer.ui;

import fi.refineid.signer.AppVersion;
import fi.refineid.signer.job.DocumentOutcome;
import fi.refineid.signer.job.JobPlan;
import fi.refineid.signer.job.JobShape;
import fi.refineid.signer.job.PinPolicy;
import fi.refineid.signer.job.SigningJob;
import fi.refineid.signer.card.CredentialStatus;
import fi.refineid.signer.card.TokenAuthentication;
import fi.refineid.signer.sign.CardSigner;
import fi.refineid.signer.sign.SigningFailedException;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

/**
 * The signing window.
 *
 * <p>Documents arrive by being dropped, several at a time, because
 * that is what people sign: the day's batch, not one file. What the
 * window has to get right before anything else is the count -- how
 * many times the card will ask for PIN 2 -- and it says that before
 * the button is pressed rather than after (ADR-0007).
 */
public final class SignerApp extends Application {

  private final List<Path> documents = new ArrayList<>();
  private final ListView<String> chosen = new ListView<>();
  private final ListView<String> results = new ListView<>();
  private final Label plan = new Label();
  private final Label signer = new Label("No card read yet");
  private final Label credential = new Label();

  /** The drop area, disabled while there is nothing to sign with. */
  private StackPane drop;
  private final ToggleGroup shapes = new ToggleGroup();
  private final Button sign = new Button("Sign…");
  private final MenuBar menus = new MenuBar();
  private volatile boolean cancelled;

  /** True while a job is on the card, when nothing else may open it. */
  private volatile boolean signing;

  /** Who collects the PIN, asked of the token when the card is read. */
  private volatile TokenAuthentication authentication = TokenAuthentication.TOKEN_COLLECTS;

  /**
   * Whether the card's certificate is worth signing with.
   *
   * <p>Read once with the card and remembered, because it decides
   * whether Sign does anything at all.
   */
  private volatile boolean credentialUsable = true;

  public static void main(String[] arguments) {
    launch(arguments);
  }

  @Override
  public void start(Stage stage) {
    RadioButton container = new RadioButton("One signed container (ASiC-E)");
    container.setToggleGroup(shapes);
    container.setUserData(JobShape.ONE_CONTAINER);
    container.setSelected(true);
    RadioButton separately = new RadioButton("Each document signed separately");
    separately.setToggleGroup(shapes);
    separately.setUserData(JobShape.EACH_DOCUMENT);
    shapes.selectedToggleProperty().addListener((source, was, now) -> refreshPlan());

    drop = dropArea();
    plan.setWrapText(true);
    // Wrapped, not clipped: this line carries the reason a card cannot
    // be used, and half a reason sends someone to the wrong place.
    signer.setWrapText(true);
    credential.setWrapText(true);

    sign.setDefaultButton(true);
    sign.setDisable(true);
    sign.setOnAction(event -> signAll());

    Button choose = new Button("Choose…");
    choose.setOnAction(event -> chooseFiles(stage));

    VBox layout = new VBox(12,
        menus(),
        title(),
        signer,
        credential,
        drop,
        new Label("Documents"),
        chosen,
        new Label("How"),
        container,
        separately,
        plan,
        new HBox(8, choose, sign),
        new Label("Results"),
        results);
    layout.setPadding(new Insets(20));
    VBox.setVgrow(chosen, Priority.ALWAYS);
    VBox.setVgrow(results, Priority.ALWAYS);

    stage.setTitle("ReFineID Signer");
    stage.setScene(new Scene(layout, 620, 720));
    // Read again whenever the window comes forward. A card is put in
    // after the application is already open, and a window that read
    // once at startup says there is no card while it signs documents.
    stage.focusedProperty().addListener((source, was, now) -> {
      if (now && !signing) {
        readCard();
      }
    });
    stage.show();
    // After the window is showing, not before: asked earlier, the
    // menus were measured drawn inside the window, which is not where
    // a Mac keeps them.
    Platform.runLater(() -> menus.setUseSystemMenuBar(true));
    readCard();
  }

  private Label title() {
    Label label = new Label("ReFineID Signer");
    label.setFont(Font.font(label.getFont().getFamily(), 22));
    return label;
  }

  /**
   * The menu bar, carrying what this build is.
   *
   * <p>A version does not belong under the title: nobody signing a
   * document needs a build number, and everybody reporting a problem
   * does. It goes where a version is looked for.
   *
   * <p>Drawn in the system bar rather than in the window, which is
   * where a Mac keeps menus. The About item macOS puts in the
   * application menu belongs to the bundle and cannot be filled from
   * here, so this one is named in full and sits under Help.
   */
  private MenuBar menus() {
    MenuItem timestamps = new MenuItem("Timestamp Service…");
    timestamps.setOnAction(event -> new TimestampDialog().show());
    Menu signing = new Menu("Signing");
    signing.getItems().add(timestamps);

    MenuItem about = new MenuItem("About ReFineID Signer");
    about.setOnAction(event -> showAbout());
    Menu help = new Menu("Help");
    help.getItems().add(about);

    menus.getMenus().addAll(signing, help);
    return menus;
  }

  /** What this build is, and what it is signing through. */
  private void showAbout() {
    Alert about = new Alert(Alert.AlertType.INFORMATION);
    about.setTitle("About ReFineID Signer");
    about.setHeaderText("ReFineID Signer " + AppVersion.current());
    about.setContentText(
        "Document signing with Finnish identity cards.\n\n"
            + "Card module: " + CardSigner.defaultModule());
    about.showAndWait();
  }

  /** The drop area, which takes several files at once. */
  private StackPane dropArea() {
    Label invitation = new Label("Drop documents here");
    StackPane area = new StackPane(invitation);
    area.setMinHeight(90);
    area.setStyle(
        "-fx-border-color: -fx-accent; -fx-border-style: dashed; -fx-border-radius: 8;");
    area.setOnDragOver(event -> {
      if (event.getDragboard().hasFiles()) {
        event.acceptTransferModes(TransferMode.COPY);
      }
      event.consume();
    });
    area.setOnDragDropped(event -> {
      List<File> dropped = event.getDragboard().getFiles();
      if (dropped != null) {
        add(dropped);
      }
      event.setDropCompleted(true);
      event.consume();
    });
    return area;
  }

  private void chooseFiles(Stage stage) {
    FileChooser chooser = new FileChooser();
    chooser.setTitle("Documents to sign");
    List<File> picked = chooser.showOpenMultipleDialog(stage);
    if (picked != null) {
      add(picked);
    }
  }

  private void add(List<File> files) {
    files.stream().map(File::toPath).filter(path -> !documents.contains(path))
        .forEach(documents::add);
    chosen.getItems().setAll(documents.stream().map(path -> path.getFileName().toString())
        .toList());
    sign.setDisable(documents.isEmpty() || !credentialUsable);
    refreshPlan();
  }

  /** Says what the job will cost, in prompts, before it is started. */
  private void refreshPlan() {
    if (!credentialUsable) {
      // A signature made with a withdrawn certificate is correct in
      // every respect and validates nowhere. Saying what the job would
      // cost would be describing work this app will not do.
      plan.setText(
          "This certificate has been revoked. A signature made with it can never be "
              + "validated, so nothing will be signed.");
      return;
    }
    if (documents.isEmpty()) {
      plan.setText("");
      return;
    }
    plan.setText(new JobPlan(documents, selectedShape(), pinPolicy()).summary());
  }

  /**
   * How PIN 2 will be collected, as the token says.
   *
   * <p>A token that collects its own credential decides how often a
   * holder is asked, and this window must not promise otherwise. One
   * that expects the caller to supply it can be given one entry for a
   * whole job.
   */
  private PinPolicy pinPolicy() {
    return authentication == TokenAuthentication.CALLER_SUPPLIES
        ? PinPolicy.ASK_ONCE_FOR_THE_JOB
        : PinPolicy.ASK_EACH_SIGNATURE;
  }

  private JobShape selectedShape() {
    return (JobShape) shapes.getSelectedToggle().getUserData();
  }

  /** Reads the card once at startup, so the window can name the signer. */
  private void readCard() {
    Thread.ofVirtual().start(() -> {
      try (CardSigner card = CardSigner.open(CardSigner.defaultModule())) {
        String name = card.signerName();
        Platform.runLater(() -> signer.setText("Signing as " + name));
        authentication = TokenAuthentication.of(CardSigner.defaultModule(), CardSigner.slot());
        CredentialStatus status = card.credentialStatus();
        credentialUsable = status.permitsSigning();
        Platform.runLater(() -> {
          credential.setText(status.sentence());
          credential.setStyle(credentialUsable ? "" : "-fx-text-fill: -fx-accent;");
          refreshPlan();
          sign.setDisable(documents.isEmpty() || !credentialUsable);
        });
      } catch (SigningFailedException unavailable) {
        Platform.runLater(() -> signer.setText(unavailable.getMessage()));
      }
    });
  }

  /**
   * Runs the job off the interface thread.
   *
   * <p>Each signature waits on the card and on a person answering a
   * PIN sheet, and a window that stopped drawing while that happened
   * would look like a window that had crashed.
   */
  private void signAll() {
    JobPlan job = new JobPlan(documents, selectedShape(), pinPolicy());
    // Asked once, here, for the whole job: the card verifies PIN 2 per
    // signature but does not require a person to answer per signature.
    char[] pin = null;
    if (job.pin() == PinPolicy.ASK_ONCE_FOR_THE_JOB) {
      Optional<char[]> entered = new PinPrompt().ask(job.signatureCount());
      if (entered.isEmpty()) {
        return;
      }
      pin = entered.get();
    }
    char[] held = pin;
    results.getItems().clear();
    sign.setDisable(true);
    cancelled = false;
    Path destination = documents.getFirst().getParent();
    signing = true;
    Thread.ofVirtual().start(() -> {
      try (CardSigner card = CardSigner.open(CardSigner.defaultModule(), held)) {
        new SigningJob(card, destination).run(job, new SigningJob.Progress() {
          @Override
          public void starting(Path document, int number, int total) {
            report("asking the card for " + document.getFileName()
                + " (" + number + " of " + total + ")");
          }

          @Override
          public void finished(DocumentOutcome outcome) {
            report(switch (outcome) {
              case DocumentOutcome.Signed signed ->
                  signed.source().getFileName() + " signed -> " + signed.output().getFileName();
              case DocumentOutcome.Failed failed ->
                  failed.source().getFileName() + " not signed: " + failed.reason();
              case DocumentOutcome.Skipped skipped ->
                  skipped.source().getFileName() + " skipped";
            });
          }

          @Override
          public boolean cancelled() {
            return cancelled;
          }
        });
      } catch (SigningFailedException failure) {
        report(failure.getMessage());
      } finally {
        if (held != null) {
          // The job is over; the value goes with it.
          java.util.Arrays.fill(held, '\0');
        }
        signing = false;
        Platform.runLater(() -> {
          sign.setDisable(false);
          readCard();
        });
      }
    });
  }

  private void report(String line) {
    Platform.runLater(() -> {
      results.getItems().add(line);
      results.scrollTo(results.getItems().size() - 1);
    });
  }
}

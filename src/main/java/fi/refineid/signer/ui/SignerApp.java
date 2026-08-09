package fi.refineid.signer.ui;

import fi.refineid.signer.AppVersion;
import fi.refineid.signer.job.DocumentOutcome;
import fi.refineid.signer.job.JobPlan;
import fi.refineid.signer.job.JobShape;
import fi.refineid.signer.job.PinPolicy;
import fi.refineid.signer.job.SigningJob;
import fi.refineid.signer.card.CredentialStatus;
import fi.refineid.signer.sign.CardSigner;
import fi.refineid.signer.sign.SigningFailedException;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
  private final ToggleGroup shapes = new ToggleGroup();
  private final Button sign = new Button("Sign…");
  private final MenuBar menus = new MenuBar();
  private volatile boolean cancelled;

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

    StackPane drop = dropArea();
    plan.setWrapText(true);

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
   * How PIN 2 will be collected on this run.
   *
   * <p>Asking once for a whole job needs the module started with
   * textual PIN entry; otherwise the system dialog collects it and
   * appears for every signature, and the window must not promise
   * otherwise.
   */
  private PinPolicy pinPolicy() {
    return "textual".equalsIgnoreCase(System.getenv("REFINEID_PKCS11_PIN_ENTRY"))
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
    results.getItems().clear();
    sign.setDisable(true);
    cancelled = false;
    Path destination = documents.getFirst().getParent();
    Thread.ofVirtual().start(() -> {
      try (CardSigner card = CardSigner.open(CardSigner.defaultModule())) {
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
        Platform.runLater(() -> sign.setDisable(false));
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

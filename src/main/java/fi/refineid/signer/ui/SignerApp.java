package fi.refineid.signer.ui;

import fi.refineid.signer.job.DocumentOutcome;
import fi.refineid.signer.job.JobPlan;
import fi.refineid.signer.job.JobShape;
import fi.refineid.signer.job.PinPolicy;
import fi.refineid.signer.job.SigningJob;
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
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
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
  private final ToggleGroup shapes = new ToggleGroup();
  private final Button sign = new Button("Sign…");
  private volatile boolean cancelled;

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
        title(),
        signer,
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
    readCard();
  }

  private Label title() {
    Label label = new Label("ReFineID Signer");
    label.setFont(Font.font(label.getFont().getFamily(), 22));
    return label;
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
    sign.setDisable(documents.isEmpty());
    refreshPlan();
  }

  /** Says what the job will cost, in prompts, before it is started. */
  private void refreshPlan() {
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

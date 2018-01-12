package filecompare;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.apache.commons.io.FileUtils;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class Controller
{
    private static final String INVALID_DIR = "Invalid directory";
    private static final String CHOOSE_DIR = "No directory selected";
    private static final String DATE_PRIORITY = "Take newest";
    private static final String SIZE_PRIORITY = "Take largest";
    private static final String DELIMITER = ";";

    @FXML
    GridPane basePane;

    @FXML
    private Button checkButton;

    @FXML
    private TextField srcFileText;

    @FXML
    private TextField destFileText;

    @FXML
    private ComboBox<String> filePriorityBox;

    private File src;

    private File dest;

    private boolean datePriority = true;

    private long size = 0;

    private ExecutorService executorService = Executors.newSingleThreadExecutor();

    @FXML
    public void initialize()
    {
        filePriorityBox.setItems(FXCollections.observableArrayList(DATE_PRIORITY, SIZE_PRIORITY));
        filePriorityBox.getSelectionModel().select(0);
        checkButton.setDisable(true);
        srcFileText.setText(CHOOSE_DIR);
        destFileText.setText(CHOOSE_DIR);
        executorService.execute(() ->
        {
            try
            {
                byte[] encoded = Files.readAllBytes(Paths.get(System.getProperty("user.dir") + "/history"));
                String value = new String(encoded);
                if (value.contains(DELIMITER))
                {
                    String[] paths = value.split(DELIMITER);
                    if (paths.length == 2)
                    {
                        src = new File(paths[0]);
                        dest = new File(paths[1]);
                    }
                    else if (paths.length == 1)
                    {
                        if (value.startsWith(DELIMITER))
                        {
                            dest = new File(paths[0]);
                        }
                        else
                        {
                            src = new File(paths[0]);
                        }
                    }
                }
            }
            catch (IOException e)
            {
                //Not much to do here
            }
            Platform.runLater(()->updateUI(false));
        });
    }

    @FXML
    public void updatePriority()
    {
        datePriority = filePriorityBox.getSelectionModel().getSelectedItem().equals(DATE_PRIORITY);
    }

    @FXML
    public void showSourceChooser()
    {
        DirectoryChooser fileChooser = new DirectoryChooser ();
        fileChooser.setTitle("Select source directory");
        File file = fileChooser.showDialog(getStage());
        if (file == null || !file.exists() || !file.isDirectory())
        {
            src = null;
        }
        else
        {
            src = file;
        }
        updateUI(true);
    }

    @FXML
    public void showDestChooser()
    {
        DirectoryChooser fileChooser = new DirectoryChooser();
        fileChooser.setTitle("Select destination directory");
        File file = fileChooser.showDialog(getStage());
        if (file == null || !file.exists() || !file.isDirectory())
        {
            dest = null;
        }
        else
        {
            dest = file;
        }
        updateUI(true);
    }

    private Alert progress;

    private void showProgressDialog(String headerText)
    {
        progress = new Alert(Alert.AlertType.NONE);
        progress.initModality(Modality.APPLICATION_MODAL);
        progress.setTitle("Working...");
        progress.setHeaderText(headerText);
        //JavaFX hack https://stackoverflow.com/questions/28698106/why-am-i-unable-to-programmatically-close-a-dialog-on-javafx
        progress.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL);
        progress.show();
    }

    @FXML
    public void checkFiles()
    {
        showProgressDialog("Checking files...");
        executorService.execute(()->
        {
            final List<Path> filesToCopy = new ArrayList<>();
            try
            {
                checkDir(src, dest, filesToCopy);
            }
            catch (IOException e)
            {
                StringWriter stringWriter = new StringWriter();
                PrintWriter printWriter = new PrintWriter(stringWriter);
                e.printStackTrace(printWriter);
                String exceptionText = stringWriter.toString();
                Platform.runLater(()->
                {
                    progress.close();
                    exceptionDialog(Alert.AlertType.ERROR,"Error", "File checking error",
                            "The following error occurred while checking the files", exceptionText);
                });
            }
            Platform.runLater(()->
            {
                progress.close();

                if (filesToCopy.size() == 0)
                {
                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                    alert.setTitle("Up To Date");
                    alert.setHeaderText("There are no files to copy");
                    alert.show();
                    return;
                }

                double mbytes = (double)size / 10e6;

                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.setTitle("Confirm Copy");
                alert.setHeaderText(filesToCopy.size() + " files will be copied (" + mbytes + " MB)");
                alert.setContentText("Review files or confirm copy");

                ButtonType buttonTypeReview = new ButtonType("Review Files");
                ButtonType buttonTypeConfirm = new ButtonType("Copy!");
                ButtonType buttonTypeCancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);

                alert.getButtonTypes().setAll(buttonTypeReview, buttonTypeConfirm, buttonTypeCancel);

                Optional<ButtonType> result = alert.showAndWait();
                if (result.get() == buttonTypeReview)
                {
                    showFileList(filesToCopy);
                }
                else if (result.get() == buttonTypeConfirm)
                {
                    alert.close();
                    showProgressDialog("Copying files...");
                    executorService.execute(() -> doCopy(filesToCopy));
                }
            });
        });
    }

    private void showFileList(List<Path> filesToCopy)
    {
        StringWriter stringWriter = new StringWriter();
        for (Path path : filesToCopy)
        {
            stringWriter.append(path.toString()).append("\n");
        }
        exceptionDialog(Alert.AlertType.INFORMATION, "File Review", "Files to be copied",
                "The following files will be copied to the destination directory:", stringWriter.toString());
    }

    private void showFileErrors(List<Path> files)
    {
        StringWriter stringWriter = new StringWriter();
        for (Path path : files)
        {
            stringWriter.append(path.toString()).append("\n");
        }
        exceptionDialog(Alert.AlertType.INFORMATION, "File Errors", "Errors copying files",
                "The following files could not be copied:", stringWriter.toString());
    }

    private void exceptionDialog(Alert.AlertType type, String title, String headerText, String contentText, String exceptionText)
    {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(headerText);
        alert.setContentText(contentText);

        Label label = new Label("More info:");

        TextArea textArea = new TextArea(exceptionText);
        textArea.setEditable(false);
        textArea.setWrapText(true);

        textArea.setMaxWidth(Double.MAX_VALUE);
        textArea.setMaxHeight(Double.MAX_VALUE);
        GridPane.setVgrow(textArea, Priority.ALWAYS);
        GridPane.setHgrow(textArea, Priority.ALWAYS);

        GridPane expContent = new GridPane();
        expContent.setMaxWidth(Double.MAX_VALUE);
        expContent.add(label, 0, 0);
        expContent.add(textArea, 0, 1);

        alert.getDialogPane().setExpandableContent(expContent);
        alert.getDialogPane().setExpanded(true);

        alert.showAndWait();
    }

    private void doCopy(List<Path> filesToCopy)
    {
        final List<Path> errorPaths = new ArrayList<>();
        for (Path path : filesToCopy)
        {
            String destString = path.toString().replace(src.toString(), dest.toString());
            File dest = new File(destString);
            try
            {
                FileUtils.copyFile(path.toFile(), dest);
            }
            catch (IOException e)
            {
                errorPaths.add(path);
            }
        }
        Platform.runLater(()->result(errorPaths));
    }

    private void result(List<Path> errorPaths)
    {
        progress.close();
        if (errorPaths.size() > 0)
        {
            showFileErrors(errorPaths);
        }
        else
        {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Success");
            alert.setHeaderText("Files copied successfully");
            alert.show();
        }
    }

    private void checkDir(File src, File dest, List<Path> filesToCopy) throws IOException
    {
        size = 0;
        List<Path> srcFiles = Files.walk(src.toPath()).filter(s->s.toFile().isFile()).collect(Collectors.toList());
        for (final Path srcPath : srcFiles)
        {
            String destString = srcPath.toString().replace(src.toString(), dest.toString());
            File destFile = new File(destString);
            if (!destFile.exists() || shouldCopy(srcPath, destFile.toPath()))
            {
                filesToCopy.add(srcPath);
                size += Files.size(srcPath);
            }
        }
    }

    //Condition: both source and dest files must exist
    private boolean shouldCopy(Path src, Path dest) throws IOException
    {
        File sourceFile = src.toFile();
        File destFile = dest.toFile();
        if (datePriority)
        {
            return sourceFile.lastModified() > destFile.lastModified();
        }
        else
        {
            return sourceFile.length() > destFile.length();
        }
    }

    private Stage getStage()
    {
        return (Stage) basePane.getScene().getWindow();
    }

    private void updateUI(boolean save)
    {
        srcFileText.setText(src == null ? INVALID_DIR : src.getAbsolutePath());
        destFileText.setText(dest == null ? INVALID_DIR : dest.getAbsolutePath());
        checkButton.setDisable(src == null || dest == null);
        if (save && (src != null || dest != null))
        {
            executorService.execute(() ->
            {
                try
                {
                    File file = new File(System.getProperty("user.dir") + "/history");
                    FileOutputStream outputStream = null;
                    outputStream = new FileOutputStream(file);
                    PrintWriter writer = new PrintWriter(outputStream);
                    if (src != null)
                    {
                        writer.write(src.getPath());
                    }
                    writer.write(DELIMITER);
                    if (dest != null)
                    {
                        writer.write(dest.getPath());
                    }
                    writer.flush();
                    outputStream.flush();
                    outputStream.close();
                }
                catch (IOException e)
                {
                    //Nothing we can do here
                }
            });
        }
    }
}

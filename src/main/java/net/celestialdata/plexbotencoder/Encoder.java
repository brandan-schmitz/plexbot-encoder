package net.celestialdata.plexbotencoder;

import com.github.kokorin.jaffree.JaffreeException;
import com.github.kokorin.jaffree.LogLevel;
import com.github.kokorin.jaffree.ffmpeg.*;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import net.celestialdata.plexbotencoder.clients.models.Episode;
import net.celestialdata.plexbotencoder.clients.models.Movie;
import net.celestialdata.plexbotencoder.clients.models.WorkItem;
import net.celestialdata.plexbotencoder.clients.services.*;
import net.celestialdata.plexbotencoder.utilities.FileType;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.SystemUtils;
import org.apache.http.client.methods.RequestBuilder;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import javax.net.ssl.HttpsURLConnection;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.channels.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.DecimalFormat;
import java.util.Base64;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class Encoder {
    private boolean isNotEncoding = true;
    private WorkItem currentWorkItem = new WorkItem();
    private final DecimalFormat decimalFormatter = new DecimalFormat("#0.00");

    private static final Logger logger = Logger.getLogger(Encoder.class);

    @ConfigProperty(name = "AppSettings.apiAddress/mp-rest/url")
    String baseUrl;

    @ConfigProperty(name = "AppSettings.workerName")
    String workerName;

    @ConfigProperty(name = "AppSettings.username")
    String username;

    @ConfigProperty(name = "AppSettings.password")
    String password;

    @ConfigProperty(name = "AppSettings.crf")
    String crf;

    @ConfigProperty(name = "AppSettings.accelerationHardware")
    String accelerationHardware;

    @ConfigProperty(name = "FolderSettings.tempFolder")
    String tempFolder;

    @Inject
    @RestClient
    EpisodeService episodeService;

    @Inject
    @RestClient
    MovieService movieService;

    @Inject
    @RestClient
    QueueService queueService;

    @Inject
    @RestClient
    WorkService workService;

    @Scheduled(every = "3s", delay = 10, delayUnit = TimeUnit.SECONDS)
    public void updateProgress() {
        if (!isNotEncoding) {
            workService.update(currentWorkItem.id, currentWorkItem.progress);
        }
    }

    @Scheduled(every = "1m", delay = 10, delayUnit = TimeUnit.SECONDS)
    public void fetchWork() {
        if (isNotEncoding) {
            var finalPath = "";
            var tempFilePath = "";
            var outputFilePath = "";
            var itemFileExtension = "";

            try {
                // Fetch the next job to work on
                currentWorkItem = getNextJob();

                // Ensure that there is actually a job to work on
                if (currentWorkItem == null) {
                    isNotEncoding = true;
                    return;
                }

                // Mark that the encoding process has started
                isNotEncoding = false;
                currentWorkItem.progress = "loading media";

                // Fetch the media file based on the type contained in the queue item
                var mediaItem = currentWorkItem.mediaType.equals("movie") ?
                        movieService.get(currentWorkItem.mediaId) : episodeService.get(currentWorkItem.mediaId);

                // Use the right API to get the download connection of the file based on its type
                Response downloadResponse = null;
                if (mediaItem instanceof Movie) {
                    // Get start the connection to the server to download the file
                    downloadResponse = movieService.downloadFile(((Movie) mediaItem).id);
                    itemFileExtension = ((Movie) mediaItem).filetype;
                } else if (mediaItem != null) {
                    downloadResponse = episodeService.downloadFile(((Episode) mediaItem).id);
                    itemFileExtension = ((Episode) mediaItem).filetype;
                }

                // Ensure it was able to get a download connection otherwise cancel
                if (downloadResponse == null) {
                    workService.delete(currentWorkItem.id);
                    isNotEncoding = true;
                    return;
                }

                // Build the destination path for this file
                tempFilePath = tempFolder + currentWorkItem.mediaId + "-old." + itemFileExtension;

                // Create the input and output streams
                ReadableByteChannel downloadByteChannel = Channels.newChannel((InputStream) downloadResponse.getEntity());
                FileChannel downloadOutputStream = new FileOutputStream(tempFilePath, false).getChannel();

                // Download the file
                long downloadProgress = 0;
                long downloadFileSize = Long.parseLong(downloadResponse.getHeaderString("Content-Length"));
                while (downloadOutputStream.transferFrom(downloadByteChannel, downloadOutputStream.size(), 1024) > 0) {
                    downloadProgress += 1024;
                    currentWorkItem.progress = "downloading file: " + decimalFormatter.format((((double) downloadProgress / downloadFileSize) * 100)) + "%";
                }

                // Close the data streams
                downloadByteChannel.close();
                downloadOutputStream.close();

                // Show that the download has been completed
                currentWorkItem.progress = "gathering information";

                /*
                // Generate the output filepath
                outputFilePath = tempFolder + currentWorkItem.mediaId + ".mkv";

                // Determine which encoder to use
                var encoder = "libx265";
                if (SystemUtils.IS_OS_WINDOWS && accelerationHardware.equalsIgnoreCase("nvidia")) {
                    encoder = "hevc_nvenc";
                } else if (SystemUtils.IS_OS_WINDOWS && accelerationHardware.equalsIgnoreCase("amd")) {
                    encoder = "hevc_amf";
                }

                // Ensure that we catch errors with the encoding process itself
                try {
                    // Get the media duration
                    final AtomicLong duration = new AtomicLong();
                    FFmpeg.atPath()
                            .addInput(UrlInput.fromUrl(tempFilePath))
                            .setOverwriteOutput(true)
                            .addOutput(new NullOutput())
                            .setLogLevel(LogLevel.ERROR)
                            .setProgressListener(progress -> duration.set(progress.getTimeMillis()))
                            .execute();

                    // Build the encoding process
                    FFmpeg.atPath()
                            .addInput(UrlInput.fromUrl(tempFilePath))
                            .addArguments("-c:v", encoder)
                            .addArguments("-crf", crf)
                            .addArguments("-preset", "medium")
                            .addArguments("-c:a", "copy")
                            .addArguments("-c:s", "copy")
                            .setOverwriteOutput(true)
                            .setLogLevel(LogLevel.ERROR)
                            .addOutput(UrlOutput.toUrl(outputFilePath))
                            .setProgressListener(fFmpegProgress -> currentWorkItem.progress =
                                    decimalFormatter.format(100. * fFmpegProgress.getTimeMillis() / duration.get()) + "%")
                            .execute();
                } catch (JaffreeException e) {
                    workService.delete(currentWorkItem.id);

                    // Attempt to delete work files
                    try {
                        Files.deleteIfExists(Paths.get(outputFilePath));
                        Files.deleteIfExists(Paths.get(tempFilePath));
                        Files.deleteIfExists(Paths.get(finalPath));
                    } catch (Exception e2) {
                        logger.error(e2);
                    }

                    isNotEncoding = true;
                    return;
                }
                 */

                // Determine which API endpoint to use
                var apiEndpoint = baseUrl;
                var mediaItemId = 0;
                if (mediaItem instanceof Movie) {
                    apiEndpoint = apiEndpoint + "/api/v1/movies/upload";
                    mediaItemId = ((Movie) mediaItem).id;
                } else {
                    apiEndpoint = apiEndpoint + "/api/v1/episodes/upload";
                    mediaItemId = ((Episode) mediaItem).id;
                }

                logger.info("Determined API endpoint: " + apiEndpoint);

                // Determine the size of the file that will be uploaded
                var uploadFileSize = new File(tempFilePath).length();

                logger.info("Determined file size: " + uploadFileSize);

                // Open an HTTP Connection to the API endpoint for uploading the file
                HttpURLConnection uploadConnection = (HttpURLConnection) new URL(apiEndpoint).openConnection();

                logger.info("Opened URL connection");

                // Configure the connection headers
                uploadConnection.setDoOutput(true);
                uploadConnection.setChunkedStreamingMode(4096);
                uploadConnection.setRequestMethod("POST");
                uploadConnection.addRequestProperty("Content-Type", "application/octet-stream");
                uploadConnection.addRequestProperty("Content-Length", String.valueOf(uploadFileSize));
                uploadConnection.addRequestProperty("Content-Id", String.valueOf(mediaItemId));
                uploadConnection.addRequestProperty("Authorization", "Basic " +
                        Base64.getEncoder().encodeToString((username + ":" + password).getBytes()));

                logger.info("Configured connection");

                // Create the channels used to upload the file
                WritableByteChannel uploadDataChannel = Channels.newChannel(uploadConnection.getOutputStream());
                FileChannel uploadFileChannel = new FileInputStream(tempFilePath).getChannel();

                logger.info("Created channels");

                // Upload the file
                long uploadProgress = 0;
                while (uploadFileChannel.transferTo(uploadFileChannel.size(), 1024, uploadDataChannel) > 0) {
                    uploadProgress += 1024;
                    logger.info("Uploading file: " + uploadProgress);
                    currentWorkItem.progress = "uploading file: " + decimalFormatter.format((((double) uploadProgress / uploadFileSize) * 100)) + "%";
                }

                // Update the progress of the encoding
                currentWorkItem.progress = "cleaning up";

                // Delete both files from the temp folder
                try {
                    Files.deleteIfExists(Paths.get(outputFilePath));
                    Files.deleteIfExists(Paths.get(tempFilePath));
                } catch (IOException e) {
                    workService.delete(currentWorkItem.id);
                    isNotEncoding = true;
                    return;
                }

                // Delete the current work item
                workService.delete(currentWorkItem.id);

                // Mark that the encoding has been finished
                isNotEncoding = true;
            } catch (Exception e1) {
                e1.printStackTrace();

                // Update the work item with the error state and end the encoding
                if (currentWorkItem.id != null) {
                    workService.delete(currentWorkItem.id);
                }
                isNotEncoding = true;

                // Attempt to delete work files
                try {
                    Files.deleteIfExists(Paths.get(outputFilePath));
                    Files.deleteIfExists(Paths.get(tempFilePath));
                    Files.deleteIfExists(Paths.get(finalPath));
                } catch (Exception e2) {
                    logger.error(e2);
                }

                // Log the error
                logger.error(e1);
            }
        }
    }

    private WorkItem getNextJob() {
        // Check to make sure there are no previous jobs that this
        // encoding agent was working on that are still in the database.
        // If there are, it likely means the encoder never finished, so the
        // encoder should re-attempt the encoding process.
        var databaseProcesses = workService.get();
        if (!databaseProcesses.isEmpty()) {
            for (WorkItem item : databaseProcesses) {
                if (item.workerAgentName.equals(workerName)) {
                    if (item.mediaType.equals("movie")) {
                        // Fetch the movie from the API
                        var movieInfo = movieService.get(item.mediaId);

                        // If the movie has not already been optimized, retry the
                        // optimization of this item
                        if (!movieInfo.isOptimized) {
                            return item;
                        }
                    } else if (item.mediaType.equals("episode")) {
                        // Fetch the episode from the API
                        var episodeInfo = episodeService.get(item.mediaId);

                        // If the episode has not already been optimized, retry the
                        // optimization of this item
                        if (!episodeInfo.isOptimized) {
                            return item;
                        }
                    }
                }
            }
        }

        // Create a new, blank work item
        var nextWorkItem = new WorkItem();

        // Fetch the next encoding work assignment from the queue
        var queueItem = queueService.next();

        // Ensure that a work assignment was given, otherwise cancel
        if (queueItem == null) {
            return null;
        }

        // Create a new work item for the media file
        nextWorkItem.progress = "loading media file";
        nextWorkItem.workerAgentName = workerName;
        nextWorkItem.mediaType = queueItem.mediaType;
        nextWorkItem.mediaId = queueItem.mediaId;
        nextWorkItem.id = workService.create(nextWorkItem);

        // Delete the queue item
        queueService.delete(queueItem.id);

        // Return the built work item
        return nextWorkItem;
    }

    private boolean copyMedia(String source, String destination) {
        boolean failed = false;

        try {
            // Copy the file to the destination
            Files.copy(
                    Paths.get(source),
                    Paths.get(destination),
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (Exception e) {
            failed = true;
            logger.error(e);
        }

        return failed;
    }

    public boolean moveMedia(String source, String destination) {
        boolean success;

        // If the file exists and the overwrite flag is false, then do not write the file
        if (Files.exists(Paths.get(destination))) {
            success = false;
        } else {
            try {
                success = new File(source).renameTo(new File(destination));
            } catch (Exception e) {
                e.printStackTrace();
                success = false;
            }
        }

        return success;
    }

    public void cleanTempFolder(@Observes StartupEvent startupEvent) {
        // Collect a list of media files to delete
        Collection<File> mediaFiles = FileUtils.listFiles(new File(tempFolder), FileType.mediaFileExtensions, false);

        // Remove any directories and hidden files from the list
        mediaFiles.removeIf(File::isDirectory);
        mediaFiles.removeIf(File::isHidden);

        // Delete files in the temp folder
        mediaFiles.forEach(file -> {
            try {
                Files.deleteIfExists(file.getAbsoluteFile().toPath());
            } catch (IOException e) {
                logger.error(e);
            }
        });
    }
}
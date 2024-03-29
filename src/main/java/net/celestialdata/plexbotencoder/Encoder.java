package net.celestialdata.plexbotencoder;

import com.github.kokorin.jaffree.JaffreeException;
import com.github.kokorin.jaffree.LogLevel;
import com.github.kokorin.jaffree.ffmpeg.*;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import net.celestialdata.plexbotencoder.clients.models.Episode;
import net.celestialdata.plexbotencoder.clients.models.HistoryItem;
import net.celestialdata.plexbotencoder.clients.models.Movie;
import net.celestialdata.plexbotencoder.clients.models.WorkItem;
import net.celestialdata.plexbotencoder.clients.services.*;
import net.celestialdata.plexbotencoder.utilities.FileType;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.SystemUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class Encoder {
    private boolean isNotEncoding = true;
    private WorkItem currentWorkItem = new WorkItem();
    private final DecimalFormat decimalFormatter = new DecimalFormat("#0.00");

    private static final Logger logger = Logger.getLogger(Encoder.class);

    private int failCount = 0;

    @ConfigProperty(name = "AppSettings.workerName")
    String workerName;

    @ConfigProperty(name = "AppSettings.crf")
    String crf;

    @ConfigProperty(name = "AppSettings.accelerationHardware")
    String accelerationHardware;

    @ConfigProperty(name = "AppSettings.tempFolder")
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

    @Inject
    @RestClient
    HistoryService historyService;

    @Scheduled(every = "3s", delay = 10, delayUnit = TimeUnit.SECONDS)
    public void updateProgress() {
        try {
            if (!isNotEncoding) {
                workService.update(currentWorkItem.id, currentWorkItem.progress);
            }
        } catch (WebApplicationException e) {
            logger.warn("Failed to updated status, server returned a " + e.getResponse().getStatus() + " error code");
        }
    }

    @Scheduled(every = "1m", delay = 10, delayUnit = TimeUnit.SECONDS)
    public void fetchWork() {
        if (isNotEncoding) {
            var tempFilePath = "";
            var outputFilePath = "";
            var itemFileExtension = "";

            // If the fail count is over 20, then exit the application with a failed status
            // so the service managers can restart it
            if (failCount == 20) {
                PlexbotEncoder.setExitCode(1);
                Quarkus.asyncExit();
                return;
            }

            try {
                logger.info("Fetching a new job");

                // Fetch the next job to work on
                try {
                    currentWorkItem = getNextJob();
                } catch (WebApplicationException e) {
                    logger.error("Failed to fetch job, server returned a " + e.getResponse().getStatus() + " error code");
                    failCount += 1;
                    isNotEncoding = true;
                    return;
                }

                // Ensure that there is actually a job to work on
                if (currentWorkItem == null) {
                    isNotEncoding = true;
                    return;
                }

                logger.info("Fetched " + currentWorkItem.mediaType + " " + currentWorkItem.mediaId + " from the queue");

                // Mark that the encoding process has started
                isNotEncoding = false;
                currentWorkItem.progress = "loading media";

                // Fetch the media file based on the type contained in the queue item
                var mediaItem = currentWorkItem.mediaType.equals("movie") ?
                        movieService.get(currentWorkItem.mediaId) : episodeService.get(currentWorkItem.mediaId);

                // Use the right API to get the download connection of the file based on its type
                Response downloadResponse = null;
                if (mediaItem instanceof Movie) {
                    downloadResponse = movieService.downloadFile(((Movie) mediaItem).id);
                    itemFileExtension = ((Movie) mediaItem).filetype;
                } else if (mediaItem != null) {
                    downloadResponse = episodeService.downloadFile(((Episode) mediaItem).id);
                    itemFileExtension = ((Episode) mediaItem).filetype;
                }

                // Ensure it was able to get a download connection otherwise cancel
                if (downloadResponse == null) {
                    workService.delete(currentWorkItem.id);
                    addHistoryItem(currentWorkItem.mediaId, currentWorkItem.mediaType, "Failed - unable to initiate download");
                    failCount += 1;
                    isNotEncoding = true;
                    return;
                }

                // Build the destination path for this file
                tempFilePath = tempFolder + currentWorkItem.mediaId + "-old." + itemFileExtension;

                logger.info("Downloading " + currentWorkItem.mediaType + " " + currentWorkItem.mediaId);

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
                logger.info("Download finished, gathering file information");

                // Generate the output filepath
                outputFilePath = tempFolder + currentWorkItem.mediaId + ".mkv";

                // Determine which encoder to use
                var encoder = "libx265";
                if (SystemUtils.IS_OS_WINDOWS && accelerationHardware.equalsIgnoreCase("nvidia")) {
                    encoder = "hevc_nvenc";
                } else if (SystemUtils.IS_OS_WINDOWS && accelerationHardware.equalsIgnoreCase("amd")) {
                    encoder = "hevc_amf";
                }

                logger.info("Encoding " + tempFilePath + " into " + outputFilePath);

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
                    addHistoryItem(currentWorkItem.mediaId, currentWorkItem.mediaType, "Failed - encoding failure: " + e.getCause());

                    // Attempt to delete work files
                    try {
                        Files.deleteIfExists(Paths.get(outputFilePath));
                        Files.deleteIfExists(Paths.get(tempFilePath));
                    } catch (Exception e2) {
                        logger.error(e2);
                    }

                    failCount += 1;
                    isNotEncoding = true;
                    return;
                }

                logger.info("Finished encoding " + outputFilePath);
                logger.info("Uploading " + outputFilePath);

                // Upload the file
                currentWorkItem.progress = "uploading";
                if (mediaItem instanceof Movie) {
                    movieService.uploadFile(((Movie) mediaItem).id, new FileInputStream(outputFilePath));
                } else {
                    episodeService.uploadFile(((Episode) mediaItem).id, new FileInputStream(outputFilePath));
                }

                // Update the progress of the encoding
                currentWorkItem.progress = "cleaning up";
                logger.info("Upload finished");

                // Delete both files from the temp folder
                try {
                    Files.deleteIfExists(Paths.get(outputFilePath));
                    Files.deleteIfExists(Paths.get(tempFilePath));
                } catch (IOException e) {
                    logger.warn("There was an error deleting one of the temporary files. Please make sure the work folder is writable.");
                }

                // Delete the current work item
                workService.delete(currentWorkItem.id);
                addHistoryItem(currentWorkItem.mediaId, currentWorkItem.mediaType, "Completed");

                // Mark that the encoding has been finished
                failCount = 0;
                isNotEncoding = true;
                logger.info("Job completed");
            } catch (Exception e1) {
                // Update the work item with the error state and end the encoding
                if (currentWorkItem.id != null) {
                    workService.delete(currentWorkItem.id);
                }

                if (currentWorkItem.mediaId != null && currentWorkItem.mediaType != null) {
                    addHistoryItem(currentWorkItem.mediaId, currentWorkItem.mediaType, "Failed - unknown exception: " + e1.getCause());
                }

                // Attempt to delete work files
                try {
                    Files.deleteIfExists(Paths.get(outputFilePath));
                    Files.deleteIfExists(Paths.get(tempFilePath));
                } catch (Exception e2) {
                    logger.error(e2);
                }

                // Mark that it is not encoding
                failCount += 1;
                isNotEncoding = true;

                // Log the error
                logger.error("Job failed to run", e1);
            }
        }
    }

    private void addHistoryItem(Long mediaId, String mediaType, String status) {
        HistoryItem item = new HistoryItem();
        item.mediaId = mediaId;
        item.mediaType = mediaType;
        item.encodingAgent = workerName;
        item.status = status;

        try {
            historyService.create(item);
        } catch (WebApplicationException e) {
            logger.warn("Failed to add the last job to the encoding history.");
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
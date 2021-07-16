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
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
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

    @ConfigProperty(name = "AppSettings.workerName")
    String workerName;

    @ConfigProperty(name = "AppSettings.crf")
    String crf;

    @ConfigProperty(name = "AppSettings.accelerationHardware")
    String accelerationHardware;

    @ConfigProperty(name = "FolderSettings.movieFolder")
    String movieFolder;

    @ConfigProperty(name = "FolderSettings.tvFolder")
    String tvFolder;

    @ConfigProperty(name = "FolderSettings.importFolder")
    String importFolder;

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
            workService.update(currentWorkItem.id, currentWorkItem);
        }
    }

    @Scheduled(every = "1m", delay = 10, delayUnit = TimeUnit.SECONDS)
    public void fetchWork() {
        if (isNotEncoding) {
            var itemPath = "";
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
                var mediaItem = currentWorkItem.type.equals("movie") ?
                        movieService.get(currentWorkItem.mediaId) : episodeService.get(currentWorkItem.mediaId);

                // Generate the path to the media item
                if (mediaItem instanceof Movie) {
                    itemPath = movieFolder + ((Movie) mediaItem).folderName + "/" + ((Movie) mediaItem).filename;
                    itemFileExtension = ((Movie) mediaItem).filetype;
                } else if (mediaItem != null) {
                    itemPath = tvFolder + ((Episode) mediaItem).show.foldername + "/Season " + ((Episode) mediaItem).season + "/" + ((Episode) mediaItem).filename;
                    itemFileExtension = ((Episode) mediaItem).filetype;
                }

                // Generate the destination path for the temp media item
                tempFilePath = tempFolder + currentWorkItem.mediaId + "-old." + itemFileExtension;

                // Generate the output filepath
                outputFilePath = tempFolder + currentWorkItem.mediaId + ".mkv";

                // Ensure that the generated file path is not empty, cancel if it is
                if (itemPath.isBlank()) {
                    workService.delete(currentWorkItem.id);
                    isNotEncoding = true;
                    return;
                }

                // Ensure the media item exists, cancel if it does not
                if (Files.notExists(Paths.get(itemPath))) {
                    workService.delete(currentWorkItem.id);
                    isNotEncoding = true;
                    return;
                }

                // Copy media item to the temp folder and ensure it was successful
                if (copyMedia(itemPath, tempFilePath)) {
                    workService.delete(currentWorkItem.id);
                    isNotEncoding = true;
                    return;
                }

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

                // Generate the path for the final file being moved to the import folder
                finalPath = currentWorkItem.type.equals("movie") ?
                        importFolder + "movies/" + currentWorkItem.mediaId + ".mkv" :
                        importFolder + "episodes/" + currentWorkItem.mediaId + ".mkv";

                // Update the progress of the encoding
                currentWorkItem.progress = "cleaning up";

                // Copy the media file to the import folder
                if (copyMedia(outputFilePath, finalPath)) {
                    workService.delete(currentWorkItem.id);
                    isNotEncoding = true;
                    return;
                }

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
                // Update the work item with the error state and end the encoding
                workService.delete(currentWorkItem.id);
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
        // If there are, it likely means the encoder never finished so it should
        // re-attempt the encoding process.
        var databaseProcesses = workService.get();
        if (!databaseProcesses.isEmpty()) {
            for (WorkItem item : databaseProcesses) {
                if (item.workerAgentName.equals(workerName)) {
                    if (item.type.equals("movie")) {
                        // Fetch the movie from the API
                        var movieInfo = movieService.get(item.mediaId);

                        // If the movie has not already been optimized, retry the
                        // optimization of this item
                        if (!movieInfo.isOptimized) {
                            return item;
                        }
                    } else if (item.type.equals("episode")) {
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
        nextWorkItem.type = queueItem.type;
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


    public void cleanTempFolder(@Observes StartupEvent startupEvent) {
        // Create the array of media file extensions to look for
        String[] mediaFileExtensions = {
                FileType.AVI.getTypeString(),
                FileType.DIVX.getTypeString(),
                FileType.FLV.getTypeString(),
                FileType.M4V.getTypeString(),
                FileType.MKV.getTypeString(),
                FileType.MP4.getTypeString(),
                FileType.MPEG.getTypeString(),
                FileType.MPG.getTypeString(),
                FileType.WMV.getTypeString()
        };

        // Collect a list of media files to delete
        Collection<File> mediaFiles = FileUtils.listFiles(new File(tempFolder), mediaFileExtensions, false);

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
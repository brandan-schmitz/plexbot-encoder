package net.celestialdata.plexbotencoder;

import com.github.kokorin.jaffree.LogLevel;
import com.github.kokorin.jaffree.ffmpeg.*;
import io.quarkus.scheduler.Scheduled;
import net.celestialdata.plexbotencoder.clients.models.Episode;
import net.celestialdata.plexbotencoder.clients.models.Movie;
import net.celestialdata.plexbotencoder.clients.models.WorkItem;
import net.celestialdata.plexbotencoder.clients.services.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.DecimalFormat;
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

    @Scheduled(every = "15m", delay = 10, delayUnit = TimeUnit.SECONDS)
    public void fetchWork() {
        if (isNotEncoding) {
            try {
                // Create a new, blank work item
                currentWorkItem = new WorkItem();

                // Fetch the next encoding work assignment from the queue
                var newWorkAssignment = queueService.next();

                // Ensure that a work assignment was given, otherwise cancel
                if (newWorkAssignment == null) {
                    isNotEncoding = true;
                    return;
                }

                // Create a new work item for the media file
                currentWorkItem.progress = "loading media file";
                currentWorkItem.workerAgentName = workerName;
                currentWorkItem.type = newWorkAssignment.type;
                currentWorkItem.mediaId = newWorkAssignment.mediaId;
                currentWorkItem.id = workService.create(currentWorkItem);

                // Mark that the encoding process has started
                isNotEncoding = false;

                // Fetch the media file based on the type contained in the queue item
                var mediaItem = newWorkAssignment.type.equals("movie") ?
                        movieService.get(newWorkAssignment.mediaId) : episodeService.get(newWorkAssignment.mediaId);

                // Generate the path to the media item
                var itemPath = "";
                var itemFileExtension = "";
                var mediaTitle = "";
                if (mediaItem instanceof Movie) {
                    itemPath = movieFolder + ((Movie) mediaItem).folderName + "/" + ((Movie) mediaItem).filename;
                    itemFileExtension = ((Movie) mediaItem).filetype;
                    mediaTitle = ((Movie) mediaItem).title;
                } else if (mediaItem != null) {
                    itemPath = tvFolder + ((Episode) mediaItem).show.foldername + "/Season " + ((Episode) mediaItem).season + "/" + ((Episode) mediaItem).filename;
                    itemFileExtension = ((Episode) mediaItem).filetype;
                    mediaTitle = ((Episode) mediaItem).title;
                }

                // Generate the destination path for the temp media item
                var tempFilePath = tempFolder + newWorkAssignment.mediaId + "-old." + itemFileExtension;

                // Generate the output filepath
                var outputFilePath = tempFolder + newWorkAssignment.mediaId + ".mkv";

                // Ensure that the generated file path is not empty, cancel if it is
                if (itemPath.isBlank()) {
                    currentWorkItem.progress = "error (path generation error)";
                    workService.update(currentWorkItem.id, currentWorkItem);
                    isNotEncoding = true;
                    return;
                }

                // Ensure the media item exists, cancel if it does
                if (Files.notExists(Paths.get(itemPath))) {
                    currentWorkItem.progress = "error (missing file)";
                    workService.update(currentWorkItem.id, currentWorkItem);
                    isNotEncoding = true;
                    return;
                }

                // Delete the queue item
                queueService.delete(newWorkAssignment.id);

                // Copy media item to the temp folder and ensure it was successful
                if (copyMedia(itemPath, tempFilePath)) {
                    currentWorkItem.progress = "error (file copy failed)";
                    workService.update(currentWorkItem.id, currentWorkItem);
                    isNotEncoding = true;
                    return;
                }

                // Get the media duration
                final AtomicLong duration = new AtomicLong();
                FFmpeg.atPath()
                        .addInput(UrlInput.fromUrl(tempFilePath))
                        .setOverwriteOutput(true)
                        .addOutput(new NullOutput())
                        .setProgressListener(progress -> duration.set(progress.getTimeMillis()))
                        .execute();

                // Build the encoding process
                FFmpeg.atPath()
                        .addInput(UrlInput.fromUrl(tempFilePath))
                        .addArguments("-c:v", "libx265")
                        .addArguments("-crf", "26")
                        .addArguments("-preset", "medium")
                        .addArguments("-c:a", "copy")
                        .addArguments("-c:s", "copy")
                        .addArguments("-metadata", "title=\"" + mediaTitle + "\"")
                        .setOverwriteOutput(true)
                        .setLogLevel(LogLevel.ERROR)
                        .addOutput(UrlOutput.toUrl(outputFilePath))
                        .setProgressListener(fFmpegProgress -> currentWorkItem.progress =
                                decimalFormatter.format(100. * fFmpegProgress.getTimeMillis() / duration.get()) + "%")
                        .execute();

                // Generate the path for the final file being moved to the import folder
                var finalPath = newWorkAssignment.type.equals("movie") ?
                        importFolder + "movies/" + newWorkAssignment.mediaId + ".mkv" :
                        importFolder + "episodes/" + newWorkAssignment.mediaId + ".mkv";

                // Copy the media file to the import folder
                if (copyMedia(outputFilePath, finalPath)) {
                    currentWorkItem.progress = "error (final file copy failed)";
                    workService.update(currentWorkItem.id, currentWorkItem);
                    isNotEncoding = true;
                    return;
                }

                // Update the progress of the encoding
                currentWorkItem.progress = "cleaning up";

                // Delete both files from the temp folder
                try {
                    Files.delete(Paths.get(outputFilePath));
                    Files.delete(Paths.get(tempFilePath));
                } catch (IOException e) {
                    currentWorkItem.progress = "error (failed deleting files)";
                    workService.update(currentWorkItem.id, currentWorkItem);
                    isNotEncoding = true;
                    return;
                }

                // Delete the current work item
                workService.delete(currentWorkItem.id);

                // Mark that the encoding has been finished
                isNotEncoding = true;
            } catch (Exception e) {
                // Update the work item with the error state and end the encoding
                currentWorkItem.progress = "error (unknown error)";
                workService.update(currentWorkItem.id, currentWorkItem);
                isNotEncoding = true;

                // Log the error
                logger.error(e);
            }
        }
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
}
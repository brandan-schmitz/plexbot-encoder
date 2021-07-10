package net.celestialdata.plexbotencoder.utilities;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.StartupEvent;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import java.nio.file.Files;
import java.nio.file.Paths;

@ApplicationScoped
public class LifecycleController {

    private static final Logger LOGGER = Logger.getLogger(LifecycleController.class);

    void start(@Observes StartupEvent event) {
        if (!LaunchMode.current().isDevOrTest() && Files.notExists(Paths.get("config/application.yaml").toAbsolutePath())) {
            var configSample = getClass().getClassLoader().getResourceAsStream("config.sample");
            if (configSample != null) {
                try {
                    if (Files.notExists(Paths.get("config").toAbsolutePath()) || !Files.isDirectory(Paths.get("config").toAbsolutePath())) {
                        Files.createDirectory(Paths.get("config").toAbsolutePath());
                    }

                    Files.copy(configSample, Paths.get("config/application.yaml").toAbsolutePath());
                    LOGGER.fatal("The encoder configuration file does not exist, hover a sample configuration file has been provided for you. " +
                            "Please edit the created configuration file located at " + Paths.get("config/application.yaml").toAbsolutePath().toString() +
                            " to use your own settings as the default settings in this sample file WILL NOT WORK.");
                    Quarkus.asyncExit(1);
                } catch (Exception e) {
                    LOGGER.fatal("The encoder configuration file does not exist and the bot was unable to create a sample file on your filesystem. " +
                            "Please check the plexbot code repo for a sample configuration file then place it within a directory called \"config\" " +
                            "with the name \"application.yaml\" in the base folder where you installed the application.");
                    Quarkus.asyncExit(1);
                }
            } else {
                LOGGER.fatal("The encoder configuration file does not exist and the bot was unable to locate the sample file within the application. " +
                        "Please check the plexbot code repo for a sample configuration file then place it within a directory called \"config\" " +
                        "with the name \"application.yaml\" in the base folder where you installed the application.");
                Quarkus.asyncExit(1);
            }
        }
    }
}

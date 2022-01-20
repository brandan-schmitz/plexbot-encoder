package net.celestialdata.plexbotencoder.clients.services;

import net.celestialdata.plexbotencoder.clients.AuthorizationHeaderFactory;
import net.celestialdata.plexbotencoder.clients.models.HistoryItem;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.rest.client.annotation.RegisterClientHeaders;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import javax.inject.Singleton;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;

@Singleton
@Path("/api/v1/encoding/history")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RegisterRestClient(configKey = "AppSettings.apiAddress")
@RegisterClientHeaders(AuthorizationHeaderFactory.class)
public interface HistoryService {

    @POST
    @Retry()
    int create(HistoryItem historyItem);
}
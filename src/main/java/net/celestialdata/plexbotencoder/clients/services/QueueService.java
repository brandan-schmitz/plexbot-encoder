package net.celestialdata.plexbotencoder.clients.services;

import net.celestialdata.plexbotencoder.clients.AuthorizationHeaderFactory;
import net.celestialdata.plexbotencoder.clients.models.QueueItem;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.rest.client.annotation.RegisterClientHeaders;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import javax.inject.Singleton;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;

@Singleton
@Path("/api/v1/encoding/queue")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RegisterRestClient(configKey = "AppSettings.apiAddress")
@RegisterClientHeaders(AuthorizationHeaderFactory.class)
public interface QueueService {

    @GET
    @Retry()
    @Path("/next")
    QueueItem next();

    @GET
    @Retry()
    @Path("/{id}")
    QueueItem get(@PathParam("id") int id);

    @DELETE
    @Retry()
    @Path("/{id}")
    void delete(@PathParam("id") int id);
}
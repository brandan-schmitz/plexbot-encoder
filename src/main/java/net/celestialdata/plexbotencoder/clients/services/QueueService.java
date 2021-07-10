package net.celestialdata.plexbotencoder.clients.services;

import net.celestialdata.plexbotencoder.clients.models.QueueItem;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import javax.inject.Singleton;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;

@Singleton
@Path("/encoding/queue")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RegisterRestClient(configKey = "AppSettings.apiAddress")
public interface QueueService {

    @GET
    @Path("/next")
    QueueItem next();

    @GET
    @Path("/{id}")
    QueueItem get(@PathParam("id") int id);

    @DELETE
    @Path("/{id}")
    void delete(@PathParam("id") int id);
}
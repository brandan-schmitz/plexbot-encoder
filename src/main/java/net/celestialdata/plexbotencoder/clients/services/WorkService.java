package net.celestialdata.plexbotencoder.clients.services;

import net.celestialdata.plexbotencoder.clients.AuthorizationHeaderFactory;
import net.celestialdata.plexbotencoder.clients.models.WorkItem;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.rest.client.annotation.RegisterClientHeaders;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import javax.inject.Singleton;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.util.List;

@Singleton
@Path("/api/v1/encoding/work")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RegisterRestClient(configKey = "AppSettings.apiAddress")
@RegisterClientHeaders(AuthorizationHeaderFactory.class)
public interface WorkService {

    @GET
    @Retry()
    List<WorkItem> get();

    @GET
    @Retry()
    @Path("/{id}")
    WorkItem get(@PathParam("id") int id);

    @POST
    @Retry()
    int create(WorkItem workItem);

    @PUT
    @Retry()
    @Path("/{id}")
    WorkItem update(@PathParam("id") int id, @QueryParam("progress") String progress);

    @DELETE
    @Retry()
    @Path("/{id}")
    void delete(@PathParam("id") int id);
}